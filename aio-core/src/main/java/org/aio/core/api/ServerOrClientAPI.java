/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK java.net.http.HttpClient
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

package org.aio.core.api;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.Optional;
import java.util.concurrent.Executor;

public interface ServerOrClientAPI {

    /**
     * A builder of {@link ServerOrClientAPI}.
     * todo remove generic parameter
     */
    public static interface Builder<T extends ServerOrClientAPI> {

        /**
         * Sets the executor to be used for asynchronous and dependent tasks.
         *
         * <p> If this method is not invoked prior to {@linkplain #build()
         * building}, a default executor is used
         *
         * @implNote The default executor uses a thread pool, with a custom
         * thread factory. If a security manager has been installed, the thread
         * factory creates threads that run with an access control context that
         * has no permissions.
         *
         * @param executor the Executor
         * @return this builder
         */
        public Builder executor(Executor executor);

        /**
         * Sets an {@code SSLContext}.
         *
         * <p> If this method is not invoked prior to {@linkplain #build()
         * building}, connection will not use SSL.
         *
         * @param sslContext the SSLContext
         * @return this builder
         */
        public Builder sslContext(SSLContext sslContext);

        /**
         * Sets an {@code SSLParameters}.
         *
         * <p> If this method is not invoked prior to {@linkplain #build()
         * building}, and if {@linkplain #sslContext(SSLContext) SSL Context}
         * was called, then newly built client or server will use a default,
         * implementation specific, set of parameters.
         *
         * <p> Some parameters which are used internally by the TCP Server or
         * client implementation (such as the application protocol list) should
         * not be set by callers, as they may be ignored. The contents of the
         * given object are copied.
         *
         * @param sslParameters the SSLParameters
         * @return this builder
         */
        public Builder sslParameters(SSLParameters sslParameters);

        /**
         * Returns a new child of {@link ServerOrClientAPI} built from the
         * current state of this builder.
         *
         * @return a new child of TcpServerOrClient
         */
        public T build(); // todo remove when stages work

        public FirstStagesConfigurer configureStages();
    }

    /**
     * Configurer for first {@linkplain ChanStages stage} of the Chan
     *
     * @author Frédéric Montariol
     */
    @FunctionalInterface
    public static interface FirstStagesConfigurer {

        /**
         * Define first stage in {@linkplain ChanStages stage(s)} of the Chan
         *
         * @param name The unique name of the {@link ChanEvtsEmitter} associated with provided {@code chanEvtsHandler}
         * @param chanEvtsHandler The first Event Handler to add to {@linkplain ChanStages stage(s)} of the Chan
         * @return The {@link StagesConfigurer} that allows to configure next stage(s) and then build the server
         * or client
         */
        public <T extends ServerOrClientAPI, U extends ChanEvtsHandler> StagesConfigurer<T> stage1(
                String name, U chanEvtsHandler);
    }

    /**
     * Configurer for next {@linkplain ChanStages stage(s)} of the Chan
     * <p>
     * Provide the {@link #build()} method that instanciate the server
     * or client
     *
     * @author Frédéric Montariol
     */
    public static interface StagesConfigurer<T extends ServerOrClientAPI> {

        /**
         * Add last stage to {@linkplain ChanStages stage(s)} of the Chan
         *
         * @param name The unique name of the {@link ChanEvtsEmitter} associated with provided {@code chanEvtsHandler}
         * @param chanEvtsHandler The last Event Handler to add to {@linkplain ChanStages stage(s)} of the Chan
         * @return this StagesConfigurer
         */
        public <U extends ChanEvtsHandler> StagesConfigurer<T> addLast(String name, U chanEvtsHandler);

        /**
         * Returns a new child of {@link ServerOrClientAPI} built from the
         * current state of this configurer.
         *
         * @return a new server or client
         */
        public T build();
    }

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * Executor}. If no {@code Executor} was set in this client or server's
     * builder, then the {@code Optional} is empty.
     *
     * <p> Even though this method may return an empty optional, the {@code
     * TcpClientOrServer} may still have an non-exposed {@linkplain
     * ServerOrClientAPI.Builder#executor(Executor) default executor} that
     * is used for executing asynchronous and dependent tasks.
     *
     * @return an {@code Optional} containing this client or server's
     * {@code Executor}
     */
    public Optional<Executor> getExecutor();

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * SSLContext}. If no {@code SSLContext} was set in this client or
     * server's builder, then the {@code Optional} is empty.
     *
     * @return an {@code Optional} containing this client or server's {@code
     * SSLContext}
     */
    public Optional<SSLContext> getSslContext();

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * SSLParameters}. If no {@code SSLParameters} was set in this client or
     * server's builder, then the {@code Optional} is empty.
     *
     * @return an {@code Optional} containing this client or server's
     * {@code SSLParameters}
     */
    public Optional<SSLParameters> getSslParameters();

    public ChanStages getStages();
}
