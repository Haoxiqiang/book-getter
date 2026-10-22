# 普通 `BookSource` 编写参考

基准：官方 Legado `62003ce732a7e30602754d28996da7f98b9ea296`；提炼自上游 `legadoSkill` `1e59ff3c1fa428a1a0981f42249fd4cf5d347644` 的[字段](../../../legadoSkill/docs/rules/schema.md)、[语法](../../../legadoSkill/docs/rules/syntax.md)、[请求](../../../legadoSkill/docs/rules/requests.md)和[阶段](../../../legadoSkill/docs/rules/stages.md)。官方版本升级时重核，具体行为争议回查根仓库 `AnalyzeRule`、`AnalyzeUrl`、`BookSource` 及阶段调用方。

## 按目标选择字段

- `bookSourceUrl` 是源身份，`bookSourceName` 是显示名；`bookSourceType` 为 0 文本、1 音频、2 图片、3 文件、4 视频。字段可空与默认值由官方 `BookSource.kt` 和各规则数据类决定，不能把“常见完整小说源”的字段表当全部源的必填清单。
- 搜索用 `searchUrl` 与 `ruleSearch`，发现用 `exploreUrl`、可选筛选及 `ruleExplore`。列表项的书名、详情地址通常是可继续阅读的关键结果，作者、封面、分类等按页面实有信息提取；`bookUrlPattern` 可能改变列表入口。
- 详情用 `ruleBookInfo.init` 预处理，再提取字段和 `tocUrl`；文件型源可用 `downloadUrls`。目录用 `ruleToc.chapterList` 选条目、`chapterName`/`chapterUrl` 提取章节，按需配置 `nextTocUrl`、分卷、VIP、付费标记。
- 正文用 `ruleContent.content`；`nextContentUrl` 只应指向同章后续页，已知下一章地址是停止边界。`sourceRegex` 作用于请求正文路径，`replaceRegex` 作用于合并后内容。音频、图片、文件、视频的正文可能是资源链接，不能一律按 HTML 文本净化。
- 单个书源实体是对象；多个源可组成数组供相应导入入口使用。未知字段可保留，但不能宣称能够执行。

## 从页面推导规则

共同语法只维护在[规则契约](../../../docs/legado-rule-contract.md#模式选择器与替换)，包括 Default/CSS 区分、动作、索引、组合、替换及 JS 返回形态。根据真实请求确定 GET/POST、body、Content-Type、headers、charset 和基址；发现乱码先查响应头和原始字节，不为所有站点套固定编码。

需要登录、Cookie 或网页执行时标明前提；Rhino 宿主和网页 JS 的绑定不能混用。根据用户目标选择必要规则，不为填满模板补无关字段。交付和证据范围按[验证参考](verification.md)，旧知识库误解查上游[勘误](../../../legadoSkill/docs/rules/errata.md)。
