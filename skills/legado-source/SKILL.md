---
name: legado-source
description: 在 new-book-getter 中创建、修复或解释 Legado 普通书源与 RSS 配置，或开发解析器、请求规划、JS 宿主及兼容回归。不用于一般 Android UI 或 Chromium 构建适配。
---

# Legado 书源与兼容开发

先区分任务是交付源 JSON，还是修改本工程引擎；同时涉及两者时分别验收。普通 `BookSource` 与 `RssSource` 独立建模，不混用身份、类型值或执行路径。

## 按任务读取

- **创建、修复或解释源**：普通书源读[编写参考](references/book-source.md)，RSS 读[订阅源编写](references/rss-source.md)。从用户提供的 URL、页面、接口响应或 JSON 取得目标阶段证据，依据实际响应写最少必要规则；缺关键页面时交付限定的草案，不照搬其他站点选择器。
- **引擎兼容与差分测试**：读[兼容开发](references/engine-compat.md)，再按受影响路径查[规则契约](../../docs/legado-rule-contract.md)或[RSS 边界](../../docs/rss-compatibility.md)。实现使用 Java，保持核心与 Android 适配边界。
- **验证和交付**：读[统一验证口径](references/verification.md)。JSON 可导入、离线规则通过、真实阶段通过和完整目标流程通过分别报告。

模型、目标能力和证据层级见[书源定义 RFC](../../docs/source-definition.md)；仅在涉及定义或验收争议时查相应章节。当前工程支持程度只查[能力矩阵](../../docs/compatibility.md)，执行记录由[验证入口](../../docs/verification.md)索引，历史探针不表示当前站点状态。

行为依据是项目根目录兄弟检出 `../legado` 的官方源码，版本基准见 RFC。`legadoSkill/` 是固定版本的独立上游 Git 仓库，其[规则索引](../../legadoSkill/docs/rules/README.md)和[勘误](../../legadoSkill/docs/rules/errata.md)用于查线索；Python 模拟器不能证明官方 App 可用。版本升级须重核受影响路径与夹具。

目标能力写在 JSON 外的验证说明中，不发明 Legado 字段。日志与夹具不提交凭据、Cookie、第三方完整书源或作品正文；登录、验证码、付费和发布动作只在已有授权范围内验证。
