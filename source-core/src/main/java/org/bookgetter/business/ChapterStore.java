package org.bookgetter.business;

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
public final class ChapterStore {
    private final File directory;
    private final Gson gson = new Gson();
    public ChapterStore(File directory) { this.directory = directory; directory.mkdirs(); }
    /** A capture owns this snapshot; no cached identity is retained across sessions. */
    public final class Session {
        private final Source source;
        private final BookDetail book;
        private final String sourceIdentity;
        private final String bookJson;

        private Session(Source source, BookDetail book) {
            if (source == null || book == null || book.hit() == null || book.hit().url() == null)
                throw new IllegalArgumentException("Source and book context are required");
            this.source = new Source(source.id(), source.name(), source.kind(), source.json().deepCopy());
            this.book = new BookDetail(book.hit(), book.tocUrl(), book.downloadUrls() == null ? null
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(book.downloadUrls())));
            sourceIdentity = sourceHash(this.source);
            bookJson = gson.toJson(this.book);
        }

        public Source source() { return source; }
        public BookDetail book() { return book; }

        public ChapterContent get(Chapter chapter, String nextChapterUrl) {
            if (!belongsToBook(chapter)) return null;
            File file = file(sourceIdentity, bookJson, chapter, nextChapterUrl);
            if (!file.isFile()) return null;
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                ChapterContent content = gson.fromJson(reader, ChapterContent.class);
                // A syntactically valid but incomplete cache entry must be fetched again.
                return valid(source, chapter, content) ? content : null;
            } catch (Exception ignored) { return null; }
        }

        public void put(ChapterContent content, String nextChapterUrl) throws Exception {
            if (content == null || !belongsToBook(content.chapter()) || !valid(source, content.chapter(), content))
                throw new IllegalArgumentException("Invalid chapter cache entry");
            File file = file(sourceIdentity, bookJson, content.chapter(), nextChapterUrl);
            File temp = File.createTempFile(file.getName(), ".tmp", directory);
            try {
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                    gson.toJson(content, writer);
                }
                if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save chapter cache");
            } finally { if (temp.exists()) temp.delete(); }
        }

        private boolean belongsToBook(Chapter chapter) {
            return chapter != null && book.hit().url().equals(chapter.bookUrl());
        }
    }

    public Session session(Source source, BookDetail book) { return new Session(source, book); }

    public ChapterContent get(Source source, BookDetail book, Chapter chapter, String nextChapterUrl) {
        if (book == null || book.hit() == null || book.hit().url() == null || chapter == null
                || !book.hit().url().equals(chapter.bookUrl())) return null;
        return session(source, book).get(chapter, nextChapterUrl);
    }
    public void put(Source source, BookDetail book, ChapterContent content, String nextChapterUrl) throws Exception {
        if (book == null || book.hit() == null || book.hit().url() == null)
            throw new IllegalArgumentException("Invalid chapter cache entry");
        session(source, book).put(content, nextChapterUrl);
    }
    public static String sourceHash(Source source) {
        return hex(digest(source.json().toString()));
    }

    private File file(String sourceIdentity, String bookJson, Chapter chapter, String nextChapterUrl) {
        try {
            // Rules can read book metadata/variables, and the next chapter ends pagination.
            String key = sourceIdentity + "\n" + gson.toJson(chapter)
                    + "\n" + (nextChapterUrl == null ? "" : nextChapterUrl) + "\n" + bookJson;
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
        for (byte value : bytes)
            key.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return key.toString();
    }
}
