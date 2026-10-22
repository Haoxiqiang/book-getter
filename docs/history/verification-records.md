# 验证记录

## 2026-10-22 整体审查与兼容扩展验收

三个子任务分别实施和复审兼容性、安全、可读性/性能，主任务统一整合并串行验证。官方行为基准为 `../legado` 的 `62003ce732a7e30602754d28996da7f98b9ea296`；本轮没有新增第三方实站或整书通过数量。

在 JDK 21 下运行 `./gradlew test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --console=plain`，最终通过。按单一构建配置计数为 309 项：核心 186、导出 10、目录 10、Android runtime 31、App 72；Android 模块的 Release 同类用例亦通过，不重复计入 309。目录配置校验覆盖 14 个订阅。初期 JDK 17、缺失缓存和并行 Gradle 中间失败均已解决，不作为最终结果。

回归覆盖规则前缀/组合/列表替换、GBK/UTF-8/escape、请求选项与超时、头优先级、持久变量及完整 RSS 文章身份、XML/实体拒绝、混合导入原子提交与失败回滚、任务日志迁移/generation/损坏恢复、2,000 章进度不重写目录、下一章索引、缓存会话快照及新旧缓存互通。资源选择包含 5,000 个长 URL 的 token 恢复，RSS 覆盖过期任务选择和跨会话缓存拒写；TXT/EPUB/ZIP 验证调用方流所有权和异常清理。

设备使用临时 Android 6/API 23 x86_64 模拟器，以及 Pixel 4/API 36，均安装本轮 Debug APK：

- 标准 RSS XML 两端均返回 1 篇、摘要阅读 65 字符，`RSS_PROBE_DONE`。
- 自定义 RSS 两端均返回 2 篇；正文请求依次访问合成第一页和第二页，合并 163 字符，`first=true final=true`。API 23 首次暴露 `StringBuilder.isEmpty()` 的 `NoSuchMethodError`，改为 `length()>0` 后已复测通过。
- XML 首轮曾因 Android Expat 不认识 Xerces feature 而失败；改为声明预检、必需 lexical handler 拒绝 DTD及外部实体 resolver 后，两端复测通过，离线 XXE 负例仍通过。
- API 36 浏览器探针最终 `SUMMARY pass=24 fail=0 skip=0`：主 frame 变量持久化、脚本顺序、同源/异源 iframe、旧 token、导航写入顺序、关闭排队动作、线性正则、长资源地址、字段诊断、心跳及真实请求取消/停止。
- API 23 的旧 WebView 明确报告 `PARTIAL_WEB_BRIDGE`，不安装不安全原生桥；浏览器探针 `pass=8 fail=0 skip=1`。跳过的是需要安全消息特性的桥用例；正则、字段诊断、心跳、真实周期资源执行与取消清理均通过。取消夹具使用 ES5/图片请求，未把旧 provider 不支持 `fetch` 当作取消失败。
- 最终 APK 在 API 23 复跑普通书源合成站点：`SEARCH_OK hits=1`、`DETAIL_OK`、`CATALOG_OK chapters=2`、`CONTENT_OK visibleCharacters=41`，确认既有基础链路未回退。
- API 23 实际 UI 通过首页 URL 导入两条合成 RSS、打开 RSS 入口、分类列表和摘要阅读，截图确认正文可见。界面状态将桥降级解释为中文，技术字段保留在点击可见的诊断中。

设备探针仅面向本地自造夹具。桥关闭用例覆盖控制器队列；已分发到 Activity 的回调通过 generation 单测约束，不宣称完成真实进程回收/所有文件提供方或所有网页功能的设备自动化。浏览器正则采用有编译和匹配预算的 RE2/J 子集；反向引用、环视明确诊断，普通规则的 Java 正则不因此获得时间上限保证。

## 2026-09-27 API 23 与图片归档回归

- `./gradlew :app:lintDebug :app:assembleDebug --offline --console=plain` 通过。应用和 Android 运行库启用核心库 desugaring 后，先前由最低版本 API 23 触发的 77 项 `NewApi` 错误已消除；其余 Lint 警告不影响本次构建。
- 在 Android 6（API 23）ARM 模拟器 `emulator-5556` 安装 Debug APK。合成书源 `test-fixtures/http/source.json` 的设备探针依次记录 `SEARCH_OK hits=1`、`DETAIL_OK`、`CATALOG_OK chapters=2`、`CONTENT_OK`，首页正常启动。将界面设为较小尺寸后，操作区可滚动到导出按钮，状态与结果区保持可见。
- 同一台 API 23 模拟器走真实 UI：URL 导入本地合成书源、搜索 `fixture`、打开两章目录、在系统文件选择器保存 TXT 和 EPUB。首次测试发现 `String.codePoints()` 以及带字符集参数的 `ZipOutputStream` 构造方法在设备上抛 `NoSuchMethodError`，导致 0 字节文件；改成 API 23 可运行的字符遍历和 ZIP 构造后，TXT 得到 129 字节且含两章正文，EPUB 得到 1,840 字节，`unzip -t` 对全部 6 个条目通过。失败尝试遗留的 0 字节测试文件不计入通过结果。
- `ResourceRequestHeadersTest` 新增 CBZ 回归：请求地址故意以 `.jpg` 结尾而响应为 PNG，ZIP 条目按实际图片签名写成 `.png`，内容字节完整。此测试只覆盖本地合成响应。
- API 23 模拟器进一步导入 `comic-source.json`，真实 UI 下载一章图片归档；得到 194 字节 ZIP，`unzip -t` 通过，唯一条目为 69 字节 `0001.png`。`js-source.json` 的 `<js>result</js>` 正文规则也通过设备探针四阶段。两者仍是本地合成规则；Rhino 特殊 Unicode 转义标识符路径未覆盖。
- CBZ 下载改为先在应用缓存目录暂存并验证全部图片，再向系统文件写入 ZIP。API 23 上重装新版后再次下载合成漫画，194 字节 ZIP 校验通过，缓存目录无遗留暂存文件；离线测试还覆盖第二张图 HTTP 503 和暂存容量超限时，最终输出保持 0 字节。
- 同轮 `./gradlew test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --warning-mode all --console=plain` 通过，配置检查覆盖 14 个订阅。API 37 首页的状态栏边距、书源选择器可点击性及更紧凑的搜索结果由模拟器复核。

## 2026-09-27 工程整理回归

运行 `./gradlew test :source-catalog:checkCatalogConfig :app:assembleDebug :app:assembleRelease --offline --warning-mode all` 通过；联网补齐本机缺少的 Lint 依赖后，Release 构建也单独通过。配置校验覆盖仓库内 14 个订阅定义，不依赖被 Git 忽略的本地快照。新增反例覆盖超大订阅响应、HTTP 响应上限、最后一章抓取时取消不能进入导出，以及 `java.head` 的请求方法、请求头和不跟随跳转。

安装新版 Debug APK 后，模拟器 `emulator-5554` 的 MainActivity 正常启动。用 `test-fixtures/http/source.json` 在设备探针复测，依次得到 `SEARCH_OK hits=1`、`DETAIL_OK`、`CATALOG_OK chapters=2`、`CONTENT_OK`。这一轮只验证本地合成站点的单章路径；文件选择器的真实进程回收和完整导出 UI 仍以此前记录及离线测试为依据。

妙公子搜索引擎合集 6 条源的快照、规则回归、JVM/Android 实站边界见[专项记录](miaogongzi-search-engine-compatibility-20260927.md)。

2026-09-26，针对本仓库提交的 `test-fixtures/http/` 合成站点：

1. 使用项目自带 wrapper 执行 `./gradlew :source-core:test :export:test :app:assembleDebug --offline --console=plain`。核心规则、JS 基础函数、聚合隔离及 TXT/EPUB 结构测试通过；Debug APK 构建成功。
2. 在 `emulator-5554` 安装 Debug APK，并启动 `org.bookgetter.app/.MainActivity`。进程保持运行，界面控件正常出现。
3. 临时在开发机启动 `test-fixtures/http` 的 HTTP 服务；模拟器导入 `http://10.0.2.2:18765/source.json`，预检为 0 项；搜索返回 1 本 “Fixture Book”，详情目录显示 2 章。
4. 未勾选章节时导出 TXT 到设备 Downloads；读取文件确认包含 “Chapter One” 和 “Chapter Two”，且第一章的两段文本保持分行。
5. 再导出 EPUB；取回文件运行 `unzip -t`，确认 `mimetype`、`META-INF/container.xml`、两章 XHTML、`content.opf`、`toc.ncx` 均完整。临时 HTTP 服务的日志显示第一轮导出请求了 `/one`、`/two`，第二轮导出复用章节缓存。

以上证明合成站点的设备闭环；不证明任意第三方书源兼容。真实站点仍须分别验证登录、请求选项、规则方言、JS 宿主 API、WebView 和资源下载。`docs/compatibility.md` 是当前支持边界。

## 2026-09-27 补充验证

- `./gradlew :source-core:test :app:testDebugUnitTest :app:assembleDebug --offline` 通过。离线测试覆盖发现分类与分页、目录/正文登录页的阶段分类、脱敏请求轨迹、浏览器资源传输入口，以及任务与章节缓存的持久化和书源快照隔离。
- 在 `emulator-5554` 安装 Debug APK，确认“发现”“下一页”“继续上次”入口可见。使用 `test-fixtures/http/sniff-source.json` 经 `CompatibilityProbeActivity` 跑通 `SEARCH_OK → DETAIL_OK → CATALOG_OK → CONTENT_OK resources=1`；静态服务实际收到 `/sniff-play.html` 和 `/sniff-media.mp3` 请求。这只证明合成页面的 Android `sourceRegex` 资源监听，不证明真实媒体站点或可播放音频。
- 接入原版使用的繁简转换库和词组例外字典后，重新运行 `./gradlew :source-core:test :export:test :app:testDebugUnitTest :app:assembleDebug --offline`。验证 `java.t2s` 转换、资源监听未命中错误码、浏览器回调竞争保护，以及 Debug APK 打包成功。
- 第二轮缺陷修复运行 `./gradlew :source-core:test :source-runtime-android:testDebugUnitTest :export:test :app:testDebugUnitTest :app:assembleDebug --offline --quiet` 通过。新增反例覆盖目录超限、正文分页循环、搜索取消对子请求的中断、跨域资源请求头限制、WebView HTTP 错误分类、任务冲突保护和导出流关闭失败后的续跑保留。重新安装 Debug APK，重启 MainActivity 后确认“搜索”“发现”“下一页”“继续上次”入口可见。资源下载另用两个本地 HTTP 服务器验证跨端口 302 跳转：最终服务器收到文件请求，未收到 `Authorization`、`Cookie` 或自定义令牌头；Android CookieManager 的设备端行为仍需单独验证。

- 继续审查后，运行同一组离线测试与 Debug APK 构建并通过。验证同一章节在下一章边界、书籍变量或章节标题变化时不复用旧缓存，结构不完整的缓存触发重新抓取；TXT/EPUB 导出从缓存按章读取，TXT 第一章写出后才载入第二章；规则脚本无限循环可由线程中断终止，导出遇中断保留续跑任务；正文分页的第二页不重复应用首请求的 `sourceRegex`，仍保留 `webJs`。重新安装 APK 并重启模拟器应用，进程与四个主要入口正常。
- 再次审查后，运行 `./gradlew :source-core:test :source-runtime-android:testDebugUnitTest :export:test :app:testDebugUnitTest :app:assembleDebug --offline --quiet` 通过。新增离线反例验证：目录跳转别名不会重复处理同一最终页；正文下一页跳转到下一章时不会合并；HTTP 200 HTML 登录页不会写成图片资源；二进制资源前缀检查后仍完整写出；嵌套书源请求或持久化失败不报告导入成功；损坏的抓取任务不会被静默覆盖，备份后才允许新建。重新安装 APK 后，模拟器 MainActivity 正常启动并保持进程运行。以上均是离线边界测试，本轮没有新增真实站点通过记录。
- 本轮补充验证：顶层 URL 导入对 HTTP 404 即使正文是合法书源 JSON 也拒绝；两个不同 `SourceStore` 实例的并发导入在提交时合并，最终保留两份书源；请求返回后已取消的导入不提交；资源选择快照在序列化、Activity 重建恢复后保持书源 ID、规则哈希和资源地址。再次运行同一组离线测试及 Debug APK 构建通过，安装新版 APK 后模拟器首页正常启动。资源选择器的真实进程回收路径尚未进行设备自动化复测。
- 继续检查变量和缓存后，新增同一详情 URL、不同书源的 `@put/@get` 隔离反例，以及媒体缓存含非法地址的拒绝反例。官方 `AnalyzeRule.get` 缺值返回空字符串；核心现在不会把原始响应误当成缺失变量值。运行 `./gradlew :source-core:test :source-runtime-android:testDebugUnitTest :export:test :app:testDebugUnitTest :app:assembleDebug --offline --quiet` 通过。
- 增加“章节属于另一书籍”的正文请求与缓存写入反例，确认在请求发生前或缓存落盘前拒绝。重新运行上述全量离线测试与 Debug APK 构建通过。
- 本次五订阅更新后执行 `./gradlew test :app:assembleDebug :source-catalog:verifyCatalog --offline --quiet` 通过；订阅持久化、重复导入替换、失败更新不覆盖旧数据、RSS 与 BookSource 分流均有离线测试。JVM 实站探针结果见[五订阅专项记录](five-feed-compatibility-20260927.md)。

- 安装本次 Debug APK 到 `emulator-5554`，首页可见“更新订阅”；逐源 Debug 探针：ESJ Zone 搜索→首章通过，真白萌 403、再漫画详情业务错误，轻小说机翻两次 `EHOSTUNREACH`。细节见[五订阅专项记录](five-feed-compatibility-20260927.md)。

- 妙公子管理页适配：离线测试覆盖链接提取、选择持久化、链接变更跟踪及失败原子性；实站发现 11 个合集，指定合集解析为 6 条 BookSource。模拟器通过 URL 输入、弹窗选择、导入、重启和“更新订阅”操作，选择及书源数量持续保留。详见[五订阅记录](five-feed-compatibility-20260927.md)。

## 2026-10-22 面向嵌入的结构重组

使用本机 JDK 21 完成基线与迁移后验证。默认 JDK 17 会报 `invalid source release: 21`，构建时需正确设置 JAVA_HOME。

- `test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease` 全部成功。核心 255、目录工具 10、Android 运行层 31、App 14 个测试通过；Android 模块的 Debug/Release 变体均执行。上述计数不重复累计两种变体。lint 为 0 error、12 warning。
- 新增跨包 `EmbeddedWorkflowTest` 使用宿主内存存储和替身传输，验证导入、订阅更新、搜索、详情/目录、部分失败、重建实例续抓、缓存复用及导出完成后清理任务。
- 检查 Android runtime 依赖树：只依赖核心库及第三方库，没有 App、目录工具或已删除的 export 项目；核心源码没有 Android、AndroidX、OkHttp 或 App 引用。
- 在连接的 Android 16 设备上通过 `adb install -r` 更新调试包，未清除 App 数据。RSS 网页桥本地探针结果 `SUMMARY pass=24 fail=0 skip=0`，覆盖注入顺序、frame/origin/token 隔离、有序状态、排队动作关闭、正则与请求取消；不覆盖已经分发给 Activity 的 UI 回调。
- 书源本地夹具通过 SEARCH_OK、DETAIL_OK、CATALOG_OK（2 章）、CONTENT_OK。设备使用 adb reverse，临时副本将模拟器地址替换为 127.0.0.1，仓库夹具保持原样。
- RSS XML 夹具返回 1 篇并通过 summary 正文；自定义规则夹具返回 2 篇并合并两页正文，均输出 RSS_PROBE_DONE。

本轮未访问真实第三方书源，未验证 Chromium/GN 构建，也未新增真实站点兼容性结论。设备流程未执行系统文件选择器的人工导出；导出和续抓由离线测试覆盖。
