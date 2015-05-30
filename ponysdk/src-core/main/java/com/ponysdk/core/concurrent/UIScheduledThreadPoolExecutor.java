
package com.ponysdk.core.concurrent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ponysdk.core.UIContext;
import com.ponysdk.core.UIContextListener;
import com.ponysdk.ui.server.basic.PWindow;

public class UIScheduledThreadPoolExecutor implements UIScheduledExecutorService, UIContextListener {

    private static Logger log = LoggerFactory.getLogger(UIScheduledThreadPoolExecutor.class);

    private static UIScheduledThreadPoolExecutor INSTANCE;

    protected final ScheduledThreadPoolExecutor executor;
    protected Map<UIContext, Set<UIRunnable>> runnablesByUIContexts = new ConcurrentHashMap<UIContext, Set<UIRunnable>>();

    private UIScheduledThreadPoolExecutor(final ScheduledThreadPoolExecutor executor) {
        log.info("Initializing UIScheduledThreadPoolExecutor");
        this.executor = executor;
    }

    public static UIScheduledThreadPoolExecutor initDefault() {
        if (INSTANCE != null) throw new IllegalAccessError("Already initialized");
        INSTANCE = new UIScheduledThreadPoolExecutor(new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors()));
        return INSTANCE;
    }

    public static UIScheduledThreadPoolExecutor init(final ScheduledThreadPoolExecutor executor) {
        if (INSTANCE != null) throw new IllegalAccessError("Already initialized");
        INSTANCE = new UIScheduledThreadPoolExecutor(executor);
        return INSTANCE;
    }

    public static UIScheduledThreadPoolExecutor get() {
        return INSTANCE;
    }

    private void checkUIState() {
        if (UIContext.get() == null) throw new IllegalAccessError("UIScheduledThreadPoolExecutor must be called from UI client code");
        if (UIContext.get().getPusher() == null) throw new IllegalAccessError("PPusher not initialized");
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        checkUIState();
        final UIRunnable runnable = new UIRunnable(command);
        final ScheduledFuture<?> future = executor.schedule(runnable, delay, unit);
        runnable.setFuture(future);
        registerTask(runnable);

        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        checkUIState();
        final UIRunnable runnable = new UIRunnable(command);
        final ScheduledFuture<?> future = executor.scheduleAtFixedRate(runnable, initialDelay, period, unit);
        runnable.setFuture(future);
        registerTask(runnable);

        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
        checkUIState();

        final UIRunnable runnable = new UIRunnable(command);
        final ScheduledFuture<?> future = executor.scheduleWithFixedDelay(runnable, initialDelay, delay, unit);
        runnable.setFuture(future);
        registerTask(runnable);

        return future;
    }

    static class WindowUIRunnable implements Runnable {

        private final Runnable runnable;
        private final PWindow window;

        public WindowUIRunnable(final PWindow window, final Runnable runnable) {
            this.runnable = runnable;
            this.window = window;
        }

        @Override
        public void run() {
            window.acquire();
            try {
                runnable.run();
                window.flush();
            } catch (final Exception e) {
                log.error("Cannot run UIRunnable", e);
            } finally {
                window.release();
            }
        }
    }

    protected class UIRunnable implements Runnable, ScheduledFuture<Object> {

        private final Runnable runnable;
        private final UIContext uiContext;

        private boolean cancelled;
        private ScheduledFuture<?> future;
        private final PWindow window;

        public UIRunnable(final Runnable runnable) {
            this.uiContext = UIContext.get();

            this.window = UIContext.getCurrentWindow();
            if (window != null) {
                this.runnable = new WindowUIRunnable(window, runnable);
            } else {
                this.runnable = runnable;
            }
        }

        @Override
        public void run() {
            if (cancelled) return;
            if (!uiContext.getPusher().execute(runnable)) cancel(false);
        }

        public void setFuture(final ScheduledFuture<?> future) {
            this.future = future;
        }

        public UIContext getUiContext() {
            return uiContext;
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return future.getDelay(unit);
        }

        @Override
        public int compareTo(final Delayed o) {
            return future.compareTo(o);
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            this.cancelled = true;
            final boolean cancel = this.future.cancel(mayInterruptIfRunning);
            final Set<UIRunnable> set = runnablesByUIContexts.get(uiContext);
            if (set != null) {
                set.remove(this);
            }
            executor.purge();
            return cancel;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return future.get();
        }

        @Override
        public Object get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }

    }

    protected void registerTask(final UIRunnable runnable) {
        final UIContext uiContext = runnable.getUiContext();
        uiContext.addUIContextListener(this);
        Set<UIRunnable> runnables = runnablesByUIContexts.get(uiContext);
        if (runnables == null) {
            runnables = new HashSet<UIScheduledThreadPoolExecutor.UIRunnable>();
            runnablesByUIContexts.put(uiContext, runnables);
        }
        runnables.add(runnable);
    }

    @Override
    public void onUIContextDestroyed(final UIContext uiContext) {
        final Set<UIRunnable> runnables = runnablesByUIContexts.remove(uiContext);
        if (runnables != null) {
            for (final UIRunnable runnable : runnables) {
                runnable.cancel(false);
            }
        }
    }

}
