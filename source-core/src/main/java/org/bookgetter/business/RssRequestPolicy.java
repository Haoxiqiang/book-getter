package org.bookgetter.business;

import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.RssModels.RssSource;
import org.bookgetter.core.SourcePolicy;

/** Preserve the browser's origin-filtered headers and mark their source-default provenance. */
public final class RssRequestPolicy {
    private RssRequestPolicy() {}
    public static FetchRequest forSource(RssSource source, FetchRequest request) {
        return new FetchRequest(request.url(), request.method(), request.headers(), request.body(), request.charset(),
                request.webView(), request.webViewDelayMillis(), request.followRedirects(), request.timeoutMillis(),
                SourcePolicy.of(source.executionSource()).withDefaultHeaders(request.headers()));
    }
}
