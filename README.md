# Book Getter

独立的 Android 书源获取实验工程，代码使用 Java。它不包含阅读排版、TTS、本地 TXT/EPUB 解析或 MOBI 转换。用户导入自己的书源后，可以单源或多源搜索、查看详情与目录、抓取选定章节，并导出文本小说为 TXT 或 EPUB；也可导入独立 RSS 源，按分类或搜索浏览文章。

## 模块

运行时仅有两个库，依赖方向为 `app → source-runtime-android → source-core`：

- `source-core`：纯 Java 规则与书源/RSS 引擎、订阅事务、缓存、续抓及 TXT/EPUB 导出。业务入口位于 `org.bookgetter.business`，导出保留 `org.bookgetter.export` 包名。
- `source-runtime-android`：默认 OkHttp、WebView、Cookie、SharedPreferences 与资源下载适配。
- `app`：调试界面、系统文件选择器和 Activity 生命周期管理。
- `source-catalog`：独立开发工具，不进入 App 或库的运行时依赖。

第三方依赖版本集中在 `gradle/libs.versions.toml`。构建使用 JDK 21；宿主注入方式与迁移约束见[嵌入指南](docs/embedding.md)。

## 使用

在 `new-book-getter/` 作为独立工程打开。导入书源 JSON（文件、剪贴板或 URL），选择单个书源或“全部书源”搜索；“下一页”继续查询当前关键词。选中单个书源后可从“发现”选择 URL 分类。点选结果后按书源配置读取详情与目录；仅提供搜索的书源会停在书籍信息。勾选章节后导出，未勾选则抓取全书。选章任务和已完成章节保存在应用私有目录，重开应用后可点“继续上次”；新导出与未完成任务冲突时会提示继续或替换。若有章节失败，界面会明确报告部分导出及缺章清单。登录入口打开书源 `loginUrl`（缺省为书源地址），浏览器 Cookie 与抓取请求共享。

本目录本身是独立 Git 仓库，`legadoSkill/` 作为上游 Git 子模块固定版本。单独克隆本工程时使用 `git clone --recurse-submodules ...`，已有检出可运行 `git submodule update --init`。规则实现与测试约定见[规则契约](docs/legado-rule-contract.md)和[统一 Legado skill](skills/legado-source/SKILL.md)。

当前只把**文本章节**导出为 EPUB/TXT。漫画图片可按章下载为 ZIP；音频、视频和文件链接可按需下载单个资源。媒体流清单、分段流、解密及复杂书源兼容仍需扩展，见 [能力矩阵](docs/compatibility.md)。所有书源都由用户导入；工程不附带第三方书源或正文。

验证命令：`./gradlew test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease`。核心测试使用本地构造的页面；另有 [验证指南与设备记录](docs/verification.md) 和 [本地测试站点](test-fixtures/README.md)，均不依赖真实第三方网站。文档统一从[文档导航](docs/README.md)进入，书源编写和引擎兼容统一从[Legado skill](skills/legado-source/SKILL.md)按任务读取。

App 的“URL 导入”会保存小说书源订阅地址，支持手动更新；妙公子管理页可选择下游书源合集。普通书源和 RSS 可以混合导入、分别保存，并在一次事务中更新订阅。首页「RSS 订阅」提供分类、搜索、文章阅读及受控网页扩展；旧 WebView 缺少安全消息能力时明确关闭原生桥。书源快照由[书源管理目录](source-catalog/README.md)手动维护，原始快照不纳入版本控制。当前支持边界以[能力矩阵](docs/compatibility.md)为准；历次实站探针与测试范围见[历史证据索引](docs/history/README.md)。
