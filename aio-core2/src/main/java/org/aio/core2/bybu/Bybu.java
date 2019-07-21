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
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bybu is a ByteBuffer abstraction wrapping a {@link List}<{@link ByteBuffer}> <br>
 * Can be created empty, from single ByteBuffer or a List< ByteBuffer > <br>
 * It provides some thread-safe methods
 *
 * @author Fred Montariol
 */
public interface Bybu {

    /**
     * @return {@code true} if, and only if, there is at least one element remaining in wrapped list
     */
    boolean hasRemaining();

    /**
     * @return The number of elements remaining in wrapped list
     */
    long remaining();

    /**
     * @param max accepted limit of elements
     * @return The number of elements remaining in wrapped list,
     * or throw IllegalArgumentException("too many bytes") if (remain > max)
     */
    int remaining(int max);

    /**
     * @return size of wrapped list
     */
    int size();

    /**
     * WARNING : not thread-safe !
     *
     * @return an array containing all of the elements in this list in
     * proper sequence (from first to last element);
     */
    ByteBuffer[] toArray();

    /**
     * Appends the specified ByteBuffer to the end of this list
     */
    void add(ByteBuffer buf);

    /**
     * @param bufs compared list
     * @return {@code true} if wrapped list == bufs
     */
    boolean listEquals(List<ByteBuffer> bufs);

    /**
     * @return {@code true} if wrapped list contains no elements
     */
    boolean isEmpty();

    /**
     * Appends all of the elements in the specified bybu to the end of wrapped list <br>
     * WARNING : not thread-safe !
     *
     * @param bybu containing elements to be added to wrapped list
     */
    void addAll(Bybu bybu);

    /**
     * Consume wrapped list in a lock-safe block, using implementation's lock
     *
     * @param bufsConsumer wrapped list consumer
     */
    void lockedConsume(Consumer<List<ByteBuffer>> bufsConsumer);

    /**
     * Consume wrapped list in a lock-safe block, using provided lock
     *
     * @param lock non-null lock
     * @param bufsConsumer wrapped list consumer
     */
    void lockedConsume(Lock lock, Consumer<List<ByteBuffer>> bufsConsumer);

//    /**
//     * Call function on wrapped list in a lock-safe block
//     *
//     * @param bufsFunction wrapped list function
//     */
//    <R> R lockedFunction(Function<List<ByteBuffer>, R> bufsFunction);

    /**
     * Call predicate on wrapped list in a lock-safe block
     *
     * @param bufsPredicate predicate on wrapped list
     */
    boolean lockedPredicate(Predicate<List<ByteBuffer>> bufsPredicate);

    /**
     * Removes all of the elements from wrapped list (optional operation).
     * The list will be empty after this call returns.
     */
    void clear();

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
