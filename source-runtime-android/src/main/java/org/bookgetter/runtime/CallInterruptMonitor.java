package org.bookgetter.runtime;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;

/** Socket reads do not reliably wake for Thread.interrupt(); cancel the owning call as well. */
final class CallInterruptMonitor implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor MONITOR = createMonitor();
    private final ScheduledFuture<?> task;
    private static ScheduledThreadPoolExecutor createMonitor() {
        ScheduledThreadPoolExecutor result = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "http-interrupt-monitor");
            thread.setDaemon(true);
            return thread;
        });
        result.setRemoveOnCancelPolicy(true);
        return result;
    }
    CallInterruptMonitor(Call call) {
        Thread owner = Thread.currentThread();
        if (owner.isInterrupted()) call.cancel();
        task = MONITOR.scheduleWithFixedDelay(() -> {
            if (owner.isInterrupted()) call.cancel();
        }, 25, 25, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { task.cancel(false); }
}
