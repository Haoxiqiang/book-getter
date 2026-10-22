package org.bookgetter.app;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bookgetter.core.RssModels.Article;

/** Keeps article HTML out of Activity saved-state transactions. */
final class RssSelectionStore {
    private static final Gson JSON = new Gson();
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final File directory;
    RssSelectionStore(File parent) {
        directory = new File(parent, "rss-selections");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create RSS selection directory");
        File[] stale = directory.listFiles();
        long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        if (stale != null) for (File file : stale)
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
    }
    String save(Article article) throws IOException {
        byte[] bytes = JSON.toJson(article).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("RSS article selection exceeds limit");
        String token = UUID.randomUUID().toString();
        File temp = new File(directory, token + ".tmp"), target = file(token);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(bytes); output.getFD().sync(); }
            if (!temp.renameTo(target)) throw new IOException("Cannot save RSS selection");
        } finally { if (temp.exists()) temp.delete(); }
        return token;
    }
    Article restore(String token) {
        File file = file(token);
        if (file == null || !file.isFile() || file.length() > MAX_BYTES) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Article value = JSON.fromJson(reader, Article.class);
            if (value == null || value.origin() == null || value.link() == null || value.kind() == null) return null;
            return new Article(value.origin(), value.sort(), value.title(), value.link(), value.pubDate(),
                    value.description(), value.content(), value.image(), value.kind(), value.variables());
        } catch (Exception invalid) { return null; }
    }
    void remove(String token) { File file = file(token); if (file != null) file.delete(); }
    private File file(String token) {
        return token != null && token.matches("[a-f0-9-]{36}") ? new File(directory, token + ".json") : null;
    }
}
