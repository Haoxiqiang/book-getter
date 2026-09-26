# JS 宿主预检分级与跨站阻断记录

2026-09-27。目标是让兼容诊断准确指向**未提供的方法、仅部分兼容的方法及具体规则字段**，而不是把所有 `java.*` 调用报成未实现。语义优先对照仓库内 Legado `app/src/main/java/io/legado/app/help/JsExtensions.kt`，Java 端实际暴露方法以 `source-core/.../RhinoRuntime.java` 为准。`java.head` 在原版执行不自动跟随跳转的 HTTP HEAD；`java.base64Decode` 有字符串/编码重载；`java.encodeURI` 使用 `URLEncoder`。本工程这些方法目前未提供，不能仅因源站请求失败就宣称它们可运行。

旧预检只豁免 `java.ajax`，因而把已经存在的 `java.get`、`java.post`、`java.md5Encode` 等误列为 `UNSUPPORTED_JS_HOST`；也没有扫描 `jsLib`，并把不同阶段混成 `mainJs/rules`。现按当前宿主的暴露情况分为：

| 级别 | 例子 | 诊断含义 |
| --- | --- | --- |
| 不提示 | `ajax`、`put`、`md5Encode`、`base64Encode`、`timeFormatUTC` | 有基础实现；**不**等于所有重载、时序和登录环境均等价 |
| `PARTIAL_JS_HOST` | `get`、`post`、`getString`、`getElement(s)`、`setContent`、`t2s`、`toast/longToast`、`startBrowserAwait`；后续补入的 `base64Decode`、`encodeURI` 见[编码探针](js-host-encoding-probe.md) | 只支持受控子集、占位或人工交互前提；不能算完全兼容 |
| `UNSUPPORTED_JS_HOST` | `head`、`getStringList`、`refreshTocUrl` 等 | 当前 Rhino 宿主没有该方法 |

新预检扫描 `mainJs/jsLib/searchUrl` 以及四个规则段的各字段，诊断带阶段和精确字段；脱网测试覆盖三种级别、`jsLib` 和目录字段定位。`./gradlew :source-core:test :app:assembleDebug --offline` 通过。静态扫描不能证明脚本分支真的执行，实时阶段探针仍是必要条件。

JVM `liveSourceProbe` 现单列 `PREFLIGHT` 行。101 言情的实际原规则分别在 `SEARCH.ruleSearch.bookList`、`DETAIL.ruleBookInfo.init`、`CATALOG.ruleToc.chapterList`、`CONTENT.ruleContent.content` 标出 `base64Decode` 缺失；随后请求才在旧域跳转后的 HTTP 404 停住。这样的输出把**静态能力缺口**与**本次真实首个失败阶段**并排保留，不混成一个错误。

静态候选分别来自 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，本轮内容与既有 SHA-256 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de` 对照，以及 [关耳女频 86 源集合](https://legado.aoaostar.com/sources/e3e5d620.json)，既有 SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。原始 JSON 仅在探针内存中处理，不提交或并入主书源目录。

为避免把静态缺口错当真实失败，对三个规则机制不同的候选源各做一次低频搜索（“故事”），仅请求搜索，不抓小说内容：

| 站点/静态候选 | 实时最深阶段 | 当前结论 |
| --- | --- | --- |
| 一读小说：搜索规则用 `java.head` | 搜索请求 TLS 握手被远端终止 | 未执行到 `head`；方法缺失是静态实现缺口，不能说它导致了此轮失败 |
| 101 言情：搜索规则用 `java.base64Decode` | 旧域 301 到新域后 HTTP 404 | 当时未提供、现有[部分兼容子集](js-host-encoding-probe.md)；仍未执行到该规则，优先归站点/源 URL 漂移 |
| 书香之家：后续规则用 AES/字符串宿主 | 搜索请求 TLS 握手被远端终止 | 未进入详情/目录或加密分支 |
| 粉丝漫画：正文规则用 `java.getStringList` | 搜索请求 TLS 握手被远端终止 | 未进入正文；没有下载漫画资源 |

这四个阻断样本说明继续随机抽旧域名的收益很低。下一轮优先在**已能到相应阶段**的独立站点上筛选未覆盖方法；若没有这样的公开站点，就以最小离线契约回归加原版差分为先，真实可用性保持“未证实”。
