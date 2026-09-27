package org.bookgetter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Offline audit of the complete comic manifest; does not use the downloader's verifier. */
public final class ComicDownloadAudit {
    private ComicDownloadAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("directory sourceId expectedChapters");
        Audit result = audit(Path.of(args[0]), args[1], Integer.parseInt(args[2]));
        System.out.println("COMIC_AUDIT\tchapters=" + result.chapters + "\timages=" + result.images
                + "\tbytes=" + result.bytes);
    }

    static Audit audit(Path directory, String sourceId, int expectedChapters) throws Exception {
        JsonObject book = JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
        JsonArray records = book.getAsJsonArray("chapterManifests");
        if (expectedChapters < 1 || !sourceId.equals(book.get("sourceId").getAsString())
                || book.get("chapters").getAsInt() != expectedChapters || records.size() != expectedChapters)
            throw new IllegalStateException("Comic manifest count or source mismatch");
        long images = 0;
        long bytes = 0;
        Set<String> urls = new HashSet<>();
        for (int index = 0; index < records.size(); index++) {
            JsonObject record = records.get(index).getAsJsonObject();
            String url = record.get("chapterUrl").getAsString();
            if (!sourceId.equals(record.get("sourceId").getAsString())
                    || !book.get("bookUrl").getAsString().equals(record.get("bookUrl").getAsString())
                    || !urls.add(url)) throw new IllegalStateException("Chapter identity mismatch at " + index);
            String directoryName = String.format("%04d-%s", index, sha256(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16));
            Path chapterDirectory = directory.resolve("chapters").resolve(directoryName);
            JsonObject saved = JsonParser.parseString(Files.readString(chapterDirectory.resolve("manifest.json"))).getAsJsonObject();
            if (!saved.equals(record)) throw new IllegalStateException("Chapter manifest mismatch at " + index);
            Path cbz = chapterDirectory.resolve("chapter.cbz");
            long size = Files.size(cbz);
            if (record.get("bytes").getAsLong() != size || !record.get("sha256").getAsString().equals(sha256(Files.readAllBytes(cbz))))
                throw new IllegalStateException("Chapter archive hash mismatch at " + index);
            int expectedImages = record.get("images").getAsInt();
            int sourcePages = record.has("sourcePageCount") ? record.get("sourcePageCount").getAsInt() : expectedImages;
            if (sourcePages < 1 || sourcePages > expectedImages)
                throw new IllegalStateException("Invalid source page count at " + index);
            Map<Integer, JsonObject> replacements = new HashMap<>();
            if (record.has("replacements")) {
                for (var item : record.getAsJsonArray("replacements")) {
                    JsonObject replacement = item.getAsJsonObject();
                    int page = replacement.get("page").getAsInt();
                    if (page < 1 || page > expectedImages || replacements.putIfAbsent(page, replacement) != null
                            || !ResourceUrls.isHttp(replacement.get("sourceUrl").getAsString())
                            || (page <= sourcePages && (!replacement.has("originalUrl")
                                    || !ResourceUrls.isHttp(replacement.get("originalUrl").getAsString())))
                            || (page > sourcePages && replacement.has("originalUrl")))
                        throw new IllegalStateException("Invalid image replacement at " + index);
                }
            }
            for (int page = sourcePages + 1; page <= expectedImages; page++)
                if (!replacements.containsKey(page))
                    throw new IllegalStateException("Extra page lacks provenance at " + index + ":" + page);
            try (ZipFile zip = new ZipFile(cbz.toFile())) {
                if (expectedImages < 1 || zip.size() != expectedImages)
                    throw new IllegalStateException("Page count mismatch at " + index);
                for (int page = 1; page <= expectedImages; page++) {
                    ZipEntry entry = null;
                    for (String extension : new String[] {"jpg", "png", "gif", "webp", "avif"}) {
                        entry = zip.getEntry(String.format("%04d.%s", page, extension));
                        if (entry != null) break;
                    }
                    if (entry == null) throw new IllegalStateException("Missing page " + index + ":" + page);
                    byte[] header = new byte[32];
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (input.readNBytes(header, 0, header.length) < header.length
                                || ImageSignatures.extension(header) == null)
                            throw new IllegalStateException("Invalid image at " + index + ":" + page);
                        JsonObject replacement = replacements.get(page);
                        if (replacement != null) {
                            byte[] remainder = input.readAllBytes();
                            byte[] image = new byte[header.length + remainder.length];
                            System.arraycopy(header, 0, image, 0, header.length);
                            System.arraycopy(remainder, 0, image, header.length, remainder.length);
                            if (!sha256(image).equals(replacement.get("sha256").getAsString()))
                                throw new IllegalStateException("Replacement hash mismatch at " + index + ":" + page);
                        }
                    }
                }
            }
            images += expectedImages;
            bytes += size;
        }
        if (images != book.get("images").getAsLong()) throw new IllegalStateException("Total page count mismatch");
        return new Audit(expectedChapters, images, bytes);
    }

    private static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    record Audit(int chapters, long images, long bytes) {}
}
