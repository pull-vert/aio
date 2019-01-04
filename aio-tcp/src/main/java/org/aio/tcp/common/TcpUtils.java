/*
 * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.common.Utils
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

package org.aio.tcp.common;

import javax.net.ssl.SSLParameters;

/**
 * Miscellaneous utilities for TCP
 */
public final class TcpUtils {
    private TcpUtils() { }

    public static SSLParameters copySSLParameters(SSLParameters p) {
        SSLParameters p1 = new SSLParameters();
        p1.setAlgorithmConstraints(p.getAlgorithmConstraints());
        p1.setCipherSuites(p.getCipherSuites());
        // JDK 8 EXCL START
        p1.setEnableRetransmissions(p.getEnableRetransmissions());
        p1.setMaximumPacketSize(p.getMaximumPacketSize());
        // JDK 8 EXCL END
        p1.setEndpointIdentificationAlgorithm(p.getEndpointIdentificationAlgorithm());
        p1.setNeedClientAuth(p.getNeedClientAuth());
        String[] protocols = p.getProtocols();
        if (protocols != null) {
            p1.setProtocols(protocols.clone());
        }
        p1.setSNIMatchers(p.getSNIMatchers());
        p1.setServerNames(p.getServerNames());
        p1.setUseCipherSuitesOrder(p.getUseCipherSuitesOrder());
        p1.setWantClientAuth(p.getWantClientAuth());
        return p1;
    }
}
