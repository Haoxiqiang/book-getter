package org.bookgetter.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class FeedUrlValidationTest {
    private JsonObject feed(String url, boolean allowHttp) {
        JsonObject feed = new JsonObject();
        feed.addProperty("url", url);
        feed.addProperty("allowHttp", allowHttp);
        return feed;
    }
    @Test void acceptsSchemeCaseConsistentlyAndRequiresExplicitPlainHttp() {
        assertEquals("HTTPS", SourceCatalogManager.validateFeedUrl(feed("HTTPS://example.test/sources", false)).getScheme());
        assertEquals("HTTP", SourceCatalogManager.validateFeedUrl(feed("HTTP://example.test/sources", true)).getScheme());
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.validateFeedUrl(feed("http://example.test/sources", false)));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.validateFeedUrl(feed("file:///sources", true)));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.validateFeedUrl(feed("https:///sources", false)));
    }
}
