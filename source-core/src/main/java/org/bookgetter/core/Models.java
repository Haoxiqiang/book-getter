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
    public record BookHit(String sourceId, String url, String name, String author, String intro, String coverUrl,
                          Kind kind, String genre, String wordCount, String lastChapter) {
        public BookHit(String sourceId, String url, String name, String author, String intro, String coverUrl, Kind kind) {
            this(sourceId, url, name, author, intro, coverUrl, kind, "", "", "");
        }
    }
    public record BookDetail(BookHit hit, String tocUrl, List<String> downloadUrls) {}
    public record Chapter(String bookUrl, String url, String title, int index, boolean volume, boolean vip) {}
    public record Resource(String url, String title, String mimeHint) {}
    public record ChapterContent(Chapter chapter, Kind kind, String text, List<Resource> resources) {}
    public record FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                               boolean webView, long webViewDelayMillis) {
        public FetchRequest(String url) { this(url, "GET", Map.of(), null, null, false, 0); }
        public FetchRequest(String url, String method, Map<String, String> headers, String body, String charset,
                            boolean webView) { this(url, method, headers, body, charset, webView, 0); }
    }
    public record FetchResponse(String url, String body, int status, Map<String, String> headers) {}
    public record SearchResult(List<BookHit> hits, Map<String, String> errors) {}
    public record CaptureReport(List<ChapterContent> chapters, Map<Integer, String> failures) {}

    public static final class SourceFailure extends RuntimeException {
        public final Stage stage;
        public final String sourceId;
        public final String field;
        public SourceFailure(Stage stage, String sourceId, String field, String message, Throwable cause) {
            super(message, cause); this.stage = stage; this.sourceId = sourceId; this.field = field;
        }
        public SourceFailure(Stage stage, String sourceId, String field, String message) { this(stage, sourceId, field, message, null); }
    }
}
