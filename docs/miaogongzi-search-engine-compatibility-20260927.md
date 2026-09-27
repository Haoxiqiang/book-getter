# 妙公子“搜索引擎通用”合集兼容记录（2026-09-27）

这份记录针对妙公子管理页中的“不世玄奇搜索引擎书源”合集。合集 JSON 地址为 `https://cdn.miaogongzi.cc/shuyuan/%E6%90%9C%E7%B4%A2%E5%BC%95%E6%93%8E%E9%80%9A%E7%94%A8.json`；本次原始响应 SHA-256 为 `fe97cefe0ea75994b597c8cff3ab9e8a6fbf2ef47be791769e8d83f3802e36c9`，共 6 条不同的 BookSource。本地原始文件和探针日志位于 Git 忽略的 `downloads/probes/`；临时书源目录快照 `20260927-070007-0d177d9e` 完成了导入、去重和 `verifyCatalog`。这些书源的 `bookSourceUrl` 是名称型稳定标识，并非可请求的 HTTP 地址；只有规则展开后的请求 URL 才用于网络访问。

## 分阶段结果

关键词为“斗破苍穹”；只尝试首条详情，未读取目录、正文或整书。每条结果都依赖当时的网络与站点页面。

| 书源 | 搜索观察 | 后续边界 |
| --- | --- | --- |
| 谷歌搜书 | 能规划并请求搜索 URL；后续请求 TLS 握手被远端关闭 | 未得到有效列表，不能判作零命中 |
| 必应搜书 | 搜索返回 9 条候选；JVM 与 Android Debug 探针一致 | 首条指向百度百科，详情为 HTTP 200 挑战页，明确报 `CHALLENGE_PAGE`；未到目录 |
| 搜狗搜书 | 请求跳转到 QQ 浏览器搜索页，解析为 0 条 | 探针标记 `RESPONSE_SUSPECT`，不能算有效零结果或规则通过 |
| 夸克搜书 | 搜索页 HTTP 200，但页面没有规则所需的 `c-header-inner` 节点 | 修复宿主方法后，原规则的空列表详情回退仍得不到书籍；探针显示 `DIRECT_DETAIL_EMPTY_LIST` 和未验证的 0 条。属于当前页面与原选择器不符 |
| 百度搜书 | 搜索跳转到百度安全验证页 | HTTP 200 验证页被识别为 `CHALLENGE_PAGE`，未进入有效列表 |
| 通用书源 | 该条没有搜索 URL，入口是发现分类 | 首个发现地址 `m.niliuxs.org/sort-1-1/` 返回 HTTP 502；不能据此判断其他分类或详情规则 |

**结论：**6 条均可解析并保存，但本轮只有必应得到真实候选列表，且没有任何一条完成搜索→详情→目录→正文闭环。HTTP 200、空列表和导入成功均不能推导为可读小说。

## 代码兼容与验证

本次针对源中实际出现的语法，补了 `{{...}}` 内单层 JS 花括号、`<...>` 页码选择、模板内请求选项边界和 URL UTF-8 转义；源 ID 目录允许名称型 `bookSourceUrl`。受控 Rhino 宿主补了 `src`、部分 `java.getStringList`、书籍变量与 `book.setReverseToc`，并将目录倒序标志应用到章节结果。Jsoup 安全视图补了节点 `remove()` 和集合 `eachText()`，保留对原 DOM 的删除效果；不开放任意 Java 类。百度搜索与百科的挑战页有明确诊断，嵌套 `java.ajax` 遇挑战页立即停止，避免规则脚本重复请求验证码。`getStringList` 和其他复杂宿主方法仍按部分兼容预检，不能据此宣布完整 Legado 等价。

确定性回归覆盖模板规划、宿主方法和作用域隔离、DOM 删除与逐项文本、章节倒序、挑战页识别、名称型 ID；完整 `test`、Android Debug APK 构建及 `verifyCatalog` 在本次修改后通过。Android Debug 的必应探针得到 9 条候选且首条详情同样被挑战页阻断。其余 5 条只做 JVM 网络探针，未声称跨端实站通过。

复测时可运行：

```sh
./gradlew test :app:assembleDebug :source-catalog:verifyCatalog --offline
./gradlew :source-core:liveSourceProbe \
  -PprobeJsonUrl='https://cdn.miaogongzi.cc/shuyuan/%E6%90%9C%E7%B4%A2%E5%BC%95%E6%93%8E%E9%80%9A%E7%94%A8.json' \
  -PprobeQueries='斗破苍穹' -PprobeCaptureFirst=true
```

“通用书源”应单独使用 `-PprobeSource='=通用书源' -PprobeExploreFirst=true` 测发现入口。网络探针会访问第三方站点，不在自动单元测试中运行；遇验证页不执行验证码或登录操作。
