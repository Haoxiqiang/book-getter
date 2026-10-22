# 文档导航

本工程面向后续嵌入 Chromium Android，当前保留纯 Java 核心、Android 默认适配、调试 App 和独立目录工具。按任务进入下面的维护入口。

## 工程开发与接入

- [架构与数据流](architecture.md)：模块边界、请求、业务状态及缓存机制。
- [嵌入指南](embedding.md)：宿主接口、依赖、线程、流所有权和数据兼容。
- [验证指南](verification.md)：构建命令、本地/设备入口及最近执行范围。
- [后续工作](remaining-work.md)：仍有证据支持的缺口和移植待办。

## 书源定义与规则

- [书源定义 RFC](source-definition.md)：普通书源/RSS、目标能力及证据层级，保留草案状态。
- [规则契约](legado-rule-contract.md)：共同规则语义与实现回归要求。
- [RSS 兼容边界](rss-compatibility.md)：独立列表、阅读和安全网页执行层。
- [能力矩阵](compatibility.md)：当前 Java/Android 已验证的实现子集，不承诺完整 Legado 兼容。
- [统一 Legado skill](../skills/legado-source/SKILL.md)：按“编写源”或“开发引擎”选择操作参考。

## 工具与证据

- [目录工具](../source-catalog/README.md)、[设备夹具](../test-fixtures/README.md)：保留在各自目录的实际操作说明。
- [历史证据索引](history/README.md)：按主题聚合历次探针、实站与工程验证记录。
- [外部资料与许可边界](references.md)：官方/上游资料和采用结论。

维护时将稳定语义放入规则契约，实现支持状态放入能力矩阵，测试执行与站点快照放入历史证据并链接；skill 只维护任务流程，不复制整份规则手册。历史文件保留当时环境、版本和结论，不能作为当前站点可用率。`legadoSkill/` 是独立上游 Git 仓库，不随本地文档整理改写。
