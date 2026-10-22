package org.bookgetter.app;

import android.content.Context;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.MiaogongziDirectoryAdapter;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.RssImporter;
import org.bookgetter.core.RssModels.RssSource;
import org.bookgetter.core.SourceImporter;
import org.bookgetter.core.Transport;

/** Resolve mixed imports before atomically committing independent book and RSS models. */
final class SourceImportCoordinator {
    record Result(ImportResult books, int rssCount, SourceStore.DirectoryPreview directory) {}
    private record Resolved(List<Source> books, List<RssSource> rss, List<Diagnostic> diagnostics, String url) {}
    private final MixedSourceStore store;
    SourceImportCoordinator(Context context) { this(new MixedSourceStore(context)); }
    SourceImportCoordinator(MixedSourceStore store) { this.store = store; }

    Result importText(String json, Transport transport) throws Exception {
        return commit(List.of(resolve(json, transport, new Budget(), null)), null);
    }
    Result importUrlOrPreview(String url, Transport transport) throws Exception {
        Budget budget = new Budget();
        String text = fetch(url, transport, budget);
        String directory = MiaogongziDirectoryAdapter.directoryPage(text);
        if (directory != null) return new Result(null, 0, new SourceStore.DirectoryPreview(url,
                MiaogongziDirectoryAdapter.discover(fetch(directory, transport, budget))));
        Resolved resolved = resolveCounted(text, transport, budget, url);
        if (resolved.books().isEmpty() && resolved.rss().isEmpty()
                && !registeredUrls().contains(url)) throw new IllegalArgumentException("Source list contains no usable sources");
        return commit(List.of(resolved), null);
    }
    Result importDirectorySelection(SourceStore.DirectoryPreview preview, List<String> names, Transport transport) throws Exception {
        return store.transaction((books, rss) -> {
            ImportResult imported = books.importDirectorySelection(preview, names, transport);
            return new Result(imported, rss.all().size(), null);
        });
    }
    Result updateSubscriptions(Transport transport) throws Exception {
        MixedSourceStore.Snapshot expected = store.snapshot();
        SourceStore books = new SourceStore(store.bookStorage());
        Map<String, SourceStore.Subscription> bookFeeds = new LinkedHashMap<>();
        books.subscriptions().forEach(feed -> bookFeeds.put(feed.url(), feed));
        LinkedHashSet<String> urls = new LinkedHashSet<>(bookFeeds.keySet());
        new RssStore(store.rssStorage()).subscriptions().forEach(feed -> urls.add(feed.url()));
        if (urls.isEmpty()) throw new IllegalStateException("No source URL subscriptions to update");
        Budget budget = new Budget();
        List<Resolved> pending = new ArrayList<>();
        for (String url : urls) {
            SourceStore.Subscription feed = bookFeeds.get(url);
            Resolved resolved;
            if (feed != null && feed.directory()) resolved = refreshDirectory(feed, transport, budget);
            else resolved = resolveCounted(fetch(url, transport, budget), transport, budget, url);
            requireComplete(resolved);
            pending.add(resolved);
        }
        return commit(pending, expected);
    }

    private Resolved refreshDirectory(SourceStore.Subscription feed, Transport transport, Budget budget) throws Exception {
        String page = MiaogongziDirectoryAdapter.directoryPage(fetch(feed.url(), transport, budget));
        if (page == null) throw new IllegalStateException("Directory configuration changed");
        var collections = MiaogongziDirectoryAdapter.discover(fetch(page, transport, budget));
        List<Source> books = new ArrayList<>(); List<RssSource> rss = new ArrayList<>(); List<Diagnostic> diagnostics = new ArrayList<>();
        for (SourceStore.SelectedCollection previous : feed.collections()) {
            var selected = collections.stream().filter(item -> item.name().equals(previous.name())).findFirst()
                    .orElseGet(() -> collections.stream().filter(item -> item.url().equals(previous.url())).findFirst().orElse(null));
            if (selected == null) throw new IllegalStateException("Selected directory collection disappeared");
            Resolved resolved = resolveCounted(fetch(selected.url(), transport, budget), transport, budget, feed.url());
            requireComplete(resolved);
            if (resolved.books().isEmpty() && resolved.rss().isEmpty()) throw new IllegalArgumentException("Selected collection contains no sources");
            books.addAll(resolved.books()); rss.addAll(resolved.rss()); diagnostics.addAll(resolved.diagnostics());
        }
        return new Resolved(books, rss, diagnostics, feed.url());
    }
    private Result commit(List<Resolved> pending, MixedSourceStore.Snapshot expected) throws Exception {
        return store.transaction(expected, (books, rss) -> {
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (Resolved resolved : pending) {
                diagnostics.addAll(resolved.diagnostics());
                books.importResolved(resolved.books(), resolved.diagnostics(), resolved.url());
                rss.importResolved(resolved.rss(), resolved.diagnostics(), resolved.url());
            }
            if ((long) books.all().size() + rss.all().size() > SourceStore.MAX_IMPORT_SOURCES)
                throw new IllegalArgumentException("Source store exceeds 10000 distinct model identities");
            return new Result(new ImportResult(books.all(), List.of(), diagnostics), rss.all().size(), null);
        });
    }
    private List<String> registeredUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        new SourceStore(store.bookStorage()).subscriptions().forEach(feed -> urls.add(feed.url()));
        new RssStore(store.rssStorage()).subscriptions().forEach(feed -> urls.add(feed.url()));
        return new ArrayList<>(urls);
    }
    private static void requireComplete(Resolved resolved) {
        if (resolved.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR))
            throw new IllegalArgumentException("Updated source list contains invalid entries");
    }
    private static Resolved resolve(String text, Transport transport, Budget budget, String url) throws Exception {
        budget.add(text); return resolveCounted(text, transport, budget, url);
    }
    private static Resolved resolveCounted(String text, Transport transport, Budget budget, String url) throws Exception {
        Map<String, Source> books = new LinkedHashMap<>(); Map<String, RssSource> rss = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        recursive(text, transport, budget, books, rss, diagnostics, 0);
        return new Resolved(new ArrayList<>(books.values()), new ArrayList<>(rss.values()), diagnostics, url);
    }
    private static void recursive(String text, Transport transport, Budget budget, Map<String, Source> books,
                                  Map<String, RssSource> rss, List<Diagnostic> diagnostics, int depth) throws Exception {
        active();
        if (depth > 3) throw new IllegalArgumentException("sourceUrls nesting too deep");
        JsonElement root = JsonParser.parseString(text);
        if (root.isJsonObject() && root.getAsJsonObject().has("sourceUrls")) {
            // Retain the existing URL validation contract for recursive containers.
            for (String url : SourceImporter.parse(text).sourceUrls()) {
                if (++budget.urls > 100) throw new IllegalArgumentException("Source list exceeds 100 nested URLs");
                recursive(fetch(url, transport, budget), transport, budget, books, rss, diagnostics, depth + 1);
            }
            return;
        }
        List<JsonElement> entries = new ArrayList<>();
        if (root.isJsonArray()) root.getAsJsonArray().forEach(entries::add); else entries.add(root);
        for (JsonElement entry : entries) {
            active();
            if (entry.isJsonObject() && entry.getAsJsonObject().has("sourceUrl") && !entry.getAsJsonObject().has("bookSourceUrl")) {
                var parsed = RssImporter.parse(entry.toString()); diagnostics.addAll(parsed.diagnostics());
                parsed.sources().forEach(source -> rss.put(source.id(), source));
            } else {
                var parsed = SourceImporter.parse(entry.toString()); diagnostics.addAll(parsed.diagnostics());
                parsed.sources().forEach(source -> books.put(source.id(), source));
            }
            if ((long) books.size() + rss.size() > SourceStore.MAX_IMPORT_SOURCES)
                throw new IllegalArgumentException("Source list exceeds 10000 distinct model identities");
        }
    }
    private static String fetch(String url, Transport transport, Budget budget) throws Exception {
        active();
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Source list URL must be HTTP(S)");
        FetchResponse response = transport.fetch(new FetchRequest(url));
        if (response == null || response.status() < 200 || response.status() >= 300)
            throw new IllegalStateException("Source list request failed");
        budget.add(response.body()); return response.body();
    }
    private static void active() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source import cancelled");
    }
    private static final class Budget {
        long bytes; int urls;
        void add(String text) {
            if (text == null || text.length() > SourceStore.MAX_SOURCE_BYTES) throw new IllegalArgumentException("Source list exceeds 16 MiB limit");
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            if (size > SourceStore.MAX_SOURCE_BYTES) throw new IllegalArgumentException("Source list exceeds 16 MiB limit");
            bytes += size;
            if (bytes > SourceStore.MAX_TOTAL_IMPORT_BYTES) throw new IllegalArgumentException("Source import exceeds 64 MiB total limit");
        }
    }
}
