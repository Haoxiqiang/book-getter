package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Map;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Source;
import org.junit.jupiter.api.Test;

class RequestPlannerTemplateTest {
    private static RequestPlanner planner() {
        return new RequestPlanner(new RhinoRuntime(request ->
                new FetchResponse(request.url(), "", 200, Map.of())));
    }
    private static Source source() { return new Source("搜索代理", "proxy", Kind.TEXT, new JsonObject()); }

    @Test void evaluatesMultilineJsWithSingleBracesAndPageChoices() {
        String rule = "https://example.test/search?q={{if(key){key=key.trim()} key}}&start=<0,{{page*10}}>";
        var first = planner().plan(rule, source(), " 中文 ", 1, source().id());
        var second = planner().plan(rule, source(), " 中文 ", 2, source().id());
        assertEquals("https://example.test/search?q=%E4%B8%AD%E6%96%87&start=0", first.url());
        assertEquals("https://example.test/search?q=%E4%B8%AD%E6%96%87&start=20", second.url());
    }

    @Test void optionDelimiterInsideJsDoesNotConsumeRequestOptions() {
        String rule = "https://example.test/search?q={{var items=[1,{x:2}]; key}}"
                + ",{\"headers\":{\"X-Test\":\"yes\"}}";
        var request = planner().plan(rule, source(), "中文", 1, source().id());
        assertEquals("https://example.test/search?q=%E4%B8%AD%E6%96%87", request.url());
        assertEquals("yes", request.headers().get("X-Test"));
    }
    @Test void ordinaryRuleScriptsExposeSourceHtmlAndStringListHost() {
        Source source = source();
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        String html = "<a href='/one'>One</a><a href='/two'>Two</a>";
        assertEquals("One", runtime.evaluate("org.jsoup.Jsoup.parse(src).select('a').first().text()",
                Map.of("result", html), source));
        assertEquals(2, ((Number) runtime.evaluate("java.getStringList('a@href').size()",
                Map.of("result", html, "baseUrl", "https://example.test/"), source)).intValue());
        assertEquals("/one", runtime.evaluate("java.getStringList('a@href').get(0)",
                Map.of("result", html, "baseUrl", "https://example.test/"), source));
    }
    @Test void sourceHostCanReturnItsStableBookSourceId() {
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        assertEquals("搜索代理", runtime.evaluate("source.getBookSourceUrl()", Map.of(), source()));
    }
    @Test void jsoupViewsCanRemoveSelectedNodesAndReadEachText() {
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        String html = "<main><script>junk</script><h1> Novel </h1><h2>Subtitle</h2><p>Body</p></main>";
        assertEquals("Novel|Subtitle|Body", runtime.evaluate(
                "var r=org.jsoup.Jsoup.parse(src); r.select('script').remove();"
                        + "r.select('h1,h2,p').eachText().join('|')",
                Map.of("result", html), source()));
        assertEquals("Novel Body", runtime.evaluate(
                "var r=org.jsoup.Jsoup.parse(src); r.select('script,h2').remove();r.select('main').text()",
                Map.of("result", html), source()));
    }
    @Test void nestedAjaxStopsAtCaptchaInsteadOfRepeatingIt() {
        Source source = source();
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(
                "https://wappass.baidu.com/static/captcha/tuxing_v2.html",
                "<title>百度安全验证</title>", 200, Map.of()));
        Exception error = assertThrows(Exception.class, () -> runtime.evaluate(
                "java.ajax('https://www.baidu.com/s?wd=book')", Map.of(), source));
        assertTrue(error.toString().contains("CHALLENGE_PAGE"));
    }
    @Test void bookHostStateIsScopedAndSupportsExplicitReset() {
        RhinoRuntime runtime = new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of()));
        runtime.enterBook(Map.of("bookUrl", "https://example.test/one"));
        assertEquals(true, runtime.evaluate("book.putVariable('chapterToken','one')", Map.of(), source()));
        assertEquals("one", runtime.evaluate("book.getVariable('chapterToken')", Map.of(), source()));
        runtime.evaluate("book.setReverseToc(true)", Map.of(), source());
        assertTrue(runtime.reverseToc());
        runtime.evaluate("book.setReverseToc(false)", Map.of(), source());
        assertFalse(runtime.reverseToc());
        runtime.leaveBook();
        runtime.enterBook(Map.of("bookUrl", "https://example.test/two"));
        assertEquals("", runtime.evaluate("book.getVariable('chapterToken')", Map.of(), source()));
        runtime.leaveBook();
    }
}
