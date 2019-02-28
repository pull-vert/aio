/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.HttpClientBuilderImpl
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

import org.aio.core.ServerOrClient;
import org.aio.tcp.common.TcpUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

final class TcpServerBuilderImpl extends ServerOrClient.Builder implements TcpServer.Builder {

    int port;
    // Security parameters
    SSLContext sslContext;
    SSLParameters sslParams;

    @Override
    public TcpServer.Builder port(int port) {
        assert port > 0 : "Port must be greater than 0";
        this.port = port;
        return this;
    }

    @Override
    public TcpServer.Builder sslContext(SSLContext sslContext) {
        requireNonNull(sslContext);
        this.sslContext = sslContext;
        return this;
    }


    @Override
    public TcpServer.Builder sslParameters(SSLParameters sslParameters) {
        requireNonNull(sslParameters);
        this.sslParams = TcpUtils.copySSLParameters(sslParameters);
        return this;
    }


    @Override
    public TcpServer.Builder executor(Executor s) {
        requireNonNull(s);
        setExecutor(s);
        return this;
    }

    @Override
    public TcpServer.FirstStagesConfigurer configureStages() {
        return new TcpFirstStagesConfigurerImpl(this);
    }
}
