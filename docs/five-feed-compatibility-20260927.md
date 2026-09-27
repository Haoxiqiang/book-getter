# 用户指定的五个订阅：2026-09-27 实测

配置在 [`source-catalog/feeds.json`](../source-catalog/feeds.json)。执行 `./gradlew :source-catalog:updateCatalog :source-catalog:verifyCatalog` 后，当前快照为 `20260927-061547-ab30ed05`。5 个指定 URL 均下载成功；原始文件存于 Git 忽略的 `source-catalog/snapshots/<CURRENT>/raw/`，索引记录 URL、SHA-256、条目数和类型。CLI 可再次更新并原子切换快照。App 中通过“URL 导入”的小说书源会保存该订阅 URL，可用“更新订阅”重新下载；妙公子 RSS 配置仍保留在目录快照；App 另对其书源管理页提供选合集适配，导入的是用户选中的下游 BookSource，RSS 自身不参加小说搜索。

| 订阅文件 | 类型及条目 | 搜索、详情、目录、首章正文（JVM 实站） | 结论 |
| --- | --- | --- | --- |
| `miaogongziDY.json` | RSS 1 条，字段 `sourceUrl` | 不适用小说搜索 | 原始 JSON 下载、结构、哈希及快照回读通过；App 可从管理页选择下游书源合集，未实现通用 RSS 阅读 |
| `fishhawk.json` | BookSource 1 条 | “无职转生”搜索 20、首条详情、23 章目录、首章可见 7619 字符；“魔法”搜索 20、首条详情、272 项目录、首章可见 674 字符 | 两条搜索路径均走通首章；`downloadUrls` 可选规则有字段告警，未验证全书/直链下载 |
| `esjzone.json` | BookSource 1 条 | “魔法”搜索 40、首条详情、32 项目录、首章可见 5027 字符；“无职转生”搜索 0，响应有效但零结果未独立验证 | 一条搜索路径走通首章；未验证全书 |
| `masiro.json` | BookSource 1 条 | 两词搜索均 302 到登录页，最终 HTTP 403 挑战页 | 当前网络/登录前提阻断，不能判定规则兼容性 |
| `zaimanhua.json` | BookSource 1 条 | 两词搜索分别 10、24 条；首条详情请求返回 HTTP 200、业务码错误 | 搜索可用，原始规则已与现站路径漂移；未通过详情及后续阶段 |

再漫画当前搜索页首条链接是 `/details/58227`，原始 `ruleSearch.bookUrl` 却匹配 `info.(\\w+).html` 并将捕获值作为 `comic_py` 请求旧详情 API。该 API 对空 `comic_py` 返回“漫画不存在”。这属于上游规则与当前站点不匹配；不能通过放宽解析器把错误页当作有效详情。

复测命令（同一快照，探针只读取首个可读章节，不下载整书）：

```sh
./gradlew :source-catalog:updateCatalog :source-catalog:verifyCatalog
./gradlew :source-core:liveSourceProbe -PprobeSource='轻小说机翻🤖,真白萌,ESJ Zone,再漫画' -PprobeQueries='无职转生,魔法' -PprobeCaptureFirst=true
```

原始探针日志在本机 `downloads/probes/five-feeds-20260927.log`（Git 忽略）。Android 模拟器使用 Debug `CompatibilityProbeActivity` 和相同的上游单源 URL 复测：ESJ Zone“魔法”搜索 40、详情、32 项目录、首章可见 5027 字符，和 JVM 一致；真白萌“魔法”在搜索 HTTP 403；再漫画“无职转生”搜索 10 后在详情 API `BUSINESS_ERROR`；轻小说机翻的两个词均在搜索请求阶段遇到 `EHOSTUNREACH`，因此设备端没有得到有效规则结果。这是模拟器到该站的网络障碍，不能用 JVM 成功代替 Android 成功。RSS 本身不进入小说引擎。本轮没有进行整书导出。站点、登录和规则会变化，复测结果以当次快照和日期为准。

## 妙公子管理页适配补测

原始 RSS 规则为 `id.content@h3`，当前页面主要使用 `h1` 与 `yuedu://booksource/importonline?src=` 链接。专用适配器只识别该管理页及明确的导入链接，拒绝非 HTTP(S) 下游地址；页面结构不匹配或选择的合集消失时更新失败且保留旧数据。

`./gradlew :source-core:probeMiaogongziDirectory --offline` 实站发现 11 个合集；指定 `-PdirectoryCollection='不世玄奇搜索引擎书源'` 后，下载并解析出 6 条 BookSource。Android 模拟器中输入原始妙公子 JSON URL，弹出 11 个合集；选择该合集并导入后保存了 6 条来源，重启 App 后订阅选择仍在，点击“更新订阅”成功。设备既有书源存在，因此 UI 总数为 18，不代表该合集有 18 条。此验证仅到合集导入与更新，未声称其下游各站搜索/正文通过。

下游 6 条书源的规则能力、逐站网络结果与跨端边界另见[搜索引擎合集兼容记录](miaogongzi-search-engine-compatibility-20260927.md)。
