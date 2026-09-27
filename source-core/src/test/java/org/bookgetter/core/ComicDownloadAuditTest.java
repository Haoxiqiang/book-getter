package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComicDownloadAuditTest {
    @TempDir Path directory;

    @Test void auditsCompleteArchiveAndRejectsTampering() throws Exception {
        String chapterUrl = "https://example.org/comic/1";
        String hash = sha256(chapterUrl.getBytes(StandardCharsets.UTF_8));
        Path chapter = directory.resolve("chapters/0000-" + hash.substring(0, 16));
        Files.createDirectories(chapter);
        Path cbz = chapter.resolve("chapter.cbz");
        byte[] image = new byte[32];
        image[0] = (byte) 0xff;
        image[1] = (byte) 0xd8;
        image[2] = (byte) 0xff;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            zip.putNextEntry(new ZipEntry("0001.jpg"));
            zip.write(image);
            zip.closeEntry();
        }
        JsonObject record = new JsonObject();
        record.addProperty("sourceId", "https://example.org");
        record.addProperty("bookUrl", "https://example.org/comic");
        record.addProperty("chapterUrl", chapterUrl);
        record.addProperty("images", 1);
        record.addProperty("bytes", Files.size(cbz));
        record.addProperty("sha256", sha256(Files.readAllBytes(cbz)));
        JsonObject replacement = new JsonObject();
        replacement.addProperty("page", 1);
        replacement.addProperty("sourceUrl", "https://other.example.org/chapter/1");
        replacement.addProperty("originalUrl", "https://example.org/image/1.jpg");
        replacement.addProperty("sha256", sha256(image));
        record.add("replacements", new com.google.gson.JsonArray());
        record.getAsJsonArray("replacements").add(replacement);
        Files.writeString(chapter.resolve("manifest.json"), record.toString());
        JsonObject book = new JsonObject();
        book.addProperty("sourceId", "https://example.org");
        book.addProperty("bookUrl", "https://example.org/comic");
        book.addProperty("chapters", 1);
        book.addProperty("images", 1);
        book.add("chapterManifests", new com.google.gson.JsonArray());
        book.getAsJsonArray("chapterManifests").add(record);
        Files.writeString(directory.resolve("manifest.json"), book.toString());

        assertEquals(1, ComicDownloadAudit.audit(directory, "https://example.org", 1).images());
        replacement.addProperty("sha256", "bad");
        Files.writeString(chapter.resolve("manifest.json"), record.toString());
        Files.writeString(directory.resolve("manifest.json"), book.toString());
        assertThrows(IllegalStateException.class,
                () -> ComicDownloadAudit.audit(directory, "https://example.org", 1));
        replacement.addProperty("sha256", sha256(image));
        Files.writeString(chapter.resolve("manifest.json"), record.toString());
        Files.writeString(directory.resolve("manifest.json"), book.toString());
        Files.writeString(cbz, "corrupt");
        assertThrows(IllegalStateException.class,
                () -> ComicDownloadAudit.audit(directory, "https://example.org", 1));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
