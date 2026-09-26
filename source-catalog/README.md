# 书源管理目录

`feeds.json` 记录 [吉的书源集](https://source.jwyihao.top/) 页面列出的 9 个**原始 JSON 文件**的 GitHub Raw 地址；代理和 jsDelivr 链接只是同一文件的镜像，不重复下载。页面的完整书源另有转载/二次开发限制，因此本目录只把原始 JSON 保存在本机、默认由 Git 忽略；不要直接提交或再发布 `snapshots/`。需要对外分发完整书源时，应先按上游声明取得相应许可。

在 `new-book-getter/` 执行：

```sh
./gradlew :source-catalog:updateCatalog
./gradlew :source-catalog:verifyCatalog
./gradlew :source-catalog:listCatalog
```

`updateCatalog` 下载到新快照，校验 JSON 和 `bookSourceUrl`，按规范化书源地址去重，生成 `unique.json` 和带原始文件 SHA-256、重复来源记录的 `index.json`。全部成功后才原子切换 `CURRENT`；失败时旧快照继续可用。`verifyCatalog` 对当前快照重新计算哈希和去重结果。`listCatalog` 列出当前去重书源。

当前文件位于 `snapshots/<CURRENT>/`：`raw/` 保留完整的 9 份上游原文，`unique.json` 是可在 App 中通过“文件导入”选取的 11 条去重书源。更新时旧快照保留，可将 `CURRENT` 改回旧快照名进行回滚。去重键是 `bookSourceUrl`：主机名小写、去默认端口、去 URL 片段和路径末尾斜杠；不会擅自合并 `www` 与非 `www` 或 HTTP 与 HTTPS。重复项按 `feeds.json` 顺序保留第一条，并在索引中记录其他来源。

这是手动更新而非后台自动订阅；真实书源能导入不等于规则、登录和网站始终可用。在线搜索诊断见 [实测记录](../docs/live-source-tests.md)。
