# 压缩响应、结构化详情初始化与业务错误

2026-09-27，从 [AOAOSTAR 128 源补充集合](https://legado.aoaostar.com/sources/4dc410d1.json)选两个文本源各搜一次 `斗破苍穹`，并仅对有结果的猫眼看书继续首条详情。集合响应 SHA-256：`05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。原始第三方 JSON 未提交，也未抓取或导出小说正文。

| 站点 | 初次探针 | 复测结论 |
| --- | --- | --- |
| 读书阁 | 规划出 POST 搜索请求，远端 HTTP 403 | 停在访问控制；不能推断列表规则好坏，不报空结果 |
| 猫眼看书 | 搜索 HTTP 200、约 4253 字符却 0 条 | 响应实际带 `Content-Encoding: gzip`；JVM 探针未解压，错误地把二进制当 UTF-8。补有 16 MiB 解码上限的 gzip/deflate 处理和脱网 gzip 回归后，JVM 搜索 15 条；Android 同词也 15 条 |

猫眼详情的 `ruleBookInfo.init` 为 `$.data`。官方 [BookInfo.kt](../../app/src/main/java/io/legado/app/model/webBook/BookInfo.kt) 调用 `AnalyzeRule.getElement`，会保留 JSON 对象；本工程原先调用字符串字段提取，破坏后续 `$.novelName` 等字段。现将详情初始化改为结构化提取，合成 JSON 对象和原有 JS 初始化回归均通过。这个修复**不等于**猫眼真实详情通过：实站首条详情仍返回 HTTP 200、88 字符业务错误，`data` 为空数组。此前探针误记 `DETAIL_OK` 并拼出缺书 ID 的目录 URL；现在响应诊断将“非成功业务码 + 错误消息 + 空数组”标为 `BUSINESS_ERROR`，核心在 `DETAIL.ruleBookInfo.init` 处失败。JVM 与 Android 均复现；未尝试绕过站点客户端或授权前提。

JVM 的 `LiveSourceProbe.fetch` 同时供批量验证工具使用，因此压缩处理修复了两条验证路径；Android 使用 OkHttp，自身解压能力需以设备对照为证。这里只证明搜索数据被正确解码，不证明猫眼的详情、目录或正文可用。
