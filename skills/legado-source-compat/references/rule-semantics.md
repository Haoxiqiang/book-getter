# 普通书源规则语义

基准：项目根目录 `../legado` 中的官方 Legado `62003ce732a7e30602754d28996da7f98b9ea296`，上游 `legadoSkill` `1e59ff3c1fa428a1a0981f42249fd4cf5d347644`。版本变化时重新核对。冲突时采用官方执行代码 → 可重复 Android 差分 → 脱敏真实页面 → 上游文档/案例 → Python 近似模拟的证据顺序。细节索引见上游 [`docs/rules`](../../../legadoSkill/docs/rules/README.md)。

## 模型与阶段

- `BookSource.bookSourceUrl` 标识普通书源；`bookSourceType` 为 0 文本、1 音频、2 图片、3 文件、4 视频。字段可空性、默认值和嵌套模型见 [`BookSource.kt`](../../../../legado/app/src/main/java/io/legado/app/data/entities/BookSource.kt)及上游[字段表](../../../legadoSkill/docs/rules/schema.md)。按目标流程验证条件必需字段，不强迫搜索、详情、目录、正文全部存在。
- 搜索/发现选列表并逐项提取字段；详情合并已有字段、可给目录或文件下载入口；目录解析章名、地址、卷及 `isVip`/`isPay`；正文可能是文本或媒体资源。阶段共享上下文和 `@put/@get`，不能把字段都视为无状态函数。调用方见 `model/webBook/`。
- `ruleToc.nextTocUrl` 是目录下一页。`ruleContent.nextContentUrl` 用于同章正文分页；若等于已知下一章 URL，官方停止本章合并。`sourceRegex` 在获取正文路径中使用，`replaceRegex` 在正文合并后使用。参见 [`BookContent.kt`](../../../../legado/app/src/main/java/io/legado/app/model/webBook/BookContent.kt)。
- JSON 数组是导入容器，单个 `BookSource` 是对象；单对象是否可由某入口导入应核对该入口。未知字段可存档供透传，不能据此声称已能执行。

## 表达式与请求

- [`AnalyzeRule.kt`](../../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt)按输入与规则段判型：Default、`@CSS:`、`@XPath:`、`@Json:`、`@js:`、`<js>…</js>` 和网页 JS 走不同路径。先识别模式，再处理选择器、动作和替换；不要按首字符或简单 `split('@')` 猜测。`getElements`、`getStringList`、`getString` 结果形态不同。
- 默认 HTML 规则有 `class.name`、`tag.name`、`text.片段`、零基/负索引、排除和区间语法；`@CSS:` 交给 Jsoup。`:contains`、`:first-child` 等伪类不能全局禁用或全局放行，应按具体路径和 Jsoup 版本测试。`@textNodes` 取直属文本；`@html` 去 script/style 后返回外层 HTML，`@all` 返回原外层 HTML。见 [`AnalyzeByJSoup.kt`](../../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt)。
- `||` 依次回退到首个非空值，`&&` 合并，`%%` 交错；不能误拆 JSONPath 过滤器内部的运算符。`##匹配##替换` 是常规替换；第四段（常见尾部 `###`）走首个匹配片段的替换路径，未匹配为空，不是“只替换全文第一处并保留剩余文字”。
- [`AnalyzeUrl.kt`](../../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)处理 JS、`{{ }}`、页码候选、后附 URL 选项、相对地址及请求。`method`、`body`、`charset`、`headers`、`webView` 等选项影响真实请求；日志应留最终 URL/方法/状态和脱敏头，不能把模板原文当发送地址。
- Rhino 规则脚本、`java.*` 宿主方法和 WebView 页面脚本不是同一环境。按场景核对绑定、返回类型及白名单；未知方法报告阶段和名称，不开放任意 Java 类或反射。见 [`JsExtensions.kt`](../../../../legado/app/src/main/java/io/legado/app/help/JsExtensions.kt)与上游[JS 契约](../../../legadoSkill/docs/rules/javascript.md)。

旧版冲突及其官方依据见上游[勘误表](../../../legadoSkill/docs/rules/errata.md)；这里的摘要不替代遇到边界问题时读取执行代码。

## Java 扩展的验证口径（2026-10-22）

模式前缀、URL 空白选项、统一查询字符集和列表逐项后置替换有独立离线回归。`timeout`、`followRedirects`、JS 请求头、源变量/登录头、Cookie 和 `concurrentRate` 必须核对最终请求与状态隔离，不能只断言宿主方法存在。Rhino 嵌套执行仍须维持同一个脚本截止时间和类白名单，禁止为修复重入而取消安全限制。

RSS 执行复用规则与请求设施，但身份、类型值、文章变量与阅读目标独立；具体见[RSS 边界](rss-compat.md)。最终统一测试和 Android 合成夹具执行状态写入[实测记录](../../../docs/live-source-tests.md)，本轮离线用例不能增加真实站点覆盖。
