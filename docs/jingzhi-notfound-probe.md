# 经致文学：HTTP 200 不存在页（2026-09-27）

目标是扩大站点兼容验证，不抓正文或下载整书。样本来自 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，当日原始 JSON SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`；本轮不并入主书源目录、不提交第三方源 JSON。书源为“经致文学💰”（`www.jingzhi5.com`）。同一集合中的“百里中文💰”首页返回 HTTP 404，未作为规则正例。

搜索“上门龙婿”时，公开搜索页 HTTP 200，解析出 1 条同名结果，详情地址 `/books/609`。但该地址返回 HTTP 200、标题“提示信息”，正文明确写“所访问的页面不存在”；首页也仍有指向该地址的链接。原探针把 HTTP 200 当作 `DETAIL_OK`，再把目录空结果记成 `CATALOG.ruleToc.chapterList` 失败，掩盖了上游页面已失效这一更早的原因。

现在响应诊断以**同时出现**该标题和提示文字为保守条件，标为 `PAGE_NOT_FOUND`；`SourceEngine.detail` 在 `DETAIL.ruleBookInfo.init` 报不可用响应，目录规则不再被执行。合成测试同时覆盖正例、单有标题的反例与详情阶段失败。真实 JVM 复测结果：搜索 1 条；详情 fetch 为 `200 / PAGE_NOT_FOUND`；最终 `CAPTURE_ERROR DETAIL.ruleBookInfo.init`。Android 模拟器安装同版 Debug APK 后也得到 `SEARCH_OK hits=1`、详情 `quality=PAGE_NOT_FOUND`、`ERROR DETAIL.ruleBookInfo.init`，没有 `CATALOG_OK`。JVM 复测命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=经致文学💰' '-PprobeQueries=上门龙婿' -PprobeCatalogFirst=true --offline
```

该源的目录 VIP 规则使用 `result.select('a').hasClass('isvip')`，但本轮未取得有效详情/目录页，**不能据此宣称这两个节点 JS 方法在实站通过或失败**。待站点提供有效目录样本后再做离线语义回归和跨端实测。HTTP 状态、页面内容与规则阶段应分别记录；本例不计作可阅读源或下载成功。
