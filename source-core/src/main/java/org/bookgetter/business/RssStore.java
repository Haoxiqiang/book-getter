package org.bookgetter.business;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.Diagnostic;
import org.bookgetter.core.Models.Severity;
import org.bookgetter.core.RssImporter;
import org.bookgetter.core.RssModels.RssSource;

/** RSS snapshots have separate keys and feed ownership from ordinary book sources. */
public final class RssStore {
    public interface Storage {
        String read();
        String readManual();
        String readSubscriptions();
        boolean write(String sources, String manual, String subscriptions);
    }
    public record Subscription(String url, List<RssSource> sources) {
        public Subscription { sources = List.copyOf(sources); }
    }
    private final Storage storage;
    public RssStore(Storage storage) { this.storage = storage; }
    public List<RssSource> all() { return parseSaved(storage.read()); }
    public List<Subscription> subscriptions() {
        List<Subscription> feeds = new ArrayList<>();
        for (JsonElement entry : JsonParser.parseString(storage.readSubscriptions()).getAsJsonArray()) {
            JsonObject object = entry.getAsJsonObject();
            feeds.add(new Subscription(object.get("url").getAsString(), parseSaved(object.get("sources").toString())));
        }
        return List.copyOf(feeds);
    }
    void importResolved(List<RssSource> incoming, List<Diagnostic> diagnostics, String subscriptionUrl) throws Exception {
        synchronized (SourceStore.COMMIT_LOCK) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RSS import cancelled");
            List<Subscription> feeds = new ArrayList<>(subscriptions());
            String savedManual = storage.readManual();
            Map<String, RssSource> manual = byId(savedManual == null ? all() : parseSaved(savedManual));
            if (savedManual == null) for (Subscription feed : feeds)
                feed.sources().forEach(source -> manual.remove(source.id()));
            if (subscriptionUrl == null) incoming.forEach(source -> manual.put(source.id(), source));
            else {
                if (feeds.stream().anyMatch(feed -> feed.url().equals(subscriptionUrl))
                        && diagnostics.stream().anyMatch(item -> item.severity() == Severity.ERROR))
                    throw new IllegalArgumentException("Updated RSS source list has invalid entries");
                // Keep every feed's original definitions so removing its last ownership
                // restores another feed's definition or the manual snapshot.
                feeds.removeIf(feed -> feed.url().equals(subscriptionUrl));
                feeds.add(new Subscription(subscriptionUrl, incoming));
            }
            Map<String, RssSource> active = new LinkedHashMap<>(manual);
            for (Subscription feed : feeds) feed.sources().forEach(source -> active.put(source.id(), source));
            JsonArray subscriptionJson = new JsonArray();
            for (Subscription feed : feeds) {
                JsonObject object = new JsonObject(); object.addProperty("url", feed.url());
                object.add("sources", array(feed.sources())); subscriptionJson.add(object);
            }
            if (!storage.write(array(new ArrayList<>(active.values())).toString(), array(new ArrayList<>(manual.values())).toString(), subscriptionJson.toString()))
                throw new IllegalStateException("Cannot save RSS sources");
        }
    }
    private static Map<String, RssSource> byId(List<RssSource> sources) {
        Map<String, RssSource> result = new LinkedHashMap<>();
        sources.forEach(source -> result.put(source.id(), source)); return result;
    }
    private static JsonArray array(List<RssSource> sources) {
        JsonArray array = new JsonArray(); sources.forEach(source -> array.add(source.json())); return array;
    }
    private static List<RssSource> parseSaved(String json) {
        var result = RssImporter.parse(json);
        if (result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR))
            throw new IllegalStateException("Saved RSS source data is damaged");
        return result.sources();
    }
}
