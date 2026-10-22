# XIU2 补充集合：规则广度与人工验证边界（2026-09-27）

为减少反复抽到旧域名，从 [AOAOSTAR 书源目录](https://github.com/aoaostar/legado/blob/release/index.html)另选其标注较新更新时间的 [XIU2 精品集合](https://legado.aoaostar.com/sources/71e56d4f.json) 作**诊断样本**，不并入主书源目录。此刻下载得到 22 条去重源，原始 JSON SHA-256 为 `24fe11c400ddc8754703ff0c4d8a249baf024da3a15563de805cc134140959a0`。上游页面的“更新时间”仅说明集合元数据，不证明每站仍可用。第三方完整源 JSON、响应正文、Cookie 均不提交。

按域名过滤 13 条结构不同的源，各仅搜索一次“斗破苍穹”；JVM 低频探针每请求间隔 1.5 秒。阶段结果如下：

| 书源 | 首个可观察结果 | 归因边界 |
| --- | --- | --- |
| 酷我小说 | JSON API 200，`code=200`、`data=[]`、`paging.count=0` | 此词有效零结果；不证明目录/正文规则 |
| 天天看小说 | HTTP 403 | 访问边界 |
| 独步小说网 | HTTP 200 的“提示信息”页写明“没找到此书” | 此词无匹配；没有真实列表样本 |
| 来看文学 | 规则的异常分支调用 `java.longToast`；原宿主报该方法缺失 | 该源涉及 Cookie、表单和跳转；本次未得到可用搜索响应。仅补提示接口，不声称搜索通过 |
| 大文学无错小说网、和图书、顶点小说 ddxs、八一中文 | 上游规则在 Cloudflare `Just a moment...` 页面调用 `java.longToast` 再调用 `java.startBrowserAwait` | 真实前提是人工浏览器验证；不绕过。原错误误导为 `longToast` 不存在，修复后两站复测明确报 `Interactive browser verification is unavailable in this runtime` |
| 思路客、武林中文网 | TLS 握手关闭 | 未到规则 |
| 艾途小说 | HTTP 401 | 未到规则 |
| 就爱文学 | EOF | 未到规则 |
| 阅友小说（`m.suixkan.com`） | 搜索 5 条，站内确有“灵破苍穹” | 继续下文阶段核对；与其他同名但不同域的“阅友小说”分开 |

官方 [JsExtensions.kt](../../../legado/app/src/main/java/io/legado/app/help/JsExtensions.kt) 中 `longToast` 是 UI 提示；`startBrowserAwait` 会打开内置浏览器并等待人工验证。Java 核心现为 `toast/longToast` 提供可注入的通知回调（合成测试核对两条消息），但默认调用者未绑定 UI；`startBrowserAwait` 明确报当前运行时不可用，而不是假装通过或自动闯过防护。JVM 实站复测大文学无错小说网、和图书都从原来的 `TypeError: longToast` 前进到该能力边界，仍**不能算书源可用**。未来 Android 应提供用户可见的授权验证流程，并独立验收 Cookie/返回页；本轮没有执行。

阅友小说用站内已返回的“灵破苍穹”复测：JVM 与 Android 均搜索 5、详情 HTTP 200、目录 **1649 条**、首章 HTTP 200，提取原始 3751 字符/可见 3033 字符；详情的 `ruleBookInfo.kind` 仍有可选字段告警，不阻断目录。公开目录页 `/c/229093.html` 的 `/r/229093/*.html` 链接独立计数恰为 1649 个，且没有 `javascript:` 占位；只验证一章，不下载整书，也不由此证明其余 1648 章质量。Android Debug 探针日志为 `BookGetterProbe`，未保存正文。

可重跑命令（真实站点状态会变，勿高频重复）：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json '-PprobeSource=appi.kuwo.cn,cn.ttkan.co,dbxsd.com,m.laikan9.com,wcxsw.org,hetushu.com,siluke.com,ddxs.com,aitbooks.com,m.suixkan.com,92xs.info,81zw2.com,50zw.so' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json '-PprobeSource=m.suixkan.com' '-PprobeQueries=灵破苍穹' -PprobeCaptureFirst=true --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json '-PprobeSource=wcxsw.org,hetushu.com' '-PprobeQueries=斗破苍穹' --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/71e56d4f.json --es sourceFilter m.suixkan.com --es keyword 灵破苍穹 --ez captureFirst true
```

此集合剩余样本包括 69书吧、速读谷、得奇、快书、手机小说等，部分已有[既往独立记录](live-source-tests.md)。后续仍按“独立域名 × 机制 × 最深阶段”挑样，而非以同名源或下载本数充广度。
