/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core2.bybu;

import org.aio.core2.internal.bybu.BybuImpl;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Bybu is a ByteBuffer abstraction wrapping a {@link List}<{@link ByteBuffer}> <br>
 * Can be created empty, or from single ByteBuffer or a List< ByteBuffer >
 *
 * @author Fred Montariol
 */
public interface Bybu {

    /**
     * @return {@code true} if, and only if, there is at least one element remaining in this buffer
     */
    boolean hasRemaining();

    /**
     * @return The number of elements remaining in this buffer
     */
    long remaining();

    /**
     * @param max accepted limit of elements
     * @return The number of elements remaining in this buffer,
     * or throw IllegalArgumentException("too many bytes") if (remain > max)
     */
    int remaining(int max);

    /**
     * Returns an array containing all of the elements in this list in
     * proper sequence (from first to last element);
     */
    ByteBuffer[] toArray();

    /**
     * @return a empty Bybu implementation
     */
    static Bybu empty() {
        return new BybuImpl();
    }

    /**
     * @return a Bybu wrapping a single {@link ByteBuffer}
     */
    static Bybu fromSingle(ByteBuffer buf) {
        return new BybuImpl(buf);
    }

    /**
     * @return a Bybu wrapping a {@link List}<{@link ByteBuffer}>
     */
    static Bybu fromList(List<ByteBuffer> bufs) {
        return new BybuImpl(bufs);
    }
}
