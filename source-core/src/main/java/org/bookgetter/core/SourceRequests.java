package org.bookgetter.core;

import com.google.gson.JsonParser;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bookgetter.core.Models.*;

/** Shared source request policy for stages and nested script requests. */
public final class SourceRequests {
    private record Rate(int count, long intervalMillis) {}
    private static final class Window { long started; int used; String rate; }
    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();
    private SourceRequests() {}

    public static FetchRequest prepare(FetchRequest request, SourceStateStore states) throws InterruptedIOException {
        long start = System.nanoTime();
        SourcePolicy policy = request.policy();
        if (policy != null) await(policy, request.timeoutMillis());
        long remaining = request.timeoutMillis();
        if (remaining > 0) {
            remaining -= TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (remaining <= 0) throw new InterruptedIOException("Request timeout while waiting for source rate");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (policy != null) policy.defaultHeaders().forEach((name, value) -> putHeader(headers, name, value));
        // Source login state is never implicitly attached to a different origin.
        if (policy != null && sameOrigin(policy.id(), request.url())) {
            String saved = states.get(policy.stateKey(), "loginHeader");
            if (!saved.isEmpty()) {
                try { JsonParser.parseString(saved).getAsJsonObject().entrySet().forEach(entry ->
                        putHeader(headers, entry.getKey(), entry.getValue().getAsString())); }
                catch (RuntimeException invalid) { throw new IllegalStateException("Invalid saved login headers"); }
            }
        }
        request.headers().forEach((name, value) -> {
            boolean inherited = policy != null && policy.defaultHeaders().keySet().stream()
                    .anyMatch(key -> key.equalsIgnoreCase(name));
            if (!inherited) putHeader(headers, name, value);
        });
        return new FetchRequest(request.url(), request.method(), headers, request.body(), request.charset(),
                request.webView(), request.webViewDelayMillis(), request.followRedirects(), remaining, policy);
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    static boolean sameOrigin(String first, String second) {
        try {
            URI a = originUri(first), b = originUri(second);
            return a.getHost() != null && b.getHost() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
        } catch (RuntimeException invalid) { return false; }
    }
    private static URI originUri(String value) {
        int fragment = value.indexOf('#');
        return URI.create(fragment < 0 ? value : value.substring(0, fragment));
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }

    private static Rate parse(String text) {
        if (text == null || text.isBlank() || text.trim().equals("0")) return null;
        String[] parts = text.trim().split("/", -1);
        try {
            int count = parts.length == 1 ? 1 : Integer.parseInt(parts[0]);
            long interval = Long.parseLong(parts[parts.length - 1]);
            if (parts.length > 2 || count <= 0 || interval <= 0 || interval > TimeUnit.DAYS.toMillis(1))
                throw new NumberFormatException();
            return new Rate(count, interval);
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid concurrentRate"); }
    }

    private static void await(SourcePolicy policy, long timeoutMillis) throws InterruptedIOException {
        Rate rate = parse(policy.rate());
        if (rate == null) return;
        Window window = WINDOWS.computeIfAbsent(policy.stateKey(), ignored -> new Window());
        long started = System.nanoTime();
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source request cancelled");
            long wait;
            synchronized (window) {
                long now = System.nanoTime();
                long elapsed = now - window.started;
                long interval = TimeUnit.MILLISECONDS.toNanos(rate.intervalMillis());
                if (!java.util.Objects.equals(window.rate, policy.rate()) || elapsed >= interval || window.started == 0) {
                    window.rate = policy.rate(); window.started = now; window.used = 0;
                }
                if (window.used < rate.count()) { window.used++; return; }
                wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(interval - (now - window.started)));
            }
            if (timeoutMillis > 0) {
                long left = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (left <= 0) throw new InterruptedIOException("Source rate wait timed out");
                wait = Math.min(wait, left);
            }
            try { Thread.sleep(Math.min(wait, 100)); }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("Source request cancelled");
            }
        }
    }
}
