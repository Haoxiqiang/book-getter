package org.bookgetter.core;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceStateRuntimeTest {
    private Source source(String id) {
        JsonObject json = new JsonObject(); json.addProperty("bookSourceUrl", id);
        return new Source(id, "Source", Kind.TEXT, json);
    }
    private RhinoRuntime runtime(SourceStateStore states, Transport transport) {
        return new RhinoRuntime(transport, ignored -> {}, ignored -> {}, states, CookieAccess.NONE);
    }
    @Test void sourceStatePersistsAndModelsRemainIsolated() throws Exception {
        var directory = Files.createTempDirectory("source-state").toFile();
        Source source = source("https://example.test");
        RhinoRuntime first = runtime(new FileSourceStateStore(directory), request -> null);
        assertEquals("saved", first.evaluate("source.put('k','saved');source.putVariable('settings');source.get('k')", Map.of(), source));
        RhinoRuntime second = runtime(new FileSourceStateStore(directory), request -> null);
        assertEquals("saved|settings", second.evaluate("source.get('k')+'|'+source.getVariable()", Map.of(), source));
        JsonObject json = new JsonObject(); json.addProperty("sourceUrl", source.id());
        Source rss = new Source(source.id(), "RSS", Kind.TEXT, json);
        assertEquals("", second.evaluate("source.get('k')", Map.of(), rss));
        assertEquals("", second.evaluate("source.get('k')", Map.of(), source("https://other.test")));
    }
    @Test void nestedRulesKeepSafetyAndTimeOffset() {
        RhinoRuntime scripts = runtime(new MemorySourceStateStore(), request -> null);
        Source source = source("https://example.test");
        assertEquals("ABC", scripts.evaluate("java.getString('@js:result','ABC')", Map.of(), source));
        assertEquals("08:00", scripts.evaluate("java.timeFormatUTC(0,'HH:mm',28800000)", Map.of(), source));
        assertThrows(RuntimeException.class, () -> scripts.evaluate("java.getString('@js:Packages.java.lang.System.exit(0)','')", Map.of(), source));
    }
    @Test void networkGetUsesHeadersResponseViewAndTimeout() {
        AtomicReference<FetchRequest> seen = new AtomicReference<>();
        RhinoRuntime scripts = runtime(new MemorySourceStateStore(), request -> {
            seen.set(request); return new FetchResponse(request.url(), "body", 302, Map.of("Location", "/next"));
        });
        assertEquals("302|/next|body", scripts.evaluate("var r=java.get('https://example.test/a',{'X-Test':'yes'},1234);"
                + "r.statusCode()+'|'+r.header('location')+'|'+r.body()", Map.of(), source("https://example.test")));
        assertEquals("yes", seen.get().headers().get("X-Test"));
        assertFalse(seen.get().followRedirects());
        assertTrue(seen.get().timeoutMillis() > 0 && seen.get().timeoutMillis() <= 1234);
    }
    @Test void loginHeadersStayOnSourceOriginAndExplicitHeadersWin() throws Exception {
        MemorySourceStateStore states = new MemorySourceStateStore();
        Source source = source("https://example.test");
        states.put(SourcePolicy.of(source).stateKey(), "loginHeader", "{\"Authorization\":\"secret\",\"X-Auth\":\"saved\"}");
        FetchRequest request = new FetchRequest("https://example.test/book", "GET", Map.of("x-auth", "explicit"), null, null, false).forSource(source);
        FetchRequest prepared = SourceRequests.prepare(request, states);
        assertEquals("secret", prepared.headers().get("Authorization"));
        assertEquals("explicit", prepared.headers().get("x-auth"));
        assertFalse(prepared.headers().containsKey("X-Auth"));
        assertTrue(SourceRequests.prepare(new FetchRequest("https://cdn.test/a").forSource(source), states).headers().isEmpty());
    }
    @Test void sharedRateWaitIsCancellableAndHasTimeout() throws Exception {
        Source source = source("https://rate-" + System.nanoTime() + ".test"); source.json().addProperty("concurrentRate", "1/60000");
        var states = new MemorySourceStateStore();
        FetchRequest request = new FetchRequest(source.id()).forSource(source);
        SourceRequests.prepare(request, states);
        assertThrows(java.io.InterruptedIOException.class, () -> SourceRequests.prepare(request.withTimeout(20), states));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                assertThrows(java.io.InterruptedIOException.class, () -> SourceRequests.prepare(request, states));
            });
            pending.cancel(true);
            worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
    @Test void standaloneBookWritesStayOutOfSourceStateAndChapterMetadataMatchesSelectors() {
        var states = new MemorySourceStateStore(); var source = source("https://context.test");
        var scripts = runtime(states, request -> null);
        scripts.enterBook(Map.of("bookUrl", source.id() + "/book", "name", "Book"));
        try {
            assertEquals("saved", scripts.evaluate("java.put('session','saved');java.get('session')", Map.of(), source));
            assertEquals("", scripts.evaluate("source.get('session')", Map.of(), source));
        } finally { scripts.leaveBook(); }
        var rules = new RuleEvaluator(scripts, states);
        rules.enterBook(source, source.id() + "/book", Map.of());
        rules.metadata(source, Map.of("bookName", "Book"));
        try {
            rules.enterChapter(source, source.id() + "/book", source.id() + "/chapter");
            rules.metadata(source, Map.of("title", "Chapter One"));
            try {
                assertEquals("Chapter One|Book", scripts.evaluate("java.get('title')+'|'+java.get('bookName')", Map.of(), source));
                assertEquals("Chapter One", rules.one("@get:{title}", "", source.id(), source, false));
            } finally { rules.leaveChapter(); }
        } finally { rules.leaveBook(); }
    }
}
