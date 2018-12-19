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
import org.aio.core.common.BufferSupplier;
import org.aio.core.common.CoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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

    private final Logger logger = LoggerFactory.getLogger(TcpServerImpl.class);

    public static final int DEFAULT_PORT = 35700;
    private static final AtomicLong TCP_SERVER_IDS = new AtomicLong();

    private final int port;
    // Security parameters
    private final SSLContext sslContext;
    private final SSLParameters sslParams;
    private final String dbgTag;

    // The SSL DirectBuffer Supplier provides the ability to recycle
    // buffers used between the socket reader and the SSLEngine, or
    // more precisely between the SocketTube getPublisher and the
    // SSLFlowDelegate reader.
    private final SSLDirectBufferSupplier<SocketChan> sslBufferSupplier
            = new SSLDirectBufferSupplier<>(this);

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
    //    when the the getSocketChan is closed.
    //    In addition, the HttpClient facade is passed to the WebSocket builder,
    //    (instead of the client implementation delegate).
    // 3. For HTTP/1.1 the count is incremented before starting to parse the body
    //    response, and decremented when the parser has reached the end of the
    //    response body flow.
    //
    // This should ensure that the selector manager thread remains alive until
    // the response has been fully received or the web socket is closed.
    private final AtomicLong pendingOperationCount = new AtomicLong();

    private Set<TcpConnection> activeTcpConnections;
    private final SocketChanManager socketChanMgr;

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

    private TcpServerImpl(TcpServerBuilderImpl builder, SingleFacadeFactory facadeFactory) {
        super(TCP_SERVER_IDS, builder);
        if (builder.port > 0) {
            port = builder.port;
        } else {
            port = DEFAULT_PORT;
        }
        dbgTag = "TcpServerImpl(" + id +")";
        if (logger.isDebugEnabled())
            logger.debug("{} instanciated with port {}", dbgTag, port);
        sslContext = builder.sslContext;
        activeTcpConnections = new HashSet<>();
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
        socketChanMgr = new SocketChanManager(port, this);
        socketChanMgr.setDaemon(true);
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
    protected BufferSupplier getSSLBufferSupplier() {
        return sslBufferSupplier;
    }

    // Returns the facade that was returned to the application code.
    // May be null if that facade is no longer referenced.
    private TcpServerFacade facade() {
        return facadeRef.get();
    }

    // Returns the pendingOperationCount.
    private long referenceCount() {
        return pendingOperationCount.get();
    }

    // Called by the SelectorManager thread to figure out whether it's time
    // to terminate.
    @Override
    protected final boolean isNotReferenced() {
        TcpServer facade = facade();
        return facade == null && referenceCount() == 0;
    }

    @Override
    protected void start() {
        // start SelectorManager thread first
        super.start();
        // then start SocketChanManager thread
        logger.debug("starting SocketChannelManager thread");
        socketChanMgr.start();
    }

    @Override
    public void stop() {
        // todo close connections
    }

    String dbgString() {
        return dbgTag;
    }

    // Return all supported params
    private static SSLParameters getDefaultParams(SSLContext ctx) {
        return ctx.getSupportedSSLParameters();
    }

    /**
     * @author Frédéric Montariol
     */
    public class SocketChanManager extends Thread {

        private final Logger logger = LoggerFactory.getLogger(SocketChanManager.class);

        private InetSocketAddress inetSocketAddress;
        private ServerSocketChannel serverSocket;
        private TcpServerImpl owner;
        private volatile boolean closed;

        SocketChanManager(int tcpPort, TcpServerImpl ref) {
            super(null, null,
                    "TcpServer-" + ref.id + "-SocketChanManager",
                    0, false);
            this.inetSocketAddress = new InetSocketAddress(tcpPort);
            owner = ref;
        }

        synchronized void shutdown() {
            if (logger.isDebugEnabled()) logger.debug("{} : SocketChanManager shutting down", getName());
            closed = true;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            } finally {
                owner.stop();
            }
        }

        @Override
        public void run() {
            try {
                try {
                    serverSocket = ServerSocketChannel.open();
                    serverSocket.bind(inetSocketAddress);
                } catch (IOException e) {
                    // This terminates thread. So, better just print stack trace
                    String err = CoreUtils.stackTrace(e);
                    logger.error("{}: {}: {}", getName(),
                            "TcpServer shutting down due to fatal error on ServerSocketChannel init phase",
                            err);
                    return;
                }


                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        SocketChan socketChan = new SocketChan(this.serverSocket.accept());
                        if (logger.isDebugEnabled()) logger.debug("new Socket accepted: {}", socketChan);
                        TcpConnection tcpConnection = TcpConnection.createConnection(inetSocketAddress, owner, socketChan, false);
                        owner.activeTcpConnections.add(tcpConnection);

                    } catch (IOException e) {
                        logger.warn("{}: {}: {}", getName(),
                                "TcpServer error on accepting incoming connection", e);
                    }
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
    }
}
