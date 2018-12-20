/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.api;

import org.aio.core.Chan;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @author Frédéric Montariol
 */
public interface ChanEvtsEmitterOut<OUT> {
    public void startReceiving();

    public CompletionStage<Void> write(OUT item);

    public CompletionStage<Void> write(OUT item, CompletableFuture<Void> promise);

    public void send();

    // todo needed ?
//    public CompletionStage<Void> closeForSend();
    // todo needed ?
//    public CompletionStage<Void> closeForReceive();

    /**
     * Request to close the {@link Chan} and notify the {@link CompletionStage} once the operation completes
     */
    public CompletionStage<Void> closeChan();
}
