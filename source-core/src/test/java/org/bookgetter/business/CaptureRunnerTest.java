package org.bookgetter.business;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SourceImporter;
import org.junit.jupiter.api.Test;

public final class CaptureRunnerTest {
    @Test public void completeCaptureExportsAndClearsJob() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-runner-");
        Source source = source();
        CaptureJobStore jobs = new CaptureJobStore(directory.toFile());
        ChapterStore cache = new ChapterStore(directory.resolve("cache").toFile());
        CaptureJobStore.Job job = job(source);
        jobs.putNew(job);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger fetched = new AtomicInteger();
        var result = CaptureRunner.run(source, job, cache, jobs, (s, book, chapter, next) -> {
            fetched.incrementAndGet();
            return new ChapterContent(chapter, Kind.TEXT, "chapter text", List.of());
        }, () -> output, (processed, total, failed) -> {});
        assertTrue(result.complete());
        assertEquals(1, result.captured());
        assertEquals(1, fetched.get());
        assertTrue(output.toString().contains("chapter text"));
        assertNull(jobs.get());
    }

    @Test public void cancellationAfterLastFetchDoesNotOpenExport() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-runner-cancel-");
        Source source = source();
        CaptureJobStore jobs = new CaptureJobStore(directory.toFile());
        ChapterStore cache = new ChapterStore(directory.resolve("cache").toFile());
        CaptureJobStore.Job job = job(source);
        jobs.putNew(job);
        AtomicInteger opened = new AtomicInteger();
        try {
            assertThrows(InterruptedIOException.class, () -> CaptureRunner.run(source, job, cache, jobs,
                    (s, book, chapter, next) -> {
                        Thread.currentThread().interrupt();
                        return new ChapterContent(chapter, Kind.TEXT, "chapter text", List.of());
                    }, () -> { opened.incrementAndGet(); return new ByteArrayOutputStream(); },
                    (processed, total, failed) -> {}));
        } finally { Thread.interrupted(); }
        assertEquals(0, opened.get());
        assertNotNull(jobs.get());
    }

    @Test public void failedChapterKeepsTaskAndExportsSuccessfulChapters() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-runner-partial-");
        Source source = source();
        CaptureJobStore jobs = new CaptureJobStore(directory.toFile());
        ChapterStore cache = new ChapterStore(directory.resolve("cache").toFile());
        CaptureJobStore.Job first = job(source);
        Chapter two = new Chapter(first.book().hit().url(), "https://source.test/two", "Two", 1, false, false);
        CaptureJobStore.Job job = new CaptureJobStore.Job(first.sourceId(), first.sourceHash(), first.book(),
                List.of(first.catalog().get(0), two), List.of(first.catalog().get(0), two), false, 0, Map.of());
        jobs.putNew(job);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var result = CaptureRunner.run(source, job, cache, jobs, (s, book, chapter, next) -> {
            if (chapter.equals(two)) throw new IllegalStateException("offline fixture failure");
            return new ChapterContent(chapter, Kind.TEXT, "first chapter", List.of());
        }, () -> output, (processed, total, failed) -> {});
        assertFalse(result.complete());
        assertEquals(1, result.captured());
        assertEquals(1, result.failures().size());
        assertTrue(output.toString().contains("first chapter"));
        assertNotNull(jobs.get());
        assertEquals(job.generation(), jobs.get().generation());
    }

    private static Source source() {
        return SourceImporter.parse("{\"bookSourceUrl\":\"https://source.test\"}").sources().get(0);
    }
    private static CaptureJobStore.Job job(Source source) {
        BookHit hit = new BookHit(source.id(), "https://source.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
        return new CaptureJobStore.Job(source.id(), ChapterStore.sourceHash(source), book,
                List.of(chapter), List.of(chapter), false, 0, Map.of());
    }
}
