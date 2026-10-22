# 目录规则中的 DOM 节点 JS 宿主

2026-09-27，主书源管理目录快照 `20260926-170836-f658eae9`，去重文件 SHA-256 `a38d14210242819454c9c0b741335a2892bdbfc0150f7d674e99a9e3a2bd86ed`；其中 rezero 原始单源 JSON SHA-256 `e095f80e2c38e9d16de3f8fc29a707be3a77aacbc2efe1bf8f45caec0dcdb759`，与当日 [公开单源地址](https://source.jwyihao.top/rezero.json) 相同。关键词 `RE0`，只验证搜索、详情及公开目录，未抓正文或整书。

## 失败点与官方语义

主源目录 `ruleToc.chapterList = a[href^="markdown/ch/"]` 产出 DOM 节点，`isVolume = @js:result.attr('href').endsWith('index.html')`。修正前本工程把节点 `toString()` 后作为 JS 的 `result`，所以搜索 1 条、详情 HTTP 200 后停在 `CATALOG.ruleToc.isVolume`：字符串没有 `attr()`。此前文档所述“887 章目录通过”不能沿用为这个版本的当前状态。

官方 [AnalyzeRule.evalJS](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 直接将上一阶段 `result` 放进脚本绑定；[AnalyzeByJSoup.getElements](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt) 返回元素而非强制转字符串。现工程只对 jsoup `Element` 建立**受控 JS 视图**：`attr/hasAttr/text/ownText/html/outerHtml/toString`，不暴露真实 Java 类或任意方法；其他类型的绑定仍沿原路径。合成 Java 回归验证节点属性、卷/章节判定、`String(result)` 和 `result.getClass` 不可见。此视图不是完整 jsoup API；补充源“百里中文”的 `result.select('em').hasClass('vip')` 仍未实现且该站当前首页 HTTP 404，不能标为兼容通过。

## 实站复测

同源同词重跑：JVM 搜索 1、详情 HTTP 200、目录 HTTP 200，解析 **910 条 = 23 个卷标题 + 887 个普通章节**，VIP 0。Android 模拟器同样搜索 1、详情/目录 HTTP 200，输出 910 条、23 卷标题、首个卷标题使用稳定占位 URL。独立从站点公开目录 HTML 抽取 `href="markdown/ch/..."`，按是否以 `index.html` 结尾计数也是 **23 + 887 = 910**。这只证明目录节点类型与卷判定在该样本一致，不证明后续正文质量或完整书下载。

JVM 复测：

```sh
./gradlew :source-core:liveSourceProbe \
  '-PprobeSource=re0zero.top' -PprobeQueries=RE0 \
  -PprobeCatalogFirst=true --offline
```

Android Debug 探针：`catalogUrl=https://source.jwyihao.top/rezero.json`、`sourceFilter=re0zero.top`、`keyword=RE0`、`catalogFirst=true`。原始书源仍由独立目录管理，未提交源 JSON 或书籍文本。

后续需要按真实规则扩展受控节点方法（如 `select/hasClass`）、测试节点列表与 JS 多段规则，并找另一可达站交叉验证；不能把一个节点方法的正对照推成整个 JS 宿主等价。
