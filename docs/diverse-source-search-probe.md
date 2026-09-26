# 多站点搜索规则兼容探针（2026-09-27）

目的仅是扩大**站点与规则机制**覆盖，寻找首个失败阶段；没有抓章节、导出小说或增加整书计数。补充样本为 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，本次获取 SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。它不并入 [主书源目录](../source-catalog/README.md)，原始 JSON 不提交。环境：本机 JVM、Java `HttpClient`、搜索词“斗破苍穹”，每站一次，顺序执行且站间至少 1.5 秒；结果是一次网络快照，不代表站点恒定状态。

探针接受逗号分隔的限定书源名或 URL 片段，例如：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json \
  '-PprobeSource=全本小说①,文学迷,御书房,69书吧①,飞卢小说💰,鬼吹灯' \
  -PprobeQueries=斗破苍穹 --offline
```

第二批将 `probeSource` 换为 `书香之家,猴子阅读,番薯小说,鸟书网,无限小说,铅笔小说,三一小说,溜达小说`。列表匹配有 Java 单测；空过滤仍表示全量，只有逗号/空格不会意外匹配全量。不要无节制对 128 源全量并发请求。

| 站点 | 搜索响应/阶段 | 解释与下一步 |
| --- | --- | --- |
| 飞卢小说💰 | HTTP 200，页面有 `TwoBox02_02` 列表；修复前 0，修复后 30 条 | **解析器缺陷已确认并修复**。`class.TwoBox02_03@tag.a.0@href` 等 Legado 位置索引曾被误当 CSS 类名。只验证公开搜索结果，不进入付费内容。 |
| 全本小说① | HTTP 403 | 访问被拒；无从判断搜索规则。 |
| 69书吧① | 源 URL 从 `69shu.com` 跳至 `69shuba.com`，探针 HTTP 200、0 条 | 当前 200 页面标题“69书吧”且不含旧规则列表；另一次直接请求落到 Cloudflare challenge。源站迁移/防护导致结果不可证，不能称“有效零命中”。 |
| 文学迷 | JVM 请求要求 Android WebView | JVM 不支持，不是规则零命中；应在 Android 端单独验证。 |
| 鬼吹灯 | EOF | 连接失败，未到解析阶段。 |
| 御书房 | HTTP 202、0 条 | 非正常搜索响应，可能是站点策略；未证明规则兼容。 |
| 猴子阅读 | HTTP 200、标题“Redirecting”、0 条 | 站点返回跳转提示页；当前质量分类为 `UNCLASSIFIED`，应补假 200/跳转页识别，零结果无效。 |
| 书香之家、番薯小说 | TLS 握手被远端关闭 | 网络/站点层失败。 |
| 鸟书网、溜达小说 | EOF | 网络/站点层失败。 |
| 铅笔小说、三一小说 | HTTP 403 | 访问被拒；无从判断规则。 |
| 无限小说 | 证书过期（2025-05-22） | 不关闭 TLS 校验；不能以此判断规则。 |

## 确认的规则差异

官方 [AnalyzeByJSoup.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt) 的 `ElementsSingle` 把 `tag.a.0` 解释为第一个匹配节点、`tag.span.-1` 为最后一个、`tag.tr!0` 为排除第一个；这与普通 CSS `.0` 完全不同。`RuleEvaluator.select` 现支持这些基础后缀，合成 HTML 回归同时验证索引、排除和绝对 URL。飞卢同一公开搜索从 0 变为 30 条，说明真实规则链路可用；未验证详情/目录/正文，也未据此宣称该源全部兼容。

未解决的广度问题：`[]` 多索引/区间、复杂组合索引仍需按官方实现补测；非 200 的 HTTP 202 以及 200 跳转/停放页需要更明确的响应分类；Android WebView 源要在设备上验证。后续优先选择**可正常返回且使用不同规则特性**的站点做搜索、详情、目录小步验证，不以同站多本下载替代跨站兼容覆盖。
