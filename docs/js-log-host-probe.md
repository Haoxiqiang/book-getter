# `java.log` 宿主的受控子集

2026-09-27。真实规则样本有两处：上游“爱去小说”的下载链接脚本在 `java.ajax` 前调用 `java.log(url)`；已有[Jsoup 安全视图探针](jsoup-safe-facade-probe.md)也记录木兰花漫规则包含 `java.log`，但该站本次停在 HTTP 403，脚本未执行。前者属于尚未完成的文件下载流程。两者都不能作为实站通过证据。

官方 `JsExtensions.log(msg)` 写调试日志，并把 `msg` 原值返回。此前 Java 宿主没有此方法，规则执行到调用处会报方法不存在。现提供原值返回与可注入调试回调；默认回调为空，避免规则中的 URL 或令牌意外进入应用状态提示。导入预检把 `log` 归为 `PARTIAL_JS_HOST`，保留输出接线尚未完成的事实。

离线回归验证字符串与 `null` 的返回值、显式调试回调收到带书源 ID 的消息、通知回调未收到调试内容，以及预检定位到 `ruleSearch.bookList`。`./gradlew :source-core:test --offline --rerun-tasks` 通过。未对真实站点发请求，未验证原版调试日志格式或完整文件源流程。
