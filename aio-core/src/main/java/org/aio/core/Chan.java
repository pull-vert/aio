/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core;

import org.aio.core.api.ChanAPI;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.spi.AbstractInterruptibleChannel;

/**
 * Abstract class abstraction over a {@linkplain java.nio.channels.spi.AbstractInterruptibleChannel NIO Interruptible Channel} : Base implementation class for interruptible channels
 * It provides only methods we use, with limited visibility
 *
 * @param <T> type of AbstractInterruptibleChannel
 * @author Fred Montariol
 */
public abstract class Chan<T extends AbstractInterruptibleChannel> implements ChanAPI {

    private T delegate;

    public Chan(T delegate) {
        this.delegate = delegate;
    }

    public boolean isOpen() {
        return delegate.isOpen();
    }

    public void close() throws IOException {
        delegate.close();
    }

    /**
     * Read some bytes from the Channel and write them in the provided buf ByteBuffer
     *
     * @param buf the ByteBuffer
     * @return number of read bytes
     * @throws IOException a IO Exception that may occur during read operation
     */
    abstract public int read(ByteBuffer buf) throws IOException;

    /**
     * Write bytes from provided srcs ByteBuffer array to the Channel
     * It's a gathering write
     *
     * @param srcs ByteBuffer array containing values to write
     * @return number of written bytes
     * @throws IOException a IO Exception that may occur during write operation
     */
    abstract public long write(ByteBuffer[] srcs) throws IOException;

    @Override
    public String toString() {
        return delegate.toString();
    }
}
