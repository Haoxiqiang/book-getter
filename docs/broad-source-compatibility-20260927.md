# 指定订阅与妙公子合集扩大审查（2026-09-27）

## 范围与证据

本轮覆盖用户指定的 5 个订阅 URL（妙公子 RSS、`fishhawk.json`、`masiro.json`、`esjzone.json`、`zaimanhua.json`），以及妙公子“不世玄奇搜索引擎书源”合集的 6 条 BookSource。主目录还含另外 5 个普通书源，用于验证更新的完整性，不把它们计入本轮逐站结论。

`source-catalog:updateCatalog` 重新下载了全部 10 个配置文件，`verifyCatalog` 通过；当前快照为 `20260927-071153-4cb1c905`，11 条唯一普通书源、1 条单独保存的 RSS 配置。指定 5 个 URL 的原始 SHA-256 与前一快照相同。妙公子下游合集原始 JSON SHA-256 为 `fe97cefe0ea75994b597c8cff3ab9e8a6fbf2ef47be791769e8d83f3802e36c9`；这 6 条的导入和实站细节另见[合集专项记录](miaogongzi-search-engine-compatibility-20260927.md)。原始响应与完整探针日志保存在 Git 忽略的本机 `downloads/probes/`，不随仓库分发。

## 实站阶段

普通书源使用关键词“无职转生”“魔法”，JVM 探针只读取每次搜索的首条详情、目录和首个可读章节；未抓取整书。数字表示本次响应，不保证站点持续可用。

| 订阅 | 搜索 | 最深成功阶段或阻断 |
| --- | --- | --- |
| 妙公子 RSS | 不参与小说搜索 | 管理页适配器仍能发现 11 个合集；选定合集导入 6 条普通书源，RSS 自身没有小说正文运行时 |
| `fishhawk.json`（轻小说机翻🤖） | 两词各 20 条 | 两条首项均通过详情、目录（23／158 项）及首章（可见 7619／3076 字）；Android 模拟器本轮仍在该站搜索请求遇 `EHOSTUNREACH` |
| `masiro.json`（真白萌） | 两词均 HTTP 403 | 未得到有效搜索列表；登录或站点限制，不能评估后续规则 |
| `esjzone.json`（ESJ Zone） | “魔法”40 条；“无职转生”请求超时 | “魔法”首项详情、32 项目录和首章可见 5027 字通过；Android Debug 同词同首项也通过，数量一致 |
| `zaimanhua.json`（再漫画） | 两词分别 10／24 条 | 首项详情 API 均为 HTTP 200 的业务错误；原规则与当前站点路径漂移，未到目录 |

妙公子 6 条：必应搜索 9 条但首条详情遇验证页；谷歌 TLS 关闭；搜狗跳到 QQ 浏览器搜索页且 0 条可疑；夸克当前页缺原选择器节点且空列表回退无结果；百度进入安全验证；通用书源首个发现站点 HTTP 502。没有一条走通正文。本轮对这 6 条只做 JVM 网络复测；此前必应的 Android Debug 探针搜索 9 条且详情同被验证页阻断。

## 扩大审查后修复的缺陷

1. 合集中的单条字段类型错误原先可能使整批导入抛异常。现在将该条标为 `INVALID_ENTRY`，保留相邻有效条目；`sourceUrls` 结构、URL 主机、凭据与片段由导入阶段明确验证。
2. 订阅更新会按新清单替换旧书源。若新响应仅部分解析成功，旧源原先可能被静默删掉；现在“更新订阅”和同 URL 再导入遇导入错误均拒绝提交，保留旧书源和订阅归属。用含有效和无效条目的更新响应做了回归。
3. 目录或正文误用别的书源的书籍时，错误原先统一标成详情阶段；现在分别报告 `CATALOG`、`CONTENT` 的 `SOURCE_MISMATCH`，且不发请求。
4. 首章请求若直接跳到已知下一章，原先会把下一章正文归入本章；现在首个响应也检查下一章边界并报 `PAGINATION_BOUNDARY`。仍允许首章地址合法地等于书籍页。

相关离线回归位于 `SourceImporterTest`、`SourceStoreTest` 与 `SourceEngineTest`。`./gradlew test :app:assembleDebug :source-catalog:verifyCatalog --offline --quiet` 通过，Android Debug APK 安装成功。站点验证页、403、超时和路径漂移没有通过改写原始书源或绕过访问限制来伪装成功。

复测命令：

```sh
./gradlew :source-catalog:updateCatalog :source-catalog:verifyCatalog --offline
./gradlew :source-core:liveSourceProbe -PprobeSource='轻小说机翻🤖,真白萌,ESJ Zone,再漫画' \
  -PprobeQueries='无职转生,魔法' -PprobeCaptureFirst=true --offline
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl='https://cdn.miaogongzi.cc/shuyuan/%E6%90%9C%E7%B4%A2%E5%BC%95%E6%93%8E%E9%80%9A%E7%94%A8.json' \
  -PprobeSource='谷歌搜书,必应搜书,搜狗搜书,夸克搜书,百度搜书' \
  -PprobeQueries='斗破苍穹' -PprobeCaptureFirst=true --offline
```

“通用书源”没有搜索 URL，须使用 `-PprobeSource='=通用书源' -PprobeExploreFirst=true` 单独测试发现入口。网络探针不属于自动单元测试。
