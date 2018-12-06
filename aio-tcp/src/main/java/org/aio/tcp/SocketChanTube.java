/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
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

package org.aio.tcp;

import org.aio.core.common.BufferSupplier;
import org.aio.core.FlowTube;
import org.aio.core.ChanTube;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * SocketChanTube is a ChanTube implementation is a terminal tube plugged directly into
 * the {@linkplain java.nio.channels.SocketChannel TCP Socket Channel}
 * <br>
 * The read subscriber should call {@code subscribe} on the SocketChanTube before
 * the SocketChanTube is subscribed to the write publisher.
 */
public class SocketChanTube extends ChanTube {

    private final TcpServerOrClient tcpServerOrClient;
    private final SliceBufferSource sliceBuffersSource;

    public SocketChanTube(TcpServerOrClient tcpServerOrClient, SocketChan socketChan,
                    Supplier<ByteBuffer> buffersFactory) {
        super(tcpServerOrClient, socketChan);
        this.tcpServerOrClient = tcpServerOrClient;
        this.sliceBuffersSource = new SliceBufferSource(buffersFactory);
    }

    @Override
    protected ChanTube.BufferSource getBufferSource(FlowTube.TubeSubscriber subscriber) {
        return subscriber.supportsRecycling()
                ? new SocketChanTube.SSLDirectBufferSource(tcpServerOrClient)
                : sliceBuffersSource;
    }

    // An implementation of BufferSource used for encrypted data.
    // This buffer source uses direct byte buffers that will be
    // recycled by the ChanTube subscriber.
    //
    private static final class SSLDirectBufferSource implements BufferSource {
        private final BufferSupplier factory;
        private final TcpServerOrClient tcpServerOrClient;
        private ByteBuffer current;

        public SSLDirectBufferSource(TcpServerOrClient tcpServerOrClient) {
            this.tcpServerOrClient = Objects.requireNonNull(tcpServerOrClient);
            this.factory = Objects.requireNonNull(tcpServerOrClient.getSSLBufferSupplier());
        }

        // Obtains a 'free' byte buffer from the pool, or returns
        // the same buffer if nothing was read at the previous cycle.
        // The subscriber will be responsible for recycling this
        // buffer into the pool (see SSLFlowDelegate.Reader)
        @Override
        public final ByteBuffer getBuffer() {
            assert tcpServerOrClient.isSelectorThread();
            ByteBuffer buf = current;
            if (buf == null) {
                buf = current = factory.get();
            }
            assert buf.hasRemaining();
            assert buf.position() == 0;
            return buf;
        }

        // Adds the buffer to the list. The buffer will be later returned to the
        // pool by the subscriber (see SSLFlowDelegate.Reader).
        // The next buffer returned by getBuffer() will be obtained from the
        // pool. It might be the same buffer or another one.
        // Because socket tube can read up to MAX_BUFFERS = 3 buffers, and because
        // recycling will happen in the flow before onNext returns, then the
        // pool can not grow larger than MAX_BUFFERS = 3 buffers, even though
        // it's shared by all SSL connections opened on that serverOrClient.
        @Override
        public final List<ByteBuffer> append(List<ByteBuffer> list, ByteBuffer buf, int start) {
            assert tcpServerOrClient.isSelectorThread();
            assert buf.isDirect();
            assert start == 0;
            assert current == buf;
            current = null;
            buf.limit(buf.position());
            buf.position(start);
            // add the buffer to the list
            return ChanTube.listOf(list, buf);
        }

        @Override
        public void returnUnused(ByteBuffer buffer) {
            // if current is null, then the buffer will have been added to the
            // list, through append. Otherwise, current is not null, and needs
            // to be returned to prevent the buffer supplier pool from growing
            // to more than MAX_BUFFERS.
            assert buffer == current;
            ByteBuffer buf = current;
            if (buf != null) {
                assert buf.position() == 0;
                current = null;
                // the supplier assert if buf has remaining
                buf.limit(buf.position());
                factory.recycle(buf);
            }
        }
    }

    @Override
    public String toString() {
        return dbgString();
    }

    private String dbgString() {
        return "SocketChanTube("+id+")";
    }
}
