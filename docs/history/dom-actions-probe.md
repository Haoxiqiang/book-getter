# DOM 动作 `@textNodes` 与 `@all` 的规则验证

2026-09-27，对照本仓库官方 [AnalyzeByJSoup.kt](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt) 的 `getResultLast`：`textNodes` 仅取所选元素的直属非空文本节点并用换行连接；`all` 返回所选元素的外层 HTML。此前 Java 解析器把两者当作 HTML 属性，通常得到空字符串。现补这两个动作及直接作用于列表项的形式；合成 Java 回归检查嵌套 `<a>` 文本不混进 `textNodes`、外层 HTML 保留在 `all` 中。尚未改变 `@html` 的其他语义差异。

| 样本 | 规则与实际阶段 | 边界 |
| --- | --- | --- |
| 主目录 ESJ Zone | 快照 `20260926-170836-f658eae9`；原始 `esjzone.json` SHA-256 `f225f2d70788057baefa178f32b03ce8574b6ba822606da7d2a5c4c6e33f2854`。正文规则含 `@css:...@all`；以站内有结果的“異世界”搜索 40 条，首条详情成功，目录 50 项（49 个非分卷项）。 | `无职转生` 简体词当前页面无作品卡片，搜索 0 不能代替规则失败。 |
| ESJ 第 8 个可读条目 | 为避开前几个公告，探针指定 0 起始索引 `8`，实际选中标题以 `4.` 开始的正文章节；JVM 与 Android 同一响应均报告 `CONTENT_OK`、原始提取 6398 字符、去 HTML 标签的可见文本 2496 字符。 | 这只是**一章**的规则验证，不是完整书，也未证明净化质量、分页、全部章节或源站授权状态。正文未写入文档或版本库。 |
| 主目录哔哩轻小说等 | 搜索作者/详情简介使用 `@textNodes`。 | 当前访问控制/脚本前提未消除，只有官方源码与合成回归，不声称这些实站通过。 |
| 补充源一读小说、必读小说 | 分别在搜索简介、正文使用 `@textNodes`；128 源集合 SHA-256 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。 | 本轮搜索分别停在 TLS 握手关闭、证书过期，不关闭校验。 |
| 补充源文学迷 | 搜索简介使用 `@textNodes` 并要求 Android WebView；以源内检查词“万族之劫”在模拟器搜索。 | 请求重定向到 `m.juzixs5.com` 后 HTTP 403，未到规则提取；不绕过访问控制。 |

探针新增 `-PprobeChapterIndex=8`（非分卷章节、从 0 开始）及 Android Intent `--ei chapterIndex 8`，并同时显示 `rawCharacters` 和 `visibleCharacters`，避免把 HTML 标签长度算作可读正文。Android 使用的[上游 ESJ JSON](https://raw.githubusercontent.com/jiwangyihao/source-j-legado/main/esjzone.json)与主快照原始文件 SHA-256 完全一致；JVM/Android 对所选章节的 6398/2496 字符数一致。可复测：

```sh
./gradlew :source-core:liveSourceProbe -PprobeSource=ESJ -PprobeQueries=異世界 -PprobeChapterIndex=8 --offline
```

设备复测需先安装 Debug APK，再向 `org.bookgetter.app/.CompatibilityProbeActivity` 传 `catalogUrl`（上述单源 JSON URL）、`sourceFilter=esjzone.one`、`keyword=異世界`、`captureFirst=true`、`chapterIndex=8`。探针只读指定章，不导出作品。
