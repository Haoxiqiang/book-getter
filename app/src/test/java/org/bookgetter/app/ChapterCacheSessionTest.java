package org.bookgetter.app;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.Test;

public class ChapterCacheSessionTest {
    private Source source() {
        JsonObject json = new JsonObject(); json.addProperty("bookSourceUrl", "https://cache.test");
        json.addProperty("ruleVersion", "one");
        return new Source("https://cache.test", "Source", Kind.TEXT, json);
    }
    private BookDetail book(List<String> downloads) {
        BookHit hit = new BookHit("https://cache.test", "https://cache.test/book", "Book", "Author", "", "", Kind.TEXT);
        return new BookDetail(hit, "https://cache.test/toc", downloads);
    }
    private Chapter chapter(BookDetail book) {
        return new Chapter(book.hit().url(), "https://cache.test/one", "One", 0, false, false);
    }
    @Test public void sessionFreezesOriginalSourceAndBookAndRetainsLegacyCacheKeys() throws Exception {
        ChapterStore store = new ChapterStore(Files.createTempDirectory("cache-session-").toFile());
        Source source = source(); List<String> downloads = new ArrayList<>(List.of("https://cache.test/file"));
        BookDetail book = book(downloads); Chapter chapter = chapter(book);
        ChapterStore.Session session = store.session(source, book);
        ChapterContent content = new ChapterContent(chapter, Kind.TEXT, "Body", List.of());
        session.put(content, "next");
        assertEquals(content, store.get(source, book, chapter, "next"));
        source.json().addProperty("ruleVersion", "two"); downloads.add("https://cache.test/changed");
        assertEquals("one", session.source().string("ruleVersion"));
        assertEquals(List.of("https://cache.test/file"), session.book().downloadUrls());
        assertThrows(UnsupportedOperationException.class, () -> session.book().downloadUrls().clear());
        assertEquals(content, session.get(chapter, "next"));
        assertNull(store.session(source, book).get(chapter, "next"));
        assertNull(session.get(chapter, "different-boundary"));
    }
    @Test public void captureAndExportUseTheSameFrozenSessionAfterOriginalRulesChange() throws Exception {
        var directory = Files.createTempDirectory("capture-cache-session-");
        ChapterStore store = new ChapterStore(directory.resolve("cache").toFile());
        CaptureJobStore jobs = new CaptureJobStore(directory.toFile());
        Source source = source(); BookDetail book = book(List.of()); Chapter chapter = chapter(book);
        CaptureJobStore.Job job = new CaptureJobStore.Job(source.id(), ChapterStore.sourceHash(source), book,
                List.of(chapter), List.of(chapter), false, 0, Map.of());
        jobs.putNew(job);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CaptureRunner.Result result = CaptureRunner.run(source, job, store, jobs, (snapshotSource, snapshotBook, requested, next) -> {
            assertNotSame(source.json(), snapshotSource.json());
            source.json().addProperty("ruleVersion", "two");
            assertEquals("one", snapshotSource.string("ruleVersion"));
            return new ChapterContent(requested, Kind.TEXT, "Frozen body", List.of());
        }, () -> output, (processed, total, failed) -> {});
        assertTrue(result.complete());
        assertTrue(output.toString("UTF-8").contains("Frozen body"));
        assertNull(jobs.get());
        assertNull(store.get(source, book, chapter, null));
    }
}
