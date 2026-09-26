# 架构与处理机制

## 原工程调用链

原仓库的 `BookSource` 包含地址、请求头、搜索/详情/目录/正文规则与 `mainJs`。`WebBook.searchBookAwait` 用 `AnalyzeUrl` 构造并发送请求，`BookList` 通过 `AnalyzeRule` 生成搜索结果。后续 `WebBook.getBookInfoAwait`、`getChapterListAwait`、`getContentAwait` 分别转给 `BookInfo`、`BookChapterList`、`BookContent`。`mainJs` 则改走 `JsSourceBook`/`JsSourceEngine`。原对象同时调用 Room、`BookHelp` 缓存、全局配置及阅读器状态，不能直接当独立库使用。

定位入口：`app/src/main/java/io/legado/app/data/entities/BookSource.kt`、`model/webBook/WebBook.kt`、`model/analyzeRule/{AnalyzeUrl,AnalyzeRule}.kt`、`model/jsSource/JsSourceBook.kt`。原工程导入分支位于 `ui/association/BookSourceImport.kt`，导出位于 `service/ExportBookService.kt`。

## 新工程分层

```
App (导入、选择、任务与导出 UI)
  ├─ SourceImporter → Source / Diagnostic
  ├─ SearchCoordinator → SourceEngine
  │                     ├─ RequestPlanner → Transport (HTTP 或 WebView)
  │                     ├─ RuleEvaluator (CSS/XPath/JSONPath/正则)
  │                     └─ ScriptRuntime (Rhino 基础宿主 API)
  ├─ ChapterStore (私有目录的逐章缓存)
  └─ BookExporter (TXT/EPUB)
```

`SourceEngine` 不依赖 Activity、Room、文件系统或 Android 类；其输入与输出是 Java 数据类型。`Transport` 决定网络与浏览器获取方式，Android 实现使用同一个 CookieManager。未来可以换实现做离线重放、调试日志或测试，不改变规则层。

## 公共接口及身份

- `SourceImporter.parse(json)` 返回书源、远程合集 URL 与预检诊断；未知 JSON 字段保留在 `Source.json`。
- `SourceEngine.search(source, keyword, page)`、`detail(source, hit)`、`chapters(source, detail)`、`content(source, detail, chapter)` 是逐阶段接口。每个搜索结果带 `sourceId` 和原始书籍 URL；跨源聚合只并列展示，不把不同站点的同名书合并为一个抓取对象。
- `SearchCoordinator.search(sources, keyword, page)` 并发调用每个书源，单源失败进入 `errors`，不终止其他来源。
- `BookExporter.write(format, detail, selectedContents, output)` 只接受文本章节，输出流由调用方选择，因此可接 SAF 或其他存储。
- 解析失败使用 `SourceFailure(stage, sourceId, field, message)`，用于定位请求阶段和规则字段。网络、登录或规则异常不吞成空列表。

## 请求、登录与持久化

请求规划处理相对 URL、`{{key}}`、`{{page}}`、请求头与基础的 `,{...}` 选项。规则内 `<js>` 和 `@js:` 经 Rhino 执行；`mainJs` 首批执行四个基础函数。HTTP Cookie 从 Android CookieManager 读取，响应 Set-Cookie 写回；手动登录 WebView 使用同一 CookieManager。`webView:true` 经后台 WebView 抓取渲染后的 HTML。

成功章节写入应用私有目录，键由书源 ID、书籍 URL、章节 URL 组成；重试时先读缓存。导出从指定章节集合中组装文件。当前任务由 Activity 管理，取消能停止后续章节，但应用进程被系统回收时正在执行的网络请求不会后台继续；已完成章节可在下次操作中复用。

导出层将正文 HTML 的块级元素与换行转为段落，忽略复杂排版；TXT 使用 UTF-8，EPUB 使用 EPUB 2 的 OPF、NCX 与 XHTML。书源模型没有语言字段，因此 EPUB 元数据暂写 `und`（未指定语言）；书籍标识符由书源 ID 和原始书籍 URL 稳定生成。导出不嵌入图片、样式或封面。

## 扩展点

媒体类型通过 `ChapterContent.kind/resources` 表示，Android 运行层提供显式触发的二进制下载，漫画图片可打包为 ZIP。后续可将下载任务持久化，并按媒体类型处理分段流与解密。复杂 JS 宿主方法应逐项实现并在能力矩阵增加回归样本。规则表达式扩展仍位于 `RuleEvaluator`，不应在 UI 或导出层增加网站特例。
