package org.bookgetter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.RssModels.*;
import org.jsoup.Jsoup;

/** RSS list and reading pipeline. Browser presentation remains an Android responsibility. */
public final class RssEngine {
    private static final int MAX_PAGES = 50;
    private static final int MAX_CONTENT_CHARS = 16 * 1024 * 1024;
    private final ScriptRuntime scripts;
    private final RuleEvaluator rules;
    private final RequestPlanner requests;
    private final StageFetcher fetcher;
    private final Consumer<Diagnostic> diagnostics;
    private final ThreadLocal<Map<String, Object>> articleBinding = new ThreadLocal<>();

    public RssEngine(Transport transport) { this(transport, new RhinoRuntime(transport), ignored -> {}, ignored -> {}); }
    public RssEngine(Transport transport, ScriptRuntime scripts, Consumer<Diagnostic> diagnostics,
                     Consumer<StageTrace> traces) {
        this.scripts = scripts;
        this.diagnostics = diagnostics;
        SourceStateStore states = scripts instanceof RhinoRuntime rhino ? rhino.stateStore() : new MemorySourceStateStore();
        ScriptRuntime contextual = new ScriptRuntime() {
            @Override public Object evaluate(String code, Map<String, Object> bindings, Source source) {
                Map<String, Object> merged = new LinkedHashMap<>(bindings);
                if (articleBinding.get() != null) merged.put("rssArticle", articleBinding.get());
                return scripts.evaluate(code, merged, source);
            }
            @Override public Object call(String code, String function, Map<String, Object> args, Source source) {
                return scripts.call(code, function, args, source);
            }
        };
        rules = new RuleEvaluator(contextual, states);
        requests = new RequestPlanner(contextual);
        fetcher = new StageFetcher(transport, traces, states);
    }

    public List<Category> categories(RssSource source) {
        String configured = source.string("sortUrl");
        String resolved = configured;
        try {
            if (configured != null && configured.startsWith("@js:"))
                resolved = Objects.toString(scripts.evaluate(configured.substring(4), Map.of(), source.executionSource()), "");
            else if (configured != null && configured.startsWith("<js>") && configured.endsWith("</js>"))
                resolved = Objects.toString(scripts.evaluate(configured.substring(4, configured.length() - 5), Map.of(), source.executionSource()), "");
        } catch (RuntimeException error) {
            checkCancelled();
            diagnostics.accept(new Diagnostic(Stage.SEARCH, "RSS_SORT_SCRIPT_ERROR", "RSS categories fell back to the source URL", "sortUrl"));
            resolved = null;
        }
        List<Category> result = new ArrayList<>();
        if (resolved != null) for (String entry : resolved.split("(?:&&|\\r?\\n)+")) {
            int separator = entry.indexOf("::");
            if (separator < 0) continue;
            String url = entry.substring(separator + 2).trim();
            if (!url.isEmpty()) result.add(new Category(entry.substring(0, separator).trim(), url));
        }
        return result.isEmpty() ? List.of(new Category("", source.id())) : List.copyOf(result);
    }

    public Page search(RssSource source, String key, int page) {
        String search = source.string("searchUrl");
        if (blank(search)) throw failure(source, Stage.SEARCH, "searchUrl", "NO_SEARCH_URL", "RSS source has no search URL");
        return articles(source, new Category("", search), page, key);
    }

    public Page articles(RssSource source, Category category, int page, String key) {
        return articles(source, category, page, key, category.url());
    }

    public Page articles(RssSource source, Category category, int page, String key, String pageUrl) {
        if (page < 1) throw new IllegalArgumentException("RSS page must be positive");
        checkCancelled();
        Source execution = source.executionSource();
        FetchResponse response = fetcher.fetch(requests.plan(pageUrl, execution, Objects.toString(key, ""), page, source.id()),
                Stage.SEARCH, source.id(), "ruleArticles");
        String listRule = source.string("ruleArticles");
        if (blank(listRule)) return new Page(RssXmlParser.parse(response.body(), source, category.name()), null, page, response.url());
        boolean reverse = listRule.startsWith("-");
        if (reverse) listRule = listRule.substring(1);
        String ruleBase = response.url();
        List<Object> items = rules.many(listRule, response.body(), ruleBase, execution);
        String nextRule = source.string("ruleNextPage");
        String next = null;
        if (!blank(nextRule)) next = nextRule.equalsIgnoreCase("PAGE") ? category.url()
                : rules.one(nextRule, response.body(), ruleBase, execution, true);
        var results = new LinkedHashMap<String, Article>();
        for (Object item : items) {
            checkCancelled();
            rules.enterSearchItem();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("origin", ""); context.put("sort", "");
            context.put("title", ""); context.put("link", "");
            context.put("pubDate", ""); context.put("description", ""); context.put("image", "");
            context.put("content", ""); context.put("type", 0);
            articleBinding.set(context);
            try {
                String title = field(source, "ruleTitle", item, ruleBase, false);
                context.put("title", title);
                String date = field(source, "rulePubDate", item, ruleBase, false);
                context.put("pubDate", date);
                String description = blank(source.string("ruleDescription")) ? null
                        : field(source, "ruleDescription", item, ruleBase, false);
                context.put("description", description);
                String image = null;
                try {
                    String raw = field(source, "ruleImage", item, ruleBase, false);
                    if (!blank(raw)) image = RuleEvaluator.absolute(source.id(), raw);
                } catch (RuntimeException error) {
                    diagnostics.accept(new Diagnostic(Stage.SEARCH, "RSS_IMAGE_RULE_ERROR", "RSS image extraction failed", "ruleImage"));
                }
                context.put("image", image);
                String link = field(source, "ruleLink", item, ruleBase, true);
                if (title.isBlank()) continue;
                Article article = new Article(source.id(), category.name(), title, link, date, description,
                        null, image, source.kind(), rules.searchItemVariables());
                results.putIfAbsent(article.identity(), article);
            } finally { articleBinding.remove(); rules.leaveSearchItem(); }
        }
        List<Article> ordered = new ArrayList<>(results.values());
        if (reverse) Collections.reverse(ordered);
        return new Page(ordered, next, page, response.url());
    }

    public ReadTarget open(RssSource source, Article article) {
        if (!source.id().equals(article.origin()))
            throw failure(source, Stage.CONTENT, "link", "RSS_ARTICLE_SOURCE_MISMATCH", "Article belongs to another RSS source");
        if (source.flag("singleUrl", false)) return browser(source, blank(article.link()) ? source.id() : article.link());
        String html = article.description();
        String base = article.link();
        if (blank(html)) {
            String rule = source.string("ruleContent");
            if (blank(rule)) return browser(source, article.link());
            Map<String, Object> context = articleContext(article);
            articleBinding.set(context);
            // The same link in two categories denotes two independent RSS articles.
            rules.enterBook(source.executionSource(), article.identity(), article.variables());
            scripts.enterBook(context);
            try {
                Content content = content(source, article, rule);
                html = content.html(); base = content.baseUrl();
            } finally {
                rules.leaveBook(); scripts.leaveBook(); articleBinding.remove();
            }
        }
        return switch (article.kind()) {
            case WEB -> new HtmlContent(html, base);
            case IMAGE -> new Images(imageResources(source, html, base));
            case VIDEO -> new Video(videoUrl(source, html, base));
        };
    }

    private record Content(String html, String baseUrl) {}
    private record ContentPage(String html, String responseUrl, List<String> next) {}
    private Content content(RssSource source, Article article, String rule) {
        boolean paginate = article.kind() == RssKind.WEB;
        ContentPage first = contentPage(source, article.link(), rule, paginate);
        StringBuilder merged = new StringBuilder();
        append(source, merged, first.html());
        Set<String> visited = new HashSet<>();
        visited.add(article.link()); visited.add(first.responseUrl());
        int pages = 1;
        if (first.next().size() > 1) {
            for (String next : first.next()) {
                checkCancelled();
                if (!visited.add(next)) throw pagination(source, "RSS pagination revisited a page");
                if (++pages > MAX_PAGES) throw pagination(source, "RSS pagination exceeded page limit");
                ContentPage result = contentPage(source, next, rule, false);
                if (!next.equals(result.responseUrl()) && !visited.add(result.responseUrl()))
                    throw pagination(source, "RSS pagination redirected to a visited page");
                append(source, merged, result.html());
            }
        } else {
            List<String> next = first.next();
            while (!next.isEmpty() && !blank(next.get(0))) {
                checkCancelled();
                String url = next.get(0);
                if (!visited.add(url)) throw pagination(source, "RSS pagination revisited a page");
                if (++pages > MAX_PAGES) throw pagination(source, "RSS pagination exceeded page limit");
                ContentPage result = contentPage(source, url, rule, paginate);
                if (!url.equals(result.responseUrl()) && !visited.add(result.responseUrl()))
                    throw pagination(source, "RSS pagination redirected to a visited page");
                append(source, merged, result.html()); next = result.next();
            }
        }
        return new Content(merged.toString(), first.responseUrl());
    }

    private ContentPage contentPage(RssSource source, String url, String rule, boolean nextPage) {
        Source execution = source.executionSource();
        FetchResponse response = fetcher.fetch(requests.plan(url, execution, "", 1, source.id()), Stage.CONTENT, source.id(), "ruleContent");
        String html = rules.one(rule, response.body(), response.url(), execution, false);
        List<String> next = nextPage && source.kind() == RssKind.WEB && !blank(source.string("nextContentUrl"))
                ? rules.stringList(source.string("nextContentUrl"), response.body(), response.url(), execution, true) : List.of();
        return new ContentPage(html, response.url(), next);
    }

    private String field(RssSource source, String name, Object input, String base, boolean url) {
        try { return rules.one(source.string(name), input, base, source.executionSource(), url); }
        catch (SourceFailure error) { throw error; }
        catch (RuntimeException error) { throw new SourceFailure(Stage.SEARCH, source.id(), name, "RSS rule failed", error); }
    }
    private static Map<String, Object> articleContext(Article article) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("origin", article.origin()); context.put("sort", article.sort());
        context.put("title", article.title()); context.put("link", article.link());
        context.put("pubDate", article.pubDate()); context.put("description", article.description());
        context.put("content", article.content()); context.put("image", article.image());
        context.put("type", article.kind().ordinal());
        context.put("bookUrl", article.link()); context.put("variables", article.variables());
        return context;
    }
    private static BrowserPage browser(RssSource source, String url) {
        String resolved = RuleEvaluator.absolute(source.id(), url);
        if (!ResourceUrls.isHttp(resolved)) throw failure(source, Stage.CONTENT, "link", "INVALID_RSS_URL", "RSS page must use HTTP(S)");
        return new BrowserPage(resolved);
    }
    private static List<Resource> imageResources(RssSource source, String html, String base) {
        var resources = new LinkedHashMap<String, Resource>();
        for (var image : Jsoup.parse(html).select("img[src]")) addImage(resources, base, image.attr("src"));
        if (resources.isEmpty()) for (String line : html.split("\\r?\\n")) addImage(resources, base, line.trim());
        if (resources.isEmpty()) throw failure(source, Stage.CONTENT, "ruleContent", "RSS_NO_IMAGES", "RSS article has no HTTP(S) image");
        return new ArrayList<>(resources.values());
    }
    private static void addImage(Map<String, Resource> resources, String base, String value) {
        if (blank(value) || value.contains("<")) return;
        String url = RuleEvaluator.absolute(base, value);
        if (ResourceUrls.isHttp(url)) resources.putIfAbsent(url, new Resource(url, "", "image/*"));
    }
    private static String videoUrl(RssSource source, String html, String base) {
        var element = Jsoup.parse(html).selectFirst("video[src],video source[src]");
        String value = element == null ? html.trim() : element.attr("src");
        String url = RuleEvaluator.absolute(base, value);
        if (!ResourceUrls.isHttp(url)) throw failure(source, Stage.CONTENT, "ruleContent", "RSS_NO_VIDEO", "RSS article has no HTTP(S) video");
        return url;
    }
    private static void append(RssSource source, StringBuilder result, String html) {
        if ((long) result.length() + html.length() + 1 > MAX_CONTENT_CHARS)
            throw failure(source, Stage.CONTENT, "ruleContent", "RSS_CONTENT_TOO_LARGE", "RSS content exceeds size limit");
        if (result.length() > 0) result.append('\n');
        result.append(html);
    }
    private static SourceFailure pagination(RssSource source, String message) {
        return failure(source, Stage.CONTENT, "nextContentUrl", "RSS_PAGINATION_ERROR", message);
    }
    private static SourceFailure failure(RssSource source, Stage stage, String field, String code, String message) {
        return new SourceFailure(stage, source.id(), field, code, message);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("RSS operation cancelled");
    }
}
