package org.bookgetter.core;

import org.bookgetter.core.Models.Source;
import java.util.Map;

/** Network and state identity; BookSource and RssSource keys occupy separate namespaces. */
public record SourcePolicy(String model, String id, String rate, Map<String, String> defaultHeaders) {
    public SourcePolicy(String model, String id, String rate) {
        this(model, id, rate, Map.of());
    }
    public SourcePolicy {
        if (!("book".equals(model) || "rss".equals(model)) || id == null || id.isBlank())
            throw new IllegalArgumentException("Invalid source identity");
        defaultHeaders = Map.copyOf(defaultHeaders);
    }
    /** Header provenance only: URL options with the same name must already be excluded. */
    public SourcePolicy withDefaultHeaders(Map<String, String> headers) {
        return new SourcePolicy(model, id, rate, headers);
    }
    public static SourcePolicy of(Source source) {
        if (source == null) return null;
        return new SourcePolicy(source.json().has("sourceUrl") && !source.json().has("bookSourceUrl")
                ? "rss" : "book", source.id(), source.string("concurrentRate"));
    }
    public String stateKey() { return model + "\u0000" + id; }
}
