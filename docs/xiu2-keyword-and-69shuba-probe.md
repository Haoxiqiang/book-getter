# 关键词适配与 69 书吧源规则漂移（2026-09-27）

继续使用 [XIU2 精品 22 源集合](https://legado.aoaostar.com/sources/71e56d4f.json)，原始 JSON SHA-256 `24fe11c400ddc8754703ff0c4d8a249baf024da3a15563de805cc134140959a0`；只在内存导入，不提交第三方源 JSON、Cookie 或正文。本轮目的仍是分辨规则兼容、站点状态与**测试词不适配**，不是整书下载。

## 酷我小说：有效零结果并不代表书源失效

首次用“斗破苍穹”搜索，公开 API HTTP 200，响应结构为 `code=200, data=[], paging.count=0`；这是该词的有效空搜索，却没有触发详情、目录或正文规则。改用宽关键词“修仙”后，JVM 与 Android 均得到搜索 **30 条**、首条《修仙传》详情 HTTP 200、目录 **2052 条**、首章 HTTP 200；单章规则提取原始 2650 字符、可见 2616 字符，无资源链接。独立请求公开目录 API `.../chapters?paging=0`，`data` 数组长度为 **2052**，与解析目录同数，且目录无 `javascript:` 占位。

这提供了另一条 JSON API 的跨端正对照，但只检验一章：不证明其他 2051 章、分页、VIP 或整书质量，不新增下载计数。宽词可能匹配站内其他作品，所以必须以实际首条标题和 API 结构核对，而不能把“30 命中”直接理解为目标名著存在。

## 69 书吧：两条源是不同的失败

| 书源 | 搜索阶段证据 | 判断 |
| --- | --- | --- |
| `69书吧.com`（`www.69shuba.com`） | `searchUrl` 从 `<js>/modules/article/search.php,{'charset':'gbk',...};result='';result;</js>` 开始；JVM 发请求前报 Rhino `invalid flag 'a' after regular expression` | `<js>` 内的相对 URL 未写成字符串或合法 JS 表达式。官方 [AnalyzeUrl.analyzeJs](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) 先执行 `<js>`，再处理 URL 选项/模板；不能把无效 JS 悄悄改写成普通 URL 并宣称规则兼容。这是该第三方书源本身的语法问题，尚无搜索结果 |
| `69书吧`（`69shuba.cx`） | 搜索 POST 到 `.cx`，301 跳 `www.69shuba.com`，最终变 GET，HTTP 200 是搜索表单样式内容、0 条 | 域名/请求方法漂移，标 `RESPONSE_SUSPECT`；不把零条当成有效无书，也不擅自跨域重发 POST |

两条书源都未进入详情/目录。可复测：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json '-PprobeSource=69shuba.com,69shuba.cx,appi.kuwo.cn' '-PprobeQueries=修仙' -PprobeCatalogFirst=true --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json '-PprobeSource=appi.kuwo.cn' '-PprobeQueries=修仙' -PprobeCaptureFirst=true --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/71e56d4f.json --es sourceFilter appi.kuwo.cn --es keyword 修仙 --ez captureFirst true
```

本轮结论对测试设计的影响：每站至少要区分“无结果但 API/页面明确正常”与“测试词未覆盖该站题材”；若要向后验证目录，应选**站内确有结果的词**。同时保留原始书源规则与跳转证据，不能为了增加通过数自动修补上游第三方源。
