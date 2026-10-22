# 列表规则的“选择器 → JS → 选择器”兼容探针

2026-09-27，以 AOAOSTAR 补充集合 `https://legado.aoaostar.com/sources/b778fe6b.json`（SHA-256 `a50c80e81e45a6ad77f6cdb94ab420e74de29691f8924692415a3e9f0e022747`，3907 条记录、2595 个有效唯一 ID）中去重后首条 ID 为 `https://www.wenxue88.com/` 的“文学吧”为样本。仅做低频搜索；未抓目录、正文或下载。该集合不是主书源目录，也不提交原始 JSON。

该源 `ruleSearch.bookList` 先选择 `class.zw_txt`，再运行 `<js>…</js>`，最后选 `a`；脚本中还调用 `java.getElements(...).toArray()`。官方 `AnalyzeRule.splitSourceRule` 用 `JS_PATTERN` 将默认规则与 `<js>` 分段串行执行，而不是把整段当 CSS。

| 阶段 | 观测 |
| --- | --- |
| 修复前 | GET 源站和 `/zz/index.html` 均 HTTP 200；列表规则被整体送入 CSS，`SelectorParseException` 或空列表回退，未真正运行脚本。 |
| 第一处修复后 | 选择器/JS 链执行；停在 `SEARCH.ruleSearch.bookList`，`java.getElements(...).toArray()` 报 `Cannot find function toArray`。 |
| 第二处修复后 | 同一源、关键词“故事”：探针 `OK`，13 条候选，首个标题“笨故事集”。只说明列表机制可执行；未独立核对 13 条的相关性，也不证明该源详情、目录或正文可用。 |

本地合成测试 `SourceEngineTest.searchListSupportsSelectorJsSelectorChain` 用两个 `<li>` 检查节点集合经 JS `toArray()` 后仍能继续选择链接；`jsDomHostPreservesSafeNodeMethodsAndReturnedNodes` 检查宿主 `java.getElements(...).toArray()`。后者仍只暴露受控 Rhino 视图，不开放任意 Java 类。后续应抽不同站点、不同混合顺序（如 JSONPath→JS→CSS、XPath→JS→JSONPath）分别验证，避免把这一条源的通过扩大为规则全兼容。

复测命令：

```sh
./gradlew :source-core:test --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/b778fe6b.json '-PprobeSource==https://www.wenxue88.com/' '-PprobeQueries=故事' --offline
```
