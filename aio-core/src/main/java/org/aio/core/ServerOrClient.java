/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.HttpClientImpl
 *
 * In initial Copyright below, LICENCE file refers to OpendJDK licence, a copy
 * is provided in the OPENJDK_LICENCE file that accompanied this code.
 *
 * INITIAL COPYRIGHT NOTICES AND FILE HEADER
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.aio.core;

import org.aio.core.api.ChanEvtsHandler;
import org.aio.core.api.ChanStages;
import org.aio.core.api.ServerOrClientAPI;
import org.aio.core.common.BufferSupplier;
import org.aio.core.common.CoreUtils;
import org.aio.core.common.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public abstract class ServerOrClient<T extends SelectableChan> implements ServerOrClientAPI {

    private final Logger logger = LoggerFactory.getLogger(ServerOrClient.class);

    public abstract static class Builder implements ServerOrClientAPI.Builder {
        Executor executor;

        protected void setExecutor(Executor executor) {
            this.executor = executor;
        }
    }

    /**
     * @author Fred Montariol
     */
    public abstract static class StagesConfigurer implements ServerOrClientAPI.StagesConfigurer {

        protected final ChanStagesImpl chanStages;

        public <U extends ChanEvtsHandler> StagesConfigurer(String name, U chanEvtsHandler) {
            chanStages = new ChanStagesImpl();
            chanStages.stage1(name, chanEvtsHandler);
        }

        protected <U extends ChanEvtsHandler> void setLast(String name, U chanEvtsHandler) {
            chanStages.addLast(name, chanEvtsHandler);
        }
    }

    protected final long id;
    private final SelectorManager<T> selMgr;
    /** A Set of, deadline first, ordered timeout events. */
    private final TreeSet<TimeoutEvent> timeouts;
    private final boolean isDefaultExecutor;
    private final DelegatingExecutor delegatingExecutor;
    private final ChanStagesImpl chanStages;

    /**
     * Constructor : create the SelectorManager
     *
     * @param IDS the atomic provider for ID
     */
    protected ServerOrClient(AtomicLong IDS, Builder builder, ChanStagesImpl chanStages) {
        timeouts = new TreeSet<>();
        id = IDS.incrementAndGet();
        Executor ex = builder.executor;
        if (ex == null) {
            ex = Executors.newCachedThreadPool(new DefaultThreadFactory("TcpServer", id));
            isDefaultExecutor = true;
        } else {
            isDefaultExecutor = false;
        }
        delegatingExecutor = new DelegatingExecutor(this::isSelectorThread, ex);
        try {
            selMgr = new SelectorManager<>(this);
        } catch (IOException e) {
            // unlikely
            throw new UncheckedIOException(e);
        }
        selMgr.setDaemon(true);
        this.chanStages = chanStages;
    }

    @Override
    public Optional<Executor> getExecutor() {
        return isDefaultExecutor
                ? Optional.empty()
                : Optional.of(delegatingExecutor.delegate());
    }

    @Override
    public ChanStages getStages() {
        return chanStages;
    }

    /**
     * Wait for activity on given exchange.
     * The following occurs in the SelectorManager thread.
     *
     *  1) add to selector
     *  2) If selector fires for this exchange then
     *     call AsyncEvent.handle()
     *
     * If exchange needs to change interest ops, then call registerEvent() again.
     */
    void registerEvent(AsyncEvent<T> exchange) {
        selMgr.register(exchange);
    }

    /**
     * Allows an AsyncEvent to modify its getInterestOps.
     * @param event The modified event.
     */
    void eventUpdated(AsyncEvent<T> event) throws ClosedChannelException {
        assert !(event instanceof AsyncTriggerEvent);
        selMgr.eventUpdated(event);
    }

    public boolean isSelectorThread() {
        return Thread.currentThread() == selMgr;
    }

    public final DelegatingExecutor theExecutor() {
        return delegatingExecutor;
    }

    protected abstract boolean isNotReferenced();

    // Timer controls.
    // Timers are implemented through timed Selector.select() calls.

    synchronized void registerTimer(TimeoutEvent event) {
        if (logger.isTraceEnabled()) logger.trace("Registering timer {}", event);
        timeouts.add(event);
        selMgr.wakeupSelector();
    }

    synchronized void cancelTimer(TimeoutEvent event) {
        if (logger.isTraceEnabled()) logger.trace("Canceling timer {}", event);
        timeouts.remove(event);
    }

    @Override
    public String toString() {
        // Used by tests to get the client or server 's id and compute the
        // name of the SelectorManager thread.
        return super.toString() + ("(" + id + ")");
    }

    public final String debugInterestOps(T channel) {
        try {
            SelectionKey key = channel.keyFor(selMgr.getSelector());
            if (key == null) return "channel not registered with selector";
            String keyInterestOps = key.isValid()
                    ? "key.interestOps=" + key.interestOps() : "invalid key";
            return String.format("channel registered with selector, %s, sa.interestOps=%s",
                    keyInterestOps,
                    ((SelectorAttachment)key.attachment()).getInterestOps());
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    // used for the connection window
    public int getReceiveBufferSize() {
        return CoreUtils.getIntegerNetProperty(
                "aio.receiveBufferSize",
                0 // only set the size if > 0
        );
    }

    protected void start() {
        logger.debug("starting SelectorManager thread");
        selMgr.start();
    }

    // Called from the SelectorManager thread, just before exiting.
    protected abstract void stop();

    /**
     * Purges ( handles ) timer events that have passed their deadline, and
     * returns the amount of time, in milliseconds, until the next earliest
     * event. A return value of 0 means that there are no events.
     */
    private long purgeTimeoutsAndReturnNextDeadline() {
        long diff = 0L;
        List<TimeoutEvent> toHandle = null;
        int remaining;
        // enter critical section to retrieve the timeout event to handle
        synchronized(this) {
            if (timeouts.isEmpty()) return 0L;

            Instant now = Instant.now();
            Iterator<TimeoutEvent> itr = timeouts.iterator();
            while (itr.hasNext()) {
                TimeoutEvent event = itr.next();
                diff = now.until(event.deadline(), ChronoUnit.MILLIS);
                if (diff <= 0) {
                    itr.remove();
                    toHandle = (toHandle == null) ? new ArrayList<>() : toHandle;
                    toHandle.add(event);
                } else {
                    break;
                }
            }
            remaining = timeouts.size();
        }

        // can be useful for debugging
        if (toHandle != null && logger.isTraceEnabled()) {
            logger.trace("purgeTimeoutsAndReturnNextDeadline: handling {} events, remaining {}, next deadline: {}",
                    toHandle.size(),
                    remaining,
                    (diff < 0 ? 0L : diff));
        }

        // handle timeout events out of critical section
        if (toHandle != null) {
            Throwable failed = null;
            for (TimeoutEvent event : toHandle) {
                try {
                    if (logger.isTraceEnabled()) logger.trace("Firing timer {}", event);
                    event.handle();
                } catch (Error | RuntimeException e) {
                    // Not expected. Handle remaining events then throw...
                    // If e is an OOME or SOE it might simply trigger a new
                    // error from here - but in this case there's not much we
                    // could do anyway. Just let it flow...
                    if (failed == null) failed = e;
                    else failed.addSuppressed(e);
                    if (logger.isTraceEnabled()) logger.trace("Failed to handle event {}: {}", event, e);
                }
            }
            if (failed instanceof Error) throw (Error) failed;
            if (failed instanceof RuntimeException) throw (RuntimeException) failed;
        }

        // return time to wait until next event. 0L if there's no more events.
        return diff < 0 ? 0L : diff;
    }

    /**
     * A DelegatingExecutor is an executor that delegates tasks to
     * a wrapped executor when it detects that the current thread
     * is the SelectorManager thread. If the current thread is not
     * the selector manager thread the given task is executed inline.
     */
    public final static class DelegatingExecutor implements Executor {
        private final BooleanSupplier isInSelectorThread;
        private final Executor delegate;

        DelegatingExecutor(BooleanSupplier isInSelectorThread, Executor delegate) {
            this.isInSelectorThread = isInSelectorThread;
            this.delegate = delegate;
        }

        Executor delegate() {
            return delegate;
        }

        @Override
        public void execute(Runnable command) {
            if (isInSelectorThread.getAsBoolean()) {
                delegate.execute(command);
            } else {
                command.run();
            }
        }
    }

    /**
     * SelectorAttachment is the Object attached with SelectorKey
     *
     * Tracks multiple user level registrations associated with one NIO
     * registration (SelectionKey). In this implementation, registrations
     * are one-off and when an event is posted the registration is cancelled
     * until explicitly registered again.
     *
     * <p> No external synchronization required as this class is only used
     * by the SelectorManager thread. One of these objects required per
     * connection.
     */
    static class SelectorAttachment<T extends SelectableChan> {
        final Logger logger = LoggerFactory.getLogger(SelectorAttachment.class);

        private final T chan;
        private final Selector selector;
        private final Set<AsyncEvent<T>> pending;
        private int interestOps;

        SelectorAttachment(T chan, Selector selector) {
            this.pending = new HashSet<>();
            this.chan = chan;
            this.selector = selector;
        }

        /**
         * Register the {@link AsyncEvent} :
         * 1) read {@link AsyncEvent}'s interestOps
         * 2) add AsyncEvent to pending Set
         * 3) if not already interested in this event, call
         * {@link java.nio.channels.SelectableChannel#register(Selector, int, Object)}
         *
         * @param e The event
         */
        void register(AsyncEvent<T> e) {
            int newOps = e.getInterestOps();
            // re register interest if we are not already interested
            // in the event. If the event is paused, then the pause will
            // be taken into account later when resetInterestOps is called.
            boolean reRegister = (interestOps & newOps) != newOps;
            interestOps |= newOps;
            pending.add(e);
            if (logger.isDebugEnabled())
                logger.debug("Registering {} for {} ({})", e, newOps, reRegister);
            if (reRegister) {
                // first time registration happens here also
                try {
                    chan.register(selector, interestOps, this);
                } catch (Throwable x) {
                    abortPending(x);
                }
            } else if (!chan.isOpen()) {
                abortPending(new ClosedChannelException());
            }
        }

        /**
         * Returns a Stream<AsyncEvents> containing only events that are
         * registered with the given {@code interestOps}.
         */
        Stream<AsyncEvent<T>> events(int interestOps) {
            return pending.stream()
                    .filter(ev -> (ev.getInterestOps() & interestOps) != 0);
        }

        /**
         * Removes any events with the given {@code interestOps}, and if no
         * events remaining, cancels the associated SelectionKey.
         */
        void resetInterestOps(int interestOps) {
            int newOps = 0;

            Iterator<AsyncEvent<T>> itr = pending.iterator();
            while (itr.hasNext()) {
                AsyncEvent event = itr.next();
                int evops = event.getInterestOps();
                if (event.isRepeating()) {
                    newOps |= evops;
                    continue;
                }
                if ((evops & interestOps) != 0) {
                    itr.remove();
                } else {
                    newOps |= evops;
                }
            }

            this.interestOps = newOps;
            SelectionKey key = chan.keyFor(selector);
            if (newOps == 0 && key != null && pending.isEmpty()) {
                key.cancel();
            } else {
                try {
                    if (key == null || !key.isValid()) {
                        throw new CancelledKeyException();
                    }
                    key.interestOps(newOps);
                    // double check after
                    if (!chan.isOpen()) {
                        abortPending(new ClosedChannelException());
                        return;
                    }
                    assert key.interestOps() == newOps;
                } catch (CancelledKeyException x) {
                    // channel may have been closed
                    if (logger.isDebugEnabled()) logger.debug("key cancelled for {}", chan);
                    abortPending(x);
                }
            }
        }

        void abortPending(Throwable x) {
            if (!pending.isEmpty()) {
                AsyncEvent[] evts = pending.toArray(new AsyncEvent[0]);
                pending.clear();
                IOException io = CoreUtils.getIOException(x);
                for (AsyncEvent event : evts) {
                    event.abort(io);
                }
            }
        }

        T getChan() {
            return chan;
        }

        Set<AsyncEvent<T>> getPending() {
            return pending;
        }

        int getInterestOps() {
            return interestOps;
        }
    }

    // Define the default factory as a static inner class
    // that embeds all the necessary logic to avoid
    // the risk of using a lambda that might keep a reference on the
    // TcpServer instance from which it was created (helps with heapdump
    // analysis).
    protected static final class DefaultThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger nextId = new AtomicInteger();

        DefaultThreadFactory(String name, long serverID) {
            namePrefix = name + "-" + serverID + "-Worker-";
        }

        @Override
        public Thread newThread(Runnable r) {
            String name = namePrefix + nextId.getAndIncrement();
            Thread t;
            if (System.getSecurityManager() == null) {
                t = new Thread(null, r, name, 0, false);
            } else {
                // code from jdk11, uses jdk.internal.misc.Unsafe so not callable here
//                t = InnocuousThread.newThread(name, r);
                // so use code from jdk9
                t = new Thread(null, r, name, 0, true);
            }
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Main event loop for this client or server's {@link Selector}
     */
    protected final static class SelectorManager<T extends SelectableChan> extends Thread {

        final Logger logger = LoggerFactory.getLogger(SelectorManager.class);

        // For testing purposes we have an internal System property that
        // can control the frequency at which the selector manager will wake
        // up when there are no pending operations.
        // Increasing the frequency (shorter delays) might allow the selector
        // to observe that the facade is no longer referenced and might allow
        // the selector thread to terminate more timely - for when nothing is
        // ongoing it will only check for that condition every NODEADLINE ms.
        // To avoid misuse of the property, the delay that can be specified
        // is comprised between [MIN_NODEADLINE, MAX_NODEADLINE], and its default
        // value if unspecified (or <= 0) is DEF_NODEADLINE = 3000ms
        // The property is -Daio.selectorTimeout=<millis>
        private static final int MIN_NODEADLINE = 1000; // ms
        private static final int MAX_NODEADLINE = 1000 * 1200; // ms
        private static final int DEF_NODEADLINE = 3000; // ms
        private static final long NODEADLINE; // default is DEF_NODEADLINE ms
        static {
            // ensure NODEADLINE is initialized with some valid value.
            long deadline =  CoreUtils.getIntegerProperty(
                    "aio.selectorTimeout",
                    DEF_NODEADLINE); // millis
            if (deadline <= 0) deadline = DEF_NODEADLINE;
            deadline = Math.max(deadline, MIN_NODEADLINE);
            NODEADLINE = Math.min(deadline, MAX_NODEADLINE);
        }

        private final Selector selector;
        private volatile boolean closed;
        private final List<AsyncEvent<T>> registrations;
        private final List<AsyncTriggerEvent> deregistrations;
        ServerOrClient<T> owner;

        SelectorManager(ServerOrClient<T> ref) throws IOException {
            super(null, null,
                    "ServerOrClient-" + ref.id + "-SelectorManager",
                    0, false);
            owner = ref;
            registrations = new ArrayList<>();
            deregistrations = new ArrayList<>();
            selector = Selector.open();
        }

        @SuppressWarnings("unchecked")
        void eventUpdated(AsyncEvent<T> e) {
            // if in this selector event loop thread
            if (Thread.currentThread() == this) {
                SelectionKey key = e.getChan().keyFor(selector);
                if (key != null && key.isValid()) {
                    SelectorAttachment<T> sa = (SelectorAttachment<T>) key.attachment();
                    sa.register(e);
                } else if (e.getInterestOps() != 0){
                    // We don't care about paused events.
                    // These are actually handled by
                    // SelectorAttachment::resetInterestOps later on.
                    // But if we reach here when trying to resume an
                    // event then it's better to fail fast.
                    if (logger.isDebugEnabled()) logger.debug("No key for getChan");
                    e.abort(new IOException("No key for getChan"));
                }
            } else {
                register(e);
            }
        }

        // This returns immediately. So caller not allowed to send/receive
        // on connection.
        synchronized void register(AsyncEvent<T> e) {
            registrations.add(e);
            selector.wakeup();
        }

        public synchronized void cancel(T e) {
            SelectionKey key = e.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
            selector.wakeup();
        }

        void wakeupSelector() {
            selector.wakeup();
        }

        synchronized void shutdown() {
            if (logger.isDebugEnabled()) logger.debug("{} : SelectorManager shutting down", getName());
            closed = true;
            try {
                selector.close();
            } catch (IOException ignored) {
            } finally {
                owner.stop();
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void run() {
            List<Pair<AsyncEvent,IOException>> errorList = new ArrayList<>();
            List<AsyncEvent> readyList = new ArrayList<>();
            List<Runnable> resetList = new ArrayList<>();
            try {
                if (logger.isDebugEnabled()) logger.debug("{} : starting", getName());
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (this) {
                        assert errorList.isEmpty();
                        assert readyList.isEmpty();
                        assert resetList.isEmpty();
                        for (AsyncTriggerEvent event : deregistrations) {
                            event.handle();
                        }
                        // clear deregistrations because we just handle all of them
                        deregistrations.clear();

                        for (AsyncEvent<T> event : registrations) {
                            if (event instanceof AsyncTriggerEvent) {
                                readyList.add(event);
                                continue;
                            }
                            // Get SelectableChan associated with the event
                            T chan = event.getChan();
                            SelectionKey key = null;
                            try {
                                // Retrieves the key representing the channel's registration
                                // with the given selector
                                key = chan.keyFor(selector);
                                SelectorAttachment<T> sa;
                                if (key == null || !key.isValid()) {
                                    if (key != null) {
                                        // key is canceled.
                                        // invoke selectNow() to purge it
                                        // before registering the new event.
                                        selector.selectNow();
                                    }
                                    sa = new SelectorAttachment<>(chan, selector);
                                } else {
                                    sa = (SelectorAttachment<T>) key.attachment();
                                }
                                // may throw IOE if channel closed: that's OK
                                // call SelectableChannel.register(selector, interestop, sa)
                                // with interestop from event and SelectableChannel, Selector
                                // from SelectorAttachment
                                sa.register(event);
                                if (!chan.isOpen()) {
                                    throw new IOException("Channel closed");
                                }
                            } catch (IOException e) {
                                if (logger.isDebugEnabled())
                                    logger.debug("Got {} while handling registration events",
                                            e.getClass().getName());
                                chan.close();
                                // let the event abort deal with it
                                errorList.add(new Pair<>(event, e));
                                if (key != null) {
                                    key.cancel();
                                    selector.selectNow();
                                }
                            }
                        }
                        // clear registrations because we just handle all of them
                        registrations.clear();
                        selector.selectedKeys().clear();
                    }

                    for (AsyncEvent event : readyList) {
                        assert event instanceof AsyncTriggerEvent;
                        event.handle();
                    }
                    // clear readyList because we just handle all of them
                    readyList.clear();

                    for (Pair<AsyncEvent,IOException> error : errorList) {
                        // an IOException was raised and the channel closed.
                        handleEvent(error.first, error.second);
                    }
                    // clear errorList because we just handle all of them
                    errorList.clear();

                    // Check whether serverOrClient is still alive, and if not,
                    // gracefully stop this thread
                    if (owner.isNotReferenced()) {
                        if (logger.isTraceEnabled()) logger.trace("{}: {}",
                                getName(),
                                "ServerOrClient no longer referenced. Exiting...");
                        return;
                    }

                    // Timeouts will have milliseconds granularity. It is important
                    // to handle them in a timely fashion.
                    long nextTimeout = owner.purgeTimeoutsAndReturnNextDeadline();
                    if (logger.isTraceEnabled())
                        logger.trace("Next timeout: {}", nextTimeout);

                    // Keep-alive have seconds granularity. It's not really an
                    // issue if we keep connections linger a bit more in the keep
                    // alive cache.
                    // todo connection pool is just for Http1 ?
//                    long nextExpiry = pool.purgeExpiredConnectionsAndReturnNextDeadline();
//                    if (logger.isDebugEnabled())
//                        logger.debug("next expired: {}", nextExpiry);

                    assert nextTimeout >= 0;
//                    assert nextExpiry >= 0;

                    // Don't wait for ever as it might prevent the thread to
                    // stop gracefully. millis will be 0 if no deadline was found.
                    if (nextTimeout <= 0) nextTimeout = NODEADLINE;

//                    // Clip nextExpiry at NODEADLINE limit. The default
//                    // keep alive is 1200 seconds (half an hour) - we don't
//                    // want to wait that long.
//                    if (nextExpiry <= 0) nextExpiry = NODEADLINE;
//                    else nextExpiry = Math.min(NODEADLINE, nextExpiry);

                    // takes the least of the two.
//                    long millis = Math.min(nextExpiry, nextTimeout);

                    long millis = nextTimeout;

                    if (logger.isTraceEnabled())
                        logger.trace("Next deadline is {}",
                                (millis == 0 ? NODEADLINE : millis));
                    //debugPrint(selector);
                    int n = selector.select(millis == 0 ? NODEADLINE : millis);
                    if (n == 0) {
                        // Check whether serverOrClient is still alive, and if not,
                        // gracefully stop this thread
                        if (owner.isNotReferenced()) {
                            if (logger.isTraceEnabled()) logger.trace("{}: {}",
                                    getName(),
                                    "ServerOrClient no longer referenced. Exiting...");
                            return;
                        }
                        owner.purgeTimeoutsAndReturnNextDeadline();
                        continue;
                    }

                    Set<SelectionKey> keys = selector.selectedKeys();
                    assert errorList.isEmpty();

                    for (SelectionKey key : keys) {
                        SelectorAttachment<T> sa = (SelectorAttachment<T>) key.attachment();
                        if (!key.isValid()) {
                            IOException ex = sa.getChan().isOpen()
                                    ? new IOException("Invalid key")
                                    : new ClosedChannelException();
                            sa.getPending().forEach(e -> errorList.add(new Pair<>(e,ex)));
                            sa.getPending().clear();
                            continue;
                        }

                        int eventsOccurred;
                        try {
                            eventsOccurred = key.readyOps();
                        } catch (CancelledKeyException ex) {
                            IOException io = CoreUtils.getIOException(ex);
                            sa.getPending().forEach(e -> errorList.add(new Pair<>(e,io)));
                            sa.getPending().clear();
                            continue;
                        }
                        sa.events(eventsOccurred).forEach(readyList::add);
                        resetList.add(() -> sa.resetInterestOps(eventsOccurred));
                    }

                    selector.selectNow(); // complete cancellation
                    selector.selectedKeys().clear();

                    // handle selected events
                    readyList.forEach((e) -> handleEvent(e, null));
                    readyList.clear();

                    // handle errors (closed channels etc...)
                    errorList.forEach((p) -> handleEvent(p.first, p.second));
                    errorList.clear();

                    // reset interest ops for selected channels
                    resetList.forEach(Runnable::run);
                    resetList.clear();

                }
            } catch (Throwable e) {
                if (!closed) {
                    // This terminates thread. So, better just print stack trace
                    String err = CoreUtils.stackTrace(e);
                    logger.error("{}: {}: {}", getName(),
                            "ServerOrClient shutting down due to fatal error", err);
                }
                if (logger.isDebugEnabled()) logger.debug("shutting down", e);
            } finally {
                if (logger.isDebugEnabled()) logger.debug("{} : stopping", getName());
                shutdown();
            }
        }

        /** Handles the given event. The given ioe may be null. */
        void handleEvent(AsyncEvent event, IOException ioe) {
            if (closed || ioe != null) {
                event.abort(ioe);
            } else {
                event.handle();
            }
        }

        Selector getSelector() {
            return selector;
        }
    }

    // An implementation of BufferSupplier that manages a pool of
    // maximum 3 direct byte buffers (SocketTube.MAX_BUFFERS) that
    // are used for reading encrypted bytes off the channel before
    // copying and subsequent unwrapping.
    protected static final class SSLDirectBufferSupplier<T extends SelectableChan> implements BufferSupplier {

        private final Logger logger = LoggerFactory.getLogger(SSLDirectBufferSupplier.class);

        private static final int POOL_SIZE = ChanTube.MAX_BUFFERS;
        private final ByteBuffer[] pool = new ByteBuffer[POOL_SIZE];
        private final ServerOrClient<T> serverOrClient;
        private int tail, count; // no need for volatile: only accessed in SM thread.

        public SSLDirectBufferSupplier(ServerOrClient<T> serverOrClient) {
            this.serverOrClient = Objects.requireNonNull(serverOrClient);
        }

        // Gets a buffer from the pool, or allocates a new one if needed.
        @Override
        public ByteBuffer get() {
            assert serverOrClient.isSelectorThread();
            assert tail <= POOL_SIZE : "allocate tail is " + tail;
            ByteBuffer buf;
            if (tail == 0) {
                if (logger.isDebugEnabled()) {
                    // should not appear more than ChanTube.MAX_BUFFERS
                    logger.debug("ByteBuffer.allocateDirect({})", CoreUtils.BUFSIZE);
                }
                count++;
                assert count < POOL_SIZE : "trying to allocate more than "
                        + POOL_SIZE + " buffers";
                buf = ByteBuffer.allocateDirect(CoreUtils.BUFSIZE);
            } else {
                assert tail > 0 : "non positive tail value: " + tail;
                tail--;
                buf = pool[tail];
                pool[tail] = null;
            }
            assert buf.isDirect();
            assert buf.position() == 0;
            assert buf.hasRemaining();
            assert buf.limit() == CoreUtils.BUFSIZE;
            assert tail < POOL_SIZE;
            assert tail >= 0;
            return buf;
        }

        // Returns the given buffer to the pool.
        @Override
        public void recycle(ByteBuffer buffer) {
            assert serverOrClient.isSelectorThread();
            assert buffer.isDirect();
            assert !buffer.hasRemaining();
            assert tail < POOL_SIZE : "recycle tail is " + tail;
            assert tail >= 0;
            buffer.position(0);
            buffer.limit(buffer.capacity());
            // don't fail if assertions are off. we have asserted above.
            if (tail < POOL_SIZE) {
                pool[tail] = buffer;
                tail++;
            }
            assert tail <= POOL_SIZE;
            assert tail > 0;
        }
    }
}
