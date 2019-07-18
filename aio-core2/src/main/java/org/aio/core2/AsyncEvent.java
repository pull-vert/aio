/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.AsyncEvent
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

package org.aio.core2;

import java.io.IOException;
import java.nio.channels.SelectableChannel;

/**
 * Event handling interface from {@link java.nio.channels.SelectableChannel}'s {@linkplain java.nio.channels.Selector selector}.
 *
 * If REPEATING is set then the event is not cancelled after being posted.
 */
public abstract class AsyncEvent {

    public static final int REPEATING = 0x2; // one off event if not set

    protected final int flags;

    AsyncEvent() {
        this(0);
    }

    public AsyncEvent(int flags) {
        this.flags = flags;
    }

    /** Returns the channel */
    public abstract SelectableChannel getChannel();

    /** Returns the selector interest op flags OR'd */
    public abstract int getInterestOps();

    /** Called when event occurs */
    public abstract void handle();

    /**
     * Called when an error occurs during registration, or when the selector has
     * been shut down. Aborts all exchanges.
     *
     * @param ioe  the IOException, or null
     */
    public abstract void abort(IOException ioe);

    public boolean isRepeating() {
        return (flags & REPEATING) != 0;
    }
}
