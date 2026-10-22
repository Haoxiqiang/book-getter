package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

class RuleCompatibilityRegressionTest {
    private final Source source = new Source("https://example.test/", "test", Kind.TEXT, new JsonObject());
    private final RhinoRuntime scripts = new RhinoRuntime(request ->
            new FetchResponse(request.url(), "", 200, Map.of()));
    private final RuleEvaluator rules = new RuleEvaluator(scripts);
    private final RequestPlanner planner = new RequestPlanner(scripts);

    @Test void modePrefixesIgnoreCaseButPreservePropertyAndSelectorCase() {
        String html = "<a data-ID='AbC' href='/one'>First</a>";
        assertEquals("First", rules.one("@CSS:a[data-ID=AbC]@text", html, source.id(), source, false));
        assertEquals(1, rules.many("@CsS:a[data-ID=AbC]", html, source.id(), source).size());
        assertEquals("Value", rules.one("@Json:$.Name", "{\"Name\":\"Value\"}", source.id(), source, false));
        assertEquals(List.of("Value"), rules.stringList("@JSON:$.Name", "{\"Name\":\"Value\"}", source.id(), source, false));
        assertEquals("Value", rules.element("@JsOn:$.Name", "{\"Name\":\"Value\"}", source.id(), source));
        assertEquals(List.of("/one"), rules.stringList("@XPath://a/@href", html, source.id(), source, false));
    }

    @Test void spacedOptionsRetainMethodAndHeadersWhenResolvingRelativeUrls() {
        String suffix = " , \n\t{\"method\":\"POST\",\"body\":\"a=b\",\"headers\":{\"X-Test\":\"ok\"}}";
        String absolute = RuleEvaluator.absolute(source.id() + "book/", "../search" + suffix);
        var request = planner.plan(absolute, source, "", 1, source.id());
        assertEquals("https://example.test/search", request.url());
        assertEquals("POST", request.method());
        assertEquals("a=b", request.body());
        assertEquals("ok", request.headers().get("X-Test"));
        assertEquals(-1, RuleSyntax.urlOptionsStart("/s?q={{var x=[1, {a:2}];key}}"));
    }

    @Test void requestCharsetAppliesAfterAllTemplateExpansion() {
        for (String value : List.of("中文", "{{key}}", "{{key.trim()}}")) {
            var request = planner.plan("/s?q=" + value + ",{\"charset\":\"GBK\"}", source, "中文", 1, source.id());
            assertEquals("https://example.test/s?q=%D6%D0%CE%C4", request.url());
        }
        assertEquals("https://example.test/s?q=%D6%D0%CE%C4&x=%E4%B8%AD", planner.plan(
                "/s?q=中文&x=%E4%B8%AD,{\"charset\":\"GBK\"}", source, "", 1, source.id()).url());
        assertEquals("https://example.test/s?q=a%20b", planner.plan("/s?q={{key.trim()}}", source, "a b", 1, source.id()).url());
        assertEquals("q=%25D6%25D0", planner.plan(
                "/s,{\"method\":\"POST\",\"charset\":\"GBK\",\"body\":\"q=%D6%D0\"}", source, "", 1, source.id()).body());
        assertEquals("q=%D6%D0", planner.plan(
                "/s,{\"method\":\"POST\",\"body\":\"q=%D6%D0\"}", source, "", 1, source.id()).body());
    }

    @Test void listReplacementsPreserveItemBoundariesAndResolveUrlsAfterwards() {
        String html = "<a href='/one'>A</a><a href='/two'>B</a>";
        assertEquals(List.of("https://cdn.test/one", "https://cdn.test/two"), rules.stringList(
                "a@href##^/##https://cdn.test/", html, source.id(), source, true));
        assertEquals(List.of("One\ncontinued", "Two"), rules.stringList(
                "@Json:$.items[*]##^prefix-##", "{\"items\":[\"prefix-One\\ncontinued\",\"prefix-Two\"]}", source.id(), source, false));
        assertEquals(List.of("12", ""), rules.stringList(
                "$.items[*]##[0-9]+##$0###", "{\"items\":[\"x12y\",\"none\"]}", source.id(), source, false));
        assertEquals(List.of("A"), rules.stringList("a[data-x='##']@text", "<a data-x='##'>A</a>", source.id(), source, false));
        assertEquals(List.of("One\ncontinued", "Two"), rules.stringList(
                "$.first[*]&&$.second[*]##^prefix-##",
                "{\"first\":[\"prefix-One\\ncontinued\"],\"second\":[\"prefix-Two\"]}", source.id(), source, false));
    }

    @Test void requestOptionsCarryTimeoutRedirectPolicyAndScriptHeaders() {
        JsonObject json = new JsonObject();
        json.addProperty("header", "@js:JSON.stringify({'X-Test':key})");
        Source configured = new Source(source.id(), source.name(), source.kind(), json);
        var request = planner.plan("/s,{\"timeout\":1234,\"followRedirects\":false}", configured, "header-value", 1, source.id());
        assertEquals(1234, request.timeoutMillis());
        assertFalse(request.followRedirects());
        assertEquals("header-value", request.headers().get("X-Test"));
        assertEquals("book", request.policy().model());
        assertThrows(IllegalArgumentException.class, () -> planner.plan("/s,{\"timeout\":-1}", source, "", 1, source.id()));
    }

    @Test void missingRuleVariablesFallBackToPersistentSourceValues() {
        var states = new MemorySourceStateStore();
        states.put(SourcePolicy.of(source).stateKey(), "value:token", "source-token");
        var evaluator = new RuleEvaluator(scripts, states);
        assertEquals("source-token", evaluator.one("@get:{token}", "", source.id(), source, false));
        evaluator.enterBook(source, "/book", Map.of("token", "book-token"));
        try { assertEquals("book-token", evaluator.one("@get:{token}", "", source.id(), source, false)); }
        finally { evaluator.leaveBook(); }
    }
}
