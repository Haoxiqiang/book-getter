package org.bookgetter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SourceCatalogManagerTest {
    @Test void normalizedUrlHandlesCaseDefaultPortSlashAndFragment() {
        assertEquals("https://example.org/books", SourceCatalogManager.normalizedId("HTTPS://EXAMPLE.ORG:443/books/#frag"));
        assertEquals("http://example.org", SourceCatalogManager.normalizedId("http://Example.org:80/"));
    }

    @Test void symbolicBookSourceKeysRemainDistinctAndRssUrlsStayStrict() {
        assertEquals("key:必应搜书", SourceCatalogManager.normalizedId(" 必应搜书 "));
        assertEquals("key:必应搜书#自定义", SourceCatalogManager.normalizedId("必应搜书#自定义"));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.normalizedId("file:///secret"));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.rssSourceId(
                JsonParser.parseString("{\"sourceUrl\":\"搜索代理\"}")));
        JsonArray all = JsonParser.parseString("[{\"bookSourceUrl\":\"必应搜书\"},"
                + "{\"bookSourceUrl\":\"https://example.org\"}]").getAsJsonArray();
        JsonArray files = JsonParser.parseString("[{\"file\":\"mixed.json\",\"entries\":2}]").getAsJsonArray();
        assertEquals(2, SourceCatalogManager.merge(all, files).unique().size());
    }

    @Test void mergeKeepsFirstSourceAndRecordsDuplicate() {
        JsonArray all = JsonParser.parseString("[{\"bookSourceName\":\"first\",\"bookSourceUrl\":\"https://example.org/\"},"
                + "{\"bookSourceName\":\"second\",\"bookSourceUrl\":\"https://EXAMPLE.org:443\"}]").getAsJsonArray();
        JsonArray files = JsonParser.parseString("[{\"file\":\"one.json\",\"entries\":1},"
                + "{\"file\":\"two.json\",\"entries\":1}]").getAsJsonArray();
        var merged = SourceCatalogManager.merge(all, files);
        assertEquals(1, merged.unique().size());
        assertEquals("first", merged.unique().get(0).getAsJsonObject().get("bookSourceName").getAsString());
        assertEquals("one.json", merged.duplicates().get(0).getAsJsonObject().get("keptFrom").getAsString());
    }
    @Test void rssIsValidatedButExcludedFromBookMerge() {
        var rss = JsonParser.parseString("{\"sourceUrl\":\"http://example.org/rss\"}");
        assertEquals("http://example.org/rss", SourceCatalogManager.rssSourceId(rss));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.rssSourceId(
                JsonParser.parseString("{\"bookSourceUrl\":\"https://example.org\"}")));
        JsonArray files = JsonParser.parseString("[{\"file\":\"rss.json\",\"entries\":1,\"importedEntries\":0}]").getAsJsonArray();
        assertEquals(0, SourceCatalogManager.merge(new JsonArray(), files).unique().size());
    }
}
