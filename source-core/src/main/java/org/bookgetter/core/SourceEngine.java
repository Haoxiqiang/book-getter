package org.bookgetter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bookgetter.core.Models.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Stateless capture pipeline; callers own persistence, scheduling and user interaction. */
public final class SourceEngine {
    private final Transport transport;
    private final ScriptRuntime scripts;
    private final RequestPlanner requests;
    private final RuleEvaluator rules;

    public SourceEngine(Transport transport) { this(transport, new RhinoRuntime(transport)); }
    public SourceEngine(Transport transport, ScriptRuntime scripts) {
        this.transport = transport; this.scripts = scripts; requests = new RequestPlanner(scripts); rules = new RuleEvaluator(scripts);
    }

    public List<BookHit> search(Source source, String keyword, int page) {
        try {
            if (source.isJsSource()) return jsSearch(source, keyword, page);
            FetchResponse response;
            try { response = fetch(requests.plan(source.string("searchUrl"), source, keyword, page, source.id())); }
            catch (SourceFailure e) { throw e; }
            catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(), "searchUrl", e.getMessage(), e); }
            if (ResponseSignals.quality(response).equals("BUSINESS_ERROR"))
                throw new SourceFailure(Stage.SEARCH, source.id(), "searchUrl",
                        "Source returned a business error despite HTTP " + response.status());
            String listRule = source.rule("ruleSearch", "bookList");
            List<Object> items = rules.many(listRule, response.body(), response.url(), source);
            List<BookHit> hits = new ArrayList<>();
            for (Object item : items) {
                String name = field(source, "ruleSearch", "name", item, response.url(), false);
                String url = field(source, "ruleSearch", "bookUrl", item, response.url(), true);
                if (name.isBlank() || url.isBlank()) continue;
                hits.add(new BookHit(source.id(), url, name,
                        field(source, "ruleSearch", "author", item, response.url(), false),
                        field(source, "ruleSearch", "intro", item, response.url(), false),
                        field(source, "ruleSearch", "coverUrl", item, response.url(), true), source.kind()));
            }
            return hits;
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(), "ruleSearch", e.getMessage(), e); }
    }

    public BookDetail detail(Source source, BookHit hit) {
        requireIdentity(source, hit.sourceId());
        rules.enterBook(hit.url());
        try {
            if (source.isJsSource()) return jsDetail(source, hit);
            FetchResponse response = fetch(requests.plan(hit.url(), source, "", 1, source.id()));
            if (ResponseSignals.quality(response).equals("BUSINESS_ERROR"))
                throw new SourceFailure(Stage.DETAIL, source.id(), "ruleBookInfo.init",
                        "Source returned a business error despite HTTP " + response.status());
            Object detailContent = response.body();
            String init = source.rule("ruleBookInfo", "init");
            if (init != null && !init.isBlank()) {
                try { detailContent = rules.element(init, detailContent, response.url(), source); }
                catch (Exception e) { throw new SourceFailure(Stage.DETAIL, source.id(), "ruleBookInfo.init", e.getMessage(), e); }
                if (detailContent == null) throw new SourceFailure(Stage.DETAIL, source.id(),
                        "ruleBookInfo.init", "Initialization rule matched no detail object");
            }
            String name = field(source, "ruleBookInfo", "name", detailContent, response.url(), false);
            String author = field(source, "ruleBookInfo", "author", detailContent, response.url(), false);
            String intro = field(source, "ruleBookInfo", "intro", detailContent, response.url(), false);
            String cover = field(source, "ruleBookInfo", "coverUrl", detailContent, response.url(), true);
            String toc = field(source, "ruleBookInfo", "tocUrl", detailContent, response.url(), true);
            if (toc.isBlank()) toc = response.url();
            // An optional direct-download rule may only apply to one subtype of a mixed source.
            // Its failure must not hide an otherwise usable text book's catalog.
            String download = "";
            if (source.rule("ruleBookInfo", "downloadUrls") != null) {
                try { download = field(source, "ruleBookInfo", "downloadUrls", detailContent, response.url(), true); }
                catch (SourceFailure ignored) { /* no direct-download links for this item */ }
            }
            BookHit enriched = new BookHit(hit.sourceId(), hit.url(), name.isBlank() ? hit.name() : name,
                    author.isBlank() ? hit.author() : author, intro.isBlank() ? hit.intro() : intro,
                    cover.isBlank() ? hit.coverUrl() : cover, hit.kind());
            return new BookDetail(enriched, toc, download.isBlank() ? List.of() : List.of(download.split("\\s+")));
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.DETAIL, source.id(), "ruleBookInfo", e.getMessage(), e); }
        finally { rules.leaveBook(); }
    }

    public List<Chapter> chapters(Source source, BookDetail book) {
        requireIdentity(source, book.hit().sourceId());
        if (source.kind() == Kind.FILE) return List.of();
        rules.enterBook(book.hit().url());
        try {
            if (source.isJsSource()) return jsChapters(source, book);
            List<Chapter> chapters = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> chapterUrls = new HashSet<>();
            String page = book.tocUrl();
            while (page != null && !page.isBlank() && visited.add(page) && visited.size() <= 50) {
                FetchResponse response = fetch(requests.plan(page, source, "", 1, book.hit().url()));
                for (Object item : rules.many(source.rule("ruleToc", "chapterList"), response.body(), response.url(), source)) {
                    String title = field(source, "ruleToc", "chapterName", item, response.url(), false);
                    String url = field(source, "ruleToc", "chapterUrl", item, response.url(), true);
                    if (title.isBlank()) continue;
                    boolean volume = ruleTrue(field(source, "ruleToc", "isVolume", item, response.url(), false));
                    boolean vip = ruleTrue(field(source, "ruleToc", "isVip", item, response.url(), false));
                    if (url.isBlank()) url = volume ? title + chapters.size() : response.url();
                    if (chapterUrls.add(url))
                        chapters.add(new Chapter(book.hit().url(), url, title, chapters.size(), volume, vip));
                }
                page = field(source, "ruleToc", "nextTocUrl", response.body(), response.url(), true);
            }
            if (chapters.isEmpty()) throw new SourceFailure(Stage.CATALOG, source.id(), "ruleToc.chapterList", "No chapters matched");
            return chapters;
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.CATALOG, source.id(), "ruleToc", e.getMessage(), e); }
        finally { rules.leaveBook(); }
    }

    public ChapterContent content(Source source, BookDetail book, Chapter chapter) {
        String pagination = source.rule("ruleContent", "nextContentUrl");
        if (!source.isJsSource() && pagination != null && !pagination.isBlank())
            throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.nextContentUrl",
                    "Content pagination requires the following chapter boundary; use content(source, book, chapter, nextChapterUrl)");
        return content(source, book, chapter, null);
    }

    /** The next real chapter is the pagination boundary; volume headings are not content pages. */
    public static String nextReadableUrl(List<Chapter> chapters, Chapter current) {
        int index = chapters.indexOf(current);
        if (index < 0) return null;
        for (int next = index + 1; next < chapters.size(); next++)
            if (!chapters.get(next).volume()) return chapters.get(next).url();
        return null;
    }

    /** Pass the catalog's following chapter URL so pagination cannot absorb that chapter. */
    public ChapterContent content(Source source, BookDetail book, Chapter chapter, String nextChapterUrl) {
        requireIdentity(source, book.hit().sourceId());
        rules.enterBook(book.hit().url());
        try {
            String text;
            if (source.isJsSource()) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("chapter", Map.of("url", chapter.url(), "title", chapter.title(), "index", chapter.index()));
                args.put("book", jsBook(book));
                args.put("nextChapterUrl", nextChapterUrl);
                text = String.valueOf(scripts.call(source.string("mainJs"), "getContent", args, source));
            }
            else {
                StringBuilder joined = new StringBuilder();
                String page = chapter.url();
                Set<String> visited = new HashSet<>();
                while (page != null && !page.isBlank() && visited.add(page) && visited.size() <= 50) {
                    FetchResponse response = fetch(requests.plan(page, source, "", 1, book.tocUrl()));
                    if (ResponseSignals.quality(response).equals("APP_UPGRADE_REQUIRED"))
                        throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content",
                                "Source requires an app upgrade; response is not chapter text");
                    String value = field(source, "ruleContent", "content", response.body(), response.url(), false);
                    if (invalidContent(value)) throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content", "Empty page content");
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(value);
                    String following = field(source, "ruleContent", "nextContentUrl", response.body(), response.url(), true);
                    page = following.equals(nextChapterUrl) ? "" : following;
                }
                text = joined.toString();
            }
            if (invalidContent(text)) throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content", "Empty chapter content");
            String replaceRegex = source.rule("ruleContent", "replaceRegex");
            if (!source.isJsSource() && replaceRegex != null && !replaceRegex.isBlank()) {
                String normalized = text.lines().map(String::trim).collect(java.util.stream.Collectors.joining("\n"));
                text = field(source, "ruleContent", "replaceRegex", normalized, chapter.url(), false);
                if (invalidContent(text)) throw new SourceFailure(Stage.CONTENT, source.id(),
                        "ruleContent.replaceRegex", "Replacement removed all chapter content");
            }
            if (source.kind() == Kind.TEXT) return new ChapterContent(chapter, Kind.TEXT, text, List.of());
            List<Resource> resources = new ArrayList<>();
            if (source.kind() == Kind.IMAGE) {
                for (Element image : Jsoup.parse(text, chapter.url()).select("img[src]")) {
                    String link = image.absUrl("src");
                    if (httpResource(link)) resources.add(new Resource(link, image.attr("alt"), "image/*"));
                }
                if (resources.isEmpty()) throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content",
                        "No valid image resources found");
            } else {
                String link = RuleEvaluator.absolute(chapter.url(), text.trim());
                if (!httpResource(link)) {
                    String sourceRegex = source.rule("ruleContent", "sourceRegex");
                    String field = sourceRegex == null || sourceRegex.isBlank()
                            ? "ruleContent.content" : "ruleContent.sourceRegex";
                    throw new SourceFailure(Stage.CONTENT, source.id(), field,
                            "No valid media URL; WebView resource sniffing is not implemented");
                }
                resources.add(new Resource(link, chapter.title(), null));
            }
            return new ChapterContent(chapter, source.kind(), null, resources);
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent", e.getMessage(), e); }
        finally { rules.leaveBook(); }
    }

    private List<BookHit> jsSearch(Source source, String keyword, int page) {
        JsonArray array = jsonArray(scripts.call(source.string("mainJs"), "search", Map.of("key", keyword, "page", page), source));
        List<BookHit> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String url = string(object, "bookUrl", "url");
            String name = string(object, "name");
            if (url.isBlank() || name.isBlank()) continue;
            result.add(new BookHit(source.id(), RuleEvaluator.absolute(source.id(), url), name,
                    string(object, "author"), string(object, "intro"),
                    absoluteIfPresent(source.id(), string(object, "coverUrl")), source.kind()));
        }
        return result;
    }

    private BookDetail jsDetail(Source source, BookHit hit) {
        Object returned = scripts.call(source.string("mainJs"), "getBookInfo", Map.of("book", jsBook(new BookDetail(hit, hit.url(), List.of()))), source);
        if (returned == null || String.valueOf(returned).isBlank()) return new BookDetail(hit, hit.url(), List.of());
        JsonObject object = jsonObject(returned);
        String toc = absoluteIfPresent(hit.url(), string(object, "tocUrl"));
        BookHit enriched = new BookHit(hit.sourceId(), hit.url(), orDefault(string(object, "name"), hit.name()),
                orDefault(string(object, "author"), hit.author()), orDefault(string(object, "intro"), hit.intro()),
                orDefault(absoluteIfPresent(hit.url(), string(object, "coverUrl")), hit.coverUrl()), hit.kind());
        return new BookDetail(enriched, toc.isBlank() ? hit.url() : toc,
                jsonStrings(object, "downloadUrls").stream().map(url -> RuleEvaluator.absolute(hit.url(), url)).toList());
    }

    private List<Chapter> jsChapters(Source source, BookDetail book) {
        JsonArray array = jsonArray(scripts.call(source.string("mainJs"), "getChapters", Map.of("book", jsBook(book)), source));
        List<Chapter> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String title = string(object, "title");
            String url = string(object, "url");
            if (title.isBlank() || url.isBlank()) continue;
            boolean volume = object.has("isVolume") && object.get("isVolume").getAsBoolean();
            boolean vip = object.has("isVip") && object.get("isVip").getAsBoolean();
            result.add(new Chapter(book.hit().url(), volume && url.equals(title) ? url : RuleEvaluator.absolute(book.tocUrl(), url),
                    title, result.size(), volume, vip));
        }
        return result;
    }

    private static String orDefault(String value, String fallback) { return value.isBlank() ? fallback : value; }
    /** Legado String?.isTrue: only explicit false/no/not/0/0.0 are false. */
    private static boolean ruleTrue(String value) {
        if (value == null || value.isBlank() || value.equals("null")) return false;
        return !value.trim().matches("(?i)^(?:false|no|not|0|0\\.0)$");
    }
    static boolean invalidContent(String text) {
        if (text == null) return true;
        String value = text.trim();
        return value.isEmpty() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("undefined")
                || (value.startsWith("当前版本过低") && value.contains("请升级到最新版"));
    }
    private static boolean httpResource(String link) {
        try {
            URI uri = URI.create(link);
            return (uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception ignored) { return false; }
    }
    private static String absoluteIfPresent(String base, String value) {
        return value.isBlank() ? "" : RuleEvaluator.absolute(base, value);
    }

    private String field(Source source, String section, String field, Object input, String base, boolean url) {
        String rule = source.rule(section, field);
        try { return rules.one(rule, input, base, source, url); }
        catch (Exception e) { throw new SourceFailure(stage(section), source.id(), section + "." + field, e.getMessage(), e); }
    }

    private FetchResponse fetch(FetchRequest request) throws Exception {
        FetchResponse response = transport.fetch(request);
        if (response.status() >= 300 && response.status() < 400)
            throw new IllegalStateException("Unresolved HTTP redirect " + response.status() + " at " + request.url());
        if (response.status() >= 400) throw new IllegalStateException("HTTP " + response.status() + " at " + request.url());
        return response;
    }

    private static void requireIdentity(Source source, String sourceId) {
        if (!source.id().equals(sourceId)) throw new SourceFailure(Stage.DETAIL, source.id(), null, "Book belongs to another source");
    }
    private static Stage stage(String section) { return switch (section) { case "ruleSearch" -> Stage.SEARCH; case "ruleBookInfo" -> Stage.DETAIL; case "ruleToc" -> Stage.CATALOG; default -> Stage.CONTENT; }; }
    private static JsonArray jsonArray(Object value) { return JsonParser.parseString(String.valueOf(value)).getAsJsonArray(); }
    private static JsonObject jsonObject(Object value) { return JsonParser.parseString(String.valueOf(value)).getAsJsonObject(); }
    private static String string(JsonObject object, String... keys) {
        for (String key : keys) if (object.has(key) && !object.get(key).isJsonNull()) return object.get(key).getAsString();
        return "";
    }
    private static List<String> jsonStrings(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) values.add(element.getAsString());
        return values;
    }
    private static Map<String, Object> jsBook(BookDetail book) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bookUrl", book.hit().url());
        value.put("tocUrl", book.tocUrl());
        value.put("name", book.hit().name());
        value.put("author", book.hit().author());
        value.put("intro", book.hit().intro());
        value.put("coverUrl", book.hit().coverUrl());
        return value;
    }
}
