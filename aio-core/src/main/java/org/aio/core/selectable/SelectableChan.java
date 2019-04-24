/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.selectable;

import org.aio.core.Chan;
import org.aio.core.selectable.api.SelectableChanAPI;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * Abstract class abstraction over a {@linkplain java.nio.channels.SelectableChannel NIO Selectable Channel} : A channel that can be multiplexed via a {@link java.nio.channels.Selector}
 * It provides only methods we use, with limited visibility
 *
 * @param <T> type of SelectableChannel
 * @author Fred Montariol
 */
public abstract class SelectableChan<T extends SelectableChannel> extends Chan<T> implements SelectableChanAPI {

    private T delegate;

    public SelectableChan(T delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    SelectionKey keyFor(Selector sel) {
        return delegate.keyFor(sel);
    }

    void register(Selector sel, int ops, Object att) throws ClosedChannelException {
        delegate.register(sel, ops, att);
    }
}
