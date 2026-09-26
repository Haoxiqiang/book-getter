package org.bookgetter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SourceCatalogManagerTest {
    @Test void normalizedUrlHandlesCaseDefaultPortSlashAndFragment() {
        assertEquals("https://example.org/books", SourceCatalogManager.normalizedId("HTTPS://EXAMPLE.ORG:443/books/#frag"));
        assertEquals("http://example.org", SourceCatalogManager.normalizedId("http://Example.org:80/"));
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
}
