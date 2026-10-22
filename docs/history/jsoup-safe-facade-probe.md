# JS 规则中的 Jsoup 安全视图探针

2026-09-27，继续使用 AOAOSTAR 补充集合 `https://legado.aoaostar.com/sources/b778fe6b.json`（SHA-256 `a50c80e81e45a6ad77f6cdb94ab420e74de29691f8924692415a3e9f0e022747`，3907 条记录、2595 个有效唯一 ID）。该集合中粗筛有 65 条记录在搜索、目录或正文规则中引用 `org.jsoup.Jsoup`；这是静态出现次数，不等于 65 个独立可用站点或规则执行覆盖。

SF 轻小说 `https://m.sfacg.com#南笙21.0101` 的 `ruleSearch.kind` 先把搜索 JSON 中的书 ID 拼为公开详情 URL，再用 `java.ajax` 获取 HTML，执行 `importClass(org.jsoup.Jsoup); Jsoup.parse(...).select(...).text()`，并经 `java.put` 把最新章传给后续字段。修复前搜索仍有 2 条，但 `kind` 报 `FIELD_WARNING`，分类和最新章均空；旧安全 Rhino 环境没有 `org.jsoup.Jsoup`。官方 `AnalyzeRule.evalJS` 会在脚本上下文执行这些规则，而本工程仍须保持受控宿主边界。

现在仅为 `org.jsoup.Jsoup.parse(String)` 提供白名单视图，返回既有的安全 DOM 节点接口；`importClass` 只接受这个白名单对象。未开放真实 Java 类、反射、文件或任意包。脱网回归验证解析/选择/字段传递，并断言 `getClass` 不可见、任意对象不能被导入。这是针对观察到的规则子集，不等价于完整 JavaImporter 或 Jsoup API。

同源关键词“魔王”复测：JVM 与 Android 模拟器均请求搜索 API HTTP 200，搜索 2 条；每条额外请求的 `book.sfacg.com/Novel/<ID>/` 最终 HTTP 200，分类和最新章均非空，原 `kind` 告警消失。首条详情通过，目录仍为 2023 个不同章节 URL；此前已与公开 HTML 链接去重数对照。没有读取章节正文或下载整书。

交叉站点低频探针：啃书小说网停在搜索请求 TLS 握手；木兰花漫搜索为 HTTP 403 挑战页；就去看网搜索要求 Android WebView，JVM 不进入规则。三者都不能作为安全 Jsoup 视图的真实正对照，后续还需另一条可达源。木兰花漫的 `java.log` 与就去看网的 `getStringList` 当时未触达；两者后续各补了基础宿主子集，见[调试宿主](js-log-host-probe.md)及[列表宿主](js-getstringlist-probe.md)，仍没有这两站的真实执行正对照。

复测：

```sh
./gradlew :source-core:test :app:assembleDebug --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==https://m.sfacg.com#南笙21.0101' '-PprobeQueries=魔王' -PprobeCatalogFirst=true --offline
```
