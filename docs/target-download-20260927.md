# 指定作品整本下载验收（2026-09-27）

本记录验收《网球王子》漫画和《全职高手》网络小说。只有整本清单和离线审计同时通过才记为完成；中途缓存的章节不计作整本下载。

## 书源与持久化

用户提供的 yckceo 7860、7789、7779、7818 四个书源 URL 已加入 [`source-catalog/feeds.json`](../source-catalog/feeds.json)。运行 `updateCatalog` 后保留原始 JSON 和按书源地址去重的快照；`verifyCatalog` 校验原文哈希和结构。当前快照 `20260927-073600-0a3db395` 有 15 条小说/漫画书源，妙公子 RSS 单独保存。

《网球王子》选用 7860 包子漫画源。实站搜索精确命中书名，详情和目录返回 184 个可读项，首项规则提取 50 张图片。Android 调试探针也完成搜索、详情、目录、图片地址解析，并由应用的 `ResourceDownloader` 取得首张 JPEG，139,080 字节。这里的 184 是该站目录项数，不能直接等同于其他版本的章节划分。

《全职高手》选用 52书库补充书源 `https://legado.aoaostar.com/sources/e3e5d620.json`，在本机单源快照中持久化。指定书籍详情 `https://www.52shuku.net/wangyou/hRX.html` 返回 2,825 个可读页面，首、中、末页规则均提取到正文。该站按页面拆分正文，2,825 是该站目录项数。

## 下载与验收

整本数据放在 Git 忽略的 `downloads/targets/`：漫画按目录项保存图片缓存、CBZ 和章节清单；小说逐页保存 TXT 缓存，完成后生成整书 `book.txt` 和 SHA-256 清单。两个任务都支持中断后续跑。图片下载限定最多 4 个工作线程，所有请求开始时间至少间隔 500 毫秒；小说请求至少间隔 1,500 毫秒。

漫画本次已完成：`downloads/targets/prince-of-tennis/manifest.json` 记录 184 个目录项、3,657 张图片，184 个 CBZ 合计 943,788,238 字节。独立 `auditComicDownload` 返回 `COMIC_AUDIT chapters=184 images=3657 bytes=943788238`。三处包子 CDN 缺图由其他公开页面补齐，章节清单逐页记录替换来源和哈希：第 17 话替换 12 页、第 31 话替换 11 页、第 60 话替换 22 页。三个补充页面分别比包子规则所得页数多 2 页，清单保留 `sourcePageCount`，不会把页数差异藏在总数里。补图是有标记的跨站补全；此验收不能证明包子漫画单源即可无缺页下载。

小说本次已完成：`downloads/targets/kings-avatar/books/<书籍 URL 哈希>/book.txt` 为 16,124,320 字节，清单记录 2,825 个来源页面、SHA-256 `22804ede309ca86bdbede7410e89ba1f53af9da25553bfac508a59369b51d7e4`。独立 `auditBookDownloads` 返回 `AUDIT valid=1 distinctUrls=1 minimum=1`。TXT 开头有书名与作者，结尾有“（全书完）”；来源正文也夹带少量站点尾注，文件审计证明下载完整性和缓存一致性，不评定排版质量。2,825 是 52 书库的分页数，不是原作的自然章数。

完成后执行以下离线审计；审计不访问书源站点：

```sh
./gradlew :source-core:auditComicDownload -PauditComic=downloads/targets/prince-of-tennis -PauditComicSource=https://cn.bzmanga.com -PauditComicChapters=184 --offline
./gradlew :source-core:auditBookDownloads -PauditBooks=downloads/targets/kings-avatar/books -PauditSource=https://www.52shuku.vip -PauditMinimum=1 --offline
```

漫画审计核对全书与逐章清单、目录项身份、CBZ 字节数和 SHA-256、页数、逐页图片签名。小说审计核对书源与 URL、全部章节缓存、正文有效性、整书字节数和 SHA-256。在线规则测试受网站内容和访问状态影响；离线审计只验证本机已保存的数据。
