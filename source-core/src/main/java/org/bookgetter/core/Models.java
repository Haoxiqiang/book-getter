package org.bookgetter.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}
    public enum Kind { TEXT, AUDIO, IMAGE, FILE, VIDEO }
    public enum Stage { IMPORT, SEARCH, DETAIL, CATALOG, CONTENT, DOWNLOAD, EXPORT }
    public enum Severity { INFO, WARNING, ERROR }

    public record Diagnostic(Stage stage, String code, String message, Severity severity, String field) {
        public Diagnostic(Stage stage, String code, String message, String field) {
            this(stage, code, message, Severity.WARNING, field);
        }
    }
    public record Source(String id, String name, Kind kind, JsonObject json) {
        public String string(String field) {
            return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
        }
        public JsonObject rules(String section) {
            if (!json.has(section)) return null;
            if (json.get(section).isJsonObject()) return json.getAsJsonObject(section);
            if (json.get(section).isJsonPrimitive()) {
                try { return JsonParser.parseString(json.get(section).getAsString()).getAsJsonObject(); }
                catch (Exception ignored) { return null; }
            }
            return null;
        }
        public String rule(String section, String field) {
            JsonObject object = rules(section);
            return object != null && object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
        }
        public boolean isJsSource() { return string("mainJs") != null && !string("mainJs").isBlank(); }
    }
    public record ImportResult(List<Source> sources, List<String> sourceUrls, List<Diagnostic> diagnostics) {}
    public record ExploreEntry(String title, String url) {}
    public record BookHit(String sourceId, String url, String name, String author, String intro, String coverUrl,
                          Kind kind, String genre, String wordCount, String lastChapter,
                          Map<String, String> variables) {
        public BookHit {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
        public BookHit(String sourceId, String url, String name, String author, String intro, String coverUrl, Kind kind) {
            this(sourceId, url, name, author, intro, coverUrl, kind, "", "", "", Map.of());
        }
        public BookHit(String sourceId, String url, String name, String author, String intro, String coverUrl,
                       Kind kind, String genre, String wordCount, String lastChapter) {
            this(sourceId, url, name, author, intro, coverUrl, kind, genre, wordCount, lastChapter, Map.of());
        }
    }
    public record BookDetail(BookHit hit, String tocUrl, List<String> downloadUrls) {}
    public record Chapter(String bookUrl, String url, String title, int index, boolean volume, boolean vip,
                          boolean isPay) {
        public Chapter(String bookUrl, String url, String title, int index, boolean volume, boolean vip) {
            this(bookUrl, url, title, index, volume, vip, false);
        }
    }
    public record Resource(String url, String title, String mimeHint) {}
    public record ChapterContent(Chapter chapter, Kind kind, String text, List<Resource> resources) {}
    public record FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                               boolean webView, long webViewDelayMillis, boolean followRedirects,
                               long timeoutMillis, SourcePolicy policy) {
        public FetchRequest {
            if (timeoutMillis < 0) throw new IllegalArgumentException("Negative request timeout");
            headers = Map.copyOf(headers);
        }
        public FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                            boolean webView, long webViewDelayMillis, boolean followRedirects) {
            this(url, method, headers, body, charset, webView, webViewDelayMillis, followRedirects, 0, null);
        }
        public FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                            boolean webView, long webViewDelayMillis, boolean followRedirects, long timeoutMillis) {
            this(url, method, headers, body, charset, webView, webViewDelayMillis, followRedirects, timeoutMillis, null);
        }
        public FetchRequest forSource(Source source) {
            return new FetchRequest(url, method, headers, body, charset, webView, webViewDelayMillis,
                    followRedirects, timeoutMillis, SourcePolicy.of(source));
        }
        public FetchRequest withTimeout(long timeout) {
            return new FetchRequest(url, method, headers, body, charset, webView, webViewDelayMillis,
                    followRedirects, timeout, policy);
        }
        public FetchRequest(String url) { this(url, "GET", Map.of(), null, null, false, 0, true); }
        public FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                            boolean webView) { this(url, method, headers, body, charset, webView, 0, true); }
        public FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                            boolean webView, long webViewDelayMillis) {
            this(url, method, headers, body, charset, webView, webViewDelayMillis, true);
        }
    }
    public record FetchResponse(String url, String body, int status, Map<String, String> headers) {}
    public record StageTrace(Stage stage, String sourceId, String field, String method,
                             String requestUrl, String responseUrl, int status, String responseQuality) {}
    public record SearchResult(List<BookHit> hits, Map<String, String> errors,
                               Map<String, SourceFailure> failures) {
        public SearchResult(List<BookHit> hits, Map<String, String> errors) {
            this(hits, errors, Map.of());
        }
    }
    public record CaptureReport(List<ChapterContent> chapters, Map<Integer, String> failures) {}

    public static final class SourceFailure extends RuntimeException {
        public final Stage stage;
        public final String sourceId;
        public final String field;
        public final String code;
        public SourceFailure(Stage stage, String sourceId, String field, String message, Throwable cause) {
            this(stage, sourceId, field, "RULE_ERROR", message, cause);
        }
        public SourceFailure(Stage stage, String sourceId, String field, String message) {
            this(stage, sourceId, field, message, (Throwable) null);
        }
        public SourceFailure(Stage stage, String sourceId, String field, String code, String message) {
            this(stage, sourceId, field, code, message, null);
        }
        public SourceFailure(Stage stage, String sourceId, String field, String code, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage; this.sourceId = sourceId; this.field = field; this.code = code;
        }
    }
}
