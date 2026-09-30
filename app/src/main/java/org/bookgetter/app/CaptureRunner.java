package org.bookgetter.app;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SourceEngine;

/** Coordinates resumable chapter capture without depending on Activity state. */
final class CaptureRunner {
    @FunctionalInterface interface ContentFetcher {
        ChapterContent fetch(Source source, BookDetail book, Chapter chapter, String nextChapterUrl);
    }
    @FunctionalInterface interface OutputFactory { OutputStream open() throws Exception; }
    @FunctionalInterface interface Progress { void update(int processed, int total, int failed); }
    record Result(int captured, Map<String, String> failures) {
        Result { failures = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(failures)); }
        boolean complete() { return captured > 0 && failures.isEmpty(); }
    }

    static Result run(Source source, CaptureJobStore.Job job, ChapterStore cache, CaptureJobStore jobs,
                      ContentFetcher fetcher, OutputFactory output, Progress progress) throws Exception {
        List<Chapter> captured = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (Chapter chapter : job.selected()) {
            requireActive();
            try {
                String boundary = SourceEngine.nextReadableUrl(job.catalog(), chapter);
                ChapterContent content = cache.get(source, job.book(), chapter, boundary);
                if (content == null) {
                    content = fetcher.fetch(source, job.book(), chapter, boundary);
                    requireActive();
                    cache.put(source, job.book(), content, boundary);
                }
                captured.add(chapter);
            } catch (Exception e) {
                requireActive();
                failures.put(chapter.url(), chapter.title() + ": " + errorText(e));
            }
            jobs.update(new CaptureJobStore.Job(job.sourceId(), job.sourceHash(), job.book(), job.catalog(),
                    job.selected(), job.epub(), captured.size(), failures, job.generation()));
            progress.update(captured.size() + failures.size(), job.selected().size(), failures.size());
        }
        requireActive();
        if (!captured.isEmpty())
            CaptureExport.writeFromCache(output.open(), job, captured, source, cache, failures.isEmpty(), jobs);
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
