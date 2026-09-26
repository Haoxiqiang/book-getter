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

## 补充：已知存在作品的搜索入口（同日）

用上面详情页对应的完整书名对原始书源执行正常搜索（**不再使用指定详情 URL**）：JVM 探针现在逐跳记录 `REDIRECT POST 301 www.sudugu.co/modules/article/search.php → www.sudugu.cc/modules/article/search.php`，最终为 `REDIRECT_FINAL GET 200`，收到 3096 字符的“小说搜索”表单页，0 命中并标 `RESPONSE_SUSPECT`。本地合成 301 回归也确认 JDK `HttpClient.Redirect.NORMAL` 在该路径将 POST 改为 GET。Android 模拟器记录原始 POST、跨主机最终 200 搜索表单、0 命中并标 `SEARCH_RESPONSE_SUSPECT`；Android 探针尚未逐跳记录最终方法，因此**只对 JVM**确认 POST→GET 的具体变化。

直接对 `.cc` 同路径 POST 同一完整书名和原规则的 `action=login&searchtype=all&submit=` 字段，则收到 **302 到已知详情页 `/1372/`**。因此现站搜索接口对该词可用，旧域 301 是原始书源当前不能有效搜索的具体原因，不是 CSS 列表解析失败。原始规则与书源标识仍保持不变；逐跳诊断只打印方法、状态、主机与路径，不主动打印查询参数、Cookie 或正文。路径仍可能含敏感标识，外发探针日志前需审阅。

正常搜索复测：

```sh
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl=https://legado.aoaostar.com/sources/71e56d4f.json \
  '-PprobeSource=速读谷' '-PprobeQueries=假太监：我乃大明九千岁' --offline
```

结论须分阶段书写：**搜索未通过；显式提供现站详情 URL 后，详情和四页目录通过**。不能把后者倒推为原始书源已具备完整搜索→阅读链路。
