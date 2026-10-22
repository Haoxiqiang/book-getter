# 架构与处理机制

## 模块边界

运行时依赖固定为 `app → source-runtime-android → source-core`，`source-catalog` 是独立开发工具。原 `export` 模块已合并入核心并保留包名。核心中的 `org.bookgetter.business` 承载原 App 的订阅事务、导入协调、缓存、任务日志、续抓与导出协调；不依赖 Android。

Android 库通过 `AndroidSourceStorage` 实现偏好存储，默认网络、Cookie、网页与下载实现也留在此层。App 显式提供目录与适配器，仅负责 UI、系统交互和生命周期。第三方版本统一在 Gradle version catalog；宿主接入和线程/资源所有权见[嵌入指南](embedding.md)。

## 数据流与职责

```text
app：Activity、文件选择器、生命周期及调试探针
  └─ source-runtime-android：默认平台适配（api 暴露核心）
       ├─ AndroidTransport → HTTP / WebView / CookieManager
       ├─ AndroidSourceStorage → SharedPreferences
       ├─ ResourceDownloader → ResourceDownloadService
       ├─ RssWebViewController → RssBrowserSession
       └─ source-core：纯 Java
            ├─ core：导入模型、请求规划、规则与普通/RSS 引擎
            ├─ business：订阅事务、缓存、任务日志、续抓与导出协调
            └─ export：BookExporter（TXT/EPUB）

source-catalog：独立开发工具，不进入运行时依赖
```

`SourceEngine` 不依赖 Activity、Room、文件系统或 Android 类；其输入与输出是 Java 数据类型。阶段请求经 `StageFetcher` 统一分类、追踪，再由 `Transport` 决定 HTTP 或浏览器获取方式；Android 实现使用同一个 CookieManager。`CaptureRunner` 不持有 Activity 状态，文件流由界面在用户选定位置后提供。

## 公共接口及身份

- `SourceImporter.parse(json)` 返回书源、远程合集 URL 与预检诊断；未知 JSON 字段保留在 `Source.json`。
- `SourceEngine.search(source, keyword, page)`、`exploreEntries(source)`、`explore(source, entry, page)`、`detail(source, hit)`、`chapters(source, detail)`、`content(source, detail, chapter, nextChapterUrl)` 是逐阶段接口。每个结果带 `sourceId` 和原始书籍 URL；跨源聚合只并列展示。
- `SearchCoordinator.search(sources, keyword, page)` 并发调用每个书源，单源失败进入 `errors` 和结构化 `failures`，不终止其他来源。
- `BookExporter.write(format, detail, selectedContents, output)` 只接受文本章节，输出流由调用方选择，因此可接 SAF 或其他存储。
- 解析失败使用 `SourceFailure(stage, sourceId, field, code, message)`，区分请求规划、传输、身份/响应和规则错误；可选 `StageTrace` 回调记录脱敏的请求方法、最终地址、状态与响应质量。

## 请求、登录与持久化

请求规划处理相对 URL、`{{key}}`、`{{page}}`、请求头与基础的 `,{...}` 选项。书源文件及每份订阅响应最多 16 MiB，单次导入最多 10,000 个不同书源、100 个嵌套 URL；HTTP 和 WebView 响应最多 32 MiB，超限明确失败。规则内 `<js>` 和 `@js:` 经可中断、带 60 秒执行上限的 Rhino 解释器执行；`mainJs` 首批执行四个基础函数。HTTP Cookie 从 Android CookieManager 读取，响应 Set-Cookie 写回；手动登录 WebView 使用同一 CookieManager。`webView:true` 经后台 WebView 抓取渲染后的 HTML。

成功章节写入应用私有目录，键由书源 JSON 快照、书籍上下文、完整章节快照和已知下一章边界组成；规则变化后不会误用旧缓存。选章任务、目录快照和逐章进度另行保存，重开应用可以重新选择导出位置并续抓。取消能停止后续章节，但应用进程被系统回收时正在执行的网络请求不会后台继续；部分导出明确保留缺章清单。整书导出从缓存逐章读取正文，取消时停止后续写入。

导出层将正文 HTML 的块级元素与换行转为段落，忽略复杂排版；TXT 使用 UTF-8，EPUB 使用 EPUB 2 的 OPF、NCX 与 XHTML。书源模型没有语言字段，因此 EPUB 元数据暂写 `und`（未指定语言）；书籍标识符由书源 ID 和原始书籍 URL 稳定生成。导出不嵌入图片、样式或封面。

## 扩展点

媒体类型通过 `ChapterContent.kind/resources` 表示，Android 运行层提供显式触发的二进制下载，漫画图片可打包为 ZIP。`WebContentTransport` 负责 `sourceRegex` 的页面资源监听；HTTP-only 运行时明确报不支持。分段流与解密仍需单独实现。复杂 JS 宿主方法应逐项实现并在能力矩阵增加回归样本。规则表达式扩展仍位于 `RuleEvaluator`，不应在 UI 或导出层增加网站特例。

## 任务日志与 UI 生命周期

`ActivityTasks` 统一持有后台任务、取消代数和过期 UI 回调检查，销毁后不再分发结果；`RunDiagnostics` 负责阶段诊断文本，界面只选择展示目标。首次书源读取在后台完成，导入完成直接复用返回的书源快照，搜索结果按预先建立的来源名称索引展示，避免在主线程重新解析整份规则。

`CaptureJobStore` 保留兼容的 `capture-job.json` 静态任务快照，目录、选章与书籍上下文仅在创建或明确替换任务时写入。逐章完成数和单章错误追加到 `capture-progress.jsonl`，续跑开始以原子替换重置进度日志，完整读取任务时再重放日志。旧格式任务没有日志时仍按原快照读取。日志带任务 generation，旧任务 worker 不能写入或清除新任务；不完整的最后一条日志忽略后可续跑，中间损坏则保留任务并要求备份，备份同时保存快照与日志。`ChapterBoundaries` 在抓取和缓存导出开始时分别建立一次下一可读章索引，保持卷标题和重复相等章节的既有语义。

系统文件选择器的资源 URL 列表存放在应用私有 `pending-resources` 目录，Activity 状态仅保存随机 token；选定或取消后的快照删除，未能正常返回的孤立快照七天后清理。旧版 Bundle 中的完整选择仍可迁移读取。EPUB 导出确定关闭 ZIP 压缩器，但保留调用方对最终输出流的关闭和提交责任。目录工具的配置检查与实际下载共享同一 URI 协议校验。

## 规则状态与 RSS 会话

`RuleSyntax` 共享词法边界，`RuleBindings` 管理工作线程上的搜索项、书籍、章节和 RSS 文章作用域；`RhinoSourceBindings` 只安装源状态及 Cookie 的受控 facade。`SourcePolicy` 保留模型身份、限速和默认头来源；`SourceRequests` 对阶段、JS 子请求、资源下载及 RSS 原生 GET 应用统一策略，头优先级为默认源头、已保存登录头、显式请求头。登录头仅隐式发送到相同 origin，源 ID 的说明片段不参与 origin 判断。私有状态不进入书源 JSON，应用禁用备份。

RSS 模型、导入器、引擎与仓储独立；`MixedSourceStore` 以一次持久提交发布两种模型。`RssActivity` 使用不可变阅读目标，资源与文章选择通过私有 token 恢复。预加载只预取最多两篇文章的引擎结果，不运行隐藏页面 UI 动作；缓存有总量限制，并用源快照、完整文章、结构化状态、源/文章 Cookie 和刷新版本隔离，拒绝跨会话的迟到响应。

`RssWebViewController` 管理 origin、主 frame、页面 token、注入、导航与有界队列。旧 provider 不支持安全消息时不安装原生桥。浏览器 URL 正则使用有编译/匹配预算的 RE2/J 子集；普通规则正则继续保留 Java 语义。Android ICU 不支持基于 CharSequence 的合作中断，所以不能把 JVM 正则包装当作 Android 时间上限。

## 官方 Legado 调用链参考

原仓库的 `BookSource` 包含地址、请求头、搜索/详情/目录/正文规则与 `mainJs`。`WebBook.searchBookAwait` 用 `AnalyzeUrl` 构造并发送请求，`BookList` 通过 `AnalyzeRule` 生成搜索结果。后续 `WebBook.getBookInfoAwait`、`getChapterListAwait`、`getContentAwait` 分别转给 `BookInfo`、`BookChapterList`、`BookContent`。`mainJs` 则改走 `JsSourceBook`/`JsSourceEngine`。原对象同时调用 Room、`BookHelp` 缓存、全局配置及阅读器状态，不能直接当独立库使用。

定位入口：`app/src/main/java/io/legado/app/data/entities/BookSource.kt`、`model/webBook/WebBook.kt`、`model/analyzeRule/{AnalyzeUrl,AnalyzeRule}.kt`、`model/jsSource/JsSourceBook.kt`。原工程导入分支位于 `ui/association/BookSourceImport.kt`，导出位于 `service/ExportBookService.kt`。
