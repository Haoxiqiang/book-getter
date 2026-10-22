package org.bookgetter.app;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ActivityTaskOwnershipTest {
    @Test public void cancelledSelectionPublicationRunsCleanupInsteadOfUpdatingUi() throws Exception {
        ActivityTasks tasks = new ActivityTasks();
        AtomicReference<Runnable> publication = new AtomicReference<>();
        AtomicInteger updates = new AtomicInteger(), cleanups = new AtomicInteger();
        CountDownLatch prepared = new CountDownLatch(1);
        try {
            tasks.submit(() -> {
                publication.set(tasks.currentCallback(updates::incrementAndGet, cleanups::incrementAndGet));
                prepared.countDown();
            });
            assertTrue(prepared.await(2, TimeUnit.SECONDS));
            tasks.cancel();
            publication.get().run();
            assertEquals(0, updates.get()); assertEquals(1, cleanups.get());
        } finally { tasks.close(); }
    }
    @Test public void destroyedActivityNeverPublishesSavedSelection() throws Exception {
        ActivityTasks tasks = new ActivityTasks();
        AtomicReference<Runnable> publication = new AtomicReference<>();
        AtomicInteger updates = new AtomicInteger(), cleanups = new AtomicInteger();
        CountDownLatch prepared = new CountDownLatch(1);
        tasks.submit(() -> {
            publication.set(tasks.currentCallback(updates::incrementAndGet, cleanups::incrementAndGet));
            prepared.countDown();
        });
        try { assertTrue(prepared.await(2, TimeUnit.SECONDS)); }
        finally { tasks.close(); }
        publication.get().run();
        assertEquals(0, updates.get()); assertEquals(1, cleanups.get());
    }
}
