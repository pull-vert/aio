/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core2.api;

/**
 * @author Fred Montariol
 */
public interface ChanEvtsEmitterIn<IN> {
    void notifyChanOpen();

    void notifyReadNext(IN item);

    void notifyReadComplete();

    void notifyReadError(Throwable throwable);
}
