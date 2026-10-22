# RSS 订阅源兼容边界

官方基准是项目根目录 `../legado` 的 `62003ce732a7e30602754d28996da7f98b9ea296`。模型见 [`RssSource.kt`](../../legado/app/src/main/java/io/legado/app/data/entities/RssSource.kt)、[`RssArticle.kt`](../../legado/app/src/main/java/io/legado/app/data/entities/RssArticle.kt)；执行见 [`Rss.kt`](../../legado/app/src/main/java/io/legado/app/model/rss/Rss.kt)、[`RssParserByRule.kt`](../../legado/app/src/main/java/io/legado/app/model/rss/RssParserByRule.kt)与 [`RssParserDefault.kt`](../../legado/app/src/main/java/io/legado/app/model/rss/RssParserDefault.kt)。上游[RSS 说明](../legadoSkill/docs/rules/rss.md)只作索引。

现有独立 Java RSS 引擎、核心业务仓储与 Android 网页层；当前验证范围见[验证指南](verification.md)，历次执行保留在[工程验证历史](history/verification-records.md)。以下是已落地的行为子集，不是通用 Legado RSS 兼容声明。妙公子书源目录适配器继续独立存在，不能把目录配置转成 BookSource 后声称已读取 RSS 文章。

## 模型、列表和阅读

- `RssImporter/RssModels/RssEngine` 独立于普通书源；`sourceUrl` 与 `bookSourceUrl` 分别保存、去重和更新。文章身份为 `(origin, link, sort)`；RSS `type` 是 0 网页、1 图片、2 视频。混合导入由核心业务包的 `SourceImportCoordinator` 处理，并一次提交双方当前、手动与订阅状态。
- 空 `ruleArticles` 使用标准 XML `item` 分支，支持基础标题、链接、时间、摘要、`content:encoded` 和图片；拒绝 DTD/外部实体。Android Expat 不支持 Xerces 的 `disallow-doctype-decl` 特性，不能将该非标准 feature 设为启动必要条件；应使用强制 `LexicalHandler.startDTD` 拒绝、显式声明检查及实体 resolver 保持安全，并在 JVM 与 Android 分别验证。默认分支按官方保留空标题条目；只有自定义列表分支过滤空标题。Atom 和未覆盖的命名空间/媒体扩展不推定为支持。
- 自定义 HTML/JSON 列表提取标题、日期、摘要、图片、链接，支持 `-` 倒序。仅该分支执行 `ruleNextPage`：`PAGE` 保留分类模板并让调用方递增页码；其他规则取下一页。默认 XML 忽略 `ruleNextPage`。分类支持文本和受控 JS，搜索绑定 `key/page`。
- 阅读优先非空 `description`，其次执行 `ruleContent`，否则直开网页；`singleUrl` 走网页。`content:encoded` 独立保留在文章数据中，不能假定它自动覆盖官方摘要优先级。
- 网页正文保持 HTML；一个下一页连续追踪，多个下一页顺序各抓一次且不再递归扩展。仅源与文章均为网页类型时执行正文分页。循环、跳转回已处理页、超过 50 页或正文累计字符预算会明确失败，避免保存不完整结果。图片和视频只返回可请求的 HTTP(S) 资源，不扩展为分段流/解密支持。
- 分类相对模板在请求阶段解析；字段和正文下一页使用最终响应地址作为基础地址，保留跳转后的链接语义。图片字段仍按官方 RSS 规则的独立基础地址处理。

## Android 网页层与安全边界

`RssBrowserSession` 定义宿主会话接口，Android 默认 `RssWebViewController/RssWebPolicy` 处理 `enableJs`、`loadWithBaseUrl`、样式、起始 HTML/样式/脚本、`preloadJs`、`injectJs`、导航钩子、资源白黑名单；调试 App 提供有限预加载，核心 `RssReadCache` 负责缓存隔离。资源过滤保留前缀/完整匹配与黑名单优先级，但浏览器 URL 匹配采用 RE2/J 线性安全子集，不等同全部 Java 正则。`ruleContent.sourceRegex` 的反向引用、环视等不支持语法报告 `UNSUPPORTED_LINEAR_REGEX`；名单字段报告 `UNSUPPORTED_LINEAR_RESOURCE_REGEX`，仅保留字面前缀匹配。模式最长 4096 字符、输入最长 65536 字符，每次匹配默认 250 ms 且可取消；名单总匹配共享预算。普通规则 `##`、`replaceRegex` 等继续使用 Java 正则，不承诺由此获得有界执行。Android ICU 会先将 `CharSequence` 转为 String，必须用真正保留字符访问检查的线性引擎，不能以 JVM 包装器测试替代设备验收。

页面原生桥仅开放 `java.get/put/searchBook/open/showPhoto` 的受控子集。消息校验精确来源、主框架、页面代次及令牌；不向页面暴露登录头。明文 HTTP 页面及缺少来源隔离功能的 WebView 会报告 `PARTIAL_WEB_BRIDGE`；不能通过退回无限制 `addJavascriptInterface` 补兼容。源页面脚本和 Rhino 规则宿主是不同环境，禁止混用 API 及返回值承诺。

`addBook`、任意原生同步返回/回调、POST 导航重放、完整跨页面生命周期、复杂登录检查/封面解密及媒体细节仍不等价或未支持。`preloadJs` 的网页改写只支持可获取的 HTML GET；其他路径给出字段级部分支持诊断。交互式登录和验证码不因配置可导入就算通过。

先验证 `RssEngineTest`、`SourceImportCoordinatorTest` 与 `RssWebPolicyTest`，再使用本地 [RSS 合成夹具](../test-fixtures/README.md) 验证 Android HTTP 阶段。浏览器注入、桥和真实 RSS 站点必须另留证据，不从 core 通过外推。

## 验证入口

XML/自定义列表使用 `RssCompatibilityProbeActivity`，网页层使用 `RssBrowserProbeActivity`，两者都只验证本地夹具子集。最近执行范围与旧 provider 的既有记录分别见[验证指南](verification.md)，不得从历史 HTTP 或 XML 通过推断真实站点、网页桥或完整 RSS 兼容。
