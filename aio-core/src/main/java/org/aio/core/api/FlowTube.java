/*
 * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.common.FlowTube
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

package org.aio.core.api;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * FlowTube is an I/O abstraction that allows reading from and writing to a
 * destination asynchronously.
 * This is not a {@link Flow.Processor
 * Flow.Processor&lt;IN, OUT&gt;},
 * but rather models a publisher source and a subscriber sink in a bidirectional flow.
 * <p>
 * The {@code connectFlows} method should be called to connect the bidirectional
 * flow. A FlowTube supports handing over the same read subscription to different
 * sequential read subscribers over time. When {@code connectFlows(writePublisher,
 * readSubscriber} is called, the FlowTube will call {@code dropSubscription} on
 * its former readSubscriber, and {@code onSubscribe} on its new readSubscriber.
 */
public interface FlowTube<IN, OUT> extends
       Flow.Publisher<IN>,
       Flow.Subscriber<OUT> {

    /**
     * A subscriber for reading from the bidirectional flow.
     * A TubeSubscriber is a {@code Flow.Subscriber} that can be canceled
     * by calling {@code dropSubscription()}.
     * Once {@code dropSubscription()} is called, the {@code TubeSubscriber}
     * should stop calling any method on its subscription.
     */
    public static interface TubeSubscriber<IN> extends Flow.Subscriber<IN> {

        /**
         * Called when the flow is connected again, and the subscription
         * is handed over to a new subscriber.
         * Once {@code dropSubscription()} is called, the {@code TubeSubscriber}
         * should stop calling any method on its subscription.
         */
        public default void dropSubscription() { }

        public default boolean supportsRecycling() { return false; }

    }

    /**
     * A publisher for writing to the bidirectional flow.
     */
    public static interface TubePublisher extends Flow.Publisher<List<ByteBuffer>> {

    }

    /**
     * Connects the bidirectional flows to a write {@code Publisher} and a
     * read {@code Subscriber}. This method can be called sequentially
     * several times to switch existing publishers and subscribers to a new
     * pair of write subscriber and read publisher.
     * @param writePublisher A new publisher for writing to the bidirectional flow.
     * @param readSubscriber A new subscriber for reading from the bidirectional
     *                       flow.
     */
    public default void connectFlows(TubePublisher writePublisher,
                              TubeSubscriber readSubscriber) {
        this.subscribe(readSubscriber);
        writePublisher.subscribe(this);
    }

    /**
     * Returns true if this flow was completed, either exceptionally
     * or normally (EOF reached).
     * @return true if the flow is finished
     */
    public boolean isFinished();


    /**
     * Returns {@code s} if {@code s} is a {@code TubeSubscriber}, otherwise
     * wraps it in a {@code TubeSubscriber}.
     * Using the wrapper is only appropriate in the case where
     * {@code dropSubscription} doesn't need to be implemented, and the
     * {@code TubeSubscriber} is subscribed only once.
     *
     * @param s a subscriber for reading.
     * @return a {@code TubeSubscriber}: either {@code s} if {@code s} is a
     *           {@code TubeSubscriber}, otherwise a {@code TubeSubscriber}
     *           wrapper that delegates to {@code s}
     */
    public static TubeSubscriber asTubeSubscriber(Flow.Subscriber<? super List<ByteBuffer>> s) {
        if (s instanceof TubeSubscriber) {
            return (TubeSubscriber) s;
        }
        return new AbstractTubeSubscriber.TubeSubscriberWrapper(s);
    }

    /**
     * Returns {@code s} if {@code s} is a {@code  TubePublisher}, otherwise
     * wraps it in a {@code  TubePublisher}.
     *
     * @param p a publisher for writing.
     * @return a {@code TubePublisher}: either {@code s} if {@code s} is a
     *           {@code  TubePublisher}, otherwise a {@code TubePublisher}
     *           wrapper that delegates to {@code s}
     */
    public static TubePublisher asTubePublisher(Flow.Publisher<List<ByteBuffer>> p) {
        if (p instanceof TubePublisher) {
            return (TubePublisher) p;
        }
        return new AbstractTubePublisher.TubePublisherWrapper(p);
    }

    /**
     * Convenience abstract class for {@code TubePublisher} implementations.
     * It is not required that a {@code TubePublisher} implementation extends
     * this class.
     */
    public static abstract class AbstractTubePublisher implements TubePublisher {
        static final class TubePublisherWrapper extends AbstractTubePublisher {
            final Flow.Publisher<List<ByteBuffer>> delegate;
            TubePublisherWrapper(Flow.Publisher<List<ByteBuffer>> delegate) {
                this.delegate = delegate;
            }
            @Override
            public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
                delegate.subscribe(subscriber);
            }
        }
    }

    /**
     * Convenience abstract class for {@code TubeSubscriber} implementations.
     * It is not required that a {@code TubeSubscriber} implementation extends
     * this class.
     */
    public static abstract class AbstractTubeSubscriber implements TubeSubscriber {
        static final class TubeSubscriberWrapper extends  AbstractTubeSubscriber {
            private final Flow.Subscriber<? super List<ByteBuffer>> delegate;
            TubeSubscriberWrapper(Flow.Subscriber<? super List<ByteBuffer>> delegate) {
                this.delegate = delegate;
            }
            @Override
            public void dropSubscription() {}
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                delegate.onSubscribe(subscription);
            }
            @Override
            public void onNext(List<ByteBuffer> item) {
                delegate.onNext(item);
            }
            @Override
            public void onError(Throwable throwable) {
                delegate.onError(throwable);
            }
            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        }

    }

}
