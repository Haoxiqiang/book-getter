package org.bookgetter.export;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

final class BookExporterStreamOwnershipTest {
    private final BookHit hit = new BookHit("s", "https://example.test/book", "Book", "Author", "", "", Kind.TEXT);
    private final BookDetail book = new BookDetail(hit, hit.url(), List.of());
    private final Chapter chapter = new Chapter(hit.url(), hit.url() + "/1", "One", 0, false, false);
    private static final class Destination extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
    @Test void epubReleasesZipWithoutClosingCallerStream() throws Exception {
        Destination destination = new Destination();
        new BookExporter().writeFrom(BookExporter.Format.EPUB, book, 1,
                index -> new ChapterContent(chapter, Kind.TEXT, "Body", List.of()), destination);
        assertFalse(destination.closed);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(destination.toByteArray()))) {
            int entries = 0;
            while (zip.getNextEntry() != null) { zip.readAllBytes(); entries++; }
            assertEquals(5, entries);
        }
    }
    @Test void failingLoaderKeepsCallerStreamOpen() {
        Destination destination = new Destination();
        assertThrows(IOException.class, () -> new BookExporter().writeFrom(BookExporter.Format.EPUB, book, 1,
                index -> { throw new IOException("Cache missing"); }, destination));
        assertFalse(destination.closed);
    }
}
