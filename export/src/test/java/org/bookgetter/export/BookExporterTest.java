package org.bookgetter.export;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

final class BookExporterTest {
    private final BookHit hit = new BookHit("source", "https://example.test/book", "Book", "Author", "", "", Kind.TEXT);
    private final BookDetail book = new BookDetail(hit, hit.url(), List.of());
    private final Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
    private final List<ChapterContent> contents = List.of(new ChapterContent(chapter, Kind.TEXT, "<p>Hello &amp; world</p>", List.of()));

    @Test void textExportContainsSelectedChapter() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BookExporter().write(BookExporter.Format.TXT, book, contents, bytes);
        String result = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("One"));
        assertTrue(result.contains("Hello & world"));
    }

    @Test void streamingTextExportLoadsOneChapterAfterWritingThePreviousOne() throws Exception {
        Chapter second = new Chapter(hit.url(), "https://example.test/two", "Two", 1, false, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        new BookExporter().writeFrom(BookExporter.Format.TXT, book, 2, index -> {
            loads.incrementAndGet();
            if (index == 1) assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("First body"));
            return new ChapterContent(index == 0 ? chapter : second, Kind.TEXT,
                    index == 0 ? "First body" : "Second body", List.of());
        }, bytes);
        assertEquals(2, loads.get());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).indexOf("First body")
                < bytes.toString(StandardCharsets.UTF_8).indexOf("Second body"));
    }

    @Test void streamingExportStopsWhenCancelled() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.io.InterruptedIOException.class, () ->
                    new BookExporter().writeFrom(BookExporter.Format.TXT, book, 1,
                            index -> contents.get(0), new ByteArrayOutputStream()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test void epubHasRequiredEntriesAndMimetypeFirst() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BookExporter().write(BookExporter.Format.EPUB, book, contents, bytes);
        byte[] archive = bytes.toByteArray();
        assertEquals("mimetype", new String(archive, 30, 8, StandardCharsets.US_ASCII));
        assertEquals("application/epub+zip", new String(archive, 38, 20, StandardCharsets.US_ASCII));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry first = zip.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/epub+zip", new String(zip.readAllBytes(), StandardCharsets.US_ASCII));
            java.util.Set<String> names = new java.util.HashSet<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            assertTrue(names.contains("OEBPS/content.opf"));
            assertTrue(names.contains("OEBPS/c0.xhtml"));
            assertTrue(names.contains("OEBPS/toc.ncx"));
        }
    }

    @Test void htmlBlocksAndBreaksRemainSeparateInBothFormats() throws Exception {
        ChapterContent content = new ChapterContent(chapter, Kind.TEXT,
                "<p>First <b>bold</b> line</p><p>Second &amp; third<br>last</p>", List.of());
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        new BookExporter().write(BookExporter.Format.TXT, book, List.of(content), text);
        assertTrue(text.toString(StandardCharsets.UTF_8).contains("First bold line\nSecond & third\nlast"));

        String xhtml = epubEntries(List.of(content), book).get("OEBPS/c0.xhtml");
        assertTrue(xhtml.contains("<p>First bold line</p><p>Second &amp; third</p><p>last</p>"));
    }

    @Test void epubMetadataAndAllDocumentsAreWellFormedXml() throws Exception {
        BookHit specialHit = new BookHit("another-source", hit.url(), "A & <B>\u0001", "Writer \ud83d\ude00", "", "", Kind.TEXT);
        BookDetail specialBook = new BookDetail(specialHit, hit.url(), List.of());
        Chapter specialChapter = new Chapter(hit.url(), chapter.url(), "<One> & \u0002", 0, false, false);
        ChapterContent content = new ChapterContent(specialChapter, Kind.TEXT, "<p>Hello &amp; \u0003world</p>", List.of());
        Map<String, String> entries = epubEntries(List.of(content), specialBook);
        for (String path : List.of("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx", "OEBPS/c0.xhtml")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get(path).getBytes(StandardCharsets.UTF_8)));
        }
        String expectedId = "urn:uuid:" + UUID.nameUUIDFromBytes((specialHit.sourceId() + "\u0000" + specialHit.url()).getBytes(StandardCharsets.UTF_8));
        assertTrue(entries.get("OEBPS/content.opf").contains(expectedId));
        assertTrue(entries.get("OEBPS/toc.ncx").contains(expectedId));
        assertTrue(entries.get("OEBPS/content.opf").contains("<dc:language>und</dc:language>"));
        assertTrue(entries.get("OEBPS/c0.xhtml").contains("&lt;One&gt; &amp;"));
    }

    @Test void selectedChaptersKeepGivenOrderInSpineAndNavigation() throws Exception {
        Chapter later = new Chapter(hit.url(), "https://example.test/two", "Two", 2, false, false);
        ChapterContent second = new ChapterContent(later, Kind.TEXT, "Second", List.of());
        Map<String, String> entries = epubEntries(List.of(second, contents.get(0)), book);
        assertTrue(entries.get("OEBPS/c0.xhtml").contains("<h1>Two</h1>"));
        assertTrue(entries.get("OEBPS/c1.xhtml").contains("<h1>One</h1>"));
        assertTrue(entries.get("OEBPS/content.opf").contains("<itemref idref=\"c0\"/><itemref idref=\"c1\"/>"));
        assertTrue(entries.get("OEBPS/toc.ncx").indexOf("<text>Two</text>") < entries.get("OEBPS/toc.ncx").indexOf("<text>One</text>"));
    }

    @Test void rejectsNonTextOrMissingTextBeforeWriting() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ChapterContent nonText = new ChapterContent(chapter, Kind.IMAGE, null, List.of());
        assertThrows(IllegalArgumentException.class, () ->
                new BookExporter().write(BookExporter.Format.EPUB, book, List.of(contents.get(0), nonText), output));
        assertEquals(0, output.size());
        assertThrows(IllegalArgumentException.class, () ->
                new BookExporter().write(BookExporter.Format.TXT, book, List.of(new ChapterContent(chapter, Kind.TEXT, null, List.of())), output));
        assertEquals(0, output.size());
    }

    private static Map<String, String> epubEntries(List<ChapterContent> chapters, BookDetail detail) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new BookExporter().write(BookExporter.Format.EPUB, detail, chapters, output);
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return entries;
    }
}
