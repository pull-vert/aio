/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

module aio.tcp {
    requires aio.core;
    requires org.slf4j;
    exports org.aio.tcp;
    exports org.aio.tcp.api;
    exports org.aio.tcp.common;
}
