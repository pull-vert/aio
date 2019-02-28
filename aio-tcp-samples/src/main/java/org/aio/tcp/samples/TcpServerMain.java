/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.aio.tcp.samples;

import org.aio.core.api.ChanEvtsHandler;
import org.aio.tcp.TcpServer;

public class TcpServerMain {
    public static void main(String [] args) {
        TcpServer tcpServer = TcpServer.newStageConfigurer()
                .stage1("noop", new ChanEvtsHandler() {
                }).build();

        // infinite loop
        while (!Thread.currentThread().isInterrupted()) {

        }
    }
}
