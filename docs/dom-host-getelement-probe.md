# `java.getElement/getElements` 的受控节点返回值

2026-09-27。目的为校验规则宿主中的 DOM 对象语义，不下载小说。依据仓库内 Legado [AnalyzeRule.kt](../../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 的 `getElement`（约 387 行）和 `getElements`（约 453 行）：默认 HTML 模式保留 jsoup 元素/元素集合，后续脚本可访问节点方法；不能在宿主入口直接把每项降为 HTML 字符串。`getElement` 可给集合调用 `first/text/html/select`，`getElements` 可迭代元素。原方法还支持 JSON、XPath、正则与 JS 混合，以下实现**只覆盖已验证的 HTML 安全视图**。

此前 Java `RhinoRuntime` 将两者共用为“HTML 字符串数组”，所以 `java.getElement('li').first().select('a')` 会在脚本内失败。现：

- `getElement` 的 HTML 命中返回受控 `Elements` 视图；`getElements` 返回元素安全视图数组。只暴露白名单方法，不把真实 Java `Element` 类给 JS；`getClass` 不可见。
- 脚本返回这些视图时，Java 宿主用本次执行的身份映射恢复原 jsoup 节点，再交给列表/字段规则，避免 `JSON.stringify` 把方法视图变成 `{}`。普通 JSON/非 DOM 宿主仍需独立验证。
- 既有 `java.setContent(result); java.getString(...)` 合成回归保持通过；新增回归检查 `first/select/attr/html`、元素数组迭代、脚本直接返回节点及搜索字段继续解析。

跨端夹具 [node-source.json](../test-fixtures/http/node-source.json) 和 [node-search](../test-fixtures/http/node-search) 是纯合成 HTML：搜索列表脚本先对 `getElement` 调用 `first().select('a').attr('href')` 作断言，再返回 `getElements`。JVM 单测得 2 条（One/Two）；Android 模拟器在本地 `http.server` 上请求 JSON 和页面均 HTTP 200，`SEARCH_OK hits=2 first=One`，无章节请求。复现见[夹具说明](../test-fixtures/README.md)。

主书源快照 `20260926-170836-f658eae9`（去重 JSON SHA-256 `a38d14210242819454c9c0b741335a2892bdbfc0150f7d674e99a9e3a2bd86ed`）的“三七轻小说”使用 `java.getElement`、`setContent` 等方法，但以“故事”低频重验时搜索返回未解决的 HTTP 302，目标 `ww17.m.37yq.com`，停在 `SEARCH.searchUrl`。这只是原站/请求边界；不能宣称真实规则因此通过。另有主源的复杂 AES/Cookie 分支仍未实现，不应从此夹具推断兼容。

```sh
./gradlew :source-core:test :app:assembleDebug --offline
./gradlew :source-core:liveSourceProbe '-PprobeSource==https://m.37yq.com' '-PprobeQueries=故事' --offline
```

后续应找实际可到达、在搜索或目录阶段使用同类宿主节点方法的**第二站**交叉验证；同时补对象/列表在 JSON、XPath 与正则模式的返回值契约。当前状态是“HTML 受控子集跨 JVM/Android 合成通过，原真实站点未触发”。
