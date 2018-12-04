/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK jdk.internal.net.http.common.Utils
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

package org.aio.core;

import org.slf4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static java.lang.String.format;

/**
 * Miscellaneous utilities
 */
public final class CoreUtils {

    private CoreUtils() { }

    /**
     * Allocated buffer size. Must never be higher than 16K. But can be lower
     * if smaller allocation units preferred. HTTP/2 mandates that all
     * implementations support frame payloads of at least 16K.
     */
    private static final int DEFAULT_BUFSIZE = 16 * 1024;

    public static final int BUFSIZE = getIntegerNetProperty(
            "aio.bufsize", DEFAULT_BUFSIZE
    );

    public static <T> CompletableFuture<T> wrapForDebug(Logger logger, String name, CompletableFuture<T> cf) {
        if (logger.isDebugEnabled()) {
            return cf.handle((r,t) -> {
                logger.debug("{} completed {}", name, t == null ? "successfully" : t );
                return cf;
            }).thenCompose(Function.identity());
        } else {
            return cf;
        }
    }

    public static IllegalArgumentException newIAE(String message, Object... args) {
        return new IllegalArgumentException(format(message, args));
    }
    public static ByteBuffer getBuffer() {
        return ByteBuffer.allocate(BUFSIZE);
    }

    public static Throwable getCompletionCause(Throwable x) {
        if (!(x instanceof CompletionException)
                && !(x instanceof ExecutionException)) return x;
        final Throwable cause = x.getCause();
        if (cause == null) {
            throw new InternalError("Unexpected null cause", x);
        }
        return cause;
    }

    public static IOException getIOException(Throwable t) {
        if (t instanceof IOException) {
            return (IOException) t;
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            return getIOException(cause);
        }
        return new IOException(t);
    }




    public static int getIntegerNetProperty(String name, int defaultValue) {
        return AccessController.doPrivileged((PrivilegedAction<Integer>) () ->
                NetProperties.getInteger(name, defaultValue));
    }

    public static String getNetProperty(String name) {
        return AccessController.doPrivileged((PrivilegedAction<String>) () ->
                NetProperties.get(name));
    }

    public static boolean getBooleanProperty(String name, boolean def) {
        return AccessController.doPrivileged((PrivilegedAction<Boolean>) () ->
                Boolean.parseBoolean(System.getProperty(name, String.valueOf(def))));
    }

    public static String getProperty(String name) {
        return AccessController.doPrivileged((PrivilegedAction<String>) () ->
                System.getProperty(name));
    }

    public static int getIntegerProperty(String name, int defaultValue) {
        return AccessController.doPrivileged((PrivilegedAction<Integer>) () ->
                Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue))));
    }

    public static String stackTrace(Throwable t) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String s = null;
        try {
            PrintStream p = new PrintStream(bos, true, "US-ASCII");
            t.printStackTrace(p);
            s = bos.toString("US-ASCII");
        } catch (UnsupportedEncodingException ex) {
            throw new InternalError(ex); // Can't happen
        }
        return s;
    }

    /**
     * Copies as much of src to dst as possible.
     * Return number of bytes copied
     */
    public static int copy(ByteBuffer src, ByteBuffer dst) {
        int srcLen = src.remaining();
        int dstLen = dst.remaining();
        if (srcLen > dstLen) {
            int diff = srcLen - dstLen;
            int limit = src.limit();
            src.limit(limit - diff);
            dst.put(src);
            src.limit(limit);
        } else {
            dst.put(src);
        }
        return srcLen - src.remaining();
    }

    public static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer dst = ByteBuffer.allocate(src.remaining());
        dst.put(src);
        dst.flip();
        return dst;
    }

    public static ByteBuffer copyAligned(ByteBuffer src) {
        int len = src.remaining();
        int size = ((len + 7) >> 3) << 3;
        assert size >= len;
        ByteBuffer dst = ByteBuffer.allocate(size);
        dst.put(src);
        dst.flip();
        return dst;
    }

    public static String dump(Object... objects) {
        return Arrays.toString(objects);
    }

    public static String stringOf(Collection<?> source) {
        // We don't know anything about toString implementation of this
        // collection, so let's create an array
        return Arrays.toString(source.toArray());
    }

    public static long remaining(ByteBuffer[] bufs) {
        long remain = 0;
        for (ByteBuffer buf : bufs) {
            remain += buf.remaining();
        }
        return remain;
    }

    public static boolean hasRemaining(List<ByteBuffer> bufs) {
        synchronized (bufs) {
            for (ByteBuffer buf : bufs) {
                if (buf.hasRemaining())
                    return true;
            }
        }
        return false;
    }

    public static long remaining(List<ByteBuffer> bufs) {
        long remain = 0;
        synchronized (bufs) {
            for (ByteBuffer buf : bufs) {
                remain += buf.remaining();
            }
        }
        return remain;
    }

    public static int remaining(List<ByteBuffer> bufs, int max) {
        long remain = 0;
        synchronized (bufs) {
            for (ByteBuffer buf : bufs) {
                remain += buf.remaining();
                if (remain > max) {
                    throw new IllegalArgumentException("too many bytes");
                }
            }
        }
        return (int) remain;
    }

    public static int remaining(ByteBuffer[] refs, int max) {
        long remain = 0;
        for (ByteBuffer b : refs) {
            remain += b.remaining();
            if (remain > max) {
                throw new IllegalArgumentException("too many bytes");
            }
        }
        return (int) remain;
    }

    public static void close(Closeable... closeables) {
        for (Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException ignored) { }
        }
    }

    // Put all these static 'empty' singletons here
    public static final ByteBuffer EMPTY_BYTEBUFFER = ByteBuffer.allocate(0);
    public static final ByteBuffer[] EMPTY_BB_ARRAY = new ByteBuffer[0];
    public static final List<ByteBuffer> EMPTY_BB_LIST = List.of();

    /**
     * Returns a slice of size {@code amount} from the given buffer. If the
     * buffer contains more data than {@code amount}, then the slice's capacity
     * ( and, but not just, its limit ) is set to {@code amount}. If the buffer
     * does not contain more data than {@code amount}, then the slice's capacity
     * will be the same as the given buffer's capacity.
     */
    public static ByteBuffer sliceWithLimitedCapacity(ByteBuffer buffer, int amount) {
        final int index = buffer.position() + amount;
        final int limit = buffer.limit();
        if (index != limit) {
            // additional data in the buffer
            buffer.limit(index);  // ensures that the slice does not go beyond
        } else {
            // no additional data in the buffer
            buffer.limit(buffer.capacity());  // allows the slice full capacity
        }

        ByteBuffer newb = buffer.slice();
        buffer.position(index);
        buffer.limit(limit);    // restore the original buffer's limit
        newb.limit(amount);     // slices limit to amount (capacity may be greater)
        return newb;
    }

    public static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }

    /**
     * Returns the smallest (closest to zero) positive number {@code m} (which
     * is also a power of 2) such that {@code n <= m}.
     * <pre>{@code
     *          n  pow2Size(n)
     * -----------------------
     *          0           1
     *          1           1
     *          2           2
     *          3           4
     *          4           4
     *          5           8
     *          6           8
     *          7           8
     *          8           8
     *          9          16
     *         10          16
     *        ...         ...
     * 2147483647  1073741824
     * } </pre>
     *
     * The result is capped at {@code 1 << 30} as beyond that int wraps.
     *
     * @param n
     *         capacity
     *
     * @return the size of the array
     * @apiNote Used to size arrays in circular buffers (rings), usually in
     * order to squeeze extra performance substituting {@code %} operation for
     * {@code &}, which is up to 2 times faster.
     */
    public static int pow2Size(int n) {
        if (n < 0) {
            throw new IllegalArgumentException();
        } else if (n == 0) {
            return 1;
        } else if (n >= (1 << 30)) { // 2^31 is a negative int
            return 1 << 30;
        } else {
            return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
        }
    }
}
