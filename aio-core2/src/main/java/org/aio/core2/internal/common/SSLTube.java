/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK SSLTube
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

package org.aio.core2.internal.common;

import org.aio.core2.bybu.Bybu;
import org.aio.core2.internal.FlowTube;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import org.aio.core2.internal.common.SubscriberWrapper.SchedulingAction;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;

/**
 * An implementation of FlowTube that wraps another FlowTube in an SSL flow.
 * <p>
 * The following diagram shows a typical usage of the SSLTube, where the SSLTube wraps a SocketTube on the right hand side,
 * and is connected to an HttpConnection on the left hand side.
 *
 * <preformatted>{@code
 *                  +----------  SSLTube -------------------------+
 *                  |                                             |
 *                  |                    +---SSLFlowDelegate---+  |
 *  HttpConnection  |                    |                     |  |   SocketTube
 *    read sink  <- SSLSubscriberW.   <- Reader <- upstreamR.() <---- read source
 *  (a subscriber)  |                    |    \         /      |  |  (a publisher)
 *                  |                    |     SSLEngine       |  |
 *  HttpConnection  |                    |    /         \      |  |   SocketTube
 *  write source -> SSLSubscriptionW. -> upstreamW.() -> Writer ----> write sink
 *  (a publisher)   |                    |                     |  |  (a subscriber)
 *                  |                    +---------------------+  |
 *                  |                                             |
 *                  +---------------------------------------------+
 * }</preformatted>
 */
public class SSLTube implements FlowTube {

    final Logger logger = LoggerFactory.getLogger(SSLTube.class);

    private final FlowTube tube;
    private final SSLTube.SSLSubscriberWrapper readSubscriber;
    private final SSLTube.SSLSubscriptionWrapper writeSubscription;
    private final SSLFlowDelegate sslDelegate;
    private final SSLEngine engine;
    private volatile boolean finished;

    public SSLTube(SSLEngine engine, Executor executor, FlowTube tube) {
        this(engine, executor, null, tube);
    }

    public SSLTube(SSLEngine engine,
                   Executor executor,
                   Consumer<ByteBuffer> recycler,
                   FlowTube tube) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(executor);
        this.tube = Objects.requireNonNull(tube);
        writeSubscription = new SSLTube.SSLSubscriptionWrapper();
        readSubscriber = new SSLTube.SSLSubscriberWrapper();
        this.engine = engine;
        sslDelegate = new SSLTube.SSLTubeFlowDelegate(engine,
            executor,
            recycler,
            readSubscriber,
            tube);
    }

    final class SSLTubeFlowDelegate extends SSLFlowDelegate {
        SSLTubeFlowDelegate(SSLEngine engine, Executor executor,
                            Consumer<ByteBuffer> recycler,
                            SSLTube.SSLSubscriberWrapper readSubscriber,
                            FlowTube tube) {
            super(engine, executor, recycler, readSubscriber, tube);
        }
        protected SchedulingAction enterReadScheduling() {
            readSubscriber.processPendingSubscriber();
            return SchedulingAction.CONTINUE;
        }
        void connect(Flow.Subscriber<? super Bybu> downReader,
                     Flow.Subscriber<? super Bybu> downWriter) {
            assert downWriter == tube;
            assert downReader == readSubscriber;

            // Connect the read sink first. That's the left-hand side downstream subscriber from the HttpConnection
            // (or more accurately, the SSLSubscriberWrapper that will wrap it when SSLTube::connectFlows is called.
            reader.subscribe(downReader);

            // Connect the right hand side tube (the socket tube).
            //
            // The SSLFlowDelegate.writer publishes ByteBuffer to the SocketTube for writing on the socket, and the
            // SSLFlowDelegate::upstreamReader subscribes to the SocketTube to receive ByteBuffers read from the socket.
            //
            // Basically this method is equivalent to:
            //     // connect the read source:
            //     //   subscribe the SSLFlowDelegate upstream reader
            //     //   to the socket tube publisher.
            //     tube.subscribe(upstreamReader());
            //     // connect the write sink:
            //     //   subscribe the socket tube write subscriber
            //     //   with the SSLFlowDelegate downstream writer.
            //     writer.subscribe(tube);
            tube.connectFlows(FlowTube.asTubePublisher(writer), FlowTube.asTubeSubscriber(upstreamReader()));

            // Finally connect the write source. That's the left hand side publisher which will push ByteBuffer for
            // writing and encryption to the SSLFlowDelegate.
            // The writeSubscription is in fact the SSLSubscriptionWrapper that will wrap the subscription provided
            // by the HttpConnection publisher when SSLTube::connectFlows is called.
            upstreamWriter().onSubscribe(writeSubscription);
        }
    }

    public CompletableFuture<String> getALPN() {
        return sslDelegate.alpn();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Bybu> s) {
        readSubscriber.dropSubscription();
        readSubscriber.setDelegate(s);
        s.onSubscribe(readSubscription);
    }

    /**
     * Tells whether, or not, this FlowTube has finished receiving data.
     *
     * @return true when one of this FlowTube Subscriber's OnError or onComplete methods have been invoked
     */
    @Override
    public boolean isFinished() {
        return finished;
    }

    private volatile Flow.Subscription readSubscription;

    // The DelegateWrapper wraps a subscribed {@code Flow.Subscriber} and tracks the subscriber's state. In particular
    // it makes sure that onComplete/onError are not called before onSubscribed.
    final static class DelegateWrapper implements FlowTube.TubeSubscriber {
        private final FlowTube.TubeSubscriber delegate;
        private final Logger logger;
        volatile boolean subscribedCalled;
        volatile boolean subscribedDone;
        volatile boolean completed;
        volatile Throwable error;
        DelegateWrapper(Flow.Subscriber<? super Bybu> delegate, Logger logger) {
            this.delegate = FlowTube.asTubeSubscriber(delegate);
            this.logger = logger;
        }

        @Override
        public void dropSubscription() {
            if (subscribedCalled && !completed) {
                delegate.dropSubscription();
            }
        }

        @Override
        public void onNext(Bybu bybu) {
            assert subscribedCalled;
            delegate.onNext(bybu);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            onSubscribe(delegate::onSubscribe, subscription);
        }

        private void onSubscribe(Consumer<Flow.Subscription> method,
                                 Flow.Subscription subscription) {
            subscribedCalled = true;
            method.accept(subscription);
            Throwable x;
            boolean finished;
            synchronized (this) {
                subscribedDone = true;
                x = error;
                finished = completed;
            }
            if (x != null) {
                if (logger.isDebugEnabled())
                    logger.debug("Subscriber completed before subscribe: forwarding {}", (Object)x);
                delegate.onError(x);
            } else if (finished) {
                if (logger.isDebugEnabled()) logger.debug("Subscriber completed before subscribe: calling onComplete()");
                delegate.onComplete();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (completed) {
                if (logger.isDebugEnabled()) logger.debug("Subscriber already completed: ignoring {}", (Object)t);
                return;
            }
            boolean subscribed;
            synchronized (this) {
                if (completed) return;
                error = t;
                completed = true;
                subscribed = subscribedDone;
            }
            if (subscribed) {
                delegate.onError(t);
            } else {
                if (logger.isDebugEnabled()) logger.debug("Subscriber not yet subscribed: stored {}", (Object)t);
            }
        }

        @Override
        public void onComplete() {
            if (completed) return;
            boolean subscribed;
            synchronized (this) {
                if (completed) return;
                completed = true;
                subscribed = subscribedDone;
            }
            if (subscribed) {
                if (logger.isDebugEnabled()) logger.debug("DelegateWrapper: completing subscriber");
                delegate.onComplete();
            } else {
                if (logger.isDebugEnabled()) logger.debug("Subscriber not yet subscribed: stored completed=true");
            }
        }

        @Override
        public String toString() {
            return "DelegateWrapper:" + delegate.toString();
        }

    }

    // Used to read data from the SSLTube.
    final class SSLSubscriberWrapper implements FlowTube.TubeSubscriber {
        private AtomicReference<SSLTube.DelegateWrapper> pendingDelegate = new AtomicReference<>();
        private volatile SSLTube.DelegateWrapper subscribed;
        private volatile boolean onCompleteReceived;
        private final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // setDelegate can be called asynchronously when the SSLTube flow is connected. At this time the permanent subscriber
        // (this class) may already be subscribed (readSubscription != null) or not.
        // 1. If it's already subscribed (readSubscription != null), we are going to signal the SSLFlowDelegate reader, and
        // make sure onSubscribed is called within the reader flow
        // 2. If it's not yet subscribed (readSubscription == null), then we're going to wait for onSubscribe to be called.
        //
        void setDelegate(Flow.Subscriber<? super Bybu> delegate) {
            if (logger.isDebugEnabled()) logger.debug("SSLSubscriberWrapper (reader) got delegate: {}", delegate);
            assert delegate != null;
            SSLTube.DelegateWrapper delegateWrapper = new SSLTube.DelegateWrapper(delegate, logger);
            SSLTube.DelegateWrapper previous;
            Flow.Subscription subscription;
            boolean handleNow;
            synchronized (this) {
                previous = pendingDelegate.getAndSet(delegateWrapper);
                subscription = readSubscription;
                handleNow = this.errorRef.get() != null || onCompleteReceived;
            }
            if (previous != null) {
                previous.dropSubscription();
            }
            if (subscription == null) {
                if (logger.isDebugEnabled())
                    logger.debug("SSLSubscriberWrapper (reader) no subscription yet");
                return;
            }
            if (handleNow || !sslDelegate.resumeReader()) {
                processPendingSubscriber();
            }
        }

        // Can be called outside of the flow if an error has already been raise. Otherwise, must be called within the
        // SSLFlowDelegate downstream reader flow.
        // If there is a subscription, and if there is a pending delegate, calls dropSubscription() on the previous delegate
        // (if any), then subscribe the pending delegate.
        void processPendingSubscriber() {
            Flow.Subscription subscription;
            SSLTube.DelegateWrapper delegateWrapper, previous;
            synchronized (this) {
                delegateWrapper = pendingDelegate.get();
                if (delegateWrapper == null) return;
                subscription = readSubscription;
                previous = subscribed;
            }
            if (subscription == null) {
                if (logger.isDebugEnabled())
                    logger.debug("SSLSubscriberWrapper (reader) processPendingSubscriber: no subscription yet");
                return;
            }
            delegateWrapper = pendingDelegate.getAndSet(null);
            if (delegateWrapper == null) return;
            if (previous != null) {
                previous.dropSubscription();
            }
            onNewSubscription(delegateWrapper, subscription);
        }

        @Override
        public void dropSubscription() {
            SSLTube.DelegateWrapper subscriberImpl = subscribed;
            if (subscriberImpl != null) {
                subscriberImpl.dropSubscription();
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (logger.isDebugEnabled()) logger.debug("SSLSubscriberWrapper (reader) onSubscribe({})", subscription);
            onSubscribeImpl(subscription);
        }

        // called in the reader flow, from onSubscribe.
        private void onSubscribeImpl(Flow.Subscription subscription) {
            assert subscription != null;
            SSLTube.DelegateWrapper subscriberImpl, pending;
            synchronized (this) {
                readSubscription = subscription;
                subscriberImpl = subscribed;
                pending = pendingDelegate.get();
            }

            if (subscriberImpl == null && pending == null) {
                if (logger.isDebugEnabled()) logger.debug("SSLSubscriberWrapper (reader) onSubscribeImpl: no delegate yet");
                return;
            }

            if (pending == null) {
                // There is no pending delegate, but we have a previously subscribed delegate. This is obviously a re-subscribe.
                // We are in the downstream reader flow, so we should call onSubscribe directly.
                if (logger.isDebugEnabled()) logger.debug("SSLSubscriberWrapper (reader) onSubscribeImpl: resubscribing");
                onNewSubscription(subscriberImpl, subscription);
            } else {
                // We have some pending subscriber: subscribe it now that we have a subscription. If we already had a previous
                // delegate then it will get a dropSubscription().
                if (logger.isDebugEnabled()) logger.debug("SSLSubscriberWrapper (reader) onSubscribeImpl: subscribing pending");
                processPendingSubscriber();
            }
        }

        private void complete(SSLTube.DelegateWrapper subscriberImpl, Throwable t) {
            try {
                if (t == null) subscriberImpl.onComplete();
                else subscriberImpl.onError(t);
                if (logger.isDebugEnabled()) {
                    logger.debug("subscriber completed " + ((t == null) ? "normally" : ("with error: " + t)));
                }
            } finally {
                // Error or EOF while reading: cancel write side after completing read side
                writeSubscription.cancel();
            }
        }

        private void onNewSubscription(SSLTube.DelegateWrapper subscriberImpl,
                                       Flow.Subscription subscription) {
            assert subscriberImpl != null;
            assert subscription != null;

            Throwable failed;
            boolean completed;
            // reset any demand that may have been made by the previous subscriber
            sslDelegate.resetReaderDemand();
            // send the subscription to the subscriber.
            subscriberImpl.onSubscribe(subscription);

            // The following twisted logic is just here that we don't invoke onError before onSubscribe. It also prevents race
            // conditions if onError is invoked concurrently with setDelegate.
            synchronized (this) {
                failed = this.errorRef.get();
                completed = onCompleteReceived;
                subscribed = subscriberImpl;
            }

            if (failed != null) {
                if (logger.isDebugEnabled())
                    logger.debug("onNewSubscription: subscriberImpl:{}, invoking onError:{}", subscriberImpl, failed);
                complete(subscriberImpl, failed);
            } else if (completed) {
                if (logger.isDebugEnabled())
                    logger.debug("onNewSubscription: subscriberImpl:{}, invoking onCompleted", subscriberImpl);
                finished = true;
                complete(subscriberImpl, null);
            }
        }

        @Override
        public void onNext(Bybu bybu) {
            subscribed.onNext(bybu);
        }

        public void onErrorImpl(Throwable throwable) {
            // The following twisted logic is just here that we don't invoke onError before onSubscribe. It also prevents race
            // conditions if onError is invoked concurrently with setDelegate. See setDelegate.
            errorRef.compareAndSet(null, throwable);
            Throwable failed = errorRef.get();
            finished = true;
            if (logger.isDebugEnabled())
                logger.debug("%s: onErrorImpl: %s", this, throwable);
            SSLTube.DelegateWrapper subscriberImpl;
            synchronized (this) {
                subscriberImpl = subscribed;
            }
            if (subscriberImpl != null) {
                complete(subscriberImpl, failed);
            } else {
                if (logger.isDebugEnabled()) logger.debug("{}: delegate null, stored {}", this, failed);
            }
            // now if we have any pending subscriber, we should forward the error to them immediately as the read scheduler will
            // already be stopped.
            processPendingSubscriber();
        }

        @Override
        public void onError(Throwable throwable) {
            assert !finished && !onCompleteReceived;
            onErrorImpl(throwable);
        }

        private boolean handshaking() {
            HandshakeStatus hs = engine.getHandshakeStatus();
            return !(hs == NOT_HANDSHAKING || hs == FINISHED);
        }

        private String handshakeFailed() {
            // sslDelegate can be null if we reach here during the initial handshake, as that happens within the SSLFlowDelegate
            // constructor. In that case we will want to raise an exception.
            if (handshaking()
                && (sslDelegate == null
                || !sslDelegate.closeNotifyReceived())) {
                return "Remote host terminated the handshake";
            }
            // The initial handshake may not have been started yet. In which case
            // - if we are completed before the initial handshake is started, we consider this a handshake failure as well.
            if ("SSL_NULL_WITH_NULL_NULL".equals(engine.getSession().getCipherSuite()))
                return "Remote host closed the channel";
            return null;
        }

        @Override
        public void onComplete() {
            assert !finished && !onCompleteReceived;
            SSLTube.DelegateWrapper subscriberImpl;
            synchronized(this) {
                subscriberImpl = subscribed;
            }

            String handshakeFailed = handshakeFailed();
            if (handshakeFailed != null) {
                if (logger.isDebugEnabled())
                    logger.debug("handshake: {}, inbound done: {}, outbound done: {}: {}",
                        engine.getHandshakeStatus(),
                        engine.isInboundDone(),
                        engine.isOutboundDone(),
                        handshakeFailed);
                onErrorImpl(new SSLHandshakeException(handshakeFailed));
            } else if (subscriberImpl != null) {
                onCompleteReceived = finished = true;
                complete(subscriberImpl, null);
            } else {
                onCompleteReceived = true;
            }
            // now if we have any pending subscriber, we should complete
            // them immediately as the read scheduler will already be stopped.
            processPendingSubscriber();
        }
    }

    @Override
    public void connectFlows(TubePublisher writePub,
                             TubeSubscriber readSub) {
        if (logger.isDebugEnabled()) logger.debug("connecting flows");
        readSubscriber.setDelegate(readSub);
        writePub.subscribe(this);
    }

    /** Outstanding write demand from the SSL Flow Delegate. */
    private final Demand writeDemand = new Demand();

    final class SSLSubscriptionWrapper implements Flow.Subscription {

        volatile Flow.Subscription delegate;
        private volatile boolean cancelled;

        void setSubscription(Flow.Subscription sub) {
            long demand = writeDemand.get(); // FIXME: isn't it a racy way of passing the demand?
            delegate = sub;
            if (logger.isDebugEnabled()) logger.debug("setSubscription: demand={}, cancelled:{}", demand, cancelled);

            if (cancelled)
                delegate.cancel();
            else if (demand > 0)
                sub.request(demand);
        }

        @Override
        public void request(long n) {
            writeDemand.increase(n);
            if (logger.isDebugEnabled()) logger.debug("request: n=" + n);
            Flow.Subscription sub = delegate;
            if (sub != null && n > 0) {
                sub.request(n);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (delegate != null)
                delegate.cancel();
        }
    }

    /* Subscriber - writing side */
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription);
        Flow.Subscription x = writeSubscription.delegate;
        if (x != null)
            x.cancel();

        writeSubscription.setSubscription(subscription);
    }

    @Override
    public void onNext(Bybu bybu) {
        Objects.requireNonNull(bybu);
        boolean decremented = writeDemand.tryDecrement();
        assert decremented : "Unexpected writeDemand: ";
        if (logger.isDebugEnabled()) logger.debug("sending {} buffers to SSL flow delegate", bybu.size());
        sslDelegate.upstreamWriter().onNext(bybu);
    }

    @Override
    public void onError(Throwable throwable) {
        Objects.requireNonNull(throwable);
        sslDelegate.upstreamWriter().onError(throwable);
    }

    @Override
    public void onComplete() {
        sslDelegate.upstreamWriter().onComplete();
    }

    @Override
    public String toString() {
        return dbgString();
    }

    final String dbgString() {
        return "SSLTube(" + tube + ")";
    }

}
