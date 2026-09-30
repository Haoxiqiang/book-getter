package org.bookgetter.app;

import android.content.Context;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.ResourceUrls;

/** A successful chapter is written atomically and reused on the next capture attempt. */
final class ChapterStore {
    private final File directory;
    private final Gson gson = new Gson();
    ChapterStore(Context context) { this(new File(context.getFilesDir(), "chapter-cache")); }
    ChapterStore(File directory) { this.directory = directory; directory.mkdirs(); }
    ChapterContent get(Source source, BookDetail book, Chapter chapter, String nextChapterUrl) {
        if (book == null || book.hit() == null || book.hit().url() == null || chapter == null
                || !book.hit().url().equals(chapter.bookUrl())) return null;
        File file = file(source, book, chapter, nextChapterUrl);
        if (!file.isFile()) return null;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ChapterContent content = gson.fromJson(reader, ChapterContent.class);
            // A syntactically valid but incomplete cache entry must be fetched again.
            return valid(source, chapter, content) ? content : null;
        }
        catch (Exception ignored) { return null; }
    }
    void put(Source source, BookDetail book, ChapterContent content, String nextChapterUrl) throws Exception {
        if (book == null || book.hit() == null || book.hit().url() == null
                || content == null || content.chapter() == null
                || !book.hit().url().equals(content.chapter().bookUrl())
                || !valid(source, content.chapter(), content))
            throw new IllegalArgumentException("Invalid chapter cache entry");
        File file = file(source, book, content.chapter(), nextChapterUrl);
        File temp = File.createTempFile(file.getName(), ".tmp", directory);
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                gson.toJson(content, writer);
            }
            if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save chapter cache");
        } finally {
            if (temp.exists()) temp.delete();
        }
    }
    static String sourceHash(Source source) {
        return hex(digest(source.json().toString()));
    }

    private File file(Source source, BookDetail book, Chapter chapter, String nextChapterUrl) {
        try {
            // Rules can read book metadata/variables, and the next chapter ends pagination.
            String key = sourceHash(source) + "\n" + gson.toJson(chapter)
                    + "\n" + (nextChapterUrl == null ? "" : nextChapterUrl) + "\n" + gson.toJson(book);
            return new File(directory, hex(digest(key)) + ".json");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean valid(Source source, Chapter requested, ChapterContent content) {
        if (requested == null || content == null || !requested.equals(content.chapter())
                || content.kind() != source.kind()) return false;
        if (source.kind() == Kind.TEXT) return content.text() != null && !content.text().isBlank();
        return content.resources() != null && !content.resources().isEmpty()
                && content.resources().stream().allMatch(resource ->
                resource != null && ResourceUrls.isHttp(resource.url()));
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder key = new StringBuilder();
        for (byte value : bytes) key.append(String.format("%02x", value & 255));
        return key.toString();
    }
}
