/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

module aio.core {
    requires org.slf4j;
    exports org.aio.core;
    exports org.aio.core.api;
    exports org.aio.core.common;
    exports org.aio.core.util.concurrent;
}
