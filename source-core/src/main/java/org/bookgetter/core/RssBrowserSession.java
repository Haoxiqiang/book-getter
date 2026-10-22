package org.bookgetter.core;

import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.FetchRequest;

/**
 * Host-owned RSS page session. Create, load and close on the host UI thread.
 * Implementations enforce origin/main-frame isolation and reject stale bridge messages.
 * Closing cancels pending work and prevents further callbacks.
 */
public interface RssBrowserSession extends AutoCloseable {
    record Config(String sourceId, String sourceUrl, Map<String, String> headers,
                         boolean enableJs, boolean cacheFirst, String preloadJs, String injectJs,
                         String startHtml, String startJs, String style, String startStyle,
                         String contentWhitelist, String contentBlacklist, Map<String, String> variables) {
        public Config {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("RSS source identity is required");
        }
    }
    interface Host {
        /** Runs on the ordered worker. UI actions must be posted to the host UI thread. */
        String dispatch(String method, List<String> arguments) throws Exception;
        /** Runs on the same worker after all prior put messages. True consumes the navigation. */
        default boolean shouldOverride(String url) throws Exception { return false; }
        /** Prepares a normal browser GET without performing an extra fetch. Runs on the ordered worker. */
        default FetchRequest prepare(FetchRequest request) throws Exception { return request; }
        /** Runs on the host UI thread. */
        void onDiagnostic(String field, String code, String message);
    }

    void loadUrl(String url);
    void loadStartPage();
    void loadHtml(String html, String baseUrl, boolean startPage);
    @Override void close();
}
