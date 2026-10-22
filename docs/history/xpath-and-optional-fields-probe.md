# XPath 节点链与可选字段兼容探针

2026-09-27，补充样本为 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，本次 JSON SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。仅在内存中使用集合，不并入主书源目录、不提交原始规则或正文。样本词“斗破苍穹”，每站低频顺序请求；黑岩阅读只到目录，**不请求付费章节正文**。

## 规则与实现差异

- 该集合至少 7 个不同站点使用 XPath，多个目录规则为 `//*[@id="list"]//dt[2]/following-sibling::dd/a` 加 `chapterName=text`、`chapterUrl=href`。官方 `AnalyzeByXPath.getElements` 返回节点，后续 `AnalyzeByJSoup` 可读取节点字段；本工程原先把 XPath 命中项立刻降为文本，丢失链接。现通过 jsoup 的 `W3CDom.SourceProperty` 保留元素；XPath 属性和文本节点继续返回字符串。合成 Java 端到端回归核对章节标题、相对地址和多章顺序。
- 黑岩阅读的详情规则 `//pre[@class="note"]/text()` 曾被 `@class` 误切成 CSS 动作。现以规则模式优先识别 XPath，支持大小写 `@XPath:` 和单斜杠路径；合成回归覆盖谓词里的 `@`、属性节点和 `text()`。
- 其目录 URL 规则为 `href<js>…</js>`：先取节点的 href，再由 JS 构建正文 API 地址。官方 `AnalyzeRule.splitSourceRule` 会拆分内嵌 `<js>` 段；本工程已补这一种“字段→JS”串联形式，并用合成章节验证 URL。其他多段/混合规则仍未据此宣称等价。
- 更正：本节初测时把黑岩 `ruleSearch.wordCount = {$.words}字` 误判为无效选择器，并据此报告 `FIELD_WARNING`。官方 `AnalyzeByJSonPath.getString` 实际支持 `{$.字段}` 内嵌取值。现按此语义修复并用同一来源复测，搜索 20 条、首条字数非空、不再有该字段告警；见[后续专项](mixed-js-jsonpath-probe.md)。可选字段逐项容错仍保留给**真正失败**的规则，但这个样本不再作为容错正例。没有改写第三方规则或伪造字数。
- Android 实机路径另遇到带 `DOCTYPE` 的真实 HTML：jsoup `W3CDom` 在 Android Harmony `DocumentType.setUserData` 空指针，JVM 不复现。仅在新解析树中移除 doctype 再转换；不修改源响应或可读 DOM。Android 详情和目录复测因此与 JVM 一致。

## 真实结果与边界

| 站点 | 本轮最深阶段 | 说明 |
| --- | --- | --- |
| 黑岩阅读💰 | JVM、Android 均搜索 20 条，首条详情通过，目录 2203 条、其中 VIP 2156 条；首条 URL 同为 `https://a.heiyan.com/ajax/chapter/content/13443829` | 同一公开目录 HTML 中 `class="isvip name"` 链接计数也是 2156；证明该样本的 VIP 标记数量与页面一致，**不是**章节完整性或可下载证明。后续 JVM 复测字数已非空，Android 字数尚未专门重验。没有请求正文，更未尝试付费或登录。输入词的首条《龙潜苍穹》是近似匹配。 |
| 燃文小说③ | 搜索 HTTP 403 | XPath 目录未触达，不可算规则失败。 |
| 全免小说 | 搜索 HTTP 200，但页面标题为域名、0 条 | 非预期搜索结果；保持未验证，不能把合成 XPath 通过当作该站通过。 |
| 完本小说 | 搜索 HTTP 404 | 未到目录。 |
| 必读小说 | 搜索连接超时 | 未到目录。 |
| 落秋中文、无限小说 | 旧探针分别连接超时、证书过期 | 本轮未重试，仍无真实 XPath 目录证据。 |

JVM 复测命令：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json \
  '-PprobeSource=黑岩阅读💰' -PprobeQueries=斗破苍穹 \
  -PprobeCatalogFirst=true --offline
```

Android Debug 探针同 URL、`sourceFilter=heiyan.com`、`keyword=斗破苍穹`，另设 `catalogFirst=true`；它只执行搜索、详情、目录并记录首章 URL，**不触发正文请求**。JVM/Android 的三个响应均为 HTTP 200。合成 Java 回归与 `:source-core:test :export:test :app:assembleDebug --offline` 已通过。

## 第二站：从公开详情页直接验证目录漂移

另用 [AOAOSTAR 22 源集合](https://legado.aoaostar.com/sources/71e56d4f.json)的“手机小说”书源，JSON SHA-256 `24fe11c400ddc8754703ff0c4d8a249baf024da3a15563de805cc134140959a0`。其搜索在[早前广度探针](supplemental-source-probe.md)返回了非搜索页，因此本轮显式跳过搜索，从站点首页公开链接 `https://www.shoujix.com/shoujixs_101591/` 起跑：详情 HTTP 200、元数据解析成功；目录请求 HTTP 200，**`ruleToc.chapterList` 无匹配**。现页有一个 `id=list` 容器、2 个 `<dt>` 和 92 个 `<dd>`，没有规则要求的 `id=lbks`。这属于第三方规则相对现站结构漂移，不是 XPath 引擎应自动改写的语法问题，也不能把 92 个 `<dd>` 直接宣称为 92 个有效章节。

手动诊断命令：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json \
  '-PprobeSource=手机小说' \
  -PprobeBookUrl=https://www.shoujix.com/shoujixs_101591/ \
  -PprobeCatalogFirst=true --offline
```

`probeBookUrl` 是显式的阶段诊断：只接受 HTTP(S) URL，必须配书源过滤条件，输出 `SEARCH_SKIPPED_DIRECT_BOOK`，不把直接输入的详情页伪装为搜索命中。默认止步目录；只有显式加 `probeCaptureFirst=true` 才会尝试首章正文。上面的 `probeCatalogFirst=true` 也明确锁定目录止步。没有修改第三方原始规则。

下一步应找另一可达且规则未漂移的 XPath 站点，验证复杂 XPath 返回类型和目录分页。本轮不将目录条数或搜索结果数当作整书下载验收。
