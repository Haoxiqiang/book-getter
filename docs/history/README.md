# 历史证据索引

本目录集中保留各次探针与验证原文；时间、源快照、关键词、运行时和最深成功阶段限定其结论。移入此处不表示重新执行，也不把历史站点结果提升为当前可用率。

当前入口：[文档导航](../README.md) · [能力矩阵](../compatibility.md) · [验证指南](../verification.md)。稳定语义维护在[规则契约](../legado-rule-contract.md)与 [RSS 边界](../rss-compatibility.md)。旧记录中的模块名、测试数量和命令反映当时版本，当前命令查验证指南。

## 工程与设备验证

- [`source-core` 的 Android API 23 标准库调用审查](api23-source-core-audit.md)
- [验证记录](verification-records.md)

## 跨站、订阅与下载

- [不同站点补充探针：搜索边界与目录占位（2026-09-27）](additional-cross-site-probe.md)
- [全量聚合源筛选与同名域名差分](aggregate-source-freshness-probe.md)
- [关耳女频集合：异构机制广度探针（2026-09-27）](aoaostar-mechanism-breadth-probe.md)
- [指定订阅与妙公子合集扩大审查（2026-09-27）](broad-source-compatibility-20260927.md)
- [跨站响应筛查：客户端跳转与域名出售页（2026-09-27）](client-redirect-and-parked-domain-probe.md)
- [11 条书源跨站兼容诊断](cross-source-compatibility.md)
- [多站点搜索规则兼容探针（2026-09-27）](diverse-source-search-probe.md)
- [用户指定的五个订阅：2026-09-27 实测](five-feed-compatibility-20260927.md)
- [上游书源搜索实测（2026-09-26）](live-source-tests.md)
- [妙公子“搜索引擎通用”合集兼容记录（2026-09-27）](miaogongzi-search-engine-compatibility-20260927.md)
- [补充书源跨站探针（不并入主书源目录）](supplemental-source-probe.md)
- [指定作品整本下载验收（2026-09-27）](target-download-20260927.md)
- [XIU2 补充集合：规则广度与人工验证边界（2026-09-27）](xiu2-cross-site-probe.md)
- [关键词适配与 69 书吧源规则漂移（2026-09-27）](xiu2-keyword-and-69shuba-probe.md)

## 请求、响应与分页

- [飞卢目录零匹配归因：第三方规则与现页不符](faloo-catalog-rule-drift.md)
- [压缩响应、结构化详情初始化与业务错误](gzip-and-init-probe.md)
- [字符串型请求头与逐书变量：磨铁、宜搜跨站探针（2026-09-27）](header-and-search-item-variable-probe.md)
- [`java.head(...).cookies()` 响应视图](java-head-cookies-probe.md)
- [`java.post` 保留 302 响应头：宿主与起舞中文复测（2026-09-27）](java-post-redirect-probe.md)
- [经致文学：HTTP 200 不存在页（2026-09-27）](jingzhi-notfound-probe.md)
- [补充集合：POST 搜索、签名 URL 与正文翻页边界](post-and-pagination-probe.md)
- [书源 ID 片段与列表前缀探针](source-id-fragment-and-list-prefix-probe.md)
- [速读谷：详情可选字段与四页目录](sudugu-paged-catalog-probe.md)
- [URL 首匹配与分页回目录：跨站探针（2026-09-27）](url-first-and-pagination-probe.md)
- [跨站探针：对象型 POST、书籍变量与伪正文](variable-and-response-probe.md)

## 规则与 JS 宿主

- [搜索直达详情：`bookUrlPattern` 与空列表回退](book-url-pattern-probe.md)
- [方括号位置索引与书籍元数据兼容（2026-09-27）](bracket-index-and-metadata.md)
- [DOM 动作 `@textNodes` 与 `@all` 的规则验证](dom-actions-probe.md)
- [`java.getElement/getElements` 的受控节点返回值](dom-host-getelement-probe.md)
- [目录规则中的 DOM 节点 JS 宿主](dom-node-js-probe.md)
- [DOM 节点 `select/hasClass` 与 VIP 目录：经致文学（2026-09-27）](dom-select-vip-probe.md)
- [`@html` 外层节点与脚本清理兼容验证](html-action-probe.md)
- [目录 `isPay` 与 `isVip` 分离：晋江样本（2026-09-27）](is-pay-marker-probe.md)
- [`java.getStringList` 的 JSONPath 项目边界](js-getstringlist-jsonpath-probe.md)
- [`java.getStringList` 的 DOM 列表与 URL 参数](js-getstringlist-probe.md)
- [JS 编码宿主：`base64Decode` 与 `encodeURI`](js-host-encoding-probe.md)
- [JS 宿主预检分级与跨站阻断记录](js-host-preflight-audit.md)
- [`java.log` 宿主的受控子集](js-log-host-probe.md)
- [JS 规则中的 Jsoup 安全视图探针](jsoup-safe-facade-probe.md)
- [音频与漫画书源的跨站探针](media-source-probe.md)
- [混合 JS→JSONPath、Map 视图与登录响应：跨站探针（2026-09-27）](mixed-js-jsonpath-probe.md)
- [列表规则的“选择器 → JS → 选择器”兼容探针](selector-js-selector-probe.md)
- [XPath 节点链与可选字段兼容探针](xpath-and-optional-fields-probe.md)
