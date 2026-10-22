# 引擎兼容开发

对一个差异，先固定模型、源 ID/快照、规则字段、触发入口、最小输入及预期输出，再沿官方 `AnalyzeUrl` / `AnalyzeRule` / `WebBook` 或 RSS 调用路径确认行为。共同语义以[规则契约](../../../docs/legado-rule-contract.md)为入口；RSS 的列表、阅读目标和网页桥按[独立边界](../../../docs/rss-compatibility.md)验证。

## 修改落点

- `source-core` 的 `org.bookgetter.core`：请求规划、规则求值、JS 宿主与普通/RSS 引擎；`org.bookgetter.business`：导入事务、订阅、缓存及续抓；`org.bookgetter.export`：TXT/EPUB。
- `source-runtime-android`：默认 HTTP、Cookie、SharedPreferences、WebView 和资源下载实现。平台替换使用已有宿主接口，具体组装见[嵌入指南](../../../docs/embedding.md)。
- `app`：界面、系统文件选择器、生命周期与调试探针。不要在 Activity、导出器或目录工具中加站点特例来掩盖规则问题。

阶段错误保留 `SourceFailure` 的 stage/sourceId/field/code，`StageTrace` 记录脱敏最终请求和响应质量。新宿主方法使用受控 facade；不为修复兼容而开放 Java 反射、取消 Rhino 截止时间或降低网页桥来源隔离。

## 回归与记录

在对应实现模块补脱敏离线正反例，断言实际请求、状态隔离和输出，不仅断言方法存在。普通书源和 RSS 同 ID 仍须隔离；正文分页应传入已知下一章边界。平台行为要单独做 Android 夹具或官方差分，不能从 JVM 通过外推。

验证及归因按[统一口径](verification.md)。纯结构重组或离线修复写入[工程验证记录](../../../docs/verification.md)，不向历史实站记录追加“站点已通过”。只有实现支持范围变化时更新[能力矩阵](../../../docs/compatibility.md)，后续缺口记入[待办](../../../docs/remaining-work.md)。
