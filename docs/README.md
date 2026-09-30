# 验证资料索引

当前实现边界以[能力矩阵](compatibility.md)和[规则契约](legado-rule-contract.md)为准；[架构](architecture.md)说明模块与数据流，[验证记录](verification.md)说明离线测试和设备闭环，[后续工作](remaining-work.md)列出本轮仍有证据支持的缺口。

以下文件记录特定日期、书源快照和网络环境下的观察，不能直接当作当前站点可用率或完整规则兼容承诺：

- [主集合在线实测](live-source-tests.md)、[11 源跨站诊断](cross-source-compatibility.md)及[扩大审查](broad-source-compatibility-20260927.md)：按请求、搜索、详情、目录、单章区分最深通过阶段。
- [规则与 JS 宿主探针](selector-js-selector-probe.md)、[DOM 节点探针](dom-node-js-probe.md)、[混合 JS/JSONPath 探针](mixed-js-jsonpath-probe.md)：记录规则差异与离线回归。
- [订阅和导入专项](five-feed-compatibility-20260927.md)、[媒体探针](media-source-probe.md)、[指定下载验收](target-download-20260927.md)：记录功能边界及独立审计。

其他 `*-probe.md` 文件也保留为历史诊断。阅读时先看文件中的快照、日期、关键词、环境与首个失败阶段；后续修复以能力矩阵和测试结果为准。
