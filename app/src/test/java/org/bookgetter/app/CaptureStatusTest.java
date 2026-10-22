package org.bookgetter.app;

import org.bookgetter.business.CaptureRunner;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class CaptureStatusTest {
    @Test public void allFailedShowsCurrentChapterReasons() {
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("one", "第一章: HTTP 403");
        failures.put("two", "第二章: 规则失败");
        CaptureStatus status = CaptureStatus.from(new CaptureRunner.Result(0, failures), 2);
        assertTrue(status.message().contains("未写入导出文件"));
        assertEquals("第一章: HTTP 403\n第二章: 规则失败", status.details());
    }

    @Test public void completedCaptureClearsPreviousDetails() {
        CaptureStatus status = CaptureStatus.from(new CaptureRunner.Result(2, Map.of()), 2);
        assertEquals("抓取完成", status.title());
        assertEquals("", status.details());
    }
}
