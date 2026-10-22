# JS 编码宿主：`base64Decode` 与 `encodeURI`

2026-09-27。目的不是抓小说，而是补两个真实规则中出现、此前预检标为缺失的 JavaScript 宿主方法，并明确实站验证边界。官方依据是仓库内 Legado `app/src/main/java/io/legado/app/help/JsExtensions.kt`：`base64Decode(str)` 使用 Hutool Base64 的 UTF-8 字符串解码，可指定字符集；`encodeURI(str[,enc])` 实际使用 Java `URLEncoder`，空格编码为 `+`，与浏览器全局 `encodeURI` 不同。两者不能按方法名猜测行为。

Java Rhino 宿主已提供这些受控子集：Base64→UTF-8/指定字符集字符串，以及 UTF-8/指定字符集的表单 URL 编码。`base64Decode` 的数字 flags 重载仍明确报不可用，异常/宽松输入语义未证明与 Hutool 完全相同，因此预检从 `UNSUPPORTED_JS_HOST` 改为 `PARTIAL_JS_HOST`，不标为完全支持。脱网回归覆盖中文 UTF-8、GBK、空格 `+`、无效编码和数字 flags；另以和图书搜索规则的**等价合成模板**验证两次 `java.encodeURI(key)` 的 URL 和单次编码的 Referer 头。`./gradlew :source-core:test :app:assembleDebug --offline` 通过。

关耳女频补充集合快照 SHA-256 为 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`（[来源](https://legado.aoaostar.com/sources/e3e5d620.json)），第三方原始 JSON 未提交。每站仅搜一次“故事”，不请求详情、目录或正文：

| 站点 | 改动后真实探针 | 能证明什么 |
| --- | --- | --- |
| 101 言情 | 搜索旧域 301 到新域的 HTTP 404；预检在搜索列表、详情初始化、目录列表、正文四处列出 `base64Decode` 为部分兼容 | 尚未执行到解码规则；只有脱网编码契约通过，不算实站通过 |
| 和图书 | 搜索 URL 中的双重 `encodeURI` 与 Referer 单次编码已完成请求规划，站点返回 HTTP 403 `Just a moment...` | 编码方法能从原搜索规则运行至发请求；列表规则被访问控制阻断，不能称搜索兼容，不尝试绕过验证 |

Android 模拟器安装本轮 Debug APK 后，同词对和图书重验：`FETCH method=GET`、`host=m.hetushu.com`、路径 `/book/search.list.php`、HTTP 403、标题 `Just a moment...`，随后 `SEARCH.searchUrl` 报 403；没有脚本方法缺失错误。JVM/Android 都证明此源的编码表达式能进入请求阶段，但都没有得到可解析搜索响应。调试异常栈可能含完整查询 URL，公开日志前需脱敏。

重跑：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/e3e5d620.json '-PprobeSource=www.txt101.com,m.hetushu.com' '-PprobeQueries=故事' --offline
```

下一步应使用可到达规则阶段的第二站交叉验证字符集和 Base64 容错；在找到样本前保留“部分兼容”状态。这里不因 HTTP 403/404 擅自改写第三方书源或推断源站内容可抓取。
