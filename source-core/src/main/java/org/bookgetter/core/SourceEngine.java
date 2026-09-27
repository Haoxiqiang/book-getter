package org.bookgetter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bookgetter.core.Models.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Stateless capture pipeline; callers own persistence, scheduling and user interaction. */
public final class SourceEngine {
    private static final int MAX_PAGES = 50;
    private final Transport transport;
    private final ScriptRuntime scripts;
    private final RequestPlanner requests;
    private final RuleEvaluator rules;
    private final Consumer<Diagnostic> diagnostics;
    private final Consumer<StageTrace> traces;

    public SourceEngine(Transport transport) { this(transport, new RhinoRuntime(transport)); }
    public SourceEngine(Transport transport, ScriptRuntime scripts) {
        this(transport, scripts, ignored -> {});
    }
    public SourceEngine(Transport transport, ScriptRuntime scripts, Consumer<Diagnostic> diagnostics) {
        this(transport, scripts, diagnostics, ignored -> {});
    }
    public SourceEngine(Transport transport, ScriptRuntime scripts, Consumer<Diagnostic> diagnostics,
                        Consumer<StageTrace> traces) {
        this.transport = transport; this.scripts = scripts; this.diagnostics = diagnostics; this.traces = traces;
        requests = new RequestPlanner(scripts); rules = new RuleEvaluator(scripts);
    }

    /** Legado strips list-control prefixes before sending the rule to AnalyzeRule. */
    private List<Object> listItems(String rule, Object input, String baseUrl, Source source) {
        if (rule == null || rule.isEmpty()) return List.of();
        boolean reverse = rule.startsWith("-");
        String selector = reverse ? rule.substring(1) : rule;
        if (selector.startsWith("+")) selector = selector.substring(1);
        List<Object> items = rules.many(selector, input, baseUrl, source);
        if (!reverse) return items;
        List<Object> ordered = new ArrayList<>(items);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    public List<BookHit> search(Source source, String keyword, int page) {
        try {
            if (source.isJsSource()) return jsSearch(source, keyword, page);
            FetchResponse response;
            try { response = fetch(plan(source.string("searchUrl"), source, keyword, page, source.id(),
                            Stage.SEARCH, "searchUrl"),
                    Stage.SEARCH, source.id(), "searchUrl"); }
            catch (SourceFailure e) { throw e; }
            catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(), "searchUrl", e.getMessage(), e); }
            String bookUrlPattern = source.string("bookUrlPattern");
            if (bookUrlPattern != null && !bookUrlPattern.isEmpty()) {
                try {
                    if (Pattern.compile(bookUrlPattern).matcher(response.url()).matches()) {
                        diagnostics.accept(new Diagnostic(Stage.SEARCH, "DIRECT_DETAIL_URL_PATTERN",
                                "Final search URL matches bookUrlPattern; parsing detail rules",
                                Severity.INFO, "bookUrlPattern"));
                        return directDetailSearch(source, response);
                    }
                } catch (PatternSyntaxException e) {
                    throw new SourceFailure(Stage.SEARCH, source.id(), "bookUrlPattern", e.getMessage(), e);
                }
            }
            String listRule = source.rule("ruleSearch", "bookList");
            List<Object> items = listItems(listRule, response.body(), response.url(), source);
            if (items.isEmpty() && (bookUrlPattern == null || bookUrlPattern.isEmpty())) {
                diagnostics.accept(new Diagnostic(Stage.SEARCH, "DIRECT_DETAIL_EMPTY_LIST",
                        "Search list is empty and no bookUrlPattern is set; trying detail rules",
                        Severity.INFO, "ruleSearch.bookList"));
                return directDetailSearch(source, response);
            }
            return parseHits(source, "ruleSearch", items, response.url());
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(), "ruleSearch", e.getMessage(), e); }
    }

    /** Resolve the source's discoverable categories before fetching a selected category. */
    public List<ExploreEntry> exploreEntries(Source source) {
        String raw = source.string("exploreUrl");
        if (raw == null || raw.isBlank()) return List.of();
        try {
            String value = raw.trim();
            if (value.startsWith("@js:")) value = String.valueOf(scripts.evaluate(value.substring(4), Map.of(), source));
            else if (value.startsWith("<js>") && value.endsWith("</js>"))
                value = String.valueOf(scripts.evaluate(value.substring(4, value.length() - 5), Map.of(), source));
            List<ExploreEntry> entries = new ArrayList<>();
            if (value.startsWith("[")) {
                for (JsonElement item : JsonParser.parseString(value).getAsJsonArray()) {
                    JsonObject object = item.getAsJsonObject();
                    String type = string(object, "type");
                    if (!type.isBlank() && !type.equals("url"))
                        throw new SourceFailure(Stage.SEARCH, source.id(), "exploreUrl", "UNSUPPORTED_EXPLORE_FILTER",
                                "Discovery category requires the exploreScreen filter UI: " + type);
                    String url = string(object, "url");
                    if (!url.isBlank()) entries.add(new ExploreEntry(orDefault(string(object, "title"), url), url));
                }
            } else {
                for (String part : value.split("&&|\\r?\\n")) {
                    String item = part.trim();
                    if (item.isEmpty()) continue;
                    int separator = item.indexOf("::");
                    if (separator < 0)
                        throw new SourceFailure(Stage.SEARCH, source.id(), "exploreUrl", "MISSING_EXPLORE_URL",
                                "Discovery category has no URL: " + item);
                    entries.add(new ExploreEntry(item.substring(0, separator).trim(), item.substring(separator + 2).trim()));
                }
            }
            return entries;
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) {
            throw new SourceFailure(Stage.SEARCH, source.id(), "exploreUrl", "INVALID_EXPLORE",
                    "Cannot parse discovery categories: " + e.getMessage(), e);
        }
    }

    public List<BookHit> explore(Source source, ExploreEntry entry, int page) {
        try {
            if (source.isJsSource()) return jsHits(source, "explore", Map.of("url", entry.url(), "page", page));
            FetchResponse response = fetch(plan(entry.url(), source, "", page, source.id(),
                            Stage.SEARCH, "exploreUrl"),
                    Stage.SEARCH, source.id(), "exploreUrl");
            String section = source.rule("ruleExplore", "bookList") == null ? "ruleSearch" : "ruleExplore";
            List<Object> items = listItems(source.rule(section, "bookList"), response.body(), response.url(), source);
            if (items.isEmpty()) return directDetailSearch(source, response);
            return parseHits(source, section, items, response.url());
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(), "ruleExplore", e.getMessage(), e); }
    }

    private List<BookHit> parseHits(Source source, String section, List<Object> items, String baseUrl) {
        List<BookHit> hits = new ArrayList<>();
        for (Object item : items) {
            rules.enterSearchItem();
            try {
                String name = field(source, section, "name", item, baseUrl, false);
                String url = field(source, section, "bookUrl", item, baseUrl, true);
                if (name.isBlank() || url.isBlank()) continue;
                String author = field(source, section, "author", item, baseUrl, false);
                String intro = optionalField(source, section, "intro", item, baseUrl, false);
                String cover = optionalField(source, section, "coverUrl", item, baseUrl, true);
                String genre = optionalField(source, section, "kind", item, baseUrl, false);
                String wordCount = optionalField(source, section, "wordCount", item, baseUrl, false);
                String lastChapter = optionalField(source, section, "lastChapter", item, baseUrl, false);
                hits.add(new BookHit(source.id(), url, name, author, intro, cover, source.kind(),
                        genre, wordCount, lastChapter, rules.searchItemVariables()));
            } finally { rules.leaveSearchItem(); }
        }
        return hits;
    }

    /** Legado treats a matching final search URL, or an empty list without a pattern, as a detail page. */
    private List<BookHit> directDetailSearch(Source source, FetchResponse response) {
        rules.enterBook(source, response.url(), Map.of());
        scripts.enterBook(Map.of("bookUrl", response.url()));
        try {
            Object detail = response.body();
            String init = source.rule("ruleBookInfo", "init");
            if (init != null && !init.isBlank()) {
                try { detail = rules.element(init, detail, response.url(), source); }
                catch (Exception e) { throw new SourceFailure(Stage.SEARCH, source.id(),
                        "ruleBookInfo.init", e.getMessage(), e); }
                if (detail == null) return List.of();
            }
            String name = field(source, "ruleBookInfo", "name", detail, response.url(), false);
            if (name.isBlank()) return List.of();
            String author = field(source, "ruleBookInfo", "author", detail, response.url(), false);
            String intro = optionalField(source, "ruleBookInfo", "intro", detail, response.url(), false);
            String cover = optionalField(source, "ruleBookInfo", "coverUrl", detail, response.url(), true);
            String genre = optionalField(source, "ruleBookInfo", "kind", detail, response.url(), false);
            String wordCount = optionalField(source, "ruleBookInfo", "wordCount", detail, response.url(), false);
            String lastChapter = optionalField(source, "ruleBookInfo", "lastChapter", detail, response.url(), false);
            return List.of(new BookHit(source.id(), response.url(), name, author, intro, cover,
                    source.kind(), genre, wordCount, lastChapter, bookVariables(source, response.url())));
        } catch (SourceFailure e) {
            throw new SourceFailure(Stage.SEARCH, source.id(), e.field, e.getMessage(), e);
        } finally { scripts.leaveBook(); rules.leaveBook(); }
    }

    public BookDetail detail(Source source, BookHit hit) {
        requireIdentity(source, hit.sourceId(), Stage.DETAIL);
        rules.enterBook(source, hit.url(), hit.variables());
        scripts.enterBook(jsBook(new BookDetail(hit, hit.url(), List.of())));
        try {
            if (source.isJsSource()) return jsDetail(source, hit);
            FetchResponse response = fetch(plan(hit.url(), source, "", 1, source.id(),
                            Stage.DETAIL, "ruleBookInfo.init"),
                    Stage.DETAIL, source.id(), "ruleBookInfo.init");
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
            String intro = optionalField(source, "ruleBookInfo", "intro", detailContent, response.url(), false);
            String cover = optionalField(source, "ruleBookInfo", "coverUrl", detailContent, response.url(), true);
            String genre = optionalField(source, "ruleBookInfo", "kind", detailContent, response.url(), false);
            String wordCount = optionalField(source, "ruleBookInfo", "wordCount", detailContent, response.url(), false);
            String lastChapter = optionalField(source, "ruleBookInfo", "lastChapter", detailContent, response.url(), false);
            String toc = field(source, "ruleBookInfo", "tocUrl", detailContent, response.url(), true);
            if (toc.isBlank()) toc = response.url();
            // An optional direct-download rule may only apply to one subtype of a mixed source.
            // Its failure must not hide an otherwise usable text book's catalog.
            String download = "";
            if (source.rule("ruleBookInfo", "downloadUrls") != null) {
                try { download = field(source, "ruleBookInfo", "downloadUrls", detailContent, response.url(), true); }
                catch (SourceFailure e) {
                    diagnostics.accept(new Diagnostic(Stage.DETAIL, "OPTIONAL_FIELD_ERROR", e.getMessage(),
                            Severity.WARNING, "ruleBookInfo.downloadUrls"));
                }
            }
            BookHit enriched = new BookHit(hit.sourceId(), hit.url(), name.isBlank() ? hit.name() : name,
                    author.isBlank() ? hit.author() : author, intro.isBlank() ? hit.intro() : intro,
                    cover.isBlank() ? hit.coverUrl() : cover, hit.kind(),
                    genre.isBlank() ? hit.genre() : genre,
                    wordCount.isBlank() ? hit.wordCount() : wordCount,
                    lastChapter.isBlank() ? hit.lastChapter() : lastChapter, bookVariables(source, hit.url()));
            return new BookDetail(enriched, toc, download.isBlank() ? List.of() : List.of(download.split("\\s+")));
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.DETAIL, source.id(), "ruleBookInfo", e.getMessage(), e); }
        finally { scripts.leaveBook(); rules.leaveBook(); }
    }

    public List<Chapter> chapters(Source source, BookDetail book) {
        requireIdentity(source, book.hit().sourceId(), Stage.CATALOG);
        if (source.kind() == Kind.FILE) return List.of();
        rules.enterBook(source, book.hit().url(), book.hit().variables());
        scripts.enterBook(jsBook(book));
        try {
            if (source.isJsSource()) return jsChapters(source, book);
            List<Chapter> chapters = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> finalUrls = new HashSet<>();
            Set<String> chapterUrls = new HashSet<>();
            String page = book.tocUrl();
            while (page != null && !page.isBlank()) {
                checkPage(visited, page, Stage.CATALOG, source.id(), "ruleToc.nextTocUrl");
                FetchResponse response = fetch(plan(page, source, "", 1, book.hit().url(),
                                Stage.CATALOG, "ruleToc.nextTocUrl"),
                        Stage.CATALOG, source.id(), "ruleToc.chapterList");
                checkFinalPage(finalUrls, response.url(), Stage.CATALOG, source.id(), "ruleToc.nextTocUrl");
                for (Object item : listItems(source.rule("ruleToc", "chapterList"), response.body(), response.url(), source)) {
                    String title = field(source, "ruleToc", "chapterName", item, response.url(), false);
                    String url = field(source, "ruleToc", "chapterUrl", item, response.url(), true);
                    if (title.isBlank()) continue;
                    boolean volume = ruleTrue(field(source, "ruleToc", "isVolume", item, response.url(), false));
                    boolean vip = ruleTrue(field(source, "ruleToc", "isVip", item, response.url(), false));
                    boolean pay = ruleTrue(field(source, "ruleToc", "isPay", item, response.url(), false));
                    if (url.isBlank()) url = volume ? title + chapters.size() : response.url();
                    if (chapterUrls.add(url))
                        chapters.add(new Chapter(book.hit().url(), url, title, chapters.size(), volume, vip, pay));
                }
                page = field(source, "ruleToc", "nextTocUrl", response.body(), response.url(), true);
            }
            if (chapters.isEmpty()) throw new SourceFailure(Stage.CATALOG, source.id(), "ruleToc.chapterList", "No chapters matched");
            if (!"true".equals(book.hit().variables().get("__bookgetter.reverseToc"))) return chapters;
            // A source may request the opposite catalog direction through Book.setReverseToc.
            List<Chapter> reversed = new ArrayList<>();
            for (int index = chapters.size() - 1; index >= 0; index--) {
                Chapter item = chapters.get(index);
                reversed.add(new Chapter(item.bookUrl(), item.url(), item.title(), reversed.size(),
                        item.volume(), item.vip(), item.isPay()));
            }
            return reversed;
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.CATALOG, source.id(), "ruleToc", e.getMessage(), e); }
        finally { scripts.leaveBook(); rules.leaveBook(); }
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
        requireIdentity(source, book.hit().sourceId(), Stage.CONTENT);
        if (chapter == null || book.hit().url() == null || !book.hit().url().equals(chapter.bookUrl()))
            throw new SourceFailure(Stage.CONTENT, source.id(), "chapter.bookUrl", "CHAPTER_BOOK_MISMATCH",
                    "Chapter belongs to another book");
        rules.enterBook(source, book.hit().url(), book.hit().variables());
        scripts.enterBook(jsBook(book));
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
                Set<String> finalUrls = new HashSet<>();
                while (page != null && !page.isBlank()) {
                    checkPage(visited, page, Stage.CONTENT, source.id(), "ruleContent.nextContentUrl");
                    FetchRequest request = plan(page, source, "", 1, book.tocUrl(),
                            Stage.CONTENT, "ruleContent.nextContentUrl");
                    FetchResponse response = fetchContent(request, source, visited.size() == 1);
                    // Even the first request may redirect to the following chapter. A book page
                    // can legitimately be the initial content URL, so only reject it on later pages.
                    if (response.url().equals(nextChapterUrl)
                            || visited.size() > 1 && (response.url().equals(book.hit().url())
                            || response.url().equals(book.tocUrl())))
                        throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.nextContentUrl",
                                "PAGINATION_BOUNDARY", "Pagination redirected to a chapter or catalog boundary");
                    checkFinalPage(finalUrls, response.url(), Stage.CONTENT, source.id(), "ruleContent.nextContentUrl");
                    String value = field(source, "ruleContent", "content", response.body(), response.url(), false);
                    if (invalidContent(value)) throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content", "Empty page content");
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(value);
                    String following = field(source, "ruleContent", "nextContentUrl", response.body(), response.url(), true);
                    if (!following.isBlank() && (following.equals(book.hit().url()) || following.equals(book.tocUrl()))) {
                        diagnostics.accept(new Diagnostic(Stage.CONTENT, "PAGINATION_TO_CATALOG",
                                "Pagination rule points to the book/catalog page; stopped after the current content page",
                                Severity.WARNING, "ruleContent.nextContentUrl"));
                        page = "";
                    } else page = following.equals(nextChapterUrl) ? "" : following;
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
                for (Element image : Jsoup.parse(text, chapter.url()).select("img")) {
                    // Lazy readers keep the usable image URL in a data attribute while
                    // src points to a blob or placeholder that cannot be downloaded.
                    String link = "";
                    for (String attribute : List.of("data-original", "data-src", "data-lazy-src", "src")) {
                        String candidate = image.absUrl(attribute);
                        if (ResourceUrls.isHttp(candidate)) { link = candidate; break; }
                    }
                    if (ResourceUrls.isHttp(link)) resources.add(new Resource(link, image.attr("alt"), "image/*"));
                }
                if (resources.isEmpty()) throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.content",
                        "No valid image resources found");
            } else {
                String link = RuleEvaluator.absolute(chapter.url(), text.trim());
                if (!ResourceUrls.isHttp(link)) {
                    String sourceRegex = source.rule("ruleContent", "sourceRegex");
                    String field = sourceRegex == null || sourceRegex.isBlank()
                            ? "ruleContent.content" : "ruleContent.sourceRegex";
                    throw new SourceFailure(Stage.CONTENT, source.id(), field,
                            "No valid media URL was extracted");
                }
                resources.add(new Resource(link, chapter.title(), null));
            }
            return new ChapterContent(chapter, source.kind(), null, resources);
        } catch (SourceFailure e) { throw e; }
        catch (Exception e) { throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent", e.getMessage(), e); }
        finally { scripts.leaveBook(); rules.leaveBook(); }
    }

    private List<BookHit> jsSearch(Source source, String keyword, int page) {
        return jsHits(source, "search", Map.of("key", keyword, "page", page));
    }

    private List<BookHit> jsHits(Source source, String function, Map<String, Object> args) {
        JsonArray array = jsonArray(scripts.call(source.string("mainJs"), function, args, source));
        List<BookHit> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String url = string(object, "bookUrl", "url");
            String name = string(object, "name");
            if (url.isBlank() || name.isBlank()) continue;
            result.add(new BookHit(source.id(), RuleEvaluator.absolute(source.id(), url), name,
                    string(object, "author"), string(object, "intro"),
                    absoluteIfPresent(source.id(), string(object, "coverUrl")), source.kind(),
                    string(object, "kind", "genre"), string(object, "wordCount"), string(object, "lastChapter")));
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
                orDefault(absoluteIfPresent(hit.url(), string(object, "coverUrl")), hit.coverUrl()), hit.kind(),
                orDefault(string(object, "kind", "genre"), hit.genre()),
                orDefault(string(object, "wordCount"), hit.wordCount()),
                orDefault(string(object, "lastChapter"), hit.lastChapter()), hit.variables());
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
            boolean pay = object.has("isPay") && object.get("isPay").getAsBoolean();
            result.add(new Chapter(book.hit().url(), volume && url.equals(title) ? url : RuleEvaluator.absolute(book.tocUrl(), url),
                    title, result.size(), volume, vip, pay));
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
    private static String absoluteIfPresent(String base, String value) {
        return value.isBlank() ? "" : RuleEvaluator.absolute(base, value);
    }

    private String field(Source source, String section, String field, Object input, String base, boolean url) {
        String rule = source.rule(section, field);
        try { return rules.one(rule, input, base, source, url); }
        catch (Exception e) { throw new SourceFailure(stage(section), source.id(), section + "." + field, e.getMessage(), e); }
    }

    private String optionalField(Source source, String section, String name, Object input, String base, boolean url) {
        try { return field(source, section, name, input, base, url); }
        catch (SourceFailure e) {
            diagnostics.accept(new Diagnostic(stage(section), "OPTIONAL_FIELD_ERROR", e.getMessage(),
                    Severity.WARNING, e.field));
            return "";
        }
    }

    private FetchRequest plan(String template, Source source, String key, int page, String base,
                              Stage stage, String field) {
        try { return requests.plan(template, source, key, page, base); }
        catch (Exception e) {
            throw new SourceFailure(stage, source.id(), field, "REQUEST_PLAN_ERROR",
                    "Cannot plan request (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private FetchResponse fetch(FetchRequest request, Stage stage, String sourceId, String field) {
        FetchResponse response;
        try { response = transport.fetch(request); }
        catch (WebContentTransport.HttpStatusException e) {
            throw browserStatusFailure(request, stage, sourceId, field, e);
        }
        catch (Exception e) {
            throw new SourceFailure(stage, sourceId, field, "REQUEST_ERROR",
                    "Request failed at " + ResponseSignals.urlForLog(request.url())
                            + " (" + e.getClass().getSimpleName() + ")", e);
        }
        trace(request, response, stage, sourceId, field);
        validateResponse(response, request, stage, sourceId, field);
        return response;
    }

    private static void validateResponse(FetchResponse response, FetchRequest request,
                                         Stage stage, String sourceId, String field) {
        if (response.status() >= 300 && response.status() < 400)
            throw new SourceFailure(stage, sourceId, field, "UNRESOLVED_REDIRECT",
                    "Unresolved HTTP redirect " + response.status() + " at " + ResponseSignals.urlForLog(request.url()));
        if (response.status() >= 400)
            throw new SourceFailure(stage, sourceId, field,
                    response.status() == 401 || response.status() == 403 ? "ACCESS_DENIED" : "HTTP_ERROR",
                    "HTTP " + response.status() + " at " + ResponseSignals.urlForLog(request.url()));
        rejectUnusableResponse(response, stage, sourceId, field);
    }

    private FetchResponse fetchContent(FetchRequest request, Source source, boolean firstPage) {
        // Legado only sniffs resources on the initial chapter request; later pages may still run webJs.
        String sourceRegex = firstPage ? source.rule("ruleContent", "sourceRegex") : null;
        String webJs = source.rule("ruleContent", "webJs");
        String browserField = sourceRegex != null && !sourceRegex.isBlank()
                ? "ruleContent.sourceRegex" : "ruleContent.webJs";
        if ((sourceRegex == null || sourceRegex.isBlank()) && (webJs == null || webJs.isBlank()))
            return fetch(request, Stage.CONTENT, source.id(), "ruleContent.content");
        if (!(transport instanceof WebContentTransport browser))
            throw new SourceFailure(Stage.CONTENT, source.id(),
                    browserField,
                    "UNSUPPORTED", "Content rule requires a browser transport");
        try {
            FetchResponse response = browser.fetchContent(request, sourceRegex, webJs);
            trace(request, response, Stage.CONTENT, source.id(), "ruleContent.content");
            validateResponse(response, request, Stage.CONTENT, source.id(), "ruleContent.content");
            return response;
        } catch (SourceFailure e) { throw e; }
        catch (WebContentTransport.HttpStatusException e) {
            throw browserStatusFailure(request, Stage.CONTENT, source.id(), browserField, e);
        }
        catch (WebContentTransport.NoMatchingResourceException e) {
            throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.sourceRegex", "SOURCE_REGEX_NO_MATCH",
                    e.getMessage(), e);
        }
        catch (Exception e) {
            throw new SourceFailure(Stage.CONTENT, source.id(), browserField, "REQUEST_ERROR",
                    "Browser content request failed at " + ResponseSignals.urlForLog(request.url())
                            + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private void trace(FetchRequest request, FetchResponse response, Stage stage, String sourceId, String field) {
        traces.accept(new StageTrace(stage, sourceId, field, request.method(),
                ResponseSignals.urlForLog(request.url()), ResponseSignals.urlForLog(response.url()),
                response.status(), ResponseSignals.quality(response)));
    }

    private SourceFailure browserStatusFailure(FetchRequest request, Stage stage, String sourceId, String field,
                                               WebContentTransport.HttpStatusException error) {
        FetchResponse failed = new FetchResponse(error.url(), "", error.status(), Map.of());
        trace(request, failed, stage, sourceId, field);
        try { validateResponse(failed, request, stage, sourceId, field); }
        catch (SourceFailure failure) { return failure; }
        return new SourceFailure(stage, sourceId, field, "REQUEST_ERROR",
                "Browser reported an unexpected HTTP status " + error.status());
    }

    private static void rejectUnusableResponse(FetchResponse response, Stage stage, String sourceId, String field) {
        String quality = ResponseSignals.quality(response);
        if (quality.equals("BUSINESS_ERROR") || quality.equals("EMPTY_RESPONSE")
                || quality.equals("LOGIN_REQUIRED")
                || quality.equals("CHALLENGE_PAGE") || quality.equals("PARKED_DOMAIN")
                || quality.equals("CLIENT_SIDE_REDIRECT")
                || quality.equals("APP_UPGRADE_REQUIRED") || quality.equals("PAGE_NOT_FOUND"))
            throw new SourceFailure(stage, sourceId, field, quality,
                    "Unusable source response: " + quality + " (HTTP " + response.status() + ")");
    }

    private static void requireIdentity(Source source, String sourceId, Stage stage) {
        if (!source.id().equals(sourceId))
            throw new SourceFailure(stage, source.id(), "book.sourceId", "SOURCE_MISMATCH",
                    "Book belongs to another source");
    }
    private static void checkPage(Set<String> visited, String page, Stage stage, String sourceId, String field) {
        // An incomplete catalog or chapter must never enter the cache or a successful export.
        if (visited.contains(page))
            throw new SourceFailure(stage, sourceId, field, "PAGINATION_CYCLE", "Pagination returned to an earlier page");
        if (visited.size() >= MAX_PAGES)
            throw new SourceFailure(stage, sourceId, field, "PAGINATION_LIMIT",
                    "Pagination exceeded " + MAX_PAGES + " pages");
        visited.add(page);
    }
    private static void checkFinalPage(Set<String> visited, String url, Stage stage, String sourceId, String field) {
        if (!visited.add(url))
            throw new SourceFailure(stage, sourceId, field, "PAGINATION_CYCLE",
                    "Pagination redirected to an earlier page");
    }
    private static Stage stage(String section) { return switch (section) { case "ruleSearch", "ruleExplore" -> Stage.SEARCH; case "ruleBookInfo" -> Stage.DETAIL; case "ruleToc" -> Stage.CATALOG; default -> Stage.CONTENT; }; }
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
        value.put("variables", book.hit().variables());
        return value;
    }

    private Map<String, String> bookVariables(Source source, String bookUrl) {
        Map<String, String> merged = new LinkedHashMap<>(rules.bookVariables(source, bookUrl));
        merged.putAll(scripts.bookVariables());
        if (scripts.reverseToc()) merged.put("__bookgetter.reverseToc", "true");
        else merged.remove("__bookgetter.reverseToc");
        return merged;
    }
}
