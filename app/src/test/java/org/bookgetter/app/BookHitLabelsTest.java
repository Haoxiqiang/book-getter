package org.bookgetter.app;

import static org.junit.Assert.*;
import java.util.Map;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.Kind;
import org.junit.Test;

public final class BookHitLabelsTest {
    @Test public void searchAndDetailShowOnlyPresentMetadata() {
        BookHit hit = new BookHit("source", "https://test/book", "Title", "Author", "", "",
                Kind.TEXT, "Fantasy", " 120万字 ", "Chapter 20", Map.of());
        assertEquals("Title · Author\n分类 Fantasy · 字数 120万字 · 最新 Chapter 20\nReadable Source",
                BookHitLabels.search(hit, "Readable Source"));
        assertEquals("\n分类 Fantasy · 字数 120万字 · 最新 Chapter 20", BookHitLabels.detail(hit));
        BookHit sparse = new BookHit("source", "https://test/book", "Title", "", "", "",
                Kind.TEXT, null, " ", null, Map.of());
        assertEquals("Title\nsource", BookHitLabels.search(sparse, null));
        assertEquals("", BookHitLabels.detail(sparse));
    }

    @Test public void longSearchMetadataIsCompactButDetailKeepsFullValue() {
        String latest = "https://example.test/book/123/chapter/456/very-long-path?source=fixture";
        BookHit hit = new BookHit("source", "https://test/book", "Title", "", "", "",
                Kind.TEXT, "", "", latest, Map.of());
        String search = BookHitLabels.search(hit, "Source");
        assertTrue(search.contains("最新 https://example.test/book/123/chapter/4…"));
        assertFalse(search.contains("source=fixture"));
        assertTrue(BookHitLabels.detail(hit).contains(latest));
    }
}
