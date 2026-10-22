# 混合 JS→JSONPath、Map 视图与登录响应：跨站探针（2026-09-27）

为覆盖与前两批不同的规则机制，本轮从 [AOAOSTAR 的关耳女频集合](https://legado.aoaostar.com/sources/e3e5d620.json)选 11 个不同域名，各用“故事”低频搜索一次。集合此刻按 URL 去重为 86 源，原始 JSON SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。它是仅供诊断的补充来源，不并入主书源目录；不提交第三方完整规则、账号、响应正文或媒体文件。站点状态会变化，HTTP 200 不自动算规则通过。

| 源 | 首轮搜索与边界 | 修复后最深实测 |
| --- | --- | --- |
| 晋江文学 | 301 后落作品库页，0 条，未证实是有效搜索页 | 未继续 |
| 长佩文学 | HTTP 200，`<js>...</js> $.data.list` 被误作 CSS 报错 | JVM/Android 搜索 10、首条详情和 7 条目录通过；章节 URL 带 `webView` 选项，未取正文 |
| 七猫 | HTTP 200、0 条 | 仍为 `ZERO_UNVERIFIED`，未宣称有效无书 |
| 玫瑰小说 | TLS 握手关闭 | 未到规则 |
| 若晨文学 | HTTP 200、标题为搜索页，0 条 | 仍未独立证实站内应有此词 |
| 梦远书城 | HTTP 520 | 未到规则 |
| 52书库 | 旧域 301 到 `.net`，搜索 20 条 | JVM 首条详情 HTTP 200、目录 78 条，尚未读正文，也未做 Android 对照 |
| 豆瓣阅读 | HTTP 200；先卡在 `<js>...</js> $.[*]`，后卡在 `{$.id}` 备用 URL，再出现 Map 伪 URL | JVM/Android 搜索 6、详情、公开目录 50 条；API `list` 独立计数 50，`price>0` 33 条与目录 VIP 33 同数；首 URL 为真实 `/reader/column/...` 加选项，不取付费正文 |
| 听姬 | HTTP 200 空体 | `EMPTY_RESPONSE`，不是有效零命中 |
| 猫耳 FM | HTTP 200；列表规则 `<js>...</js> $.[*]` 报 CSS 错，随后 `String(result)` 无 `id=` | JVM/Android 搜索 40、首条详情及音频目录 6 条；公开 API 首条 `episodes` 亦为 6，不请求媒体流 |
| 喜马拉雅 | HTTP 200、81 字节，原被记 0 条 | 响应含 `needLogin=true`，现于 `SEARCH.searchUrl` 报 `LOGIN_REQUIRED`；不绕过登录 |

## 修复依据与边界

官方 [AnalyzeRule.splitSourceRule/getElements](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 将 `<js>` 和其后的 JSONPath 作为顺序规则，而不是一个 CSS 选择器。Java `RuleEvaluator.many/one` 现先执行 JS，将输出交给后续规则；合成回归覆盖列表、字段和两项 JSONPath。

官方 [AnalyzeByJSonPath.getString](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSonPath.kt) 支持 `{$.字段}` 内嵌取值与 `||` 回退。Java 端补此基本形式；豆瓣阅读的备用详情 URL 因此从 CSS 失败变为搜索命中。该修复也纠正了过去把黑岩 `{$.words}字` 误判为坏字段的结论：同一 128 源集合复测，搜索 20 条、首条 `words=true`，不再有 `ruleSearch.wordCount` 告警。复杂嵌套模板仍未证明完全等价。

豆瓣目录又暴露两个联动问题：`href||$.links.reader` 的 `href` 在 JSON Map 上不能返回整张 Map 的字符串，否则永远不会回退；JS 拼出的 `路径,{URL选项}` 也须只对路径做绝对地址解析并保留选项。合成回归核对章节路径和 `webView` 选项，真实目录由伪 URL 改为可解析链接。曾经的探针把伪 URL 中的 Map 内容直接打印出来，属于不合适的诊断输出；JVM/Android 调试日志现仅展示 URL 的协议、主机、路径与 `[options]`，省略查询和选项值，非法 URL 只显示长度。旧终端输出不进入工程文档或 Git。

猫耳列表项是 JSON Map；其第三方规则用 `String(result).match(/id=(\\d+)/)`。官方 JSONPath 返回 Map 的字符串视图含 `id=...`；本工程原本将 Map 安全转换为普通 JS 对象，`String(result)` 为 `[object Object]`。现仅给安全对象一个不可枚举的 `toString` 视图，不暴露 Java 类/方法；合成测试确认仍不可访问 `getClass`。真实源因此从字段异常前进到公开的 6 条音频目录。尚未验证音频资源地址、播放或分段流。

喜马拉雅返回的 `needLogin=true` 是明确认证前提。`ResponseSignals` 现归为 `LOGIN_REQUIRED`，核心拒绝将其当作空搜索。没有登录凭据或绕过动作。

复测命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=m.jjwxc.net/channel/,webapi.gongzicp.com,api-bc.wtzw.com,api.meiguixsapp.com,m.ruochenwenxue.com,m.mengyuanshucheng.com,www.52shuku.vip,read.douban.com,www.missevan.com,www.ximalaya.com,www.himehear.com' '-PprobeQueries=故事' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=webapi.gongzicp.com,read.douban.com,www.missevan.com' '-PprobeQueries=故事' -PprobeCatalogFirst=true --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.ximalaya.com' '-PprobeQueries=故事' --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/e3e5d620.json --es sourceFilter read.douban.com --es keyword 故事 --ez catalogFirst true
```

Android 另分别以 `sourceFilter=webapi.gongzicp.com`、`sourceFilter=www.missevan.com` 执行相同目录止步探针，结果与 JVM 条数一致。`./gradlew :source-core:test :export:test :app:assembleDebug --offline` 通过。本轮未取付费正文、媒体文件或整书，不能据目录数推断所有章节可读。
