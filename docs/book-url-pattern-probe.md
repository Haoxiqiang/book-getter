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
