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

/** A successful chapter is written atomically and reused on the next capture attempt. */
final class ChapterStore {
    private final File directory;
    private final Gson gson = new Gson();
    ChapterStore(Context context) { directory = new File(context.getFilesDir(), "chapter-cache"); directory.mkdirs(); }
    ChapterContent get(String sourceId, Chapter chapter) {
        File file = file(sourceId, chapter);
        if (!file.isFile()) return null;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, ChapterContent.class);
        }
        catch (Exception ignored) { return null; }
    }
    void put(String sourceId, ChapterContent content) throws Exception {
        File file = file(sourceId, content.chapter());
        File temp = new File(directory, file.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            gson.toJson(content, writer);
        }
        if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save chapter cache");
    }
    private File file(String sourceId, Chapter chapter) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((sourceId + "\n" + chapter.bookUrl() + "\n" + chapter.url()).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : hash) key.append(String.format("%02x", value & 255));
            return new File(directory, key + ".json");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
