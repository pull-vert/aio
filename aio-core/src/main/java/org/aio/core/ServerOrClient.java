/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.HttpClientImpl
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

package org.aio.core;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public abstract class ServerOrClient {

    /**
     * Wait for activity on given exchange.
     * The following occurs in the SelectorManager thread.
     *
     *  1) add to selector
     *  2) If selector fires for this exchange then
     *     call AsyncEvent.handle()
     *
     * If exchange needs to change interest ops, then call registerEvent() again.
     */
    protected abstract void registerEvent(AsyncEvent exchange);

    /**
     * Allows an AsyncEvent to modify its interestOps.
     * @param event The modified event.
     */
    protected abstract void eventUpdated(AsyncEvent event) throws ClosedChannelException;

    protected abstract boolean isSelectorThread();

    protected abstract DelegatingExecutor theExecutor();

    /**
     * A DelegatingExecutor is an executor that delegates tasks to
     * a wrapped executor when it detects that the current thread
     * is the SelectorManager thread. If the current thread is not
     * the selector manager thread the given task is executed inline.
     */
    public final static class DelegatingExecutor implements Executor {
        private final BooleanSupplier isInSelectorThread;
        private final Executor delegate;
        public DelegatingExecutor(BooleanSupplier isInSelectorThread, Executor delegate) {
            this.isInSelectorThread = isInSelectorThread;
            this.delegate = delegate;
        }

        Executor delegate() {
            return delegate;
        }

        @Override
        public void execute(Runnable command) {
            if (isInSelectorThread.getAsBoolean()) {
                delegate.execute(command);
            } else {
                command.run();
            }
        }
    }
}
