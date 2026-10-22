package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.RssModels.*;
import org.junit.jupiter.api.Test;

class RssEngineTest {
    private static RssSource source(String fields) {
        JsonObject json = JsonParser.parseString("{\"sourceUrl\":\"https://example.test/rss\",\"sourceName\":\"News\"" + fields + "}").getAsJsonObject();
        return RssImporter.parse(json.toString()).sources().get(0);
    }
    private static Article article(RssSource source, String description) {
        return new Article(source.id(), "News", "Title", "https://example.test/a", "today", description,
                null, null, source.kind(), Map.of());
    }
    private static RssEngine engine(Map<String, String> pages) {
        return new RssEngine(request -> new FetchResponse(request.url(), pages.getOrDefault(request.url(), ""), 200, Map.of()));
    }

    @Test void rssImportKeepsItsOwnIdentityTypesAndMalformedEntryDiagnostics() {
        var result = RssImporter.parse("[{\"sourceUrl\":\"same\",\"type\":2,\"unknown\":42},{\"bookSourceUrl\":\"book\"},false]");
        assertEquals(1, result.sources().size());
        assertEquals(RssKind.VIDEO, result.sources().get(0).kind());
        assertEquals(42, result.sources().get(0).json().get("unknown").getAsInt());
        assertEquals("rss", SourcePolicy.of(result.sources().get(0).executionSource()).model());
        assertEquals(2, result.diagnostics().size());
        assertEquals(1, RssImporter.parse("[{\"sourceUrl\":\"a\"},{\"sourceUrl\":\"a\"}]").sources().size());
    }

    @Test void defaultXmlKeepsCdataContentImagesAndIgnoresNextPageRule() {
        var source = source(",\"ruleNextPage\":\"PAGE\"");
        String xml = "<rss xmlns:content='urn:content'><channel><item><title> A </title><link>https://example.test/a</link>"
                + "<description><![CDATA[<p>Summary<img src='/pic.jpg'></p>]]></description><pubDate> today </pubDate>"
                + "<content:encoded><![CDATA[<b>Full</b>]]></content:encoded></item><item><title></title></item></channel></rss>";
        Page page = engine(Map.of(source.id(), xml)).articles(source, new Category("News", source.id()), 1, "");
        assertEquals(2, page.articles().size());
        assertNull(page.nextPageUrl());
        assertEquals("A", page.articles().get(0).title());
        assertEquals("/pic.jpg", page.articles().get(0).image());
        assertEquals("<b>Full</b>", page.articles().get(0).content());
        assertEquals("today", page.articles().get(0).pubDate());
        assertEquals("News", page.articles().get(0).sort());
    }

    @Test void xmlRejectsDtdAndExternalEntities() {
        var source = source("");
        String xml = "<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><rss><item><title>&secret;</title></item></rss>";
        var error = assertThrows(SourceFailure.class, () -> engine(Map.of(source.id(), xml))
                .articles(source, new Category("", source.id()), 1, ""));
        assertEquals("RSS_XML_ERROR", error.code);
    }

    @Test void customListsReverseFilterBlankTitlesAndKeepPageTemplate() {
        var source = source(",\"ruleArticles\":\"-li\",\"ruleTitle\":\"a@text\",\"ruleLink\":\"a@href\",\"ruleNextPage\":\"PAGE\"");
        String html = "<li><a href='/a'>A</a></li><li><a href='/b'>B</a></li><li><a href='/empty'></a></li>";
        var page = engine(Map.of("https://example.test/list?p=2", html)).articles(source,
                new Category("Category", "https://example.test/list?p={{page}}"), 2, "");
        assertEquals(List.of("B", "A"), page.articles().stream().map(Article::title).toList());
        assertEquals("https://example.test/b", page.articles().get(0).link());
        assertEquals("https://example.test/list?p={{page}}", page.nextPageUrl());
    }

    @Test void categoriesAndSearchUseRssFieldsAndBindings() {
        var source = source(",\"sortUrl\":\"@js:'News::/news&&Tech::/tech'\",\"searchUrl\":\"/search?q={{key}}&p={{page}}\","
                + "\"ruleArticles\":\"$.items[*]\",\"ruleTitle\":\"$.title\",\"ruleLink\":\"$.url\"");
        var engine = engine(Map.of("https://example.test/search?q=word&p=3", "{\"items\":[{\"title\":\"Hit\",\"url\":\"/a\"}]}"));
        assertEquals(List.of(new Category("News", "/news"), new Category("Tech", "/tech")), engine.categories(source));
        assertEquals("Hit", engine.search(source, "word", 3).articles().get(0).title());
        assertEquals(List.of(new Category("", source.id())), engine.categories(source("")));
    }

    @Test void readingPrefersDescriptionAndSingleUrlOpensBrowser() {
        AtomicInteger requests = new AtomicInteger();
        RssEngine engine = new RssEngine(request -> { requests.incrementAndGet(); throw new AssertionError("Unexpected fetch"); });
        var source = source(",\"ruleContent\":\"main@html\"");
        assertEquals(new HtmlContent("<b>Summary</b>", "https://example.test/a"), engine.open(source, article(source, "<b>Summary</b>")));
        assertEquals(0, requests.get());
        var single = source(",\"singleUrl\":true,\"ruleContent\":\"main@html\"");
        assertEquals(new BrowserPage("https://example.test/a"), engine.open(single, article(single, "summary")));
        assertThrows(SourceFailure.class, () -> engine.open(source, new Article("other", "", "", "/a", "", "", "", "", RssKind.WEB, Map.of())));
    }

    @Test void sequentialContentPaginationPreservesHtmlAndStopsOnCycles() {
        var source = source(",\"ruleContent\":\"main@html\",\"nextContentUrl\":\"a.next@href\"");
        var engine = engine(Map.of("https://example.test/a", "<main>One</main><a class=next href='/b'>Next</a>",
                "https://example.test/b", "<main>Two</main>"));
        String html = ((HtmlContent) engine.open(source, article(source, null))).html();
        assertTrue(html.contains("One")); assertTrue(html.contains("Two")); assertTrue(html.contains("<main>"));
        var looping = engine(Map.of("https://example.test/a", "<main>One</main><a class=next href='/a'>Next</a>"));
        assertEquals("RSS_PAGINATION_ERROR", assertThrows(SourceFailure.class, () -> looping.open(source, article(source, null))).code);
    }

    @Test void multipleNextLinksAreFetchedOnceInOrderWithoutRecursion() {
        var source = source(",\"ruleContent\":\"main@text\",\"nextContentUrl\":\"a@href\"");
        var engine = engine(Map.of("https://example.test/a", "<main>A</main><a href='/b'>B</a><a href='/c'>C</a>",
                "https://example.test/b", "<main>B</main><a href='/should-not-fetch'>More</a>",
                "https://example.test/c", "<main>C</main>"));
        assertEquals("A\nB\nC", ((HtmlContent) engine.open(source, article(source, null))).html());
    }

    @Test void imageAndVideoTargetsUseRssMediaMappingAndHttpResources() {
        var images = source(",\"type\":1");
        var video = source(",\"type\":2");
        var engine = engine(Map.of());
        var result = (Images) engine.open(images, article(images, "<img src='/a.png'><img src='javascript:bad'>"));
        assertEquals(List.of("https://example.test/a.png"), result.resources().stream().map(Resource::url).toList());
        assertEquals(new Video("https://example.test/a.mp4"), engine.open(video, article(video, "<video src='/a.mp4'></video>")));
    }

    @Test void relativeCategoriesUseFinalResponseBaseAfterRedirects() {
        var source = source(",\"ruleArticles\":\"li\",\"ruleTitle\":\"a@text\",\"ruleLink\":\"a@href\",\"ruleNextPage\":\"a.next@href\"");
        var engine = new RssEngine(request -> {
            assertEquals("https://example.test/list", request.url());
            return new FetchResponse("https://redirect.test/section/list", "<li><a href='article'>Title</a></li><a class=next href='page2'>Next</a>", 200, Map.of());
        });
        var page = engine.articles(source, new Category("", "/list"), 1, "");
        assertEquals("https://redirect.test/section/article", page.articles().get(0).link());
        assertEquals("https://redirect.test/section/page2", page.nextPageUrl());
    }

    @Test void contentPaginationRequiresBothSourceAndArticleToBeWeb() {
        var source = source(",\"ruleContent\":\"main@html\",\"nextContentUrl\":\"a@href\"");
        AtomicInteger fetched = new AtomicInteger();
        var engine = new RssEngine(request -> {
            fetched.incrementAndGet();
            return new FetchResponse(request.url(), "<main><img src='/image.png'></main><a href='/next'>Next</a>", 200, Map.of());
        });
        var article = new Article(source.id(), "", "Title", "https://example.test/a", null, null, null, null, RssKind.IMAGE, Map.of());
        assertInstanceOf(Images.class, engine.open(source, article));
        assertEquals(1, fetched.get());
    }

    @Test void listFieldsCanReadEarlierArticleFieldsWithoutLeakingThePreviousItem() {
        var source = source(",\"ruleArticles\":\"li\",\"ruleTitle\":\"a@text\",\"rulePubDate\":\"time@text\","
                + "\"ruleDescription\":\"@js:rssArticle.title+'|'+rssArticle.pubDate\","
                + "\"ruleLink\":\"@js:'/read/'+rssArticle.title\"");
        var engine = engine(Map.of(source.id(), "<li><a>A</a><time>Day1</time></li><li><a>B</a><time>Day2</time></li>"));
        var page = engine.articles(source, new Category("News", source.id()), 1, "");
        assertEquals(List.of("A|Day1", "B|Day2"), page.articles().stream().map(Article::description).toList());
        assertEquals(List.of("https://example.test/read/A", "https://example.test/read/B"), page.articles().stream().map(Article::link).toList());
    }

    @Test void persistedArticleVariablesRemainIsolatedAcrossCategoriesWithTheSameLink() {
        var source = source(",\"ruleContent\":\"@js:var prior=java.get('token');java.put('token',rssArticle.sort);prior\"");
        var engine = engine(Map.of("https://example.test/a", "<main>Article</main>"));
        var first = new Article(source.id(), "First", "Title", "https://example.test/a", null,
                null, null, null, RssKind.WEB, Map.of("token", "inherited-first"));
        var second = new Article(source.id(), "Second", "Title", "https://example.test/a", null,
                null, null, null, RssKind.WEB, Map.of("token", "inherited-second"));
        assertEquals("inherited-first", ((HtmlContent) engine.open(source, first)).html());
        assertEquals("inherited-second", ((HtmlContent) engine.open(source, second)).html());
        assertEquals("First", ((HtmlContent) engine.open(source, first)).html());
        assertEquals("Second", ((HtmlContent) engine.open(source, second)).html());
    }
}
