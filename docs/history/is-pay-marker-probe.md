# 目录 `isPay` 与 `isVip` 分离：晋江样本（2026-09-27）

目标是验证目录规则保留两个**不同布尔标记**，不进行购买、不抓取付费正文或整书。已知样本来自 [AOAOSTAR 关耳女频 86 源集合](https://legado.aoaostar.com/sources/e3e5d620.json)，原始 JSON SHA-256 `10537479695e9168c63ad3225463124c47bb3730525f6142d9c1ff4ad0139627`。这批集合里只有“晋江文学”的 `ruleToc.isPay` 非空；其他已检的 128 源与 22 源集合没有该字段。原始第三方规则仍只在探针中读取，不并入主书源目录。

官方 `BookChapterList.kt` 分别执行 `isVip`、`isPay` 规则，并把非假值存到 `BookChapter.isVip/isPay`；两者不能合并。本工程此前 `Chapter` 只有 `vip`，会丢失 `isPay`。Java 模型现独立保存 `isPay`，常规 `ruleToc` 和 `mainJs.getChapters` 两条目录路径均映射；原六参数构造仍默认为 `false`。JVM/Android 调试目录统计新增 `isPay=`，不把它命名为“付费章数”。

晋江书源的实际规则为 `$..point<js>result!=0?false:true</js>`，即 `point=0` 时 `isPay=true`，并不等同于 `isVip=true`。合成回归直接用这条表达式与两项 JSON 目录：`point=0/isvip=0` 得 `isPay=true/vip=false`，`point=5/isvip=1` 得 `isPay=false/vip=true`。Android 模拟器又通过一次性本机合成 HTTP 服务完整跑过搜索→详情→两项目录，日志为 `chapters=2 vip=1 isPay=1`；服务仅返回合成 JSON，已停止，不含第三方内容。

为核对规则字段的真实数据形态，只读请求了[晋江官方作品页《兼职无常后我红了》](https://www.jjwxc.net/onebook.php?novelid=4398357)对应的公开 `chapterList?novelId=4398357&more=0&whole=1` API。当前响应 `chapterlist` 105 项，其中 `isvip` 非零 90 项、`point=0` 15 项、`point` 非零 90 项，字段为数值型。这与该源规则可能产生 `isPay=15`、`vip=90` 的方向一致，但**不是原书源目录在工程中跑通的证据**：原搜索 API 对“故事”返回业务码 `1055`（没有更多小说），目录规则还依赖 Cookie、章节 URL 宿主及可触发购买的脚本分支。没有提供登录态，也没有运行自动购买、正文或解密动作。后续若要声称真实晋江书源兼容，必须在有权限的环境逐阶段复测原规则，并确认副作用分支不会被探针误触发。

复测与只读核对：

```sh
./gradlew :source-core:test :export:test :app:assembleDebug --offline
curl -fsSL --max-time 20 'https://app-cdn.jjwxc.net/androidapi/chapterList?novelId=4398357&more=0&whole=1' | jq '{count:(.chapterlist|length), vip:([.chapterlist[]|select((.isvip|tostring)!="0")]|length), pointZero:([.chapterlist[]|select((.point|tonumber?)==0)]|length)}'
```

本轮同时纠正了调试 URL 摘要在非默认端口下省略端口的问题；现在保留 `host:port/path`，仍隐藏查询串、片段和 URL 选项值。只验证了合成跨端链路与公开 API 字段分布，不计入真实晋江站点目录通过或整书验收。
