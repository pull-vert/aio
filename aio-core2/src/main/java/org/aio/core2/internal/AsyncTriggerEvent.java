/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.AsyncTriggerEvent
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

package org.aio.core2.internal;

import org.aio.core2.AsyncEvent;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An asynchronous event which is triggered only once from the selector manager
 * thread as soon as event registration are handled.
 */
public final class AsyncTriggerEvent extends AsyncEvent {

    private final Runnable trigger;
    private final Consumer<? super IOException> errorHandler;
    public AsyncTriggerEvent(Consumer<? super IOException> errorHandler, Runnable trigger) {
        super(0);
        this.trigger = Objects.requireNonNull(trigger);
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }
    /** Returns null */
    @Override
    public SelectableChannel getChannel() { return null; }
    /** Returns 0 */
    @Override
    public int getInterestOps() { return 0; }
    @Override
    public void handle() { trigger.run(); }
    @Override
    public void abort(IOException ioe) { errorHandler.accept(ioe); }
    @Override
    public boolean isRepeating() { return false; }
}
