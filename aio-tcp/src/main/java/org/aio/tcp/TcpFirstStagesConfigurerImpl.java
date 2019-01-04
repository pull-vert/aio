/*
 * Copyright (c) 2018-2019 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.tcp;

import org.aio.core.api.ChanEvtsHandler;

/**
 * @author Frédéric Montariol
 */
public class TcpFirstStagesConfigurerImpl implements TcpServer.FirstStagesConfigurer {

    TcpServerBuilderImpl builder;

    TcpFirstStagesConfigurerImpl(TcpServerBuilderImpl builder) {
        this.builder = builder;
    }

    @Override
    public <U extends ChanEvtsHandler> TcpServer.StagesConfigurer stage1(String name, U chanEvtsHandler) {
        return new TcpStagesConfigurerImpl(builder, name, chanEvtsHandler);
    }
}
