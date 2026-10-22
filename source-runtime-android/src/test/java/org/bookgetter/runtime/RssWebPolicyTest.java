package org.bookgetter.runtime;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RssWebPolicyTest {
    @Test public void originsAreExactAndRejectCredentialsAndOpaqueUrls() {
        assertEquals("https://books.test", RssWebPolicy.origin("HTTPS://BOOKS.test:443/path?q=1"));
        assertEquals("http://books.test:8080", RssWebPolicy.origin("http://books.test:8080/path"));
        assertEquals("", RssWebPolicy.origin("https://user:pass@books.test/"));
        assertEquals("", RssWebPolicy.origin("data:text/html,hello"));
        assertNotEquals(RssWebPolicy.origin("https://books.test"), RssWebPolicy.origin("https://books.test.evil"));
    }
    @Test public void nonemptyBlacklistTakesPrecedenceOverWhitelist() {
        RssWebPolicy policy = new RssWebPolicy("https://ads.test/", "https://books.test/");
        assertFalse(policy.permitsResource("https://ads.test/banner", () -> false));
        assertTrue(policy.permitsResource("https://other.test/image", () -> false));
    }
    @Test public void whitelistAllowsPrefixesOrWholeJavaRegex() {
        RssWebPolicy policy = new RssWebPolicy("", "https://books.test/,https://cdn\\.test/[0-9]+\\.png");
        assertTrue(policy.permitsResource("https://books.test/style.css", () -> false));
        assertTrue(policy.permitsResource("https://cdn.test/123.png", () -> false));
        assertFalse(policy.permitsResource("https://other.test/123.png", () -> false));
    }
    @Test public void cancelledFilteringStopsBeforePrefixMatch() {
        assertThrows(IllegalArgumentException.class, () -> new RssWebPolicy("https://ads.test/", "")
                .permitsResource("https://ads.test/1", () -> true));
    }
    @Test(timeout = 2000) public void pathologicalResourceFilterHasLinearBoundedBehavior() {
        assertTrue(new RssWebPolicy("https://x/(a|aa)+$", "")
                .permitsResource("https://x/" + "a".repeat(10000) + "!", () -> false));
    }
    @Test public void bridgeRequiresAnAuthorizedCurrentOriginAndMainFrame() {
        java.util.Set<String> allowed = java.util.Set.of("https://books.test");
        assertTrue(RssWebPolicy.permitsBridge(allowed, "https://books.test", "https://books.test/page", true));
        assertFalse(RssWebPolicy.permitsBridge(allowed, "https://books.test", "https://books.test/frame", false));
        assertFalse(RssWebPolicy.permitsBridge(allowed, "https://books.test", "https://books.test.evil/", true));
        assertFalse(RssWebPolicy.permitsBridge(allowed, "https://elsewhere.test", "https://books.test/", true));
        assertFalse(RssWebPolicy.permitsBridge(allowed, "https://books.test", "http://books.test/", true));
        assertFalse(RssWebPolicy.permitsBridge(allowed, "https://books.test", "data:text/html,hi", true));
    }

}
