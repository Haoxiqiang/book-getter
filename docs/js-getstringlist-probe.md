# `java.getStringList` 的 DOM 列表与 URL 参数

2026-09-27。上游 `legadoSkill` 快照中的“爱去小说”下载链接规则使用 `java.getStringList("@text.下载地址@href", html, true)`；该规则属于文件下载字段，本工程尚未验证完整文件源流程。这里只用它作为实际规则语法样本，不收录第三方完整书源或下载内容。

官方 `AnalyzeRule.getStringList` 先逐项取得 DOM 结果，再在 `isUrl=true` 时相对 `redirectUrl` 补全地址、过滤空值并去重；`AnalyzeByJSoup` 的 `text.片段` 按直属文本选择元素。旧 Java 宿主调用标量 `one()`，把多项拼为换行字符串再拆分，同时忽略第三个 URL 参数；`text.片段` 也未在默认选择器中实现。这样会把多条相对下载地址原样返回，或完全匹配不到。

现有受控子集对默认 DOM 规则逐项提取，保留普通字符串列表顺序；URL 列表按当前规则基址补全并去重。离线夹具用四个同名链接覆盖两条不同 URL、重复 URL 和空 `href`，断言 URL 模式得到两条绝对地址，非 URL 模式仍保留原相对地址。`./gradlew :source-core:test --offline --tests org.bookgetter.core.SourceEngineTest` 通过。

这不证明该下载源可访问、文件字段已进入应用流程，或复杂组合、替换和 JS 返回列表与原版完全一致；这些仍须按实际阶段差分。
