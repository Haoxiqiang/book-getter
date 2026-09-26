# URL 首匹配与分页回目录：跨站探针（2026-09-27）

从 [AOAOSTAR 关耳女频集合](https://legado.aoaostar.com/sources/e3e5d620.json)按 `bookSourceUrl` 去重的 86 源中挑 7 个带 `nextContentUrl` 的不同站点，各用“故事”低频搜索，命中后仅探首条。集合 SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。不导出整书，也不把网络阻断算作规则失败。

| 书源 | 最深实测 | 判断 |
| --- | --- | --- |
| 夜伴书屋 | JVM/Android 搜索 59、首条详情、目录 71；首条章节按 3 页读取，合并 HTML 原始 6071 字符、可见 4099 字符 | 两处可复现的规则兼容边界，见下文；单章长度不证明内容净化或整书正确 |
| 小说路上 | 搜索请求证书已过期（2026-08-18） | 未到规则，不关闭证书验证 |
| 起舞中文 | 首轮搜索 URL 脚本调用 `java.post(...).header("Location")` 时宿主无 `post` | 此为当时基线；[后续修复](java-post-redirect-probe.md)已补受控 POST 宿主，实站现在停在 TLS |
| 神书网 | HTTP 200 的域名出售页，归 `PARKED_DOMAIN` | 未到规则 |
| 笔趣阁③、CP 文库 | 搜索连接超时 | 未到规则 |
| MM 小说网 | 搜索请求超时 | 未到规则 |

夜伴书屋的搜索结果一行同时含阅读和 TXT 两个链接，规则 `tag.a@href` 是 URL 字段。原 Java 实现将两个 `href` 用换行拼接，生成 `/book/1432/ /txt/1432.html` 的非法请求；官方 `AnalyzeRule.getString(..., isUrl=true)` 对默认 DOM 规则调用 `AnalyzeByJSoup.getString0`，只取第一个匹配。Java 规则引擎现对 URL 字段取首个 DOM 属性，普通非 URL 字段仍保留多值合并。合成回归精确断言两种行为，真实源从详情 URI 错误前进到 71 条目录。

该源 `ruleContent.nextContentUrl=.text-center@tag.a.2@href` 在首章第 3/3 页提取出书籍详情/目录页 `/book/1432/`。此前引擎继续抓这页，最终报 `Empty page content`，掩盖前 3 页已有正文。现在只有在下一页 URL **精确等于**已知书籍 URL 或目录 URL 时，停止本章分页并发出 `PAGINATION_TO_CATALOG / ruleContent.nextContentUrl` 警告；这是一条显式安全边界，不是声称与 Legado 对所有分页规则等价。官方 `BookContent.analyzeContent` 主要以已知下一章 URL 为边界，仍须继续验证其他网站的分页和末章行为。合成回归确保不请求书籍页，Android 日志显示该警告并只请求 3 个章节页。没有记录或提交正文文本。

起舞中文的 `java.post` 在官方 `JsExtensions.kt` 返回 jsoup `Connection.Response`，该源读取 `Location`；官方请求明确 `followRedirects(false)`。本轮初测时 `FetchRequest`/通用 `Transport` 尚无逐请求跳转策略；后续已补受控响应对象和跳转控制，并以合成 302 及真实站点复测，见[专项记录](java-post-redirect-probe.md)。真实站点仍被 TLS 阻断。

复测命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.yeban360.com,m.xs63.biz,www.75zhongwen.com,www.pi12345.com,www.xsbiquge.net,www.cpwenku.com,www.mmtxt.com' '-PprobeQueries=故事' -PprobeCatalogFirst=true --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.yeban360.com' '-PprobeQueries=故事' -PprobeCaptureFirst=true --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/e3e5d620.json --es sourceFilter www.yeban360.com --es keyword 故事 --ez captureFirst true
```

`./gradlew :source-core:test :export:test :app:assembleDebug --offline` 通过；Android 模拟器的首条章节为 3 个 HTTP 200 页，记录 `PAGINATION_TO_CATALOG`，正文可见字符数与 JVM 一致。未下载小说或媒体文件。
