package org.bookgetter.business;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bookgetter.core.*;
import org.bookgetter.core.RssModels.*;

/** Small read-through/preload cache, invalidated by source rules, private state and cookies. */
public final class RssReadCache {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private record Entry(ReadTarget target, int bytes) {}
    public record Ticket(String key, long version) {}
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final CookieAccess cookies;
    private int bytes;
    private long version;
    public RssReadCache(CookieAccess cookies) { this.cookies = cookies; }
    public synchronized Ticket ticket(RssSource source, Article article, SourceStateStore states) {
        return new Ticket(key(source, article, states), version);
    }
    public synchronized ReadTarget get(Ticket ticket) {
        if (ticket.version() != version) return null;
        Entry entry = entries.get(ticket.key()); return entry == null ? null : entry.target();
    }
    public synchronized ReadTarget get(RssSource source, Article article, SourceStateStore states) {
        return get(ticket(source, article, states));
    }
    public synchronized void put(RssSource source, Article article, SourceStateStore states, ReadTarget target) {
        putIfUnchanged(ticket(source, article, states), source, article, states, target);
    }
    public synchronized boolean putIfUnchanged(Ticket ticket, RssSource source, Article article,
                                        SourceStateStore states, ReadTarget target) {
        if (ticket.version() != version || !ticket.key().equals(key(source, article, states))) return false;
        int size = new Gson().toJson(target).getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_BYTES) return false;
        Entry previous = entries.put(ticket.key(), new Entry(target, size));
        bytes += size - (previous == null ? 0 : previous.bytes());
        while (bytes > MAX_BYTES || entries.size() > 64) {
            var iterator = entries.entrySet().iterator(); Entry first = iterator.next().getValue();
            bytes -= first.bytes(); iterator.remove();
        }
        return true;
    }
    public synchronized void clear() { version++; entries.clear(); bytes = 0; }
    private String key(RssSource source, Article article, SourceStateStore states) {
        String stateKey = SourcePolicy.of(source.executionSource()).stateKey();
        String articleCookies = ResourceUrls.isHttp(article.link()) ? cookies.get(article.link()) : "";
        // Structured encoding distinguishes state values containing commas/equal signs.
        return hash(new Gson().toJson(new Object[] {source.json(), article,
                new java.util.TreeMap<>(states.snapshot(stateKey)), (ResourceUrls.isHttp(source.id()) ? cookies.get(source.id()) : ""), articleCookies}));
    }
    private static String hash(String input) {
        try { return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
