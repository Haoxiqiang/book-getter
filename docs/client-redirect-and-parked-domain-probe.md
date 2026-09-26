# 跨站响应筛查：客户端跳转与域名出售页（2026-09-27）

本轮继续以**不同站点的首个失败阶段**寻找兼容缺口，不抓正文或整书。样本是 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)，原始集合 SHA-256 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`；只在内存导入，不提交第三方完整书源。JVM `liveSourceProbe` 每站只搜一次“斗破苍穹”，请求间隔 1.5 秒。两组共 24 条书源，其中第一组有数条在先前记录已出现，**不能当作 24 个全新站点**；本记录的价值在阶段和响应差异。实时网络状态可能变化。

第一组：读书阁、文趣阁、笔书阁❷、燃文小说③、顶点小说③、全本小说①、笔趣阁①、69书吧①、书趣阁、选书网、小说部落、鬼吹灯。结果：5 条 HTTP 403（读书阁、燃文③、全本①、笔趣阁①、小说部落）；3 条 EOF（文趣阁、笔书阁❷、鬼吹灯）；书趣阁与选书网 TLS 握手关闭，顶点③证书域名不匹配；69书吧①从旧域 `POST /modules/article/search.php` 经 301 到新域，最终变为 `GET`，HTTP 200 只有搜索表单样式内容、0 条，标为 `RESPONSE_SUSPECT`。这不是解析器已验证的空搜索；不擅自把上游 POST 规则改向新域。

第二组：笔趣阁⑨/⑮/⑯/⑲/㉔/㉖、零零小说、四三中文、乐文小说①、顶点小说④、全免小说、香书小说。结果：笔趣阁⑮两次跨域 301 后 HTTP 403；笔趣阁⑯/㉔/㉖、乐文①、顶点④直接 HTTP 403；笔趣阁⑲ EOF；笔趣阁⑨和零零小说 TLS 握手关闭，香书小说证书过期。其余两条的 HTTP 200 暴露了假零结果：

| 书源 | 原始响应和独立核对 | 旧诊断 → 现诊断 |
| --- | --- | --- |
| 四三中文 | 搜索端点 HTTP 200、176 字节，只有 `window.onload` 内的 `window.location.href="/lander?..."`，没有搜索列表 | `ZERO_UNVERIFIED` → `CLIENT_SIDE_REDIRECT`，在 `SEARCH.searchUrl` 报不可用响应；不自动执行站点 JS，也不称规则通过 |
| 全免小说 | 搜索端点 HTTP 200、约 1.7 KB；标题为域名，页面明确有 `[...] is for sale` 与“域名 [...] 正在出售中” | `ZERO_UNVERIFIED` → `PARKED_DOMAIN`，在 `SEARCH.searchUrl` 报不可用响应；不称书源确实无书 |

两种识别只针对明确的小型 JS 跳转页和出售页文案；`ResponseSignals` 是保守诊断，不替代书源规则或浏览器。已加合成 Java 回归，真实两站复测得到相同分类。四三中文的 `/lander` 目标没有继续请求，避免把其未知跳转内容冒充搜索结果。

复测命令：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=读书阁,文趣阁,笔书阁❷,燃文小说③,顶点小说③,全本小说①,笔趣阁①,69书吧①,书趣阁,选书网,小说部落,鬼吹灯' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=笔趣阁⑨,笔趣阁⑮,笔趣阁⑯,笔趣阁⑲,笔趣阁㉔,笔趣阁㉖,零零小说,四三中文,乐文小说①,顶点小说④,全免小说,香书小说' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:test :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=四三中文,全免小说' '-PprobeQueries=斗破苍穹' --offline
```

本轮没有进入详情/目录，因为没有可信的非空搜索结果。大量老域名的 `403`/TLS/EOF 说明继续盲抽同类源的边际收益很低；下一轮宜优先寻找**可达且规则结构不同**的来源或在已可达源中触发尚未覆盖的规则机制，再用相同阶段门槛验证。
