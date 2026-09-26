# DOM 节点 `select/hasClass` 与 VIP 目录：经致文学（2026-09-27）

目的：验证真实目录规则中的 JS 节点方法，而非下载作品。样本来自 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，原始集合 SHA-256 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`，只在探针内加载，不并入主书源目录或提交完整第三方规则。查询词“故事”；经致文学首条为公开搜索结果中的《山里的故事》。

首轮 JVM 搜索 2 条，首条详情和目录页面均 HTTP 200，但目录第一项的 `ruleToc.isVip=@js:result.select('a').hasClass('isvip')` 报 `Cannot find function select`。这是受控 JS 视图缺方法，不是站点不可达；先前用“上门龙婿”命中的详情页[确实不存在](jingzhi-notfound-probe.md)，也不能外推成该源所有详情都失效。

官方目录处理将目录项作为 DOM 节点交给 `AnalyzeRule`，`isVip` 的非假值设为 VIP；该规则依赖 jsoup `Element.select` 返回的 `Elements.hasClass`。Java `RhinoRuntime` 现仅暴露白名单视图：节点新增 `select/hasClass`，选中集合提供 `hasClass/select/first/attr/text/size` 和只读 `length`。没有把真实 Java `Element/Elements` 或反射对象交给规则脚本。合成回归用普通与 `isvip` 两个 `<a>` 断言真假、`first().attr('href')` 和不可访问 `getClass`。

修复后，同一书源、同一词在 JVM/Android 均为搜索 2、首条详情通过、目录 12 条且 VIP 9 条。独立从公开目录 HTML 统计，`/chapter/read/1850/...` 的不同链接为 12 个，含 `class=isvip` 的 `<a>` 为 9 个，与两端结果一致。这里只验证公开目录标记，不读取 VIP 正文，不判断付费权限或内容完整性。另一条同样使用 `result.select` 的百里中文，当前 `/search` HTTP 404，未能作为第二个真实正对照。

复测：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=www.jingzhi5.com,www.bailizw.com' '-PprobeQueries=故事' -PprobeCatalogFirst=true --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/4dc410d1.json --es sourceFilter www.jingzhi5.com --es keyword 故事 --ez catalogFirst true
curl -fsSL --max-time 20 http://www.jingzhi5.com/books/catalog/1850 | rg -o 'href="/chapter/read/1850/[0-9]+"' | sort -u | wc -l
curl -fsSL --max-time 20 http://www.jingzhi5.com/books/catalog/1850 | rg -o '<a[^>]*class="[^"]*isvip[^"]*"[^>]*>' | wc -l
```

`./gradlew :source-core:test :export:test :app:assembleDebug --offline` 通过。真实站点与集合可变，计数只代表本次快照和时间；未取章节正文或整书。
