/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.selectable.api;

import org.aio.core.api.ChanAPI;

/**
 * Interface abstraction over a {@linkplain java.nio.channels.SelectableChannel NIO Selectable Channel} : A channel that can be multiplexed via a {@link java.nio.channels.Selector}
 * @author Fred Montariol
 */
public interface SelectableChanAPI extends ChanAPI {
}
