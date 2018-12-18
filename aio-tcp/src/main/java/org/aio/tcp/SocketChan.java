/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.tcp;

import org.aio.core.Chan;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Class abstraction over a {@linkplain SocketChannel NIO Socket Channel}
 * It provides only methods we use, with limited visibility
 */
public final class SocketChan extends Chan<SocketChannel> {

    private SocketChannel socketChannel;

    SocketChan(SocketChannel socketChannel) {
        super(socketChannel);
        this.socketChannel = socketChannel;
    }

    @Override
    protected int read(ByteBuffer buf) throws IOException {
        return socketChannel.read(buf);
    }

    @Override
    protected long write(ByteBuffer[] srcs) throws IOException {
        return socketChannel.write(srcs);
    }

    boolean isBlocking() {
        return socketChannel.isBlocking();
    }

    boolean finishConnect() throws IOException {
        return socketChannel.finishConnect();
    }

    SocketAddress getLocalAddress() throws IOException {
        return socketChannel.getLocalAddress();
    }

    void configureNonBlocking() throws IOException {
        socketChannel.configureBlocking(false);
    }

    <T> void setOption(SocketOption<T> name, T value) throws IOException {
        socketChannel.setOption(name, value);
    }

    <T> T getOption(SocketOption<T> name) throws IOException {
        return socketChannel.getOption(name);
    }

    void close() throws IOException {
        socketChannel.close();
    }
}
