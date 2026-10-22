package org.bookgetter.business;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.ChapterContent;
import org.bookgetter.core.Models.Source;
import org.bookgetter.export.BookExporter;

/** A task is complete only after the document provider accepts the closed output stream. */
public final class CaptureExport {
    private CaptureExport() {}
    @FunctionalInterface private interface Writer { void write(OutputStream output) throws IOException; }

    public static void write(OutputStream destination, CaptureJobStore.Job job, List<ChapterContent> captured,
                      boolean complete, CaptureJobStore jobs) throws Exception {
        commit(destination, job, complete, jobs, output -> {
            if (complete) {
                if (captured == null || captured.size() != job.selected().size())
                    throw new IllegalArgumentException("Complete export must include every selected chapter");
                for (int index = 0; index < captured.size(); index++)
                    if (captured.get(index) == null || !job.selected().get(index).equals(captured.get(index).chapter()))
                        throw new IllegalArgumentException("Complete export chapter order differs from selection");
            }
            new BookExporter().write(job.epub() ? BookExporter.Format.EPUB : BookExporter.Format.TXT,
                    job.book(), captured, output);
        });
    }

    public static void writeFromCache(OutputStream destination, CaptureJobStore.Job job, List<Chapter> captured,
                               Source source, ChapterStore cache, boolean complete, CaptureJobStore jobs)
            throws Exception {
        writeFromCache(destination, job, captured, cache.session(source, job.book()), complete, jobs);
    }

    public static void writeFromCache(OutputStream destination, CaptureJobStore.Job job, List<Chapter> captured,
                               ChapterStore.Session session, boolean complete, CaptureJobStore jobs)
            throws Exception {
        commit(destination, job, complete, jobs, output -> {
            if (complete && !job.selected().equals(captured))
                throw new IllegalArgumentException("Complete export must include every selected chapter in order");
            ChapterBoundaries boundaries = new ChapterBoundaries(job.catalog());
            new BookExporter().writeFrom(job.epub() ? BookExporter.Format.EPUB : BookExporter.Format.TXT,
                        session.book(), captured.size(), index -> {
                            Chapter chapter = captured.get(index);
                            String boundary = boundaries.after(chapter);
                            ChapterContent content = session.get(chapter, boundary);
                            if (content == null) throw new IOException("Cached chapter unavailable: " + chapter.title());
                            return content;
                        }, output);
        });
    }

    private static void commit(OutputStream destination, CaptureJobStore.Job job, boolean complete,
                               CaptureJobStore jobs, Writer writer) throws Exception {
        if (destination == null) throw new IllegalStateException("无法写入导出文件");
        try (OutputStream output = destination) {
            writer.write(output);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
        if (complete) jobs.clear(job);
    }
}
