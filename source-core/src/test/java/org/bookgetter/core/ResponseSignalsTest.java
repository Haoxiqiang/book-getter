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
