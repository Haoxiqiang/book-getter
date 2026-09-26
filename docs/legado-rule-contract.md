# Legado 书源规则契约与测试设计

本文件是 `new-book-getter` 的实现和验收依据，不是另造一套书源格式。权威顺序：当前仓库的 Legado 源码 → 可重复的真实 App/源站行为 → [legadoSkill](../legadoSkill/README.md) 的知识库与案例。后者的 Python 调试器是近似实现，其 `SKILL.md` 也明确要求冲突时回查官方源码。上游 skill 仓库当前固定在 `d988cf232632931c3f8c7c5e5dd6714cd8a75cf6`，MIT 许可见[许可证](../legadoSkill/LICENSE)。上游案例可能涉及第三方站点与作品；不能把仓库的 MIT 许可推定为所有书源或正文的再分发许可。

## 对象和执行阶段

| 阶段 | 书源字段/输入 | 必须输出或失败分类 | 官方依据 |
| --- | --- | --- | --- |
| 导入 | 单对象/数组、`bookSourceUrl`、`bookSourceName`、`bookSourceType` | 按主键去重；保留来源、原文及预检诊断 | [BookSource.kt](../../app/src/main/java/io/legado/app/data/entities/BookSource.kt) |
| 请求 | `searchUrl`，详情/目录/正文 URL；`header`、URL 选项、登录 Cookie | 最终 URL、方法、头、正文、编码、WebView 要求；请求错误单独归类 | [AnalyzeUrl.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) |
| 搜索 | `ruleSearch.bookList` 及书名、地址、作者等字段 | 0 条是有效结果；非空结果须有书名和绝对详情地址 | [SearchRule.kt](../../app/src/main/java/io/legado/app/data/entities/rule/SearchRule.kt) |
| 详情 | `ruleBookInfo.init`、字段、`tocUrl`、可选 `downloadUrls` | 搜索字段与详情字段合并；可选直链失败不能冒充正文失败 | [BookInfoRule.kt](../../app/src/main/java/io/legado/app/data/entities/rule/BookInfoRule.kt) |
| 目录 | `ruleToc`、下一页、分卷/VIP/付费标记 | 稳定章节顺序、绝对章节地址、去重和循环检测 | [TocRule.kt](../../app/src/main/java/io/legado/app/data/entities/rule/TocRule.kt) |
| 正文 | `ruleContent.content`、`nextContentUrl`、`replaceRegex`、媒体类型 | 多页合并、广告净化、资源链接、正文非空；下一页不得越到下一章 | [ContentRule.kt](../../app/src/main/java/io/legado/app/data/entities/rule/ContentRule.kt) |

类型值依官方 `BookSource`：0 文本、1 音频、2 图片、3 文件、4 视频。阅读排版、TTS、本地 TXT/EPUB 解析与 MOBI 转换不在本工程目标内。书源规则可同时依赖多个阶段的共享变量，不能把每个字段都当作完全独立的纯函数。

## 规则语法：优先验证的契约

1. 默认规则、显式 `@CSS:`、`@XPath:`、`@Json:`、URL/规则中的 `<js>...</js>`、`@js:`、`jsLib` 与 `mainJs` 走不同模式。解析器应先识别模式，再解析选择器、动作和后置替换；不可把 `@js:` 或 `{{...}}` 误送进 CSS 选择器。依据：[AnalyzeRule.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt)、[AnalyzeUrl.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)。
2. `{{表达式}}` 在请求 URL 中执行 JS 后插入结果；整数浮点值如 `1.0` 输出为 `1`。规则中的 `{{...}}` 可能是内层规则（`$.foo` 等），也可能是 JS 表达式，不能一概文本替换。URL 选项还包括 `method`、`body`、`charset`、`headers`、`webView` 等，应保留原始请求证据以便诊断。依据：`AnalyzeUrl.replaceKeyPageJs` 和 `AnalyzeRule.SourceRule.makeUpRule`。
3. `||` 是依次回退到第一个非空结果；`&&` 合并多路结果；`%%` 按位置交错。JSONPath 本身也允许逻辑运算，拆分时须由规则分析器判别而非无条件 `String.split`。依据：[AnalyzeByJSoup.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt)、[AnalyzeByJSonPath.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSonPath.kt)。
4. `##正则##替换` 对全文替换；末尾再加一个 `#`（`###`）表示先取首个匹配，再在该片段内替换，未命中为空。目录/正文下一页提取经常依赖这一点。依据：`AnalyzeRule.replaceRegex`。
5. HTML 的 `@text`、`@ownText`、`@html`、`@href/@src`、`@textNodes`、`@all`、位置索引、属性和节点组合应区分“返回节点列表”和“返回字段字符串”；列表字段可以直接使用动作。`@textNodes` 取直属文本，`@all` 取外层 HTML；[ESJ 实站单章与 Android/JVM 对照](dom-actions-probe.md)仅证明后者的一个样本，不等于全文净化通过。尤其 `tag.a.0`、`tag.span.-1`、`tag.tr!0` 是 Legado 位置索引/排除语法而非 CSS 类名；[跨站广度探针](diverse-source-search-probe.md)曾因此把真实搜索误判为 0。`tag.span[0:1]` 等方括号索引已按[真实规则样本](bracket-index-and-metadata.md)补离线回归，但样本站点仍被网络阻断。XPath 不带前缀的 `//a` 应匹配 HTML 元素；JSONPath 缺省字段不应让整本搜索失败。具体 CSS 能力应以官方 `AnalyzeByJSoup` 和所用 jsoup 版本测试为准，不把 skill 里的“禁用某些伪类”口号当作全部模式的硬性语法限制。
6. JS 宿主是受控兼容层：`java.ajax/get/put`、Cookie、源/书/章节变量、转换/编码/时间函数、`source`、`book`、`chapter` 等必须有生命周期和上下文。不能把任意 Java 类暴露给书源脚本；未知方法应给出字段、阶段、方法名诊断。当前 `source-core` 只实现其中一部分；`java.getElement/getElements/getString/setContent` 仅有 HTML 字符串兼容子集，`java.t2s` 仍是原文返回的占位，不能宣称完全兼容。官方接口参见 [JsExtensions.kt](../../app/src/main/java/io/legado/app/help/JsExtensions.kt) 和 `AnalyzeRule.put/get`。

## 可执行测试矩阵

| 层级 | 样本/夹具 | 通过条件 | 当前状态 |
| --- | --- | --- | --- |
| 规则单元 | 本地构造 HTML/JSON、ES6、模板、`||`、`&&/%%`、`<js>`、`###` | 精确断言请求、节点、字符串和缺省行为；无网络 | 已覆盖复合规则的基础子集，复杂 CSS/混合规则与宿主生命周期待补 |
| 本地端到端 | `test-fixtures/http/` | 导入→搜索→详情→目录→正文→TXT/EPUB，可在模拟器复现 | 已通过文本基础流程 |
| 真实源逐阶段 | `source-catalog` 当前快照的 11 源 | 分别记录搜索/详情/目录/正文/登录/WebView/网络状态，不能把 403、超时算“0 命中” | 轻小说机翻已完成整书；rezero 完成到首章；其余有规则或网络缺口 |
| 跨站兼容 | 主集合 11 源及仅作诊断的补充来源 | 按不同站点和机制记录请求、响应语义、搜索、详情、目录、单章的首个失败阶段；每类修复有离线回归及真实复测，不把一个站点的多本书算作广度 | 当前首要工作；详见[跨站诊断](cross-source-compatibility.md) |
| 批量整书 | `downloads/batch/books/<bookUrl-SHA256>/` | 若执行 100 本压力验收，须为不同书 URL，逐本核对完整章节、非空正文、TXT 字节数与 SHA-256；失败或部分下载不计数 | 已停止增加下载数量；此前 52 本有效文件只代表同一简单源的压力样本 |

每新增一种语法或宿主方法：先取一个最小真实规则/页面（允许提交时用合成等价夹具），对照官方实现确定预期，补 Java 单测，再重跑对应真实源阶段。真实测试记录必须固定书源快照、时间、客户端环境、HTTP 状态与字段阶段。不要提交账号、Cookie、原始正文或第三方完整书源；本地下载目录均被 Git 忽略。

## 当前优先级与不等价点

- 优先完善官方模式识别与 URL 选项；再扩展 JS 宿主、变量作用域、Cookie/登录和编码。顶层 `&&/||/%%` 已补合并基础路径，并避免把 JSONPath 过滤器内部的同名运算符拆开，但混合模式未穷尽。正文 `replaceRegex` 已补“合并后执行”与默认不跨行的基础路径及 `<js>` 后接 `##` 测试；未证明所有真实字体映射正确。
- Android WebView 要在模拟器/设备上独立验证；JVM 探针不能代替。遇到网站登录、403、CF、付费或频率限制时记录边界，不绕过访问控制。
- 正文分页调用应传入目录里已知的下一章 URL；`nextContentUrl` 若指向该 URL 就停止本章合并。这与官方 `BookContent.analyzeContent` 的边界处理一致。只调用不带下一章参数的兼容接口时，无法保证这一边界。
- `BookInfoRule.init` 已有 JS 字符串与 JSON 对象的 Java 回归，`TocRule.isVolume/isVip` 也有最小回归，但再漫画仍被上游 URL 失配阻断，猫眼则停在详情业务错误；都未完成真实端到端验证。`isPay`、内容净化和书源 `bookUrlPattern` 仍未完整实现。不能把合成夹具通过当作真实站点标记的证明。
- 当前优先目标是尽量发现跨站规则兼容问题：按**独立站点 × 不同规则机制 × 最深通过阶段**衡量覆盖。此前提出的 100 本量化验收可在兼容缺口收敛后作为压力测试，不能替代跨站验证；有未覆盖项时继续标记为未完成。
