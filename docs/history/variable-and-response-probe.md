# 跨站探针：对象型 POST、书籍变量与伪正文

2026-09-27，从 [AOAOSTAR 的 128 条补充集合](https://legado.aoaostar.com/sources/4dc410d1.json)中选机制不同的 3 站低频验证，每站只搜 `斗破苍穹`；书源 JSON 仅在内存中使用，不并入主目录、不提交。此次集合响应 SHA-256：`05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。命令模板：`./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json -PprobeSource=<站名> -PprobeQueries=斗破苍穹 --offline`；仅追书的详情/目录/首章追加 `-PprobeCaptureFirst=true`。

| 站点 | 新机制及阶段证据 | 当前结论 |
| --- | --- | --- |
| 番薯小说 | 搜索 URL 选项使用对象型 POST `body`。原引擎在 `RequestPlanner.body` 对 `JsonObject.getAsString()` 抛异常，尚未发请求；按官方 `AnalyzeUrl.UrlOption.getBody` 序列化 JSON 并设置 `application/json` 后，JVM 到 TLS 握手失败、Android 连接被关闭 | 请求规划缺口已用 Java 合成夹具修复；真实 API 未进入响应解析，不能算站点通过 |
| 爱青果 | 表单 POST 搜索在 JVM TLS 握手失败 | 网络/服务端前提，未测试规则；不得报 0 命中 |
| 追书小说 | JSON 搜索 HTTP 200、20 条；详情 HTTP 200；目录 HTTP 200、1658 章。`ruleBookInfo.name` 的 `@put:{bid:$..x_id}` 原先未存变量，目录生成的正文 URL 留有 `@get:{bid}` 导致 URI 错误；加书籍作用域变量与合成的双书隔离测试后，首章请求到 `yd.jsxsapp.com` HTTP 200 | 变量机制的本样本已走通，但正文仍**不可用**：见下文 |

追书首章接口返回约 572 字符 JSON，规则提取的 `chapter.body` 为 102 字符，不是正文，而是“当前版本过低、需升级 App”的提示。初版探针误记 `CONTENT_OK`，因为 HTTP 状态和非空字符串都合格。现在响应诊断先解析 JSON 转义后的嵌套 `chapter.body`，标 `APP_UPGRADE_REQUIRED`；核心正文校验也拒绝此占位。JVM 与 Android 复测均为搜索 20 条、目录 1658 章、正文 `CONTENT.ruleContent.content` 错误，不再让导出把它当成功章节。这里只识别这一明确提示，不把任意短章节一概判错，也不尝试绕过服务端版本限制。

行为依据：[Legado `AnalyzeUrl.kt` 的 `UrlOption.getBody`](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)、[`AnalyzeRule.kt` 的 `splitPutRule`、`put/get` 与变量作用域](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt)。本工程当前只覆盖详情→目录→正文的**书籍作用域** `@put/@get` 基础链；官方还支持章节、规则数据、书源及局部变量的优先级，搜索字段的逐书变量绑定也未等价。合成测试证明机制，不证明其他站点、其他变量层级或追书正文可用。

设备回归另发现：JVM 接受的 `@put/@get` 正则未转义右花括号，Android ICU 在 `RuleEvaluator` 初始化时抛 `PatternSyntaxException`。显式转义后再安装 Debug 包复测，上述追书链路可运行并正确停在升级提示；这类规则改动必须在 Android 上实际运行，不能只看 JVM 单测。
