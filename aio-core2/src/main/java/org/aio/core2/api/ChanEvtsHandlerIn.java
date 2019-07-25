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
     * Handle a Chan open Event
     * <p>
     * <b>This is the default implementation :</b>no op
     *
     * @param evtsEmitter Events Emitter
     * @param <NEXT_IN> next IN message sent type
     * @param <OUT> OUT message sent type
     */
    default <NEXT_IN, OUT> void onChanOpen(ChanEvtsEmitter<NEXT_IN, OUT> evtsEmitter) {
    }

    /**
     * Handle a {@code item} Read Event
     * <p>
     * <b>This is the default implementation :</b> forwards message to next Read Channel's step
     *
     * @param evtsEmitter Events Emitter
     * @param item the item to read
     * @param <NEXT_IN> next IN message sent type
     * @param <OUT> OUT message sent type
     */
    default <NEXT_IN, OUT> void onReadNext(ChanEvtsEmitter<NEXT_IN, OUT> evtsEmitter, IN item) {
        evtsEmitter.notifyReadNext((NEXT_IN) item);
    }

    /**
     * Handle a Chan Read complete
     * <p>
     * <b>This is the default implementation :</b>no op
     *
     * @param evtsEmitter Events Emitter
     * @param <NEXT_IN> next IN message sent type
     * @param <OUT> OUT message sent type
     */
    default <NEXT_IN, OUT> void onReadComplete(ChanEvtsEmitter<NEXT_IN, OUT> evtsEmitter) {
    }

    /**
     * Handle a Chan Read error
     * <p>
     * <b>This is the default implementation :</b>forwards error to next Read Channel's step
     *
     * @param evtsEmitter Events Emitter
     * @param throwable received error
     * @param <NEXT_IN> next IN message sent type
     * @param <OUT> OUT message sent type
     */
    default <NEXT_IN, OUT> void onReadError(ChanEvtsEmitter<NEXT_IN, OUT> evtsEmitter, Throwable throwable) {
        evtsEmitter.notifyReadError(throwable);
    }
}
