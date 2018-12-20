/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.api;

/**
 * @author Frédéric Montariol
 */
public interface ChanEvtsHandlerIn<IN> extends ChanEvtsHandler {

    /**
     * Handle a {@code item} Read Event
     * <p>
     * <b>Default :</b> forwards message to next Channel's Step
     *
     * @param evtsEmitter Events Emitter
     * @param item the item that was read
     */
    default void onReadNext(ChanEvtsEmitter<IN, ?> evtsEmitter, IN item) {
        evtsEmitter.notifyNextRead(item);
    }
}
