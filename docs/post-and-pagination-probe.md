# 补充集合：POST 搜索、签名 URL 与正文翻页边界

2026-09-27，从 [AOAOSTAR 收录的破冰书源集合](https://legado.aoaostar.com/sources/4dc410d1.json)中挑不同请求机制的站点做低频探针。集合标示 128 条，源规则仅在探针内存中读取，不并入 [主书源管理目录](../source-catalog/README.md)、不提交第三方原始 JSON。聚合仓库的许可不自动涵盖每条第三方书源；这里只记录机制、错误分类和必要的站点标识。

命令模板：`./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json -PprobeSource=<域名> -PprobeQueries=斗破苍穹`；需要验证首条详情到首章时再加 `-PprobeCaptureFirst=true`。每个站点仅一个搜索词，不批量抓书。

| 书源 | 机制 | 结果与边界 |
| --- | --- | --- |
| 书香之家 `s.lhdqsb.cn` | JSON API | JVM TLS 握手被远端终止，未进入规则 |
| 熬夜看书 `get.aoyekanshu.com` | `{{java.md5Encode(java.base64Encode(...))}}` 签名 URL | 原缺受控宿主方法；按官方 `JsEncodeUtils` 补 UTF-8 Base64（无换行）与小写 MD5 固定向量测试后，JVM 到 TLS 握手失败、Android 到 `connection closed`。只能确认 URL 规划不再卡在宿主，不能宣称站点可用 |
| 猴子阅读 `api.leeyegy.com` | 跨域搜索 URL | JVM 收到 HTTP 200、标题 `Redirecting` 的 451 字符页面，0 条未验证；另一次只读 `curl` 收到 HTTP 429，不能把它当正常空搜索 |
| 9x 阅读器 `novel-api.elklk.cn` | JSON API | JVM TLS 握手被远端终止 |
| 有度中文网 `www.yodu.org` | POST 表单、默认多类名、字段 `||`、正文下一页 | 初次响应是正常搜索结果页却被解析为 0；修复后 JVM/Android 均完成搜索→详情→目录→首章，见下文 |

有度书源的 `bookList` 是 `class.ser-ret@tag.li||class.pagebook`，字段规则含 `class.mb5 fs20 f_mbo pt5 ell@tag.a@text||...`。搜索页实际有 `<h3 class="mb5 fs20 f_mbo pt5 ell">`。Legado 的默认 `class.` 路径会调用 jsoup `getElementsByClass`，它接受带空格的多类名；原引擎将它变成 CSS 后代选择器，且字段 `one()` 没有依次回退 `||`，导致所有卡片因书名为空被丢弃。已补最小 Java 回归并按官方路径修复。

复测 `斗破苍穹`：JVM 搜索 POST HTTP 200、15 条；首条《斗破苍穹19·勇闯中域》详情 HTTP 200、目录去重后 107 条、首章一页 600 字符。Android 模拟器同样搜索 15 条、目录 107 条、首章 600 字符。后续核对发现这 107 条中有 [1 条 `javascript:` 占位](additional-cross-site-probe.md#有度目录的107-章含义)，不能说 107 章均可抓取。这是来自主集合与 22 源补充集合之外的另一种跨环境正对照，不说明整个 128 源集合可用。

首次 JVM 首章探针错误地调用不带下一章 URL 的简化 `content()`，沿 `nextContentUrl` 连续请求约 50 页并把后续章节并入当前正文，得到错误的 28111 字符。修正后探针传入目录中下一条**非卷**章节的 URL，首章只请求一页。核心简化接口对配置了 `nextContentUrl` 的书源现直接要求显式边界；App、调试探针与批量工具共用“跳过卷标题找下一章”逻辑，批量工具也不再把卷标题当正文下载。此前 28111 字符结果是失败证据，不得计入正文质量或下载成功数。

后续按官方 `BookContent.analyzeContent` 的顺序补上了正文**多页合并后**执行 `ruleContent.replaceRegex`：支持开头 `<js>…</js>` 接后置 `##正则##替换`，并以合成私用区字符 U+E800 验证字体映射脚本能执行。`source-core:test`、`export:test` 与 Android Debug 构建通过。2026-09-27 再测同一有度样本，Android 搜索 POST HTTP 200、15 条，目录 107 条（含 1 条占位）、首章 HTTP 200、处理后 600 字符。首章未必含需映射的私用区字符，故这仍**不能证明**有度完整字体表或所有章节正确；也未下载整书。
