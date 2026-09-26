# 速读谷：详情可选字段与四页目录

2026-09-27，补充样本取自 [AOAOSTAR 22 源集合](https://legado.aoaostar.com/sources/71e56d4f.json)，本次 SHA-256 为 `24fe11c400ddc8754703ff0c4d8a249baf024da3a15563de805cc134140959a0`。该集合仅供诊断，不并入主书源目录，也不提交原始 JSON。书源标识仍是 `https://www.sudugu.co`，现站首页转到 `https://www.sudugu.cc/`；本轮从现站首页公开链接 `https://www.sudugu.cc/1372/` **直接起跑详情**，没有声称原书源搜索已恢复。

## 首个失败阶段与修正

修正前：指定详情页 HTTP 200，但 `ruleBookInfo.wordCount` 的 `@js` 表达式访问 `result.textContent` 抛异常，整个详情失败，目录规则未执行。官方 [BookInfo.kt](../../app/src/main/java/io/legado/app/model/webBook/BookInfo.kt) 对分类、字数、最新章、简介、封面分别捕获异常；书名、作者与目录地址仍应显式失败。本工程此前仅对**搜索**可选字段隔离异常，现将同样边界应用到详情，并输出 `FIELD_WARNING ruleBookInfo.wordCount`。没有改写第三方字数规则或伪造字数。合成 Java 回归断言坏字数脚本不遮蔽有效的详情书名与目录地址。

修正后：JVM 与 Android 模拟器均以 HTTP 200 取得详情页，报告相同字段告警、详情通过；目录依书源 `id.list.0@tag.a` 与 `@css:#pages .gr:not([href='#'])@href` 访问首页及 `/1372/2`、`/1372/3`、`/1372/4` 四页，均 HTTP 200，去重后 **3292 个章节 URL**、VIP 标记 0、首条 URL 同为 `https://www.sudugu.cc/1372/1147899.html`。独立用四页公开 HTML 的 `https://www.sudugu.cc/1372/<数字>.html` 链接去重计数也是 3292；这只验证 URL 数量，与正文可达、目录顺序、VIP 真实性、作品完整性并非同一命题。合成 Java 回归还覆盖下一页选择器、重复章节 URL 去重和分页停止。

JVM 复测命令：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json \
  '-PprobeSource=速读谷' \
  -PprobeBookUrl=https://www.sudugu.cc/1372/ \
  -PprobeCatalogFirst=true --offline
```

Android Debug 探针用同一集合 URL，`sourceFilter=sudugu.co`、`keyword=DIRECT_BOOK`、`directBookUrl=https://www.sudugu.cc/1372/`、`catalogFirst=true`。两端均打印 `SEARCH_SKIPPED_DIRECT_BOOK`；不向旧搜索端点发送请求，也不抓首章或整书。独立数量核对只提取章节链接，没有保存正文。

尚需使用正常搜索命中的第二个分页目录书源交叉验证，并检查更多页码格式、循环/跨书链接、目录顺序和正文边界。本样本不能计入 100 本整书验收。
