///*
// * Copyright (c) 2018-2019 AIO's author : Fred Montariol
// *
// * Use of this source code is governed by the GNU General Public License v2.0,
// * and is subject to the "Classpath" exception as provided in the LICENSE
// * file that accompanied this code.
// *
// *
// * This file is a fork of OpenJDK jdk.internal.net.http.ExchangeImpl
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
///**
// * Splits request so that headers and body can be sent separately with optional
// * (multiple) responses in between (e.g. 100 Continue). Also request and
// * response always sent/received in different calls.
// *
// * Synchronous and asynchronous versions of each method are provided.
// *
// * Separate implementations of this class exist for HTTP/1.1 and HTTP/2
// *      Http1Exchange   (HTTP/1.1)
// *      Stream          (HTTP/2)
// *
// * These implementation classes are where work is allocated to threads.
// */
//abstract public class ExchangeImpl<T, U extends Chan> {
//
//    private final Logger logger = LoggerFactory.getLogger(ExchangeImpl.class);
//
//    final Exchange<T, U> exchange;
//
//    ExchangeImpl(Exchange<T, U> e) {
//        // e == null means a http/2 pushed stream
//        this.exchange = e;
//    }
//
//    final Exchange<T, U> getExchange() {
//        return exchange;
//    }
//}
