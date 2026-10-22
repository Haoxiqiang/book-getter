package org.bookgetter.app;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.bookgetter.core.Models.Resource;
import org.junit.Test;

public class PendingResourceStoreTest {
    @Test public void expiredOrphansAreRemovedWithoutDroppingRecentSelection() throws Exception {
        java.nio.file.Path directory = Files.createTempDirectory("pending-resource-expiry-");
        PendingResourceStore store = new PendingResourceStore(directory.toFile());
        PendingResourceDownload pending = new PendingResourceDownload("source", "hash",
                List.of(new Resource("https://example.test/resource", "Page", null)));
        String expired = store.save(pending);
        String recent = store.save(pending);
        assertTrue(directory.resolve("pending-resources").resolve(expired + ".json").toFile().setLastModified(1));
        PendingResourceStore restored = new PendingResourceStore(directory.toFile());
        assertNull(restored.restore(expired));
        assertEquals(pending, restored.restore(recent));
    }

    @Test public void largeSelectionRestoresThroughSmallTokenAndCanBeDeleted() throws Exception {
        PendingResourceStore store = new PendingResourceStore(Files.createTempDirectory("pending-resource-").toFile());
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < 5000; i++) resources.add(new Resource("https://example.test/" + i + "?token=" + "x".repeat(300), "Page " + i, null));
        PendingResourceDownload pending = new PendingResourceDownload("source", "hash", resources);
        String token = store.save(pending);
        assertEquals(36, token.length());
        assertEquals(pending, store.restore(token));
        assertNull(store.restore("../capture-job"));
        store.delete(token);
        assertNull(store.restore(token));
    }
}
