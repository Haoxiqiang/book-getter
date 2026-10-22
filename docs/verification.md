# 验证指南

本页维护可重复入口与最近执行范围。日期、版本、测试数量和设备结果的原始记录集中在[工程验证历史](history/verification-records.md)，真实站点观察集中在[历史证据索引](history/README.md)。当前支持声明见[能力矩阵](compatibility.md)。

## 构建与离线回归

使用 JDK 21；默认 JDK 17 会报 `invalid source release: 21`。首次构建需具备 Android SDK 36 和依赖下载条件，只有本地缓存完整时才加 `--offline`。

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :source-catalog:checkCatalogConfig :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

JAVA_HOME 示例对应本机路径，其他机器使用自己的 JDK 21。运行时库为 `source-core` 与 `source-runtime-android`；导出测试已合入核心，没有独立 `:export` 任务。当前 CI 执行上述全量检查。

- 核心：规则、请求、源状态、RSS 和 `org.bookgetter.business` 订阅/缓存/续抓回归；`EmbeddedWorkflowTest` 从外部包用宿主存储和替身传输覆盖完整业务接口。
- Android 适配：HTTP、资源下载/ZIP、重定向、网页策略与线性正则的本地测试。
- 调试 App：UI 任务所有权、文件选择器状态、展示与恢复；目录工具：订阅配置与 URI 校验。

测试报告位于各模块 `build/reports/tests/`，lint 报告位于 `app/build/reports/lint-results-debug.html`。JVM 通过不能证明 API 23 或 WebView provider 兼容，Debug/Release 重复用例不累计为独立覆盖。

## 本地设备夹具

[夹具说明](../test-fixtures/README.md)维护服务器、设备寻址和调试 Activity 参数。模拟器使用 10.0.2.2；物理设备可用 adb reverse 和临时替换地址的副本，不修改仓库夹具来适应某台设备。

- `CompatibilityProbeActivity`：普通书源搜索、详情、目录、正文或资源捕获；实际 UI 导出需另走系统文件选择器并检查产物。
- `RssCompatibilityProbeActivity`：RSS XML/自定义列表和阅读目标，不能据此声明浏览器桥通过。
- `RssBrowserProbeActivity`：自带本地服务器，验证脚本顺序、来源/frame/token、消息队列、正则和取消。旧 provider 缺安全消息特性时明确降级并区分跳过项。

最新结构迁移已通过 310 个不重复计数的单元测试、14 项目录配置校验、lint 和 Debug/Release 构建；lint 0 error、12 warning。Android 16 本地普通书源、两种 RSS 和网页桥（24/0/0）通过。完整范围见[迁移记录](history/verification-records.md#2026-10-22-面向嵌入的结构重组)。此前 API 23 的独立记录保留在同页，不能解释成这次迁移重新跑过 API 23。Chromium/GN 尚未执行验证。

## 实站与新记录

只有任务需要时才运行 live probe 或批量下载；这些操作会访问真实站点，遵守已有授权和源限速。探针参数在 `source-core/build.gradle`，目录快照准备见[目录工具](../source-catalog/README.md)。

新执行记录保存到 `history/` 的专题或日期文件，并加入历史索引；记录快照/环境、命令、最深成功阶段、首个失败、预期与实际、未测范围。纯离线和结构重组不记成实站通过。统一证据与交付口径见[skill 参考](../skills/legado-source/references/verification.md)。
