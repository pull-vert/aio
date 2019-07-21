/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.common.SSLFlowDelegate
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
import org.aio.core2.internal.concurrent.MinimalFuture;
import org.aio.core2.internal.concurrent.SequentialScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;

/**
 * Implements SSL using two SubscriberWrappers.
 *
 * <p> Constructor takes two Flow.Subscribers: one that receives the network
 * data (after it has been encrypted by SSLFlowDelegate) data, and one that
 * receives the application data (before it has been encrypted by SSLFlowDelegate).
 *
 * <p> Methods upstreamReader() and upstreamWriter() return the corresponding
 * Flow.Subscribers containing Flows for the encrypted/decrypted upstream data.
 * See diagram below.
 *
 * <p> How Flow.Subscribers are used in this class, and where they come from:
 * <pre>
 * {@code
 *
 *
 *
 * --------->  data flow direction
 *
 *
 *                         +------------------+
 *        upstreamWriter   |                  | downWriter
 *        ---------------> |                  | ------------>
 *  obtained from this     |                  | supplied to constructor
 *                         | SSLFlowDelegate  |
 *        downReader       |                  | upstreamReader
 *        <--------------- |                  | <--------------
 * supplied to constructor |                  | obtained from this
 *                         +------------------+
 *
 * Errors are reported to the downReader Flow.Subscriber
 *
 * }
 * </pre>
 */
public class SSLFlowDelegate {

    final Logger logger = LoggerFactory.getLogger(SSLFlowDelegate.class);

    private static final ByteBuffer SENTINEL = CoreUtils.EMPTY_BYTEBUFFER;
    private static final ByteBuffer HS_TRIGGER = ByteBuffer.allocate(0);
    // When handshake is in progress trying to wrap may produce no bytes.
    private static final ByteBuffer NOTHING = ByteBuffer.allocate(0);
    private static final String monProp = CoreUtils.getProperty("jdk.internal.httpclient.monitorFlowDelegate");
    private static final boolean isMonitored = monProp != null && (monProp.equals("") || monProp.equalsIgnoreCase("true"));

    final Executor exec;
    final SSLFlowDelegate.Reader reader;
    final SSLFlowDelegate.Writer writer;
    final SSLEngine engine;
    final String tubeName; // hack
    final CompletableFuture<String> alpnCF; // completes on initial handshake
    final SSLFlowDelegate.Monitorable monitor = isMonitored ? this::monitor : null; // prevent GC until SSLFD is stopped
    volatile boolean close_notify_received;
    final CompletableFuture<Void> readerCF;
    final CompletableFuture<Void> writerCF;
    final Consumer<ByteBuffer> recycler;
    static AtomicInteger scount = new AtomicInteger(1);
    final int id;

    private final Lock lock = new ReentrantLock();

    /**
     * Creates an SSLFlowDelegate fed from two Flow.Subscribers. Each
     * Flow.Subscriber requires an associated {@link CompletableFuture}
     * for errors that need to be signaled from downstream to upstream.
     */
    public SSLFlowDelegate(SSLEngine engine,
                           Executor exec,
                           Subscriber<? super Bybu> downReader,
                           Subscriber<? super Bybu> downWriter)
    {
        this(engine, exec, null, downReader, downWriter);
    }

    /**
     * Creates an SSLFlowDelegate fed from two Flow.Subscribers. Each
     * Flow.Subscriber requires an associated {@link CompletableFuture}
     * for errors that need to be signaled from downstream to upstream.
     */
    public SSLFlowDelegate(SSLEngine engine,
                           Executor exec,
                           Consumer<ByteBuffer> recycler,
                           Subscriber<? super Bybu> downReader,
                           Subscriber<? super Bybu> downWriter)
    {
        this.id = scount.getAndIncrement();
        this.tubeName = String.valueOf(downWriter);
        this.recycler = recycler;
        this.reader = new SSLFlowDelegate.Reader();
        this.writer = new SSLFlowDelegate.Writer();
        this.engine = engine;
        this.exec = exec;
        this.handshakeState = new AtomicInteger(NOT_HANDSHAKING);
        this.readerCF = reader.completion();
        this.writerCF = reader.completion();
        readerCF.exceptionally(this::stopOnError);
        writerCF.exceptionally(this::stopOnError);

        CompletableFuture.allOf(reader.completion(), writer.completion())
            .thenRun(this::normalStop);
        this.alpnCF = new MinimalFuture<>();

        // connect the Reader to the downReader and the
        // Writer to the downWriter.
        connect(downReader, downWriter);

        if (isMonitored) SSLFlowDelegate.Monitor.add(monitor);
    }

    /**
     * Returns true if the SSLFlowDelegate has detected a TLS close_notify from the server.
     * @return true, if a close_notify was detected.
     */
    public boolean closeNotifyReceived() {
        return close_notify_received;
    }

    /**
     * Connects the read sink (downReader) to the SSLFlowDelegate Reader,
     * and the write sink (downWriter) to the SSLFlowDelegate Writer.
     * Called from within the constructor. Overwritten by SSLTube.
     *
     * @param downReader  The left hand side read sink (typically, the
     *                    HttpConnection read subscriber).
     * @param downWriter  The right hand side write sink (typically
     *                    the SocketTube write subscriber).
     */
    void connect(Subscriber<? super Bybu> downReader,
                 Subscriber<? super Bybu> downWriter) {
        this.reader.subscribe(downReader);
        this.writer.subscribe(downWriter);
    }

    /**
     * Returns a CompletableFuture<String> which completes after the initial handshake completes,
     * and which contains the negotiated alpn.
     */
    public CompletableFuture<String> alpn() {
        return alpnCF;
    }

    private void setALPN() {
        // Handshake is finished. So, can retrieve the ALPN now
        if (alpnCF.isDone())
            return;
        var alpn = engine.getApplicationProtocol();
        if (logger.isDebugEnabled()) logger.debug("setALPN = " + alpn);
        alpnCF.complete(alpn);
    }

    public String monitor() {
        var sb = new StringBuilder();
        sb.append("SSL: id ").append(id);
        sb.append(" ").append(dbgString());
        sb.append(" HS state: ").append(states(handshakeState));
        sb.append(" Engine state: ").append(engine.getHandshakeStatus().toString());
        if (stateList != null) {
            sb.append(" LL : ");
            for (String s : stateList) {
                sb.append(s).append(" ");
            }
        }
        sb.append("\r\n");
        sb.append("Reader:: ").append(reader.toString());
        sb.append("\r\n");
        sb.append("Writer:: ").append(writer.toString());
        sb.append("\r\n===================================");
        return sb.toString();
    }

    protected SubscriberWrapper.SchedulingAction enterReadScheduling() {
        return SubscriberWrapper.SchedulingAction.CONTINUE;
    }


    /**
     * Processing function for incoming data. Pass it thru SSLEngine.unwrap().
     * Any decrypted buffers returned to be passed downstream.
     * Status codes:
     *     NEED_UNWRAP: do nothing. Following incoming data will contain any required handshake data
     *     NEED_WRAP: call writer.addData() with empty buffer
     *     NEED_TASK: delegate task to executor
     *     BUFFER_OVERFLOW: allocate larger output buffer. Repeat unwrap
     *     BUFFER_UNDERFLOW: keep buffer and wait for more data
     *     OK: return generated buffers.
     *
     * Upstream subscription strategy is to try and keep no more than TARGET_BUFSIZE bytes in readBuf
     */
    final class Reader extends SubscriberWrapper implements FlowTube.TubeSubscriber {
        // Maximum record size is 16k.
        // Because SocketTube can feeds us up to 3 16K buffers, then setting this size to 16K means that the readBuf can store up to 64K-1 (16K-1 + 3*16K)
        static final int TARGET_BUFSIZE = 16 * 1024;

        final SequentialScheduler scheduler;
        volatile ByteBuffer readBuf;
        volatile boolean completing;
        final Lock readBufferLock = new ReentrantLock();
        final Logger loggerr =  LoggerFactory.getLogger(Reader.class);

        private final class ReaderDownstreamPusher implements Runnable {
            @Override
            public void run() {
                processData();
            }
        }

        Reader() {
            super();
            scheduler = SequentialScheduler.lockedScheduler(new SSLFlowDelegate.Reader.ReaderDownstreamPusher());
            this.readBuf = ByteBuffer.allocate(1024);
            readBuf.limit(0); // keep in read mode
        }

        @Override
        public boolean supportsRecycling() {
            return recycler != null;
        }

        protected SchedulingAction enterScheduling() {
            return enterReadScheduling();
        }

        public final String dbgString() {
            return "SSL Reader(" + tubeName + ")";
        }

        /**
         * entry point for buffers delivered from upstream Subscriber
         */
        @Override
        public void incoming(Bybu bybu, boolean complete) {
            if (loggerr.isDebugEnabled()) loggerr.debug("Adding {} bytes to read buffer", bybu.remaining());
            addToReadBuf(bybu, complete);
            scheduler.runOrSchedule(exec);
        }

        @Override
        public String toString() {
            return "READER: " + super.toString() + ", readBuf: " + readBuf.toString()
                + ", count: " + count.toString() + ", scheduler: "
                + (scheduler.isStopped() ? "stopped" : "running")
                + ", status: " + lastUnwrapStatus;
        }

        private void reallocReadBuf() {
            var sz = readBuf.capacity();
            var newb = ByteBuffer.allocate(sz * 2);
            readBuf.flip();
            CoreUtils.copy(readBuf, newb);
            readBuf = newb;
        }

        @Override
        protected long upstreamWindowUpdate(long currentWindow, long downstreamQsize) {
            if (readBuf.remaining() > TARGET_BUFSIZE) {
                if (loggerr.isDebugEnabled()) loggerr.debug("readBuf has more than TARGET_BUFSIZE: " + readBuf.remaining());
                return 0;
            } else {
                return super.upstreamWindowUpdate(currentWindow, downstreamQsize);
            }
        }

        // readBuf is kept ready for reading outside of this method
        private void addToReadBuf(Bybu bybu, boolean complete) {
            assert bybu.remaining() > 0 || bybu.isEmpty();
            bybu.lockedConsume(readBufferLock, bufs -> {
                for (ByteBuffer buf : bufs) {
                    readBuf.compact();
                    while (readBuf.remaining() < buf.remaining()) {
                        reallocReadBuf();
                    }
                    readBuf.put(buf);
                    readBuf.flip();
                    // should be safe to call inside lock since the only implementation offers the buffer to an unbounded queue.
                    // WARNING: do not touch buf after this point!
                    if (recycler != null) {
                        recycler.accept(buf);
                    }
                }
                if (complete) {
                    this.completing = complete;
                    minBytesRequired = 0;
                }
            });
        }

        void schedule() {
            scheduler.runOrSchedule(exec);
        }

        void stop() {
            if (loggerr.isDebugEnabled()) loggerr.debug("stop");
            scheduler.stop();
        }

        AtomicInteger count = new AtomicInteger(0);

        // minimum number of bytes required to call unwrap. Usually this is 0, unless there was a buffer underflow.
        // In this case we need to wait for more bytes than what we had before calling unwrap() again.
        volatile int minBytesRequired;

        // work function where it all happens
        final void processData() {
            try {
                if (loggerr.isDebugEnabled())
                    loggerr.debug("processData:"
                        + " readBuf remaining:" + readBuf.remaining()
                        + ", state:" + states(handshakeState)
                        + ", engine handshake status:" + engine.getHandshakeStatus());
                int len;
                var complete = false;
                while (readBuf.remaining() > (len = minBytesRequired)) {
                    var handshaking = false;
                    try {
                        SSLFlowDelegate.EngineResult result;
                        readBufferLock.lock();
                        try {
                            complete = this.completing;
                            if (loggerr.isDebugEnabled()) loggerr.debug("Unwrapping: " + readBuf.remaining());
                            // Unless there is a BUFFER_UNDERFLOW, we should try to unwrap any number of bytes.
                            // Set minBytesRequired to 0 : we only need to do that if minBytesRequired is not already 0.
                            if (len > 0) {
                                minBytesRequired = 0;
                            }
                            result = unwrapBuffer(readBuf);
                            len = readBuf.remaining();
                            if (loggerr.isDebugEnabled()) {
                                loggerr.debug("Unwrapped: result: {}", result.result);
                                loggerr.debug("Unwrapped: consumed: " + result.bytesConsumed());
                            }
                        } finally {
                            readBufferLock.unlock();
                        }
                        if (result.bytesProduced() > 0) {
                            if (loggerr.isDebugEnabled()) loggerr.debug("sending " + result.bytesProduced());
                            count.addAndGet(result.bytesProduced());
                            outgoing(result.destBuffer, false);
                        }
                        if (result.status() == Status.BUFFER_UNDERFLOW) {
                            if (loggerr.isDebugEnabled()) loggerr.debug("BUFFER_UNDERFLOW");
                            // not enough data in the read buffer...
                            // no need to try to unwrap again unless we get more bytes than minBytesRequired = len in the read buffer.
                            readBufferLock.lock();
                            try {
                                minBytesRequired = len;
                                // more bytes could already have been added...
                                assert readBuf.remaining() >= len;
                                // check if we have received some data, and if so
                                // we can just re-spin the loop
                                if (readBuf.remaining() > len) continue;
                                else if (this.completing) {
                                    if (logger.isDebugEnabled()) loggerr.debug("BUFFER_UNDERFLOW with EOF,{} bytes non decrypted.", len);
                                    // The channel won't send us any more data, and we are in underflow: we need to fail.
                                    throw new IOException("BUFFER_UNDERFLOW with EOF, " + len + " bytes non decrypted.");
                                }
                            } finally {
                                readBufferLock.unlock();
                            }
                            // request more data and return.
                            requestMore();
                            return;
                        }
                        if (complete && result.status() == Status.CLOSED) {
                            if (loggerr.isDebugEnabled()) loggerr.debug("Closed: completing");
                            outgoing(CoreUtils.EMPTY_BYBU, true);
                            return;
                        }
                        if (result.handshaking()) {
                            handshaking = true;
                            if (loggerr.isDebugEnabled()) loggerr.debug("handshaking");
                            if (doHandshake(result, READER)) continue; // need unwrap
                            else break; // doHandshake will have triggered the write scheduler if necessary
                        } else {
                            if ((handshakeState.getAndSet(NOT_HANDSHAKING) & ~DOING_TASKS) == HANDSHAKING) {
                                handshaking = false;
                                applicationBufferSize = engine.getSession().getApplicationBufferSize();
                                packetBufferSize = engine.getSession().getPacketBufferSize();
                                setALPN();
                                resumeActivity();
                            }
                        }
                    } catch (IOException ex) {
                        errorCommon(ex);
                        handleError(ex);
                        return;
                    }
                    if (handshaking && !complete)
                        return;
                }
                if (!complete) {
                    readBufferLock.lock();
                    try {
                        complete = this.completing && !readBuf.hasRemaining();
                    } finally {
                        readBufferLock.unlock();
                    }
                }
                if (complete) {
                    if (loggerr.isDebugEnabled()) loggerr.debug("completing");
                    // Complete the alpnCF, if not already complete, regardless of whether or not the ALPN is available,
                    // there will be no more activity.
                    setALPN();
                    outgoing(CoreUtils.EMPTY_BYBU, true);
                }
            } catch (Throwable ex) {
                errorCommon(ex);
                handleError(ex);
            }
        }

        private volatile Status lastUnwrapStatus;
        SSLFlowDelegate.EngineResult unwrapBuffer(ByteBuffer src) throws IOException {
            var dst = getAppBuffer();
            var len = src.remaining();
            while (true) {
                var sslResult = engine.unwrap(src, dst);
                switch (lastUnwrapStatus = sslResult.getStatus()) {
                    case BUFFER_OVERFLOW:
                        // may happen if app size buffer was changed, or if our 'adaptiveBufferSize' guess was too small for
                        // the current payload. In that case, update the value of applicationBufferSize, and allocate a
                        // buffer of that size, which we are sure will be big enough to decode whatever needs to be
                        // decoded. We will later update adaptiveBufferSize in OK: below.
                        var appSize = applicationBufferSize =
                            engine.getSession().getApplicationBufferSize();
                        var b = ByteBuffer.allocate(appSize + dst.position());
                        dst.flip();
                        b.put(dst);
                        dst = b;
                        break;
                    case CLOSED:
                        assert dst.position() == 0;
                        return doClosure(new SSLFlowDelegate.EngineResult(sslResult));
                    case BUFFER_UNDERFLOW:
                        // handled implicitly by compaction/reallocation of readBuf
                        assert dst.position() == 0;
                        return new SSLFlowDelegate.EngineResult(sslResult);
                    case OK:
                        var size = dst.position();
                        if (logger.isDebugEnabled())
                            loggerr.debug("Decoded " + size + " bytes out of " + len
                                + " into buffer of " + dst.capacity()
                                + " remaining to decode: " + src.remaining());
                        // if the record payload was bigger than what was originally allocated, then sets the adaptiveAppBufferSize
                        // to size and we will use that new size as a guess for the next app buffer.
                        if (size > adaptiveAppBufferSize) {
                            adaptiveAppBufferSize = ((size + 7) >>> 3) << 3;
                        }
                        dst.flip();
                        return new SSLFlowDelegate.EngineResult(sslResult, dst);
                }
            }
        }
    }

    public interface Monitorable {
        public String getInfo();
    }

    public static class Monitor extends Thread {
        final List<WeakReference<SSLFlowDelegate.Monitorable>> list;
        final List<SSLFlowDelegate.Monitor.FinalMonitorable> finalList;
        final ReferenceQueue<SSLFlowDelegate.Monitorable> queue = new ReferenceQueue<>();
        static SSLFlowDelegate.Monitor themon;

        static {
            themon = new SSLFlowDelegate.Monitor();
            themon.start(); // uncomment to enable Monitor
        }

        // An instance used to temporarily store the
        // last observable state of a monitorable object.
        // When Monitor.remove(o) is called, we replace
        // 'o' with a FinalMonitorable whose reference
        // will be enqueued after the last observable state
        // has been printed.
        final class FinalMonitorable implements SSLFlowDelegate.Monitorable {
            final String finalState;
            FinalMonitorable(SSLFlowDelegate.Monitorable o) {
                finalState = o.getInfo();
                finalList.add(this);
            }
            @Override
            public String getInfo() {
                finalList.remove(this);
                return finalState;
            }
        }

        Monitor() {
            super("Monitor");
            setDaemon(true);
            list = Collections.synchronizedList(new LinkedList<>());
            finalList = new ArrayList<>(); // access is synchronized on list above
        }

        void addTarget(SSLFlowDelegate.Monitorable o) {
            list.add(new WeakReference<>(o, queue));
        }
        void removeTarget(SSLFlowDelegate.Monitorable o) {
            // It can take a long time for GC to clean up references.
            // Calling Monitor.remove() early helps removing noise from the
            // logs/
            synchronized (list) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var m = it.next().get();
                    if (m == null) {
                        it.remove();
                    }
                    if (o == m) {
                        it.remove();
                        break;
                    }
                }
               var m = new SSLFlowDelegate.Monitor.FinalMonitorable(o);
                addTarget(m);
                Reference.reachabilityFence(m);
            }
        }

        public static void add(SSLFlowDelegate.Monitorable o) {
            themon.addTarget(o);
        }
        public static void remove(SSLFlowDelegate.Monitorable o) {
            themon.removeTarget(o);
        }

        @Override
        public void run() {
            System.out.println("Monitor starting");
            try {
                while (true) {
                    Thread.sleep(20 * 1000);
                    synchronized (list) {
                        Reference<? extends SSLFlowDelegate.Monitorable> expired;
                        while ((expired = queue.poll()) != null) list.remove(expired);
                        for (var ref : list) {
                            var o = ref.get();
                            if (o == null) {
                                continue;
                            }
                            if (o instanceof SSLFlowDelegate.Monitor.FinalMonitorable) {
                                ref.enqueue();
                            }
                            System.out.println(o.getInfo());
                            System.out.println("-------------------------");
                        }
                    }
                    System.out.println("--o-o-o-o-o-o-o-o-o-o-o-o-o-o-");
                }
            } catch (InterruptedException e) {
                System.out.println("Monitor exiting with " + e);
            }
        }
    }

    /**
     * Processing function for outgoing data. Pass it thru SSLEngine.wrap()
     * Any encrypted buffers generated are passed downstream to be written.
     * Status codes:
     *     NEED_UNWRAP: call reader.addData() with empty buffer
     *     NEED_WRAP: call addData() with empty buffer
     *     NEED_TASK: delegate task to executor
     *     BUFFER_OVERFLOW: allocate larger output buffer. Repeat wrap
     *     BUFFER_UNDERFLOW: shouldn't happen on writing side
     *     OK: return generated buffers
     */
    class Writer extends SubscriberWrapper {
        final SequentialScheduler scheduler;
        // queues of buffers received from upstream waiting
        // to be processed by the SSLEngine
        final Bybu writeBybu;
        final Logger loggerw =  LoggerFactory.getLogger(Writer.class);
        volatile boolean completing;
        boolean completed; // only accessed in processData

        class WriterDownstreamPusher extends SequentialScheduler.CompleteRestartableTask {
            @Override public void run() { processData(); }
        }

        Writer() {
            super();
            writeBybu = Bybu.fromList(new LinkedList<>());
            scheduler = new SequentialScheduler(new SSLFlowDelegate.Writer.WriterDownstreamPusher());
        }

        @Override
        protected void incoming(Bybu bybu, boolean complete) {
            assert complete ? bybu == CoreUtils.EMPTY_BYBU : true;
            assert bybu != CoreUtils.EMPTY_BYBU ? complete == false : true;
            if (complete) {
                if (loggerw.isDebugEnabled()) loggerw.debug("adding SENTINEL");
                completing = true;
                writeBybu.add(SENTINEL);
            } else {
                writeBybu.addAll(bybu);
            }
            if (loggerw.isDebugEnabled())
                loggerw.debug("added " + bybu.size()
                    + " (" + bybu.remaining()
                    + " bytes) to the writeList");
            scheduler.runOrSchedule();
        }

        public final String dbgString() {
            return "SSL Writer(" + tubeName + ")";
        }

        protected void onSubscribe() {
            if (loggerw.isDebugEnabled()) loggerw.debug("onSubscribe initiating handshaking");
            addData(HS_TRIGGER);  // initiates handshaking
        }

        void schedule() {
            scheduler.runOrSchedule();
        }

        void stop() {
            if (loggerw.isDebugEnabled()) loggerw.debug("stop");
            scheduler.stop();
        }

        @Override
        public boolean closing() {
            return closeNotifyReceived();
        }

        private boolean isCompleting() {
            return completing;
        }

        @Override
        protected long upstreamWindowUpdate(long currentWindow, long downstreamQsize) {
            if (writeBybu.size() > 10)
                return 0;
            else
                return super.upstreamWindowUpdate(currentWindow, downstreamQsize);
        }

        private boolean hsTriggered() {
            return writeBybu.lockedPredicate(writeBufs -> {
                for (var b : writeBufs) {
                    if (b == HS_TRIGGER) {
                        return true;
                    }
                }
                return false;
            });
        }

        void triggerWrite() {
            writeBybu.lockedConsume(writeBufs -> {
                if (writeBufs.isEmpty()) {
                    writeBufs.add(HS_TRIGGER);
                }
            });
            scheduler.runOrSchedule();
        }

        private void processData() {
            var completing = isCompleting();

            try {
                if (loggerw.isDebugEnabled())
                    loggerw.debug("processData, writeList remaining:"
                        + writeBybu.remaining() + ", hsTriggered:"
                        + hsTriggered() + ", needWrap:" + needWrap());

                while (writeBybu.remaining() > 0 || hsTriggered() || needWrap()) {
                    var outbufs = writeBybu.toArray();
                    var result = wrapBuffers(outbufs);
                    if (loggerw.isDebugEnabled())
                        loggerw.debug("wrapBuffer returned {}", result.result);

                    if (result.status() == Status.CLOSED) {
                        if (!upstreamCompleted) {
                            upstreamCompleted = true;
                            upstreamSubscription.cancel();
                        }
                        if (result.bytesProduced() <= 0)
                            return;

                        if (!completing && !completed) {
                            completing = this.completing = true;
                            // There could still be some outgoing data in outbufs.
                            writeBybu.add(SENTINEL);
                        }
                    }

                    var handshaking = false;
                    if (result.handshaking()) {
                        if (loggerw.isDebugEnabled()) loggerw.debug("handshaking");
                        doHandshake(result, WRITER);  // ok to ignore return
                        handshaking = true;
                    } else {
                        if ((handshakeState.getAndSet(NOT_HANDSHAKING) & ~DOING_TASKS) == HANDSHAKING) {
                            applicationBufferSize = engine.getSession().getApplicationBufferSize();
                            packetBufferSize = engine.getSession().getPacketBufferSize();
                            setALPN();
                            resumeActivity();
                        }
                    }
                    cleanBybu(writeBybu); // tidy up the source list
                    sendResultBytes(result);
                    if (handshaking) {
                        if (!completing && needWrap()) {
                            continue;
                        } else {
                            return;
                        }
                    }
                }
                if (completing && writeBybu.remaining() == 0) {
                    if (!completed) {
                        completed = true;
                        writeBybu.clear();
                        outgoing(CoreUtils.EMPTY_BYBU, true);
                    }
                    return;
                }
                if (writeBybu.isEmpty() && needWrap()) {
                    writer.addData(HS_TRIGGER);
                }
            } catch (Throwable ex) {
                errorCommon(ex);
                handleError(ex);
            }
        }

        // The SSLEngine insists on being given a buffer that is at least SSLSession.getPacketBufferSize() long (usually 16K).
        // If given a smaller buffer it will go in BUFFER_OVERFLOW, even if it only has 6 bytes to wrap. Typical usage shows
        // that for GET we usually produce an average of ~ 100 bytes. To avoid wasting space, and because allocating and zeroing
        // 16K buffers for encoding 6 bytes is costly, we are reusing the same writeBuffer to interact with SSLEngine.wrap().
        // If the SSLEngine produces less than writeBuffer.capacity() / 2, then we copy off the bytes to a smaller buffer that
        // we send downstream. Otherwise, we send the writeBuffer downstream and will allocate a new one next time.
        volatile ByteBuffer writeBuffer;
        private volatile Status lastWrappedStatus;
        @SuppressWarnings("fallthrough")
        SSLFlowDelegate.EngineResult wrapBuffers(ByteBuffer[] src) throws SSLException {
            var len = CoreUtils.remaining(src);
            if (loggerw.isDebugEnabled()) loggerw.debug("wrapping " + len + " bytes");

            var dst = writeBuffer;
            if (dst == null) dst = writeBuffer = getNetBuffer();
            assert dst.position() == 0 : "buffer position is " + dst.position();
            assert dst.hasRemaining() : "buffer has no remaining space: capacity=" + dst.capacity();

            while (true) {
                var sslResult = engine.wrap(src, dst);
                if (loggerw.isDebugEnabled()) loggerw.debug("SSLResult: " + sslResult);
                switch (lastWrappedStatus = sslResult.getStatus()) {
                    case BUFFER_OVERFLOW:
                        // Shouldn't happen. We allocated buffer with packet size get it again if net buffer size was changed
                        if (loggerw.isDebugEnabled()) loggerw.debug("BUFFER_OVERFLOW");
                        var netSize = packetBufferSize
                            = engine.getSession().getPacketBufferSize();
                        var b = writeBuffer = ByteBuffer.allocate(netSize + dst.position());
                        dst.flip();
                        b.put(dst);
                        dst = b;
                        break; // try again
                    case CLOSED:
                        if (loggerw.isDebugEnabled()) loggerw.debug("CLOSED");
                        // fallthrough. There could be some remaining data in dst. CLOSED will be handled by the caller.
                    case OK:
                        final ByteBuffer dest;
                        if (dst.position() == 0) {
                            dest = NOTHING; // can happen if handshake is in progress
                        } else if (dst.position() < dst.capacity() / 2) {
                            // less than half the buffer was used. copy off the bytes to a smaller buffer, and keep
                            // the writeBuffer for next time.
                            dst.flip();
                            dest = CoreUtils.copyAligned(dst);
                            dst.clear();
                        } else {
                            // more than half the buffer was used.
                            // just send that buffer downstream, and we will
                            // get a new writeBuffer next time it is needed.
                            dst.flip();
                            dest = dst;
                            writeBuffer = null;
                        }
                        if (loggerw.isDebugEnabled())
                            loggerw.debug("OK => produced: {} bytes into {}, not wrapped: {}",
                                dest.remaining(),  dest.capacity(), CoreUtils.remaining(src));
                        return new SSLFlowDelegate.EngineResult(sslResult, dest);
                    case BUFFER_UNDERFLOW:
                        // Shouldn't happen.  Doesn't returns when wrap() underflow handled externally
                        // assert false : "Buffer Underflow";
                        if (logger.isDebugEnabled()) logger.debug("BUFFER_UNDERFLOW");
                        return new SSLFlowDelegate.EngineResult(sslResult);
                    default:
                        if (loggerw.isDebugEnabled())
                            loggerw.debug("result: {}", sslResult.getStatus());
                        assert false : "result:" + sslResult.getStatus();
                }
            }
        }

        private boolean needWrap() {
            return engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP;
        }

        private void sendResultBytes(SSLFlowDelegate.EngineResult result) {
            if (result.bytesProduced() > 0) {
                if (loggerw.isDebugEnabled())
                    loggerw.debug("Sending {} bytes downstream", result.bytesProduced());
                outgoing(result.destBuffer, false);
            }
        }

        @Override
        public String toString() {
            return "WRITER: " + super.toString()
                + ", writeList size: " + Integer.toString(writeBybu.size())
                + ", scheduler: " + (scheduler.isStopped() ? "stopped" : "running")
                + ", status: " + lastWrappedStatus;
            //" writeList: " + writeList.toString();
        }
    }

    private void handleError(Throwable t) {
        if (logger.isDebugEnabled()) logger.debug("handleError", t);
        readerCF.completeExceptionally(t);
        writerCF.completeExceptionally(t);
        // no-op if already completed
        alpnCF.completeExceptionally(t);
        reader.stop();
        writer.stop();
    }

    boolean stopped;

    private void normalStop() {
        lock.lock();
        try {
            if (stopped)
                return;
            stopped = true;
            reader.stop();
            writer.stop();
            if (isMonitored) SSLFlowDelegate.Monitor.remove(monitor);
        } finally {
            lock.unlock();
        }
    }

    private Void stopOnError(Throwable currentlyUnused) {
        // maybe log, etc
        normalStop();
        return null;
    }

    private void cleanBybu(Bybu bybu) {
        bybu.lockedConsume(bufs -> {
            Iterator<ByteBuffer> iter = bufs.iterator();
            // todo test with IntelliJ optimized Collection.removeIf...
            while (iter.hasNext()) {
                var b = iter.next();
                if (!b.hasRemaining() && b != SENTINEL) {
                    iter.remove();
                }
            }
        });
    }

    /**
     * States for handshake. We avoid races when accessing/updating the AtomicInt
     * because updates always schedule an additional call to both the read()
     * and write() functions.
     */
    private static final int NOT_HANDSHAKING = 0;
    private static final int HANDSHAKING = 1;

    // Bit flags
    // a thread is currently executing tasks
    private static final int DOING_TASKS = 4;
    // a thread wants to execute tasks, while another thread is executing
    private static final int REQUESTING_TASKS = 8;
    private static final int TASK_BITS = 12; // Both bits

    private static final int READER = 1;
    private static final int WRITER = 2;

    private static String states(AtomicInteger state) {
        var s = state.get();
        var sb = new StringBuilder();
        var x = s & ~TASK_BITS;
        switch (x) {
            case NOT_HANDSHAKING:
                sb.append(" NOT_HANDSHAKING ");
                break;
            case HANDSHAKING:
                sb.append(" HANDSHAKING ");
                break;
            default:
                throw new InternalError();
        }
        if ((s & DOING_TASKS) > 0)
            sb.append("|DOING_TASKS");
        if ((s & REQUESTING_TASKS) > 0)
            sb.append("|REQUESTING_TASKS");
        return sb.toString();
    }

    private void resumeActivity() {
        reader.schedule();
        writer.schedule();
    }

    final AtomicInteger handshakeState;
    final ConcurrentLinkedQueue<String> stateList =
        logger.isDebugEnabled() ? new ConcurrentLinkedQueue<>() : null;

    // Atomically executed to update task bits. Sets either DOING_TASKS or REQUESTING_TASKS
    // depending on previous value
    private static final IntBinaryOperator REQUEST_OR_DO_TASKS = (current, ignored) -> {
        if ((current & DOING_TASKS) == 0)
            return DOING_TASKS | (current & HANDSHAKING);
        else
            return DOING_TASKS | REQUESTING_TASKS | (current & HANDSHAKING);
    };

    // Atomically executed to update task bits. Sets DOING_TASKS if REQUESTING was set
    // clears bits if not.
    private static final IntBinaryOperator FINISH_OR_DO_TASKS = (current, ignored) -> {
        if ((current & REQUESTING_TASKS) != 0)
            return DOING_TASKS | (current & HANDSHAKING);
        // clear both bits
        return (current & HANDSHAKING);
    };

    private boolean doHandshake(SSLFlowDelegate.EngineResult r, int caller) {
        // unconditionally sets the HANDSHAKING bit, while preserving task bits
        handshakeState.getAndAccumulate(0, (current, unused) -> HANDSHAKING | (current & TASK_BITS));
        if (stateList != null && logger.isDebugEnabled()) {
            stateList.add(r.handshakeStatus().toString());
            stateList.add(Integer.toString(caller));
        }
        switch (r.handshakeStatus()) {
            case NEED_TASK:
                var s = handshakeState.accumulateAndGet(0, REQUEST_OR_DO_TASKS);
                if ((s & REQUESTING_TASKS) > 0) { // someone else is or will do tasks
                    return false;
                }

                if (logger.isDebugEnabled()) logger.debug("obtaining and initiating task execution");
                var tasks = obtainTasks();
                executeTasks(tasks);
                return false;  // executeTasks will resume activity
            case NEED_WRAP:
                if (caller == READER) {
                    writer.triggerWrite();
                    return false;
                }
                break;
            case NEED_UNWRAP:
            case NEED_UNWRAP_AGAIN:
                // do nothing else
                // receiving-side data will trigger unwrap
                if (caller == WRITER) {
                    reader.schedule();
                    return false;
                }
                break;
            default:
                throw new InternalError("Unexpected handshake status:" + r.handshakeStatus());
        }
        return true;
    }

    private List<Runnable> obtainTasks() {
        var l = new ArrayList<Runnable>();
        Runnable r;
        while ((r = engine.getDelegatedTask()) != null) {
            l.add(r);
        }
        return l;
    }

    private void executeTasks(List<Runnable> tasks) {
        exec.execute(() -> {
            try {
                var nextTasks = tasks;
                if (logger.isDebugEnabled()) logger.debug("#tasks to execute: " + nextTasks.size());
                do {
                    nextTasks.forEach(Runnable::run);
                    if (engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                        nextTasks = obtainTasks();
                    } else {
                        var s = handshakeState.accumulateAndGet(0, FINISH_OR_DO_TASKS);
                        if ((s & DOING_TASKS) != 0) {
                            if (logger.isDebugEnabled()) logger.debug("re-running tasks (B)");
                            nextTasks = obtainTasks();
                            continue;
                        }
                        break;
                    }
                } while (true);
                if (logger.isDebugEnabled()) logger.debug("finished task execution");
                resumeActivity();
            } catch (Throwable t) {
                handleError(t);
            }
        });
    }

    // FIXME: acknowledge a received CLOSE request from peer
    SSLFlowDelegate.EngineResult doClosure(SSLFlowDelegate.EngineResult r) throws IOException {
        if (logger.isDebugEnabled())
            logger.debug("doClosure({}): {} [isOutboundDone: {}, isInboundDone: {}]",
                r.result, engine.getHandshakeStatus(), engine.isOutboundDone(), engine.isInboundDone());
        if (engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
            // we have received TLS close_notify and need to send an acknowledgement back.
            // We're calling doHandshake to finish the close handshake.
            if (engine.isInboundDone() && !engine.isOutboundDone()) {
                if (logger.isDebugEnabled()) logger.debug("doClosure: close_notify received");
                close_notify_received = true;
                if (!writer.scheduler.isStopped()) {
                    doHandshake(r, READER);
                } else {
                    // We have received closed notify, but we
                    // won't be able to send the acknowledgement.
                    // Nothing more will come from the socket either,
                    // so mark the reader as completed.
                    reader.readBufferLock.lock();
                    try {
                        reader.completing = true;
                    } finally {
                        reader.readBufferLock.unlock();
                    }
                }
            }
        }
        return r;
    }

    /**
     * Returns the upstream Flow.Subscriber of the reading (incoming) side.
     * This flow must be given the encrypted data read from upstream (eg socket)
     * before it is decrypted.
     */
    public Flow.Subscriber<Bybu> upstreamReader() {
        return reader;
    }

    /**
     * Returns the upstream Flow.Subscriber of the writing (outgoing) side.
     * This flow contains the plaintext data before it is encrypted.
     */
    public Flow.Subscriber<Bybu> upstreamWriter() {
        return writer;
    }

    public boolean resumeReader() {
        return reader.signalScheduling();
    }

    public void resetReaderDemand() {
        reader.resetDownstreamDemand();
    }

    static class EngineResult {
        final SSLEngineResult result;
        final ByteBuffer destBuffer;

        // normal result
        EngineResult(SSLEngineResult result) {
            this(result, null);
        }

        EngineResult(SSLEngineResult result, ByteBuffer destBuffer) {
            this.result = result;
            this.destBuffer = destBuffer;
        }

        boolean handshaking() {
            var s = result.getHandshakeStatus();
            return s != HandshakeStatus.FINISHED
                && s != HandshakeStatus.NOT_HANDSHAKING
                && result.getStatus() != Status.CLOSED;
        }

        boolean needUnwrap() {
            var s = result.getHandshakeStatus();
            return s == HandshakeStatus.NEED_UNWRAP;
        }


        int bytesConsumed() {
            return result.bytesConsumed();
        }

        int bytesProduced() {
            return result.bytesProduced();
        }

        SSLEngineResult.HandshakeStatus handshakeStatus() {
            return result.getHandshakeStatus();
        }

        SSLEngineResult.Status status() {
            return result.getStatus();
        }
    }

    // The maximum network buffer size negotiated during the handshake. Usually 16K.
    volatile int packetBufferSize;
    final ByteBuffer getNetBuffer() {
        var netSize = packetBufferSize;
        if (netSize <= 0) {
            packetBufferSize = netSize = engine.getSession().getPacketBufferSize();
        }
        return ByteBuffer.allocate(netSize);
    }

    // The maximum application buffer size negotiated during the handshake. Usually close to 16K.
    volatile int applicationBufferSize;
    // Despite of the maximum applicationBufferSize negotiated above, TLS records usually have a much smaller payload.
    // The adaptativeAppBufferSize records the max payload ever decoded, and we use that as a guess for how big
    // a buffer we will need for the next payload. This avoids allocating and zeroing a 16K buffer for nothing...
    volatile int adaptiveAppBufferSize;
    final ByteBuffer getAppBuffer() {
        var appSize = applicationBufferSize;
        if (appSize <= 0) {
            applicationBufferSize = appSize
                = engine.getSession().getApplicationBufferSize();
        }
        var size = adaptiveAppBufferSize;
        if (size <= 0) {
            size = 512; // start with 512 this is usually enough for handshaking / headers
        } else if (size > appSize) {
            size = appSize;
        }
        // will cause a BUFFER_OVERFLOW if not big enough, but that's OK.
        return ByteBuffer.allocate(size);
    }

    final String dbgString() {
        return "SSLFlowDelegate(" + tubeName + ")";
    }
}
