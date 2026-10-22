# 嵌入宿主指南

## 构建和依赖

移植单元是 `source-core` 和 `source-runtime-android`。`app` 是使用示例及本地验证入口，`source-catalog` 是开发工具。原 `export` Gradle 模块已删除，导出类型仍为 `org.bookgetter.export.BookExporter`。迁出 App 的业务类现在位于 `org.bookgetter.business`，旧 `org.bookgetter.app` 包不提供兼容包装。

独立工程保留 Java 21、Android minSdk 23、compileSdk 36 及 core library desugaring。当前尚未提供 GN 或 Chromium 实现，实际接入时需核对目标分支的 Java 编译与 desugaring 能力。

直接依赖的版本唯一来源是 `gradle/libs.versions.toml`：核心使用 Gson、Jsoup、JSONPath、Rhino、quick-transfer；Android 默认实现使用 OkHttp、AndroidX WebKit、Jsoup、RE2/J。Gson 因模型公开 JSON 类型使用 `api`，其他第三方库使用 `implementation`；Android 库以 `api` 暴露核心。当前解析出的传递依赖还包括 json-smart/accessors-smart、ASM、SLF4J、Okio，以及 AndroidX 引入的 Kotlin 标准库等。应用源码全部为 Java，但这不表示依赖树没有 Kotlin 运行库。移植必须携带传递依赖及其许可，不能只复制直接依赖 JAR。通过 `:source-core:dependencies --configuration runtimeClasspath` 和 `:source-runtime-android:dependencies --configuration debugRuntimeClasspath` 检查实际依赖树。

核心资源目录中的繁简转换排除表也必须打包；quick-transfer 自身的字典资源需随其依赖一起保留。Rhino 规则脚本与网页 JS 是两个运行环境，替换浏览器不等于可以删除 Rhino。

## 宿主组装

宿主创建并注入 `Transport`（或支持网页规则的 `WebContentTransport`）、`CookieAccess`、`SourceStateStore`，再构造 `RhinoRuntime` 和书源/RSS 引擎。默认 Android 实现可继续使用 `AndroidTransport`、`AndroidCookieAccess` 和 `FileSourceStateStore`。同一会话的请求、脚本和页面必须使用一致的状态及 Cookie；Chromium 接入应使用对应 Profile 的实现。

订阅与任务可按以下方式组装（Android 示例，放在宿主初始化处）：

```java
MixedSourceStore mixed = new MixedSourceStore(AndroidSourceStorage.mixedStorage(context));
SourceStore books = new SourceStore(mixed.bookStorage());
RssStore rss = new RssStore(mixed.rssStorage());
SourceImportCoordinator imports = new SourceImportCoordinator(mixed);
ChapterStore chapters = new ChapterStore(new File(context.getFilesDir(), "chapter-cache"));
CaptureJobStore jobs = new CaptureJobStore(context.getFilesDir());
```

纯 Java 宿主提供 `MixedSourceStore.Storage` 即可，无需 Context。`read` 返回完整快照，`write` 必须原子提交全部字段，失败返回 false 且不发布部分结果。普通书源与 RSS 各自保留身份、订阅和手动来源；混合导入统一走协调器。存储实现若有外部写入者，需要自行协调并发。每个 Chromium Profile 应提供独立目录与存储实例。

`CaptureRunner.run` 接收内容获取、输出流工厂和进度回调；`SourceEngine.content` 可以直接作为内容获取回调。RSS 使用独立的 `RssEngine`，`RssReadCache` 接受宿主 Cookie 接口，按源快照、文章和会话状态隔离。

## 平台替换接口

- `Transport` / `WebContentTransport`：分别执行普通请求和网页正文/资源捕获。Chromium 适配需保持重定向、超时、取消、源策略及错误语义。
- `CookieAccess` / `SourceStateStore`：分别接入 Profile Cookie 与私有规则状态，不把登录信息写入源 JSON。
- `ResourceDownloadService`：单资源及 ZIP 下载的阻塞接口。默认 `ResourceDownloader` 保留现有头策略、内容校验、临时文件清理与取消行为；Chromium 可整体替换该服务。
- `RssBrowserSession`：不含 WebView 类型的页面加载、起始页、HTML 和关闭接口，嵌套 `Config` / `Host` 表达配置及宿主回调。默认 `RssWebViewController` 绑定 Android WebView，Chromium 后续绑定自己的页面实例；保留 origin、主 frame、页面 token、有界队列和安全消息能力检查。

这些接口的默认实现仍位于 Android 库，替换时选择对应实现源码及依赖即可。本轮没有把 WebView、OkHttp 的内部逻辑拆成更多模块，也没有实现 Chromium Profile 或 WebContents。

## 线程、生命周期和持久兼容

引擎、导入、抓取、文件读写和资源下载在宿主工作线程运行；取消使用线程中断，宿主负责取消任务和阻止过期 UI 回调。RSS 页面创建与加载在 UI 线程执行，Host 的 dispatch/导航/请求准备回调在有序工作线程执行，诊断回到 UI 线程。页面销毁必须关闭会话；默认 WebView 实现同时释放它绑定的 WebView。

`BookExporter` 和 `ResourceDownloadService` 不关闭调用方输出流。`CaptureRunner` / `CaptureExport` 接管输出工厂返回的流并关闭，只有成功写入并关闭后才清除完成任务；宿主不要混淆两种所有权。

Android 偏好文件仍为 `sources`，沿用 json/subscriptions/manualJson 和 RSS 对应键；任务仍使用 `capture-job.json`、`capture-progress.jsonl`，章节缓存仍位于 `chapter-cache`。不需要数据迁移。保留旧快照读取、generation 隔离、尾部不完整日志恢复及损坏备份语义。升级测试不得清除 App 数据。

验证入口：`./gradlew test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease`。`EmbeddedWorkflowTest` 从外部包使用纯 Java 公共 API 验证导入、订阅更新、搜索、抓取失败、重建实例续抓和导出；设备夹具步骤见 `test-fixtures/README.md`。
