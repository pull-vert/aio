/*
 * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.SocketTube
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

import org.aio.core.api.FlowTube;
import org.aio.core.common.CoreUtils;
import org.aio.core.common.Demand;
import org.aio.core.util.concurrent.SequentialScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A ChanTube implementation is a terminal tube plugged directly into the {@link Chan}
 * <br>
 * The read subscriber should call {@code subscribe} on the ChanTube before
 * the ChanTube is subscribed to the write publisher.
 */
public abstract class ChanTube<T extends Chan> implements FlowTube<List<ByteBuffer>, List<ByteBuffer>> {

    private final Logger logger = LoggerFactory.getLogger(ChanTube.class);
    private static final AtomicLong IDS = new AtomicLong();

    private final ServerOrClient<T> serverOrClient;
    private final T chan;
    private final Object lock = new Object();
    private final AtomicReference<Throwable> errorRef = new AtomicReference<>();
    private final InternalReadPublisher readPublisher;
    private final InternalWriteSubscriber writeSubscriber;
    protected final long id = IDS.incrementAndGet();

    public ChanTube(ServerOrClient<T> serverOrClient, T chan) {
        this.serverOrClient = serverOrClient;
        this.chan = chan;

        this.readPublisher = new InternalReadPublisher();
        this.writeSubscriber = new InternalWriteSubscriber();
    }

    // ===================================================================== //
    //                            FlowTube                                   //
    // ======================================================================//

    /**
     * Returns {@code true} if this flow is finished.
     * This happens when this flow internal read subscription is completed,
     * either normally (EOF reading) or exceptionally  (EOF writing, or
     * underlying getChan closed, or some exception occurred while reading or
     * writing to the getChan).
     *
     * @return {@code true} if this flow is finished.
     */
    @Override
    public boolean isFinished() {
        InternalReadPublisher.InternalReadSubscription subscription =
                readPublisher.subscriptionImpl;
        return subscription.completed;
    }

    // ===================================================================== //
    //                       Flow.Publisher                                  //
    // ======================================================================//

    /**
     * {@inheritDoc }
     * @apiNote This method should be called first. In particular, the caller
     *          must ensure that this method must be called by the read
     *          subscriber before the write publisher can call {@code onSubscribe}.
     *          Failure to adhere to this contract may result in assertion errors.
     */
    @Override
    public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> s) {
        logger.debug("subscribed");
        Objects.requireNonNull(s);
        assert s instanceof TubeSubscriber : "Expected TubeSubscriber, got:" + s;
        readPublisher.subscribe(s);
    }


    // ===================================================================== //
    //                       Flow.Subscriber                                 //
    // ======================================================================//

    /**
     * {@inheritDoc }
     * @apiNote The caller must ensure that {@code subscribe} is called by
     *          the read subscriber before {@code onSubscribe} is called by
     *          the write publisher.
     *          Failure to adhere to this contract may result in assertion errors.
     */
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        writeSubscriber.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        writeSubscriber.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        writeSubscriber.onError(throwable);
    }

    @Override
    public void onComplete() {
        writeSubscriber.onComplete();
    }

    // ===================================================================== //
    //                           Events                                      //
    // ======================================================================//

    public void signalClosed() {
        // Ensures that the subscriber will be terminated and that future
        // subscribers will be notified when the connection is closed.
        if (logger.isDebugEnabled()) {
            logger.debug("Connection close signalled: connection closed locally ({})",
                    channelDescr());
        }
        readPublisher.subscriptionImpl.signalError(
                new IOException("connection closed locally"));
    }

    abstract protected BufferSource getBufferSource(TubeSubscriber subscriber);

    /**
     * A restartable task used to process tasks in sequence.
     */
    private static class SelectableChannelFlowTask implements SequentialScheduler.RestartableTask {
        final Runnable task;
        private final Object monitor = new Object();
        SelectableChannelFlowTask(Runnable task) {
            this.task = task;
        }
        @Override
        public final void run(SequentialScheduler.DeferredCompleter taskCompleter) {
            try {
                // non contentious synchronized for visibility.
                synchronized(monitor) {
                    task.run();
                }
            } finally {
                taskCompleter.complete();
            }
        }
    }

    // This is best effort - there's no guarantee that the printed set of values
    // is consistent. It should only be considered as weakly accurate - in
    // particular in what concerns the events states, especially when displaying
    // a read event state from a write event callback and conversely.
    private void debugState(String when) {
        if (logger.isDebugEnabled()) {
            StringBuilder state = new StringBuilder();

            InternalReadPublisher.InternalReadSubscription sub =
                    readPublisher.subscriptionImpl;
            InternalReadPublisher.ReadEvent readEvent = sub.readEvent;
            Demand rdemand = sub.demand;
            InternalWriteSubscriber.WriteEvent writeEvent =
                    writeSubscriber.writeEvent;
            Demand wdemand = writeSubscriber.writeDemand;
            int rops = readEvent == null ? 0 : readEvent.getInterestOps();
            long rd = rdemand.get();
            int wops = writeEvent.getInterestOps();
            long wd = wdemand.get();

            state.append(when).append(" Reading: [ops=")
                    .append(rops).append(", demand=").append(rd)
                    .append(", stopped=")
                    .append(sub.readScheduler.isStopped())
                    .append("], Writing: [ops=").append(wops)
                    .append(", demand=").append(wd)
                    .append("]");
            logger.debug(state.toString());
        }
    }

    /**
     * A repeatable event that can be paused or resumed by changing its
     * getInterestOps. When the event is fired, it is first paused before being
     * signaled. It is the responsibility of the code triggered by
     * {@code signalEvent} to resume the event if required.
     */
    private static abstract class SelectableChannelFlowEvent<T extends Chan> extends AsyncEvent<T> {

        final Logger logger = LoggerFactory.getLogger(SelectableChannelFlowEvent.class);

        final T chan;
        final int defaultInterest;
        volatile int interestOps;
        volatile boolean registered;
        SelectableChannelFlowEvent(int defaultInterest, T chan) {
            super(AsyncEvent.REPEATING);
            this.defaultInterest = defaultInterest;
            this.chan = chan;
        }
        final boolean registered() {return registered;}
        final void resume() {
            interestOps = defaultInterest;
            registered = true;
        }
        final void pause() {interestOps = 0;}
        @Override
        public final T getChan() {return chan;}
        @Override
        public final int getInterestOps() {return interestOps;}

        @Override
        public final void handle() {
            pause();       // pause, then signal
            signalEvent(); // won't be fired again until resumed.
        }
        @Override
        public final void abort(IOException error) {
            logger.debug("abort: " + error);
            pause();              // pause, then signal
            signalError(error);   // should not be resumed after abort (not checked)
        }

        protected abstract void signalEvent();
        protected abstract void signalError(Throwable error);
    }

    // ===================================================================== //
    //                              Writing                                  //
    // ======================================================================//

    // This class makes the assumption that the publisher will call onNext
    // sequentially, and that onNext won't be called if the demand has not been
    // incremented by request(1).
    // It has a 'queue of 1' meaning that it will call request(1) in
    // onSubscribe, and then only after its 'current' buffer list has been
    // fully written and current set to null;
    private final class InternalWriteSubscriber
            implements Flow.Subscriber<List<ByteBuffer>> {

        volatile WriteSubscription subscription;
        volatile List<ByteBuffer> current;
        volatile boolean completed;
        final AsyncTriggerEvent startSubscription =
                new AsyncTriggerEvent(this::signalError, this::startSubscription);
        final WriteEvent writeEvent = new WriteEvent(chan, this);
        final Demand writeDemand = new Demand();

        @SuppressWarnings("unchecked")
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            WriteSubscription previous = this.subscription;
            if (logger.isDebugEnabled()) logger.debug("subscribed for writing");
            try {
                boolean needEvent = current == null;
                if (needEvent) {
                    if (previous != null && previous.upstreamSubscription != subscription) {
                        previous.dropSubscription();
                    }
                }
                this.subscription = new WriteSubscription(subscription);
                if (needEvent) {
                    if (logger.isDebugEnabled())
                        logger.debug("write: registering startSubscription event");
                    serverOrClient.registerEvent(startSubscription);
                }
            } catch (Throwable t) {
                signalError(t);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> bufs) {
            assert current == null : dbgString() // this is a queue of 1.
                    + "w.onNext current: " + current;
            assert subscription != null : dbgString()
                    + "w.onNext: subscription is null";
            current = bufs;
            tryFlushCurrent(serverOrClient.isSelectorThread()); // may be in selector thread
            // For instance in HTTP/2, a received SETTINGS frame might trigger
            // the sending of a SETTINGS frame in turn which might cause
            // onNext to be called from within the same selector thread that the
            // original SETTINGS frames arrived on. If rs is the read-subscriber
            // and ws is the write-subscriber then the following can occur:
            // ReadEvent -> rs.onNext(bytes) -> process server SETTINGS -> write
            // serverOrClient SETTINGS -> ws.onNext(bytes) -> tryFlushCurrent
            debugState("leaving w.onNext");
        }

        // Don't use a SequentialScheduler here: rely on onNext() being invoked
        // sequentially, and not being invoked if there is no demand, request(1).
        // onNext is usually called from within a user / executor thread.
        // Initial writing will be performed in that thread. If for some reason,
        // not all the data can be written, a writeEvent will be registered, and
        // writing will resume in the the selector manager thread when the
        // writeEvent is fired.
        //
        // If this method is invoked in the selector manager thread (because of
        // a writeEvent), then the executor will be used to invoke request(1),
        // ensuring that onNext() won't be invoked from within the selector
        // thread. If not in the selector manager thread, then request(1) is
        // invoked directly.
        void tryFlushCurrent(boolean inSelectorThread) {
            List<ByteBuffer> bufs = current;
            if (bufs == null) return;
            try {
                assert inSelectorThread == serverOrClient.isSelectorThread() :
                       "should " + (inSelectorThread ? "" : "not ")
                        + " be in the selector thread";
                long remaining = CoreUtils.remaining(bufs);
                if (logger.isDebugEnabled()) logger.debug("trying to write: {}", remaining);
                long written = writeAvailable(bufs);
                if (logger.isDebugEnabled()) logger.debug("wrote: {}", written);
                assert written >= 0 : "negative number of bytes written:" + written;
                assert written <= remaining;
                if (remaining - written == 0) {
                    current = null;
                    if (writeDemand.tryDecrement()) {
                        Runnable requestMore = this::requestMore;
                        if (inSelectorThread) {
                            assert serverOrClient.isSelectorThread();
                            serverOrClient.theExecutor().execute(requestMore);
                        } else {
                            assert !serverOrClient.isSelectorThread();
                            requestMore.run();
                        }
                    }
                } else {
                    resumeWriteEvent();
                }
            } catch (Throwable t) {
                signalError(t);
            }
        }

        // Kick off the initial request:1 that will start the writing side.
        // Invoked in the selector manager thread.
        void startSubscription() {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("write: starting subscription");
                    logger.debug("Start requesting bytes for writing to chan: {}",
                            channelDescr());
                }
                assert serverOrClient.isSelectorThread();
                // make sure read registrations are handled before;
                readPublisher.subscriptionImpl.handlePending();
                if (logger.isDebugEnabled()) logger.debug("write: offloading requestMore");
                // start writing;
                serverOrClient.theExecutor().execute(this::requestMore);
            } catch(Throwable t) {
                signalError(t);
            }
        }

        void requestMore() {
           WriteSubscription subscription = this.subscription;
           subscription.requestMore();
        }

        @Override
        public void onError(Throwable throwable) {
            signalError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
            // no need to pause the write event here: the write event will
            // be paused if there is nothing more to write.
            List<ByteBuffer> bufs = current;
            long remaining = bufs == null ? 0 : CoreUtils.remaining(bufs);
            if (logger.isDebugEnabled())
                logger.debug( "write completed, {} yet to send", remaining);
            debugState("InternalWriteSubscriber::onComplete");
        }

        void resumeWriteEvent() {
            if (logger.isDebugEnabled()) logger.debug("scheduling write event");
            resumeEvent(writeEvent, this::signalError);
        }

        void signalWritable() {
            if (logger.isDebugEnabled()) logger.debug("chan is writable");
            tryFlushCurrent(true);
        }

        void signalError(Throwable error) {
            if (logger.isDebugEnabled()) {
                logger.debug("write error: " + error);
                logger.debug("Failed to write to chan ({}: {})",
                        channelDescr(), error);
            }
            completed = true;
            readPublisher.signalError(error);
            Flow.Subscription subscription = this.subscription;
            if (subscription != null) subscription.cancel();
        }

        // A repeatable WriteEvent which is paused after firing and can
        // be resumed if required - see SelectableChannelFlowEvent;
        final class WriteEvent extends SelectableChannelFlowEvent<T> {
            final ChanTube.InternalWriteSubscriber sub;
            WriteEvent(T chan, ChanTube.InternalWriteSubscriber sub) {
                super(SelectionKey.OP_WRITE, chan);
                this.sub = sub;
            }
            @Override
            protected final void signalEvent() {
                try {
                    serverOrClient.eventUpdated(this);
                    sub.signalWritable();
                } catch(Throwable t) {
                    sub.signalError(t);
                }
            }

            @Override
            protected void signalError(Throwable error) {
                sub.signalError(error);
            }
        }

        final class WriteSubscription implements Flow.Subscription {
            final Flow.Subscription upstreamSubscription;
            volatile boolean cancelled;
            WriteSubscription(Flow.Subscription subscription) {
                this.upstreamSubscription = subscription;
            }

            @Override
            public void request(long n) {
                if (cancelled) return;
                upstreamSubscription.request(n);
            }

            @Override
            public void cancel() {
                if (cancelled) return;
                if (logger.isDebugEnabled()) {
                    logger.debug("write: cancel - Cancelling write subscription");
                }
                dropSubscription();
                upstreamSubscription.cancel();
            }

            void dropSubscription() {
                synchronized (ChanTube.InternalWriteSubscriber.this) {
                    cancelled = true;
                    if (logger.isDebugEnabled()) logger.debug("write: resetting demand to 0");
                    writeDemand.reset();
                }
            }

            void requestMore() {
                try {
                    if (completed || cancelled) return;
                    boolean requestMore;
                    long d;
                    // don't fiddle with demand after cancel.
                    // see dropSubscription.
                    synchronized (ChanTube.InternalWriteSubscriber.this) {
                        if (cancelled) return;
                        d = writeDemand.get();
                        requestMore = writeDemand.increaseIfFulfilled();
                    }
                    if (requestMore) {
                        if (logger.isDebugEnabled()) logger.debug("write: requesting more...");
                        upstreamSubscription.request(1);
                    } else {
                        if (logger.isDebugEnabled())
                            logger.debug("write: no need to request more: {}", d);
                    }
                } catch (Throwable t) {
                    if (logger.isDebugEnabled())
                        logger.debug("write: error while requesting more: " + t);
                    signalError(t);
                } finally {
                    debugState("leaving requestMore: ");
                }
            }
        }
    }

    // ===================================================================== //
    //                              Reading                                  //
    // ===================================================================== //

    // The InternalReadPublisher uses a SequentialScheduler to ensure that
    // onNext/onError/onComplete are called sequentially on the caller's
    // subscriber.
    // However, it relies on the fact that the only time where
    // runOrSchedule() is called from a user/executor thread is in signalError,
    // right after the errorRef has been set.
    // Because the sequential scheduler's task always checks for errors first,
    // and always terminate the scheduler on error, then it is safe to assume
    // that if it reaches the point where it reads from the chan, then
    // it is running in the SelectorManager thread. This is because all
    // other invocation of runOrSchedule() are triggered from within a
    // ReadEvent.
    //
    // When pausing/resuming the event, some shortcuts can then be taken
    // when we know we're running in the selector manager thread
    // (in that case there's no need to call serverOrClient.eventUpdated(readEvent);
    //
    private final class InternalReadPublisher
            implements Flow.Publisher<List<ByteBuffer>> {
        private final InternalReadSubscription subscriptionImpl = new InternalReadSubscription();
        AtomicReference<ReadSubscription> pendingSubscription = new AtomicReference<>();
        private volatile ReadSubscription subscription;

        @Override
        public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> s) {
            Objects.requireNonNull(s);

            TubeSubscriber<List<ByteBuffer>> sub = FlowTube.asTubeSubscriber(s);
            ReadSubscription target = new ReadSubscription(subscriptionImpl, sub);
            ReadSubscription previous = pendingSubscription.getAndSet(target);

            if (previous != null && previous != target) {
                if (logger.isDebugEnabled())
                    logger.debug("read publisher: dropping pending subscriber: {}",
                              previous.subscriber);
                previous.errorRef.compareAndSet(null, errorRef.get());
                previous.signalOnSubscribe();
                if (subscriptionImpl.completed) {
                    previous.signalCompletion();
                } else {
                    previous.subscriber.dropSubscription();
                }
            }

            if (logger.isDebugEnabled()) logger.debug("read publisher got subscriber");
            subscriptionImpl.signalSubscribe();
            debugState("leaving read.subscribe: ");
        }

        void signalError(Throwable error) {
            if (logger.isDebugEnabled()) logger.debug("error signalled " + error);
            if (!errorRef.compareAndSet(null, error)) {
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Error signalled on chan {}: {}",
                        channelDescr(), error);
            }
            subscriptionImpl.handleError();
        }

        final class ReadSubscription implements Flow.Subscription {
            final InternalReadSubscription impl;
            final TubeSubscriber<List<ByteBuffer>> subscriber;
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            final BufferSource bufferSource;
            volatile boolean subscribed;
            volatile boolean cancelled;
            volatile boolean completed;

            ReadSubscription(InternalReadSubscription impl,
                             TubeSubscriber<List<ByteBuffer>> subscriber) {
                this.impl = impl;
                this.bufferSource = getBufferSource(subscriber);
                this.subscriber = subscriber;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public void request(long n) {
                if (!cancelled) {
                    impl.request(n);
                } else {
                    if (logger.isDebugEnabled())
                        logger.debug("subscription cancelled, ignoring request {}", n);
                }
            }

            void signalCompletion() {
                assert subscribed || cancelled;
                if (completed || cancelled) return;
                synchronized (this) {
                    if (completed) return;
                    completed = true;
                }
                Throwable error = errorRef.get();
                if (error != null) {
                    if (logger.isDebugEnabled())
                        logger.debug("forwarding error to subscriber: " + error);
                    subscriber.onError(error);
                } else {
                    if (logger.isDebugEnabled()) logger.debug("completing subscriber");
                    subscriber.onComplete();
                }
            }

            void signalOnSubscribe() {
                if (subscribed || cancelled) return;
                synchronized (this) {
                    if (subscribed || cancelled) return;
                    subscribed = true;
                }
                subscriber.onSubscribe(this);
                if (logger.isDebugEnabled()) logger.debug("onSubscribe called");
                if (errorRef.get() != null) {
                    signalCompletion();
                }
            }
        }

        final class InternalReadSubscription implements Flow.Subscription {

            private final Demand demand = new Demand();
            final SequentialScheduler readScheduler;
            private volatile boolean completed;
            private final ReadEvent readEvent;
            private final AsyncTriggerEvent subscribeEvent;

            InternalReadSubscription() {
                readScheduler = new SequentialScheduler(new SelectableChannelFlowTask(this::read));
                subscribeEvent = new AsyncTriggerEvent(this::signalError,
                                                       this::handleSubscribeEvent);
                readEvent = new ReadEvent(chan, this);
            }

            @SuppressWarnings("unchecked")
            /*
             * This method must be invoked before any other method of this class.
             */
            final void signalSubscribe() {
                if (readScheduler.isStopped() || completed) {
                    // if already completed or stopped we can handle any
                    // pending connection directly from here.
                    if (logger.isDebugEnabled())
                        logger.debug("handling pending subscription while completed");
                    handlePending();
                } else {
                    try {
                        if (logger.isDebugEnabled()) logger.debug("registering subscribe event");
                        serverOrClient.registerEvent(subscribeEvent);
                    } catch (Throwable t) {
                        signalError(t);
                        handlePending();
                    }
                }
            }

            final void handleSubscribeEvent() {
                assert serverOrClient.isSelectorThread();
                if (logger.isDebugEnabled()) {
                    logger.debug("subscribe event raised");
                    logger.debug("Start reading from {}", channelDescr());
                }
                readScheduler.runOrSchedule();
                if (readScheduler.isStopped() || completed) {
                    // if already completed or stopped we can handle any
                    // pending connection directly from here.
                    if (logger.isDebugEnabled())
                        logger.debug("handling pending subscription when completed");
                    handlePending();
                }
            }


            /*
             * Although this method is thread-safe, the Reactive-Streams spec seems
             * to not require it to be as such. It's a responsibility of the
             * subscriber to signal demand in a thread-safe manner.
             *
             * See Reactive Streams specification, rules 2.7 and 3.4.
             */
            @Override
            public final void request(long n) {
                if (n > 0L) {
                    boolean wasFulfilled = demand.increase(n);
                    if (wasFulfilled) {
                        if (logger.isDebugEnabled()) logger.debug("got some demand for reading");
                        resumeReadEvent();
                        // if demand has been changed from fulfilled
                        // to unfulfilled register read event;
                    }
                } else {
                    signalError(new IllegalArgumentException("non-positive request"));
                }
                debugState("leaving request("+n+"): ");
            }

            @Override
            public final void cancel() {
                pauseReadEvent();
                if (logger.isDebugEnabled()) {
                    logger.debug("Read subscription cancelled for chan {}",
                            channelDescr());
                }
                readScheduler.stop();
            }

            private void resumeReadEvent() {
                if (logger.isDebugEnabled()) logger.debug("resuming read event");
                resumeEvent(readEvent, this::signalError);
            }

            private void pauseReadEvent() {
                if (logger.isDebugEnabled()) logger.debug("pausing read event");
                pauseEvent(readEvent, this::signalError);
            }


            final void handleError() {
                assert errorRef.get() != null;
                readScheduler.runOrSchedule();
            }

            final void signalError(Throwable error) {
                if (!errorRef.compareAndSet(null, error)) {
                    return;
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("Read error signalled on chan {}: {}",
                            channelDescr(), error);
                }
                readScheduler.runOrSchedule();
            }

            final void signalReadable() {
                readScheduler.runOrSchedule();
            }

            /** The body of the task that runs in SequentialScheduler. */
            final void read() {
                // It is important to only call pauseReadEvent() when stopping
                // the scheduler. The event is automatically paused before
                // firing, and trying to pause it again could cause a race
                // condition between this loop, which calls tryDecrementDemand(),
                // and the thread that calls request(n), which will try to resume
                // reading.
                try {
                    while(!readScheduler.isStopped()) {
                        if (completed) return;

                        // make sure we have a subscriber
                        if (handlePending()) {
                            if (logger.isDebugEnabled())
                                logger.debug("pending subscriber subscribed");
                            return;
                        }

                        // If an error was signaled, we might not be in the
                        // the selector thread, and that is OK, because we
                        // will just call onError and return.
                        ReadSubscription current = subscription;
                        Throwable error = errorRef.get();
                        if (current == null)  {
                            assert error != null;
                            if (logger.isDebugEnabled())
                                logger.debug("error raised before subscriber subscribed: {}",
                                          (Object)error);
                            return;
                        }
                        TubeSubscriber<List<ByteBuffer>> subscriber = current.subscriber;
                        if (error != null) {
                            completed = true;
                            // safe to pause here because we're finished anyway.
                            pauseReadEvent();
                            if (logger.isDebugEnabled()) {
                                logger.debug("Raising error with subscriber {} for {}: {}",
                                        subscriber, channelDescr(), error);
                            }
                            current.errorRef.compareAndSet(null, error);
                            current.signalCompletion();
                            readScheduler.stop();
                            debugState("leaving read() loop with error: ");
                            return;
                        }

                        // If we reach here then we must be in the selector thread.
                        assert serverOrClient.isSelectorThread();
                        if (demand.tryDecrement()) {
                            // we have demand.
                            try {
                                List<ByteBuffer> bytes = readAvailable(current.bufferSource);
                                if (bytes == EOF) {
                                    if (!completed) {
                                        if (logger.isDebugEnabled()) {
                                            logger.debug("EOF read from chan: {}",
                                                        channelDescr());
                                        }
                                        completed = true;
                                        // safe to pause here because we're finished
                                        // anyway.
                                        pauseReadEvent();
                                        current.signalCompletion();
                                        readScheduler.stop();
                                    }
                                    debugState("leaving read() loop after EOF: ");
                                    return;
                                } else if (CoreUtils.remaining(bytes) > 0) {
                                    // the subscriber is responsible for offloading
                                    // to another thread if needed.
                                    if (logger.isDebugEnabled())
                                        logger.debug("read bytes: " + CoreUtils.remaining(bytes));
                                    assert !current.completed;
                                    subscriber.onNext(bytes);
                                    // we could continue looping until the demand
                                    // reaches 0. However, that would risk starving
                                    // other connections (bound to other channels)
                                    // - as other selected keys activated
                                    // by the selector manager thread might be
                                    // waiting for this event to terminate.
                                    // So resume the read event and return now...
                                    resumeReadEvent();
                                    debugState("leaving read() loop after onNext: ");
                                    return;
                                } else {
                                    // nothing available!
                                    if (logger.isDebugEnabled()) logger.debug("no more bytes available");
                                    // re-increment the demand and resume the read
                                    // event. This ensures that this loop is
                                    // executed again when the getChan becomes
                                    // readable again.
                                    demand.increase(1);
                                    resumeReadEvent();
                                    debugState("leaving read() loop with no bytes");
                                    return;
                                }
                            } catch (Throwable x) {
                                signalError(x);
                                continue;
                            }
                        } else {
                            if (logger.isDebugEnabled()) logger.debug("no more demand for reading");
                            // the event is paused just after firing, so it should
                            // still be paused here, unless the demand was just
                            // incremented from 0 to n, in which case, the
                            // event will be resumed, causing this loop to be
                            // invoked again when the getChan becomes readable:
                            // This is what we want.
                            // Trying to pause the event here would actually
                            // introduce a race condition between this loop and
                            // request(n).
                            debugState("leaving read() loop with no demand");
                            break;
                        }
                    }
                } catch (Throwable t) {
                    if (logger.isDebugEnabled()) logger.debug("Unexpected exception in read loop", t);
                    signalError(t);
                } finally {
                    if (readScheduler.isStopped()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Read scheduler stopped from chan {0}", channelDescr());
                        }
                    }
                    handlePending();
                }
            }

            boolean handlePending() {
                ReadSubscription pending = pendingSubscription.getAndSet(null);
                if (pending == null) return false;
                if (logger.isDebugEnabled())
                    logger.debug("handling pending subscription for {}", pending.subscriber);
                ReadSubscription current = subscription;
                if (current != null && current != pending && !completed) {
                    current.subscriber.dropSubscription();
                }
                if (logger.isDebugEnabled()) logger.debug("read demand reset to 0");
                subscriptionImpl.demand.reset(); // subscriber will increase demand if it needs to.
                pending.errorRef.compareAndSet(null, errorRef.get());
                if (!readScheduler.isStopped()) {
                    subscription = pending;
                } else {
                    if (logger.isDebugEnabled()) logger.debug("Chan tube is already stopped");
                }
                if (logger.isDebugEnabled()) logger.debug("calling onSubscribe");
                pending.signalOnSubscribe();
                if (completed) {
                    pending.errorRef.compareAndSet(null, errorRef.get());
                    pending.signalCompletion();
                }
                return true;
            }
        }


        // A repeatable ReadEvent which is paused after firing and can
        // be resumed if required - see SelectableChannelFlowEvent;
        final class ReadEvent extends SelectableChannelFlowEvent<T> {
            final InternalReadSubscription sub;
            ReadEvent(T chan, InternalReadSubscription sub) {
                super(SelectionKey.OP_READ, chan);
                this.sub = sub;
            }
            @Override
            protected final void signalEvent() {
                try {
                    serverOrClient.eventUpdated(this);
                    sub.signalReadable();
                } catch(Throwable t) {
                    sub.signalError(t);
                }
            }

            @Override
            protected final void signalError(Throwable error) {
                sub.signalError(error);
            }
        }
    }

    // ===================================================================== //
    //                       Buffer Management                               //
    // ===================================================================== //

    // This interface is used by readAvailable(BufferSource);
    public static interface BufferSource {
        /**
         * Returns a buffer to read data from the channel.
         *
         * @implNote
         * Different implementation can have different strategies, as to
         * which kind of buffer to return, or whether to return the same
         * buffer. The only constraints are that:
         *   a. the buffer returned must not be null
         *   b. the buffer position indicates where to start reading
         *   c. the buffer limit indicates where to stop reading.
         *   d. the buffer is 'free' - that is - it is not used
         *      or retained by anybody else
         *
         * @return A buffer to read data from the channel.
         */
        public ByteBuffer getBuffer();

        /**
         * Appends the read-data in {@code buffer} to the list of buffer to
         * be sent downstream to the subscriber. May return a new
         * list, or append to the given list.
         *
         * @implNote
         * Different implementation can have different strategies, but
         * must obviously be consistent with the implementation of the
         * getBuffer() method. For instance, an implementation could
         * decide to add the buffer to the list and return a new buffer
         * next time getBuffer() is called, or could decide to add a buffer
         * slice to the list and return the same buffer (if remaining
         * space is available) next time getBuffer() is called.
         *
         * @param list    The list before adding the data. Can be null.
         * @param buffer  The buffer containing the data to add to the list.
         * @param start   The start position at which data were read.
         *                The current buffer position indicates the end.
         * @return A possibly new list where a buffer containing the
         *         data read from the channel has been added.
         */
        public List<ByteBuffer> append(List<ByteBuffer> list, ByteBuffer buffer, int start);

        /**
         * Returns the given unused {@code buffer}, previously obtained from
         * {@code getBuffer}.
         *
         * @implNote This method can be used, if necessary, to return
         *  the unused buffer to the pull.
         *
         * @param buffer The unused buffer.
         */
        public default void returnUnused(ByteBuffer buffer) { }
    }

    // An implementation of BufferSource used for unencrypted data.
    // This buffer source uses heap buffers and avoids wasting memory
    // by forwarding read-only buffer slices downstream.
    // Buffers allocated through this source are simply GC'ed when
    // they are no longer referenced.
    protected static final class SliceBufferSource implements BufferSource {
        private final Supplier<ByteBuffer> factory;
        private volatile ByteBuffer current;

        public SliceBufferSource() {
            this(CoreUtils::getBuffer);
        }
        public SliceBufferSource(Supplier<ByteBuffer> factory) {
            this.factory = Objects.requireNonNull(factory);
        }

        // Reuses the same buffer if some space remains available.
        // Otherwise, returns a new heap buffer.
        @Override
        public final ByteBuffer getBuffer() {
            ByteBuffer buf = current;
            buf = (buf == null || !buf.hasRemaining())
                    ? (current = factory.get()) : buf;
            assert buf.hasRemaining();
            return buf;
        }

        // Adds a read-only slice to the list, potentially returning a
        // new list with that slice at the end.
        @Override
        public final List<ByteBuffer> append(List<ByteBuffer> list, ByteBuffer buf, int start) {
            // creates a slice to add to the list
            int limit = buf.limit();
            buf.limit(buf.position());
            buf.position(start);
            ByteBuffer slice = buf.slice();

            // restore buffer state to what it was before creating the slice
            buf.position(buf.limit());
            buf.limit(limit);

            // add the buffer to the list
            return ChanTube.listOf(list, slice.asReadOnlyBuffer());
        }
    }

    // ===================================================================== //
    //                       Channel Read/Write                              //
    // ===================================================================== //
    static final int MAX_BUFFERS = 3;
    private static final List<ByteBuffer> EOF = List.of();
    private static final List<ByteBuffer> NOTHING = List.of(CoreUtils.EMPTY_BYTEBUFFER);

    // readAvailable() will read bytes into the 'current' ByteBuffer until
    // the ByteBuffer is full, or 0 or -1 (EOF) is returned by read().
    // When that happens, a slice of the data that has been read so far
    // is inserted into the returned buffer list, and if the current buffer
    // has remaining space, that space will be used to read more data when
    // the chan becomes readable again.
    private List<ByteBuffer> readAvailable(BufferSource buffersSource) throws IOException {
        ByteBuffer buf = buffersSource.getBuffer();
        assert buf.hasRemaining();

        int read;
        int pos = buf.position();
        List<ByteBuffer> list = null;
        while (buf.hasRemaining()) {
            try {
                while ((read = chan.read(buf)) > 0) {
                    if (!buf.hasRemaining())
                        break;
                }
            } catch (IOException x) {
                if (buf.position() == pos && list == null) {
                    // make sure that the buffer source will recycle
                    // 'buf' if needed
                    buffersSource.returnUnused(buf);
                    // no bytes have been read, just throw...
                    throw x;
                } else {
                    // some bytes have been read, return them and fail next time
                    errorRef.compareAndSet(null, x);
                    read = 0; // ensures outer loop will exit
                }
            }

            // nothing read;
            if (buf.position() == pos) {
                // An empty list signals the end of data, and should only be
                // returned if read == -1. If some data has already been read,
                // then it must be returned. -1 will be returned next time
                // the caller attempts to read something.
                buffersSource.returnUnused(buf);
                if (list == null) {
                    // nothing read - list was null - return EOF or NOTHING
                    list = read == -1 ? EOF : NOTHING;
                }
                break;
            }

            // check whether this buffer has still some free space available.
            // if so, we will keep it for the next round.
            list = buffersSource.append(list, buf, pos);

            if (read <= 0 || list.size() == MAX_BUFFERS) {
                break;
            }

            buf = buffersSource.getBuffer();
            pos = buf.position();
            assert buf.hasRemaining();
        }
        return list;
    }

    protected static <T> List<T> listOf(List<T> list, T item) {
        int size = list == null ? 0 : list.size();
        switch (size) {
            case 0: return List.of(item);
            case 1: return List.of(list.get(0), item);
            case 2: return List.of(list.get(0), list.get(1), item);
            default: // slow path if MAX_BUFFERS > 3
                List<T> res = list instanceof ArrayList ? list : new ArrayList<>(list);
                res.add(item);
                return res;
        }
    }

    private long writeAvailable(List<ByteBuffer> bytes) throws IOException {
        ByteBuffer[] srcs = bytes.toArray(CoreUtils.EMPTY_BB_ARRAY);
        final long remaining = CoreUtils.remaining(srcs);
        long written = 0;
        while (remaining > written) {
            try {
                long w = chan.write(srcs);
                assert w >= 0 : "negative number of bytes written:" + w;
                if (w == 0) {
                    break;
                }
                written += w;
            } catch (IOException x) {
                if (written == 0) {
                    // no bytes were written just throw
                    throw x;
                } else {
                    // return how many bytes were written, will fail next time
                    break;
                }
            }
        }
        return written;
    }

    private void resumeEvent(SelectableChannelFlowEvent<T> event,
                             Consumer<Throwable> errorSignaler) {
        boolean registrationRequired;
        synchronized(lock) {
            registrationRequired = !event.registered();
            event.resume();
        }
        try {
            if (registrationRequired) {
                serverOrClient.registerEvent(event);
             } else {
                serverOrClient.eventUpdated(event);
            }
        } catch(Throwable t) {
            errorSignaler.accept(t);
        }
   }

    private void pauseEvent(SelectableChannelFlowEvent<T> event,
                            Consumer<Throwable> errorSignaler) {
        synchronized(lock) {
            event.pause();
        }
        try {
            serverOrClient.eventUpdated(event);
        } catch(Throwable t) {
            errorSignaler.accept(t);
        }
    }

    @Override
    public void connectFlows(TubePublisher<List<ByteBuffer>> writePublisher,
                             TubeSubscriber<List<ByteBuffer>> readSubscriber) {
        if (logger.isDebugEnabled()) logger.debug("connecting flows");
        this.subscribe(readSubscriber);
        writePublisher.subscribe(this);
    }


    @Override
    public String toString() {
        return dbgString();
    }

    private String dbgString() {
        return "ChanTube("+id+")";
    }

    protected final String channelDescr() {
        return String.valueOf(chan);
    }
}
