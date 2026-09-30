package org.bookgetter.app;

import android.content.Context;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.Chapter;

/** Persists the selected catalog and progress; chapter bodies remain in ChapterStore. */
final class CaptureJobStore {
    private static final Object FILE_LOCK = new Object();
    record Job(String sourceId, String sourceHash, BookDetail book, List<Chapter> catalog, List<Chapter> selected,
               boolean epub, int completed, Map<String, String> failures, String generation) {
        Job(String sourceId, String sourceHash, BookDetail book, List<Chapter> catalog, List<Chapter> selected,
            boolean epub, int completed, Map<String, String> failures) {
            this(sourceId, sourceHash, book, catalog, selected, epub, completed, failures,
                    UUID.randomUUID().toString());
        }
        Job {
            catalog = List.copyOf(catalog);
            selected = List.copyOf(selected);
            failures = Map.copyOf(failures);
        }
    }

    private final Gson gson = new Gson();
    private final File file;

    CaptureJobStore(Context context) { this(context.getFilesDir()); }
    CaptureJobStore(File directory) { file = new File(directory, "capture-job.json"); }

    Job get() { synchronized (FILE_LOCK) {
        if (!file.isFile()) return null;
        try (InputStreamReader input = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Job job = gson.fromJson(input, Job.class);
            if (job == null || job.sourceId() == null || job.sourceHash() == null
                    || job.book() == null || job.book().hit() == null || job.catalog() == null
                    || job.selected() == null || job.failures() == null
                    || job.completed() < 0 || job.completed() > job.selected().size())
                throw new IllegalStateException("Saved capture task is damaged");
            // Gson bypasses record constructors. Rebuild to reject null members and restore
            // the immutable snapshots expected by capture and export code.
            BookHit hit = job.book().hit();
            BookDetail book = new BookDetail(new BookHit(hit.sourceId(), hit.url(), hit.name(), hit.author(),
                    hit.intro(), hit.coverUrl(), hit.kind(), hit.genre(), hit.wordCount(), hit.lastChapter(),
                    hit.variables()), job.book().tocUrl(), List.copyOf(job.book().downloadUrls()));
            return new Job(job.sourceId(), job.sourceHash(), book, job.catalog(), job.selected(),
                    job.epub(), job.completed(), job.failures(), job.generation());
        } catch (Exception e) { throw new IllegalStateException("Saved capture task is damaged", e); }
    } }

    /** Preserve the unreadable file before an explicitly requested new task is saved. */
    File backupCorrupt() { synchronized (FILE_LOCK) {
        if (!file.isFile()) throw new IllegalStateException("No capture task to back up");
        try { get(); } catch (IllegalStateException damaged) {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
            for (int suffix = 1; backup.exists(); suffix++)
                backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis() + "-" + suffix);
            if (!file.renameTo(backup)) throw new IllegalStateException("Cannot back up damaged capture task", damaged);
            return backup;
        }
        throw new IllegalStateException("Capture task is readable; backup is unnecessary");
    } }

    static boolean sameTask(Job left, Job right) {
        return left.sourceId().equals(right.sourceId()) && left.sourceHash().equals(right.sourceHash())
                && left.book().equals(right.book())
                && left.catalog().equals(right.catalog()) && left.selected().equals(right.selected())
                && left.epub() == right.epub();
    }

    boolean putNew(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing != null) return sameTask(existing, job);
        write(job);
        return true;
    } }

    /** Only an explicit replacement may discard a different unfinished task. */
    void replace(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing != null && Objects.equals(existing.generation(), job.generation()))
            throw new IllegalArgumentException("Replacement must have a new task generation");
        write(job);
    } }

    void update(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing == null || !sameTask(existing, job)
                || !Objects.equals(existing.generation(), job.generation()))
            throw new IllegalStateException("Capture task was replaced");
        write(job);
    } }

    private void write(Job job) throws Exception {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStreamWriter output = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            gson.toJson(job, output);
        }
        if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save capture job");
    }

    void clear(Job job) { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing == null || !sameTask(existing, job)
                || !Objects.equals(existing.generation(), job.generation())) return;
        if (!file.delete()) throw new IllegalStateException("Cannot clear capture job");
    } }
}
