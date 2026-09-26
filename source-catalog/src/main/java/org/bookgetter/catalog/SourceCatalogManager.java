package org.bookgetter.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private SourceCatalogManager() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: update|verify|list <catalog-directory>");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "update" -> update(directory);
            case "verify" -> verify(directory);
            case "list" -> list(directory);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void update(Path directory) throws Exception {
        JsonArray feeds = JsonParser.parseString(Files.readString(directory.resolve("feeds.json"))).getAsJsonArray();
        if (feeds.isEmpty()) throw new IllegalArgumentException("No feeds configured");
        Path snapshots = directory.resolve("snapshots");
        Files.createDirectories(snapshots);
        String snapshotName = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path staging = snapshots.resolve(".staging-" + snapshotName);
        Files.createDirectories(staging.resolve("raw"));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        JsonArray files = new JsonArray();
        JsonArray all = new JsonArray();
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement entry : feeds) {
            JsonObject feed = entry.getAsJsonObject();
            String file = feed.get("file").getAsString();
            String url = feed.get("url").getAsString();
            if (!file.matches("[a-zA-Z0-9._-]+\\.json") || !names.add(file))
                throw new IllegalArgumentException("Invalid or duplicate feed file name: " + file);
            if (!url.startsWith("https://")) throw new IllegalArgumentException("Expected HTTPS feed: " + url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "BookGetterCatalog/1.0 (manual update)").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
            byte[] bytes = response.body();
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonArray entries = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            if (!root.isJsonArray()) entries.add(root);
            if (entries.isEmpty()) throw new IllegalArgumentException("Empty source file: " + file);
            for (JsonElement source : entries) {
                sourceId(source);
                all.add(source.deepCopy());
            }
            Files.write(staging.resolve("raw").resolve(file), bytes);
            JsonObject item = new JsonObject();
            item.addProperty("file", file);
            item.addProperty("url", url);
            item.addProperty("sha256", sha256(bytes));
            item.addProperty("entries", entries.size());
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
        verifySnapshot(staging);
        Path finalSnapshot = snapshots.resolve(snapshotName);
        move(staging, finalSnapshot);
        Path pointerTemp = directory.resolve(".CURRENT-" + UUID.randomUUID());
        Files.writeString(pointerTemp, snapshotName + "\n");
        move(pointerTemp, directory.resolve("CURRENT"));
        System.out.println("Current snapshot: " + snapshotName + "; unique=" + merged.unique().size()
                + "; duplicates=" + merged.duplicates().size());
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
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equals(file.get("sha256").getAsString()))
                throw new IllegalStateException("Raw hash mismatch: " + path);
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonArray entries = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            if (!root.isJsonArray()) entries.add(root);
            if (entries.size() != file.get("entries").getAsInt()) throw new IllegalStateException("Entry count changed: " + path);
            entries.forEach(all::add);
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
            int count = file.get("entries").getAsInt();
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

    private static String sourceId(JsonElement entry) {
        if (!entry.isJsonObject() || !entry.getAsJsonObject().has("bookSourceUrl"))
            throw new IllegalArgumentException("Source lacks bookSourceUrl");
        String id = entry.getAsJsonObject().get("bookSourceUrl").getAsString().trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Empty bookSourceUrl");
        normalizedId(id);
        return id;
    }

    static String normalizedId(String raw) {
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

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record Merged(JsonArray unique, JsonArray sources, JsonArray duplicates) {}
}
