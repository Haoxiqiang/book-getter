# RSS 订阅源编写

普通 `BookSource` 与 `RssSource` 独立；字段详表查上游[RSS 规则](../../../legadoSkill/docs/rules/rss.md)，官方版本及模型定义见[书源定义 RFC](../../../docs/source-definition.md)。官方列表、阅读优先级、分页和网页执行语义集中在[RSS 边界](../../../docs/rss-compatibility.md)，当前 Java 子集查[能力矩阵](../../../docs/compatibility.md)。

## 按目标配置

- 用 `sourceUrl` 标识源，`sourceName` 显示名称；`type` 为 0 网页、1 图片、2 视频，不能套普通书源的 0–4。根据目标选择 `sortUrl` 分类、`searchUrl` 搜索或 `singleUrl` 单页。
- 标准 RSS XML 的 `item` 列表可不填 `ruleArticles`；任意 HTML/Atom 不会自动得到相同解析。自定义列表配置 `ruleArticles`，逐项提取标题、链接、日期、摘要和图片。
- 列表翻页只在自定义分支配置 `ruleNextPage`；`PAGE` 保留分类模板由调用方递增页码。默认 XML 不执行该字段；URL 模板的页码绑定与是否执行列表下一页要分别判断。
- `ruleDescription` 是摘要，`ruleContent` 是阅读路径，不能视作互斥字段。网页、正文分页、图片和视频按实际阅读目标验证；仅列表成功不证明文章可读。
- `enableJs`、`preloadJs`、`injectJs`、起始页和样式依赖网页环境；资源白黑名单、安全消息能力、登录和 Cookie 都需相应设备证据，HTTP 解析不代替网页验证。

按用户需要检查对应分支，不机械要求每种源覆盖所有功能。交付与未测字段按[验证参考](verification.md)，旧字段误写见上游[教程对照](../../../legadoSkill/docs/rules/tutorial-crosswalk.md)及[勘误](../../../legadoSkill/docs/rules/errata.md)。
