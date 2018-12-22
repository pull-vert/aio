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

package org.aio.tcp;

import org.aio.core.api.ChanEvtsHandler;
import org.aio.core.api.ServerOrClientAPI;
import org.aio.tcp.api.TcpServerAPI;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.concurrent.Executor;

public interface TcpServer extends TcpServerAPI {

    /**
     * Returns a new {@code TcpServer} with default settings.
     *
     * <p> Equivalent to {@code newBuilder().build()}.
     *
     * <p> The default settings include: the 35700 default port.
     *
     * @implNote The system-wide default values are retrieved at the time the
     * {@code TcpServer} instance is constructed. Changing the system-wide
     * values after an {@code TcpServer} instance has been built, for
     * instance, by calling {@link SSLContext#setDefault(SSLContext)},
     * has no effect on already built instances.
     *
     * @return a new TcpServer
     */
    public static FirstStagesConfigurer newStageConfigurer() {
        return newBuilder().configureStages();
    }

    public static Builder newBuilder() {
        return new TcpServerBuilderImpl();
    }

    /**
     * A builder of {@link TcpServer}.
     *
     * <p> Builders are created by invoking {@link TcpServer#newBuilder()}.
     * Each of the setter methods modifies the state of the builder
     * and returns the same instance. Builders are not thread-safe and should not be
     * used concurrently from multiple threads without external synchronization.
     */
    public static interface Builder extends TcpServerAPI.Builder {
        @Override
        Builder port(int port);

        @Override
        Builder executor(Executor executor);

        @Override
        Builder sslContext(SSLContext sslContext);

        @Override
        Builder sslParameters(SSLParameters sslParameters);

        @Override
        FirstStagesConfigurer configureStages();
    }

    public static interface FirstStagesConfigurer extends ServerOrClientAPI.FirstStagesConfigurer {
        @Override
        <U extends ChanEvtsHandler> StagesConfigurer stage1(String name, U chanEvtsHandler);
    }

    public static interface StagesConfigurer extends ServerOrClientAPI.StagesConfigurer {
        @Override
        <U extends ChanEvtsHandler> ServerOrClientAPI.StagesConfigurer addLast(String name, U chanEvtsHandler);

        @Override
        TcpServer build();
    }
}
