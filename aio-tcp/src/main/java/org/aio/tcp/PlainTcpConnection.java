/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.PlainHttpConnection
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
import org.aio.core.api.FlowTube;
import org.aio.core.common.CoreUtils;
import org.aio.core.util.concurrent.MinimalFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;

/**
 * Plain raw TCP connection direct to destination.
 * The connection operates in asynchronous non-blocking mode.
 * All reads and writes are done non-blocking.
 */
class PlainTcpConnection extends TcpConnection {

    private final Logger logger = LoggerFactory.getLogger(PlainTcpConnection.class);

    private final Object reading = new Object();
    protected final SocketChan chan;
    private final SocketChanTube tube; // need SocketChanTube to call signalClosed().
    private final PlainTcpPublisher writePublisher = new PlainTcpPublisher(reading);
    private volatile boolean connected;
    private boolean closed;
//    private volatile ConnectTimerEvent connectTimerEvent;  // may be null

    // should be volatile to provide proper synchronization(visibility) action

//    /**
//     * Returns a ConnectTimerEvent iff there is a connect timeout duration,
//     * otherwise null.
//     */
//    private ConnectTimerEvent newConnectTimer(Exchange<?> exchange,
//                                              CompletableFuture<Void> cf) {
//        Duration duration = client().connectTimeout().orElse(null);
//        if (duration != null) {
//            ConnectTimerEvent cte = new ConnectTimerEvent(duration, exchange, cf);
//            return cte;
//        }
//        return null;
//    }
//
//    final class ConnectTimerEvent extends TimeoutEvent {
//        private final CompletableFuture<Void> cf;
////        private final Exchange<?> exchange;
//
//        ConnectTimerEvent(Duration duration,
////                          Exchange<?> exchange,
//                          CompletableFuture<Void> cf) {
//            super(duration);
////            this.exchange = exchange;
//            this.cf = cf;
//        }
//
//        @Override
//        public void handle() {
//            if (debug.on()) {
//                debug.log("HTTP connect timed out");
//            }
//            ConnectException ce = new ConnectException("HTTP connect timed out");
//            exchange.multi.cancel(ce);
//            client().theExecutor().execute(() -> cf.completeExceptionally(ce));
//        }
//
//        @Override
//        public String toString() {
//            return "ConnectTimerEvent, " + super.toString();
//        }
//    }

    final class ConnectEvent extends AsyncEvent {
        private final CompletableFuture<Void> cf;

        ConnectEvent(CompletableFuture<Void> cf) {
            this.cf = cf;
        }

        @Override
        public SocketChan getChan() {
            return chan;
        }

        @Override
        public int getInterestOps() {
            return SelectionKey.OP_CONNECT;
        }

        @Override
        public void handle() {
            try {
                assert !connected : "Already connected";
                SocketChannel socketChannel = chan.getChannel();
                assert !socketChannel.isBlocking() : "Unexpected blocking channel";
                if (logger.isDebugEnabled())
                    logger.debug("ConnectEvent: finishing connect");
                boolean finished = socketChannel.finishConnect();
                assert finished : "Expected channel to be connected";
                if (logger.isDebugEnabled())
                    logger.debug("ConnectEvent: connect finished: {} Local addr: {}",
                              finished, socketChannel.getLocalAddress());
                // complete async since the event runs on the SelectorManager thread
                cf.completeAsync(() -> null, getServer().theExecutor());
            } catch (Throwable e) {
                Throwable t = CoreUtils.toConnectException(e);
                getServer().theExecutor().execute( () -> cf.completeExceptionally(t));
                close();
            }
        }

        @Override
        public void abort(IOException ioe) {
            getServer().theExecutor().execute( () -> cf.completeExceptionally(ioe));
            close();
        }
    }

//    @Override
//    public CompletableFuture<Void> connectAsync(Exchange<?> exchange) {
//        CompletableFuture<Void> cf = new MinimalFuture<>();
//        try {
//            assert !connected : "Already connected";
//            assert !chan.isBlocking() : "Unexpected blocking channel";
//            boolean finished;
//
//            connectTimerEvent = newConnectTimer(exchange, cf);
//            if (connectTimerEvent != null) {
//                if (debug.on())
//                    debug.log("registering connect timer: " + connectTimerEvent);
//                client().registerTimer(connectTimerEvent);
//            }
//
//            PrivilegedExceptionAction<Boolean> pa =
//                    () -> chan.connect(Utils.resolveAddress(address));
//            try {
//                 finished = AccessController.doPrivileged(pa);
//            } catch (PrivilegedActionException e) {
//               throw e.getCause();
//            }
//            if (finished) {
//                if (debug.on()) debug.log("connect finished without blocking");
//                cf.complete(null);
//            } else {
//                if (debug.on()) debug.log("registering connect event");
//                client().registerEvent(new ConnectEvent(cf));
//            }
//        } catch (Throwable throwable) {
//            cf.completeExceptionally(Utils.toConnectException(throwable));
//            try {
//                close();
//            } catch (Exception x) {
//                if (debug.on())
//                    debug.log("Failed to close channel after unsuccessful connect");
//            }
//        }
//        return cf;
//    }

    @Override
    public CompletableFuture<Void> finishConnect() {
        assert connected == false;
        if (logger.isDebugEnabled())
            logger.debug("finishConnect, setting connected=true");
        connected = true;
//        if (connectTimerEvent != null)
//            client().cancelTimer(connectTimerEvent);
        return MinimalFuture.completedFuture(null);
    }

    /**
     * Create a TCP Connection without SSL
     * Provided SocketChan must be opened before this
     *
     * @param addr
     * @param server
     * @param chan
     */
    PlainTcpConnection(InetSocketAddress addr, TcpServerImpl server, SocketChan chan) {
        super(addr, server);
        try {
            this.chan = chan;
            chan.getChannel().configureBlocking(false);
            trySetReceiveBufferSize(server.getReceiveBufferSize());
            if (logger.isDebugEnabled()) {
                int bufsize = getInitialBufferSize();
                logger.debug("Initial receive buffer size is: {}", bufsize);
            }
            chan.getChannel().setOption(StandardSocketOptions.TCP_NODELAY, true);
            // wrap the channel in a Tube for async reading and writing
            tube = new SocketChanTube(getServer(), chan, CoreUtils::getBuffer);
        } catch (IOException e) {
            throw new InternalError(e);
        }
    }

    @Override
    SocketChan getChan() {
        return chan;
    }

    @Override
    final FlowTube getConnectionFlow() {
        return tube;
    }

    private int getInitialBufferSize() {
        try {
            return chan.getChannel().getOption(StandardSocketOptions.SO_RCVBUF);
        } catch(IOException x) {
            if (logger.isDebugEnabled())
                logger.debug("Failed to get initial receive buffer size on {}", chan);
        }
        return 0;
    }

    private void trySetReceiveBufferSize(int bufsize) {
        try {
            if (bufsize > 0) {
                chan.getChannel().setOption(StandardSocketOptions.SO_RCVBUF, bufsize);
            }
        } catch(IOException x) {
            if (logger.isDebugEnabled())
                logger.debug("Failed to set receive buffer size to {} on {}",
                          bufsize, chan);
        }
    }

    @Override
    TcpPublisher getPublisher() { return writePublisher; }


    @Override
    public String toString() {
        return "PlainTcpConnection: " + super.toString();
    }

    /**
     * Closes this connection
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            logger.trace("Closing: {}", toString());
            if (logger.isDebugEnabled())
                logger.debug("Closing channel: " + getServer().debugInterestOps(chan));
//            if (connectTimerEvent != null)
//                getServer().cancelTimer(connectTimerEvent);
            chan.getChannel().close();
            tube.signalClosed();
        } catch (IOException e) {
            logger.trace("Closing resulted in " + e);
        }
    }

    @Override
    synchronized boolean isConnected() {
        return connected;
    }


    @Override
    boolean isSecure() {
        return false;
    }
}
