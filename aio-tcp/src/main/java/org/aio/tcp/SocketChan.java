/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.tcp;

import org.aio.core.selectable.SelectableChan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Class abstraction over a {@linkplain java.nio.channels.SocketChannel NIO Socket Channel} : A selectable channel for stream-oriented connecting sockets.
 * It provides only methods we use, with limited visibility
 * @author Fred Montariol
 */
public final class SocketChan extends SelectableChan<SocketChannel> {

    private final Logger logger = LoggerFactory.getLogger(SocketChan.class);

    private SocketChannel socketChannel;

    SocketChan(SocketChannel socketChannel) {
        super(socketChannel);
        this.socketChannel = socketChannel;
    }

    @Override
    public int read(ByteBuffer buf) throws IOException {
        logger.debug("Reading");
        return socketChannel.read(buf);
    }

    @Override
    public long write(ByteBuffer[] srcs) throws IOException {
        logger.debug("Writing");
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
}
