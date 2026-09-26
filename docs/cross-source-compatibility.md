# 11 条书源跨站兼容诊断

快照：`20260926-152117-ccb9324c`。样本来自[吉的书源集](https://source.jwyihao.top/)的 9 个原始文件，经 `source-catalog` 按书源 URL 去重为 11 条。本文件记录的是当前 Java 工程的兼容诊断，不复制第三方完整书源，也不把站点不可达误判为规则错误。

## 2026-09-27 单词低频探针

命令：`./gradlew :source-core:liveSourceProbe -PprobeQueries=无职转生`。每次搜索间隔 1.5 秒；JVM 探针没有 Android WebView 与登录状态。根据根因，下一步分为“实现差异”“需要 Android/账号环境”“上游站点/书源变化”，不能统一算作搜索零结果。

| 书源 | 搜索阶段实测 | 当前归因和下一步 |
| --- | --- | --- |
| 哔哩轻小说🏷 | JS 对空正则结果取 `[1]` | 搜索前置脚本依赖站点 guard 接口；单独只读检查曾得 HTTP 403。先在有授权的浏览器/登录环境确认响应，再判断规则宿主差异 |
| 哔哩轻小说•台 | JVM 需要 WebView；Android 搜索为 0 | 模拟器 WebView 对“无职转生”拿到 HTTP 200、标题 `Programmable Search Engine`，默认延时约 1 秒时 HTML 61,963 字符，额外延时 5 秒时 62,093 字符，均解析 0 条。单纯延长等待未解决；需检查 CSE 结果容器/站内索引与脚本加载状态，不能标为搜索兼容。`java.setContent/getString` 现有最小 HTML 宿主，但该源无列表命中，尚未真实触发 |
| 轻之文库轻小说 | JVM TLS 握手由远端终止；Android 连接被关闭 | 两种环境均在请求阶段失败，尚未进入该源复杂 `java.getElement`/AES 防护规则；需换正常授权网络复测，不能推断规则语法 |
| 三七轻小说 | JVM 未跟随 HTTPS→HTTP 的 302；Android HTTP 200 但只是域名出售页 | 已补 `java.getElement/getElements/getString/setContent` 的最小 HTML 宿主与离线回归，随后探针才暴露真实站点问题。JVM 搜索请求按探针 UA 跳到 `ww17.m.37yq.com` 的 HTTP 地址，不能再把空 302 算作零结果；Android 返回 975 字节、标题 `37yq.com` 的出售页，0 命中同样无效。复杂缓存/AES/Cookie 分支仍未被真实页面触发 |
| 轻小说文库 | 修复 URL 选项后，暴露 `cookie` 未定义 | 之前的 `MalformedJsonException` 是请求尾部 `@js:` 被误当 JSON；已补语法单测。下一步是共享 Cookie 宿主及登录/GBK 编码验证；站点曾跳转后 HTTP 403 |
| lk轻之国度 | `cookie.getKey` 缺失 | 搜索请求需用户 token 中的 `security_key`；未登录不能声称规则错误或零结果 |
| rezero | 有效 0 命中 | 此站只收录 RE0 系，关键词不适用；用 `RE0` 已验证搜索 1 条、详情、887 章目录和首章 |
| 轻小说机翻🤖 | 20 条 | 搜索到首章、整书下载链路已实测；但不能代表其他 10 源 |
| 真白萌 | HTTP 403 | 登录/站点防护边界；不绕过访问控制 |
| ESJ Zone | 有效 0 命中，本轮未超时 | 需用站内确定收录的作品与 Android 环境再测；追加的 `無職轉生`、`異世界` 探针超时，不能以本轮 0 条证明整源可用 |
| 再漫画 | 修复默认规则后 10 条 | `@@` 内嵌规则、`class…@tag…` 连续选择器已加 Java 回归测试。首条详情 API 返回 HTTP 200 但 JSON `errno=2, errmsg=漫画不存在`，目录因而失败；站点链接变为 `/details/58227`，源中正则还按旧 `info.xxx.html` 提取 `comic_py`，属于上游规则失配，不能标成详情成功。其 `ruleBookInfo.init` 与 `ruleToc.isVolume` 机制现有离线回归，仍不能越过上游 URL 失配作真实端到端验证 |

## 静态规则特征与待补宿主

对当前去重 JSON 的 `searchUrl/ruleSearch/ruleBookInfo/ruleToc/ruleContent` 搜索 `java.*`、`cookie.*` 调用，仅用于定位测试，不等于方法执行覆盖：

| 类别 | 当前书源用到的调用 | 兼容测试方向 |
| --- | --- | --- |
| DOM/规则 | `java.getElement/getElements/getString/setContent` | 已有 HTML 列表、`setContent`、字段提取的合成回归；当前只把元素作为 HTML 字符串数组暴露，尚非官方完整 `Elements`/对象模型，也未覆盖 JSON/正则 DOM 宿主组合 |
| 网络与 Cookie | `java.ajax/get/post`、`cookie.getCookie/getKey/replaceCookie` | 同站与跳转 Cookie、请求头、GBK 表单、登录态；JVM 与 Android 分别验证 |
| 编码/资源/加密 | `java.base64Encode/base64DecodeToByteArray/cacheFile/createSymmetricCrypto/queryTTF/replaceFont` | 先按实际书源样本和官方接口定契约，不把占位返回算实现 |
| 辅助 | `java.log/toast/sleep/toURL/timeFormatUTC/t2s/put` | 副作用、时间、繁简转换及 URL 对象要逐项测；`t2s` 目前仍是原文占位 |

书源规则字段还出现 `init`、`isVolume`、`isVip`、`payAction`、`replaceRegex`、`nextContentUrl`、`subContent`、`imageDecode` 等。当前 `SourceEngine` 只执行其中一部分；例如再漫画的 `ruleBookInfo.init` 保存 `bid` 给章节 URL 使用，目录的 `isVolume` 标注卷，这两个机制已补离线测试，但真实源仍被失效详情 URL 阻断。100 本都来自同一个简单文本源，无法覆盖这些分支。

本轮根据官方 `BookInfo.kt` 与 `BookChapterList.kt` 补齐了详情 `init` 先于其他字段执行、目录 `isVolume/isVip` 判断及无 URL 卷标题的稳定占位 URL；官方 `String?.isTrue` 仅把空、`null`、`false/no/not/0/0.0` 判为假。合成的漫画 JSON 回归验证 `init` 保存 `bid` 后被章节 URL 使用，并验证卷与 VIP 标记。此为**机制单测通过**，不是再漫画真实站点通过；`isPay` 仍未进入当前 `Chapter` 模型。

后续优先级：先让诊断区分请求/响应语义/规则字段；对 `@@`、默认选择器链、URL 后置 JS 建回归；接着实现受控 Cookie 与规则 JS 上下文，再在 Android 环境验证 WebView/登录。每次改动都以仓库内 Legado 官方实现核对；[上游 skill](../legadoSkill/README.md) 作为案例库，其部分章节对 `nextContentUrl` 的说法互相矛盾，不能直接当作规范。批量下载已按用户纠偏停止，已有 52 本离线有效文件保留在 Git 忽略目录中，100 本仅作为后续压力/整书验收，不替代跨站兼容测试。

## 验证策略修正

目标是尽量暴露**不同规则机制的失配**，而不是提高下载本数。每个书源优先挑站内可确认存在的作品，依次检查请求规划、响应语义、搜索列表、详情、目录、单章；只对已通过前一阶段的样本进入下一阶段。对 HTTP 200 但防护页、空壳页面、业务 `errno` 等情况单独归类，不能计作成功或“无作品”。当前核心对传输层尚未解决的 3xx 报错，JVM/Android 探针的 0 命中均标作 `ZERO_UNVERIFIED`，诊断日志另标记域名出售页；不要把这个探针标记误当成通用防护页识别器。无登录凭据的私有 API 记录为环境前提，不尝试绕过。每类修复至少加入一个脱网夹具回归，再用一个不同站点的同类规则交叉验证。

当前 11 源覆盖静态 HTML、动态 WebView、JSON API、JS 请求脚本、GBK/表单、登录 Cookie、字体/加密和漫画列表，但各类别并不均衡。后续若增加测试源，应从具有明确使用许可的独立来源补足 XPath、正则正文、多页目录、多页正文、POST 表单、正文图片与音频，而不是从当前 11 源重复抽相似书。新增源仍进入独立书源管理快照、按 URL 去重并保留来源与许可记录。

Android 调试包内提供 `CompatibilityProbeActivity`，可通过 `adb shell am start` 传 `catalogUrl`、`sourceFilter`、`keyword`、`captureFirst`，另可用 `--el webViewDelayMillis 5000` 做等待时间对照；`BookGetterProbe` 常规请求日志只记主机、状态、HTML 长度/标题和阶段结果，不保存正文与完整请求 URL（异常文本仍需谨慎处理，勿公开原始 logcat）。调试过程曾发现占位符正则的 `}}` 在 JVM 容忍、Android ICU 报错，现已转为显式 `\\}\\}`。官方 `BackstageWebView.kt` 对无 `webJs` 的请求默认在 `onPageFinished` 后约 1 秒取结果，支持 `webViewDelayTime`；本工程现已对齐这个默认时间并传递延时选项。CSE 页面即使再等 5 秒仍为 0，不能继续仅靠增加固定等待猜测；要补 DOM 就绪/网络错误的可观测性。

模拟器对照（同一快照，2026-09-27）：

| 书源/词 | 响应 | 阶段结果 | 结论 |
| --- | --- | --- | --- |
| 哔哩轻小说•台 / 无职转生 | WebView 200；默认约 1 秒 HTML 61,963 字符，额外 5 秒 62,093 字符，Google CSE 标题 | 两次搜索均 0 | 延时并未产生规则匹配结果；尚未验证列表宿主或确认 CSE 是否索引该词 |
| ESJ Zone / 無職轉生 | 请求超时 | 停在搜索请求 | 网络/站点可达性待复测 |
| 轻之文库轻小说 / 无职转生 | Android `connection closed`，JVM 曾在 TLS 握手失败 | 停在搜索请求 | 两端均未进入复杂 JS 列表规则，不能算规则失败或空结果 |
| 三七轻小说 / 无职转生 | JVM HTTP 302 到 HTTP 子域（未跟随），Android HTTP 200、975 字节域名出售页 | JVM 现报 `SEARCH.searchUrl` 未解决重定向；Android 表面 0 条 | 两种响应都不是书源搜索结果。`curl` 的普通 UA 可看到页面文案 `This domain may be for sale`；不要计作有效空结果 |
| 轻小说机翻🤖 / 无职转生 | API 各阶段均 200 | 搜索 20、详情、目录 23、首章 7,945 字符 | Android 上 JSON/JS 路径的正对照；不据此推断其他书源 |
| rezero / RE0 | 搜索经百度跳转，正文在站点自身，均 200 | 搜索 1、详情、目录 887、首章 1,979 字符 | Android 上跨域请求/静态 HTML 路径的正对照 |

Release 构建不包含调试 Activity（已检查合并清单）；`assembleRelease --offline` 因本地缺少 Android Lint 依赖失败，尚不能据此断言完整 Release 构建通过。
