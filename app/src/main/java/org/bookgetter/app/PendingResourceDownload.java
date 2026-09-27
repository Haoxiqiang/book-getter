package org.bookgetter.app;

import com.google.gson.Gson;
import java.util.List;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.ResourceUrls;

/** Stable resource choice across the system document picker and Activity recreation. */
record PendingResourceDownload(String sourceId, String sourceHash, List<Resource> resources) {
    private static final Gson JSON = new Gson();

    PendingResourceDownload {
        if (sourceId == null || sourceId.isBlank() || sourceHash == null || sourceHash.isBlank()
                || resources == null || resources.isEmpty() || resources.stream().anyMatch(resource ->
                resource == null || !ResourceUrls.isHttp(resource.url())))
            throw new IllegalArgumentException("Incomplete resource selection");
        resources = List.copyOf(resources);
    }

    static PendingResourceDownload forSource(Source source, List<Resource> resources) {
        return new PendingResourceDownload(source.id(), ChapterStore.sourceHash(source), resources);
    }

    String serialize() { return JSON.toJson(this); }

    static PendingResourceDownload restore(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            PendingResourceDownload parsed = JSON.fromJson(json, PendingResourceDownload.class);
            return new PendingResourceDownload(parsed.sourceId(), parsed.sourceHash(), parsed.resources());
        } catch (RuntimeException invalid) { return null; }
    }
}
