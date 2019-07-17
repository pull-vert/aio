/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

module aio.core {
    requires org.slf4j;
    requires java.net.http;
//    exports org.aio.core2;
    exports org.aio.core2.api;
    exports org.aio.core2.common;
    exports org.aio.core2.util.concurrent;
}
