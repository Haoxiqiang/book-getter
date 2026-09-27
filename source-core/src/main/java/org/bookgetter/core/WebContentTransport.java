package org.bookgetter.core;

import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;

/** Optional browser operations used by content rules, separate from ordinary HTTP fetching. */
public interface WebContentTransport extends Transport {
    /** Browser loaded the page, but no requested resource matched the source's capture rule. */
    final class NoMatchingResourceException extends Exception {
        public NoMatchingResourceException() { super("No browser resource matched sourceRegex before timeout"); }
    }

    /** WebView reports a main-frame HTTP error outside its page-finished callback. */
    final class HttpStatusException extends Exception {
        private final int status;
        private final String url;
        public HttpStatusException(int status, String url) {
            super("Browser HTTP " + status);
            this.status = status;
            this.url = url;
        }
        public int status() { return status; }
        public String url() { return url; }
    }

    FetchResponse fetchContent(FetchRequest request, String sourceRegex, String webJs) throws Exception;
}
