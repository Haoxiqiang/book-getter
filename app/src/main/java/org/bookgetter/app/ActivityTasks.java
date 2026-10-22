package org.bookgetter.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns cancellation and rejects UI callbacks posted by superseded Activity work. */
final class ActivityTasks {
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final AtomicInteger generation = new AtomicInteger();
    private final ThreadLocal<Integer> workerGeneration = new ThreadLocal<>();
    private Future<?> current;
    private volatile boolean closed;

    void submit(Runnable work) {
        cancel();
        int owner = generation.get();
        current = executor.submit(() -> {
            workerGeneration.set(owner);
            try { work.run(); }
            finally { workerGeneration.remove(); }
        });
    }
    boolean isCurrent() {
        Integer owner = workerGeneration.get();
        return !closed && (owner == null || generation.get() == owner);
    }
    Runnable currentCallback(Runnable callback) { return currentCallback(callback, () -> {}); }
    Runnable currentCallback(Runnable callback, Runnable discarded) {
        Integer owner = workerGeneration.get();
        return () -> {
            if (!closed && (owner == null || generation.get() == owner)) callback.run();
            else discarded.run();
        };
    }
    void cancel() {
        generation.incrementAndGet();
        if (current != null) current.cancel(true);
    }
    void close() { closed = true; cancel(); executor.shutdownNow(); }
}
