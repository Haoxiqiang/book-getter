package org.bookgetter.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.ChapterContent;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.SourceEngine;
import org.bookgetter.export.BookExporter;

/** A task is complete only after the document provider accepts the closed output stream. */
final class CaptureExport {
    private CaptureExport() {}
    @FunctionalInterface private interface Writer { void write(OutputStream output) throws IOException; }

    static void write(OutputStream destination, CaptureJobStore.Job job, List<ChapterContent> captured,
                      boolean complete, CaptureJobStore jobs) throws Exception {
        commit(destination, job, complete, jobs, output ->
                new BookExporter().write(job.epub() ? BookExporter.Format.EPUB : BookExporter.Format.TXT,
                        job.book(), captured, output));
    }

    static void writeFromCache(OutputStream destination, CaptureJobStore.Job job, List<Chapter> captured,
                               Source source, ChapterStore cache, boolean complete, CaptureJobStore jobs)
            throws Exception {
        commit(destination, job, complete, jobs, output ->
                new BookExporter().writeFrom(job.epub() ? BookExporter.Format.EPUB : BookExporter.Format.TXT,
                        job.book(), captured.size(), index -> {
                            Chapter chapter = captured.get(index);
                            String boundary = SourceEngine.nextReadableUrl(job.catalog(), chapter);
                            ChapterContent content = cache.get(source, job.book(), chapter, boundary);
                            if (content == null) throw new IOException("Cached chapter unavailable: " + chapter.title());
                            return content;
                        }, output));
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
