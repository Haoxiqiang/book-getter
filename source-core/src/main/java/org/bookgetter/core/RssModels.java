package org.bookgetter.core;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** RSS identities and media types are independent of ordinary book sources. */
public final class RssModels {
    private RssModels() {}
    public enum RssKind { WEB, IMAGE, VIDEO }
    public record RssSource(String id, String name, RssKind kind, JsonObject json) {
        public String string(String field) {
            return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
        }
        public boolean flag(String field, boolean fallback) {
            return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsBoolean() : fallback;
        }
        public Models.Source executionSource() {
            return new Models.Source(id, name, switch (kind) {
                case WEB -> Models.Kind.TEXT;
                case IMAGE -> Models.Kind.IMAGE;
                case VIDEO -> Models.Kind.VIDEO;
            }, json.deepCopy());
        }
    }
    public record Category(String name, String url) {}
    public record Article(String origin, String sort, String title, String link, String pubDate,
                          String description, String content, String image, RssKind kind,
                          Map<String, String> variables) {
        public Article { variables = variables == null ? Map.of() : Map.copyOf(variables); }
        public String identity() { return origin + "\u0000" + link + "\u0000" + sort; }
    }
    public record Page(List<Article> articles, String nextPageUrl, int page, String responseUrl) {
        public Page { articles = List.copyOf(articles); }
    }
    public sealed interface ReadTarget permits HtmlContent, BrowserPage, Images, Video {}
    public record HtmlContent(String html, String baseUrl) implements ReadTarget {}
    public record BrowserPage(String url) implements ReadTarget {}
    public record Images(List<Models.Resource> resources) implements ReadTarget {
        public Images { resources = List.copyOf(resources); }
    }
    public record Video(String url) implements ReadTarget {}
}
