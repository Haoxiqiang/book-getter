# 书源管理目录

`feeds.json` 记录可手动更新的原始订阅 URL，包括用户指定的妙公子订阅、吉的书源集 9 份文件，以及 yckceo 的 7860、7789、7779、7818 四份漫画书源。妙公子文件是 1 条 Legado RSS 源（`sourceUrl`）；其余文件含小说或漫画书源（`bookSourceUrl`）。RSS 原文会进入本机快照并接受哈希和结构校验，但不计入小说搜索的 `unique.json`。App 对妙公子管理页提供选合集适配：输入其 JSON URL，选择要导入的下游书源合集，之后可更新。HTTP 订阅须在单条配置中显式设置 `allowHttp`；它的传输未加密。

上游文件保存在本机、默认由 Git 忽略。完整书源可能附带转载限制，公开分发前应核对上游声明。

在 `new-book-getter/` 执行：

```sh
./gradlew :source-catalog:updateCatalog
./gradlew :source-catalog:verifyCatalog
./gradlew :source-catalog:listCatalog
```

`updateCatalog` 下载到新快照，校验 JSON 和 `bookSourceUrl`，按规范化书源地址去重，生成 `unique.json` 和带原始文件 SHA-256、重复来源记录的 `index.json`。全部成功后才原子切换 `CURRENT`；失败时旧快照继续可用。`verifyCatalog` 对当前快照重新计算哈希和去重结果。`listCatalog` 列出当前去重书源。

当前文件位于 `snapshots/<CURRENT>/`：`raw/` 保留完整的 14 份上游原文。2026-09-27 的快照 `20260927-073600-0a3db395` 包含 15 条去重小说/漫画书源，可在 App 中通过“文件导入”选取；实际数量以 `listCatalog` 为准。更新时旧快照保留，可将 `CURRENT` 改回旧快照名进行回滚。去重键是 `bookSourceUrl`：主机名小写、去默认端口、去 URL 片段和路径末尾斜杠；不会擅自合并 `www` 与非 `www` 或 HTTP 与 HTTPS。重复项按 `feeds.json` 顺序保留第一条，并在索引中记录其他来源。

目录快照通过 CLI 手动更新；App 的“URL 导入”会持久化 URL，之后可点“更新订阅”重新下载。真实书源能导入不等于规则、登录和网站始终可用。在线搜索诊断见 [实测记录](../docs/live-source-tests.md)。
