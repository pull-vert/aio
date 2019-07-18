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
public interface ChanEvtsHandlerIn<IN> {

    /**
     * Handle a {@code item} Read Event
     * <p>
     * <b>Default :</b> forwards message to next Channel's Step
     *
     * @param evtsEmitter Events Emitter
     * @param item the item that was read
     */
    default <NEXT_IN> void onReadNext(ChanEvtsEmitter<NEXT_IN, ?> evtsEmitter, IN item) {
        evtsEmitter.notifyNextRead((NEXT_IN) item);
    }
}
