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

    @Test void matchingFinalSearchUrlUsesDetailRulesWithoutFetchingAgain() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"/search?q={{key}}",
             "bookUrlPattern":"https://example\\\\.test/book/[0-9]+",
             "ruleSearch":{"bookList":"li.result","name":"a@text","bookUrl":"a@href"},
             "ruleBookInfo":{"name":"h1@text","author":".author@text","intro":".intro@text"}}
            """).sources().get(0);
        int[] fetches = {0};
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        Transport transport = request -> {
            fetches[0]++;
            return new FetchResponse("https://example.test/book/42", """
                <h1>Direct Result</h1><span class='author'>Writer</span>
                <p class='intro'>Summary</p>
                """, 200, Map.of());
        };
        SourceEngine engine = new SourceEngine(transport, new RhinoRuntime(transport), diagnostics::add);
        List<BookHit> hits = engine.search(source, "title", 1);
        assertEquals(1, fetches[0]);
        assertEquals(1, hits.size());
        assertEquals("https://example.test/book/42", hits.get(0).url());
        assertEquals("Direct Result", hits.get(0).name());
        assertEquals("Writer", hits.get(0).author());
        assertEquals("Summary", hits.get(0).intro());
        assertEquals(List.of("DIRECT_DETAIL_URL_PATTERN"), diagnostics.stream().map(Diagnostic::code).toList());
    }

    @Test void emptySearchListFallsBackToDetailOnlyWhenPatternIsAbsent() {
        String rules = """
            "searchUrl":"/search?q={{key}}",
            "ruleSearch":{"bookList":"li.result","name":"a@text","bookUrl":"a@href"},
            "ruleBookInfo":{"name":"h1@text","author":".author@text"}
            """;
        Source withoutPattern = SourceImporter.parse("{" +
                "\"bookSourceUrl\":\"https://example.test\"," + rules + "}").sources().get(0);
        Source withPattern = SourceImporter.parse("{" +
                "\"bookSourceUrl\":\"https://example.test\",\"bookUrlPattern\":\"/never\"," + rules + "}")
                .sources().get(0);
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        Transport transport = request -> new FetchResponse(request.url(),
                "<h1>Fallback</h1><span class='author'>Writer</span>", 200, Map.of());
        SourceEngine engine = new SourceEngine(transport, new RhinoRuntime(transport), diagnostics::add);
        assertEquals("Fallback", engine.search(withoutPattern, "title", 1).get(0).name());
        assertEquals(List.of("DIRECT_DETAIL_EMPTY_LIST"), diagnostics.stream().map(Diagnostic::code).toList());
        diagnostics.clear();
        assertTrue(engine.search(withPattern, "title", 1).isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test void invalidBookUrlPatternReportsItsOwnField() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"/search?q={{key}}",
             "bookUrlPattern":"["}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "<h1>Book</h1>", 200, Map.of()));
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.search(source, "book", 1));
        assertEquals(Stage.SEARCH, failure.stage);
        assertEquals("bookUrlPattern", failure.field);
    }

    @Test void legacyDomIndexesSelectFieldElementsAndExcludeHeaderRows() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","bookSourceName":"indexed",
             "searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"class.results@tag.li!0",
               "name":"class.title@tag.a.0@text",
               "bookUrl":"class.title@tag.a.0@href",
               "author":"class.meta@tag.span.-1@text"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), """
            <ul class='results'>
              <li><span>Header</span></li>
              <li><div class='title'><a href='/first'>First</a><a href='/other'>Other</a></div>
                  <div class='meta'><span>Type</span><span>Author A</span></div></li>
              <li><div class='title'><a href='/second'>Second</a></div>
                  <div class='meta'><span>Author B</span></div></li>
            </ul>
            """, 200, Map.of()));
        List<BookHit> hits = engine.search(source, "query", 1);
        assertEquals(2, hits.size());
        assertEquals("First", hits.get(0).name());
        assertEquals("https://example.test/first", hits.get(0).url());
        assertEquals("Author A", hits.get(0).author());
        assertEquals("Second", hits.get(1).name());
    }

    @Test void urlFieldUsesFirstMatchedDomAttributeInsteadOfJoiningMultipleLinks() {
        assertEquals("http://example.test:18766/one",
                RuleEvaluator.absolute("http://example.test:18766/catalog", "/one"));
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","bookSourceName":"links",
             "searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"tbody@tag.tr","name":"tag.td.0@text",
               "bookUrl":"tag.a@href"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), """
            <table><tbody><tr><td><a href='/book/1432/'>First</a></td>
              <td><a href='/txt/1432.html'>TXT</a></td></tr></tbody></table>
            """, 200, Map.of()));
        List<BookHit> hits = engine.search(source, "book", 1);
        assertEquals(1, hits.size());
        assertEquals("https://example.test/book/1432/", hits.get(0).url());
        RuleEvaluator rules = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals("/book/1432/\n/txt/1432.html", rules.one("tag.a@href",
                "<tr><td><a href='/book/1432/'>First</a></td><td><a href='/txt/1432.html'>TXT</a></td></tr>",
                source.id(), source, false));
    }

    @Test void safeDomSelectAndHasClassDriveVipCatalogRule() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleToc":{
              "chapterList":"li.chapter","chapterName":"a@text","chapterUrl":"a@href",
              "isVip":"@js:result.select('a').hasClass('isvip')"}}
            """).sources().get(0);
        Transport transport = request -> new FetchResponse(request.url(), """
            <ul><li class='chapter'><a href='/one' class='name'>One</a></li>
                <li class='chapter'><a href='/two' class='name isvip'>Two</a></li></ul>
            """, 200, Map.of());
        RhinoRuntime runtime = new RhinoRuntime(transport);
        SourceEngine engine = new SourceEngine(transport, runtime);
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source, new BookDetail(hit,
                "https://example.test/catalog", List.of()));
        assertEquals(2, chapters.size());
        assertFalse(chapters.get(0).vip());
        assertTrue(chapters.get(1).vip());
        Object node = org.jsoup.Jsoup.parse("<li class='chapter'><a href='/two' class='isvip'>Two</a></li>")
                .selectFirst("li");
        assertEquals(true, runtime.evaluate("result.hasClass('chapter') && result.select('a').hasClass('isvip')",
                Map.of("result", node), source));
        assertEquals("/two", runtime.evaluate("result.select('a').first().attr('href')",
                Map.of("result", node), source));
        assertEquals(1, runtime.evaluate("var selected=result.select('a'); selected.length=0; selected.length",
                Map.of("result", node), source));
        assertEquals("undefined", runtime.evaluate("typeof result.select('a').getClass",
                Map.of("result", node), source));
    }

    @Test void payMarkerIsIndependentFromVipAndSupportsChainedJsonPathJs() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleToc":{
              "chapterList":"$.chapterlist","chapterName":"$.chaptername",
              "chapterUrl":"$.chapterurl","isVip":"$.isvip",
              "isPay":"$..point<js>result!=0?false:true</js>"}}
            """).sources().get(0);
        Transport transport = request -> new FetchResponse(request.url(), """
            {"chapterlist":[
              {"chaptername":"Free","chapterurl":"/one","isvip":0,"point":0},
              {"chaptername":"VIP","chapterurl":"/two","isvip":1,"point":5}]}
            """, 200, Map.of());
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        List<Chapter> chapters = new SourceEngine(transport)
                .chapters(source, new BookDetail(hit, "https://example.test/catalog", List.of()));
        assertEquals(2, chapters.size());
        assertFalse(chapters.get(0).vip());
        assertTrue(chapters.get(0).isPay());
        assertTrue(chapters.get(1).vip());
        assertFalse(chapters.get(1).isPay());
        assertFalse(new Chapter(hit.url(), "https://example.test/three", "Legacy", 2, false, false).isPay());
    }

    @Test void embeddedScriptCanFeedJsonPathInListAndFieldRules() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://chain.test","searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"<js>result=JSON.stringify({data:{list:[{name:'A',url:'/a'},{name:'B',url:'/b'}]}});result</js> $.data.list",
               "name":"$.name","bookUrl":"$.url"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request ->
                new FetchResponse(request.url(), "{}", 200, Map.of()));
        List<BookHit> hits = engine.search(source, "book", 1);
        assertEquals(List.of("A", "B"), hits.stream().map(BookHit::name).toList());
        assertEquals("https://chain.test/b", hits.get(1).url());

        RuleEvaluator rules = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        assertEquals(List.of("A", "B"), rules.many(
                "<js>result=JSON.stringify(['A','B']);result</js> $.[*]",
                "{}", source.id(), source));
        assertEquals("A", rules.one("<js>result=JSON.stringify({title:'A'});result</js> $.title",
                "{}", source.id(), source, false));
        assertEquals("https://chain.test/ebook/42/", rules.one(
                "$.reader_uri||https://chain.test/ebook/{$.id}/",
                Map.of("id", 42), source.id(), source, true));
        Map<String, Object> chapter = Map.of("title", "Chapter", "links", Map.of("reader", "/reader/42"));
        assertEquals("Chapter", rules.one("text||$.title", chapter, source.id(), source, false));
        assertEquals("https://chain.test/reader/42", rules.one(
                "href||$.links.reader", chapter, source.id(), source, true));
        assertEquals("https://chain.test/reader/42,{\"webView\":true}", rules.one(
                "href||$.links.reader<js>result+',{\"webView\":true}'</js>",
                chapter, source.id(), source, true));
    }

    @Test void chapterUrlWithSourceOptionsResolvesAgainstCatalogUrl() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        FetchRequest request = planner.plan("/html/92696/33648343.html,{webView:“true”}",
                source, "", 1, "http://www.isiluke.la/html/92696/");
        assertEquals("http://www.isiluke.la/html/92696/33648343.html", request.url());
        assertTrue(request.webView());
        assertFalse(planner.plan("/chapter,{\"webView\":\"false\"}", source, "", 1,
                "http://www.isiluke.la/html/92696/").webView());
    }

    @Test void legacyBracketIndexesFollowInclusiveLegadoRanges() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator rules = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        String html = "<p><span>A</span><span>B</span><span>C</span><span>D</span></p>";
        String base = "https://example.test/";
        assertEquals("A\nB", rules.one("tag.p@tag.span[0:1]@text", html, base, source, false));
        assertEquals("D\nC\nB\nA", rules.one("tag.p@tag.span[-1:0]@text", html, base, source, false));
        assertEquals("A\nC", rules.one("tag.p@tag.span[0,2]@text", html, base, source, false));
        assertEquals("A\nC\nD", rules.one("tag.p@tag.span![1]@text", html, base, source, false));
        assertEquals("B\nD", rules.one("tag.p@tag.span[1:3:2]@text", html, base, source, false));
    }

    @Test void searchAndDetailPreserveBookMetadataWithoutConfusingMediaKind() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","bookSourceName":"metadata",
             "searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"class.item","name":"tag.h3@text","bookUrl":"tag.a@href",
               "kind":"tag.p.0@tag.span[0:1]@text","wordCount":"class.words@text",
               "lastChapter":"class.latest@text"},
             "ruleBookInfo":{"kind":"class.genre@text","wordCount":"class.words@text"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                request.url().contains("/search")
                        ? "<div class='item'><h3>Book</h3><a href='/book'>Read</a>"
                        + "<p><span>玄幻</span><span>完结</span><span>Extra</span></p>"
                        + "<b class='words'>100万字</b><b class='latest'>第九章</b></div>"
                        : "<div class='genre'>奇幻</div>", 200, Map.of()));
        BookHit hit = engine.search(source, "Book", 1).get(0);
        assertEquals(Kind.TEXT, hit.kind());
        assertEquals("玄幻\n完结", hit.genre());
        assertEquals("100万字", hit.wordCount());
        assertEquals("第九章", hit.lastChapter());
        BookHit detailed = engine.detail(source, hit).hit();
        assertEquals("奇幻", detailed.genre());
        assertEquals("100万字", detailed.wordCount());
        assertEquals("第九章", detailed.lastChapter());
    }

    @Test void staleCatalogClassDoesNotFuzzilyMatchChapterContainers() {
        String sourceTemplate = """
            {"bookSourceUrl":"https://example.test","bookSourceName":"catalog",
             "ruleToc":{"chapterList":"%s@tag.a","chapterName":"text","chapterUrl":"href"}}
            """;
        String html = "<div class='DivTd3'><a href='/one'>First chapter</a></div><div class='DivTd'>&nbsp;</div>";
        Source stale = SourceImporter.parse(sourceTemplate.formatted("class.DivTd")).sources().get(0);
        BookHit staleHit = new BookHit(stale.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), html, 200, Map.of()));
        SourceFailure failure = assertThrows(SourceFailure.class,
                () -> engine.chapters(stale, new BookDetail(staleHit, staleHit.url(), List.of())));
        assertEquals(Stage.CATALOG, failure.stage);
        assertEquals("ruleToc.chapterList", failure.field);

        Source corrected = SourceImporter.parse(sourceTemplate.formatted("class.DivTd3")).sources().get(0);
        BookHit correctedHit = new BookHit(corrected.id(), staleHit.url(), "Book", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(corrected, new BookDetail(correctedHit, correctedHit.url(), List.of()));
        assertEquals(1, chapters.size());
        assertEquals("https://example.test/one", chapters.get(0).url());
    }

    @Test void duplicateJavascriptChapterPlaceholdersFollowLegadoUrlDeduplication() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        String html = "<ul>"
                + "<li class='chapter'><a href='/one'>One</a></li>"
                + "<li class='chapter'><a href='javascript:'>Two</a></li>"
                + "<li class='chapter'><a href='/three'>Three</a></li>"
                + "<li class='chapter'><a href='javascript:'>Four</a></li></ul>";
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), html, 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source, new BookDetail(hit, hit.url(), List.of()));
        assertEquals(3, chapters.size());
        assertEquals(1, chapters.stream().filter(chapter ->
                ResponseSignals.javascriptPlaceholderUrl(chapter.url())).count());
    }

    @Test void directTextNodesAndAllMatchLegadoDomActions() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator rules = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        String html = "<div class='content'>before<a href='/x'>link</a>after<br>line2</div>";
        String base = "https://example.test/";
        assertEquals("before\nafter\nline2", rules.one("class.content@textNodes", html, base, source, false));
        Object item = rules.many("class.content", html, base, source).get(0);
        assertEquals("before\nafter\nline2", rules.one("@textNodes", item, base, source, false));
        String outer = rules.one("@css:div.content@all", html, base, source, false);
        assertEquals(outer, rules.one("@all", item, base, source, false));
        assertTrue(outer.startsWith("<div class=\"content\">"));
        assertTrue(outer.contains("<a href=\"/x\">link</a>"));
        assertTrue(outer.endsWith("</div>"));
    }

    @Test void htmlActionKeepsWrapperButRemovesScriptsAndStyles() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        RuleEvaluator rules = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        String html = "<div id='content'><style>.x{display:none}</style>"
                + "<p>Visible</p><script>window.bad=true</script></div>";
        String extracted = rules.one("id.content@html", html, "https://example.test/", source, false);
        assertTrue(extracted.startsWith("<div id=\"content\">"));
        assertTrue(extracted.contains("<p>Visible</p>"));
        assertFalse(extracted.contains("<script"));
        assertFalse(extracted.contains("<style"));
        assertTrue(rules.one("id.content@all", html, "https://example.test/", source, false)
                .contains("<script>window.bad=true</script>"));
    }

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

    @Test void businessErrorSearchIsNotAnEmptyResult() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "{\"code\":-1,\"msg\":\"No user\"}", 200, Map.of()));
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.search(source, "book", 1));
        assertEquals(Stage.SEARCH, failure.stage);
        assertEquals("searchUrl", failure.field);
    }

    @Test void redirectedLoginPageIsNotAnEmptySearchResult() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(
                "https://example.test/user/login", "<title>登录 - 示例站</title>", 200, Map.of()));
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.search(source, "book", 1));
        assertEquals(Stage.SEARCH, failure.stage);
        assertEquals("searchUrl", failure.field);
        assertTrue(failure.getMessage().contains("LOGIN_REQUIRED"));
    }

    @Test void failedHttpRequestDoesNotExposeSearchQueryInError() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "", 403, Map.of()));
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.search(source, "secret-query", 1));
        assertTrue(failure.getMessage().contains("https://example.test/search"));
        assertFalse(failure.getMessage().contains("secret-query"));
    }

    @Test void http200MissingDetailPageFailsBeforeCatalogRules() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<html><title>提示信息</title><body>所访问的页面不存在</body></html>",
                200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.detail(source, hit));
        assertEquals(Stage.DETAIL, failure.stage);
        assertEquals("ruleBookInfo.init", failure.field);
        assertTrue(failure.getMessage().contains("PAGE_NOT_FOUND"));
    }

    @Test void emptyAndChallengeResponsesAreNotRuleFailuresOrValidZeroResults() {
        Source source = SourceImporter.parse(sourceJson).sources().get(0);
        for (String body : List.of("", "<title>Just a moment...</title><p>Cloudflare challenge</p>",
                "<html><script>window.location.href=\"/lander\"</script></html>",
                "{\"ret\":303,\"needLogin\":true,\"loginUrl\":\"https://example.test/login\"}")) {
            SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), body, 200, Map.of()));
            SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.search(source, "book", 1));
            assertEquals(Stage.SEARCH, failure.stage);
            assertEquals("searchUrl", failure.field);
            assertTrue(failure.getMessage().contains("Unusable source response"));
        }
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
        assertTrue(SourceEngine.invalidContent("当前版本过低，已停止使用，请升级到最新版继续阅读。"));
    }

    @Test void appUpgradeNoticeIsNotAChapter() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","ruleContent":{"content":"$..body"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "{\"code\":0,\"chapter\":{\"body\":\"当前版本过低，请升级到最新版继续阅读。\"}}",
                200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://api.example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://api.example.test/one", "One", 0, false, false);
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.content(source, book, chapter));
        assertEquals(Stage.CONTENT, failure.stage);
        assertEquals("ruleContent.content", failure.field);
    }

    @Test void audioHtmlIsNotAPlayableResourceWithoutSourceSniffing() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://audio.example.test","bookSourceType":1,
             "ruleContent":{"content":"<js>result</js>","sourceRegex":".*\\\\.mp3"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<html><body>Player page</body></html>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://audio.example.test/book", "Book", "", "", "", Kind.AUDIO);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://audio.example.test/one", "One", 0, false, false);
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.content(source, book, chapter));
        assertEquals("ruleContent.sourceRegex", failure.field);
    }

    @Test void imageChapterRequiresImageResources() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://comic.example.test","bookSourceType":2,
             "ruleContent":{"content":"div.pages@html"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='pages'>No pages</div>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://comic.example.test/book", "Book", "", "", "", Kind.IMAGE);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://comic.example.test/one", "One", 0, false, false);
        assertEquals("ruleContent.content", assertThrows(SourceFailure.class,
                () -> engine.content(source, book, chapter)).field);
    }

    @Test void directAudioAndImageUrlsRemainUsable() {
        Source audio = SourceImporter.parse("""
            {"bookSourceUrl":"https://audio.example.test","bookSourceType":1,
             "ruleContent":{"content":"a.stream@href"}}
            """).sources().get(0);
        SourceEngine audioEngine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<a class='stream' href='/stream/1'>Play</a>", 200, Map.of()));
        BookHit audioHit = new BookHit(audio.id(), "https://audio.example.test/book", "Audio", "", "", "", Kind.AUDIO);
        BookDetail audioBook = new BookDetail(audioHit, audioHit.url(), List.of());
        Chapter audioChapter = new Chapter(audioHit.url(), "https://audio.example.test/one", "One", 0, false, false);
        assertEquals("https://audio.example.test/stream/1",
                audioEngine.content(audio, audioBook, audioChapter).resources().get(0).url());

        Source image = SourceImporter.parse("""
            {"bookSourceUrl":"https://comic.example.test","bookSourceType":2,
             "ruleContent":{"content":"div.pages@html"}}
            """).sources().get(0);
        SourceEngine imageEngine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='pages'><img src='/page/1.jpg'></div>", 200, Map.of()));
        BookHit imageHit = new BookHit(image.id(), "https://comic.example.test/book", "Comic", "", "", "", Kind.IMAGE);
        BookDetail imageBook = new BookDetail(imageHit, imageHit.url(), List.of());
        Chapter imageChapter = new Chapter(imageHit.url(), "https://comic.example.test/one", "One", 0, false, false);
        assertEquals("https://comic.example.test/page/1.jpg",
                imageEngine.content(image, imageBook, imageChapter).resources().get(0).url());
    }

    @Test void contentPaginationStopsAtKnownNextChapter() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@text","nextContentUrl":"a.next@href",
              "replaceRegex":"<js>result.replace(/first\\\\nsecond/, 'joined')</js>"}}
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
        SourceFailure missingBoundary = assertThrows(SourceFailure.class,
                () -> engine.content(source, book, chapter));
        assertEquals("ruleContent.nextContentUrl", missingBoundary.field);
        assertEquals("joined", engine.content(source, book, chapter, "https://example.test/two").text());
        assertEquals(List.of("https://example.test/one", "https://example.test/one-2"), fetched);
    }

    @Test void contentPaginationStopsBeforeReturningToBookPageAndWarns() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@text","nextContentUrl":"a.next@href"}}
            """).sources().get(0);
        List<String> fetched = new java.util.ArrayList<>();
        List<Diagnostic> warnings = new java.util.ArrayList<>();
        Transport transport = request -> {
            fetched.add(request.url());
            String body = switch (request.url()) {
                case "https://example.test/one" -> "<div class='content'>first</div><a class='next' href='/one-2'>Next</a>";
                case "https://example.test/one-2" -> "<div class='content'>second</div><a class='next' href='/book'>Index</a>";
                default -> throw new AssertionError("Fetched book page as chapter content: " + request.url());
            };
            return new FetchResponse(request.url(), body, 200, Map.of());
        };
        SourceEngine engine = new SourceEngine(transport, new RhinoRuntime(transport), warnings::add);
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        assertEquals("first\nsecond", engine.content(source, book, chapter, "https://example.test/two").text());
        assertEquals(List.of("https://example.test/one", "https://example.test/one-2"), fetched);
        assertEquals("PAGINATION_TO_CATALOG", warnings.get(0).code());
        assertEquals("ruleContent.nextContentUrl", warnings.get(0).field());
    }

    @Test void nextReadableChapterSkipsVolumeHeadings() {
        Chapter first = new Chapter("book", "https://example.test/one", "One", 0, false, false);
        Chapter volume = new Chapter("book", "Volume 2", "Volume 2", 1, true, false);
        Chapter second = new Chapter("book", "https://example.test/two", "Two", 2, false, false);
        List<Chapter> chapters = List.of(first, volume, second);
        assertEquals(second.url(), SourceEngine.nextReadableUrl(chapters, first));
        assertNull(SourceEngine.nextReadableUrl(chapters, second));
    }

    @Test void contentReplacementRunsJsThenTrailingRegex() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@text",
              "replaceRegex":"<js>result.replace(/X/g, 'Y')</js>\\n##AD##"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='content'>X AD</div>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        assertEquals("Y", engine.content(source, book, chapter).text());
    }

    @Test void replacementJsCanMapPrivateUseFontCharacters() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@text",
              "replaceRegex":"<js>unescape(escape(result).replace(/%uE800/g,'的'))</js>"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='content'>A&#xE800;B</div>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        assertEquals("A的B", engine.content(source, book, chapter).text());
    }

    @Test void contentReplacementRegexDoesNotConsumeFollowingLinesByDefault() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","ruleContent":{
              "content":"div.content@html",
              "replaceRegex":"##[（]本章未完.*[）]"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='content'>开头（本章未完）<br>后续正文</div>", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://example.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail book = new BookDetail(hit, hit.url(), List.of());
        Chapter chapter = new Chapter(hit.url(), "https://example.test/one", "One", 0, false, false);
        String content = engine.content(source, book, chapter).text();
        assertTrue(content.contains("后续正文"));
        assertFalse(content.contains("本章未完"));
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

    @Test void jsDomHostUsesCurrentResponseAndSetContent() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/search",
             "ruleSearch":{"bookList":"@js:java.getElement('class.results@tag.li')",
               "name":"@js:java.setContent(result); java.getString('class.title@text')",
               "bookUrl":"a@href"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<ul class='results'><li><a href='/one'><span class='title'>One</span></a></li>"
                        + "<li><a href='/two'><span class='title'>Two</span></a></li></ul>",
                200, Map.of()));
        var hits = engine.search(source, "book", 1);
        assertEquals(2, hits.size());
        assertEquals("One", hits.get(0).name());
        assertEquals("https://novel.example/two", hits.get(1).url());
    }

    @Test void inlineAttributeThenJsRuleFindsLiveStyleBookUrl() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/search",
             "ruleSearch":{"bookList":".v-list-item","name":".v-title@text",
               "bookUrl":"@onclick@js:result.match(/'(.*?)'/)[1]"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<div class='v-list-item' onclick=\"newWebView('/b/229093.html', '', '')\">"
                        + "<div class='v-title'>灵破苍穹</div></div>", 200, Map.of()));
        BookHit hit = engine.search(source, "苍穹", 1).get(0);
        assertEquals("灵破苍穹", hit.name());
        assertEquals("https://novel.example/b/229093.html", hit.url());
    }

    @Test void defaultMultiClassSelectorUsesJsoupClassNameSemantics() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/sa",
             "ruleSearch":{"bookList":"class.ser-ret@tag.li",
               "name":"class.missing@text||class.mb5 fs20 f_mbo pt5 ell@tag.a@text",
               "bookUrl":"class.mb5 fs20 f_mbo pt5 ell@tag.a@href"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "<ul class='ser-ret'><li><h3 class='mb5 fs20 f_mbo pt5 ell'>"
                        + "<a href='/book/17551/'>斗破苍穹</a></h3></li></ul>", 200, Map.of()));
        BookHit hit = engine.search(source, "斗破苍穹", 1).get(0);
        assertEquals("斗破苍穹", hit.name());
        assertEquals("https://novel.example/book/17551/", hit.url());
    }

    @Test void javaAjaxUsesLegadoRequestOptionsInsteadOfLiteralUrl() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example"}
            """).sources().get(0);
        java.util.concurrent.atomic.AtomicReference<FetchRequest> observed = new java.util.concurrent.atomic.AtomicReference<>();
        RhinoRuntime runtime = new RhinoRuntime(request -> {
            observed.set(request);
            return new FetchResponse(request.url(), "response", 200, Map.of());
        });
        Object value = runtime.evaluate("java.ajax(\"/search,{\\\"method\\\":\\\"POST\\\",\\\"body\\\":\\\"keyword={{key}}\\\"}\")",
                Map.of("key", "book", "page", 1), source);
        assertEquals("response", value);
        assertEquals("https://novel.example/search", observed.get().url());
        assertEquals("POST", observed.get().method());
        assertEquals("keyword=book", observed.get().body());
    }

    @Test void sourceNotificationsAreObservableAndInteractiveVerificationIsExplicit() {
        Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://verify.test\"}").sources().get(0);
        java.util.List<String> notifications = new java.util.ArrayList<>();
        RhinoRuntime runtime = new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of()), notifications::add);
        assertEquals("done", runtime.evaluate("java.toast('short'); java.longToast('long'); 'done'",
                Map.of(), source));
        assertEquals(List.of("short", "long"), notifications);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> runtime.evaluate(
                "java.startBrowserAwait('https://verify.test', 'Verify')", Map.of(), source));
        assertTrue(failure.getMessage().contains("Interactive browser verification is unavailable"));
    }

    @Test void jsonPathMapItemKeepsLegadoStringViewWithoutExposingJavaObject() {
        Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://audio.test\"}").sources().get(0);
        RhinoRuntime runtime = new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of()));
        Object value = runtime.evaluate("String(result).match(/id=(\\d+)/)[1] + ':' + result.id",
                Map.of("result", Map.of("id", 123, "catalog_name", "Audio")), source);
        assertEquals("123:123", value);
        assertEquals(false, runtime.evaluate("!!result.getClass", Map.of("result", Map.of("id", 123)), source));
    }

    @Test void signedUrlHostUsesUtf8Base64AndLowercaseMd5() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://signed.example","searchUrl":"/search?token={{java.md5Encode(java.base64Encode('abc'))}}"}
            """).sources().get(0);
        FetchRequest request = new RequestPlanner(new RhinoRuntime(unused ->
                new FetchResponse(unused.url(), "", 200, Map.of())))
                .plan(source.string("searchUrl"), source, "book", 1, source.id());
        assertEquals("https://signed.example/search?token=f4c0128178a6a21b7a3dd76729725d91", request.url());
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

    @Test void urlOptionsAcceptStringEncodedHeadersLikeLegado() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"http://app2.motie.com",
             "searchUrl":"/search?word={{key}}&pageNo={{page}},{\\\"headers\\\":\\\"{os:'pc'}\\\"}"}
            """).sources().get(0);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        FetchRequest request = planner.plan(source.string("searchUrl"), source, "斗破苍穹", 1, source.id());
        assertEquals("http://app2.motie.com/search?word="
                + java.net.URLEncoder.encode("斗破苍穹", java.nio.charset.StandardCharsets.UTF_8)
                + "&pageNo=1", request.url());
        assertEquals("pc", request.headers().get("os"));
        assertEquals("GET", request.method());
    }

    @Test void javaPostExposesRedirectLocationWithoutFollowingIt() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"<js>java.post('https://example.test/form','searchkey='+key,{referer:'https://example.test/',cookie:'sex=boy'}).header('Location')</js>",
             "ruleSearch":{"bookList":"div.book","name":"a@text","bookUrl":"a@href"}}
            """).sources().get(0);
        List<FetchRequest> requests = new java.util.ArrayList<>();
        Transport transport = request -> {
            requests.add(request);
            if (request.url().equals("https://example.test/form"))
                return new FetchResponse(request.url(), "", 302, Map.of("location", "/results"));
            return new FetchResponse(request.url(),
                    "<div class='book'><a href='/book/1'>Book</a></div>", 200, Map.of());
        };
        RhinoRuntime runtime = new RhinoRuntime(transport);
        SourceEngine engine = new SourceEngine(transport, runtime);
        assertEquals("Book", engine.search(source, "故事", 1).get(0).name());
        assertEquals(2, requests.size());
        assertEquals("POST", requests.get(0).method());
        assertFalse(requests.get(0).followRedirects());
        assertEquals("searchkey=故事", requests.get(0).body());
        assertEquals("sex=boy", requests.get(0).headers().get("cookie"));
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8",
                requests.get(0).headers().get("Content-Type"));
        assertEquals("https://example.test/results", requests.get(1).url());
        assertTrue(requests.get(1).followRedirects());
        assertEquals(302, runtime.evaluate(
                "java.post('https://example.test/form','a=1','{\"referer\":\"https://example.test/\"}').statusCode()",
                Map.of(), source));
        assertEquals("undefined", runtime.evaluate(
                "typeof java.post('https://example.test/form','a=1',{}).getClass", Map.of(), source));
        assertThrows(RuntimeException.class, () -> runtime.evaluate(
                "java.post('https://example.test/form','a=1',{},1000)", Map.of(), source));
    }

    @Test void webViewDelayOptionSurvivesRequestPlanning() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"/search,{\\\"webView\\\":true,\\\"webViewDelayTime\\\":2500}"}
            """).sources().get(0);
        FetchRequest request = new RequestPlanner(new RhinoRuntime(unused ->
                new FetchResponse(unused.url(), "", 200, Map.of())))
                .plan(source.string("searchUrl"), source, "book", 1, source.id());
        assertTrue(request.webView());
        assertEquals(2500, request.webViewDelayMillis());
    }

    @Test void postFormUsesConfiguredCharsetAndPreservesSeparators() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/search,{\\\"method\\\":\\\"POST\\\",\\\"charset\\\":\\\"GBK\\\",\\\"body\\\":\\\"keyword={{key}}&page={{page}}\\\"}"}
            """).sources().get(0);
        FetchRequest request = new RequestPlanner(new RhinoRuntime(unused ->
                new FetchResponse(unused.url(), "", 200, Map.of())))
                .plan(source.string("searchUrl"), source, "斗破苍穹", 2, source.id());
        assertEquals("POST", request.method());
        assertEquals("keyword=" + java.net.URLEncoder.encode("斗破苍穹", java.nio.charset.Charset.forName("GBK"))
                + "&page=2", request.body());
        assertTrue(request.headers().get("Content-Type").startsWith("application/x-www-form-urlencoded"));
    }

    @Test void postObjectBodyIsSerializedAsJsonLikeLegadoUrlOption() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/search,{\\"method\\":\\"POST\\",\\"body\\":{\\"keyword\\":{{key}},\\"page_index\\":{{page}}}}"}
            """).sources().get(0);
        FetchRequest request = new RequestPlanner(new RhinoRuntime(unused ->
                new FetchResponse(unused.url(), "", 200, Map.of())))
                .plan(source.string("searchUrl"), source, "斗破苍穹", 2, source.id());
        assertEquals("POST", request.method());
        assertEquals("斗破苍穹", com.google.gson.JsonParser.parseString(request.body())
                .getAsJsonObject().get("keyword").getAsString());
        assertEquals(2, com.google.gson.JsonParser.parseString(request.body())
                .getAsJsonObject().get("page_index").getAsInt());
        assertTrue(request.headers().get("Content-Type").startsWith("application/json"));
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

    @Test void unresolvedRedirectCannotMasqueradeAsEmptySearch() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://novel.example","searchUrl":"/search",
             "ruleSearch":{"bookList":"class.book@tag.li","name":"text","bookUrl":"a@href"}}
            """).sources().get(0);
        SourceFailure failure = assertThrows(SourceFailure.class,
                () -> new SourceEngine(request -> new FetchResponse(request.url(), "", 302,
                        Map.of("location", "http://parked.example"))).search(source, "book", 1));
        assertEquals(Stage.SEARCH, failure.stage);
        assertEquals("searchUrl", failure.field);
        assertTrue(failure.getMessage().contains("Unresolved HTTP redirect 302"));
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
            {"bookSourceUrl":"https://example.test","mainJs":"function search(key,page){return [{bookUrl:'/book',name:'A'}]}\nfunction getChapters(book){return [{url:'chapter/1',title:'One',isVip:true,isPay:true}]}\nfunction getContent(chapter,book,nextChapterUrl){return java.ajax(chapter.url)}"}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), "Chapter body", 200, Map.of()));
        BookHit hit = engine.search(source, "a", 1).get(0);
        assertEquals("https://example.test/book", hit.url());
        BookDetail detail = engine.detail(source, hit);
        Chapter chapter = engine.chapters(source, detail).get(0);
        assertEquals("https://example.test/chapter/1", chapter.url());
        assertTrue(chapter.vip());
        assertTrue(chapter.isPay());
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

    @Test void preflightSeparatesProvidedPartialAndMissingHostMethodsByField() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test",
             "searchUrl":"@js:java.ajax('https://example.test'); java.get('key')",
             "jsLib":"function helper(){ return java.head('https://example.test'); }",
             "ruleToc":{"chapterList":"@js:java.getString('li'); java.encodeURI(result)"}}
            """).sources().get(0);
        List<Diagnostic> diagnostics = SourceImporter.preflight(source);
        assertTrue(diagnostics.stream().anyMatch(item -> item.code().equals("PARTIAL_JS_HOST")
                && item.field().equals("searchUrl") && item.message().contains("get")
                && !item.message().contains("ajax")));
        assertTrue(diagnostics.stream().anyMatch(item -> item.code().equals("UNSUPPORTED_JS_HOST")
                && item.field().equals("jsLib") && item.message().contains("head")));
        assertTrue(diagnostics.stream().anyMatch(item -> item.code().equals("PARTIAL_JS_HOST")
                && item.field().equals("ruleToc.chapterList") && item.stage() == Stage.CATALOG
                && item.message().contains("encodeURI")));
        assertTrue(diagnostics.stream().anyMatch(item -> item.code().equals("PARTIAL_JS_HOST")
                && item.field().equals("ruleToc.chapterList") && item.message().contains("getString")));
    }

    @Test void base64DecodeAndEncodeUriUseDocumentedStringOverloads() throws Exception {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test","searchUrl":"@js:java.encodeURI(key)",
             "ruleSearch":{"bookList":"@js:java.base64Decode('5Lit5paH')"}}
            """).sources().get(0);
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertEquals("中文", runtime.evaluate("java.base64Decode('5Lit5paH')", Map.of(), source));
        String gbk = java.util.Base64.getEncoder().encodeToString("中文".getBytes("GBK"));
        assertEquals("中文", runtime.evaluate("java.base64Decode('" + gbk + "','GBK')", Map.of(), source));
        assertEquals("%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC",
                runtime.evaluate("java.encodeURI('中文 空格')", Map.of(), source));
        assertEquals("", runtime.evaluate("java.encodeURI('abc','invalid-charset')", Map.of(), source));
        assertTrue(SourceImporter.preflight(source).stream().noneMatch(item ->
                item.code().equals("UNSUPPORTED_JS_HOST")));
        assertTrue(SourceImporter.preflight(source).stream().anyMatch(item ->
                item.code().equals("PARTIAL_JS_HOST") && item.field().equals("ruleSearch.bookList")));
        assertThrows(Exception.class, () -> runtime.evaluate("java.base64Decode('YQ==',0)", Map.of(), source));

        com.google.gson.JsonObject actualRule = new com.google.gson.JsonObject();
        actualRule.addProperty("bookSourceUrl", "https://example.test");
        String template = "https://example.test/book/search.list.php?keyword={{java.encodeURI(java.encodeURI(key))}}"
                + ",{\"headers\":{\"referer\":\"https://example.test/book/search.php?keyword={{java.encodeURI(key)}}\"}}";
        actualRule.addProperty("searchUrl", template);
        Source encodedSource = new Source("https://example.test", "encoded", Kind.TEXT, actualRule);
        FetchRequest planned = new RequestPlanner(runtime).plan(template, encodedSource, "中文 空格", 1, encodedSource.id());
        assertEquals("https://example.test/book/search.list.php?keyword=%25E4%25B8%25AD%25E6%2596%2587%2B%25E7%25A9%25BA%25E6%25A0%25BC",
                planned.url());
        assertEquals("https://example.test/book/search.php?keyword=%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC",
                planned.headers().get("referer"));
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

    @Test void jsonPathCombinedListHandlesTwoApiResponseShapes() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","searchUrl":"/search?key={{key}}",
             "ruleSearch":{"bookList":"$..book_data[0]&&$.data[*]",
               "name":"$.book_name","bookUrl":"/book/{{$.book_id}}"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                request.url().endsWith("key=first")
                    ? "{\"rows\":[{\"book_data\":[{\"book_name\":\"First\",\"book_id\":1}]}]}"
                    : "{\"data\":[{\"book_name\":\"Second\",\"book_id\":2}]}",
                200, Map.of()));
        assertEquals("First", engine.search(source, "first", 1).get(0).name());
        assertEquals("Second", engine.search(source, "second", 1).get(0).name());
    }

    @Test void detailPutVariableFeedsCatalogGetWithoutCrossBookLeakage() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test",
             "ruleBookInfo":{"name":"$..bookName@put:{bid:$..x_id}","tocUrl":"/catalog/{{$.._id}}"},
             "ruleToc":{"chapterList":"$..chapters[*]","chapterName":"$.t",
               "chapterUrl":"https://content.example.test/@get:{bid}/{{$.l}}"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> {
            String body = request.url().contains("/info/a")
                    ? "{\"bookName\":\"First\",\"x_id\":\"A42\",\"_id\":\"a\"}"
                    : request.url().contains("/info/b")
                    ? "{\"bookName\":\"Second\",\"x_id\":\"B43\",\"_id\":\"b\"}"
                    : "{\"chapters\":[{\"t\":\"One\",\"l\":1}]}";
            return new FetchResponse(request.url(), body, 200, Map.of());
        });
        BookHit first = new BookHit(source.id(), "https://api.example.test/info/a", "First", "", "", "", Kind.TEXT);
        BookHit second = new BookHit(source.id(), "https://api.example.test/info/b", "Second", "", "", "", Kind.TEXT);
        BookDetail firstDetail = engine.detail(source, first);
        BookDetail secondDetail = engine.detail(source, second);
        assertEquals("https://content.example.test/A42/1", engine.chapters(source, firstDetail).get(0).url());
        assertEquals("https://content.example.test/B43/1", engine.chapters(source, secondDetail).get(0).url());
    }

    @Test void detailInitKeepsJsonObjectForFollowingFields() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","ruleBookInfo":{
              "init":"$.data","name":"$.novelName","author":"$.authorName",
              "tocUrl":"/novel/{{$.novelId}}/chapters"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "{\"code\":200,\"data\":{\"novelName\":\"Object Book\",\"authorName\":\"A\",\"novelId\":\"42\"}}",
                200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://api.example.test/book/42", "Search Book", "", "", "", Kind.TEXT);
        BookDetail detail = engine.detail(source, hit);
        assertEquals("Object Book", detail.hit().name());
        assertEquals("A", detail.hit().author());
        assertEquals("https://api.example.test/novel/42/chapters", detail.tocUrl());
    }

    @Test void detailBusinessErrorCannotBecomeAnEmptyCatalogUrl() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test","ruleBookInfo":{
              "init":"$.data","tocUrl":"/novel/{{$.novelId}}/chapters"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                "{\"code\":4004,\"msg\":\"Missing client headers\",\"data\":[]}", 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://api.example.test/book/42", "Book", "", "", "", Kind.TEXT);
        SourceFailure failure = assertThrows(SourceFailure.class, () -> engine.detail(source, hit));
        assertEquals(Stage.DETAIL, failure.stage);
        assertEquals("ruleBookInfo.init", failure.field);
    }

    @Test void combinedRulesDoNotSplitJsonPathFilterOperators() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://api.example.test"}
            """).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        String json = "{\"items\":[{\"name\":\"A\",\"enabled\":true,\"score\":2},"
                + "{\"name\":\"B\",\"enabled\":false,\"score\":3}]}";
        assertEquals(1, evaluator.many("$.items[?(@.enabled == true && @.score > 1)]", json, source.id(), source).size());
    }

    @Test void combinedHtmlFieldsAndInterleavedListsFollowLegadoOrder() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://example.test"}
            """).sources().get(0);
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        String html = "<div class='a'>A1</div><div class='a'>A2</div>"
                + "<div class='b'>B1</div><div class='b'>B2</div>";
        assertEquals("A1\nA2\nB1\nB2", evaluator.one("class.a@text&&class.b@text", html,
                source.id(), source, false));
        assertEquals("A1\nB1\nA2\nB2", evaluator.one("class.a@text%%class.b@text", html,
                source.id(), source, false));
        assertEquals(List.of("A1", "B1", "A2", "B2"), evaluator.many("class.a%%class.b", html,
                source.id(), source).stream().map(item -> evaluator.one("text", item, source.id(), source, false)).toList());
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
        assertEquals("Code: 123", evaluator.one("//p[@class='note']/text()",
                "<!doctype html><html><body><p class='note'>Code: 123</p></body></html>",
                source.id(), source, false));
        assertEquals("/book", evaluator.one("@XPath://a/@href", html, source.id(), source, false));
        assertEquals(List.of("123"), evaluator.many("@regex:\\d+", html, source.id(), source));
    }

    @Test void xpathCatalogKeepsSelectedElementsForChapterFields() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://xpath.test","bookSourceName":"xpath-catalog",
             "ruleToc":{"chapterList":"//*[@id='list']//dt[2]/following-sibling::dd/a",
                        "chapterName":"text","chapterUrl":"href"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), """
            <div id='list'><dt>Book</dt><dd><a href='/ignore'>Preface</a></dd>
            <dt>Chapters</dt><dd><a href='one.html'>Chapter One</a></dd>
            <dd><a href='/two.html'>Chapter Two</a></dd></div>
            """, 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://xpath.test/book", "Fixture", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source,
                new BookDetail(hit, "https://xpath.test/book/catalog.html", List.of()));
        assertEquals(2, chapters.size());
        assertEquals("Chapter One", chapters.get(0).title());
        assertEquals("https://xpath.test/book/one.html", chapters.get(0).url());
        assertEquals("https://xpath.test/two.html", chapters.get(1).url());
    }

    @Test void jsonInnerRuleInterpolatesOptionalSearchMetadata() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://metadata.test","searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"$.data[*]","name":"$.name","bookUrl":"$.url",
                           "wordCount":"{$.words}字"}}
            """).sources().get(0);
        java.util.ArrayList<Diagnostic> warnings = new java.util.ArrayList<>();
        Transport transport = request -> new FetchResponse(request.url(), """
            {"data":[{"name":"One","url":"/one","words":123},
                     {"name":"Two","url":"/two","words":456}]}
            """, 200, Map.of());
        SourceEngine engine = new SourceEngine(transport, new RhinoRuntime(transport), warnings::add);
        List<BookHit> hits = engine.search(source, "query", 1);
        assertEquals(2, hits.size());
        assertEquals("https://metadata.test/one", hits.get(0).url());
        assertEquals("123字", hits.get(0).wordCount());
        assertEquals("456字", hits.get(1).wordCount());
        assertTrue(warnings.isEmpty());
    }

    @Test void failingOptionalDetailFieldDoesNotHideCatalogUrl() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://detail.test","ruleBookInfo":{
             "name":"h1@text","tocUrl":"a.catalog@href",
             "wordCount":"script@text@js:result.textContent.match(/[0-9]+/)[0]"}}
            """).sources().get(0);
        java.util.ArrayList<Diagnostic> warnings = new java.util.ArrayList<>();
        Transport transport = request -> new FetchResponse(request.url(), """
            <h1>Fixture</h1><script>12345</script><a class='catalog' href='/toc'>Catalog</a>
            """, 200, Map.of());
        SourceEngine engine = new SourceEngine(transport, new RhinoRuntime(transport), warnings::add);
        BookHit hit = new BookHit(source.id(), "https://detail.test/book", "Old", "", "", "", Kind.TEXT);
        BookDetail detail = engine.detail(source, hit);
        assertEquals("Fixture", detail.hit().name());
        assertEquals("https://detail.test/toc", detail.tocUrl());
        assertEquals("", detail.hit().wordCount());
        assertEquals(1, warnings.size());
        assertEquals("ruleBookInfo.wordCount", warnings.get(0).field());
        assertEquals(Stage.DETAIL, warnings.get(0).stage());
    }

    @Test void pagedCatalogFollowsCssNextAndDeduplicatesChapterUrls() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://paged.test","ruleToc":{
             "chapterList":"id.list.0@tag.a","chapterName":"text","chapterUrl":"href",
             "nextTocUrl":"@css:#pages .gr:not([href='#'])@href"}}
            """).sources().get(0);
        java.util.ArrayList<String> fetched = new java.util.ArrayList<>();
        SourceEngine engine = new SourceEngine(request -> {
            fetched.add(request.url());
            String page = request.url().endsWith("/2") ? """
                <div id='list'><a href='/a'>A again</a><a href='/c'>C</a></div>
                <div id='pages'><a class='gr' href='#'>End</a></div>
                """ : """
                <div id='list'><a href='/a'>A</a><a href='/b'>B</a></div>
                <div id='pages'><a class='gr' href='/toc/2'>Next</a></div>
                """;
            return new FetchResponse(request.url(), page, 200, Map.of());
        });
        BookHit hit = new BookHit(source.id(), "https://paged.test/book", "Fixture", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source,
                new BookDetail(hit, "https://paged.test/toc/", List.of()));
        assertEquals(List.of("https://paged.test/toc/", "https://paged.test/toc/2"), fetched);
        assertEquals(List.of("https://paged.test/a", "https://paged.test/b", "https://paged.test/c"),
                chapters.stream().map(Chapter::url).toList());
    }

    @Test void catalogJsCanReadSelectedDomElementWithoutExposingJavaClass() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://dom-js.test","ruleToc":{
             "chapterList":"a[href^='markdown/ch/']","chapterName":"text","chapterUrl":"href",
             "isVolume":"@js:result.attr('href').endsWith('index.html')"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), """
            <a href='markdown/ch/volume/index.html'>Volume One</a>
            <a href='markdown/ch/volume/one.html'>Chapter One</a>
            """, 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://dom-js.test/book", "Fixture", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source,
                new BookDetail(hit, "https://dom-js.test/catalog", List.of()));
        assertEquals(2, chapters.size());
        assertTrue(chapters.get(0).volume());
        assertFalse(chapters.get(1).volume());
        assertEquals("https://dom-js.test/markdown/ch/volume/one.html", chapters.get(1).url());
        RuleEvaluator evaluator = new RuleEvaluator(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
        Object node = evaluator.many("a", "<a href='/safe'>Safe</a>", source.id(), source).get(0);
        assertEquals("/safe", evaluator.one("@js:result.attr('href')", node, source.id(), source, false));
        assertEquals("undefined", evaluator.one("@js:typeof result.getClass", node, source.id(), source, false));
        assertEquals("true", evaluator.one("@js:String(result).includes('<a href=')", node,
                source.id(), source, false));
    }

    @Test void xpathChapterHrefCanFeedEmbeddedJavaScript() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://chapter.test","ruleToc":{
             "chapterList":"//li[span]/a","chapterName":"text",
             "chapterUrl":"href<js>'https://api.chapter.test/content/'+result.match(/\\\\d+$/)[0]</js>"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(), """
            <ul><li><span>Free</span><a href='/book/123'>Chapter One</a></li>
            <li><a href='/book/456'>Not selected</a></li></ul>
            """, 200, Map.of()));
        BookHit hit = new BookHit(source.id(), "https://chapter.test/book", "Fixture", "", "", "", Kind.TEXT);
        List<Chapter> chapters = engine.chapters(source,
                new BookDetail(hit, "https://chapter.test/catalog", List.of()));
        assertEquals(1, chapters.size());
        assertEquals("https://api.chapter.test/content/123", chapters.get(0).url());
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
        assertEquals("O'Neil", evaluator.one("@js:'{{$.author}}'",
                Map.of("author", "O'Neil"), source.id(), source, false));
    }

    @Test void searchItemVariablesAndQuotedJsTemplatesProduceDistinctChapterUrls() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://easou.test","searchUrl":"/search?q={{key}}",
             "ruleSearch":{"bookList":"$[*]","name":"$.novelName@put:{gid:enid}",
               "bookUrl":"/toc?gid={{$.enid}}"},
             "ruleToc":{"chapterList":"$.chapters[*]","chapterName":"$.chapterName",
               "chapterUrl":"@js:'https://easou.test/show?gid=@get:{gid}&nid={{$.nid}}&st={{$.sort}}'"}}
            """).sources().get(0);
        SourceEngine engine = new SourceEngine(request -> new FetchResponse(request.url(),
                request.url().contains("/search")
                        ? "[{\"novelName\":\"A\",\"enid\":\"G1\"},"
                        + "{\"novelName\":\"B\",\"enid\":\"G2\"}]"
                        : "{\"chapters\":[{\"chapterName\":\"One\",\"nid\":\"101\",\"sort\":1},"
                        + "{\"chapterName\":\"Two\",\"nid\":\"102\",\"sort\":2}]}",
                200, Map.of()));
        List<BookHit> hits = engine.search(source, "x", 1);
        assertEquals(2, hits.size());
        assertEquals("G1", hits.get(0).variables().get("gid"));
        assertEquals("G2", hits.get(1).variables().get("gid"));
        List<Chapter> first = engine.chapters(source, engine.detail(source, hits.get(0)));
        List<Chapter> second = engine.chapters(source, engine.detail(source, hits.get(1)));
        assertEquals(List.of("https://easou.test/show?gid=G1&nid=101&st=1",
                "https://easou.test/show?gid=G1&nid=102&st=2"), first.stream().map(Chapter::url).toList());
        assertEquals("https://easou.test/show?gid=G2&nid=101&st=1", second.get(0).url());
    }

    @Test void detailVariablesRemainWithBookAcrossEngineInstances() {
        Source source = SourceImporter.parse("""
            {"bookSourceUrl":"https://scope.test","ruleBookInfo":{"name":"$.name@put:{token:$.token}"},
             "ruleToc":{"chapterList":"$.chapters[*]","chapterName":"$.title",
               "chapterUrl":"@js:'https://scope.test/chapter/@get:{token}/{{$.id}}'"}}
            """).sources().get(0);
        Transport transport = request -> new FetchResponse(request.url(),
                "{\"name\":\"Book\",\"token\":\"T1\",\"chapters\":[{\"title\":\"One\",\"id\":\"1\"}]}",
                200, Map.of());
        BookHit hit = new BookHit(source.id(), "https://scope.test/book", "Book", "", "", "", Kind.TEXT);
        BookDetail detail = new SourceEngine(transport).detail(source, hit);
        assertEquals("T1", detail.hit().variables().get("token"));
        assertEquals("https://scope.test/chapter/T1/1",
                new SourceEngine(transport).chapters(source, detail).get(0).url());
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
