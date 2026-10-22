# 音频与漫画书源的跨站探针

2026-09-27，从 [AOAOSTAR 128 源补充集合](https://legado.aoaostar.com/sources/4dc410d1.json)选 5 条音频与 2 条漫画源，单站只搜 `斗破苍穹`，仅恋听网进一步读取首条的详情、目录和首章页面；不下载音频或图片。集合响应 SHA-256：`05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。完整第三方书源仅在探针内存中读取，不并入主目录、不提交。

| 类型/站点 | JVM 阶段结果 | 结论边界 |
| --- | --- | --- |
| 音频·恋听网 | 搜索 HTTP 200、17 条；详情 HTTP 200；目录 1208 章；首章为 4532 字符播放页 | 原探针错误标 `CONTENT_OK characters=0`，只因非空 HTML 被当作资源链接。修正后报 `CONTENT.ruleContent.sourceRegex`，见下文；Android 同词搜索即 HTTP 403，未到规则阶段 |
| 音频·有声听书网 | 搜索 HTTP 444 | 访问限制/站点响应问题，不算 0 命中 |
| 音频·有声小说① | 搜索 HTTP 200、26 字符、0 条 | 与畅听小说相同的业务错误，见下文；Android 同样识别 `BUSINESS_ERROR`，核心报 `SEARCH.searchUrl` |
| 音频·畅听小说 | 搜索 HTTP 200、26 字符、0 条 | 业务错误，非有效空搜索；见下文 |
| 音频·有声小说② | 搜索 HTTP 200、标题为“APP大全！你想要的都有！”的页面，0 条 | 页面似乎已非原有有声 API；按上游站点/规则变化排查，不报有效无书 |
| 漫画·粉丝漫画 | JVM TLS 握手终止 | 未进入规则阶段 |
| 漫画·好看漫画 | 跳转至另一主机并遇到 HTTP 403、`Just a moment...` | 防护/访问边界，不绕过 |

恋听网 `ruleContent.content` 仅返回页面，另配 `sourceRegex` 用来找 `.mp3/.m4a`。官方 [WebBook.kt](../../../legado/app/src/main/java/io/legado/app/model/webBook/WebBook.kt) 会把该字段传给 `AnalyzeUrl`，[BackstageWebView.kt](../../../legado/app/src/main/java/io/legado/app/help/http/BackstageWebView.kt) 在资源加载时匹配媒体 URL；它**不是**对 HTML 正文做替换。当前工程没有这条 WebView 资源监听链，故不能声称恋听可播放。核心现在验证非文本资源为合法 HTTP(S) URL；漫画章无有效图片时也失败。若规则依赖 `sourceRegex` 而只拿到页面，错误归因到 `ruleContent.sourceRegex`。Java 合成回归覆盖了播放页误当音频和空图片章；JVM 实站复测不再报告虚假的音频 `CONTENT_OK`。

这轮只验证资源 URL 的语法与有无，不验证实际媒体响应、解密、流分段或可播放性。后续实现 WebView 资源监听时，必须在 Android 上用获准访问的站点复测；JVM 或合成页面不能替代。

同日复核两条 26 字符的音频 API：按各自书源的对象型 POST 参数作低频只读请求，均返回 `code=-1`、`msg=没有该用户!`，说明存在用户/认证前提，而非“斗破苍穹”无收录。原响应诊断只识别带 `data:null` 的非零 `code`，漏掉无 `data` 的负错误码；现对**负码且有消息**的 JSON 补 `BUSINESS_ERROR` 回归。探针起初将 JVM 两源标 `RESPONSE_SUSPECT`、有声小说① Android 标 `SEARCH_RESPONSE_SUSPECT`；进一步修正核心搜索阶段后，JVM 两源与 Android 有声小说①均明确报 `SEARCH.searchUrl` 业务错误，而非返回空列表。未尝试绕过认证。
