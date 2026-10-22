# `java.getStringList` 的 JSONPath 项目边界

2026-09-27。官方 `AnalyzeByJSonPath.getStringList` 对 JSONPath 返回的数组逐项转成字符串；`AnalyzeRule.getStringList` 随后可逐项处理 URL。当前 Java 宿主原先用标量 `one()` 把值合并成换行文本，再以换行拆开。因此数组元素本身含换行时，一个值会被错误拆成两个；URL 模式还可能产生虚假的地址。

现在仅对无后置 `##` 替换的直接 JSONPath 规则，从规则引擎的列表结果逐项构造宿主字符串列表。离线夹具断言 `$.items[*].text` 中的 `"first\\nline"` 保持为第一项，整个结果仍为两项。`./gradlew :source-core:test --offline --rerun-tasks` 通过。

这是官方语义与合成输入的差分回归；未找到本地快照中实际执行 `java.getStringList` 加 JSONPath 的真实站点规则，不能计作真实规则语法命中，更不是网络正对照。组合替换、JS 返回列表、URL 行为仍按各自边界验证。
