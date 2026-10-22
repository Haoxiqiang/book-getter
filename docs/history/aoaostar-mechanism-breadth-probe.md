# 关耳女频集合：异构机制广度探针（2026-09-27）

目的不是累积下载量，而是尝试此前覆盖不足的 GBK POST、XPath 搜索、CSS 位置伪类、论坛/原创平台、文件和图片源。只用“故事”各搜索一次；得到真实结果才进一步取首条详情与目录，不请求正文或文件。补充集合为 [AOAOSTAR 关耳女频 JSON](https://legado.aoaostar.com/sources/e3e5d620.json)，按 `bookSourceUrl` 去重 86 源，SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。这些源只用于诊断，不并入主书源目录；站点状态可能变化。

| 书源 / 目标机制 | JVM 首个结果 | 归因边界 |
| --- | --- | --- |
| 饭饭论坛 / GBK POST + DOM JS | 搜索 302 到无关域名 `survey-smiles.com`，未跟随 | 入口不可用，未进入规则；不能验证 GBK 或 DOM JS |
| 百合小说 / CSS `:lt(5)` | 连接超时 | 未到规则 |
| 2K 小说 / XPath | 搜索 301 到 `www.2kzw.la`，未跟随 | 原书源入口迁移，未到 XPath |
| Lofter / JSON API | HTTP 200、86 字节、0 条 | 未独立证明此词应有结果；`ZERO_UNVERIFIED`，不算规则通过 |
| POPO / POST 表单 + HTML | 搜索 10、首条详情 HTTP 200；目录 0 | JVM/Android 同样停在 `CATALOG.ruleToc.chapterList`；见下文的源规则漂移 |
| 镜文学 / JSON API | 原域 301 到 `www` 后搜索端点 404 | 上游 API 地址失效，未到 JSONPath |
| 百合会（小说） / HTML + 登录 | 搜索 302 到 `/user/login`，最终 HTTP 200、标题“登录 - 百合会” | 原来伪装成 0 条；现 JVM/Android 均报 `SEARCH.searchUrl / LOGIN_REQUIRED`，未尝试登录 |
| 九九藏书 / HTML | HTTP 403，挑战页标题 | 访问限制，未到规则 |
| 当书网 / XPath + 文件 | TLS 证书链验证失败 | 未到规则；不关闭证书验证 |
| 微博博主 / 图片 JSON | HTTP 432、空响应 | 访问限制，未到 JSONPath/图片处理 |

POPO 原规则的 `ruleBookInfo.tocUrl` 使用 `text.章回列表@href`，因此实际返回的目录 URL 仍是 `/books/446`；当前详情页的链接文字是“章回”，指向 `/books/446/articles`。详情页没有规则要求的 `class.clist`，而公开的 `/books/446/articles` 页有 `class="clist nologin"`。这说明该条书源的详情→目录链接规则与现站点不一致，不能为了让此站过关而让通用解析器模糊匹配“章回”。本轮也未判断该书章节是否可读。

百合会暴露另一类误判：HTTP 客户端跟随搜索到登录页的重定向后，最终 HTTP 200 仍不是有效搜索响应。响应质量层现仅在最终路径以 `/login`、`/signin` 或 `/sign-in` 结束，且 HTML 标题明确含登录含义时标 `LOGIN_REQUIRED`；单独匹配路径或标题均不够。合成回归同时验证了正例和路径/标题不同时的反例，搜索引擎拒绝将登录页算为空列表。HTTP 错误消息还改用去查询值的 URL 摘要，避免调试输出搜索词或令牌。此识别不等于实现登录流程，也不能推断其他登录页形式已覆盖。

复测：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.2kxs.info,m.baihexs.com,mirrorfiction.com,www.yamibo.com/site/novel,www.99csw.com,www.popo.tw,www.downbook.org,bbs.fanfanu.com,newsmiss.lofter.com,m.weibo.cn' '-PprobeQueries=故事' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.yamibo.com/site/novel,www.popo.tw' '-PprobeQueries=故事' -PprobeCatalogFirst=true --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/e3e5d620.json --es sourceFilter www.yamibo.com/site/novel --es keyword 故事
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/e3e5d620.json --es sourceFilter www.popo.tw --es keyword 故事 --ez catalogFirst true
```

`./gradlew :source-core:test :export:test :app:assembleDebug --offline` 通过；Android 模拟器复测百合会为最终 `/user/login` HTTP 200 + `LOGIN_REQUIRED`，POPO 为搜索 10、详情通过、目录 0，与 JVM 同阶段。没有登录、规避防护、抓取正文或下载整书。
