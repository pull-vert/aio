/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
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

import org.aio.core.FlowTube;
import org.aio.core.common.CoreUtils;
import org.aio.core.common.Demand;
import org.aio.core.util.concurrent.SequentialScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;

public abstract class TcpConnection implements Closeable {

    protected final InetSocketAddress address;
    private final TcpServerImpl server;

    protected TcpConnection(InetSocketAddress address, TcpServerImpl server) {
        this.address = address;
        this.server = server;
    }

    public TcpServerImpl getServer() {
        return server;
    }

    abstract SocketChan getChan();

    final InetSocketAddress address() {
        return address;
    }

    /** Tells whether, or not, this connection is connected to its destination. */
    abstract boolean isConnected();

    /** Tells whether, or not, this connection is secure ( over SSL ) */
    abstract boolean isSecure();

    /**
     * Closes this connection, by returning the socket to its connection pool.
     */
    @Override
    public abstract void close();

    abstract FlowTube getConnectionFlow();

    interface TcpPublisher extends FlowTube.TubePublisher {
        void enqueue(List<ByteBuffer> buffers) throws IOException;
        void signalEnqueued() throws IOException;
    }

    /**
     * Returns the TCP publisher associated with this connection.  May be null
     * if invoked before connecting.
     */
    abstract TcpPublisher publisher();

    /**
     * A publisher that makes it possible to publish (write) ordered (normal
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
                    logger.debug("HttpPublisher: cancelled by {}", getConnectionFlow());
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
        public void enqueue(List<ByteBuffer> buffers) throws IOException {
            queue.add(buffers);
            int bytes = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            logger.debug("added {} bytes to the write queue", bytes);
        }

        @Override
        public void signalEnqueued() throws IOException {
            logger.debug("signalling the publisher of the write queue");
            signal();
        }
    }

    private String dbgTag;
    final String dbgString() {
        FlowTube flow = getConnectionFlow();
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
        return "TcpConnection: " + getChan().toString();
    }
}
