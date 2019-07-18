/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core2.api;

import org.aio.core2.internal.ChanStagesImpl;

import java.util.Map;

/**
 * @author Fred Montariol
 */
public interface ChanStages extends Iterable<Map.Entry<String, ChanEvtsHandler>> {
    void stage1(String name, ChanEvtsHandler evtsHandler);

    void addLast(String name, ChanEvtsHandler evtsHandler);

    static ChanStages build() {
        return new ChanStagesImpl();
    }
}
