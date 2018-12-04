/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.chan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;

/**
 * Interface abstraction over {@linkplain java.nio.channels.SelectableChannel
 * NIO Selectable Channel}
 */
public interface Chan /*extends ChanEvtsEmitterOut<ByteBuffer[]>*/ {
    /**
     * @return The delegated {@link SelectableChannel}
     */
    public SelectableChannel getChannel();

    /**
     * @throws  java.nio.channels.NotYetConnectedException
     *          If this channel is not yet connected
     */
    // todo : use UncheckedIOException instead and throws nothing
    public int read(ByteBuffer buf) throws IOException;

    /**
     * @throws  java.nio.channels.NotYetConnectedException
     *          If this channel is not yet connected
     */
    // todo : use UncheckedIOException instead and throws nothing
    public int write(ByteBuffer[] srcs) throws IOException;
}
