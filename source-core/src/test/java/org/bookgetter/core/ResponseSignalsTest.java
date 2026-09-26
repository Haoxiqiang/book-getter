package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.bookgetter.core.Models.FetchResponse;
import org.junit.jupiter.api.Test;

final class ResponseSignalsTest {
    @Test void detectsTransportAndPageFailuresWithoutTreatingEveryZeroAsFailure() {
        assertEquals("UNRESOLVED_REDIRECT", quality(302, ""));
        assertEquals("ACCESS_DENIED", quality(403, "blocked"));
        assertEquals("EMPTY_RESPONSE", quality(200, ""));
        assertEquals("PARKED_DOMAIN", quality(200, "<p>This domain may be for sale.</p>"));
        assertEquals("CHALLENGE_PAGE", quality(200, "<title>Just a moment...</title>"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"errno\":2,\"errmsg\":\"漫画不存在\",\"data\":{}}"));
        assertEquals("BUSINESS_ERROR", quality(200, "{\"code\":100103,\"message\":\"PARAM_INVALID\",\"data\":null}"));
        assertEquals("UNCLASSIFIED", quality(200, "{\"errno\":0,\"data\":[]}"));
        assertEquals("UNCLASSIFIED", quality(200, "{\"code\":0,\"message\":\"OK\",\"data\":null}"));
        assertEquals("UNCLASSIFIED", quality(200, "<html><title>搜索结果</title></html>"));
    }

    private static String quality(int status, String body) {
        return ResponseSignals.quality(new FetchResponse("https://example.test", body, status, Map.of()));
    }
}
