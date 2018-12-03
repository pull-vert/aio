/*
 * Copyright (c) 2018 AIO's author : Frédéric Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 */

package org.pullvert.aio.tcp;

import org.pullvert.aio.core.AsyncEvent;
import org.pullvert.aio.core.BufferSupplier;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public interface TcpClient {
    public void registerEvent(AsyncEvent exchange);

    public boolean isSelectorThread();

    public DelegatingExecutor theExecutor();

    /**
     * Allows an AsyncEvent to modify its interestOps.
     * @param event The modified event.
     */
    public void eventUpdated(AsyncEvent event) throws ClosedChannelException;

    public BufferSupplier getSSLBufferSupplier();

    /**
     * A DelegatingExecutor is an executor that delegates tasks to
     * a wrapped executor when it detects that the current thread
     * is the SelectorManager thread. If the current thread is not
     * the selector manager thread the given task is executed inline.
     */
    final static class DelegatingExecutor implements Executor {
        private final BooleanSupplier isInSelectorThread;
        private final Executor delegate;
        DelegatingExecutor(BooleanSupplier isInSelectorThread, Executor delegate) {
            this.isInSelectorThread = isInSelectorThread;
            this.delegate = delegate;
        }

        Executor delegate() {
            return delegate;
        }

        @Override
        public void execute(Runnable command) {
            if (isInSelectorThread.getAsBoolean()) {
                delegate.execute(command);
            } else {
                command.run();
            }
        }
    }
}
