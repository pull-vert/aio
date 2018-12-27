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

import org.aio.core.api.FlowTube;
import org.aio.core.common.CoreUtils;
import org.aio.core.util.concurrent.MinimalFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.util.concurrent.CompletableFuture;

/**
 * Plain raw TCP connection direct to destination.
 * The connection operates in asynchronous non-blocking mode.
 * All reads and writes are done non-blocking.
 */
class PlainTcpConnection extends TcpConnection {

    private final Logger logger = LoggerFactory.getLogger(PlainTcpConnection.class);

    private final Object reading = new Object();
    private final SocketChan socketChan;
    private final SocketChanTube tube; // need SocketChanTube to call signalClosed().
    private final PlainTcpPublisher writePublisher = new PlainTcpPublisher(reading);
    private volatile boolean connected;
    private boolean closed;

    @Override
    public CompletableFuture<Void> finishConnect() {
        assert !connected;
        if (logger.isDebugEnabled())
            logger.debug("finishConnect, setting connected=true");
        connected = true;
        return MinimalFuture.completedFuture(null);
    }

    /**
     * Create a TCP Connection without SSL
     * Provided SocketChan must be opened or accepted by ServerSocketChannel before calling this constructor
     *
     * @param addr the InetSocketAddress
     * @param tcpServerOrClient server or client that will hold the TcpConnection
     * @param socketChan the SocketChan where we will read and write
     */
    PlainTcpConnection(InetSocketAddress addr, TcpServerOrClient tcpServerOrClient, SocketChan socketChan) {
        super(addr, tcpServerOrClient);
        try {
            this.socketChan = socketChan;
            socketChan.configureNonBlocking();
            trySetReceiveBufferSize(tcpServerOrClient.getReceiveBufferSize());
            if (logger.isDebugEnabled()) {
                int bufsize = getInitialBufferSize();
                logger.debug("Initial receive buffer size is: {}", bufsize);
            }
            socketChan.setOption(StandardSocketOptions.TCP_NODELAY, true);
            // wrap the channel in a Tube for async reading and writing
            tube = new SocketChanTube(getServerOrClient(), socketChan, CoreUtils::getBuffer);
        } catch (IOException e) {
            throw new InternalError(e);
        }
    }

    @Override
    SocketChan getSocketChan() {
        return socketChan;
    }

    @Override
    final FlowTube getConnectionFlow() {
        return tube;
    }

    private int getInitialBufferSize() {
        try {
            return socketChan.getOption(StandardSocketOptions.SO_RCVBUF);
        } catch(IOException x) {
            if (logger.isDebugEnabled())
                logger.debug("Failed to get initial receive buffer size on {}", socketChan);
        }
        return 0;
    }

    private void trySetReceiveBufferSize(int bufsize) {
        try {
            if (bufsize > 0) {
                socketChan.setOption(StandardSocketOptions.SO_RCVBUF, bufsize);
            }
        } catch(IOException x) {
            if (logger.isDebugEnabled())
                logger.debug("Failed to set receive buffer size to {} on {}",
                          bufsize, socketChan);
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
                logger.debug("Closing channel: " + getServerOrClient().debugInterestOps(socketChan));
//            if (connectTimerEvent != null)
//                getServerOrClient().cancelTimer(connectTimerEvent);
            socketChan.close();
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
