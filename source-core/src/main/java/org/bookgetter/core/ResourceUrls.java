package org.bookgetter.core;

import java.net.URI;

/** The address types the explicit resource downloader can request. */
public final class ResourceUrls {
    private ResourceUrls() {}

    public static boolean isHttp(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
