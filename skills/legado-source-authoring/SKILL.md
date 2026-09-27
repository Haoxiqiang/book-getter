---
name: legado-source-authoring
description: 根据网页、接口或现有 JSON 创建、修复和解释可导入 Legado 的普通书源或 RSS 订阅源。用于站点规则编写与逐阶段调试；不用于实现 new-book-getter 的解析器。
---

# Legado 书源与订阅源编写

先判定用户要的是普通 `BookSource` 还是独立的 `RssSource`。[书源定义 RFC](../LEGADO_SOURCE_DEFINITION_RFC.md)汇总版本锁定的模型与验收口径；普通书源按[字段与规则](references/book-source.md)处理，RSS 按[订阅源](references/rss-source.md)处理。目标能力写在验证记录中，不在源 JSON 中添加虚构的声明字段。不要以字段名相似为由混用两种实体或类型值。规则行为以当前工作区的官方 Legado 源码为依据；旧 `legadoSkill` 的版本化 skill、模板、案例和 Python 模拟器只作为线索，遇冲突按技能内的勘误处理。

从用户给出的 URL、HTML/API 响应或现有 JSON 取得与目标阶段相关的证据，先辨明请求、编码、重定向、登录或 WebView 条件，再基于实际结构写规则。已有样本足够时直接工作；缺少关键页面且无法访问时明确列出未验证字段，提供可检查的草案。不要照搬其他站点的选择器或假装模拟调试等于 Legado App 通过。

对目标流程逐阶段验证请求和提取结果，按[验证与交付](references/verification.md)区分已实测、仅静态检查和待真机验证。交付符合该导入入口的 JSON 及简短验证说明；单个源是 JSON 对象，数组只是批量包装。保持用户提供的合法配置，不自行补齐无关字段、账号、Cookie 或未经授权的付费/发布动作。
