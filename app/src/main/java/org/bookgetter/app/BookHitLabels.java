package org.bookgetter.app;

import java.util.ArrayList;
import java.util.List;
import org.bookgetter.core.Models.BookHit;

/** Short, empty-safe labels for search results and the opened book. */
final class BookHitLabels {
    private BookHitLabels() {}

    static String search(BookHit hit, String sourceName) {
        StringBuilder label = new StringBuilder(hit.name());
        if (has(hit.author())) label.append(" · ").append(hit.author().trim());
        List<String> metadata = metadata(hit, true);
        if (!metadata.isEmpty()) label.append("\n").append(String.join(" · ", metadata));
        label.append("\n").append(has(sourceName) ? sourceName.trim() : hit.sourceId());
        return label.toString();
    }

    static String detail(BookHit hit) {
        List<String> metadata = metadata(hit, false);
        return metadata.isEmpty() ? "" : "\n" + String.join(" · ", metadata);
    }

    private static List<String> metadata(BookHit hit, boolean compact) {
        List<String> result = new ArrayList<>(3);
        if (has(hit.genre())) result.add("分类 " + value(hit.genre(), compact));
        if (has(hit.wordCount())) result.add("字数 " + value(hit.wordCount(), compact));
        if (has(hit.lastChapter())) result.add("最新 " + value(hit.lastChapter(), compact));
        return result;
    }

    private static String value(String text, boolean compact) {
        String cleaned = text.trim().replaceAll("\\s+", " ");
        if (!compact || cleaned.codePointCount(0, cleaned.length()) <= 40) return cleaned;
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, 39)) + "…";
    }

    private static boolean has(String value) { return value != null && !value.isBlank(); }
}
