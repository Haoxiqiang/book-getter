package org.bookgetter.business;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
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
public final class CaptureJobStore {
    private static final Object FILE_LOCK = new Object();
    public record Job(String sourceId, String sourceHash, BookDetail book, List<Chapter> catalog, List<Chapter> selected,
               boolean epub, int completed, Map<String, String> failures, String generation) {
        public Job(String sourceId, String sourceHash, BookDetail book, List<Chapter> catalog, List<Chapter> selected,
            boolean epub, int completed, Map<String, String> failures) {
            this(sourceId, sourceHash, book, catalog, selected, epub, completed, failures,
                    UUID.randomUUID().toString());
        }
        public Job {
            catalog = List.copyOf(catalog);
            selected = List.copyOf(selected);
            failures = Map.copyOf(failures);
        }
    }

    private final Gson gson = new Gson();
    private final File file;
    private final File progressFile;
    // All store instances share the lock/version so an old worker observes replacements.
    private static long mutationVersion;
    private long cachedVersion = -1;
    private Job cached;
    private Job validatedJob;
    private long validatedVersion = -1;
    private record ProgressEntry(String generation, int completed, boolean reset, String url, String error) {}

    public CaptureJobStore(File directory) {
        file = new File(directory, "capture-job.json");
        progressFile = new File(directory, "capture-progress.jsonl");
    }

    public Job get() { synchronized (FILE_LOCK) {
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
            Job snapshot = new Job(job.sourceId(), job.sourceHash(), book, job.catalog(), job.selected(),
                    job.epub(), job.completed(), job.failures(), job.generation());
            cached = readProgress(snapshot);
            cachedVersion = mutationVersion;
            return cached;
        } catch (Exception e) { throw new IllegalStateException("Saved capture task is damaged", e); }
    } }

    private Job readProgress(Job snapshot) throws IOException {
        if (!progressFile.isFile()) return snapshot;
        int completed = snapshot.completed();
        Map<String, String> failures = new LinkedHashMap<>(snapshot.failures());
        // An interrupted final append is ignored; earlier complete records remain valid.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(progressFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ProgressEntry entry;
                try { entry = gson.fromJson(line, ProgressEntry.class); }
                catch (RuntimeException damaged) {
                    if (reader.readLine() == null) break;
                    throw new IOException("Damaged capture progress", damaged);
                }
                if (entry == null || !Objects.equals(snapshot.generation(), entry.generation())) continue;
                if (entry.completed() < 0 || entry.completed() > snapshot.selected().size())
                    throw new IOException("Invalid capture progress");
                if (entry.reset()) failures.clear();
                completed = entry.completed();
                if (entry.url() != null) {
                    if (entry.error() == null) failures.remove(entry.url());
                    else failures.put(entry.url(), entry.error());
                }
            }
        }
        return new Job(snapshot.sourceId(), snapshot.sourceHash(), snapshot.book(), snapshot.catalog(),
                snapshot.selected(), snapshot.epub(), completed, failures, snapshot.generation());
    }

    /** Start a retry without rewriting the immutable book/catalog snapshot. */
    public void beginProgress(Job job) throws Exception { synchronized (FILE_LOCK) {
        requireCurrent(job);
        replaceProgress(job, 0, Map.of());
    } }

    private void replaceProgress(Job job, int completed, Map<String, String> failures) throws IOException {
        File temporary = new File(progressFile.getPath() + ".tmp");
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
                gson.toJson(new ProgressEntry(job.generation(), completed, true, null, null), writer);
                writer.write('\n');
                for (var failure : failures.entrySet()) {
                    gson.toJson(new ProgressEntry(job.generation(), completed, false, failure.getKey(), failure.getValue()), writer);
                    writer.write('\n');
                }
            }
            if (!temporary.renameTo(progressFile)) throw new IOException("Cannot reset capture progress");
            mutationVersion++;
            cachedVersion = mutationVersion;
            validatedVersion = mutationVersion;
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    public void updateProgress(Job job, int completed, String failedUrl, String error) throws Exception {
        synchronized (FILE_LOCK) {
            requireCurrent(job);
            if (completed < 0 || completed > job.selected().size())
                throw new IllegalArgumentException("Invalid completed chapter count");
            append(new ProgressEntry(job.generation(), completed, false, failedUrl, error));
        }
    }

    private void requireCurrent(Job job) {
        if (validatedJob == job && validatedVersion == mutationVersion && file.isFile()) return;
        Job current = cachedVersion == mutationVersion && file.isFile() ? cached : get();
        if (current == null || !Objects.equals(current.generation(), job.generation())
                || !sameTask(current, job)) throw new IllegalStateException("Capture task was replaced");
        validatedJob = job;
        validatedVersion = mutationVersion;
    }

    private void append(ProgressEntry entry) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(progressFile, true),
                StandardCharsets.UTF_8)) {
            gson.toJson(entry, writer);
            writer.write('\n');
        }
        // The snapshot identity remains unchanged. Other store instances reload the journal.
        mutationVersion++;
        cachedVersion = mutationVersion;
        validatedVersion = mutationVersion;
    }

    /** Preserve the unreadable file before an explicitly requested new task is saved. */
    public File backupCorrupt() { synchronized (FILE_LOCK) {
        if (!file.isFile()) throw new IllegalStateException("No capture task to back up");
        try { get(); } catch (IllegalStateException damaged) {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
            for (int suffix = 1; backup.exists(); suffix++)
                backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis() + "-" + suffix);
            File progressBackup = new File(backup.getPath() + ".progress");
            boolean hasProgress = progressFile.exists();
            if (hasProgress && !progressFile.renameTo(progressBackup))
                throw new IllegalStateException("Cannot back up damaged capture progress", damaged);
            if (!file.renameTo(backup)) {
                if (hasProgress && !progressBackup.renameTo(progressFile))
                    damaged.addSuppressed(new IOException("Cannot restore capture progress backup"));
                throw new IllegalStateException("Cannot back up damaged capture task", damaged);
            }
            mutationVersion++;
            return backup;
        }
        throw new IllegalStateException("Capture task is readable; backup is unnecessary");
    } }

    public static boolean sameTask(Job left, Job right) {
        return left.sourceId().equals(right.sourceId()) && left.sourceHash().equals(right.sourceHash())
                && left.book().equals(right.book())
                && left.catalog().equals(right.catalog()) && left.selected().equals(right.selected())
                && left.epub() == right.epub();
    }

    public boolean putNew(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing != null) return sameTask(existing, job);
        write(job);
        return true;
    } }

    /** Only an explicit replacement may discard a different unfinished task. */
    public void replace(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing != null && Objects.equals(existing.generation(), job.generation()))
            throw new IllegalArgumentException("Replacement must have a new task generation");
        write(job);
    } }

    public void update(Job job) throws Exception { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing == null || !sameTask(existing, job)
                || !Objects.equals(existing.generation(), job.generation()))
            throw new IllegalStateException("Capture task was replaced");
        if (job.completed() < 0 || job.completed() > job.selected().size())
            throw new IllegalArgumentException("Invalid completed chapter count");
        requireCurrent(job);
        replaceProgress(job, job.completed(), job.failures());
    } }

    private void write(Job job) throws Exception {
        if (!file.isFile() && progressFile.exists() && !progressFile.delete())
            throw new IOException("Cannot remove orphaned capture progress");
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStreamWriter output = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            gson.toJson(job, output);
        }
        if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save capture job");
        mutationVersion++;
        cached = job;
        cachedVersion = mutationVersion;
        // Old-generation records are harmless if cleanup was interrupted after publication.
        if (progressFile.exists()) progressFile.delete();
    }

    public void clear(Job job) { synchronized (FILE_LOCK) {
        Job existing = get();
        if (existing == null || !sameTask(existing, job)
                || !Objects.equals(existing.generation(), job.generation())) return;
        if (!file.delete()) throw new IllegalStateException("Cannot clear capture job");
        mutationVersion++;
        cached = null;
        cachedVersion = mutationVersion;
        // An orphan log cannot resurrect a deleted task and is removed before the next creation.
        if (progressFile.exists()) progressFile.delete();
    } }
}
