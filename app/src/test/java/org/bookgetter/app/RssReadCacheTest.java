package org.bookgetter.app;

import com.google.gson.JsonObject;
import java.util.Map;
import org.bookgetter.core.*;
import org.bookgetter.core.RssModels.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RssReadCacheTest {
    private RssSource source() {
        JsonObject json = new JsonObject(); json.addProperty("sourceUrl", "https://rss.test");
        return new RssSource("https://rss.test", "RSS", RssKind.WEB, json);
    }
    private Article article(String title) {
        return new Article("https://rss.test", "news", title, "https://rss.test/a", "", null, null, null, RssKind.WEB, Map.of());
    }
    @Test public void sourceRulesPrivateStateAndArticleChangesInvalidateCache() {
        RssSource source = source(); var states = new MemorySourceStateStore(); var cache = new RssReadCache(CookieAccess.NONE);
        HtmlContent target = new HtmlContent("<p>body</p>", source.id());
        cache.put(source, article("one"), states, target);
        assertEquals(target, cache.get(source, article("one"), states));
        assertNull(cache.get(source, article("changed"), states));
        states.put(SourcePolicy.of(source.executionSource()).stateKey(), "loginHeader", "new");
        assertNull(cache.get(source, article("one"), states));
        cache.put(source, article("one"), states, target);
        source.json().addProperty("ruleContent", "changed");
        assertNull(cache.get(source, article("one"), states));
    }
    @Test public void cookiesAndExplicitRefreshInvalidateCache() {
        String[] cookie = {"one"};
        CookieAccess cookies = new CookieAccess() {
            public String get(String url) { return cookie[0]; }
            public void set(String url, String value) { cookie[0] = value; }
            public void replace(String url, String value) { set(url, value); }
            public void remove(String url) { cookie[0] = ""; }
        };
        var source = source(); var cache = new RssReadCache(cookies); var states = new MemorySourceStateStore();
        cache.put(source, article("one"), states, new BrowserPage(source.id()));
        cookie[0] = "two"; assertNull(cache.get(source, article("one"), states));
        cache.put(source, article("one"), states, new BrowserPage(source.id()));
        cache.clear(); assertNull(cache.get(source, article("one"), states));
    }
}
