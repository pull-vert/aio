/*
 * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.common.SubscriberWrapper
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

package org.aio.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * This is a specific abstract SubscriberWrapper for IN = List < ByteBuffer >
 * and OUT = List < ByteBuffer >
 *     fixme is this class really necessary ? could be replaced by a Stage
 */
public abstract class ByteBufferSubscriberWrapper extends SubscriberWrapper<List<ByteBuffer>, List<ByteBuffer>> {

    private final Logger logger = LoggerFactory.getLogger(ByteBufferSubscriberWrapper.class);

    /**
     * Delivers buffers of data downstream. After incoming()
     * has been called complete == true signifying completion of the upstream
     * subscription, data may continue to be delivered, up to when outgoing() is
     * called complete == true, after which, the downstream subscription is
     * completed.
     *
     * It's an error to call outgoing() with complete = true if incoming() has
     * not previously been called with it.
     */
    public void outgoing(ByteBuffer buffer, boolean complete) {
        Objects.requireNonNull(buffer);
        assert !complete || !buffer.hasRemaining();
        outgoing(List.of(buffer), complete);
    }

    @Override
    public void outgoing(List<ByteBuffer> buffers, boolean complete) {
        Objects.requireNonNull(buffers);
        if (complete) {
            assert CoreUtils.remaining(buffers) == 0;
            boolean closing = closing();
            if (logger.isDebugEnabled())
                logger.debug("completionAcknowledged upstreamCompleted:{},"
                                + " downstreamCompleted:{}, closing:{}",
                        upstreamCompleted, downstreamCompleted, closing);
            if (!upstreamCompleted && !closing) {
                throw new IllegalStateException("upstream not completed");
            }
            completionAcknowledged = true;
        } else {
            if (logger.isDebugEnabled())
                logger.debug("Adding {} to outputQ queue", CoreUtils.remaining(buffers));
            outputQ.add(buffers);
        }
        if (logger.isDebugEnabled())
            logger.debug("pushScheduler" +(pushScheduler.isStopped() ? " is stopped!" : " is alive"));
        pushScheduler.runOrSchedule();
    }

    /** Adds the given data to the input queue. */
    public void addData(ByteBuffer l) {
        if (upstreamSubscription == null) {
            throw new IllegalStateException("can't add data before upstream subscriber subscribes");
        }
        incomingCaller(List.of(l), false);
    }

    @Override
    List<ByteBuffer> finalValue() {
        return CoreUtils.EMPTY_BB_LIST;
    }
}
