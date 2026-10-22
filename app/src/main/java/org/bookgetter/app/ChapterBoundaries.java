package org.bookgetter.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.Chapter;

/** Indexes the next readable URL once while preserving indexOf's first-match semantics. */
final class ChapterBoundaries {
    private final Map<Chapter, String> next = new HashMap<>();

    ChapterBoundaries(List<Chapter> catalog) {
        String following = null;
        for (int index = catalog.size() - 1; index >= 0; index--) {
            Chapter chapter = catalog.get(index);
            next.put(chapter, following);
            if (!chapter.volume()) following = chapter.url();
        }
    }

    String after(Chapter chapter) { return next.get(chapter); }
}
