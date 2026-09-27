# 普通 `BookSource` 编写参考

基准：官方 Legado `62003ce732a7e30602754d28996da7f98b9ea296`；提炼自上游 `legadoSkill` `1e59ff3c1fa428a1a0981f42249fd4cf5d347644` 的[字段](../../../legadoSkill/docs/rules/schema.md)、[语法](../../../legadoSkill/docs/rules/syntax.md)、[请求](../../../legadoSkill/docs/rules/requests.md)和[阶段](../../../legadoSkill/docs/rules/stages.md)。官方版本升级时重核，具体行为争议回查根仓库 `AnalyzeRule`、`AnalyzeUrl`、`BookSource` 及阶段调用方。

## 按目标选择字段

- `bookSourceUrl` 是源身份，`bookSourceName` 是显示名；`bookSourceType` 为 0 文本、1 音频、2 图片、3 文件、4 视频。字段可空与默认值由官方 `BookSource.kt` 和各规则数据类决定，不能把“常见完整小说源”的字段表当全部源的必填清单。
- 搜索用 `searchUrl` 与 `ruleSearch`，发现用 `exploreUrl`、可选筛选及 `ruleExplore`。列表项的书名、详情地址通常是可继续阅读的关键结果，作者、封面、分类等按页面实有信息提取；`bookUrlPattern` 可能改变列表入口。
- 详情用 `ruleBookInfo.init` 预处理，再提取字段和 `tocUrl`；文件型源可用 `downloadUrls`。目录用 `ruleToc.chapterList` 选条目、`chapterName`/`chapterUrl` 提取章节，按需配置 `nextTocUrl`、分卷、VIP、付费标记。
- 正文用 `ruleContent.content`；`nextContentUrl` 只应指向同章后续页，已知下一章地址是停止边界。`sourceRegex` 作用于请求正文路径，`replaceRegex` 作用于合并后内容。音频、图片、文件、视频的正文可能是资源链接，不能一律按 HTML 文本净化。
- 单个书源实体是对象；多个源可组成数组供相应导入入口使用。未知字段可保留，但不能宣称能够执行。

## 规则与请求易错处

- Legado Default 段、`@CSS:`、`@Json:`、`@XPath:`、`@js:`、`<js>…</js>` 和 WebView JS 各有解析路径。Default 的 `class.name`、`tag.name`、`text.片段`、位置索引和 `@text/@href` 动作不能当纯 CSS；CSS 伪类是否可用要按实际 `@CSS:`/Jsoup 路径试验，没有全局禁令。
- `@textNodes` 是直属文本，`@html` 会去 script/style 后返回外层 HTML，`@all` 保留原外层 HTML。`||` 是首个非空回退，`&&` 合并，`%%` 交错；在 JSONPath 过滤器内的同形符号不能机械拆开。
- `##匹配##替换` 做常规替换；尾部第四段（常见 `###`）只从首个匹配片段求替换结果，无匹配返回空。不能用普通“只替换第一处并保留全文”解释。`ContentRule.replaceRegex` 是另一层正则，勿与规则尾部混淆。
- URL 模板可含 `{{key}}`、`{{page}}`、页码候选、JS 和后附 JSON 请求选项。根据真实请求确定 GET/POST、body、Content-Type、headers、charset 和相对 URL 的基址；最终请求与模板文本不同。发现乱码时核对响应头、页面编码和原始字节，再指定 charset，不要求每个站点预先调用某个固定工具。
- Rhino 的 `java.*` 宿主和 WebView 页面脚本不同；JS 规则只能在该场景可用的绑定和方法上工作。需要登录、Cookie、验证码或网页执行时标明前提，不能用空列表冒充规则验证成功。

历史 skill 的全局伪类禁令、`nextContentUrl`“下一章”说法、全字段必填及强制 JSON 数组要求均已由上游[勘误](../../../legadoSkill/docs/rules/errata.md)纠正。
