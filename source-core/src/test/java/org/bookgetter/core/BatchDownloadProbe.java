package org.bookgetter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.ChapterContent;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Source;
import org.bookgetter.export.BookExporter;

/** Manual, resumable acceptance probe. Files remain in the Git-ignored downloads directory. */
public final class BatchDownloadProbe {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private BatchDownloadProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("catalog output sourceFilter target maxChapters maxPages delayMs");
        Path catalog = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String sourceFilter = args[2];
        int target = Integer.parseInt(args[3]);
        int maxChapters = Integer.parseInt(args[4]);
        int maxPages = Integer.parseInt(args[5]);
        long delayMillis = Long.parseLong(args[6]);
        if (target < 1 || target > 100 || maxChapters < 1 || maxPages < 1 || delayMillis < 1500)
            throw new IllegalArgumentException("Invalid limits; at least 1500 ms between HTTP requests is required");
        String snapshot = Files.readString(catalog.resolve("CURRENT")).trim();
        Path unique = catalog.resolve("snapshots").resolve(snapshot).resolve("unique.json");
        Source source = SourceImporter.parse(Files.readString(unique)).sources().stream()
                .filter(item -> item.id().contains(sourceFilter) || item.name().contains(sourceFilter))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No matching source: " + sourceFilter));
        if (source.kind() != Kind.TEXT) throw new IllegalArgumentException("Text book source required");
        Files.createDirectories(output.resolve("books"));
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(12)).build();
        final long[] nextRequest = {0};
        Transport transport = request -> {
            synchronized (nextRequest) {
                long wait = nextRequest[0] - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                nextRequest[0] = System.currentTimeMillis() + delayMillis;
            }
            return LiveSourceProbe.fetch(client, request);
        };
        SourceEngine engine = new SourceEngine(transport);
        int completed = countVerified(output.resolve("books"), source.id());
        System.out.println("BATCH\tsnapshot=" + snapshot + "\tsource=" + source.id()
                + "\tverifiedBefore=" + completed + "\ttarget=" + target);
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= maxPages && completed < target; page++) {
            List<BookHit> hits;
            try { hits = engine.search(source, "", page); }
            catch (Exception e) { log(output, "SEARCH_ERROR", "page=" + page, e); break; }
            System.out.println("PAGE\t" + page + "\thits=" + hits.size());
            if (hits.isEmpty()) break;
            for (BookHit hit : hits) {
                if (completed >= target) break;
                if (!seen.add(hit.url())) continue;
                Path bookDirectory = output.resolve("books").resolve(sha256(hit.url().getBytes(StandardCharsets.UTF_8)));
                if (verified(bookDirectory, source.id())) continue;
                try {
                    BookDetail detail = engine.detail(source, hit);
                    List<Chapter> chapters = engine.chapters(source, detail).stream()
                            .filter(chapter -> !chapter.volume()).toList();
                    if (chapters.isEmpty() || chapters.size() > maxChapters) {
                        System.out.println("SKIP\t" + hit.url() + "\tchapters=" + chapters.size());
                        continue;
                    }
                    Files.createDirectories(bookDirectory.resolve("chapters"));
                    List<ChapterContent> contents = new ArrayList<>();
                    for (Chapter chapter : chapters) {
                        Path chapterFile = bookDirectory.resolve("chapters")
                                .resolve(chapter.index() + "-" + sha256(chapter.url().getBytes(StandardCharsets.UTF_8)) + ".txt");
                        String text;
                        if (Files.isRegularFile(chapterFile) && Files.size(chapterFile) > 0) {
                            text = Files.readString(chapterFile);
                        } else {
                            ChapterContent content = engine.content(source, detail, chapter);
                            text = content.text();
                            if (text == null || text.isBlank()) throw new IllegalStateException("Blank chapter: " + chapter.url());
                            Path temporary = chapterFile.resolveSibling(chapterFile.getFileName() + ".part");
                            Files.writeString(temporary, text);
                            Files.move(temporary, chapterFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        contents.add(new ChapterContent(chapter, Kind.TEXT, text, List.of()));
                    }
                    Path temporary = bookDirectory.resolve("book.txt.part");
                    try (var stream = Files.newOutputStream(temporary)) {
                        new BookExporter().write(BookExporter.Format.TXT, detail, contents, stream);
                    }
                    Path finalBook = bookDirectory.resolve("book.txt");
                    Files.move(temporary, finalBook, StandardCopyOption.REPLACE_EXISTING);
                    JsonObject manifest = new JsonObject();
                    manifest.addProperty("sourceId", source.id());
                    manifest.addProperty("sourceSnapshot", snapshot);
                    manifest.addProperty("bookUrl", hit.url());
                    manifest.addProperty("title", detail.hit().name());
                    manifest.addProperty("chapterCount", contents.size());
                    manifest.addProperty("bytes", Files.size(finalBook));
                    manifest.addProperty("sha256", sha256(Files.readAllBytes(finalBook)));
                    manifest.addProperty("completedAt", Instant.now().toString());
                    Path manifestTemp = bookDirectory.resolve("manifest.json.part");
                    Files.writeString(manifestTemp, GSON.toJson(manifest) + "\n");
                    Files.move(manifestTemp, bookDirectory.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
                    completed++;
                    System.out.println("COMPLETE\t" + completed + "\t" + detail.hit().name()
                            + "\tchapters=" + contents.size() + "\tbytes=" + Files.size(finalBook));
                } catch (Exception e) { log(output, "BOOK_ERROR", hit.url(), e); }
            }
        }
        int verified = countVerified(output.resolve("books"), source.id());
        System.out.println("SUMMARY\tverified=" + verified + "\ttarget=" + target);
        if (verified < target) throw new IllegalStateException("Only " + verified + " verified books; target is " + target);
    }

    private static void log(Path output, String stage, String target, Exception error) throws Exception {
        String clean = String.valueOf(error.getMessage()).replaceAll("[\\r\\n\\t]", " ");
        String line = Instant.now() + "\t" + stage + "\t" + target + "\t" + clean + "\n";
        Files.writeString(output.resolve("failures.tsv"), line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println(stage + "\t" + target + "\t" + clean);
    }

    private static int countVerified(Path books, String sourceId) throws Exception {
        int count = 0;
        try (var paths = Files.list(books)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) if (verified(path, sourceId)) count++;
        }
        return count;
    }

    private static boolean verified(Path directory, String sourceId) {
        try {
            Path manifestFile = directory.resolve("manifest.json");
            Path bookFile = directory.resolve("book.txt");
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(bookFile)) return false;
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
            int chapterCount = manifest.get("chapterCount").getAsInt();
            Path chapters = directory.resolve("chapters");
            if (!Files.isDirectory(chapters)) return false;
            try (var files = Files.list(chapters)) {
                List<Path> chapterFiles = files.filter(path -> path.getFileName().toString().endsWith(".txt")).toList();
                if (chapterFiles.size() != chapterCount) return false;
                for (Path chapterFile : chapterFiles) if (Files.size(chapterFile) == 0) return false;
            }
            return sourceId.equals(manifest.get("sourceId").getAsString())
                    && chapterCount > 0
                    && directory.getFileName().toString().equals(sha256(manifest.get("bookUrl").getAsString().getBytes(StandardCharsets.UTF_8)))
                    && manifest.get("bytes").getAsLong() == Files.size(bookFile)
                    && manifest.get("sha256").getAsString().equals(sha256(Files.readAllBytes(bookFile)));
        } catch (Exception ignored) { return false; }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
