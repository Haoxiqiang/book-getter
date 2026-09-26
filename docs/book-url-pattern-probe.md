# 搜索直达详情：`bookUrlPattern` 与空列表回退

2026-09-27。本轮目的只是在更多规则分支和站点之间找兼容差异，不下载小说。语义以仓库内 Legado `app/src/main/java/io/legado/app/model/webBook/BookList.kt` 的 `analyzeBookList` / `getInfoItem` 为准：搜索响应的**最终 URL** 用完整正则匹配 `bookUrlPattern`；命中后跳过搜索列表，直接用 `ruleBookInfo` 解析同一响应。未设置该模式而列表为空时，也尝试同一详情回退；已设置但未匹配时不回退。这与“关键词匹配正则”或“任何零结果都当详情”不同。

Java 核心现在覆盖这两个分支，且直达解析不重复请求详情页；无效正则定位到 `SEARCH.bookUrlPattern`。脱网回归覆盖搜索请求被重定向到详情最终 URL、列表为空有/无模式、无效模式，共 3 项。`./gradlew :source-core:test :app:assembleDebug --offline` 通过。这里的**机制回归不等于实站已触发**；详情页 `init`、变量与全部 Legado 书籍状态仍未完全等价。

静态样本：主目录 11 源中 2 条有非空模式（轻小说机翻、ESJ Zone）；补充 [关耳女频 86 源](https://legado.aoaostar.com/sources/e3e5d620.json)有 4 条，[128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)有 33 条，[XIU2 22 源](https://legado.aoaostar.com/sources/71e56d4f.json)有 1 条。它们只用于本地诊断，不并入主书源目录，不提交原始第三方 JSON。

对关耳集合的 4 条模式源以“故事”各搜一次、无详情/正文抓取：

| 站点 | 实测最深阶段 | 不能推断的部分 |
| --- | --- | --- |
| 格格党① | 请求 HTTP 200，但返回约 4.5 KB 首页形态，探针为 `ZERO_UNVERIFIED`；页面标题编码显示乱码 | 没有匹配模式，也不能把零结果视为规则通过；源的 URL 选项指定 GBK，但当前响应头为 `Content-Type: text/html; charset=utf-8`，可疑点是源编码声明与现站不一致；还需同响应字节差分，不能直接归为引擎解码错误 |
| 笔趣阁③ | TLS 握手收到远端 `internal_error` | 未进入规则 |
| 心跳书城 | 证书链校验失败 | 未进入规则 |
| 52书库 | 301 到新域后搜索 HTML 200，解析 20 条 | 只证明普通搜索列表；最终 URL 未命中详情模式 |

重跑命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.51ggd.com,www.xsbiquge.net,www.gaohbook.net,www.52shuku.vip' '-PprobeQueries=故事' --offline
```

下轮重点不是更多同类搜索，而是找到确实会从搜索 URL 跳到书籍详情的公开站点，或通过 Legado 原版与 Java 核心对同一响应做差分。记录最终 URL、是否命中模式、解析分支、字段输出和失败阶段；遇到登录/防护页先归环境，不修补原书源，也不抓整本正文。

## 分支可观测性与第二批实站筛查

同日给核心两种回退分别增加 `DIRECT_DETAIL_URL_PATTERN` 和 `DIRECT_DETAIL_EMPTY_LIST` 信息诊断；JVM 探针输出 `SEARCH_BRANCH`，Android Debug 输出同名标记，和真正的 `FIELD_WARNING` 分开。脱网测试断言命中模式、空列表回退及已设置模式但未命中的三种日志状态。这样普通 `OK` 不能再被误读为直达详情通过。

从 128 源集合的 33 条非空模式中选六个不同站点，只搜“斗破苍穹”，不取目录或正文。整个运行**没有出现 `SEARCH_BRANCH`**，故没有实站直达正对照：

| 站点 | 搜索阶段 | 判断 |
| --- | --- | --- |
| 有度中文网 | HTTP 200，普通列表 15 条 | 列表规则通过，未触发详情模式 |
| 飞卢小说 | HTTP 200，普通列表 30 条 | 同上；不涉及付费正文 |
| 恋听网 | HTTP 200，普通列表 17 条 | 同上；不涉及音频下载 |
| 文学迷 | JVM 要求 Android WebView | 当前 JVM 环境未进入列表或详情规则 |
| 三一小说 | HTTP 403 | 访问边界，未进入规则 |
| 书本网 | HTTP 连接超时 | 网络边界，未进入规则 |

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=www.yodu.org,www.juzixs.vip,www.31xs.com,b.faloo.com,ting55.com,www.ibookben.com' '-PprobeQueries=斗破苍穹' --offline
```

这批样本扩展了模式源的阶段证据，却尚未验证直达分支。后续应优先找已知会重定向到详情的搜索请求，避免反复用普通列表站点累加“成功数”。

文学迷另在 Android 模拟器以同词复测：规则按要求启动 WebView，但搜索 URL 所在 `m.juzixs5.com` 返回 HTTP 403，停在 `SEARCH.searchUrl`，仍未进入模式或列表解析。命令：`adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/4dc410d1.json --es sourceFilter www.juzixs.vip --es keyword 斗破苍穹`。调试异常栈当前会带查询 URL，公开分享 logcat 前应脱敏；本文件不保存完整请求 URL 或页面内容。
