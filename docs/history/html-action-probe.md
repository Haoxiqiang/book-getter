# `@html` 外层节点与脚本清理兼容验证

2026-09-27，对照官方 [AnalyzeByJSoup.kt](../../../legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt) 的 `getResultLast("html")`：先从所选元素移除 `script` 和 `style` 后返回**外层 HTML**；`all` 则返回未清理的外层 HTML。本工程原先用 jsoup 的 `element.html()`，只返回内层 HTML，语义不同。现在在副本上去脚本/样式并返回 `outerHtml()`，不改原节点；合成 Java 回归检查容器保留、脚本/样式消失且 `@all` 仍保留原标记。

真实正对照为 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)中的“抖音小说”（集合 SHA-256 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`），规则 `ruleContent.content = id.content@html`。同词“斗破苍穹”、同一首条作品及章节：搜索 20 条、详情成功、目录 112 章，首章页面 HTTP 200。修复前 JVM 提取原始 1095 字符、可见文本 927 字符；修复后 JVM 与 Android 模拟器均为原始 1169 字符、可见文本仍为 **927 字符**。新增的 74 字符来自外层结构，未吞掉这章可见文本；无正文或完整书导出。

这只验证一个公开章节的规则动作与跨运行时一致性，不证明目录完整（该样本首个目录标题已是第 607 章）、后续章节可达、所有 script/style 页面形态或文本净化质量。尤其不能把 `CONTENT_OK` 和字符数当作 100 本完整下载验收。原始第三方书源保留原样、未并入主书源目录。

JVM 复测：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json \
  '-PprobeSource=抖音小说' -PprobeQueries=斗破苍穹 \
  -PprobeCaptureFirst=true --offline
```

Android Debug 探针使用同一集合 URL、`sourceFilter=douyinxs.com`、`keyword=斗破苍穹`、`captureFirst=true`，日志仅报告阶段和原始/可见字符数，不保存正文。
