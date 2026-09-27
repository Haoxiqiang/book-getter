package org.bookgetter.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bookgetter.core.MiaogongziDirectoryAdapter;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SourceImporter;
import org.bookgetter.core.Transport;

final class SourceStore {
    private static final Object COMMIT_LOCK = new Object();
    interface Storage {
        String read();
        String readSubscriptions();
        boolean write(String sources, String subscriptions);
    }
    private final Storage storage;
    SourceStore(Context context) { this(preferences(context.getApplicationContext())); }
    SourceStore(Storage storage) { this.storage = storage; }
    private static Storage preferences(Context context) {
        SharedPreferences values = context.getSharedPreferences("sources", 0);
        return new Storage() {
            @Override public String read() { return values.getString("json", "[]"); }
            @Override public String readSubscriptions() { return values.getString("subscriptions", "[]"); }
            @Override public boolean write(String sources, String subscriptions) {
                return values.edit().putString("json", sources).putString("subscriptions", subscriptions).commit();
            }
        };
    }
    record SelectedCollection(String name, String url) {}
    record Subscription(String url, Set<String> sourceIds, List<SelectedCollection> collections) {
        Subscription(String url, Set<String> sourceIds) { this(url, sourceIds, List.of()); }
        boolean directory() { return !collections.isEmpty(); }
    }
    record DirectoryPreview(String url, List<MiaogongziDirectoryAdapter.Collection> collections) {}
    record UrlImport(ImportResult imported, DirectoryPreview directory) {}
    List<Source> all() { return SourceImporter.parse(storage.read()).sources(); }
    List<Subscription> subscriptions() {
        List<Subscription> result = new ArrayList<>();
        for (JsonElement entry : JsonParser.parseString(storage.readSubscriptions()).getAsJsonArray()) {
            JsonObject item = entry.getAsJsonObject();
            Set<String> ids = new LinkedHashSet<>();
            item.getAsJsonArray("sourceIds").forEach(id -> ids.add(id.getAsString()));
            List<SelectedCollection> selected = new ArrayList<>();
            if (item.has("collections")) for (JsonElement collection : item.getAsJsonArray("collections")) {
                JsonObject object = collection.getAsJsonObject();
                selected.add(new SelectedCollection(object.get("name").getAsString(), object.get("url").getAsString()));
            }
            result.add(new Subscription(item.get("url").getAsString(), ids, selected));
        }
        return result;
    }
    ImportResult importText(String json, Transport transport) throws Exception {
        Parsed parsed = parse(json, transport);
        return commit(parsed.sources, parsed.diagnostics, null);
    }
    ImportResult importUrl(String url, Transport transport) throws Exception {
        UrlImport importResult = importUrlOrPreview(url, transport);
        if (importResult.directory() != null) throw new IllegalArgumentException("Select source collections before importing");
        return importResult.imported();
    }
    UrlImport importUrlOrPreview(String url, Transport transport) throws Exception {
        String json = fetchSourceList(url, transport);
        String page = MiaogongziDirectoryAdapter.directoryPage(json);
        if (page != null) return new UrlImport(null, new DirectoryPreview(url,
                MiaogongziDirectoryAdapter.discover(fetchSourceList(page, transport))));
        Parsed parsed = parse(json, transport);
        if (parsed.sources.isEmpty()) throw new IllegalArgumentException("Source list contains no usable BookSource");
        return new UrlImport(commit(parsed.sources, parsed.diagnostics,
                new Subscription(url, new LinkedHashSet<>(parsed.sources.keySet()))), null);
    }
    ImportResult importDirectorySelection(DirectoryPreview preview, List<String> names, Transport transport) throws Exception {
        if (names.isEmpty()) throw new IllegalArgumentException("Select at least one source collection");
        Map<String, MiaogongziDirectoryAdapter.Collection> offered = new LinkedHashMap<>();
        preview.collections().forEach(collection -> offered.put(collection.name(), collection));
        Map<String, Source> incoming = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<SelectedCollection> selected = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            MiaogongziDirectoryAdapter.Collection collection = offered.get(name);
            if (collection == null) throw new IllegalArgumentException("Unknown source collection: " + name);
            Parsed parsed = parse(fetchSourceList(collection.url(), transport), transport);
            if (parsed.sources.isEmpty()) throw new IllegalArgumentException("Collection contains no usable BookSource: " + name);
            incoming.putAll(parsed.sources);
            diagnostics.addAll(parsed.diagnostics);
            selected.add(new SelectedCollection(collection.name(), collection.url()));
        }
        return commit(incoming, diagnostics, new Subscription(preview.url(),
                new LinkedHashSet<>(incoming.keySet()), selected));
    }
    ImportResult updateSubscriptions(Transport transport) throws Exception {
        List<Subscription> registered = subscriptions();
        if (registered.isEmpty()) throw new IllegalStateException("No source URL subscriptions to update");
        Map<String, Source> fetched = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Subscription> revisedByUrl = new LinkedHashMap<>();
        for (Subscription subscription : registered) {
            Map<String, Source> fromSubscription = new LinkedHashMap<>();
            List<SelectedCollection> selected = List.of();
            if (subscription.directory()) {
                String config = fetchSourceList(subscription.url(), transport);
                String page = MiaogongziDirectoryAdapter.directoryPage(config);
                if (page == null) throw new IllegalStateException("Directory configuration changed: " + subscription.url());
                List<MiaogongziDirectoryAdapter.Collection> current = MiaogongziDirectoryAdapter.discover(
                        fetchSourceList(page, transport));
                selected = new ArrayList<>();
                for (SelectedCollection previous : subscription.collections()) {
                    MiaogongziDirectoryAdapter.Collection match = current.stream()
                            .filter(entry -> entry.name().equals(previous.name())).findFirst()
                            .orElseGet(() -> current.stream().filter(entry -> entry.url().equals(previous.url()))
                                    .findFirst().orElse(null));
                    if (match == null) throw new IllegalStateException("Selected collection disappeared: " + previous.name());
                    Parsed parsed = parse(fetchSourceList(match.url(), transport), transport);
                    requireCompleteRefresh(parsed, match.url());
                    if (parsed.sources.isEmpty()) throw new IllegalArgumentException("Collection contains no usable BookSource: " + match.name());
                    fromSubscription.putAll(parsed.sources);
                    diagnostics.addAll(parsed.diagnostics);
                    selected.add(new SelectedCollection(match.name(), match.url()));
                }
            } else {
                Parsed parsed = parse(fetchSourceList(subscription.url(), transport), transport);
                requireCompleteRefresh(parsed, subscription.url());
                if (parsed.sources.isEmpty()) throw new IllegalArgumentException("Source list contains no usable BookSource: " + subscription.url());
                fromSubscription.putAll(parsed.sources);
                diagnostics.addAll(parsed.diagnostics);
            }
            revisedByUrl.put(subscription.url(), new Subscription(subscription.url(),
                    new LinkedHashSet<>(fromSubscription.keySet()), selected));
            fetched.putAll(fromSubscription);
        }
        synchronized (COMMIT_LOCK) {
            requireActive();
            // Re-read under the lock so imports committed during network I/O are retained.
            Map<String, Source> updated = savedSources();
            List<Subscription> latest = subscriptions();
            if (!latest.equals(registered))
                throw new IllegalStateException("Subscriptions changed during update; retry");
            Set<String> oldIds = new LinkedHashSet<>();
            latest.forEach(subscription -> oldIds.addAll(subscription.sourceIds()));
            oldIds.forEach(updated::remove);
            updated.putAll(fetched);
            List<Subscription> revised = new ArrayList<>();
            for (Subscription subscription : latest)
                revised.add(revisedByUrl.get(subscription.url()));
            save(updated, revised);
            return new ImportResult(new ArrayList<>(updated.values()), List.of(), diagnostics);
        }
    }
    private ImportResult commit(Map<String, Source> incoming, List<Diagnostic> diagnostics, Subscription replacement) throws Exception {
        synchronized (COMMIT_LOCK) {
            requireActive();
            Map<String, Source> updated = savedSources();
            List<Subscription> feeds = new ArrayList<>(subscriptions());
            if (replacement != null) {
                if (feeds.stream().anyMatch(feed -> feed.url().equals(replacement.url()))
                        && diagnostics.stream().anyMatch(item -> item.severity() == Severity.ERROR))
                    throw new IllegalArgumentException("Updated source list has invalid entries: " + replacement.url());
                // Re-importing the same feed replaces its previous definitions, including removed IDs.
                // An ID also supplied by another feed stays until that feed is refreshed.
                Set<String> otherOwned = new LinkedHashSet<>();
                for (Subscription feed : feeds) if (!feed.url().equals(replacement.url()))
                    otherOwned.addAll(feed.sourceIds());
                for (Subscription previous : feeds) if (previous.url().equals(replacement.url()))
                    previous.sourceIds().stream().filter(id -> !otherOwned.contains(id)).forEach(updated::remove);
                feeds.removeIf(subscription -> subscription.url().equals(replacement.url()));
                feeds.add(replacement);
            }
            updated.putAll(incoming);
            save(updated, feeds);
            return new ImportResult(new ArrayList<>(updated.values()), List.of(), diagnostics);
        }
    }
    private Map<String, Source> savedSources() {
        Map<String, Source> result = new LinkedHashMap<>();
        for (Source source : all()) result.put(source.id(), source);
        return result;
    }
    private void save(Map<String, Source> sources, List<Subscription> feeds) {
        JsonArray sourceArray = new JsonArray();
        sources.values().forEach(source -> sourceArray.add(source.json()));
        JsonArray feedArray = new JsonArray();
        for (Subscription feed : feeds) {
            JsonObject entry = new JsonObject();
            entry.addProperty("url", feed.url());
            JsonArray ids = new JsonArray();
            feed.sourceIds().forEach(ids::add);
            entry.add("sourceIds", ids);
            if (feed.directory()) {
                JsonArray selected = new JsonArray();
                for (SelectedCollection collection : feed.collections()) {
                    JsonObject choice = new JsonObject();
                    choice.addProperty("name", collection.name());
                    choice.addProperty("url", collection.url());
                    selected.add(choice);
                }
                entry.add("collections", selected);
            }
            feedArray.add(entry);
        }
        if (!storage.write(sourceArray.toString(), feedArray.toString()))
            throw new IllegalStateException("Cannot save imported sources and subscriptions");
    }
    private Parsed parse(String json, Transport transport) throws Exception {
        requireActive();
        Map<String, Source> incoming = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        importRecursive(json, transport, incoming, diagnostics, 0);
        return new Parsed(incoming, diagnostics);
    }
    private record Parsed(Map<String, Source> sources, List<Diagnostic> diagnostics) {}
    private static void requireCompleteRefresh(Parsed parsed, String url) {
        // Refresh replaces old feed ownership. A partly parsed response would otherwise
        // silently remove previously saved sources, so keep the prior snapshot intact.
        if (parsed.diagnostics.stream().anyMatch(item -> item.severity() == Severity.ERROR))
            throw new IllegalArgumentException("Source list has invalid entries: " + url);
    }
    private void importRecursive(String text, Transport transport, Map<String, Source> updated, List<Diagnostic> diagnostics, int depth) throws Exception {
        if (depth > 3) throw new IllegalArgumentException("sourceUrls nesting too deep");
        requireActive();
        ImportResult parsed = SourceImporter.parse(text);
        diagnostics.addAll(parsed.diagnostics());
        for (Source source : parsed.sources()) updated.put(source.id(), source);
        for (String url : parsed.sourceUrls()) {
            requireActive();
            importRecursive(fetchSourceList(url, transport), transport, updated, diagnostics, depth + 1);
        }
    }
    private static String fetchSourceList(String url, Transport transport) throws Exception {
        FetchResponse response = transport.fetch(new FetchRequest(url));
        if (response == null || response.status() < 200 || response.status() >= 300)
            throw new IllegalStateException("Source list request failed with HTTP "
                    + (response == null ? "no response" : response.status()));
        return response.body();
    }
    private static void requireActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source import cancelled");
    }
}
