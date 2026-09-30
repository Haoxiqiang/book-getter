# 书源管理目录

`feeds.json` 记录可手动更新的原始订阅 URL，包括用户指定的妙公子订阅、吉的书源集 9 份文件，以及 yckceo 的 7860、7789、7779、7818 四份漫画书源。妙公子文件是 1 条 Legado RSS 源（`sourceUrl`）；其余文件含小说或漫画书源（`bookSourceUrl`）。RSS 原文会进入本机快照并接受哈希和结构校验，但不计入小说搜索的 `unique.json`。App 对妙公子管理页提供选合集适配：输入其 JSON URL，选择要导入的下游书源合集，之后可更新。HTTP 订阅须在单条配置中显式设置 `allowHttp`；它的传输未加密。

上游文件保存在本机、默认由 Git 忽略。完整书源可能附带转载限制，公开分发前应核对上游声明。

在 `new-book-getter/` 执行：

```sh
./gradlew :source-catalog:checkCatalogConfig
./gradlew :source-catalog:updateCatalog
./gradlew :source-catalog:verifyCatalog
./gradlew :source-catalog:listCatalog
```

`updateCatalog` 下载到新快照，校验 JSON 和 `bookSourceUrl`，按规范化书源地址去重，生成 `unique.json` 和带原始文件 SHA-256、重复来源记录的 `index.json`。每个订阅响应最多 16 MiB，整次更新累计最多 64 MiB；超限或下载失败会清理暂存文件并保留旧 `CURRENT`。全部成功后才原子切换 `CURRENT`。`verifyCatalog` 对当前快照重新计算哈希和去重结果。`listCatalog` 列出当前去重书源。

已下载文件位于 `snapshots/<CURRENT>/`，`raw/` 保留上游原文；快照 ID、原始文件数及去重书源数以本机 `CURRENT`、`index.json` 和 `listCatalog` 输出为准。新检出没有本机快照时，先运行 `checkCatalogConfig` 校验仓库内订阅配置，再按需手动运行 `updateCatalog`。更新时旧快照保留，可将 `CURRENT` 改回旧快照名进行回滚。去重键是 `bookSourceUrl`：主机名小写、去默认端口、去 URL 片段和路径末尾斜杠；不会擅自合并 `www` 与非 `www` 或 HTTP 与 HTTPS。重复项按 `feeds.json` 顺序保留第一条，并在索引中记录其他来源。

目录快照通过 CLI 手动更新；App 的“URL 导入”会持久化 URL，之后可点“更新订阅”重新下载。真实书源能导入不等于规则、登录和网站始终可用。在线搜索诊断见 [实测记录](../docs/live-source-tests.md)。
