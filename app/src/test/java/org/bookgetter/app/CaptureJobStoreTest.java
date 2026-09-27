package org.bookgetter.app;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SourceImporter;
import org.junit.Test;

public final class CaptureJobStoreTest {
    @Test public void damagedJobMustBeBackedUpBeforeStartingAnother() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-damaged-job-");
        try {
            var original = directory.resolve("capture-job.json");
            Files.write(original, "{broken json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CaptureJobStore store = new CaptureJobStore(directory.toFile());
            assertThrows(IllegalStateException.class, store::get);
            BookHit hit = new BookHit("https://source.test", "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            CaptureJobStore.Job replacement = new CaptureJobStore.Job(hit.sourceId(), "snapshot", book,
                    List.of(chapter), List.of(chapter), false, 0, Map.of());
            assertThrows(IllegalStateException.class, () -> store.putNew(replacement));
            assertEquals("{broken json", new String(Files.readAllBytes(original), java.nio.charset.StandardCharsets.UTF_8));
            java.io.File backup = store.backupCorrupt();
            assertEquals("{broken json", new String(Files.readAllBytes(backup.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(store.putNew(replacement));
            assertEquals(replacement.sourceId(), store.get().sourceId());
            assertThrows(IllegalStateException.class, store::backupCorrupt);
            Files.delete(backup.toPath());
        } finally {
            Files.deleteIfExists(directory.resolve("capture-job.json.tmp"));
            Files.deleteIfExists(directory.resolve("capture-job.json"));
            Files.delete(directory);
        }
    }

    @Test public void unfinishedJobSurvivesStoreRecreationWithSelectionAndFailures() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-job-");
        try {
            CaptureJobStore first = new CaptureJobStore(directory.toFile());
            BookHit hit = new BookHit("https://source.test", "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, "https://source.test/toc", List.of());
            Chapter one = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            Chapter two = new Chapter(hit.url(), "https://source.test/two", "Two", 1, false, false);
            CaptureJobStore.Job job = new CaptureJobStore.Job(hit.sourceId(), "snapshot-1", book,
                    List.of(one, two), List.of(two), true, 0, Map.of(two.url(), "Timeout"));
            assertTrue(first.putNew(job));

            CaptureJobStore.Job restored = new CaptureJobStore(directory.toFile()).get();
            assertNotNull(restored);
            assertEquals(hit.sourceId(), restored.sourceId());
            assertEquals("snapshot-1", restored.sourceHash());
            assertEquals("Book", restored.book().hit().name());
            assertEquals(List.of(one, two), restored.catalog());
            assertEquals(List.of(two), restored.selected());
            assertEquals("Timeout", restored.failures().get(two.url()));
            assertTrue(restored.epub());

            first.clear(job);
            assertNull(new CaptureJobStore(directory.toFile()).get());
        } finally {
            Files.deleteIfExists(directory.resolve("capture-job.json.tmp"));
            Files.deleteIfExists(directory.resolve("capture-job.json"));
            Files.delete(directory);
        }
    }

    @Test public void differentTaskCannotSilentlyReplaceProgressOrBeClearedByOldWorker() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-job-conflict-");
        try {
            CaptureJobStore store = new CaptureJobStore(directory.toFile());
            BookHit hit = new BookHit("https://source.test", "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter one = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            Chapter two = new Chapter(hit.url(), "https://source.test/two", "Two", 1, false, false);
            CaptureJobStore.Job old = new CaptureJobStore.Job(hit.sourceId(), "snapshot", book, List.of(one, two),
                    List.of(one), false, 1, Map.of());
            CaptureJobStore.Job newer = new CaptureJobStore.Job(hit.sourceId(), "snapshot", book, List.of(one, two),
                    List.of(two), false, 0, Map.of());
            BookHit changedHit = new BookHit(hit.sourceId(), hit.url(), hit.name(), hit.author(), hit.intro(),
                    hit.coverUrl(), Kind.TEXT, "", "", "", Map.of("chapterPrefix", "changed"));
            CaptureJobStore.Job changedContext = new CaptureJobStore.Job(hit.sourceId(), "snapshot",
                    new BookDetail(changedHit, hit.url(), List.of()), List.of(one, two), List.of(one),
                    false, 0, Map.of());
            assertTrue(store.putNew(old));
            assertFalse(store.putNew(newer));
            assertFalse(store.putNew(changedContext));
            assertEquals(old.selected(), store.get().selected());
            store.replace(newer);
            assertThrows(IllegalStateException.class, () -> store.update(old));
            store.clear(old);
            assertEquals(newer.selected(), store.get().selected());
        } finally {
            Files.deleteIfExists(directory.resolve("capture-job.json.tmp"));
            Files.deleteIfExists(directory.resolve("capture-job.json"));
            Files.delete(directory);
        }
    }

    @Test public void failedDocumentCloseKeepsTheResumableJob() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-export-close-");
        try {
            CaptureJobStore store = new CaptureJobStore(directory.toFile());
            BookHit hit = new BookHit("https://source.test", "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            CaptureJobStore.Job job = new CaptureJobStore.Job(hit.sourceId(), "snapshot", book, List.of(chapter),
                    List.of(chapter), false, 1, Map.of());
            assertTrue(store.putNew(job));
            java.io.OutputStream broken = new java.io.ByteArrayOutputStream() {
                @Override public void close() throws java.io.IOException { throw new java.io.IOException("Provider commit failed"); }
            };
            assertThrows(java.io.IOException.class, () -> CaptureExport.write(broken, job,
                    List.of(new ChapterContent(chapter, Kind.TEXT, "Body", List.of())), true, store));
            assertNotNull(store.get());
        } finally {
            Files.deleteIfExists(directory.resolve("capture-job.json.tmp"));
            Files.deleteIfExists(directory.resolve("capture-job.json"));
            Files.delete(directory);
        }
    }

    @Test public void onlyCompletedExportClearsJobAfterClosingDocument() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-export-success-");
        try {
            CaptureJobStore store = new CaptureJobStore(directory.toFile());
            BookHit hit = new BookHit("https://source.test", "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            CaptureJobStore.Job job = new CaptureJobStore.Job(hit.sourceId(), "snapshot", book, List.of(chapter),
                    List.of(chapter), false, 1, Map.of());
            assertTrue(store.putNew(job));
            List<ChapterContent> content = List.of(new ChapterContent(chapter, Kind.TEXT, "Body", List.of()));
            CaptureExport.write(new java.io.ByteArrayOutputStream(), job, content, false, store);
            assertNotNull(store.get());
            java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            CaptureExport.write(new java.io.ByteArrayOutputStream() {
                @Override public void close() throws java.io.IOException {
                    closed.set(true);
                    super.close();
                }
            }, job, content, true, store);
            assertTrue(closed.get());
            assertNull(store.get());
        } finally {
            Files.deleteIfExists(directory.resolve("capture-job.json.tmp"));
            Files.deleteIfExists(directory.resolve("capture-job.json"));
            Files.delete(directory);
        }
    }

    @Test public void cachedChapterIsNotReusedAfterRulesChange() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-cache-");
        try {
            Source first = SourceImporter.parse("""
                    {"bookSourceUrl":"https://source.test","ruleContent":{"content":"p.old@text"}}
                    """).sources().get(0);
            Source changed = SourceImporter.parse("""
                    {"bookSourceUrl":"https://source.test","ruleContent":{"content":"p.new@text"}}
                    """).sources().get(0);
            Chapter chapter = new Chapter("https://source.test/book", "https://source.test/one", "One", 0, false, false);
            BookHit hit = new BookHit(first.id(), chapter.bookUrl(), "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            ChapterStore store = new ChapterStore(directory.toFile());
            store.put(first, book, new ChapterContent(chapter, Kind.TEXT, "Old body", List.of()),
                    "https://source.test/two");
            assertEquals("Old body", store.get(first, book, chapter, "https://source.test/two").text());
            assertNull(store.get(changed, book, chapter, "https://source.test/two"));
            assertNull(store.get(first, book, chapter, "https://source.test/new-next"));
            Chapter renamed = new Chapter(chapter.bookUrl(), chapter.url(), "Renamed", 0, false, false);
            assertNull(store.get(first, book, renamed, "https://source.test/two"));
            BookHit withVariables = new BookHit(first.id(), chapter.bookUrl(), "Book", "", "", "", Kind.TEXT,
                    "", "", "", Map.of("chapterPrefix", "new", "suffix", "end"));
            assertNull(store.get(first, new BookDetail(withVariables, hit.url(), List.of()), chapter,
                    "https://source.test/two"));
            Chapter next = new Chapter(chapter.bookUrl(), "https://source.test/two", "Two", 1, false, false);
            CaptureJobStore jobs = new CaptureJobStore(directory.toFile());
            CaptureJobStore.Job job = new CaptureJobStore.Job(first.id(), ChapterStore.sourceHash(first),
                    book, List.of(chapter, next), List.of(chapter),
                    false, 1, Map.of());
            assertTrue(jobs.putNew(job));
            var output = new java.io.ByteArrayOutputStream();
            CaptureExport.writeFromCache(output, job, List.of(chapter), first, store, true, jobs);
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Old body"));
            assertNull(jobs.get());
            BookDetail variableBook = new BookDetail(withVariables, hit.url(), List.of());
            store.put(first, variableBook, new ChapterContent(chapter, Kind.TEXT, "Variable body", List.of()),
                    "https://source.test/two");
            CaptureJobStore.Job variableJob = new CaptureJobStore.Job(first.id(), ChapterStore.sourceHash(first),
                    variableBook, List.of(chapter, next), List.of(chapter), false, 1, Map.of());
            assertTrue(jobs.putNew(variableJob));
            assertEquals("Variable body", new ChapterStore(directory.toFile()).get(first,
                    new CaptureJobStore(directory.toFile()).get().book(), chapter,
                    "https://source.test/two").text());
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> { try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            Files.delete(directory);
        }
    }

    @Test public void structurallyIncompleteCacheIsRefetched() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-corrupt-cache-");
        try {
            Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://source.test\"}").sources().get(0);
            BookHit hit = new BookHit(source.id(), "https://source.test/book", "Book", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            ChapterStore store = new ChapterStore(directory.toFile());
            store.put(source, book, new ChapterContent(chapter, Kind.TEXT, "Body", List.of()), null);
            try (var files = Files.list(directory)) {
                Files.write(files.findFirst().orElseThrow(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertNull(store.get(source, book, chapter, null));
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> { try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            Files.delete(directory);
        }
    }

    @Test public void cachedMediaWithInvalidResourceAddressIsRefetched() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-bad-media-cache-");
        try {
            Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://source.test\",\"bookSourceType\":1}")
                    .sources().get(0);
            BookHit hit = new BookHit(source.id(), "https://source.test/book", "Book", "", "", "", Kind.AUDIO);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter chapter = new Chapter(hit.url(), "https://source.test/one", "One", 0, false, false);
            ChapterStore store = new ChapterStore(directory.toFile());
            store.put(source, book, new ChapterContent(chapter, Kind.AUDIO, null,
                    List.of(new Resource("https://source.test/audio.mp3", "One", null))), null);
            assertThrows(IllegalArgumentException.class, () -> store.put(source, book,
                    new ChapterContent(chapter, Kind.AUDIO, null,
                            List.of(new Resource("javascript:alert(1)", "One", null))), null));
            try (var files = Files.list(directory)) {
                Files.write(files.findFirst().orElseThrow(), new com.google.gson.Gson().toJson(
                        new ChapterContent(chapter, Kind.AUDIO, null,
                                List.of(new Resource("javascript:alert(1)", "One", null))))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertNull(store.get(source, book, chapter, null));
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> { try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            Files.delete(directory);
        }
    }

    @Test public void chapterCacheRejectsAnotherBooksChapter() throws Exception {
        var directory = Files.createTempDirectory("bookgetter-cache-book-identity-");
        try {
            Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://source.test\"}").sources().get(0);
            BookHit hit = new BookHit(source.id(), "https://source.test/book-one", "One", "", "", "", Kind.TEXT);
            BookDetail book = new BookDetail(hit, hit.url(), List.of());
            Chapter wrong = new Chapter("https://source.test/book-two", "https://source.test/one", "One", 0, false, false);
            ChapterStore store = new ChapterStore(directory.toFile());
            assertThrows(IllegalArgumentException.class, () -> store.put(source, book,
                    new ChapterContent(wrong, Kind.TEXT, "Body", List.of()), null));
            assertNull(store.get(source, book, wrong, null));
        } finally {
            Files.delete(directory);
        }
    }
}
