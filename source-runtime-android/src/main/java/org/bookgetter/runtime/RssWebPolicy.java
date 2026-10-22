package org.bookgetter.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Browser filters retain prefix-or-full-match and blacklist precedence in the RE2/J linear subset. */
public final class RssWebPolicy {
    private final List<Filter> blacklist;
    private final List<Filter> whitelist;
    public int invalidPatternCount() {
        return (int) java.util.stream.Stream.concat(blacklist.stream(), whitelist.stream())
                .filter(filter -> filter.regex == null).count();
    }
    public RssWebPolicy(String blacklist, String whitelist) {
        this.blacklist = filters(blacklist);
        this.whitelist = filters(whitelist);
    }
    public boolean permitsResource(String url, BooleanSupplier cancelled) {
        if (!blacklist.isEmpty()) return !matches(blacklist, url, cancelled);
        return whitelist.isEmpty() || matches(whitelist, url, cancelled);
    }
    private static boolean matches(List<Filter> filters, String url, BooleanSupplier cancelled) {
        if (url == null || url.length() > LinearRegex.MAX_INPUT_CHARS)
            throw new IllegalArgumentException("Resource URL exceeds matching length limit");
        long started = System.nanoTime();
        for (Filter filter : filters) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new IllegalArgumentException("Resource filter cancelled");
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long remaining = LinearRegex.DEFAULT_TIMEOUT_MILLIS - elapsedMillis;
            if (remaining <= 0) throw new IllegalArgumentException("Resource filter exceeded time budget");
            if (url.startsWith(filter.text)) return true;
            if (filter.regex != null && filter.regex.matches(url, remaining, cancelled)) return true;
        }
        return false;
    }
    private static List<Filter> filters(String value) {
        List<Filter> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        if (value.length() > 65_536) throw new IllegalArgumentException("Resource filter list is too large");
        for (String text : value.split(",")) {
            if (text.isBlank()) continue;
            if (result.size() >= 128) throw new IllegalArgumentException("Too many resource filters");
            String trimmed = text.trim();
            LinearRegex regex;
            try { regex = new LinearRegex(trimmed); }
            catch (LinearRegex.UnsupportedPatternException invalid) { regex = null; }
            result.add(new Filter(trimmed, regex));
        }
        return result;
    }
    private record Filter(String text, LinearRegex regex) {}

    public static boolean permitsBridge(java.util.Set<String> allowed, String currentOrigin,
                                        String senderUrl, boolean mainFrame) {
        String sender = origin(senderUrl);
        return mainFrame && !sender.isEmpty() && sender.equals(currentOrigin) && allowed.contains(sender);
    }

    /** Returns a canonical exact HTTP(S) origin; credentials and opaque URLs are never authorized. */
    public static String origin(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null)
                return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port < 0 || port == (scheme.equals("https") ? 443 : 80);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException invalid) { return ""; }
    }
}
