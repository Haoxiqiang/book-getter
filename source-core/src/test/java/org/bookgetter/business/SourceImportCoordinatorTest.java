package org.bookgetter.business;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.Transport;
import org.junit.jupiter.api.Test;

public class SourceImportCoordinatorTest {
    private static final class Memory implements MixedSourceStore.Storage {
        MixedSourceStore.Snapshot value = new MixedSourceStore.Snapshot("[]", "[]", null, "[]", "[]", null);
        boolean fail;
        int commits;
        @Override public MixedSourceStore.Snapshot read() { return value; }
        @Override public boolean write(MixedSourceStore.Snapshot next) {
            if (fail) return false;
            commits++; value = next; return true;
        }
    }
    private static String mixed(String name) {
        return "[{\"bookSourceUrl\":\"same\",\"bookSourceName\":\"" + name + "\"},"
                + "{\"sourceUrl\":\"same\",\"sourceName\":\"" + name + "\"}]";
    }
    private static Transport returns(String body) {
        return request -> new FetchResponse(request.url(), body, 200, Map.of());
    }

    @Test public void mixedClipboardImportKeepsBothModelsAndUsesOneCommit() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var result = new SourceImportCoordinator(store).importText(mixed("manual"), returns(""));
        assertEquals(1, result.books().sources().size()); assertEquals(1, result.rssCount());
        assertEquals(1, memory.commits);
        assertEquals("same", new SourceStore(store.bookStorage()).all().get(0).id());
        assertEquals("same", new RssStore(store.rssStorage()).all().get(0).id());
    }

    @Test public void nestedContainersResolveMixedModelsAndFailAtomically() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        coordinator.importText("{\"sourceUrls\":[\"https://feed.test/a\"]}", returns(mixed("nested")));
        assertEquals(1, new RssStore(store.rssStorage()).all().size());
        var before = memory.value;
        assertThrows(IllegalStateException.class, () -> coordinator.importText(
                "{\"sourceUrls\":[\"https://feed.test/a\",\"https://feed.test/b\"]}", request ->
                        new FetchResponse(request.url(), mixed("replacement"), request.url().endsWith("b") ? 500 : 200, Map.of())));
        assertEquals(before, memory.value);
    }

    @Test public void mixedSubscriptionRemovalRestoresBothManualModels() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        coordinator.importText(mixed("manual"), returns(""));
        coordinator.importUrlOrPreview("https://feed.test/all", returns(mixed("feed")));
        assertEquals("feed", new SourceStore(store.bookStorage()).all().get(0).name());
        assertEquals("feed", new RssStore(store.rssStorage()).all().get(0).name());
        coordinator.updateSubscriptions(returns("[]"));
        assertEquals("manual", new SourceStore(store.bookStorage()).all().get(0).name());
        assertEquals("manual", new RssStore(store.rssStorage()).all().get(0).name());
    }

    @Test public void failedUpdateOrDurableCommitKeepsBothModelsUnchanged() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        coordinator.importUrlOrPreview("https://feed.test/all", returns(mixed("old")));
        var before = memory.value;
        assertThrows(IllegalArgumentException.class, () -> coordinator.updateSubscriptions(returns(
                "[{\"bookSourceUrl\":\"new\"},{\"sourceUrl\":null}]")));
        assertEquals(before, memory.value);
        memory.fail = true;
        assertThrows(IllegalStateException.class, () -> coordinator.importText(mixed("new"), returns("")));
        assertEquals(before, memory.value);
    }

    @Test public void rssOnlySubscriptionUpdatesWithoutBookSourceErrors() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        coordinator.importUrlOrPreview("https://feed.test/rss", returns("{\"sourceUrl\":\"rss-old\"}"));
        var result = coordinator.updateSubscriptions(returns("{\"sourceUrl\":\"rss-new\"}"));
        assertTrue(result.books().sources().isEmpty());
        assertEquals(List.of("rss-new"), new RssStore(store.rssStorage()).all().stream().map(source -> source.id()).toList());
    }

    @Test public void rssOwnershipRestoresOtherFeedBeforeManualVersion() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        coordinator.importText("{\"sourceUrl\":\"rss\",\"sourceName\":\"manual\"}", returns(""));
        coordinator.importUrlOrPreview("https://feed.test/one", returns("{\"sourceUrl\":\"rss\",\"sourceName\":\"one\"}"));
        coordinator.importUrlOrPreview("https://feed.test/two", returns("{\"sourceUrl\":\"rss\",\"sourceName\":\"two\"}"));
        coordinator.importUrlOrPreview("https://feed.test/two", returns("[]"));
        assertEquals("one", new RssStore(store.rssStorage()).all().get(0).name());
        coordinator.importUrlOrPreview("https://feed.test/one", returns("[]"));
        assertEquals("manual", new RssStore(store.rssStorage()).all().get(0).name());
    }

    @Test public void depthAndCancellationDoNotCommit() throws Exception {
        Memory memory = new Memory(); MixedSourceStore store = new MixedSourceStore(memory);
        var coordinator = new SourceImportCoordinator(store);
        assertThrows(IllegalArgumentException.class, () -> coordinator.importText(
                "{\"sourceUrls\":[\"https://feed.test/nested\"]}", returns("{\"sourceUrls\":[\"https://feed.test/nested\"]}")));
        assertEquals(0, memory.commits);
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.io.InterruptedIOException.class, () -> coordinator.importText(mixed("manual"), returns("")));
        } finally { Thread.interrupted(); }
        assertEquals(0, memory.commits);
    }
}
