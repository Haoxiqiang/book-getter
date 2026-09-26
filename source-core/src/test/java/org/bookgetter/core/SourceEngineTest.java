package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

final class SourceEngineTest {
    private final String sourceJson = """
        {"bookSourceUrl":"https://example.test", "bookSourceName":"fixture",
         "searchUrl":"/search?q={{key}}&page={{page}}",
         "ruleSearch":{"bookList":"div.book","name":"a@text","bookUrl":"a@href","author":"span.author@text"},
         "ruleBookInfo":{"name":"h1@text","author":".author@text","tocUrl":"a.toc@href"},
         "ruleToc":{"chapterList":"li.chapter","chapterName":"a@text","chapterUrl":"a@href"},
         "ruleContent":{"content":"div.content@text"}}
        """;

    @Test void importSearchDetailCatalogContent() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        Transport transport = request -> {
            String body = switch (request.url()) {
                case "https://example.test/search?q=alpha&page=1" -> "<div class='book'><a href='/book'>Alpha</a><span class='author'>A</span></div>";
                case "https://example.test/book" -> "<h1>Alpha</h1><span class='author'>A</span><a class='toc' href='/toc'>目录</a>";
                case "https://example.test/toc" -> "<ul><li class='chapter'><a href='/one'>第一章</a></li></ul>";
                case "https://example.test/one" -> "<div class='content'>Hello world</div>";
                default -> throw new IllegalArgumentException(request.url());
            };
            return new FetchResponse(request.url(), body, 200, Map.of());
        };
        SourceEngine engine = new SourceEngine(transport);
        BookHit hit = engine.search(source, "alpha", 1).get(0);
        assertEquals("https://example.test/book", hit.url());
        BookDetail detail = engine.detail(source, hit);
        Chapter chapter = engine.chapters(source, detail).get(0);
        assertEquals("第一章", chapter.title());
        assertEquals("Hello world", engine.content(source, detail, chapter).text());
    }

    @Test void sourceIdentityCannotBeMixed() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertThrows(SourceFailure.class, () -> engine.detail(source, new BookHit("other", "/book", "X", "", "", "", Kind.TEXT)));
    }

    @Test void mainJsBasicSearch() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","bookSourceName":"JS",
             "mainJs":"function search(key,page){return [{bookUrl:'https://example.test/b',name:key,author:'A'}]}"}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertEquals("Novel", engine.search(source, "Novel", 1).get(0).name());
    }

    @Test void emptySearchPageIsAValidResult() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "<p>Nothing found</p>", 200, Map.of()));
        assertTrue(engine.search(source, "missing", 1).isEmpty());
    }

    @Test void nullLikeChapterIsNotSuccessfulContent() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='content'>null</div>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        assertEquals(Stage.CONTENT, assertThrows(SourceFailure.class,
                () -> engine.content(source, book, chapter)).stage);
        assertTrue(SourceEngine.invalidContent("\nnull "));
    }

    @Test void contentPaginationStopsAtKnownNextChapter() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@text","nextContentUrl":"a.next@href"}}
            """).sources().get(0);
        java.util.List<String> fetched = new java.util.ArrayList<>();
        SourceEngine engine = new SourceEngine(request -> {
            fetched.add(request.url());
            String body = switch (request.url()) {
                case "https://example.test/one" -> "<div class='content'>first</div><a class='next' href='/one-2'>Next page</a>";
                case "https://example.test/one-2" -> "<div class='content'>second</div><a class='next' href='/two'>Next chapter</a>";
                default -> throw new AssertionError("Fetched next chapter as current content: " + request.url());
            };
            return new FetchResponse(request.url(), body, 200, Map.of());
        });
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        assertEquals("first\nsecond", engine.content(source, book, chapter, "https://example.test/two").text());
        assertEquals(List.of("https://example.test/one", "https://example.test/one-2"), fetched);
    }

    @Test void directAttributeRuleReadsCurrentListElement() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"/search",
             "ruleSearch":{"bookList":"a.book","name":"text","bookUrl":"href"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<a class='book' href='/book'>A novel</a>", 200, Map.of()));
        BookHit hit = engine.search(source, "a", 1).get(0);
        assertEquals("A novel", hit.name());
        assertEquals("https://example.test/book", hit.url());
    }

    @Test void defaultRuleChainAndAtAtTemplateResolveCurrentElement() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://comic.example","searchUrl":"/search",
             "ruleSearch":{"bookList":"class.update_con@tag.li","name":"class.title@text",
               "bookUrl":"https://comic.example/api/detail/{{@@class.title@tag.a@href##.+info.(\\\\w+).html##$1}}"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='update_con'><li><div class='title'><a href='/info.demo.html'>Comic</a></div></li></div>",
                200, Map.of()));
        BookHit hit = engine.search(source, "Comic", 1).get(0);
        assertEquals("Comic", hit.name());
        assertEquals("https://comic.example/api/detail/demo", hit.url());
    }

    @Test void requestOptionFollowedByJsKeepsUrlAndOptions() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"/search?key={{key}},{\\n  \\"charset\\": \\"gbk\\"\\n}\\n@js:result"}
            """).sources().get(0);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        FetchRequest request = planner.plan(source.string("searchUrl"), source, "book", 1, source.id());
        assertEquals("https://example.test/search?key=book", request.url());
        assertEquals("gbk", request.charset());
        FetchRequest chinese = planner.plan(source.string("searchUrl"), source, "无职", 1, source.id());
        assertEquals("https://example.test/search?key="
                + java.net.URLEncoder.encode("无职", java.nio.charset.Charset.forName("GBK")), chinese.url());
    }

    @Test void requestPlanningFailureIsAttributedToSearchUrl() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"@js:missingHostMethod()"}
            """).sources().get(0);
        SourceFailure failure = assertThrows(SourceFailure.class,
                () -> new SourceEngine(request -> new FetchResponse(request.url(), "", 200, Map.of()))
                        .search(source, "book", 1));
        assertEquals(Stage.SEARCH, failure.stage);
        assertEquals("searchUrl", failure.field);
    }

    @Test void detailInitFeedsChapterUrlAndVolumeVipRules() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://comic.example","ruleBookInfo":{
               "init":"@js:java.put('bid', `{{$.id}}`); result",
               "name":"$.title","tocUrl":"@js:'/toc'"},
             "ruleToc":{"chapterList":"$[*]","chapterName":"$.chapter_title",
               "chapterUrl":"@js:result.isV ? '' : '/chapter/' + java.get('bid') + '/' + result.chapter_id",
               "isVolume":"$.isV","isVip":"$.vip"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                request.url().endsWith("/toc")
                        ? "[{\"chapter_title\":\"Volume\",\"isV\":true},{\"chapter_title\":\"One\",\"isV\":false,\"vip\":\"yes\",\"chapter_id\":7}]"
                        : "{\"id\":42,\"title\":\"Comic\"}", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://comic.example/book", "Search title", "", "", "", Kind.IMAGE);
        BookDetail detail = engine.detail(source, hit);
        assertEquals("Comic", detail.hit().name());
        var chapters = engine.chapters(source, detail);
        assertEquals(2, chapters.size());
        assertTrue(chapters.get(0).volume());
        assertFalse(chapters.get(0).vip());
        assertEquals("Volume0", chapters.get(0).url());
        assertFalse(chapters.get(1).volume());
        assertTrue(chapters.get(1).vip());
        assertEquals("https://comic.example/chapter/42/7", chapters.get(1).url());
    }

    @Test void optionalJsDetailAndRelativeChapterUrls() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","mainJs":"function search(key,page){return [{bookUrl:'/book',name:'A'}]}\nfunction getChapters(book){return [{url:'chapter/1',title:'One',isVip:true}]}\nfunction getContent(chapter,book,nextChapterUrl){return java.ajax(chapter.url)}"}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "Chapter body", 200, Map.of()));
        BookHit hit = engine.search(source, "a", 1).get(0);
        assertEquals("https://example.test/book", hit.url());
        BookDetail detail = engine.detail(source, hit);
        Chapter chapter = engine.chapters(source, detail).get(0);
        assertEquals("https://example.test/chapter/1", chapter.url());
        assertTrue(chapter.vip());
        assertEquals("Chapter body", engine.content(source, detail, chapter).text());
    }

    @Test void jsDetailMergesMetadataAndResolvesLinks() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","mainJs":"function search(key,page){return [{bookUrl:'/book/1',name:'Old'}]}\nfunction getBookInfo(book){return {name:'New',intro:'Description',tocUrl:'toc',downloadUrls:['file.epub']}}"}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        BookDetail detail = engine.detail(source, engine.search(source, "a", 1).get(0));
        assertEquals("New", detail.hit().name());
        assertEquals("Description", detail.hit().intro());
        assertEquals("https://example.test/book/toc", detail.tocUrl());
        assertEquals(List.of("https://example.test/book/file.epub"), detail.downloadUrls());
    }

    @Test void ruleDetailEnrichesIntroAndCover() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleBookInfo":{
              "intro":"p.intro@text","coverUrl":"img.cover@src"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<p class='intro'>More detail</p><img class='cover' src='/cover.jpg'>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "Search snippet", "", Kind.TEXT);
        BookDetail detail = engine.detail(source, hit);
        assertEquals("More detail", detail.hit().intro());
        assertEquals("https://example.test/cover.jpg", detail.hit().coverUrl());
    }

    @Test void preflightFindsUnimplementedHostMethods() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"@js:java.get('https://example.test')"}
            """).sources().get(0);
        assertTrue(SourceImporter.preflight(source).stream().anyMatch(item -> item.code().equals("UNSUPPORTED_JS_HOST") && item.message().contains("get")));
    }

    @Test void jsonPathSearchUsesStructuredResults() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","searchUrl":"/search?key={{key}}",
             "ruleSearch":{"bookList":"@json:$.items[*]","name":"@json:$.name","bookUrl":"@json:$.url"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "{\"items\":[{\"name\":\"Json Book\",\"url\":\"/book\"}]}", 200, Map.of()));
        assertEquals("https://api.example.test/book", engine.search(source, "json", 1).get(0).url());
    }

    @Test void multiSourceSearchKeepsFailuresSeparate() {
        Source good = SourceImporter.parse(sourceJson).sources().get(0);
        Source bad = SourceImporter.parse("""
            {"bookSourceUrl":"https://bad.test","searchUrl":"/search","ruleSearch":{"bookList":"div.book"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> {
            if (request.url().contains("bad.test")) throw new IllegalStateException("unavailable");
            return new FetchResponse(request.url(), "<div class='book'><a href='/b'>B</a><span class='author'>A</span></div>", 200, Map.of());
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            SearchResult result = new SearchCoordinator(engine, pool).search(List.of(good, bad), "alpha", 1);
            assertEquals(1, result.hits().size());
            assertTrue(result.errors().containsKey(bad.id()));
            assertEquals(good.id(), result.hits().get(0).sourceId());
        } finally { pool.shutdownNow(); }
    }

    @Test void xpathAndRegexRulesHandleHtmlFixture() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        String html = "<html><body><a href='/book'>Fixture</a><p>Code: 123</p></body></html>";
        assertEquals("Fixture", evaluator.one("@xpath://a", html, source.id(), source, false));
        assertEquals(List.of("123"), evaluator.many("@regex:\\d+", html, source.id(), source));
    }

    @Test void requestTemplateEvaluatesExpressionsAfterEs6Script() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","searchUrl":"@js: const suffix = 'novel'; result = `/api/${suffix}?page={{page-1}}&query={{key}}&level={{source.getLoginHeader() ? 0 : 1}}`; result;"}
            """).sources().get(0);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals("https://api.example.test/api/novel?page=0&query=%E6%97%A0%E8%81%8C&level=1",
                planner.plan(source.string("searchUrl"), source, "无职", 1, source.id()).url());
    }

    @Test void alternativeJsonFieldsFallbackWhenFirstIsMissing() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals("日文书名", evaluator.one("$.titleZh||$.titleJp", Map.of("titleJp", "日文书名"), source.id(), source, false));
        assertEquals("", evaluator.one("$.cover", Map.of("titleJp", "日文书名"), source.id(), source, false));
    }

    @Test void fieldTemplatesAndJsRulesCanReadJsonListItems() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        Map<String, String> item = Map.of("titleJp", "日文书名", "providerId", "site", "novelId", "42");
        assertEquals("日文书名 - site", evaluator.one("{{$.titleJp}} - {{$.providerId}}", item, source.id(), source, false));
        assertEquals("https://example.test/api/novel/site/42",
                evaluator.one("@js:`/api/novel/{{$.providerId}}/{{$.novelId}}`", item, source.id(), source, true));
        assertEquals("https://example.test/api/novel/site/42",
                evaluator.one("@js:result.id ? `/api/wenku/{{$.id}}` : `/api/novel/{{$.providerId}}/{{$.novelId}}`",
                        item, source.id(), source, true));
    }

    @Test void optionalDirectDownloadRuleDoesNotBlockTextDetail() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleBookInfo":{"name":"$.title","downloadUrls":"@js:missingOnlyForFileBooks.value"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "{\"title\":\"Usable\"}", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Fallback", "", "", "", Kind.TEXT);
        assertEquals("Usable", engine.detail(source, hit).hit().name());
    }

    @Test void sourceScopedScriptVariablesSurviveSeparateRuleEvaluations() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RhinoRuntime script = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertEquals("keyword", script.evaluate("java.put('term','keyword')", Map.of(), source));
        assertEquals("keyword", script.evaluate("java.get('term')", Map.of(), source));
    }

    @Test void angleBracketJavaScriptRulesCanReturnListsAndStrings() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals(List.of("A", "B"), evaluator.many("<js>['A','B']</js>", "", source.id(), source));
        assertEquals("A", evaluator.one("<js>'A'</js>", "", source.id(), source, false));
    }

    @Test void trailingTripleHashExtractsFirstRegexMatch() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals("/next.html", evaluator.one("##url_next:'([^']*)'##$1###",
                "url_next:'/next.html'; url_next:'/later.html'", source.id(), source, false));
        assertEquals("", evaluator.one("##missing:(\\d+)##$1###", "no link", source.id(), source, false));
    }
}
