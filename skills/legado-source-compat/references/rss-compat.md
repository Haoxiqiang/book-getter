# RSS 订阅源兼容边界

当前 Java 工程未承诺通用 RSS 阅读运行时。妙公子书源管理页另有专用适配器：从其 RSS 配置定位页面、提取 `yuedu://booksource/importonline?src=` 的 BookSource 合集供用户选择并更新；该适配器不解析 RSS 文章正文。本页用于设计或验证 RSS 能力，不能从普通书源测试推断 RSS 已支持。官方模型见 [`RssSource.kt`](../../../../app/src/main/java/io/legado/app/data/entities/RssSource.kt)、[`RssArticle.kt`](../../../../app/src/main/java/io/legado/app/data/entities/RssArticle.kt)及上游[RSS 规则](../../../legadoSkill/docs/rules/rss.md)。

- `RssSource.sourceUrl` 与 `BookSource.bookSourceUrl` 分开存档、去重、更新；文章身份涉及 `(origin, link, sort)`。RSS `type` 为 0 网页、1 图片、2 视频，不能套用 `bookSourceType` 的 0–4 含义。
- `ruleArticles` 为空时，官方走 XML `item` 标准解析分支；非空时按规则选列表，并逐项取标题、日期、摘要、图片、链接。空标题文章被丢弃；这不表示空 `ruleArticles` 可解析任意 HTML 或 Atom。
- `ruleNextPage` 仅在 `ruleArticles` 非空的自定义列表分支求值；值为 `PAGE` 时返回当前分类 URL，否则提取下一页链接。`ruleArticles` 为空时默认 XML 分支返回空下一页，忽略 `ruleNextPage`。`AnalyzeUrl` 仍可接收页码绑定，不能照旧教程断言 RSS 完全没有页码机制。
- `ruleDescription` 是列表摘要字段；`ruleContent` 是后续阅读路径，二者不互斥。`singleUrl`、网页直开、`nextContentUrl` 同篇分页及图片/视频分支须分别验收。WebView 脚本与普通 HTTP 抓取分开标记能力状态。
- 若实现 RSS，先用标准 XML 与自定义规则各自的脱敏正反例验证，再测分类/搜索、翻页、正文与网页分支；Atom、命名空间和媒体细节未测时列 `UNKNOWN`。现有普通书源兼容矩阵不能替代 RSS 专项记录。
