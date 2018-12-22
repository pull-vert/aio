/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.core;

import org.aio.core.api.ChanEvtsHandler;
import org.aio.core.api.ChanStages;

import java.util.Iterator;
import java.util.Map;

/**
 * @author Frédéric Montariol
 */
public class ChanStagesImpl implements ChanStages {

    @Override
    public void stage1(String name, ChanEvtsHandler evtsHandler) {

    }

    @Override
    public void addLast(String name, ChanEvtsHandler evtsHandler) {

    }

    @Override
    public Iterator<Map.Entry<String, ChanEvtsHandler>> iterator() {
        return null;
    }
}
