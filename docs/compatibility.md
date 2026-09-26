# 书源能力矩阵（首批实现）

这张表描述规则引擎已实现的语义子集，不代表真实书源可用率。来自 `source.jwyihao.top` 的 11 条去重书源、5 个关键词的在线搜索实测与分源失败原因见[在线实测](live-source-tests.md)。

| 能力 | 状态 | 边界 |
| --- | --- | --- |
| 单个/数组 JSON、`sourceUrls` 合集 | 已实现 | 合集递归深度最多 3；覆盖同 ID 书源 |
| 本地文件、剪贴板、URL 导入 | 已实现 | URL 由用户主动输入；无自动订阅更新 |
| 单源及多源搜索 | 已实现 | 每源第 1 页；核心接口接受页码，UI 暂无翻页按钮；正常无匹配返回空结果，已识别的 HTTP 200 业务错误作为源错误而非空结果。[磨铁](header-and-search-item-variable-probe.md)的 URL 选项 `headers` 字符串现可解析，实站不再卡在请求规划；此词 API 明确返回空列表。[经致文学](jingzhi-notfound-probe.md)搜索 1 条但详情返回 HTTP 200 的明确不存在页，现停在详情响应而非误报目录规则失败。调试探针对跨主机跳转、非 200 的 2xx 和搜索端点落根路径标响应可疑，仅供排查，不把所有 0 条认作规则通过。[速读谷](sudugu-paged-catalog-probe.md)旧域 POST 经 301 到新域后两端只得到搜索表单；JVM 逐跳证实最终方法变 GET，直连新域 POST 却能到已知详情。Android 尚无逐跳方法记录，不擅自改写原始源 |
| 详情、目录、正文 | 已实现基础链路 | `ruleBookInfo.init` 的 JSON 对象与 JS 字符串基础路径已测试；详情中分类、字数、最新章、简介、封面解析失败现按官方隔离并告警，书名/作者/目录仍失败可见。目录与正文下一页最多 50 页；[速读谷](sudugu-paged-catalog-probe.md)用指定公开详情页验证 4 页目录及 3292 个去重 URL，未验证搜索和正文。[有度](additional-cross-site-probe.md#有度目录的107-章含义)的 107 个去重目录记录含 1 个 `javascript:` 占位，探针现单列占位数，目录记录数不等于可抓取章节数。传入已知下一章 URL 后正文分页在其边界停止；拒绝已识别的升级占位正文；不执行预更新脚本和付费动作。黑岩公开目录的 2156 个 VIP 标记在 JVM/Android 与页面同数，[记录](xpath-and-optional-fields-probe.md)；不表示可读取付费正文 |
| CSS、XPath、JSONPath、正则 | 基础子集 | 支持单段选取与 `@text/@html/@href` 等单动作；`@textNodes` 有官方对照与 Java 回归、实站仍受阻，`@all` 已由主源 ESJ 单章验证，[详情](dom-actions-probe.md)；`@html` 的外层节点及 script/style 清理已由[抖音小说](html-action-probe.md)单章和合成回归验证。XPath 列表节点、谓词内 `@`、字段后接 `<js>` 已由[黑岩 JVM/Android 目录](xpath-and-optional-fields-probe.md)验证，未读付费正文。列表元素可直接用动作；`tag.a.0`、`tag.span.-1`、`tag.tr!0` 基础 DOM 索引已由真实源及合成测试验证；`tag.span[0:1]` 等方括号位置索引有真实规则样本和 Java 回归，相关站点当前网络不可达。顶层 `&&/||/%%` 有合成回归且避开 JSONPath 过滤器内部运算符，但尚未证明所有混合模式和复杂后置替换等价；普通替换正则默认不跨行 |
| 书籍元数据 | 基础字段已实现 | 搜索/详情的题材 `kind`、字数、最新章节映射至 `BookHit.genre/wordCount/lastChapter`，与媒体类型 `BookHit.kind` 区分；飞卢搜索及抖音详情已在线验证非空。搜索中分类、字数、最新章、简介、封面解析失败现按官方隔离为字段告警，不中断有效书名/地址；黑岩 `wordCount` 实站验证了该边界，[详情](xpath-and-optional-fields-probe.md)。UI 尚未展示全部字段 |
| 规则内 JS、`mainJs` | 基础子集 | Rhino 执行，支持部分 ES6、`jsLib`、`<js>` 和受控的 `java.ajax/get/put/timeFormatUTC`；`java.t2s` 仍是原文返回占位。DOM 节点作为 JS `result` 时仅暴露 `attr/hasAttr/text/ownText/html/outerHtml/toString` 的安全视图；[rezero 卷规则](dom-node-js-probe.md)在 JVM/Android 均验证 23 卷+887 章，`select/hasClass` 等仍待补。`mainJs` 支持 search/getBookInfo/getChapters/getContent，`getBookInfo` 可省略；详情字段会合并到搜索结果。[宜搜](header-and-search-item-variable-probe.md)验证搜索项 `@put` 变量随书进入目录及 JS 引号内 `{{$.字段}}` 展开，JVM/Android 目录 839 项与 API 同数；其他宿主对象、变量持久化和完整生命周期尚不等价于 Legado |
| 手动登录、Cookie、WebView | 已实现基础链路 | HTTP 跳转链共享 Android Cookie；URL 选项 `webView` 按官方“非空且非 false”语义判断，思路客②非标准引号规则已在 JVM/Android 分别核对请求路径；WebView 请求仅 GET，登录验证、WebJs、验证码处理未实现。该站首章重定向到无关域名，正文未通过 |
| TXT/EPUB 导出 | 已实现 | 仅文本章节；EPUB 是最小 EPUB 2，暂不含封面、插图或复杂排版 |
| 漫画/音频/视频/文件 | 基础资源获取与按需下载 | 漫画从正文 HTML 的 `img[src]` 提取并可打包 ZIP；音视频取第一个解析链接；文件源展示下载链接并可选一个下载。现在拒绝无图片章及非 HTTP(S) 的假资源链接；尚不处理 `sourceRegex` 的 WebView 媒体监听、媒体流清单、分段流或解密 |
| 断点继续与取消 | 基础实现 | 成功章节逐章缓存；失败章节在下次导出时重抓；任务状态未跨进程持久化 |

导入预检对 `mainJs`、发现、段评、登录检测、封面解密、批量正文、付费动作、WebJs 及未实现的脚本宿主方法给出提示。预检只做能力提示，不证明书源实际可运行。特别是复杂书源依赖的 Java 宿主 API、共享变量、字体解密、登录态、请求时序和网页脚本，仍可能在运行阶段失败。运行错误会包含阶段和字段；后续应将该结构直接展示在 UI 的逐阶段诊断页。

目录失败也可能是**书源规则漂移**而非引擎缺语法：[飞卢诊断](faloo-catalog-rule-drift.md)中旧 `class.DivTd@tag.a` 指向当前页面的空白容器，真实章节改在 `DivTd3`；核心保持精确选择，不自动猜测类名。该源仍为目录失败，不计作可阅读。

不兼容样例不应默默改写为近似结果。扩展顺序建议：先收集失败样本和预期输出，补该规则语义与回归测试，再更新此矩阵。真实站点的访问状态、账号状态和规则语义要分别记录。

目前还未复刻原版的 `bookUrlPattern` 直达详情回退、完整变量作用域与优先级、跨源换源和批量正文。书籍变量、目录分卷与 VIP 标记仅有基础子集，遇到依赖其他层级或组合行为的书源，仍须用阶段错误和预检结果识别后再扩展。
