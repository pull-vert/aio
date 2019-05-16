/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.tcp;

import org.aio.core.SelectableEndpoint;
import org.aio.core.api.ChanEvtsHandler;

import static java.util.Objects.requireNonNull;

/**
 * @author Fred Montariol
 */
public class TcpStagesConfigurerImpl extends SelectableEndpoint.StagesConfigurer implements TcpServer.StagesConfigurer {

    private TcpServerBuilderImpl builder;

    <U extends ChanEvtsHandler> TcpStagesConfigurerImpl(TcpServerBuilderImpl builder, String name, U chanEvtsHandler) {
        super(name, chanEvtsHandler);
        this.builder = builder;
    }

    @Override
    public <U extends ChanEvtsHandler> TcpServer.StagesConfigurer addLast(String name, U chanEvtsHandler) {
        requireNonNull(name);
        requireNonNull(chanEvtsHandler);
        setLast(name, chanEvtsHandler);
        return this;
    }

    @Override
    public TcpServer build() {
        return TcpServerImpl.create(builder, chanStages);
    }
}
