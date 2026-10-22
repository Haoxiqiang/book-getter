# Legado 规则契约与回归要求

本页集中维护 `new-book-getter` 实现时需要遵守的规则语义；模型与目标能力见[书源定义 RFC](source-definition.md)，当前支持子集见[能力矩阵](compatibility.md)，RSS 专用分支见[RSS 边界](rss-compatibility.md)。这里的要求不表示所有功能已实现。

官方行为以兄弟目录 `../legado` 的源码为准，固定提交与资料版本见 RFC。可重复 Android 差分用于核对平台表现；上游[规则索引](../legadoSkill/docs/rules/README.md)、[勘误](../legadoSkill/docs/rules/errata.md)和 Python 模拟器只作线索。上游 MIT 许可不等于第三方书源或正文的再分发许可。

## 阶段、身份与结果

- 普通书源按 `bookSourceUrl` 识别，类型 0 文本、1 音频、2 图片、3 文件、4 视频；RSS 有独立身份和类型。导入包装由入口决定，未知字段可存档，不意味着可执行。
- 搜索/发现按条目上下文提取字段，详情合并已有书籍信息，目录保持顺序和章节标记，正文可返回文本或媒体资源。源不必覆盖全部阶段；只验用户目标实际调用的路径。
- 正常零结果须有正确响应与合理零命中证据；HTTP 错误、登录页或宿主不支持不得折算为空列表。错误保留模型、源 ID、阶段、字段和代码。
- 可选元数据的失败保留告警，不遮蔽有效导航；书名、地址等当前路径必要字段失败可见。搜索项变量随书传递，书籍、章节、文章与源状态按各自作用域保存，不把所有字段视为纯函数。

官方入口：[BookSource](../../legado/app/src/main/java/io/legado/app/data/entities/BookSource.kt)、[WebBook](../../legado/app/src/main/java/io/legado/app/model/webBook/WebBook.kt)、[BookList](../../legado/app/src/main/java/io/legado/app/model/webBook/BookList.kt)、[BookInfo](../../legado/app/src/main/java/io/legado/app/model/webBook/BookInfo.kt)。

## 模式、选择器与替换

- 先按输入和规则段识别 Default、`@CSS:`、`@XPath:`、`@Json:`、`@js:`、`<js>…</js>`，再解析动作、组合与后置替换。模式前缀大小写不敏感，JSON 属性与选择器内容保持原样。`getElements`、`getStringList`、`getString` 的返回形态不同。
- Default 的 `class.name`、`tag.name`、`text.片段`、零基/负索引、排除及含两端的区间不能误当纯 CSS。CSS 伪类按实际模式和 Jsoup 版本验证，不全局禁用或放行。列表开头 `+` 控制入口、`-` 倒序，需先处理。
- `@textNodes` 取直属文本；`@html` 去 script/style 后返回外层 HTML，`@all` 保留原外层 HTML。DOM 列表进入 JS 后仍保留节点语义；XPath 列表保留节点，过滤器里的 `@` 不是动作分隔符。
- `||` 取首个非空分支，`&&` 合并，`%%` 交错；JSONPath 过滤器内的同形运算符不能机械拆开。JSONPath 可内嵌 `{$.字段}`，`<js>` 后仍可继续 JSONPath。Map 上不存在的 `href/text` 动作返回空以允许回退，不把整张 Map 当 URL；显式 `String(result)` 的视图另行验证。
- `##匹配##替换` 做常规替换；第四段（常见 `###`）从首个匹配片段求替换结果，未匹配为空。列表后置替换逐项执行并保留项内换行；不能解释成“只替换第一处并保留全文”。正文 `replaceRegex` 属合并后的独立层。
- `href<js>…</js>` 是字段结果后置脚本。规则内 `{{…}}` 可能是嵌套规则或 JS，不一概文本替换。URL 字段使用 URL 模式，多匹配链接只取首个，不拼成一个地址。

官方入口：[AnalyzeRule](../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt)、[AnalyzeByJSoup](../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt)、[AnalyzeByJSonPath](../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSonPath.kt)。历史正反例集中在[规则与 JS 探针](history/README.md#规则与-js-宿主)。

## 请求、阶段控制与分页

- 请求模板展开 JS、`{{key}}`/`{{page}}` 和页码候选，再处理相对 URL、字符集与后附 JSON 选项。URL 选项允许逗号周围空白，`method/body/headers/charset/webView/timeout/followRedirects` 必须影响最终请求；`timeout` 为毫秒。整数模板值不附 `.0`。
- 查询值在展开后统一按 `charset` 编码，保留已有百分号查询编码；显式字符集表单与未指定时的已编码检测分别验证。乱码先查响应头、字节与页面声明，不对所有源强制相同编码。
- URL 型源 ID 的 `#` 标签可以带中文或空格，不以严格 URI 校验破坏相对请求；名称型稳定 ID 也不能冒充可请求地址。带 `,{URL选项}` 的章节链接只对路径解析相对地址，保留选项。
- `bookUrlPattern` 完整匹配响应最终 URL，可使搜索直接走详情；未配置且列表为空时有详情回退，配置但未匹配时不回退。`ruleBookInfo.init` 的字符串/对象预处理和可选字段逐项失败分别验收。
- `nextTocUrl` 是目录分页；`nextContentUrl` 是同章分页，调用内容接口时传入已知下一章 URL，命中它即停止。正文合并后再执行 `replaceRegex`。本工程额外将精确返回书籍/目录 URL 的分页停止并告警 `PAGINATION_TO_CATALOG`，此安全扩展不冒称官方等价。
- `isVolume`、`isVip`、`isPay` 独立求值与存储；不能由标记推断付费正文可取，或将 `isPay` 简化成统一收费判断。

官方入口：[AnalyzeUrl](../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)、[BookChapterList](../../legado/app/src/main/java/io/legado/app/model/webBook/BookChapterList.kt)、[BookContent](../../legado/app/src/main/java/io/legado/app/model/webBook/BookContent.kt)。

## JS 宿主、登录态与浏览器

- Rhino 规则脚本、`java.*` 受控宿主、`mainJs` 和浏览器页面脚本是不同执行环境。未知方法给出字段、阶段和方法名；不开放任意 Java 类、反射或文件 API。DOM/Jsoup 只暴露已验证白名单。
- 规则里的 `java.post` 与 `java.head` 保留各自不自动跟随跳转的响应视图，不能复用仅返回正文的 `java.ajax`。超时重载、响应头和 Cookie 断言实际请求/返回，不只检查方法存在。
- 源变量、登录头与 Cookie 按模型和源身份隔离。默认头、保存的登录头、显式请求头按顺序覆盖；登录头默认只隐式发往同 origin，跳转逐跳限制敏感头。阶段和脚本子请求共享源限速及取消/超时策略。
- Rhino 嵌套执行维持同一截止时间与类白名单。`timeFormatUTC` 的时区偏移按毫秒；繁简转换词组例外和转换库资源需随核心打包。
- `WebContentTransport` 承载网页正文脚本和 `sourceRegex` 资源捕获，HTTP-only 明确拒绝。浏览器 URL 正则为有预算的 RE2/J 子集，反向引用、环视给字段诊断；普通规则 Java 正则不因此获得期限保证。Android ICU 会把 CharSequence 转 String，不能用 JVM 字符访问包装证明 Android 时间上限。

官方入口：[JsExtensions](../../legado/app/src/main/java/io/legado/app/help/JsExtensions.kt)。RSS 页面的来源/主 frame/token 及旧 provider 降级见[RSS 边界](rss-compatibility.md)。

## 回归与声明

新增语义应固定官方路径及最小正反例，断言请求、字段和状态隔离；必要时补本地 HTTP、Android 或官方差分。可重复命令和最近执行范围见[验证指南](verification.md)，源 JSON 交付与错误归因见[skill 验证参考](../skills/legado-source/references/verification.md)。

真实站点证据保留在[历史索引](history/README.md)，不得从单站或下载数量外推整体兼容。新增能力、已知限制和优先级分别维护在[能力矩阵](compatibility.md)与[后续工作](remaining-work.md)，本页不重复维护测试数量或实站可用率。
