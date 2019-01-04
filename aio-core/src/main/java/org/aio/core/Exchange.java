///*
// * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
// *
// * Use of this source code is governed by the GNU General Public License v2.0,
// * and is subject to the "Classpath" exception as provided in the LICENSE
// * file that accompanied this code.
// *
// *
// * This file is a fork of OpenJDK jdk.internal.net.http.Exchange
// *
// * In initial Copyright below, LICENCE file refers to OpendJDK licence, a copy
// * is provided in the OPENJDK_LICENCE file that accompanied this code.
// *
// * INITIAL COPYRIGHT NOTICES AND FILE HEADER
// * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
// * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
// *
// * This code is free software; you can redistribute it and/or modify it
// * under the terms of the GNU General Public License version 2 only, as
// * published by the Free Software Foundation.  Oracle designates this
// * particular file as subject to the "Classpath" exception as provided
// * by Oracle in the LICENSE file that accompanied this code.
// *
// * This code is distributed in the hope that it will be useful, but WITHOUT
// * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// * version 2 for more details (a copy is included in the LICENSE file that
// * accompanied this code).
// *
// * You should have received a copy of the GNU General Public License version
// * 2 along with this work; if not, write to the Free Software Foundation,
// * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
// *
// * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// * or visit www.oracle.com if you need additional information or have any
// * questions.
// */
//
//package org.aio.core;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.IOException;
//import java.security.AccessControlContext;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.Executor;
//
///**
// * One request/response exchange.
// * depth field used to track number of times a new request is being sent
// * for a given API request. If limit exceeded exception is thrown.
// *
// * Security check is performed here:
// * - uses AccessControlContext captured at API level
// * - checks for appropriate URLPermission for request
// */
//abstract public class Exchange<T, U extends Chan> {
//
//    private final Logger logger = LoggerFactory.getLogger(Exchange.class);
//
//    final ServerOrClient<U> serverOrClient;
//    volatile ExchangeImpl<T, U> exchImpl;
//    volatile CompletableFuture<? extends ExchangeImpl<T>> exchangeCF;
//    volatile CompletableFuture<Void> bodyIgnored;
//
//    // used to record possible cancellation raised before the exchImpl
//    // has been established.
//    private volatile IOException failed;
//    final AccessControlContext acc;
//    final Executor parentExecutor;
//    final String dbgTag;
//
//    // Keeps track of the underlying connection when establishing an HTTP/2
//    // exchange so that it can be aborted/timed out mid setup.
//    final ConnectionAborter connectionAborter = new ConnectionAborter();
//
//    Exchange(HttpRequestImpl request, MultiExchange<T> multi) {
//        this.request = request;
//        this.upgrading = false;
//        this.client = multi.client();
//        this.multi = multi;
//        this.acc = multi.acc;
//        this.parentExecutor = multi.executor;
//        this.pushGroup = multi.pushGroup;
//        this.dbgTag = "Exchange";
//    }
//
//    /* If different AccessControlContext to be used  */
//    Exchange(HttpRequestImpl request,
//             MultiExchange<T> multi,
//             AccessControlContext acc)
//    {
//        this.request = request;
//        this.acc = acc;
//        this.upgrading = false;
//        this.client = multi.client();
//        this.multi = multi;
//        this.parentExecutor = multi.executor;
//        this.pushGroup = multi.pushGroup;
//        this.dbgTag = "Exchange";
//    }
//
//    PushGroup<T> getPushGroup() {
//        return pushGroup;
//    }
//
//    Executor executor() {
//        return parentExecutor;
//    }
//
//    public HttpRequestImpl request() {
//        return request;
//    }
//
//    HttpClientImpl client() {
//        return client;
//    }
//
//    // Keeps track of the underlying connection when establishing an HTTP/2
//    // exchange so that it can be aborted/timed out mid setup.
//    static final class ConnectionAborter {
//        private volatile HttpConnection connection;
//
//        void connection(HttpConnection connection) {
//            this.connection = connection;
//        }
//
//        void closeConnection() {
//            HttpConnection connection = this.connection;
//            this.connection = null;
//            if (connection != null) {
//                try {
//                    connection.close();
//                } catch (Throwable t) {
//                    // ignore
//                }
//            }
//        }
//    }
//}
