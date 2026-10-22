package org.bookgetter.business;

import java.util.Map;
import org.bookgetter.core.*;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RssRequestPolicyTest {
    @Test public void originFilteredBrowserHeadersAreNotRebuiltAndLoginOverridesDefaults() throws Exception {
        var source = RssImporter.parse("{\"sourceUrl\":\"https://rss.test\",\"header\":\"{\\\"Authorization\\\":\\\"old\\\"}\"}").sources().get(0);
        var states = new MemorySourceStateStore();
        states.put(SourcePolicy.of(source.executionSource()).stateKey(), "loginHeader", "{\"Authorization\":\"new\"}");
        var same = SourceRequests.prepare(RssRequestPolicy.forSource(source,
                new FetchRequest("https://rss.test/article", "GET", Map.of("Authorization", "old"), null, null, false)), states);
        assertEquals("new", same.headers().get("Authorization"));
        var other = SourceRequests.prepare(RssRequestPolicy.forSource(source, new FetchRequest("https://other.test/article")), states);
        assertFalse(other.headers().containsKey("Authorization"));
    }
}
