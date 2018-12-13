/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core;

import org.aio.core.api.ChanAPI;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;

public abstract class Chan<T extends SelectableChannel> implements ChanAPI {

    /**
     * @return The delegated Channel
     */
    abstract protected T getChannel();

    /**
     * Read some bytes from the Channel and write them in the provided buf ByteBuffer
     *
     * @param buf the ByteBuffer
     * @return number of read bytes
     * @throws IOException a IO Exception that may occur during read operation
     */
    abstract protected int read(ByteBuffer buf) throws IOException;

    /**
     * Write bytes from provided srcs ByteBuffer array to the Channel
     *
     * @param srcs ByteBuffer array containing values to write
     * @return number of written bytes
     * @throws IOException a IO Exception that may occur during write operation
     */
    abstract protected long write(ByteBuffer[] srcs) throws IOException;
}
