# `java.head(...).cookies()` 响应视图

2026-09-27。AOAOSTAR 128 源集合中的“一读小说”`ruleSearch.bookList` 在收到脚本响应时调用 `java.head(baseUrl, {referer:baseUrl}).cookies()`，读取 `token` 与 `secret`。这是实际规则语法命中；先前对该站的搜索请求因 TLS 握手终止，脚本分支没有真实网络执行正对照。

官方 `JsExtensions.head` 返回 Jsoup `Connection.Response`，不跟随跳转；该响应的 `cookies()` 返回 Cookie 名值映射。工程此前已有 HEAD 方法和不跟跳行为，但受控响应视图没有 `cookies()`，HTTP 传输还只保留第一条 `Set-Cookie` 响应头。现在响应视图可读取响应头中的 Cookie 名值，传输以换行保留重复 `Set-Cookie` 头，避免把 `Expires` 日期中的逗号当分隔符；仍不开放原始 Java 响应对象。

离线回归验证两条 `Set-Cookie`、302 保留及 `cookies().token/secret` 读取。`./gradlew :source-core:test --offline --rerun-tasks` 和 `:source-runtime-android:testDebugUnitTest --offline --rerun-tasks` 通过。

该规则随后调用 `source.putLoginHeader`，当前宿主尚未提供此方法，也未实现官方登录头持久化；因此一读小说仍不能标为可用。Cookie 读取只覆盖响应中的基础 `Set-Cookie`，未验证属性、域、路径或 Cookie Jar 的完整官方行为。
