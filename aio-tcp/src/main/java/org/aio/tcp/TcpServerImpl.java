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

package org.aio.tcp;

import org.aio.core.AsyncEvent;
import org.aio.core.AsyncTriggerEvent;
import org.aio.core.TimeoutEvent;
import org.aio.core.common.BufferSupplier;
import org.aio.core.common.CoreUtils;
import org.aio.core.common.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
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

/**
 * TCP server implementation.
 *
 * <p>Contains all configuration information and also
 * the selector manager thread which allows async events to be registered
 * and delivered when they occur.</p>
 * @see AsyncEvent
 */
public final class TcpServerImpl extends TcpServerOrClient implements TcpServer {

    public static final int DEFAULT_PORT = 35700;
    protected static final AtomicLong TCP_SERVER_IDS = new AtomicLong();
    final Logger logger = LoggerFactory.getLogger(TcpServerImpl.class);

    private final int port;
    private final DelegatingExecutor delegatingExecutor;
    private final boolean isDefaultExecutor;
    // Security parameters
    private final SSLContext sslContext;
    private final SSLParameters sslParams;
    private final long id;
    private final String dbgTag;

    // This reference is used to keep track of the facade TcpServer
    // that was returned to the application code.
    // It makes it possible to know when the application no longer
    // holds any reference to the TcpServer.
    // Unfortunately, this information is not enough to know when
    // to exit the SelectorManager thread. Because of the asynchronous
    // nature of the API, we also need to wait until all pending operations
    // have completed.
    private final WeakReference<TcpServerFacade> facadeRef;

    // This counter keeps track of the number of operations pending
    // on the TcpServer. The SelectorManager thread will wait
    // until there are no longer any pending operations and the
    // facadeRef is cleared before exiting.
    //
    // The pendingOperationCount is incremented every time a todo : something
    //  is invoked on the TcpServer, and is decremented when
    // the todo : something
    // object is returned to the user.
    // However, at this point, the body may not have been fully read yet.
    // This is the case when the response T is implemented as a streaming
    // subscriber (such as an InputStream).
    //
    // To take care of this issue the pendingOperationCount will additionally
    // be incremented/decremented in the following cases:
    // todo list cases for TCP
    // 1. For HTTP/2  it is incremented when a stream is added to the
    //    Http2Connection streams map, and decreased when the stream is removed
    //    from the map. This should also take care of push promises.
    // 2. For WebSocket the count is increased when creating a
    //    DetachedConnectionChannel for the socket, and decreased
    //    when the the getChan is closed.
    //    In addition, the HttpClient facade is passed to the WebSocket builder,
    //    (instead of the client implementation delegate).
    // 3. For HTTP/1.1 the count is incremented before starting to parse the body
    //    response, and decremented when the parser has reached the end of the
    //    response body flow.
    //
    // This should ensure that the selector manager thread remains alive until
    // the response has been fully received or the web socket is closed.
    private final AtomicLong pendingOperationCount = new AtomicLong();

    /**
     * This is a bit tricky:
     * 1. a TcpServerFacade has a final TcpServerImpl field.
     * 2. a TcpServerImpl has a final WeakReference<TcpServerFacade> field,
     *    where the referent is the facade created for that instance.
     * 3. We cannot just create the TcpServerFacade in the TcpServerImpl
     *    constructor, because it would be only weakly referenced and could
     *    be GC'ed before we can return it.
     * The solution is to use an instance of SingleFacadeFactory which will
     * allow the caller of new TcpServerImpl(...) to retrieve the facade
     * after the TcpServerImpl has been created.
     */
    private static final class SingleFacadeFactory {
        TcpServerFacade facade;
        TcpServerFacade createFacade(TcpServerImpl impl) {
            assert facade == null;
            return (facade = new TcpServerFacade(impl));
        }
    }

    static TcpServerFacade create(TcpServerBuilderImpl builder) {
        SingleFacadeFactory facadeFactory = new SingleFacadeFactory();
        TcpServerImpl impl = new TcpServerImpl(builder, facadeFactory);
        impl.start();
        assert facadeFactory.facade != null;
        assert impl.facadeRef.get() == facadeFactory.facade;
        return facadeFactory.facade;
    }

    public TcpServerImpl(TcpServerBuilderImpl builder, SingleFacadeFactory facadeFactory) {
        super();
        if (builder.port > 0) {
            port = builder.port;
        } else {
            port = DEFAULT_PORT;
        }
        id = TCP_SERVER_IDS.incrementAndGet();
        dbgTag = "TcpServerImpl(" + id +")";
        sslContext = builder.sslContext;
        Executor ex = builder.executor;
        if (ex == null) {
            ex = Executors.newCachedThreadPool(new DefaultThreadFactory("TcpServer", id));
            isDefaultExecutor = true;
        } else {
            isDefaultExecutor = false;
        }
        delegatingExecutor = new DelegatingExecutor(this::isSelectorThread, ex);
        facadeRef = new WeakReference<>(facadeFactory.createFacade(this));
        if (builder.sslParams == null) {
            if (builder.sslContext != null) {
                sslParams = getDefaultParams(sslContext);
            } else {
                sslParams = null;
            }
        } else {
            sslParams = builder.sslParams;
        }
        try {
            selmgr = new SelectorManager(this);
        } catch (IOException e) {
            // unlikely
            throw new UncheckedIOException(e);
        }
        selmgr.setDaemon(true);
        assert facadeRef.get() != null;
    }

    // TcpServer methods

    @Override
    public int getPort() {
        return port;
    }

    // TcpServerOrClient methods

    @Override
    public Optional<SSLContext> getSslContext() {
        return Optional.ofNullable(sslContext);
    }

    @Override
    public Optional<SSLParameters> getSslParameters() {
        return Optional.ofNullable(sslParams);
    }

    @Override
    public Optional<Executor> getExecutor() {
        return isDefaultExecutor
                ? Optional.empty()
                : Optional.of(delegatingExecutor.delegate());
    }

    @Override
    protected BufferSupplier getSSLBufferSupplier() {
        return null;
    }

    // ServerOrClient methods

    @Override
    protected void registerEvent(AsyncEvent<SocketChan> exchange) {
        selmgr.register(exchange);
    }

    @Override
    protected void eventUpdated(AsyncEvent<SocketChan> event) throws ClosedChannelException {
        assert !(event instanceof AsyncTriggerEvent);
        selmgr.eventUpdated(event);
    }

    @Override
    protected boolean isSelectorThread() {
        return Thread.currentThread() == selmgr;
    }

    @Override
    protected DelegatingExecutor theExecutor() {
        return delegatingExecutor;
    }

    // Returns the facade that was returned to the application code.
    // May be null if that facade is no longer referenced.
    final TcpServerFacade facade() {
        return facadeRef.get();
    }

    // Returns the pendingOperationCount.
    final long referenceCount() {
        return pendingOperationCount.get();
    }

    // Called by the SelectorManager thread to figure out whether it's time
    // to terminate.
    final boolean isReferenced() {
        TcpServer facade = facade();
        return facade != null || referenceCount() > 0;
    }

    String dbgString() {
        return dbgTag;
    }

    @Override
    public String toString() {
        // Used by tests to get the client's id and compute the
        // name of the SelectorManager thread.
        return super.toString() + ("(" + id + ")");
    }

    // Internal classes

    // Main loop for this server's selector
    private final static class SelectorManager extends Thread {

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
        private final List<AsyncEvent<SocketChan>> registrations;
        private final List<AsyncTriggerEvent> deregistrations;
        TcpServerImpl owner;

        public SelectorManager(TcpServerImpl ref) throws IOException {
            super(null, null,
                    "TcpServer-" + ref.id + "-SelectorManager",
                    0, false);
            owner = ref;
            registrations = new ArrayList<>();
            deregistrations = new ArrayList<>();
            selector = Selector.open();
        }

        @SuppressWarnings("unchecked")
        void eventUpdated(AsyncEvent<SocketChan> e) throws ClosedChannelException {
            if (Thread.currentThread() == this) {
                SelectionKey key = e.getChan().getChannel().keyFor(selector);
                if (key != null && key.isValid()) {
                    SelectorAttachment<SocketChan> sa = (SelectorAttachment<SocketChan>) key.attachment();
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
        synchronized void register(AsyncEvent<SocketChan> e) {
            registrations.add(e);
            selector.wakeup();
        }

        synchronized void cancel(SocketChan e) {
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
                        for (AsyncEvent<SocketChan> event : registrations) {
                            if (event instanceof AsyncTriggerEvent) {
                                readyList.add(event);
                                continue;
                            }
                            SocketChan chan = event.getChan();
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
                        SelectorAttachment<SocketChan> sa = (SelectorAttachment<SocketChan>) key.attachment();
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

    // Return all supported params
    private static SSLParameters getDefaultParams(SSLContext ctx) {
        return ctx.getSupportedSSLParameters();
    }

    /**
     * Purges ( handles ) timer events that have passed their deadline, and
     * returns the amount of time, in milliseconds, until the next earliest
     * event. A return value of 0 means that there are no events.
     */
    private long purgeTimeoutsAndReturnNextDeadline() {
        long diff = 0L;
        List<TimeoutEvent> toHandle = null;
        int remaining = 0;
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
}
