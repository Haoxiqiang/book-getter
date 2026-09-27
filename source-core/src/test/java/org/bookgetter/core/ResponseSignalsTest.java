package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Models.FetchRequest;
import org.junit.jupiter.api.Test;

final class ResponseSignalsTest {
    @Test void visibleCharacterCountExcludesMarkup() {
        assertEquals(5, ResponseSignals.visibleCharacters("<div><b>Hello</b><script>ignored()</script></div>"));
        assertEquals(0, ResponseSignals.visibleCharacters(null));
    }

    @Test void javascriptCatalogLinksAreReportedAsPlaceholders() {
        assertTrue(ResponseSignals.javascriptPlaceholderUrl("javascript:"));
        assertTrue(ResponseSignals.javascriptPlaceholderUrl("  JavaScript:void(0)"));
        assertFalse(ResponseSignals.javascriptPlaceholderUrl("https://example.test/chapter/1"));
        assertFalse(ResponseSignals.javascriptPlaceholderUrl(null));
    }

    @Test void probeUrlLoggingOmitsQueriesAndInvalidObjectText() {
        assertEquals("https://example.test/chapter/1", ResponseSignals.urlForLog(
                "https://example.test/chapter/1?token=secret#fragment"));
        assertEquals("http://example.test:18766/chapter/1", ResponseSignals.urlForLog(
                "http://example.test:18766/chapter/1?token=secret"));
        assertEquals("https://example.test/chapter/1 [options]", ResponseSignals.urlForLog(
                "https://example.test/chapter/1,{\"headers\":{\"Authorization\":\"secret\"}}"));
        assertTrue(ResponseSignals.urlForLog("{links={reader=/chapter},abstract=private text}")
                .startsWith("<invalid-url chars="));
    }

    @Test void crossHostAndAcceptedResponsesMakeEmptySearchUnverified() {
        FetchRequest request = new FetchRequest("https://old.example/search?q=book");
        FetchResponse migrated = new FetchResponse("https://new.example/", "<html>Home</html>", 200, Map.of());
        assertTrue(ResponseSignals.probeSignals(request, migrated).contains("CROSS_HOST_REDIRECT"));
        assertTrue(ResponseSignals.probeSignals(request, migrated).contains("SEARCH_ENDPOINT_TO_ROOT"));
        assertTrue(ResponseSignals.suspectEmptySearch("CROSS_HOST_REDIRECT"));
        assertTrue(ResponseSignals.suspectEmptySearch("SEARCH_ENDPOINT_TO_ROOT"));
        assertTrue(ResponseSignals.probeSignals(new FetchRequest("https://new.example/modules/article/search.php"),
                new FetchResponse("https://new.example/", "<html>Home</html>", 200, Map.of()))
                .contains("SEARCH_ENDPOINT_TO_ROOT"));
        FetchResponse accepted = new FetchResponse(request.url(), "<html>Pending</html>", 202, Map.of());
        assertTrue(ResponseSignals.probeSignals(request, accepted).contains("NON_200_SUCCESS"));
        assertFalse(ResponseSignals.probeSignals(request,
                new FetchResponse(request.url(), "<html>Nothing found</html>", 200, Map.of()))
                .stream().anyMatch(ResponseSignals::suspectEmptySearch));
    }

    @Test void detectsTransportAndPageFailuresWithoutTreatingEveryZeroAsFailure() {
        assertEquals("UNRESOLVED_REDIRECT", quality(302, ""));
        assertEquals("ACCESS_DENIED", quality(403, "blocked"));
        assertEquals("EMPTY_RESPONSE", quality(200, ""));
        assertEquals("PARKED_DOMAIN", quality(200, "<p>This domain may be for sale.</p>"));
        assertEquals("PARKED_DOMAIN", quality(200,
                "<html><title>example.test</title><h1>[example.test] is for sale</h1>"
                        + "<h3>域名 [example.test] 正在出售中</h3></html>"));
        assertEquals("CLIENT_SIDE_REDIRECT", quality(200,
                "<!doctype html><html><head><script>window.onload=function(){"
                        + "window.location.href=\"/lander?page=1\"}</script></head></html>"));
        assertTrue(ResponseSignals.suspectEmptySearch("CLIENT_SIDE_REDIRECT"));
        assertEquals("CHALLENGE_PAGE", quality(200, "<title>Just a moment...</title>"));
        assertEquals("PAGE_NOT_FOUND", quality(200,
                "<html><title>提示信息</title><body>所访问的页面不存在</body></html>"));
        assertTrue(ResponseSignals.suspectEmptySearch("PAGE_NOT_FOUND"));
        assertEquals("UNCLASSIFIED", quality(200, "<title>提示信息</title><p>请稍后再试</p>"));
        assertEquals("APP_UPGRADE_REQUIRED", quality(200, "{\"code\":0,\"chapter\":{\"body\":\"当前版本过低，请升级到最新版\"}}"));
        StringBuilder escapedNotice = new StringBuilder();
        for (char character : "当前版本过低，请升级到最新版".toCharArray())
            escapedNotice.append(String.format("\\u%04x", (int) character));
        assertEquals("APP_UPGRADE_REQUIRED", quality(200,
                "{\"code\":0,\"chapter\":{\"body\":\"" + escapedNotice + "\"}}"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"errno\":2,\"errmsg\":\"漫画不存在\",\"data\":{}}"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"code\":100103,\"message\":\"PARAM_INVALID\",\"data\":null}"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"code\":-1,\"msg\":\"没有该用户!\"}"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"code\":4004,\"msg\":\"missing fields\",\"data\":[]}"));
        assertEquals("LOGIN_REQUIRED", quality(200,
                "{\"ret\":303,\"needLogin\":true,\"loginUrl\":\"https://example.test/login\"}"));
        assertEquals("LOGIN_REQUIRED", ResponseSignals.quality(new FetchResponse(
                "https://example.test/user/login?from=search",
                "<html><title>登录 - 百合会</title><form><input type='password'></form></html>",
                200, Map.of())));
        assertEquals("UNCLASSIFIED", ResponseSignals.quality(new FetchResponse(
                "https://example.test/user/login", "<title>Search results</title>", 200, Map.of())));
        assertEquals("UNCLASSIFIED", ResponseSignals.quality(new FetchResponse(
                "https://example.test/books/login-guide", "<title>登录指南</title>", 200, Map.of())));
        assertTrue(ResponseSignals.suspectEmptySearch("LOGIN_REQUIRED"));
        assertEquals("UNCLASSIFIED", quality(200, "{\"errno\":0,\"data\":[]}"));
        assertEquals("UNCLASSIFIED", quality(200, "{\"code\":0,\"message\":\"OK\",\"data\":null}"));
        assertEquals("UNCLASSIFIED", quality(200, "<html><title>搜索结果</title></html>"));
    }

    @Test void baiduSearchChallengeIsNotParsedAsBookDetail() {
        assertEquals("CHALLENGE_PAGE", ResponseSignals.quality(new FetchResponse(
                "https://www.baidu.com/s?wd=book", "<html><title>百度安全验证</title><h1>请验证</h1></html>",
                200, Map.of())));
        assertEquals("CHALLENGE_PAGE", ResponseSignals.quality(new FetchResponse(
                "https://wappass.baidu.com/static/captcha/tuxing_v2.html",
                "<html><title>百度安全验证</title></html>", 200, Map.of())));
        assertEquals("CHALLENGE_PAGE", ResponseSignals.quality(new FetchResponse(
                "https://baike.baidu.com/item/book", "<title>百度百科</title>"
                        + "<script>window.__PARIS_CHALLENGE_START__=1;window.__CHALLENGE_CONFIG__={};</script>",
                200, Map.of())));
        assertEquals("UNCLASSIFIED", ResponseSignals.quality(new FetchResponse(
                "https://example.test/book", "<html><title>百度安全验证</title></html>", 200, Map.of())));
    }

    private static String quality(int status, String body) {
        return ResponseSignals.quality(new FetchResponse("https://example.test", body, status, Map.of()));
    }
}
