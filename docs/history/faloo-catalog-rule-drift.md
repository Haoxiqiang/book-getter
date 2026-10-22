# 飞卢目录零匹配归因：第三方规则与现页不符

2026-09-27，补充诊断集合 [AOAOSTAR 128 源](https://legado.aoaostar.com/sources/4dc410d1.json) 的 SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。原始 JSON 未提交，也不并入主书源目录。选“飞卢小说💰”以“斗破苍穹”搜一次：公开搜索 30 条，首条详情 HTTP 200、书名和元数据可取；`CATALOG.ruleToc.chapterList` 报“没有匹配章节”。未进入正文或付费页面。

该源 `ruleBookInfo.tocUrl` 留空，工程按详情 URL `https://b.faloo.com/1440069.html` 作为目录页；其 `ruleToc.chapterList` 是 `class.DivTd@tag.a`。对当前公开详情页做只读结构计数：`DivTd3` 容器 29 个，`DivTd` 容器 **1 个**，后者内容仅 `&nbsp;`、没有章节链接。因此规则第一段能选到空白占位容器，但第二段 `tag.a` 选不到节点。页面上的“目录”链接指向 `https://b.faloo.com/html_1440_1440069/`；该页的章节容器为 `c_con_li_detail_p`，同样计数 29 个，而非 `DivTd`。这解释了为什么 HTTP 200 和详情成功仍会得到目录 0 章。

本次未改动第三方原始书源。候选修订有两条独立路径，须由书源维护方或本地明确的覆盖层选择并另行验证：在详情页用 `class.DivTd3@tag.a`；或把 `tocUrl` 指向目录链接，再用 `class.c_con_li_detail_p@tag.a`。不能让解析器把 `DivTd` 模糊匹配成 `DivTd3`，因为同页确实存在一个语义不同的 `DivTd`。合成 Java 回归验证旧选择器报 `CATALOG.ruleToc.chapterList`、精确的新选择器可得到一条绝对章节 URL。这是规则漂移证据，不证明候选修订能处理所有卷、VIP 标记或完整目录，更不涉及付费内容。

诊断原则：目录 0 章时先区分“规则选择器选到错误节点”与“解析器不支持语法”；只有后者才修改核心引擎。上游书源更新、去重仍由[主书源目录](../../source-catalog/README.md)管理，不能把这条补充来源的候选修订偷偷写入原始快照。
