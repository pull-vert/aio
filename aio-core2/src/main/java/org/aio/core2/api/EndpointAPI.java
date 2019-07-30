/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
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

package org.aio.core2.api;

import org.aio.core2.bybu.Bybu;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Endpoint is a server or a client
 *
 * @author Fred Montariol
 */
public interface EndpointAPI {

    /**
     * A builder of {@link EndpointAPI}.
     *
     * @author Fred Montariol
     */
    interface Builder {

        /**
         * Sets the executor to be used for asynchronous and dependent tasks.
         *
         * <p> If this method is not invoked prior to {@linkplain #inStages()
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
        Builder executor(Executor executor);

        /**
         * Sets an {@code SSLContext}.
         *
         * <p> If this method is not invoked prior to {@linkplain #inStages()
         * building}, connection will not use SSL.
         *
         * @param sslContext the SSLContext
         * @return this builder
         */
        Builder sslContext(SSLContext sslContext);

        /**
         * Sets an {@code SSLParameters}.
         *
         * <p> If this method is not invoked prior to {@linkplain #inStages()
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
        Builder sslParameters(SSLParameters sslParameters);

        InStagesConfigurer<Bybu> inStages();
    }

    /**
     * Configure stages on IN(coming) messages
     *
     * @author Fred Montariol
     */
    interface InStagesConfigurer<T> {

        /**
         * Evaluate each source value against the given {@link Predicate}. If the predicate test succeeds, the value is
         * emitted. If the predicate test fails, the value is ignored and a request of 1 is made upstream.
         *
         * @param p the {@link Predicate} to test values against
         *
         * <p>
         * Exceptions thrown by the predicate are
         * considered as if the predicate returned false: they cause the source value to be
         * dropped and a new element ({@code request(1)}) being requested from upstream.
         * </p>
         * <p>
         * This operator discards elements that do not match the filter. It
         * also discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
         * </p>
         * @return a new {@link InStagesConfigurer} containing only values that pass the predicate test
         */
        InStagesConfigurer<T> filter(Predicate<? super T> p);

        /**
         * Transform the items emitted by this {@link InStagesConfigurer} by applying a synchronous function
         * to each item.
         *
         * @param mapper the synchronous transforming {@link Function}
         * @param <V> the transformed type
         *
         * <p>
         * Exceptions thrown by the mapper then cause the
         * source value to be dropped and a new element ({@code request(1)}) being requested
         * from upstream.
         * </p>
         *
         * @return a new {@link InStagesConfigurer} containing transformed values
         */
        <V> InStagesConfigurer<V> map(Function<? super T, ? extends V> mapper);
    }

    /**
     * Configure stages on OUT(going) messages
     * <p>
     * Provide the {@link #build()} method that instantiate the endpoint
     *
     * @author Fred Montariol
     */
    interface OutStagesConfigurer<T> {

        /**
         * Returns a new {@link EndpointAPI} built from the
         * current state of this configurer.
         *
         * @return a new server or client
         */
        EndpointAPI build();
    }

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * Executor}. If no {@code Executor} was set in this client or server's
     * builder, then the {@code Optional} is empty.
     *
     * <p> Even though this method may return an empty optional, the {@code
     * TcpClientOrServer} may still have an non-exposed {@linkplain
     * EndpointAPI.Builder#executor(Executor) default executor} that
     * is used for executing asynchronous and dependent tasks.
     *
     * @return an {@code Optional} containing this client or server's
     * {@code Executor}
     */
    Optional<Executor> getExecutor();

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * SSLContext}. If no {@code SSLContext} was set in this client or
     * server's builder, then the {@code Optional} is empty.
     *
     * @return an {@code Optional} containing this client or server's {@code
     * SSLContext}
     */
    Optional<SSLContext> getSslContext();

    /**
     * Returns an {@code Optional} containing this client or server's {@link
     * SSLParameters}. If no {@code SSLParameters} was set in this client or
     * server's builder, then the {@code Optional} is empty.
     *
     * @return an {@code Optional} containing this client or server's
     * {@code SSLParameters}
     */
    Optional<SSLParameters> getSslParameters();
}
