# `java.post` 保留 302 响应头：宿主与起舞中文复测（2026-09-27）

起因是 [AOAOSTAR 关耳女频集合](https://legado.aoaostar.com/sources/e3e5d620.json)中的起舞中文规则：搜索 URL 脚本调用 `java.post("https://www.75zw.com/search.html", body, headers).header("Location")`。集合去重 86 源，SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。原 Java 宿主在请求前报 `Cannot find function post`。不提交第三方完整规则、Cookie 或响应正文。

官方 [JsExtensions.post](../../app/src/main/java/io/legado/app/help/JsExtensions.kt)使用 jsoup POST、原始请求体、可选请求头和 `followRedirects(false)`，返回可读取 `header("Location")` 的响应。Java 工程现给 `FetchRequest` 加逐请求 `followRedirects`，普通请求默认仍跟随；`java.post` 单独禁用跳转，返回只暴露 `header/body/statusCode/url` 的安全 JS 视图，不开放 Java 类反射。JVM 探针用同一 Cookie 管理器创建不跟跳的客户端，Android 的 OkHttp 对该次请求关闭 HTTP/HTTPS 跟跳。宿主默认表单 Content-Type；头参数支持普通 JS 对象或 JSON 字符串。`cookies()`、官方完整响应对象、超时参数与全部 jsoup 行为尚未实现，不应称完全等价。

离线测试有两层：合成规则的 `java.post(...).header('Location')` 返回相对地址，验证 POST 不跟跳、头大小写无关、原始 body/头保留，再由搜索阶段 GET 结果；本地 HTTP 服务器实际返回 302，验证 JVM 传输最终交还 302 及 `Location` 而非自动改为 GET。JavaScript 视图的 `getClass` 不可访问，暂不支持的第 4 个超时参数会显式报错而非静默忽略。`./gradlew :source-core:test :export:test :app:assembleDebug --offline` 通过。

真实源用“故事”低频搜索：JVM 不再出现缺方法异常，停在搜索脚本内部的 `SSLHandshakeException: Remote host terminated the handshake`；Android 模拟器同源停在 `SSLHandshakeException: connection closed`（底层 `EOFException`）。两端都未取得服务器 302，故真实 `Location`、后续列表规则、详情与目录仍未验证。没有禁用 TLS 验证、绕过站点限制或下载书籍。

Android 又使用一次性本机合成 HTTP 服务做独立对照：模拟器访问 `10.0.2.2:18765`，临时 `/source.json` 仅含一个 `java.post(...).header("Location")` 搜索规则；`POST /form` 返回 302 `Location: /results`，`GET /results` 返回一个合成 `<div class="book">`。设备日志顺序为 `FETCH POST /form status=302` → `FETCH GET /results status=200` → `SEARCH_OK hits=1`；服务端也只记录 `GET /source.json`、`POST /form`、`GET /results` 各一次。由此确认 Android 的不跟跳行为，而非仅凭代码推断。临时服务已停止，合成页面和响应序列记录于此，不作为第三方书源资产提交。

复测：

```sh
./gradlew :source-core:test :export:test :app:assembleDebug --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.75zhongwen.com' '-PprobeQueries=故事' -PprobeCatalogFirst=true --offline
adb shell am start -n org.bookgetter.app/.CompatibilityProbeActivity --es catalogUrl https://legado.aoaostar.com/sources/e3e5d620.json --es sourceFilter www.75zhongwen.com --es keyword 故事
```
