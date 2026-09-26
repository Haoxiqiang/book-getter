# 全量聚合源筛选与同名域名差分

2026-09-27。目标是从不同站点寻找兼容问题，而不是把全量源或搜索结果数量当作可用率。[AOAOSTAR 全量书源目录](https://github.com/aoaostar/legado/blob/release/index.html)提供 [3907 条 JSON 直链](https://legado.aoaostar.com/sources/b778fe6b.json)；本次响应 SHA-256 `a50c80e81e45a6ad77f6cdb94ab420e74de29691f8924692415a3e9f0e022747`。本地只在内存导入和诊断，不并入主书源管理快照，不提交第三方原始 JSON 或正文。

静态审计：3907 个记录中，`bookSourceUrl` 有效且完全相异的只有 2595 个，另有 1 个缺 ID；现有导入器探针也显示 `uniqueSources=2595`。按源内 `lastUpdateTime` 统计，仅 5 个记录晚于 2025-01-01，其中 2 个时间戳远在 2030 年以后。因此**聚合页近期同步不等于每条书源近期维护**，源内更新时间也需查异常值；不能因此对 3907 站批量发请求。选择规则：先按独立域名、机制、较可信维护时间和前次最深阶段筛选，再低频逐源验证。

本次选择同名的“速读谷”作反例：集合中至少有 `https://www.sudugu.co`（搜索 POST）与 `https://www.sudugu.com`（另一搜索 URL）两条。旧 JVM 探针把 `-PprobeSource=www.sudugu.co` 当子串，**同时匹配 `.co` 与 `.com`**，连续输出一条可疑零结果和一条证书错误，容易误判为同一源先零结果又失败。现在过滤值以 `=` 开头时按完整 `bookSourceUrl` 精确匹配，JVM 有双域单测，Android Debug 探针同样支持；普通逗号分隔子串模式仍保留。

精确筛选后每条只搜一次“故事”，不取详情或正文：

| 书源 ID | JVM 结果 | Android 对照/边界 |
| --- | --- | --- |
| `https://www.sudugu.co` | 原请求 POST；301 到 `.cc` 后最终请求为 GET，HTTP 200 仅返回 3096 字符搜索表单，列表 0，触发 `DIRECT_DETAIL_EMPTY_LIST` 尝试但没有详情命中，判 `RESPONSE_SUSPECT` | 最终主机 `.cc`、HTTP 200、同样 3096 字符表单、0 命中；Android 旧日志的 `method=POST` 只是**原请求方法**，不证明跳转后仍为 POST |
| `https://www.sudugu.com` | 搜索请求证书链校验失败，未进入规则 | 本轮未作 Android 对照，不把 `.co` 的结果外推 |

Android Debug 日志现改用 `requestMethod`，最终 URL 与原请求不同时附 `redirectFinalMethod=UNOBSERVED`，避免把未观测的逐跳方法当成事实。JVM 的逐跳记录仍是这条 POST→GET 结论的依据；这里不关闭证书校验、不猜测重写书源 POST 地址。

新日志版本通过 Debug 构建，重装模拟器后对 `.co` 的追加低频复测在搜索请求发生读取超时，未产生新的 `FETCH` 行；上表 Android HTTP 200 是修改日志字段前的那次观测。因此**新日志格式的实站输出尚未核实**，不能把编译通过说成已在重定向时看到 `redirectFinalMethod=UNOBSERVED`。

复现：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==https://www.sudugu.co' '-PprobeQueries=故事' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==https://www.sudugu.com' '-PprobeQueries=故事' --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/b778fe6b.json --es sourceFilter '=https://www.sudugu.co' --es keyword 故事
```

这不是发现一个可替代旧源的新可用站点；它确认了聚合源筛选、同名域名隔离及 POST 重定向诊断的边界。下一轮继续优先选择能进入规则阶段、且测试机制与现有正对照不同的独立站点。
