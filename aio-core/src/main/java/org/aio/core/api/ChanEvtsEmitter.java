/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core.api;

/**
 * @author Fred Montariol
 */
public interface ChanEvtsEmitter<IN, OUT> extends ChanEvtsEmitterIn<IN>, ChanEvtsEmitterOut<OUT> {

    /**
     * The unique name of the {@link ChanEvtsEmitter}.The name was declared when the {@link ChanEvtsHandler}
     * was added to the {@link ChanStages}.
     */
    public String getName();
}
