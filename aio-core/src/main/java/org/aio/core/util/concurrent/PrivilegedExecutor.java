/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.PrivilegedExecutor
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

package org.aio.core.util.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Executes tasks within a given access control context, and by a given executor.
 */
class PrivilegedExecutor implements Executor {

    /** The underlying executor. May be provided by the user. */
    final Executor executor;
    /** The ACC to execute the tasks within. */
    final AccessControlContext acc;

    public PrivilegedExecutor(Executor executor, AccessControlContext acc) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(acc);
        this.executor = executor;
        this.acc = acc;
    }

    private static class PrivilegedRunnable implements Runnable {
        private final Runnable r;
        private final AccessControlContext acc;
        PrivilegedRunnable(Runnable r, AccessControlContext acc) {
            this.r = r;
            this.acc = acc;
        }
        @Override
        public void run() {
            PrivilegedAction<Void> pa = () -> { r.run(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    @Override
    public void execute(Runnable r) {
        executor.execute(new PrivilegedRunnable(r, acc));
    }
}
