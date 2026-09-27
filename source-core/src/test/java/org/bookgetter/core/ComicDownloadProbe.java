package org.bookgetter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;

/** Manual, resumable image-book probe; content and image URLs come from SourceEngine. */
public final class ComicDownloadProbe {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String USER_AGENT = "BookGetterComicProbe/1.0 (manual verification)";
    private ComicDownloadProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6 && args.length != 7) throw new IllegalArgumentException(
                "catalog output sourceFilter directBookUrl maxChapters delayMs [parallelImages]");
        Path catalog = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String filter = args[2];
        String bookUrl = args[3];
        int maxChapters = Integer.parseInt(args[4]);
        long delayMillis = Long.parseLong(args[5]);
        int parallelImages = args.length == 7 ? Integer.parseInt(args[6]) : 1;
        if (!ResourceUrls.isHttp(bookUrl) || maxChapters < 1 || delayMillis < 500
                || parallelImages < 1 || parallelImages > 4)
            throw new IllegalArgumentException("HTTP(S) book URL, positive chapter limit, >=500 ms delay and 1-4 workers required");
        String snapshot = Files.readString(catalog.resolve("CURRENT")).trim();
        Path unique = catalog.resolve("snapshots").resolve(snapshot).resolve("unique.json");
        Source source = SourceImporter.parse(Files.readString(unique)).sources().stream()
                .filter(item -> item.name().contains(filter) || item.id().contains(filter))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No source: " + filter));
        if (source.kind() != Kind.IMAGE) throw new IllegalArgumentException("Image source required");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(12)).build();
        RateLimiter limiter = new RateLimiter(delayMillis);
        ExecutorService workers = Executors.newFixedThreadPool(parallelImages);
        Transport transport = request -> {
            limiter.await();
            return LiveSourceProbe.fetch(client, request);
        };
        SourceEngine engine = new SourceEngine(transport);
        BookHit hit = new BookHit(source.id(), bookUrl, bookUrl, "", "", "", Kind.IMAGE);
        BookDetail detail = engine.detail(source, hit);
        List<Chapter> chapters = engine.chapters(source, detail).stream().filter(chapter -> !chapter.volume()).toList();
        if (chapters.isEmpty() || chapters.size() > maxChapters)
            throw new IllegalStateException("Unexpected chapter count: " + chapters.size());
        if (chapters.stream().map(Chapter::url).distinct().count() != chapters.size())
            throw new IllegalStateException("Catalog contains duplicate chapter URLs");
        Files.createDirectories(output);
        Map<String, JsonObject> overrides = loadOverrides(output);
        System.out.println("COMIC\tsnapshot=" + snapshot + "\tsource=" + source.id()
                + "\ttitle=" + detail.hit().name() + "\tchapters=" + chapters.size());
        int completed = 0;
        int failures = 0;
        long images = 0;
        List<JsonObject> chapterRecords = new ArrayList<>();
        try { for (Chapter chapter : chapters) { try {
            Path chapterDir = output.resolve("chapters").resolve(String.format("%04d-%s", chapter.index(),
                    sha256(chapter.url().getBytes(StandardCharsets.UTF_8)).substring(0, 16)));
            Files.createDirectories(chapterDir);
            Path chapterManifest = chapterDir.resolve("manifest.json");
            JsonObject record;
            if (verified(chapterManifest, chapterDir.resolve("chapter.cbz"), chapter.url())) {
                record = JsonParser.parseString(Files.readString(chapterManifest)).getAsJsonObject();
            } else {
                List<Resource> resources = engine.content(source, detail, chapter).resources();
                if (resources.isEmpty()) throw new IllegalStateException("No pages: " + chapter.url());
                int totalPages = resources.size();
                // A recorded alternate edition may add end pages absent from the primary
                // source. Extra pages must be contiguous and have explicit local overrides.
                while (overrides.containsKey(chapter.url() + "#" + (totalPages + 1))) totalPages++;
                if (totalPages > resources.size() + 100)
                    throw new IllegalStateException("Too many extra pages at " + chapter.url());
                List<Future<?>> pending = new ArrayList<>();
                for (int page = 0; page < totalPages; page++) {
                    Resource resource = page < resources.size() ? resources.get(page) : null;
                    if (resource != null && !ResourceUrls.isHttp(resource.url()))
                        throw new IllegalStateException("Non-HTTP image URL at chapter " + chapter.index());
                    Path image = chapterDir.resolve(String.format("%04d.img", page + 1));
                    if (Files.isRegularFile(image) && ImageSignatures.extension(Files.readAllBytes(image)) != null) continue;
                    JsonObject override = overrides.get(chapter.url() + "#" + (page + 1));
                    if (resource == null && override == null)
                        throw new IllegalStateException("Extra page lacks an override");
                    pending.add(workers.submit(() -> {
                        byte[] bytes = override == null ? image(client, limiter, resource.url(), chapter.url())
                                : overrideBytes(output, override);
                        Path temp = image.resolveSibling(image.getFileName() + ".part");
                        Files.write(temp, bytes);
                        Files.move(temp, image, StandardCopyOption.REPLACE_EXISTING);
                        return null;
                    }));
                }
                // Wait for every page even when one fails, so successful pages remain cached
                // and later retries only request the missing files.
                Exception firstFailure = null;
                for (Future<?> future : pending) {
                    try { future.get(); }
                    catch (Exception failure) { if (firstFailure == null) firstFailure = failure; }
                }
                if (firstFailure != null) throw firstFailure;
                Path cbz = chapterDir.resolve("chapter.cbz");
                Path temp = chapterDir.resolve("chapter.cbz.part");
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                    for (int page = 0; page < totalPages; page++) {
                        byte[] bytes = Files.readAllBytes(chapterDir.resolve(String.format("%04d.img", page + 1)));
                        String extension = ImageSignatures.extension(bytes);
                        if (extension == null) throw new IOException("Invalid cached image at page " + (page + 1));
                        zip.putNextEntry(new ZipEntry(String.format("%04d.%s", page + 1, extension)));
                        zip.write(bytes);
                        zip.closeEntry();
                    }
                }
                Files.move(temp, cbz, StandardCopyOption.REPLACE_EXISTING);
                record = new JsonObject();
                record.addProperty("sourceId", source.id());
                record.addProperty("bookUrl", bookUrl);
                record.addProperty("chapterUrl", chapter.url());
                record.addProperty("title", chapter.title());
                record.addProperty("images", totalPages);
                if (totalPages != resources.size()) record.addProperty("sourcePageCount", resources.size());
                JsonArray replacements = new JsonArray();
                for (int page = 0; page < totalPages; page++) {
                    JsonObject override = overrides.get(chapter.url() + "#" + (page + 1));
                    if (override == null) continue;
                    byte[] actual = Files.readAllBytes(chapterDir.resolve(String.format("%04d.img", page + 1)));
                    if (!sha256(actual).equals(override.get("sha256").getAsString())) continue;
                    JsonObject replacement = new JsonObject();
                    replacement.addProperty("page", page + 1);
                    replacement.addProperty("sourceUrl", override.get("sourceUrl").getAsString());
                    if (page < resources.size()) replacement.addProperty("originalUrl", resources.get(page).url());
                    replacement.addProperty("sha256", override.get("sha256").getAsString());
                    replacements.add(replacement);
                }
                if (!replacements.isEmpty()) record.add("replacements", replacements);
                record.addProperty("bytes", Files.size(cbz));
                record.addProperty("sha256", sha256(Files.readAllBytes(cbz)));
                record.addProperty("completedAt", Instant.now().toString());
                Path manifestTemp = chapterDir.resolve("manifest.json.part");
                Files.writeString(manifestTemp, GSON.toJson(record) + "\n");
                Files.move(manifestTemp, chapterManifest, StandardCopyOption.REPLACE_EXISTING);
                if (!verified(chapterManifest, cbz, chapter.url()))
                    throw new IOException("Chapter archive failed independent verification");
                System.out.println("CHAPTER\t" + (chapter.index() + 1) + "/" + chapters.size()
                        + "\timages=" + totalPages + "\tbytes=" + Files.size(cbz));
            }
            completed++;
            images += record.get("images").getAsInt();
            chapterRecords.add(record);
        } catch (Exception error) {
            // A missing upstream page must not prevent checking later chapters. No chapter
            // manifest is written unless every image is present and the archive verifies.
            failures++;
            System.out.println("CHAPTER_ERROR\t" + (chapter.index() + 1) + "/" + chapters.size()
                    + "\t" + chapter.title() + "\t" + error.getMessage());
        }}} finally { workers.shutdownNow(); }
        if (failures > 0) throw new IllegalStateException("Incomplete comic: " + completed
                + "/" + chapters.size() + " chapters; " + failures + " failed. Cached chapters can be resumed.");
        JsonObject bookManifest = new JsonObject();
        bookManifest.addProperty("sourceId", source.id());
        bookManifest.addProperty("sourceSnapshot", snapshot);
        bookManifest.addProperty("bookUrl", bookUrl);
        bookManifest.addProperty("title", detail.hit().name());
        bookManifest.addProperty("chapters", completed);
        bookManifest.addProperty("images", images);
        bookManifest.add("chapterManifests", GSON.toJsonTree(chapterRecords));
        Path temporary = output.resolve("manifest.json.part");
        Files.writeString(temporary, GSON.toJson(bookManifest) + "\n");
        Files.move(temporary, output.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("COMPLETE\tchapters=" + completed + "\timages=" + images);
    }

    private static byte[] image(HttpClient client, RateLimiter limiter, String url, String referer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT).header("Referer", referer).GET().build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.await();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200 && ImageSignatures.extension(response.body()) != null)
                    return response.body();
                if (response.statusCode() != 429 && response.statusCode() < 500)
                    throw new IOException("Invalid image response HTTP " + response.statusCode() + " at "
                            + ResponseSignals.urlForLog(response.uri().toString()));
                if (attempt == 3) throw new IOException("Image HTTP " + response.statusCode() + " after retries at "
                        + ResponseSignals.urlForLog(response.uri().toString()));
            } catch (IOException transientFailure) {
                if (attempt == 3 || String.valueOf(transientFailure.getMessage()).startsWith("Invalid image response"))
                    throw transientFailure;
            }
            Thread.sleep(attempt * 1500L);
        }
        throw new IllegalStateException("Unreachable retry path");
    }

    private static Map<String, JsonObject> loadOverrides(Path output) throws Exception {
        Path file = output.resolve("overrides.json");
        if (!Files.isRegularFile(file)) return Map.of();
        Map<String, JsonObject> overrides = new HashMap<>();
        for (var entry : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            JsonObject item = entry.getAsJsonObject();
            String key = item.get("chapterUrl").getAsString() + "#" + item.get("page").getAsInt();
            if (!ResourceUrls.isHttp(item.get("chapterUrl").getAsString())
                    || !ResourceUrls.isHttp(item.get("sourceUrl").getAsString())
                    || item.get("page").getAsInt() < 1 || overrides.putIfAbsent(key, item) != null)
                throw new IllegalArgumentException("Invalid or duplicate comic override: " + key);
            overrideBytes(output, item);
        }
        return overrides;
    }

    private static byte[] overrideBytes(Path output, JsonObject override) throws Exception {
        Path root = output.toAbsolutePath().normalize();
        Path file = root.resolve(override.get("file").getAsString()).normalize();
        if (!file.startsWith(root)) throw new IOException("Comic override escapes output directory");
        byte[] bytes = Files.readAllBytes(file);
        if (ImageSignatures.extension(bytes) == null
                || !sha256(bytes).equals(override.get("sha256").getAsString()))
            throw new IOException("Invalid comic override: " + file.getFileName());
        return bytes;
    }

    private static boolean verified(Path manifestFile, Path cbz, String chapterUrl) {
        try {
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(cbz)) return false;
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
            if (!chapterUrl.equals(manifest.get("chapterUrl").getAsString())
                    || manifest.get("bytes").getAsLong() != Files.size(cbz)
                    || !manifest.get("sha256").getAsString().equals(sha256(Files.readAllBytes(cbz)))) return false;
            try (ZipFile zip = new ZipFile(cbz.toFile())) {
                if (zip.size() != manifest.get("images").getAsInt() || zip.size() == 0) return false;
                for (ZipEntry entry : java.util.Collections.list(zip.entries()))
                    if (ImageSignatures.extension(zip.getInputStream(entry).readAllBytes()) == null) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class RateLimiter {
        private final long delayMillis;
        private long nextRequest;
        private RateLimiter(long delayMillis) { this.delayMillis = delayMillis; }
        synchronized void await() throws InterruptedException {
            long wait = nextRequest - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            nextRequest = System.currentTimeMillis() + delayMillis;
        }
    }
}
