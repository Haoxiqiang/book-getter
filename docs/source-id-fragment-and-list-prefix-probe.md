# 书源 ID 片段与列表前缀探针

2026-09-27，在 AOAOSTAR 补充集合 `https://legado.aoaostar.com/sources/b778fe6b.json`（SHA-256 `a50c80e81e45a6ad77f6cdb94ab420e74de29691f8924692415a3e9f0e022747`；3907 条记录，2595 个有效唯一 ID）中按完整书源 ID 精确筛选。仅做低频搜索、首条详情和公开目录读取；不取正文、不下载整书。该集合只作兼容诊断，不提交第三方原始 JSON。

## 相对 URL 与带标签的书源 ID

“八一中文” `http://www.zwduxs.com#grid tr!0#芜恙` 的 `searchUrl` 为相对 POST 路径。旧解析器用严格 `URI` 处理书源 ID；片段中的空格使解析失败，最后发出无协议的 `/modules/article/search.php`，报 `SEARCH.searchUrl / URI with undefined scheme`。官方 `NetworkUtils.getAbsoluteURL` 基于 `java.net.URL` 处理相对地址，因此增加受控 URL 回退，并以合成测试断言目标为 `http://example.test/modules/article/search.php`、方法仍为 POST。修复后该真实源已越过 URL 规划，但源站响应在请求阶段 EOF；不能声称列表 JS 或搜索可用。另一条具有 `#grid tr!0` 标签的“笔趣阁③”发出了正确路径，但源站返回 HTTP 403 / `Just a moment...`，也未进入规则执行。

另一个站点“SF轻小说” `https://m.sfacg.com#南笙21.0101` 在相同修正后，关键词“魔王”请求 `/API/HTML5.ashx` 得 HTTP 200、搜索 2 条，首条“失业魔王”详情页 HTTP 200。它提供带非 ASCII 片段 ID 的真实正对照，不代表所有 URL 选项、Cookie 或登录路径已等价。

## 列表前缀与目录脚本

SF 轻小说同一首条详情的 `ruleToc.chapterList` 以 `+@js:` 开头。旧解析器把 `+` 送入 CSS，停在 `CATALOG.ruleToc / SelectorParseException`。官方 `BookList.kt`、`BookChapterList.kt` 在调用规则引擎前去掉 `+`；若以 `-` 开头则把最终列表倒序。现搜索和目录入口共用这一前缀处理，合成测试覆盖 `+` 与 `-`。复测得到 2023 个不重复目录 URL、无 `javascript:` 占位；独立只读抓取同一公开 HTML，`href="/c/[0-9]+/"` 去重计数也为 2023。只验证了目录列表数量与 URL，未验证章节正文、VIP 权限或整书。

以上是两个不同阶段的兼容差异：请求 URL 规划和列表入口控制字符。`ruleSearch.kind` 仍有字段告警，保持未解决；SF 的 2023 条目录不是 2023 本书，也不增加整书验收计数。更广的跨站测试仍应按独立站点、规则机制和最深通过阶段统计。

复测命令：

```sh
./gradlew :source-core:test :app:assembleDebug --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==http://www.zwduxs.com#grid tr!0#芜恙' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==https://m.sfacg.com#南笙21.0101' '-PprobeQueries=魔王' -PprobeCatalogFirst=true --offline
```
