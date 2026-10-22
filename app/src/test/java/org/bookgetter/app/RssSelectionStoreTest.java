package org.bookgetter.app;

import java.nio.file.Files;
import java.util.Map;
import org.bookgetter.core.RssModels.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RssSelectionStoreTest {
    @Test public void selectionSurvivesStoreRecreationAndRejectsInvalidTokens() throws Exception {
        var directory = Files.createTempDirectory("rss-selection").toFile();
        var store = new RssSelectionStore(directory);
        var article = new Article("https://rss.test", "news", "Title", "https://rss.test/a", "", "<p>summary</p>",
                null, null, RssKind.WEB, Map.of("k", "v"));
        String token = store.save(article);
        assertEquals(article, new RssSelectionStore(directory).restore(token));
        assertNull(store.restore("../source-state"));
        store.remove(token); assertNull(store.restore(token));
    }
}
