package org.bookgetter.export;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.ChapterContent;
import org.bookgetter.core.Models.Kind;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** EPUB 2 and UTF-8 TXT output for selected text chapters. Owns no source fetching. */
public final class BookExporter {
    public enum Format { TXT, EPUB }
    @FunctionalInterface public interface ChapterLoader {
        ChapterContent load(int index) throws IOException;
    }

    public void write(Format format, BookDetail book, List<ChapterContent> chapters, OutputStream output) throws IOException {
        if (chapters == null) throw new IllegalArgumentException("Chapters are required");
        for (ChapterContent chapter : chapters) requireText(chapter);
        writeFrom(format, book, chapters.size(), chapters::get, output);
    }

    /** Loads one cached chapter at a time; callers retain the cache until the output stream closes. */
    public void writeFrom(Format format, BookDetail book, int chapterCount, ChapterLoader loader,
                          OutputStream output) throws IOException {
        if (format == null || book == null || book.hit() == null || output == null || loader == null)
            throw new IllegalArgumentException("Format, book, chapters and output are required");
        if (chapterCount <= 0) throw new IllegalArgumentException("No chapters selected");
        requireActive();
        if (format == Format.TXT) writeText(book, chapterCount, loader, output);
        else writeEpub(book, chapterCount, loader, output);
    }

    private static ChapterContent requireText(ChapterContent chapter) {
        if (chapter == null || chapter.chapter() == null || chapter.kind() != Kind.TEXT || chapter.text() == null)
            throw new IllegalArgumentException("EPUB/TXT export accepts text chapters only");
        return chapter;
    }

    private void writeText(BookDetail book, int chapterCount, ChapterLoader loader, OutputStream output) throws IOException {
        writeTextChunk(output, cleanXml(book.hit().name()) + "\n作者：" + cleanXml(book.hit().author()) + "\n\n");
        for (int i = 0; i < chapterCount; i++) {
            requireActive();
            ChapterContent chapter = requireText(loader.load(i));
            writeTextChunk(output, cleanXml(chapter.chapter().title()) + "\n\n");
            writeTextChunk(output, String.join("\n", paragraphs(chapter.text())) + "\n\n");
        }
    }

    private static void writeTextChunk(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeEpub(BookDetail book, int chapterCount, ChapterLoader loader, OutputStream output) throws IOException {
        // Releasing the compressor must not close the caller-owned document stream.
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(output) {
            @Override public void close() throws IOException { flush(); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
        })) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED); mime.setSize(mimetype.length); mime.setCompressedSize(mimetype.length); mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimetype); zip.closeEntry();
            entry(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            StringBuilder manifest = new StringBuilder("<item id=\"toc\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");
            StringBuilder spine = new StringBuilder();
            StringBuilder nav = new StringBuilder();
            for (int i = 0; i < chapterCount; i++) {
                requireActive();
                String id = "c" + i;
                ChapterContent chapter = requireText(loader.load(i));
                manifest.append("<item id=\"").append(id).append("\" href=\"").append(id).append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"").append(id).append("\"/>");
                nav.append("<navPoint id=\"").append(id).append("\" playOrder=\"").append(i + 1).append("\"><navLabel><text>").append(xml(chapter.chapter().title())).append("</text></navLabel><content src=\"").append(id).append(".xhtml\"/></navPoint>");
                StringBuilder body = new StringBuilder();
                for (String paragraph : paragraphs(chapter.text())) body.append("<p>").append(xml(paragraph)).append("</p>");
                entry(zip, "OEBPS/" + id + ".xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + xml(chapter.chapter().title()) + "</title></head><body><h1>" + xml(chapter.chapter().title()) + "</h1>" + body + "</body></html>");
            }
            String uid = "urn:uuid:" + UUID.nameUUIDFromBytes((nullToEmpty(book.hit().sourceId()) + "\u0000" + nullToEmpty(book.hit().url())).getBytes(StandardCharsets.UTF_8));
            entry(zip, "OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"BookId\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"BookId\">" + uid + "</dc:identifier><dc:title>" + xml(book.hit().name()) + "</dc:title><dc:creator>" + xml(book.hit().author()) + "</dc:creator><dc:language>und</dc:language></metadata><manifest>" + manifest + "</manifest><spine toc=\"toc\">" + spine + "</spine></package>");
            entry(zip, "OEBPS/toc.ncx", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head><meta name=\"dtb:uid\" content=\"" + uid + "\"/></head><docTitle><text>" + xml(book.hit().name()) + "</text></docTitle><navMap>" + nav + "</navMap></ncx>");
            zip.finish(); zip.flush();
        }
    }

    private static List<String> paragraphs(String html) {
        StringBuilder plain = new StringBuilder();
        for (Node node : Jsoup.parseBodyFragment(html).body().childNodes()) appendText(node, plain);
        List<String> result = new ArrayList<>();
        for (String line : plain.toString().split("\\R")) {
            String trimmed = stripWhitespace(line);
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static void appendText(Node node, StringBuilder out) {
        if (node instanceof TextNode text) { out.append(text.getWholeText()); return; }
        if (node instanceof Element element && element.normalName().equals("br")) { out.append('\n'); return; }
        if (node instanceof Element element && element.isBlock() && out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        for (Node child : node.childNodes()) appendText(child, out);
        if (node instanceof Element element && element.isBlock()) out.append('\n');
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static void requireActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
    }

    private static String xml(String value) {
        return cleanXml(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cleanXml(String value) {
        String input = nullToEmpty(value);
        StringBuilder clean = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length();) {
            int code = Character.codePointAt(input, offset);
            int width = Character.charCount(code);
            if (code == 9 || code == 10 || code == 13 ||
                    code >= 0x20 && code <= 0xD7FF || code >= 0xE000 && code <= 0xFFFD ||
                    code >= 0x10000 && code <= 0x10FFFF)
                clean.append(input, offset, offset + width);
            offset += width;
        }
        return clean.toString();
    }

    private static String stripWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int code = Character.codePointAt(value, start);
            if (!Character.isWhitespace(code)) break;
            start += Character.charCount(code);
        }
        while (end > start) {
            int code = Character.codePointBefore(value, end);
            if (!Character.isWhitespace(code)) break;
            end -= Character.charCount(code);
        }
        return value.substring(start, end);
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
