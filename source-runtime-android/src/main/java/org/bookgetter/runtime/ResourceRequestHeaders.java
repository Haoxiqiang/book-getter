package org.bookgetter.runtime;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bookgetter.core.Models.Source;

/** Applies source headers to resource requests without sending credentials to another origin. */
final class ResourceRequestHeaders {
    private ResourceRequestHeaders() {}

    static Map<String, String> forUrl(Source source, String target) {
        Map<String, String> configured = new LinkedHashMap<>();
        String raw = source.string("header");
        if (raw == null || raw.isBlank()) return configured;
        try {
            JsonParser.parseString(raw).getAsJsonObject().entrySet().forEach(entry -> {
                configured.put(entry.getKey(), entry.getValue().getAsString());
            });
        } catch (Exception ignored) { /* Malformed optional headers cannot authorize cross-origin disclosure. */ }
        return RedirectRequestHeaders.forTarget(configured, source.id(), target);
    }
}
