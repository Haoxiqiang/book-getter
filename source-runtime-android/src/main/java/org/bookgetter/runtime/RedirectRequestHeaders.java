package org.bookgetter.runtime;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps source credentials on their original origin when an HTTP request redirects. */
final class RedirectRequestHeaders {
    private static final Set<String> CROSS_ORIGIN = Set.of("user-agent", "accept", "accept-language", "range");
    private RedirectRequestHeaders() {}

    static Map<String, String> forTarget(Map<String, String> original, String originUrl, String targetUrl) {
        if (sameOrigin(originUrl, targetUrl)) return new LinkedHashMap<>(original);
        Map<String, String> filtered = new LinkedHashMap<>();
        original.forEach((name, value) -> {
            if (CROSS_ORIGIN.contains(name.toLowerCase(Locale.ROOT))) filtered.put(name, value);
            else if (name.equalsIgnoreCase("Referer")) {
                String origin = originOnly(value);
                if (!origin.isEmpty()) filtered.put(name, origin);
            }
        });
        return filtered;
    }

    private static boolean sameOrigin(String firstUrl, String secondUrl) {
        try {
            URI first = URI.create(firstUrl);
            URI second = URI.create(secondUrl);
            return first.getScheme() != null && second.getScheme() != null
                    && first.getScheme().equalsIgnoreCase(second.getScheme())
                    && first.getHost() != null && first.getHost().equalsIgnoreCase(second.getHost())
                    && effectivePort(first) == effectivePort(second);
        } catch (Exception ignored) { return false; }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String originOnly(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null ||
                    !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + "/";
        } catch (Exception ignored) { return ""; }
    }
}
