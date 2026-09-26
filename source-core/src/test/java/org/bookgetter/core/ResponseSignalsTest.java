package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Models.FetchRequest;
import org.junit.jupiter.api.Test;

final class ResponseSignalsTest {
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
        assertEquals("CHALLENGE_PAGE", quality(200, "<title>Just a moment...</title>"));
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
        assertEquals("UNCLASSIFIED", quality(200, "{\"errno\":0,\"data\":[]}"));
        assertEquals("UNCLASSIFIED", quality(200, "{\"code\":0,\"message\":\"OK\",\"data\":null}"));
        assertEquals("UNCLASSIFIED", quality(200, "<html><title>搜索结果</title></html>"));
    }

    private static String quality(int status, String body) {
        return ResponseSignals.quality(new FetchResponse("https://example.test", body, status, Map.of()));
    }
}
