---
name: legado-source-compat
description: 在 new-book-getter 中实现或诊断 Legado 普通书源规则兼容性，并为 RSS 订阅源设计独立兼容边界。用于解析器、请求规划、JS 宿主和差分测试；不用于一般 Android UI 修改或为站点编写书源。
---

# Legado 规则兼容开发

以当前工作区的 Legado 官方源码为行为依据；[书源定义 RFC](../LEGADO_SOURCE_DEFINITION_RFC.md)汇总版本锁定的模型与验收口径，目标能力写在验证记录中，不作为 JSON 字段推断。先读[规则语义](references/rule-semantics.md)中与任务相关的部分，再查对应执行路径。项目的[规则契约](../../docs/legado-rule-contract.md)和[能力矩阵](../../docs/compatibility.md)记录当前 Java 实现与已验证边界。上游 `legadoSkill` 的[勘误](../../legadoSkill/docs/rules/errata.md)可追溯旧说法，历史 skill 和 Python 调试器只用于提出假设。

针对一个兼容差异，确定书源快照、规则字段、阶段和最小输入；对照官方调用路径后补脱敏离线回归，再按[证据与诊断](references/evidence-and-diagnostics.md)复测受影响的真实阶段或 Android 行为。分开记录“规则可导入”“语法可执行”“真实站点可访问”和“整条流程通过”。实现保持 Java，并更新能力矩阵与[实测记录](../../docs/live-source-tests.md)。

普通 `BookSource` 和 `RssSource` 是独立模型。Java RSS 运行时现有标准 XML、自定义列表、分类/搜索及阅读目标子集，Android 另有网页执行层；必须按[RSS 边界](references/rss-compat.md)分别验证身份、列表、正文和网页桥，不能从字段可导入或普通书源通过推断完整 RSS 兼容。官方源码位于项目根目录的兄弟目录 `../legado`。

不提交凭据、Cookie、第三方完整书源或作品正文。真实站点的登录、验证码、付费和发布动作只在已有授权范围内测试；无法验证的能力明确标为待验证。
