package org.bookgetter.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Keeps potentially large URL selections out of the Activity's Binder state transaction. */
final class PendingResourceStore {
    private static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private final File directory;
    PendingResourceStore(File directory) {
        this.directory = new File(directory, "pending-resources");
        pruneExpired();
    }
    private void pruneExpired() {
        File[] snapshots = directory.listFiles();
        if (snapshots == null) return;
        long oldest = System.currentTimeMillis() - RETENTION_MILLIS;
        for (File snapshot : snapshots)
            if ((snapshot.getName().endsWith(".json") || snapshot.getName().endsWith(".tmp"))
                    && snapshot.lastModified() < oldest) snapshot.delete();
    }
    String save(PendingResourceDownload selection) throws Exception {
        pruneExpired();
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Cannot create pending resource directory");
        String token = UUID.randomUUID().toString();
        File target = file(token);
        File temporary = new File(directory, token + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(selection.serialize().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(target)) throw new IllegalStateException("Cannot save resource selection");
            return token;
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    PendingResourceDownload restore(String token) {
        File selected = file(token);
        if (selected == null || !selected.isFile()) return null;
        try (FileInputStream input = new FileInputStream(selected);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return PendingResourceDownload.restore(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception invalid) { return null; }
    }
    void delete(String token) {
        File selected = file(token);
        if (selected != null) selected.delete();
    }
    private File file(String token) {
        return token != null && token.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                ? new File(directory, token + ".json") : null;
    }
}
