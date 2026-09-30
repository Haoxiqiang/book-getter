package org.bookgetter.app;

/** User-facing result of a chapter capture, including details behind the status label. */
record CaptureStatus(String title, String message, String details) {
    static CaptureStatus from(CaptureRunner.Result result, int total) {
        if (result.complete())
            return new CaptureStatus("抓取完成", "完整导出 " + result.captured() + "/" + total + " 章。", "");
        String failures = String.join("\n", result.failures().values());
        if (result.captured() == 0)
            return new CaptureStatus("抓取失败：缺章清单", "全部 " + total
                    + " 章抓取失败，未写入导出文件。点击状态查看原因，点击「继续上次」重试。", failures);
        return new CaptureStatus("部分导出：缺章清单", "部分导出 " + result.captured() + "/" + total
                + " 章；缺 " + result.failures().size()
                + " 章。点击状态查看缺章，点击「继续上次」重试。", failures);
    }
}
