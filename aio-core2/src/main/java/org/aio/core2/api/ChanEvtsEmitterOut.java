/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core2.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @author Fred Montariol
 */
public interface ChanEvtsEmitterOut<OUT> {
    void startReceiving();

    CompletionStage<Void> write(OUT item);

    CompletionStage<Void> write(OUT item, CompletableFuture<Void> promise);

//    void send();

    // todo needed ?
//    CompletionStage<Void> closeForSend();
    // todo needed ?
//    CompletionStage<Void> closeForReceive();

    /**
     * Request to close the {@link java.nio.channels.Channel} and notify the {@link CompletionStage} once the operation completes
     */
    CompletionStage<Void> closeChan();
}
