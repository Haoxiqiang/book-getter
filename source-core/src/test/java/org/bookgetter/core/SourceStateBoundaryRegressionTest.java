package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

final class SourceStateBoundaryRegressionTest {
    private Source source(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("bookSourceUrl", id);
        return new Source(id, "Source", Kind.TEXT, json);
    }
    private RhinoRuntime scripts(SourceStateStore states) {
        return new RhinoRuntime(request -> new FetchResponse(request.url(), "ok", 200, Map.of()),
                ignored -> {}, ignored -> {}, states, CookieAccess.NONE);
    }
    @Test void chapterBookAndSourceValuesHaveTheSamePrecedenceInSelectorsAndJs() {
        MemorySourceStateStore states = new MemorySourceStateStore();
        RhinoRuntime scripts = scripts(states);
        Source source = source("https://priority.test");
        RuleEvaluator rules = new RuleEvaluator(scripts, states);
        scripts.evaluate("source.put('key','source')", Map.of(), source);
        rules.enterBook(source, source.id() + "/book", Map.of("key", "book"));
        scripts.enterBook(Map.of("bookUrl", source.id() + "/book", "variables", Map.of("key", "book")));
        try {
            rules.enterChapter(source, source.id() + "/book", source.id() + "/chapter");
            try {
                assertEquals("chapter", scripts.evaluate("java.put('key','chapter');java.get('key')", Map.of(), source));
                assertEquals("chapter", rules.one("@get:{key}", "", source.id(), source, false));
                assertEquals("book", scripts.evaluate("book.getVariable('key')", Map.of(), source));
            } finally { rules.leaveChapter(); }
            assertEquals("book", scripts.evaluate("java.get('key')", Map.of(), source));
        } finally { rules.leaveBook(); scripts.leaveBook(); }
        assertEquals("source", scripts.evaluate("java.get('key')", Map.of(), source));
    }
    @Test void explicitlyEnteredBookBeatsSourceFallbackWithoutSelectorFrames() {
        RhinoRuntime scripts = scripts(new MemorySourceStateStore());
        Source source = source("https://standalone.test");
        scripts.evaluate("source.put('key','source')", Map.of(), source);
        scripts.enterBook(Map.of("bookUrl", source.id() + "/book", "variables", Map.of("key", "book")));
        try { assertEquals("book", scripts.evaluate("java.get('key')", Map.of(), source)); }
        finally { scripts.leaveBook(); }
    }
    @Test void persistedDetailVariablesOverrideStaleHitDuringLaterContentStage() throws Exception {
        var directory = Files.createTempDirectory("stage-state-").toFile();
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://stages.test",
             "ruleBookInfo":{"name":"@js:book.putVariable('session','saved');'Book'","tocUrl":"/toc"},
             "ruleContent":{"content":"@js:java.get('session')"}}
            """).sources().get(0);
        Transport transport = request -> new FetchResponse(request.url(), "ok", 200, Map.of());
        RhinoRuntime firstScripts = new RhinoRuntime(transport, ignored -> {}, ignored -> {}, new FileSourceStateStore(directory), CookieAccess.NONE);
        BookHit hit = new BookHit(source.id(), source.id() + "/book", "Book", "", "", "", Kind.TEXT);
        BookDetail detail = new SourceEngine(transport, firstScripts).detail(source, hit);
        assertEquals("saved", detail.hit().variables().get("session"));
        BookHit stale = new BookHit(source.id(), hit.url(), "Book", "", "", "", Kind.TEXT,
                "", "", "", Map.of("session", "stale"));
        RhinoRuntime laterScripts = new RhinoRuntime(transport, ignored -> {}, ignored -> {}, new FileSourceStateStore(directory), CookieAccess.NONE);
        BookDetail staleBook = new BookDetail(stale, source.id() + "/toc", List.of());
        Chapter chapter = new Chapter(hit.url(), source.id() + "/chapter", "One", 0, false, false);
        assertEquals("saved", new SourceEngine(transport, laterScripts).content(source, staleBook, chapter, null).text());
    }
    @Test void sameUrlBookAndRssNamespacesDoNotShareBookOrSourceSecrets() {
        MemorySourceStateStore states = new MemorySourceStateStore();
        RhinoRuntime scripts = scripts(states);
        Source book = source("https://identity.test");
        JsonObject rssJson = new JsonObject(); rssJson.addProperty("sourceUrl", book.id());
        Source rss = new Source(book.id(), "RSS", Kind.TEXT, rssJson);
        RuleEvaluator rules = new RuleEvaluator(scripts, states);
        scripts.evaluate("source.put('secret','book-secret')", Map.of(), book);
        rules.enterBook(book, "https://identity.test/item", Map.of());
        try { scripts.evaluate("java.put('bookSecret','book-only')", Map.of(), book); }
        finally { rules.leaveBook(); }
        rules.enterBook(rss, "https://identity.test/item", Map.of());
        try {
            assertEquals("", scripts.evaluate("java.get('secret')", Map.of(), rss));
            assertEquals("", scripts.evaluate("java.get('bookSecret')", Map.of(), rss));
        } finally { rules.leaveBook(); }
        assertEquals("book-secret", scripts.evaluate("source.get('secret')", Map.of(), book));
    }
    @Test void savedLoginSecretIsExcludedFromSourceJsonAndStageTraces() throws Exception {
        MemorySourceStateStore states = new MemorySourceStateStore();
        RhinoRuntime scripts = scripts(states);
        Source source = source("https://privacy.test");
        scripts.evaluate("source.putLoginHeader('{\"Authorization\":\"saved-secret\"}')", Map.of(), source);
        List<StageTrace> traces = new ArrayList<>();
        StageFetcher fetcher = new StageFetcher(request -> {
            assertEquals("saved-secret", request.headers().get("Authorization"));
            return new FetchResponse(request.url(), "ok", 200, Map.of());
        }, traces::add, states);
        fetcher.fetch(new FetchRequest(source.id() + "/item?token=query-secret").forSource(source),
                Stage.CONTENT, source.id(), "ruleContent.content");
        assertFalse(source.json().toString().contains("saved-secret"));
        assertFalse(traces.toString().contains("saved-secret"));
        assertFalse(traces.toString().contains("query-secret"));
    }
}
