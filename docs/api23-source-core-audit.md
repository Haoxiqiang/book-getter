# `source-core` 的 Android API 23 标准库调用审查

2026-09-27。对照本机 Android 23/36 `android.jar`，并检查 Debug APK 中 D8 生成的 DEX 调用。API 23 的 `String` 无 `lines()`，`URLEncoder` 无 `encode(String, Charset)`；打包前这两种调用都留在 `source-core`。D8 会回移本项目多处 `String.isBlank/stripLeading` 和集合工厂调用，但没有回移上述两项，因此 JVM 测试通过不能证明 API 23 可运行。

现将正文 `replaceRegex` 前的 `String.lines()` 改为显式 CRLF/CR/LF 分行，保留原先的逐行 `trim` 和末尾换行处理；URL 模板编码改用 API 23 已有的 `URLEncoder.encode(String, String)`。现有 URL 模板测试以及新增正文混合换行测试通过。`./gradlew :source-core:test :app:assembleDebug --offline --rerun-tasks` 通过；重新扫描 APK 后，不再发现这两种直接调用。

本轮未通过应用 UI 在 API 23 上单独触发这两个分支。DEX 仍有第三方 Rhino `TokenStream` 中对 `String.codePoints()` 的直接调用。其 1.7.15 源码表明：只有 ES6 模式的**标识符包含 Unicode 转义**时，`isValidIdentifierName()` 才调用它；例如脚本文本 `var \\u0061 = 7; a` 在 JVM 返回 7，普通 `<js>result</js>` 不会走此分支。Android 23 `String` 无 `codePoints()`，而打包 DEX 保留了直接调用，因此该特殊输入有 `NoSuchMethodError` 风险。App 已捕获 `LinkageError` 并报告运行环境错误；尚未在设备上触发该输入，不计为设备复现或已修复。

对既有 AOAOSTAR 3907 条集合做一次只读静态筛查，Unicode 转义多见于正则和字符串；未定位到确定的转义标识符规则。这不能证明全部书源没有该语法。不要简单重写脚本文本：同样的 `\\u` 也出现在字符串和正则中。上游 [Rhino 1.8.0 的 TokenStream](https://github.com/mozilla/rhino/blob/Rhino1_8_0_Release/rhino/src/main/java/org/mozilla/javascript/TokenStream.java)仍使用 `codePoints()`；[1.9.1](https://github.com/mozilla/rhino/blob/Rhino1_9_1_Release/rhino/src/main/java/org/mozilla/javascript/TokenStream.java)改用 `codePointAt()`，但升级涉及 Java 版本和 Rhino 行为变化，不能为这一未命中分支直接替换依赖。若真实规则触发，先在 API 23 夹具复现，再评估上游版本差分或受控补丁。
