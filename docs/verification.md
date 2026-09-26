# 验证记录

2026-09-26，针对本仓库提交的 `test-fixtures/http/` 合成站点：

1. 使用项目自带 wrapper 执行 `./gradlew :source-core:test :export:test :app:assembleDebug --offline --console=plain`。核心规则、JS 基础函数、聚合隔离及 TXT/EPUB 结构测试通过；Debug APK 构建成功。
2. 在 `emulator-5554` 安装 Debug APK，并启动 `org.bookgetter.app/.MainActivity`。进程保持运行，界面控件正常出现。
3. 临时在开发机启动 `test-fixtures/http` 的 HTTP 服务；模拟器导入 `http://10.0.2.2:18765/source.json`，预检为 0 项；搜索返回 1 本 “Fixture Book”，详情目录显示 2 章。
4. 未勾选章节时导出 TXT 到设备 Downloads；读取文件确认包含 “Chapter One” 和 “Chapter Two”，且第一章的两段文本保持分行。
5. 再导出 EPUB；取回文件运行 `unzip -t`，确认 `mimetype`、`META-INF/container.xml`、两章 XHTML、`content.opf`、`toc.ncx` 均完整。临时 HTTP 服务的日志显示第一轮导出请求了 `/one`、`/two`，第二轮导出复用章节缓存。

以上证明合成站点的设备闭环；不证明任意第三方书源兼容。真实站点仍须分别验证登录、请求选项、规则方言、JS 宿主 API、WebView 和资源下载。`docs/compatibility.md` 是当前支持边界。
