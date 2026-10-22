package org.bookgetter.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Download, deduplicate and verify a local-only snapshot of upstream Legado JSON sources. */
public final class SourceCatalogManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final int MAX_FEED_BYTES = 16 * 1024 * 1024;
    static final long MAX_UPDATE_BYTES = 64L * 1024 * 1024;
    private SourceCatalogManager() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: update|verify|list|check-config <catalog-directory>");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "update" -> update(directory);
            case "verify" -> verify(directory);
            case "list" -> list(directory);
            case "check-config" -> checkConfig(directory);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void checkConfig(Path directory) throws IOException {
        JsonArray feeds = JsonParser.parseString(Files.readString(directory.resolve("feeds.json"))).getAsJsonArray();
        if (feeds.isEmpty()) throw new IllegalArgumentException("No feeds configured");
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement entry : feeds) {
            JsonObject feed = entry.getAsJsonObject();
            String file = feed.get("file").getAsString();
            String url = feed.get("url").getAsString();
            if (!file.matches("[a-zA-Z0-9._-]+\\.json") || !names.add(file))
                throw new IllegalArgumentException("Invalid or duplicate feed file name: " + file);
            validateFeedUrl(feed);
            if (feed.has("model") && !"rss".equals(feed.get("model").getAsString()))
                throw new IllegalArgumentException("Unknown source model: " + feed.get("model"));
        }
        System.out.println("Checked " + feeds.size() + " feed definitions");
    }

    static URI validateFeedUrl(JsonObject feed) {
        String url = feed.get("url").getAsString();
        boolean httpAllowed = feed.has("allowHttp") && feed.get("allowHttp").getAsBoolean();
        URI address = URI.create(url);
        if (!("https".equalsIgnoreCase(address.getScheme())
                || httpAllowed && "http".equalsIgnoreCase(address.getScheme())) || address.getHost() == null)
            throw new IllegalArgumentException("Expected HTTPS feed or explicit allowHttp: " + url);
        return address;
    }

    private static void update(Path directory) throws Exception {
        checkConfig(directory);
        JsonArray feeds = JsonParser.parseString(Files.readString(directory.resolve("feeds.json"))).getAsJsonArray();
        if (feeds.isEmpty()) throw new IllegalArgumentException("No feeds configured");
        Path snapshots = directory.resolve("snapshots");
        Files.createDirectories(snapshots);
        String snapshotName = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path staging = snapshots.resolve(".staging-" + snapshotName);
        Path pointerTemp = directory.resolve(".CURRENT-" + UUID.randomUUID());
        Path finalSnapshot = snapshots.resolve(snapshotName);
        boolean snapshotMoved = false;
        try {
            Files.createDirectories(staging.resolve("raw"));
            Merged merged = buildSnapshot(feeds, staging);
            verifySnapshot(staging);
            Files.move(staging, finalSnapshot, StandardCopyOption.ATOMIC_MOVE);
            snapshotMoved = true;
            Files.writeString(pointerTemp, snapshotName + "\n");
            Files.move(pointerTemp, directory.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            snapshotMoved = false;
            System.out.println("Current snapshot: " + snapshotName + "; unique=" + merged.unique().size()
                    + "; duplicates=" + merged.duplicates().size());
        } catch (Exception | Error failure) {
            IOException cleanup = cleanupFailedUpdate(staging, pointerTemp,
                    snapshotMoved ? finalSnapshot : null);
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    private static Merged buildSnapshot(JsonArray feeds, Path staging) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        JsonArray files = new JsonArray();
        JsonArray all = new JsonArray();
        Set<String> names = new LinkedHashSet<>();
        long downloadedBytes = 0;
        for (JsonElement entry : feeds) {
            JsonObject feed = entry.getAsJsonObject();
            String file = feed.get("file").getAsString();
            String url = feed.get("url").getAsString();
            if (!file.matches("[a-zA-Z0-9._-]+\\.json") || !names.add(file))
                throw new IllegalArgumentException("Invalid or duplicate feed file name: " + file);
            URI address = validateFeedUrl(feed);
            boolean rss = feed.has("model") && feed.get("model").getAsString().equals("rss");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "BookGetterCatalog/1.0 (manual update)").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
                bytes = readLimited(body, MAX_FEED_BYTES, file);
            }
            downloadedBytes += bytes.length;
            if (downloadedBytes > MAX_UPDATE_BYTES)
                throw new IOException("Catalog update exceeds total 64 MiB download limit");
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonArray entries = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            if (!root.isJsonArray()) entries.add(root);
            if (entries.isEmpty()) throw new IllegalArgumentException("Empty source file: " + file);
            for (JsonElement source : entries) {
                if (rss) rssSourceId(source);
                else {
                    sourceId(source);
                    all.add(source.deepCopy());
                }
            }
            Files.write(staging.resolve("raw").resolve(file), bytes);
            JsonObject item = new JsonObject();
            item.addProperty("file", file);
            item.addProperty("url", url);
            item.addProperty("sha256", sha256(bytes));
            item.addProperty("entries", entries.size());
            item.addProperty("model", rss ? "rss" : "book");
            item.addProperty("importedEntries", rss ? 0 : entries.size());
            files.add(item);
            System.out.println("Downloaded " + file + ": " + entries.size() + " entries");
        }
        Merged merged = merge(all, files);
        JsonObject index = new JsonObject();
        index.addProperty("generatedAt", Instant.now().toString());
        index.addProperty("rawEntryCount", all.size());
        index.addProperty("uniqueCount", merged.unique().size());
        index.add("files", files);
        index.add("sources", merged.sources());
        index.add("duplicates", merged.duplicates());
        Files.writeString(staging.resolve("unique.json"), GSON.toJson(merged.unique()) + "\n");
        index.addProperty("uniqueSha256", sha256(Files.readAllBytes(staging.resolve("unique.json"))));
        Files.writeString(staging.resolve("index.json"), GSON.toJson(index) + "\n");
        return merged;
    }

    private static void verify(Path directory) throws Exception {
        Path snapshot = current(directory);
        verifySnapshot(snapshot);
        System.out.println("Verified " + snapshot.getFileName());
    }

    private static void verifySnapshot(Path snapshot) throws Exception {
        JsonObject index = JsonParser.parseString(Files.readString(snapshot.resolve("index.json"))).getAsJsonObject();
        JsonArray all = new JsonArray();
        for (JsonElement entry : index.getAsJsonArray("files")) {
            JsonObject file = entry.getAsJsonObject();
            String name = file.get("file").getAsString();
            if (!name.matches("[a-zA-Z0-9._-]+\\.json")) throw new IllegalStateException("Invalid indexed file name: " + name);
            Path path = snapshot.resolve("raw").resolve(name);
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = readLimited(input, MAX_FEED_BYTES, name);
            }
            if (!sha256(bytes).equals(file.get("sha256").getAsString()))
                throw new IllegalStateException("Raw hash mismatch: " + path);
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonArray entries = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            if (!root.isJsonArray()) entries.add(root);
            if (entries.size() != file.get("entries").getAsInt()) throw new IllegalStateException("Entry count changed: " + path);
            boolean rss = file.has("model") && file.get("model").getAsString().equals("rss");
            if (rss) entries.forEach(SourceCatalogManager::rssSourceId);
            else entries.forEach(sourceEntry -> { sourceId(sourceEntry); all.add(sourceEntry); });
            int importedCount = file.has("importedEntries") ? file.get("importedEntries").getAsInt() : entries.size();
            if (importedCount != (rss ? 0 : entries.size())) throw new IllegalStateException("Imported entry count changed: " + path);
        }
        Merged merged = merge(all, index.getAsJsonArray("files"));
        JsonArray unique = merged.unique();
        Path output = snapshot.resolve("unique.json");
        if (!sha256(Files.readAllBytes(output)).equals(index.get("uniqueSha256").getAsString())
                || !JsonParser.parseString(Files.readString(output)).equals(unique)
                || unique.size() != index.get("uniqueCount").getAsInt()
                || all.size() != index.get("rawEntryCount").getAsInt()
                || !merged.sources().equals(index.getAsJsonArray("sources"))
                || !merged.duplicates().equals(index.getAsJsonArray("duplicates")))
            throw new IllegalStateException("Deduplicated snapshot mismatch: " + snapshot);
    }

    private static void list(Path directory) throws Exception {
        JsonObject index = JsonParser.parseString(Files.readString(current(directory).resolve("index.json"))).getAsJsonObject();
        System.out.println("Unique sources: " + index.get("uniqueCount").getAsInt());
        for (JsonElement entry : index.getAsJsonArray("sources")) {
            JsonObject source = entry.getAsJsonObject();
            System.out.println(source.get("name").getAsString() + "\t" + source.get("bookSourceUrl").getAsString()
                    + "\t" + source.get("file").getAsString());
        }
    }

    private static Path current(Path directory) throws IOException {
        String name = Files.readString(directory.resolve("CURRENT")).trim();
        if (!name.matches("[0-9]{8}-[0-9]{6}-[a-f0-9]{8}")) throw new IllegalStateException("Invalid CURRENT pointer");
        return directory.resolve("snapshots").resolve(name);
    }

    static Merged merge(JsonArray all, JsonArray files) {
        JsonArray unique = new JsonArray();
        JsonArray sources = new JsonArray();
        JsonArray duplicates = new JsonArray();
        Map<String, String> firstFileById = new LinkedHashMap<>();
        int position = 0;
        for (JsonElement fileEntry : files) {
            JsonObject file = fileEntry.getAsJsonObject();
            int count = file.has("importedEntries") ? file.get("importedEntries").getAsInt() : file.get("entries").getAsInt();
            for (int i = 0; i < count; i++) {
                JsonElement entry = all.get(position++);
                String id = sourceId(entry);
                String key = normalizedId(id);
                String firstFile = firstFileById.putIfAbsent(key, file.get("file").getAsString());
                if (firstFile == null) {
                    unique.add(entry.deepCopy());
                    JsonObject summary = new JsonObject();
                    summary.addProperty("name", entry.getAsJsonObject().has("bookSourceName")
                            ? entry.getAsJsonObject().get("bookSourceName").getAsString() : id);
                    summary.addProperty("bookSourceUrl", id);
                    summary.addProperty("normalizedUrl", key);
                    summary.addProperty("file", file.get("file").getAsString());
                    sources.add(summary);
                } else {
                    JsonObject duplicate = new JsonObject();
                    duplicate.addProperty("bookSourceUrl", id);
                    duplicate.addProperty("file", file.get("file").getAsString());
                    duplicate.addProperty("keptFrom", firstFile);
                    duplicates.add(duplicate);
                }
            }
        }
        if (position != all.size()) throw new IllegalArgumentException("File entry counts do not match sources");
        return new Merged(unique, sources, duplicates);
    }

    static String rssSourceId(JsonElement entry) {
        if (!entry.isJsonObject() || !entry.getAsJsonObject().has("sourceUrl"))
            throw new IllegalArgumentException("RSS source lacks sourceUrl");
        String id = entry.getAsJsonObject().get("sourceUrl").getAsString().trim();
        normalizedHttpId(id);
        return id;
    }

    private static String sourceId(JsonElement entry) {
        if (!entry.isJsonObject() || !entry.getAsJsonObject().has("bookSourceUrl"))
            throw new IllegalArgumentException("Source lacks bookSourceUrl");
        String id = entry.getAsJsonObject().get("bookSourceUrl").getAsString().trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Empty bookSourceUrl");
        normalizedId(id);
        return id;
    }

    static String normalizedId(String raw) {
        String id = raw.trim();
        if (id.isEmpty() || id.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid bookSourceUrl: " + raw);
        // Legado identifies a BookSource by its key, which can be a descriptive name.
        // Keep those keys exact; only network URLs receive host/port normalization.
        if (!id.matches("(?i)^https?://.*")) {
            if (id.contains("://") || id.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
                throw new IllegalArgumentException("Invalid bookSourceUrl: " + raw);
            return "key:" + id;
        }
        return normalizedHttpId(id);
    }

    private static String normalizedHttpId(String raw) {
        URI uri = URI.create(raw.trim());
        if (uri.getHost() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")))
            throw new IllegalArgumentException("Invalid bookSourceUrl: " + raw);
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return scheme + "://" + host + ((port < 0 || port == 80 && scheme.equals("http")
                || port == 443 && scheme.equals("https")) ? "" : ":" + port) + path + query;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] readLimited(InputStream input, int limit, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (read > limit - output.size())
                throw new IOException("Catalog feed exceeds " + limit + " byte limit: " + name);
            output.write(chunk, 0, read);
        }
        return output.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static IOException cleanupFailedUpdate(Path staging, Path pointerTemp, Path unpublishedSnapshot) {
        IOException failure = null;
        try { deleteTree(staging); }
        catch (IOException error) { failure = error; }
        try { Files.deleteIfExists(pointerTemp); }
        catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (unpublishedSnapshot != null) try { deleteTree(unpublishedSnapshot); }
        catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        return failure;
    }

    record Merged(JsonArray unique, JsonArray sources, JsonArray duplicates) {}
}
