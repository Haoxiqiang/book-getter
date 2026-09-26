# 字符串型请求头与逐书变量：磨铁、宜搜跨站探针（2026-09-27）

样本仍来自 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，本轮原始 JSON SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。只在内存中导入上游书源；不提交源 JSON、Cookie 或正文。每站仅搜一次“斗破苍穹”，只对可达源继续到目录；没有抓付费正文或整书。

## 24 站搜索筛查

第一组 12 站：无错小说①/②、燃文小说①/②/④、顶点小说①、全本小说②/③/④、笔趣阁④/⑤/⑦。其中 7 站搜索直接 HTTP 403；燃文①、顶点①、全本③先跨域 301 再返回 403；全本②、笔趣阁④ TLS 握手关闭。两条笔趣阁旧接口也是 403。这组多为旧域的 `/api/search` 或 `/modules/article/search.php` 访问边界，没有规则成功/失败证据，不将它们当作 12 个兼容正例，也不绕过访问控制。

第二组 12 站：八一中文①、笔趣阁⑧、米读小说、起点中文网💰、磨铁中文💰、酷匠阅读💰、铁血读书💰、宜搜小说💰、有声小说①/②、粉丝漫画、好看漫画。搜索阶段分流如下：

| 结果 | 书源 | 边界 |
| --- | --- | --- |
| EOF/TLS | 八一中文①、铁血读书、粉丝漫画 | 未到规则 |
| 403/404/跨域防护 | 米读 403；笔趣阁⑧ 404；好看漫画 301 到另一域后 403 challenge | 不当成有效零结果 |
| 非正常响应 | 起点 HTTP 202；酷匠 HTTP 200 空体；有声小说① HTTP 200 业务错误；有声小说② HTTP 200 返回“APP 大全”页、0 命中 | 各按响应质量/阶段记录，均未证明规则可用 |
| 可达 API | 磨铁修复请求规划后返回 JSON 空列表；宜搜搜索返回 20 条 | 下文继续诊断 |

搜索筛查及专项复测命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=无错小说①,无错小说②,燃文小说①,燃文小说②,燃文小说④,顶点小说①,全本小说②,全本小说③,全本小说④,笔趣阁④,笔趣阁⑤,笔趣阁⑦' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=起点中文网💰,磨铁中文💰,酷匠阅读💰,铁血读书💰,宜搜小说💰,有声小说①,有声小说②,粉丝漫画,好看漫画,八一中文①,笔趣阁⑧,米读小说' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=磨铁中文💰' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=宜搜小说💰' '-PprobeQueries=斗破苍穹' -PprobeCatalogFirst=true --offline
```

## 磨铁：`headers` 可为 JSON 对象字符串

其 `searchUrl` 的 URL 选项使用 `"headers": "{os:'pc'}"`，即字段值是字符串，不是 JSON 对象。旧 `RequestPlanner` 强制 `getAsJsonObject("headers")`，在 `SEARCH.searchUrl` 抛 `JsonPrimitive → JsonObject` 类转换异常，请求未发出。官方 [AnalyzeUrl.UrlOption](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) 的 `getHeaderMap()` 同时接受 Map 和 String；字符串再按宽松 JSON 对象解析，失败则不加该请求头。Java 端现按此语义解析，合成回归断言 URL、GET 方法和 `os: pc` 请求头。JVM 实站从请求规划异常前进到 GET 302→200；Android 也得到 HTTP 200。返回 JSON 的 `bookList=[]`、`total=0`，所以此词确实无匹配；**没有详情/目录验证**。

## 宜搜：搜索结果变量随书传递，JS 引号内模板先展开

修复前，同源同词搜索 20 条、首条详情 API HTTP 200，但目录只输出 1 条，其 URL 含原样 `@get:{gid}` 与 `__bgGet("nid")`。公开目录 API 的 `chapters` 数组却有 **839 项**，明显不是站点只有一章：

- `ruleSearch.name` 的 `@put:{gid:enid}` 要写入当前搜索项变量，并随该 `BookHit` 进入详情/目录。官方 [BookList.kt](../../app/src/main/java/io/legado/app/model/webBook/BookList.kt) 每个搜索结果新建 `SearchBook` 并设为 `ruleData`；[AnalyzeRule.put/get](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 优先访问章节/书籍/当前规则数据，再回退书源。原工程把搜索中的 `@put` 写到书源共享域，既会被下一条覆盖，也不会进入按书 URL 划分的目录作用域。
- `ruleToc.chapterUrl` 是 `@js:'...@get:{gid}&nid={{$.nid}}&st={{$.sort}}...'`。官方 `SourceRule.makeUpRule` 在执行 JS 前处理 `@get` 与 `{{...}}`；原工程把 JSONPath 替成 JS 函数名后留在单引号字符串中，最终各章 URL 相同而被去重到 1 条。

Java 端现为每个搜索项建立独立变量快照，存入 `BookHit.variables` 并传入书籍作用域；JS 单/双引号字符串里的 JSONPath 字段先取当前条目值并转义，模板字符串与非引号表达式仍使用受控延迟求值。合成回归用两本书、两章分别断言不同 `gid` 与 `nid/sort`，并覆盖 Map 直接字段 `enid`（官方 `NativeObject` 键读取）。

真实复测：JVM 与 Android 都是搜索 20 条、首条详情 HTTP 200、目录 **839 条**、VIP **776 条**。独立对公开 API 结构计数为 `chapters=839`、`charge=1` 为 776、`charge=0` 为 63；目录首条 URL 已包含实际 `gid/nid/st`，不再有 `@get`/`__bgGet` 字面量。此前的 1 条是本地规则处理缺陷，现已修正。目录链接含 `webView=true` 和 `autoBuy=1`；本轮**不请求正文、不执行购买动作**，不能据目录匹配推断付费章节可读或全书可下载。

回归还重跑了听小说与有度中文网的搜索→目录路径，分别保持 1658 条、107 条（有度其中 1 条 `javascript:` 占位）；主快照 `20260926-170836-f658eae9` 的 rezero `RE0` 搜索 1、目录 910 条（23 卷、887 普通章）也保持。主快照命令为 `./gradlew :source-core:liveSourceProbe '-PprobeSource=rezero' '-PprobeQueries=RE0' -PprobeCatalogFirst=true --offline`。这只约束已知路径，没有证明所有 `@put/@get` 组合、变量持久化或并发跨源执行完全等同官方。
