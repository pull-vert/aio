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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
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

    // Define the default factory as a static inner class
    // that embeds all the necessary logic to avoid
    // the risk of using a lambda that might keep a reference on the
    // TcpServer instance from which it was created (helps with heapdump
    // analysis).
    private static final class DefaultThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger nextId = new AtomicInteger();

        DefaultThreadFactory(long serverID) {
            namePrefix = "TcpServer-" + serverID + "-Worker-";
        }

        @Override
        public Thread newThread(Runnable r) {
            String name = namePrefix + nextId.getAndIncrement();
            Thread t;
            if (System.getSecurityManager() == null) {
                t = new Thread(null, r, name, 0, false);
            } /*else {
                t = InnocuousThread.newThread(name, r);
            }
            t.setDaemon(true);
            return t;*/
            return null;
        }
    }

    private final DelegatingExecutor delegatingExecutor;
    private final boolean isDefaultExecutor;
    private final TcpServerImpl.SelectorManager selmgr;
    private final long id;

    // This reference is used to keep track of the facade TcpServer
    // that was returned to the application code.
    // It makes it possible to know when the application no longer
    // holds any reference to the TcpServer.
    // Unfortunately, this information is not enough to know when
    // to exit the SelectorManager thread. Because of the asynchronous
    // nature of the API, we also need to wait until all pending operations
    // have completed.
    private final WeakReference<TcpServerFacade> facadeRef;

    public TcpServerImpl(TcpServerBuilderImpl builder, SingleFacadeFactory facadeFactory) {
        id = TCP_SERVER_IDS.incrementAndGet();
        Executor ex = builder.executor;
        if (ex == null) {
            ex = Executors.newCachedThreadPool(new DefaultThreadFactory(id));
            isDefaultExecutor = true;
        } else {
            isDefaultExecutor = false;
        }
        delegatingExecutor = new DelegatingExecutor(this::isSelectorThread, ex);
        facadeRef = new WeakReference<>(facadeFactory.createFacade(this));
        try {
            selmgr = new SelectorManager(this);
        } catch (IOException e) {
            // unlikely
            throw new InternalError(e);
        }
        selmgr.setDaemon(true);
    }

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

    // TcpServer methods

    @Override
    public int getPort() {
        return 0;
    }

    // TcpServerOrClient methods

    @Override
    public Optional<SSLContext> getSslContext() {
        return Optional.empty();
    }

    @Override
    public Optional<SSLParameters> getSslParameters() {
        return Optional.empty();
    }

    @Override
    public Optional<Executor> getExecutor() {
        return Optional.empty();
    }

    @Override
    protected BufferSupplier getSSLBufferSupplier() {
        return null;
    }

    // ServerOrClient methods

    @Override
    protected void registerEvent(AsyncEvent exchange) {

    }

    @Override
    protected void eventUpdated(AsyncEvent event) throws ClosedChannelException {

    }

    @Override
    protected boolean isSelectorThread() {
        return false;
    }

    @Override
    protected DelegatingExecutor theExecutor() {
        return null;
    }

    // internal methods and classes
    private void start() {
        selmgr.start();
    }

    // Main loop for this server's selector
    private final static class SelectorManager extends Thread {

        public SelectorManager(TcpServerImpl ref) throws IOException {
            super(null, null,
                    "TcpServer-" + ref.id + "-SelectorManager",
                    0, false);
        }
    }
}
