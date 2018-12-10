/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.TimeoutEvent
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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timeout event notified by selector thread. Executes the given handler if
 * the timer not canceled first.
 *
 * Register with {@link ServerOrClient#registerTimer(TimeoutEvent)}.
 *
 * Cancel with {@link ServerOrClient#cancelTimer(TimeoutEvent)}.
 */
public abstract class TimeoutEvent implements Comparable<TimeoutEvent> {

    private static final AtomicLong COUNTER = new AtomicLong();
    // we use id in compareTo to make compareTo consistent with equals
    // see TimeoutEvent::compareTo below;
    private final long id = COUNTER.incrementAndGet();
    private final Duration duration;
    private final Instant deadline;

    TimeoutEvent(Duration duration) {
        this.duration = duration;
        deadline = Instant.now().plus(duration);
    }

    public abstract void handle();

    public Instant deadline() {
        return deadline;
    }

    @Override
    public int compareTo(TimeoutEvent other) {
        if (other == this) return 0;
        // if two events have the same deadline, but are not equals, then the
        // smaller is the one that was created before (has the smaller id).
        // This is arbitrary and we don't really care which is smaller or
        // greater, but we need a total order, so two events with the
        // same deadline cannot compare == 0 if they are not equals.
        final int compareDeadline = this.deadline.compareTo(other.deadline);
        if (compareDeadline == 0 && !this.equals(other)) {
            long diff = this.id - other.id; // should take care of wrap around
            if (diff < 0) return -1;
            else if (diff > 0) return 1;
            else assert false : "Different events with same id and deadline";
        }
        return compareDeadline;
    }

    @Override
    public String toString() {
        return "TimeoutEvent[id=" + id + ", duration=" + duration
                + ", deadline=" + deadline + "]";
    }
}
