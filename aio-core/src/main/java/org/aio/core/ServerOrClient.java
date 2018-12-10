/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
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

import org.aio.core.common.CoreUtils;
import org.aio.core.common.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public abstract class ServerOrClient<T extends Chan> implements ServerOrClientAPI {

    final Logger logger = LoggerFactory.getLogger(ServerOrClient.class);

    protected final long id;
    protected final SelectorManager selmgr;
    /** A Set of, deadline first, ordered timeout events. */
    protected final TreeSet<TimeoutEvent> timeouts;

    protected ServerOrClient() {
        timeouts = new TreeSet<>();
        id = 0;
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
    protected abstract void registerEvent(AsyncEvent<T> exchange);

    /**
     * Allows an AsyncEvent to modify its getInterestOps.
     * @param event The modified event.
     */
    protected abstract void eventUpdated(AsyncEvent<T> event) throws ClosedChannelException;

    protected abstract boolean isSelectorThread();

    protected abstract DelegatingExecutor theExecutor();

    // Timer controls.
    // Timers are implemented through timed Selector.select() calls.

    protected synchronized void registerTimer(TimeoutEvent event) {
        if (logger.isTraceEnabled()) logger.trace("Registering timer {}", event);
        timeouts.add(event);
        selmgr.wakeupSelector();
    }

    protected synchronized void cancelTimer(TimeoutEvent event) {
        if (logger.isTraceEnabled()) logger.trace("Canceling timer {}", event);
        timeouts.remove(event);
    }

    // Internal methods

    protected void start() {
        selmgr.start();
    }

    // Called from the SelectorManager thread, just before exiting.
    // todo : close what needs to be closed
    protected void stop() {

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
        public DelegatingExecutor(BooleanSupplier isInSelectorThread, Executor delegate) {
            this.isInSelectorThread = isInSelectorThread;
            this.delegate = delegate;
        }

        public Executor delegate() {
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
     * Tracks multiple user level registrations associated with one NIO
     * registration (SelectionKey). In this implementation, registrations
     * are one-off and when an event is posted the registration is cancelled
     * until explicitly registered again.
     *
     * <p> No external synchronization required as this class is only used
     * by the SelectorManager thread. One of these objects required per
     * connection.
     */
    protected static class SelectorAttachment<T extends Chan> {
        final Logger logger = LoggerFactory.getLogger(SelectorAttachment.class);

        private final T chan;
        private final Selector selector;
        private final Set<AsyncEvent<T>> pending;
        private int interestOps;

        public SelectorAttachment(T chan, Selector selector) {
            this.pending = new HashSet<>();
            this.chan = chan;
            this.selector = selector;
        }

        public void register(AsyncEvent<T> e) throws ClosedChannelException {
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
                    chan.getChannel().register(selector, interestOps, this);
                } catch (Throwable x) {
                    abortPending(x);
                }
            } else if (!chan.getChannel().isOpen()) {
                abortPending(new ClosedChannelException());
            }
        }

        /**
         * Returns a Stream<AsyncEvents> containing only events that are
         * registered with the given {@code interestOps}.
         */
        public Stream<AsyncEvent<T>> events(int interestOps) {
            return pending.stream()
                    .filter(ev -> (ev.getInterestOps() & interestOps) != 0);
        }

        /**
         * Removes any events with the given {@code interestOps}, and if no
         * events remaining, cancels the associated SelectionKey.
         */
        public void resetInterestOps(int interestOps) {
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
            SelectionKey key = chan.getChannel().keyFor(selector);
            if (newOps == 0 && key != null && pending.isEmpty()) {
                key.cancel();
            } else {
                try {
                    if (key == null || !key.isValid()) {
                        throw new CancelledKeyException();
                    }
                    key.interestOps(newOps);
                    // double check after
                    if (!chan.getChannel().isOpen()) {
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

        public T getChan() {
            return chan;
        }

        public Set<AsyncEvent<T>> getPending() {
            return pending;
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

        public DefaultThreadFactory(String name, long serverID) {
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

    // Main loop for this server's selector
    private final static class SelectorManager<T extends Chan> extends Thread {

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

        public SelectorManager(ServerOrClient<T> ref) throws IOException {
            super(null, null,
                    "TcpServer-" + ref.id + "-SelectorManager",
                    0, false);
            owner = ref;
            registrations = new ArrayList<>();
            deregistrations = new ArrayList<>();
            selector = Selector.open();
        }

        @SuppressWarnings("unchecked")
        void eventUpdated(AsyncEvent<T> e) throws ClosedChannelException {
            if (Thread.currentThread() == this) {
                SelectionKey key = e.getChan().getChannel().keyFor(selector);
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

        synchronized void cancel(T e) {
            SelectionKey key = e.getChannel().keyFor(selector);
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
                        deregistrations.clear();
                        for (AsyncEvent<T> event : registrations) {
                            if (event instanceof AsyncTriggerEvent) {
                                readyList.add(event);
                                continue;
                            }
                            T chan = event.getChan();
                            SelectionKey key = null;
                            try {
                                key = chan.getChannel().keyFor(selector);
                                SelectorAttachment sa;
                                if (key == null || !key.isValid()) {
                                    if (key != null) {
                                        // key is canceled.
                                        // invoke selectNow() to purge it
                                        // before registering the new event.
                                        selector.selectNow();
                                    }
                                    sa = new SelectorAttachment(chan, selector);
                                } else {
                                    sa = (SelectorAttachment) key.attachment();
                                }
                                // may throw IOE if channel closed: that's OK
                                sa.register(event);
                                if (!chan.getChannel().isOpen()) {
                                    throw new IOException("Channel closed");
                                }
                            } catch (IOException e) {
                                if (logger.isDebugEnabled())
                                    logger.debug("Got " + e.getClass().getName()
                                            + " while handling registration events");
                                chan.getChannel().close();
                                // let the event abort deal with it
                                errorList.add(new Pair<>(event, e));
                                if (key != null) {
                                    key.cancel();
                                    selector.selectNow();
                                }
                            }
                        }
                        registrations.clear();
                        selector.selectedKeys().clear();
                    }

                    for (AsyncEvent event : readyList) {
                        assert event instanceof AsyncTriggerEvent;
                        event.handle();
                    }
                    readyList.clear();

                    for (Pair<AsyncEvent,IOException> error : errorList) {
                        // an IOException was raised and the channel closed.
                        handleEvent(error.first, error.second);
                    }
                    errorList.clear();

                    // Check whether client is still alive, and if not,
                    // gracefully stop this thread
                    if (!owner.isReferenced()) {
                        if (logger.isTraceEnabled()) logger.trace("{}: {}",
                                getName(),
                                "TcpServer no longer referenced. Exiting...");
                        return;
                    }

                    // Timeouts will have milliseconds granularity. It is important
                    // to handle them in a timely fashion.
                    long nextTimeout = owner.purgeTimeoutsAndReturnNextDeadline();
                    if (logger.isDebugEnabled())
                        logger.debug("next timeout: {}", nextTimeout);

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

                    if (logger.isDebugEnabled())
                        logger.debug("Next deadline is {}",
                                (millis == 0 ? NODEADLINE : millis));
                    //debugPrint(selector);
                    int n = selector.select(millis == 0 ? NODEADLINE : millis);
                    if (n == 0) {
                        // Check whether client is still alive, and if not,
                        // gracefully stop this thread
                        if (!owner.isReferenced()) {
                            if (logger.isTraceEnabled()) logger.trace("{}: {}",
                                    getName(),
                                    "TcpServer no longer referenced. Exiting...");
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
                            IOException ex = sa.getChan().getChannel().isOpen()
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
                            "TcpServerImpl shutting down due to fatal error", err);
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
    }
}
