package org.bookgetter.app;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.util.Map;
import org.bookgetter.core.*;
import org.bookgetter.core.RssModels.*;
import org.junit.Test;

public class RssCacheSessionRegressionTest {
    private RssSource source() {
        JsonObject json = new JsonObject(); json.addProperty("sourceUrl", "https://rss.test");
        return new RssSource("https://rss.test", "RSS", RssKind.WEB, json);
    }
    private Article article() {
        return new Article("https://rss.test", "news", "Article", "https://rss.test/private/article",
                "", null, null, null, RssKind.WEB, Map.of());
    }
    @Test public void oldSessionResponseCannotBeStoredUnderNewLoginState() {
        var source = source(); var article = article(); var states = new MemorySourceStateStore();
        var cache = new RssReadCache(CookieAccess.NONE);
        var ticket = cache.ticket(source, article, states);
        states.put(SourcePolicy.of(source.executionSource()).stateKey(), "loginHeader", "new session");
        assertFalse(cache.putIfUnchanged(ticket, source, article, states, new HtmlContent("old session", source.id())));
        assertNull(cache.get(source, article, states));
    }
    @Test public void explicitRefreshRejectsInFlightPreloadEvenWithSameState() {
        var source = source(); var article = article(); var states = new MemorySourceStateStore();
        var cache = new RssReadCache(CookieAccess.NONE);
        var ticket = cache.ticket(source, article, states);
        cache.clear();
        assertFalse(cache.putIfUnchanged(ticket, source, article, states, new HtmlContent("stale", source.id())));
        assertNull(cache.get(ticket));
        assertNull(cache.get(source, article, states));
    }
    @Test public void differentStructuredStateDoesNotCollideAtCommaAndEquals() {
        var source = source(); var article = article(); var states = new MemorySourceStateStore();
        var cache = new RssReadCache(CookieAccess.NONE);
        String identity = SourcePolicy.of(source.executionSource()).stateKey();
        states.put(identity, "a", "b, c=d");
        cache.put(source, article, states, new HtmlContent("old state", source.id()));
        states.put(identity, "a", "b"); states.put(identity, "c", "d");
        assertNull(cache.get(source, article, states));
    }
    @Test public void articlePathCookieInvalidatesCacheEvenWhenRootCookieDoesNotChange() {
        String[] cookie = {"first"};
        CookieAccess cookies = new CookieAccess() {
            public String get(String url) { return url.contains("/private/") ? cookie[0] : "root"; }
            public void set(String url, String value) { cookie[0] = value; }
            public void replace(String url, String value) { set(url, value); }
            public void remove(String url) { cookie[0] = ""; }
        };
        var source = source(); var article = article(); var states = new MemorySourceStateStore();
        var cache = new RssReadCache(cookies);
        cache.put(source, article, states, new HtmlContent("first session", source.id()));
        cookie[0] = "second";
        assertNull(cache.get(source, article, states));
    }
}
