/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.HttpConnection
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

import org.aio.core.api.FlowTube;
import org.aio.core.common.CoreUtils;
import org.aio.core.common.Demand;
import org.aio.core.util.concurrent.SequentialScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

public abstract class TcpConnection implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(TcpConnection.class);

    private final InetSocketAddress address;
    private final TcpEndpoint tcpServerOrClient;
    private volatile Throwable cause;
    private final TcpTubeSubscriber subscriber;

    volatile boolean closed;

    TcpConnection(InetSocketAddress address, TcpEndpoint tcpServerOrClient) {
        this.address = address;
        this.tcpServerOrClient = tcpServerOrClient;
        subscriber = new TcpTubeSubscriber(tcpServerOrClient);
    }

    TcpEndpoint getServerOrClient() {
        return tcpServerOrClient;
    }

    abstract SocketChannel channel();

    final InetSocketAddress address() {
        return address;
    }

    /**
     * Finishes the connection phase.
     *
     * Returns a CompletableFuture that completes when any additional,
     * type specific, setup has been done. Must be called after connectAsync.*/
    public abstract CompletableFuture<Void> finishConnect();

    /** Tells whether, or not, this connection is connected to its destination. */
    abstract boolean isConnected();

    /** Tells whether, or not, this connection is secure ( over SSL ) */
    abstract boolean isSecure();

    /**
     * Closes this connection, todo by returning the socket to its connection pool ?
     */
    @Override
    public abstract void close();

    abstract FlowTube<List<ByteBuffer>, List<ByteBuffer>> getConnectionFlow();

    interface TcpPublisher extends FlowTube.TubePublisher<List<ByteBuffer>> {
        void enqueue(List<ByteBuffer> buffers) throws IOException;
        void signalEnqueued() throws IOException;
    }

    /**
     * Returns the TCP getPublisher associated with this connection.  May be null
     * if invoked before connecting.
     */
    abstract TcpPublisher getPublisher();

    TcpTubeSubscriber getSubscriber() {
        return subscriber;
    }

    static TcpConnection createConnection(InetSocketAddress addr,
                                              TcpServerImpl server,
                                              SocketChannel socketChannel,
                                              boolean secure) {
//        if (!secure) {
            return createPlainConnection(addr, server, socketChannel);
//        } else {  // secure
//            return createSSLConnection(addr, proxy, alpn, request, server);
//        }
    }

    private static TcpConnection createPlainConnection(InetSocketAddress addr, TcpServerImpl server, SocketChannel socketChannel) {
        return new PlainTcpConnection(addr, server, socketChannel);
    }

    /**
     * A getPublisher that makes it possible to publish (write) ordered (normal
     * priority) buffers downstream.
     */
    final class PlainTcpPublisher implements TcpPublisher {
        private final Logger logger = LoggerFactory.getLogger(PlainTcpPublisher.class);

        final Object reading;
        PlainTcpPublisher() {
            this(new Object());
        }
        PlainTcpPublisher(Object readingLock) {
            this.reading = readingLock;
        }
        final ConcurrentLinkedDeque<List<ByteBuffer>> queue = new ConcurrentLinkedDeque<>();
        volatile Flow.Subscriber<? super List<ByteBuffer>> subscriber;
        volatile TcpWriteSubscription subscription;
        final SequentialScheduler writeScheduler =
                new SequentialScheduler(this::flushTask);
        @Override
        public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
            synchronized (reading) {
                //assert this.subscription == null;
                //assert this.subscriber == null;
                if (subscription == null) {
                    subscription = new TcpWriteSubscription();
                }
                this.subscriber = subscriber;
            }
            // TODO: should we do this in the flow?
            subscriber.onSubscribe(subscription);
            signal();
        }

        void flushTask(SequentialScheduler.DeferredCompleter completer) {
            try {
                TcpWriteSubscription sub = subscription;
                if (sub != null) sub.flush();
            } finally {
                completer.complete();
            }
        }

        void signal() {
            writeScheduler.runOrSchedule();
        }

        final class TcpWriteSubscription implements Flow.Subscription {
            final Demand demand = new Demand();

            @Override
            public void request(long n) {
                if (n <= 0) throw new IllegalArgumentException("non-positive request");
                demand.increase(n);
                if (logger.isDebugEnabled())
                    logger.debug("TcpPublisher: got request of {} from {}", n,
                            getConnectionFlow());
                writeScheduler.runOrSchedule();
            }

            @Override
            public void cancel() {
                if (logger.isDebugEnabled())
                    logger.debug("TcpPublisher: cancelled by {}", getConnectionFlow());
            }

            private boolean isEmpty() {
                return queue.isEmpty();
            }

            private List<ByteBuffer> poll() {
                return queue.poll();
            }

            void flush() {
                while (!isEmpty() && demand.tryDecrement()) {
                    List<ByteBuffer> elem = poll();
                    if (logger.isDebugEnabled())
                        logger.debug("TcpPublisher: sending {} bytes ({} buffers) to {}",
                                CoreUtils.remaining(elem),
                                elem.size(),
                                getConnectionFlow());
                    subscriber.onNext(elem);
                }
            }
        }

        @Override
        public void enqueue(List<ByteBuffer> buffers) {
            queue.add(buffers);
            int bytes = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            logger.debug("added {} bytes to the write queue", bytes);
        }

        @Override
        public void signalEnqueued() {
            logger.debug("signalling the getPublisher of the write queue");
            signal();
        }
    }

    private String dbgTag;
    final String dbgString() {
        FlowTube<List<ByteBuffer>, List<ByteBuffer>> flow = getConnectionFlow();
        String tag = dbgTag;
        if (tag == null && flow != null) {
            dbgTag = tag = this.getClass().getSimpleName() + "(" + flow + ")";
        } else if (tag == null) {
            tag = this.getClass().getSimpleName() + "(?)";
        }
        return tag;
    }

    @Override
    public String toString() {
        return "TcpConnection: " + channel().toString();
    }

    private void asyncReceive(ByteBuffer buffer) {
        // Note: asyncReceive is only called from the TcpTubeSubscriber
        //       sequential scheduler.
        try {
            ByteBuffer b = buffer;
            // the TcpTubeSubscriber scheduler ensures that the order of incoming
            // buffers is preserved.
            // todo must be first Handler in Stages
//            framesController.processReceivedData(buffer);
        } catch (Throwable e) {
            String msg = CoreUtils.stackTrace(e);
            logger.trace(msg);
            shutdown(e);
        }
    }

    private void shutdown(Throwable t) {
        if (logger.isDebugEnabled()) logger.debug("Shutting down tcp (closed="+closed+"): " + t);
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        if (!(t instanceof EOFException)) {
            logger.error("Not a EOFException", t);
        } else if (t != null) {
            logger.error("Shutting down connection: {}", t.getMessage());
        }
        Throwable initialCause = this.cause;
        if (initialCause == null) this.cause = t;
        tcpServerOrClient.deleteConnection(this);
        close();
    }

    /**
     * A simple tube subscriber for reading from the connection flow.
     */
    final class TcpTubeSubscriber implements FlowTube.TubeSubscriber<List<ByteBuffer>> {

        private final Logger logger = LoggerFactory.getLogger(TcpTubeSubscriber.class);

        private volatile Flow.Subscription subscription;
        private volatile boolean completed;
        private volatile boolean dropped;
        private volatile Throwable error;
        private final ConcurrentLinkedQueue<ByteBuffer> queue
                = new ConcurrentLinkedQueue<>();
        private final SequentialScheduler scheduler =
                SequentialScheduler.synchronizedScheduler(this::processQueue);
        private final TcpEndpoint serverOrClient;

        TcpTubeSubscriber(TcpEndpoint serverOrClient) {
            this.serverOrClient = Objects.requireNonNull(serverOrClient);
        }

        final void processQueue() {
            try {
                while (!queue.isEmpty() && !scheduler.isStopped()) {
                    ByteBuffer buffer = queue.poll();
                    if (logger.isDebugEnabled())
                        logger.debug("sending {} to TcpConnection.asyncReceive",
                                buffer.remaining());
                    asyncReceive(buffer);
                }
            } catch (Throwable t) {
                Throwable x = error;
                if (x == null) error = t;
            } finally {
                Throwable x = error;
                if (x != null) {
                    if (logger.isDebugEnabled()) logger.debug("Stopping scheduler", x);
                    scheduler.stop();
                    shutdown(x);
                }
            }
        }

        private void runOrSchedule() {
            if (serverOrClient.isSelectorThread()) {
                scheduler.runOrSchedule(serverOrClient.theExecutor());
            } else scheduler.runOrSchedule();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            // supports being called multiple time.
            // doesn't cancel the previous subscription, since that is
            // most probably the same as the new subscription.
            assert this.subscription == null || !dropped;
            this.subscription = subscription;
            dropped = false;
            // TODO FIXME: request(1) should be done by the delegate.
            if (!completed) {
                if (logger.isDebugEnabled())
                    logger.debug("onSubscribe: requesting Long.MAX_VALUE for reading");
                subscription.request(Long.MAX_VALUE);
            } else {
                if (logger.isDebugEnabled()) logger.debug("onSubscribe: already completed");
            }
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (logger.isDebugEnabled()) logger.debug("onNext: got " + CoreUtils.remaining(item)
                    + " bytes in " + item.size() + " buffers");
            queue.addAll(item);
            runOrSchedule();
        }

        @Override
        public void onError(Throwable throwable) {
            if (logger.isDebugEnabled()) logger.debug("onError: ", throwable);
            error = throwable;
            completed = true;
            runOrSchedule();
        }

        @Override
        public void onComplete() {
            String msg = "EOF reached while reading";
            if (logger.isDebugEnabled()) logger.debug(msg);
            error = new EOFException(msg);
            completed = true;
            runOrSchedule();
        }

        @Override
        public void dropSubscription() {
            if (logger.isDebugEnabled()) logger.debug("dropSubscription");
            // we could probably set subscription to null here...
            // then we might not need the 'dropped' boolean?
            dropped = true;
        }
    }
}
