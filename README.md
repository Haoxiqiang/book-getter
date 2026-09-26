# Book Getter

独立的 Android 书源获取实验工程，代码使用 Java。它不包含阅读排版、TTS、本地 TXT/EPUB 解析或 MOBI 转换。用户导入自己的书源后，可以单源或多源搜索、查看详情与目录、抓取选定章节，并导出文本小说为 TXT 或 EPUB。

## 模块

| 模块 | 职责 |
| --- | --- |
| `source-core` | 书源 JSON、预检、请求规划、CSS/XPath/JSONPath/正则/基础 JS、抓取管线、聚合搜索 |
| `source-runtime-android` | OkHttp、WebView 与 Android CookieManager 共享登录态 |
| `export` | UTF-8 TXT 与最小 EPUB 2 输出 |
| `app` | 导入、手动登录、搜索、选章、缓存与导出的调试界面 |

## 使用

在 `new-book-getter/` 作为独立工程打开。导入书源 JSON（文件、剪贴板或 URL），选择单个书源或“全部书源”搜索。点选搜索结果读取目录；勾选章节后导出，未勾选则抓取全书。每个成功章节会缓存在应用私有目录，重试时继续使用缓存。登录入口打开书源 `loginUrl`（缺省为书源地址），浏览器 Cookie 与抓取请求共享。

本目录本身是独立 Git 仓库，`legadoSkill/` 作为上游 Git 子模块固定版本。单独克隆本工程时使用 `git clone --recurse-submodules ...`，已有检出可运行 `git submodule update --init`。规则实现与测试约定见[规则契约](docs/legado-rule-contract.md)和[本地兼容 skill](skills/legado-source-compat/SKILL.md)。

当前只把**文本章节**导出为 EPUB/TXT。漫画图片可按章下载为 ZIP；音频、视频和文件链接可按需下载单个资源。媒体流清单、分段流、解密及复杂书源兼容仍需扩展，见 [能力矩阵](docs/compatibility.md)。所有书源都由用户导入；工程不附带第三方书源或正文。

验证命令：`./gradlew :source-core:test :export:test :app:assembleDebug`。核心测试使用本地构造的页面；另有 [模拟器闭环记录](docs/verification.md) 和 [本地测试站点](test-fixtures/README.md)，均不依赖真实第三方网站。文档：[架构](docs/architecture.md) · [能力矩阵](docs/compatibility.md) · [资料与案例](docs/references.md)。

上游书源的本地更新、快照和去重由[书源管理目录](source-catalog/README.md)负责；真实书源的五个代表性搜索词见[在线实测](docs/live-source-tests.md)，主集合 11 源逐阶段缺口见[跨站兼容诊断](docs/cross-source-compatibility.md)，另有不并入主目录的[22 源补充探针](docs/supplemental-source-probe.md)、[POST/翻页边界探针](docs/post-and-pagination-probe.md)、[变量与伪正文探针](docs/variable-and-response-probe.md)、[媒体书源探针](docs/media-source-probe.md)及[压缩响应/详情初始化探针](docs/gzip-and-init-probe.md)。原始书源快照只保存在本机，不纳入版本控制。
