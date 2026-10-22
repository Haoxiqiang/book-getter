package org.bookgetter.embedding;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bookgetter.business.*;
import org.bookgetter.core.*;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the public host API from outside its implementation package, without Android. */
final class EmbeddedWorkflowTest {
    @TempDir Path directory;

    @Test void importSearchCaptureAndResumeWithHostOwnedStorageAndTransport() throws Exception {
        String json = """
                {"bookSourceUrl":"https://fixture.test","searchUrl":"/search",
                 "ruleSearch":{"bookList":"div.book","name":"a@text","bookUrl":"a@href"},
                 "ruleBookInfo":{"name":"h1@text","tocUrl":"a@href"},
                 "ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"},
                 "ruleContent":{"content":"div@text"}}
                """;
        MixedSourceStore.Storage storage = new MixedSourceStore.Storage() {
            private MixedSourceStore.Snapshot snapshot = new MixedSourceStore.Snapshot("[]", "[]", null, "[]", "[]", null);
            public MixedSourceStore.Snapshot read() { return snapshot; }
            public boolean write(MixedSourceStore.Snapshot value) { snapshot = value; return true; }
        };
        Map<String, String> pages = Map.of(
                "/sources", json,
                "/search", "<div class='book'><a href='/book'>Fixture</a></div>",
                "/book", "<h1>Fixture</h1><a href='/toc'>Contents</a>",
                "/toc", "<a href='/one'>One</a><a href='/two'>Two</a>",
                "/one", "<div>First chapter</div>", "/two", "<div>Second chapter</div>");
        Map<String, Integer> calls = new HashMap<>();
        AtomicBoolean failSecond = new AtomicBoolean(true);
        Transport transport = request -> {
            String path = java.net.URI.create(request.url()).getPath();
            calls.merge(path, 1, Integer::sum);
            if (path.equals("/two") && failSecond.get()) throw new java.io.IOException("Offline fixture");
            return new FetchResponse(request.url(), pages.get(path), 200, Map.of());
        };
        var imports = new SourceImportCoordinator(new MixedSourceStore(storage));
        imports.importUrlOrPreview("https://fixture.test/sources", transport);
        imports.updateSubscriptions(transport);
        Source source = new SourceStore(new MixedSourceStore(storage).bookStorage()).all().get(0);
        var engine = new SourceEngine(transport);
        var detail = engine.detail(source, engine.search(source, "Fixture", 1).get(0));
        var chapters = engine.chapters(source, detail);
        assertEquals(2, chapters.size());
        var jobs = new CaptureJobStore(directory.toFile());
        var cache = new ChapterStore(directory.resolve("chapters").toFile());
        var job = new CaptureJobStore.Job(source.id(), ChapterStore.sourceHash(source), detail,
                chapters, chapters, false, 0, Map.of());
        assertTrue(jobs.putNew(job));
        var partial = new ByteArrayOutputStream();
        assertFalse(CaptureRunner.run(source, job, cache, jobs, engine::content,
                () -> partial, (done, total, failed) -> {}).complete());
        assertTrue(partial.toString().contains("First chapter"));
        failSecond.set(false);
        var restoredJobs = new CaptureJobStore(directory.toFile());
        var restoredCache = new ChapterStore(directory.resolve("chapters").toFile());
        var output = new ByteArrayOutputStream();
        assertTrue(CaptureRunner.run(source, restoredJobs.get(), restoredCache, restoredJobs, engine::content,
                () -> output, (done, total, failed) -> {}).complete());
        assertEquals(1, calls.get("/one"));
        assertTrue(output.toString().contains("Second chapter"));
        assertNull(restoredJobs.get());
    }
}
