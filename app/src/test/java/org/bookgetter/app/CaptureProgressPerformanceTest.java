package org.bookgetter.app;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.Test;

public class CaptureProgressPerformanceTest {
    private CaptureJobStore.Job job(int count) {
        BookHit hit = new BookHit("s", "https://example.test/book", "Book", "Author", "", "", Kind.TEXT);
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < count; i++)
            chapters.add(new Chapter(hit.url(), hit.url() + "/" + i, "Chapter " + i, i, false, false));
        return new CaptureJobStore.Job("s", "hash", new BookDetail(hit, hit.url(), List.of()),
                chapters, chapters, false, 0, Map.of());
    }

    @Test public void perChapterProgressLeavesLargeCatalogSnapshotUnchanged() throws Exception {
        Path directory = Files.createTempDirectory("capture-progress-");
        CaptureJobStore store = new CaptureJobStore(directory.toFile());
        CaptureJobStore.Job original = job(2000);
        store.putNew(original);
        byte[] snapshot = Files.readAllBytes(directory.resolve("capture-job.json"));
        store.beginProgress(original);
        for (int i = 0; i < 2000; i++) store.updateProgress(original, i + 1, null, null);
        assertArrayEquals(snapshot, Files.readAllBytes(directory.resolve("capture-job.json")));
        assertEquals(2000, new CaptureJobStore(directory.toFile()).get().completed());
        assertTrue(Files.size(directory.resolve("capture-progress.jsonl")) < snapshot.length * 2);
    }

    @Test public void retryDiscardsTornTailAndFailuresWhileReplacementRejectsOldWorker() throws Exception {
        Path directory = Files.createTempDirectory("capture-progress-retry-");
        CaptureJobStore store = new CaptureJobStore(directory.toFile());
        CaptureJobStore.Job original = job(2);
        store.putNew(original);
        store.beginProgress(original);
        store.updateProgress(original, 1, "failed", "network failure");
        Path progress = directory.resolve("capture-progress.jsonl");
        Files.write(progress, "{\"generation\":".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, store.get().completed());
        assertEquals("network failure", store.get().failures().get("failed"));
        store.beginProgress(original);
        store.updateProgress(original, 2, null, null);
        assertEquals(2, store.get().completed());
        assertTrue(store.get().failures().isEmpty());
        CaptureJobStore.Job replacement = job(2);
        new CaptureJobStore(directory.toFile()).replace(replacement);
        assertThrows(IllegalStateException.class, () -> store.updateProgress(original, 2, null, null));
        store.clear(original);
        assertEquals(replacement.generation(), store.get().generation());
    }

    @Test public void legacySnapshotProgressIsReadUntilRetryBegins() throws Exception {
        Path directory = Files.createTempDirectory("capture-progress-legacy-");
        CaptureJobStore store = new CaptureJobStore(directory.toFile());
        CaptureJobStore.Job original = job(2);
        CaptureJobStore.Job legacy = new CaptureJobStore.Job(original.sourceId(), original.sourceHash(), original.book(),
                original.catalog(), original.selected(), false, 1, Map.of("failed", "failure"), null);
        store.putNew(legacy);
        assertEquals(1, store.get().completed());
        store.beginProgress(legacy);
        store.updateProgress(legacy, 2, null, null);
        assertEquals(2, store.get().completed());
        assertTrue(store.get().failures().isEmpty());
    }

    @Test public void oldGenerationJournalCannotOverrideReplacementSnapshot() throws Exception {
        Path directory = Files.createTempDirectory("capture-progress-stale-");
        CaptureJobStore store = new CaptureJobStore(directory.toFile());
        CaptureJobStore.Job original = job(2);
        store.putNew(original);
        byte[] snapshot = Files.readAllBytes(directory.resolve("capture-job.json"));
        store.update(new CaptureJobStore.Job(original.sourceId(), original.sourceHash(), original.book(),
                original.catalog(), original.selected(), false, 1, Map.of("failed", "failure"), original.generation()));
        assertArrayEquals(snapshot, Files.readAllBytes(directory.resolve("capture-job.json")));
        assertEquals(1, store.get().completed());
        byte[] staleProgress = Files.readAllBytes(directory.resolve("capture-progress.jsonl"));
        CaptureJobStore.Job replacement = job(2);
        store.replace(replacement);
        Files.write(directory.resolve("capture-progress.jsonl"), staleProgress);
        assertEquals(0, store.get().completed());
        assertTrue(store.get().failures().isEmpty());
        assertEquals(replacement.generation(), store.get().generation());
    }

    @Test public void corruptMiddleRecordBacksUpBothTaskAndJournal() throws Exception {
        Path directory = Files.createTempDirectory("capture-progress-damaged-");
        CaptureJobStore store = new CaptureJobStore(directory.toFile());
        CaptureJobStore.Job original = job(2);
        store.putNew(original);
        store.beginProgress(original);
        Path progress = directory.resolve("capture-progress.jsonl");
        Files.write(progress, "not json\n{}\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class, store::get);
        java.io.File backup = store.backupCorrupt();
        assertTrue(backup.isFile());
        assertTrue(new java.io.File(backup.getPath() + ".progress").isFile());
        assertTrue(store.putNew(job(2)));
        assertEquals(0, store.get().completed());
    }

    @Test public void boundaryIndexMatchesFirstEqualChapterAndSkipsVolumes() {
        Chapter first = new Chapter("book", "one", "One", 0, false, false);
        Chapter volume = new Chapter("book", "volume", "Volume", 1, true, false);
        Chapter second = new Chapter("book", "two", "Two", 2, false, false);
        List<Chapter> catalog = List.of(first, volume, second, first);
        ChapterBoundaries boundaries = new ChapterBoundaries(catalog);
        for (Chapter chapter : catalog)
            assertEquals(org.bookgetter.core.SourceEngine.nextReadableUrl(catalog, chapter), boundaries.after(chapter));
        assertNull(boundaries.after(new Chapter("book", "missing", "Missing", 3, false, false)));
    }
}
