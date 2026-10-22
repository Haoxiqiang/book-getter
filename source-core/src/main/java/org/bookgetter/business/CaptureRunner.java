package org.bookgetter.business;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;

/** Coordinates resumable chapter capture without depending on Activity state. */
public final class CaptureRunner {
    @FunctionalInterface public interface ContentFetcher {
        ChapterContent fetch(Source source, BookDetail book, Chapter chapter, String nextChapterUrl);
    }
    @FunctionalInterface public interface OutputFactory { OutputStream open() throws Exception; }
    @FunctionalInterface public interface Progress { void update(int processed, int total, int failed); }
    public record Result(int captured, Map<String, String> failures) {
        public Result { failures = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(failures)); }
        public boolean complete() { return captured > 0 && failures.isEmpty(); }
    }

    public static Result run(Source source, CaptureJobStore.Job job, ChapterStore cache, CaptureJobStore jobs,
                      ContentFetcher fetcher, OutputFactory output, Progress progress) throws Exception {
        List<Chapter> captured = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        ChapterBoundaries boundaries = new ChapterBoundaries(job.catalog());
        ChapterStore.Session session = cache.session(source, job.book());
        jobs.beginProgress(job);
        for (Chapter chapter : job.selected()) {
            requireActive();
            try {
                String boundary = boundaries.after(chapter);
                ChapterContent content = session.get(chapter, boundary);
                if (content == null) {
                    content = fetcher.fetch(session.source(), session.book(), chapter, boundary);
                    requireActive();
                    session.put(content, boundary);
                }
                captured.add(chapter);
            } catch (Exception e) {
                requireActive();
                failures.put(chapter.url(), chapter.title() + ": " + errorText(e));
            }
            jobs.updateProgress(job, captured.size(), chapter.url(), failures.get(chapter.url()));
            progress.update(captured.size() + failures.size(), job.selected().size(), failures.size());
        }
        requireActive();
        if (!captured.isEmpty())
            CaptureExport.writeFromCache(output.open(), job, captured, session, failures.isEmpty(), jobs);
        return new Result(captured.size(), failures);
    }

    private static void requireActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Capture cancelled");
    }
    private static String errorText(Exception e) {
        if (e instanceof SourceFailure failure)
            return failure.stage + " / " + failure.field + " / " + failure.code + " / "
                    + failure.sourceId + ": " + failure.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
