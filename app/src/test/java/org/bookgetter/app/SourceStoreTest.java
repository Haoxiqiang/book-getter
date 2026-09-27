package org.bookgetter.app;

import static org.junit.Assert.*;
import java.util.Map;
import org.bookgetter.core.Models.FetchResponse;
import org.junit.Test;

public final class SourceStoreTest {
    private static final class MemoryStorage implements SourceStore.Storage {
        String value = "[]";
        String subscriptions = "[]";
        boolean writable = true;
        @Override public String read() { return value; }
        @Override public String readSubscriptions() { return subscriptions; }
        @Override public boolean write(String json, String feeds) {
            if (!writable) return false;
            value = json;
            subscriptions = feeds;
            return true;
        }
    }

    @Test public void failedPersistenceCannotBeReportedAsImported() {
        MemoryStorage storage = new MemoryStorage();
        storage.writable = false;
        SourceStore store = new SourceStore(storage);
        assertThrows(IllegalStateException.class, () -> store.importText(
                "{\"bookSourceUrl\":\"https://example.test\"}",
                request -> new FetchResponse(request.url(), "", 200, Map.of())));
        assertTrue(store.all().isEmpty());
    }

    @Test public void failedNestedRequestDoesNotReplaceSavedSources() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        store.importText("{\"bookSourceUrl\":\"https://old.test\"}",
                request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertThrows(IllegalStateException.class, () -> store.importText(
                "{\"bookSourceUrl\":\"https://new.test\",\"sourceUrls\":[\"https://missing.test/list\"]}",
                request -> new FetchResponse(request.url(), "Not Found", 404, Map.of())));
        assertEquals(1, store.all().size());
        assertEquals("https://old.test", store.all().get(0).id());
    }

    @Test public void topLevelUrlImportRejectsHttpErrorBeforeParsingBody() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        String body = "{\"bookSourceUrl\":\"https://error-page.test\"}";
        assertThrows(IllegalStateException.class, () -> store.importUrl("https://list.test/sources.json",
                request -> new FetchResponse(request.url(), body, 404, Map.of())));
        assertTrue(store.all().isEmpty());
        assertEquals("https://error-page.test", store.importUrl("https://list.test/sources.json",
                request -> new FetchResponse(request.url(), body, 200, Map.of())).sources().get(0).id());
    }

    @Test public void concurrentImportsMergeAgainstTheLatestSavedSnapshot() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        SourceStore recreatedStore = new SourceStore(storage);
        java.util.concurrent.CountDownLatch fetching = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                store.importText("{\"sourceUrls\":[\"https://list.test/first\"]}", request -> {
                    fetching.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting for second import");
                    return new FetchResponse(request.url(), "{\"bookSourceUrl\":\"https://first.test\"}", 200, Map.of());
                });
            } catch (Throwable e) { failure.set(e); }
        });
        first.start();
        assertTrue(fetching.await(5, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.CountDownLatch secondCommitted = new java.util.concurrent.CountDownLatch(1);
        Thread second = new Thread(() -> {
            try {
                recreatedStore.importText("{\"bookSourceUrl\":\"https://second.test\"}",
                        request -> new FetchResponse(request.url(), "", 200, Map.of()));
                secondCommitted.countDown();
            }
            catch (Throwable e) { failure.set(e); }
        });
        second.start();
        try { assertTrue(secondCommitted.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        first.join(5000); second.join(5000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertNull(failure.get());
        assertEquals(java.util.Set.of("https://first.test", "https://second.test"),
                store.all().stream().map(source -> source.id()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void cancelledImportDoesNotCommitAfterFetchReturns() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> store.importUrl("https://list.test/source",
                    request -> {
                        Thread.currentThread().interrupt();
                        return new FetchResponse(request.url(), "{\"bookSourceUrl\":\"https://cancelled.test\"}",
                                200, Map.of());
                    }));
        } finally { Thread.interrupted(); }
        assertTrue(store.all().isEmpty());
    }
    @Test public void subscriptionsPersistAndRefreshWithoutLosingManualSources() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        store.importText("{\"bookSourceUrl\":\"https://manual.test\"}",
                request -> new FetchResponse(request.url(), "", 200, Map.of()));
        String url = "https://feed.test/sources.json";
        store.importUrl(url, request -> new FetchResponse(request.url(),
                "[{\"bookSourceUrl\":\"https://old.test\"}]", 200, Map.of()));
        SourceStore recreated = new SourceStore(storage);
        assertEquals(url, recreated.subscriptions().get(0).url());
        recreated.updateSubscriptions(request -> new FetchResponse(request.url(),
                "[{\"bookSourceUrl\":\"https://new.test\"}]", 200, Map.of()));
        assertEquals(java.util.Set.of("https://manual.test", "https://new.test"),
                recreated.all().stream().map(source -> source.id()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void failedRefreshPreservesSourcesAndSubscription() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        store.importUrl("https://feed.test/sources.json", request -> new FetchResponse(request.url(),
                "{\"bookSourceUrl\":\"https://old.test\"}", 200, Map.of()));
        assertThrows(IllegalStateException.class, () -> store.updateSubscriptions(request ->
                new FetchResponse(request.url(), "broken", 503, Map.of())));
        assertEquals("https://old.test", store.all().get(0).id());
        assertEquals(1, store.subscriptions().size());
    }
    @Test public void partiallyMalformedRefreshCannotDeletePreviouslySavedSources() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        String url = "https://feed.test/sources.json";
        store.importUrl(url, request -> new FetchResponse(request.url(),
                "[{\"bookSourceUrl\":\"https://old-a.test\"},{\"bookSourceUrl\":\"https://old-b.test\"}]",
                200, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.updateSubscriptions(request ->
                new FetchResponse(request.url(),
                        "[{\"bookSourceUrl\":\"https://new.test\"},{\"bookSourceUrl\":\"https://old-b.test\",\"bookSourceName\":[]}]",
                        200, Map.of())));
        assertEquals(java.util.Set.of("https://old-a.test", "https://old-b.test"),
                store.all().stream().map(source -> source.id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("https://old-a.test", "https://old-b.test"),
                store.subscriptions().get(0).sourceIds());
    }
    @Test public void importingTheSameFeedReplacesRemovedSources() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        String url = "https://feed.test/list";
        store.importUrl(url, request -> new FetchResponse(request.url(),
                "{\"bookSourceUrl\":\"https://old.test\"}", 200, Map.of()));
        store.importUrl(url, request -> new FetchResponse(request.url(),
                "{\"bookSourceUrl\":\"https://new.test\"}", 200, Map.of()));
        assertEquals(1, store.all().size());
        assertEquals("https://new.test", store.all().get(0).id());
        assertEquals(1, store.subscriptions().size());
    }
    @Test public void reimportingMalformedFeedAlsoPreservesPreviousSources() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        String url = "https://feed.test/list";
        store.importUrl(url, request -> new FetchResponse(request.url(),
                "[{\"bookSourceUrl\":\"https://old-a.test\"},{\"bookSourceUrl\":\"https://old-b.test\"}]",
                200, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.importUrl(url, request ->
                new FetchResponse(request.url(),
                        "[{\"bookSourceUrl\":\"https://new.test\"},{\"bookSourceUrl\":\"https://old-b.test\",\"bookSourceName\":[]}]",
                        200, Map.of())));
        assertEquals(java.util.Set.of("https://old-a.test", "https://old-b.test"),
                store.all().stream().map(source -> source.id()).collect(java.util.stream.Collectors.toSet()));
    }
    private static String directoryConfig() {
        return "[{\"sourceUrl\":\"http://yuedu.miaogongzi.net/gx.html\"}]";
    }
    private static String directoryPage(String feedUrl) {
        return "<div class='aui-flex'><h1>精选合集</h1><a href='yuedu://booksource/importonline?src="
                + feedUrl + "'>导入</a></div>";
    }

    @Test public void directorySelectionPersistsAndTracksChangedCollectionUrl() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SourceStore store = new SourceStore(storage);
        String configUrl = "http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json";
        SourceStore.UrlImport candidate = store.importUrlOrPreview(configUrl, request ->
                new FetchResponse(request.url(), request.url().endsWith(".json") ? directoryConfig()
                        : directoryPage("https://feed.test/old.json"), 200, Map.of()));
        assertNull(candidate.imported());
        assertEquals("精选合集", candidate.directory().collections().get(0).name());
        assertTrue(store.all().isEmpty());
        store.importDirectorySelection(candidate.directory(), java.util.List.of("精选合集"), request ->
                new FetchResponse(request.url(), "{\"bookSourceUrl\":\"https://old.test\"}", 200, Map.of()));
        SourceStore recreated = new SourceStore(storage);
        assertEquals(configUrl, recreated.subscriptions().get(0).url());
        assertEquals("精选合集", recreated.subscriptions().get(0).collections().get(0).name());
        recreated.updateSubscriptions(request -> new FetchResponse(request.url(),
                request.url().equals(configUrl) ? directoryConfig()
                        : request.url().endsWith("gx.html") ? directoryPage("https://feed.test/new.json")
                        : "{\"bookSourceUrl\":\"https://new.test\"}", 200, Map.of()));
        assertEquals("https://new.test", recreated.all().get(0).id());
        assertEquals("https://feed.test/new.json", recreated.subscriptions().get(0).collections().get(0).url());
    }

    @Test public void directoryRefreshFailureLeavesPreviouslySavedSourcesAndSelection() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        String configUrl = "http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json";
        SourceStore.DirectoryPreview preview = store.importUrlOrPreview(configUrl, request ->
                new FetchResponse(request.url(), request.url().equals(configUrl) ? directoryConfig()
                        : directoryPage("https://feed.test/one.json"), 200, Map.of())).directory();
        store.importDirectorySelection(preview, java.util.List.of("精选合集"), request ->
                new FetchResponse(request.url(), "{\"bookSourceUrl\":\"https://kept.test\"}", 200, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.updateSubscriptions(request ->
                new FetchResponse(request.url(), request.url().equals(configUrl) ? directoryConfig()
                        : "<h1>合集已移除</h1>", 200, Map.of())));
        assertEquals("https://kept.test", store.all().get(0).id());
        assertEquals("https://feed.test/one.json", store.subscriptions().get(0).collections().get(0).url());
    }
    @Test public void reimportKeepsSourcesStillOwnedByAnotherSubscription() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        var response = (org.bookgetter.core.Transport) request -> new FetchResponse(request.url(),
                "{\"bookSourceUrl\":\"https://shared.test\"}", 200, Map.of());
        store.importUrl("https://feed.test/a", response);
        store.importUrl("https://feed.test/b", response);
        store.importUrl("https://feed.test/a", request -> new FetchResponse(request.url(),
                "{\"bookSourceUrl\":\"https://new.test\"}", 200, Map.of()));
        assertEquals(java.util.Set.of("https://shared.test", "https://new.test"),
                store.all().stream().map(source -> source.id()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test public void selectedCollectionsCommitOnlyAfterEveryDownloadSucceeds() throws Exception {
        SourceStore store = new SourceStore(new MemoryStorage());
        String html = directoryPage("https://feed.test/one.json")
                + "<div class='aui-flex'><h1>第二合集</h1><a href='yuedu://booksource/importonline?src=https://feed.test/two.json'>导入</a></div>";
        SourceStore.DirectoryPreview preview = store.importUrlOrPreview(
                "http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json", request ->
                new FetchResponse(request.url(), request.url().endsWith(".json") ? directoryConfig() : html,
                        200, Map.of())).directory();
        assertThrows(IllegalStateException.class, () -> store.importDirectorySelection(preview,
                java.util.List.of("精选合集", "第二合集"), request -> new FetchResponse(request.url(),
                        "{\"bookSourceUrl\":\"https://partial.test\"}",
                        request.url().endsWith("two.json") ? 503 : 200, Map.of())));
        assertTrue(store.all().isEmpty());
        assertTrue(store.subscriptions().isEmpty());
    }
}
