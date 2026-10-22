# 补充书源跨站探针（不并入主书源目录）

2026-09-27，本地通过 `liveSourceProbe` 对 [AOAOSTAR 的 XIU2 精品书源集合](https://legado.aoaostar.com/sources/71e56d4f.json)做一次低频、逐源只搜一个词的兼容性筛查。该集合公开展示 22 条，探针在内存中读取 JSON；不纳入 `source-catalog/feeds.json`，不提交或再发布第三方完整书源。主书源仍严格来自 [吉的书源集](https://source.jwyihao.top/) 当前页面的 9 文件/11 源。聚合仓库的许可不必然覆盖每条第三方规则，补充集合只用于本地兼容诊断。

同日运行主目录 `updateCatalog`、`verifyCatalog`，得到新本地快照 `20260926-170836-f658eae9`，仍为 11 条、无重复；9 个原始文件的 SHA-256 与旧快照 `20260926-152117-ccb9324c` 逐一相同。因原始文件和快照均被 Git 忽略，只记录哈希对比结果，不提交原文。

命令：`./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json -PprobeQueries=斗破苍穹`。JVM 探针无登录态和 WebView。`ZERO_UNVERIFIED` 只表示当前规则返回 0，不能证明源站没有该作品。

| 书源 | 搜索探针结果 | 判读 |
| --- | --- | --- |
| 起点中文 | HTTP 202，随后 `cookie` 未定义 | Cookie 宿主缺口；响应也需单独判读 |
| 番茄小说2 | API HTTP 200、53 字符，0 条 | 复测确认是业务错误，不是有效空搜索；见下文 |
| 酷我小说 | API HTTP 200、127 字符，0 条 | JDK 禁止源内 `Connection` 请求头，探针注明省略；API 返回空 `data`，需换站内确定有书的词 |
| 69书吧.com | `<js>` 开头的 `/modules/...` 被 JS 当正则，非法 flag `a` | 原规则脚本本身疑似失效；未发请求 |
| 69书吧 | 跳至 `www.69shuba.com`，HTTP 200、7283 字符，0 条 | 响应为站点首页样式，不能当搜索无结果 |
| 速读谷 | 跳至 `www.sudugu.cc`，HTTP 200、3096 字符，0 条 | 页面标题“小说搜索”；需核对 POST/规则与新域名 |
| 得奇小说网 | EOF | 请求阶段失败 |
| 快书网 | TLS 证书链不可信 | 请求阶段失败 |
| 天天看小说 | HTTP 403 | 访问控制，不绕过 |
| 独步小说网 | HTTP 200，标题“提示信息”，0 条 | 响应语义非搜索结果，待查站点提示 |
| 手机小说 | HTTP 200，标题“全部小说_手机小说”，0 条 | 返回的似乎不是搜索页，待查跳转 |
| 铅笔小说 | Cloudflare HTTP 403 | 访问控制，不绕过 |
| 来看文学 | `java.longToast` 未提供 | 源脚本进入需用户交互的防护流程，不补无效果空壳 |
| 大文学无错小说网 | HTTP 403 防护页，随后 `java.longToast` 未提供 | 访问控制/交互前提 |
| 和图书 | HTTP 403 防护页，随后 `java.longToast` 未提供 | 访问控制/交互前提 |
| 思路客 | EOF | 请求阶段失败 |
| 顶点小说 ddxs | HTTP 403 防护页，随后 `java.longToast` 未提供 | 访问控制/交互前提 |
| 艾途小说 | HTTP 401 | 认证前提 |
| 阅友小说 | 初探 HTTP 200、10750 字符却 0 条；修复 `@onclick@js:` 后搜索 5 条 | 当前最有价值的第二站端到端正对照，见下文 |
| 就爱文学 | EOF | 请求阶段失败 |
| 八一中文 | 修复 `java.ajax` 的 URL 选项解析后，发出 POST 并收到 HTTP 403 防护页，进入 `java.longToast/startBrowserAwait` | 原先将整个 `url,{options}` 当普通 URL 是实现缺口；现在停在明确的真人验证边界，不绕过 |
| 武林中文网 | TLS 握手终止 | 请求阶段失败 |

阅友小说复测命令：`./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json -PprobeSource=m.suixkan.com -PprobeCaptureFirst=true -PprobeQueries=斗破苍穹`。结果：搜索 5 条；首条《灵破苍穹》详情 HTTP 200，目录 HTTP 200、1649 章，首章 HTTP 200、正文 3671 字符。只读取首章验证规则，未下载全书。实际搜索页含 `.v-list-item` 卡片，书籍链接藏在 `onclick="newWebView('/b/229093.html', '', '')"`；原引擎不支持 `@onclick@js:` 内联链，因 bookUrl 为空而丢弃全部卡片。新增 Java 合成回归后真实链路通过。这证明与主集合的轻小说机翻、rezero 不同的 HTML/属性/JS/长目录路径可用，但不代表其余 21 源可用。

Android 模拟器同词、同源、同样只读首章，得到搜索 5 条、详情成功、目录 1649 章、首章正文 3671 字符。网页正文响应长度与 JVM 相差 3 字符（站点实时页面差异），规则输出一致；此处是跨执行环境正对照，不是整书下载验收。

换词 `诡秘之主` 也返回 5 条（首条《太初之主》）。该站似乎进行宽松/分词匹配；搜索链路通过只表示书源规则能提取返回卡片，不表示精确找到了输入作品。后续验收必须分别检查“解析成功”和“作品匹配度”。

八一中文样本验证了另一缺口：官方 `JsExtensions.ajax` 会把传入字符串交给 `AnalyzeUrl` 解析 URL 选项；本工程原先直接把 `url,{'method':'POST',...}` 传给 HTTP 客户端。现 `java.ajax/get` 也通过 `RequestPlanner`，并补了表单编码的离线回归。此处仍未实现官方完整 `webJs`、`startBrowserAwait`、共享 Cookie 等能力；不能通过静默忽略真人验证来制造“成功”。

## 复合规则与正文质量回归（2026-09-27）

本轮补充集合在线获取内容的 SHA-256 为 `24fe11c400ddc8754703ff0c4d8a249baf024da3a15563de805cc134140959a0`（22 条）；原始 JSON 仍只在内存中使用，不提交。番茄小说2 的 `ruleSearch.bookList` 为 `$..book_data[0]&&$.data[*]`，用于兼容两种 API 结构。对照官方 `AnalyzeByJSonPath.getList` 与 `RuleAnalyzer.splitRule` 后，Java 引擎补了顶层 `&&/||/%%` 的列表合并、回退和交错，并测试 JSONPath 过滤器内部的 `&&` 不被错切；两种合成 API 响应都能搜索出书。这是**机制回归**，不是番茄真实站点成功。

真实番茄搜索仍返回 HTTP 200、53 字符、0 条。用同参数低频只读请求检查 API，得到 `code=100103`、`message=PARAM_INVALID`、`data=null`；JVM/Android 响应诊断均标 `BUSINESS_ERROR`，搜索探针分别标 `RESPONSE_SUSPECT`/`SEARCH_RESPONSE_SUSPECT`，而非有效无书。后续需判断书源请求参数是否过时或该 API 的访问前提，不能用合成通过掩盖这一点。

同轮跨站回归发现阅友正文从此前约 3671 字符骤降为 725 字符。原因是本工程替换正则曾无条件开启 DOTALL，`（本章未完）` 类规则中的 `.*` 跨行吞掉后文；官方 `AnalyzeRule.replaceRegex` 使用普通 `regex.toRegex()`，仅规则显式指定 `(?s)` 时才跨行。修正并补离线正文测试后，再测阅友：JVM/Android 都搜索 5 条、目录 1649 章、首章 3626 字符。站点实时响应长度略有波动，不用字符数精确相等证明正文全正确；但 725 字符的截断已由规则语义与复测共同定位并消除。有度中文网的 POST 搜索→目录 107 条（其中 1 条为 `javascript:` 占位）→首章 600 字符也同期复测通过；[占位分析](additional-cross-site-probe.md#有度目录的107-章含义)。
