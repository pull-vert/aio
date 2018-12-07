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
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public final class SocketChan extends Chan<SocketChannel> {

    private SocketChannel socketChannel;

    public SocketChan(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    @Override
    protected SocketChannel getChannel() {
        return socketChannel;
    }

    @Override
    protected int read(ByteBuffer buf) throws IOException {
        return socketChannel.read(buf);
    }

    @Override
    protected long write(ByteBuffer[] srcs) throws IOException {
        return socketChannel.write(srcs);
    }
}
