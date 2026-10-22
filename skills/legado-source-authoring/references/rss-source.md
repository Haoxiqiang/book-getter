# `RssSource` 订阅源编写参考

RSS 与普通 `BookSource` 分别建模；完整字段和官方执行路径见上游[RSS 规则](../../../legadoSkill/docs/rules/rss.md)。基准官方提交为 `62003ce732a7e30602754d28996da7f98b9ea296`，上游提交为 `1e59ff3c1fa428a1a0981f42249fd4cf5d347644`。本项目现有独立 Java RSS 运行时与 Android 网页层子集；支持边界见[兼容说明](../../legado-source-compat/references/rss-compat.md)。创作结果仍应按 `../legado` 官方源码及对应执行环境逐阶段验证，不能从导入成功推断站点可用。

- `sourceUrl` 是订阅源身份，`sourceName` 是名称；`type` 为 0 网页、1 图片、2 视频，不能套用普通书源 `bookSourceType` 的 0–4 类型。分类 `sortUrl`、单页面 `singleUrl`、搜索 `searchUrl` 按用户目标与真实站点配置。
- 标准 XML RSS `item` 可在 `ruleArticles` 为空时走默认解析；这不意味着任意 HTML 或 Atom 都会自动解析。自定义 HTML/JSON 列表设置 `ruleArticles`，逐项用 `ruleTitle`、`rulePubDate`、`ruleDescription`、`ruleImage`、`ruleLink` 提取；自定义规则分支的空标题会被丢弃，默认 XML 分支保留官方行为。
- 仅在 `ruleArticles` 非空的自定义列表分支求值 `ruleNextPage`；值 `PAGE` 返回当前分类 URL，否则按规则提取下一页。`ruleArticles` 为空时默认 XML 分支直接返回空下一页，即使配置了 `ruleNextPage` 也不会求值。URL 模板仍可有页码绑定，不能按旧教程一概排除页码。
- `ruleDescription` 是列表摘要；`ruleContent` 属文章阅读路径，二者并不互斥。`singleUrl`、网页直开与 `nextContentUrl` 的同篇分页需根据实际阅读路径验证。`contentWhitelist`/`contentBlacklist` 是文章导航边界，不是普通书源正文规则。
- `enableJs`、`injectJs`、`preloadJs` 等依赖网页环境；普通 HTTP 解析不能替代 WebView。图片/视频、登录和 Cookie 各自标明验证结果，不从 XML 列表成功推断媒体或正文可用。

书写时分别检查标准 RSS 分支和自定义规则分支；缺少对应页面或 App 证据时标为待验证。外部教程把列表下一页误写成 `ruleArticles` 等旧说法见上游[教程对照](../../../legadoSkill/docs/rules/tutorial-crosswalk.md)和[勘误](../../../legadoSkill/docs/rules/errata.md)。
