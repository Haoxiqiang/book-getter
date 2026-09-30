# 书源能力矩阵（首批实现）

[扩大审查记录](broad-source-compatibility-20260927.md)验证了指定订阅的更新与实站阶段，并修复了混合无效条目导致整批导入失败、部分解析的订阅更新删除旧源、跨源错误阶段失真及首章跳到下一章后正文归属错误。

妙公子“搜索引擎通用”6 条书源的[专项验证](miaogongzi-search-engine-compatibility-20260927.md)补充了名称型书源 ID、复杂 URL 模板、书籍宿主变量、Jsoup 安全视图和挑战页识别的回归。只有必应取得 9 条搜索候选，详情受挑战页阻断；该组没有正文闭环。

这张表描述规则引擎已实现的语义子集，不代表真实书源可用率。来自 `source.jwyihao.top` 的 11 条去重书源、5 个关键词的在线搜索实测与分源失败原因见[在线实测](live-source-tests.md)。

| 能力 | 状态 | 边界 |
| --- | --- | --- |
| 单个/数组 JSON、`sourceUrls` 合集 | 已实现 | 合集递归深度最多 3、嵌套 URL 最多 100、每份 JSON 最多 16 MiB、单次导入累计最多 64 MiB、不同书源最多 10,000；覆盖同 ID 书源；嵌套请求非 2xx 或本地持久化失败时不报告导入成功 |
| 本地文件、剪贴板、URL 导入 | 已实现 | URL 由用户主动输入，顶层与嵌套清单都须返回 2xx；并发导入在提交时读取最新列表并合并，取消后的任务在提交前停止；URL 订阅与妙公子选定合集支持手动更新、失败回滚；手动源和订阅同 ID 时分别保存原文，订阅后续移除此 ID 会恢复手动版本。旧版中已被订阅覆盖的手动原文无法追溯恢复 |
| 单源及多源搜索 | 已实现 | 核心与 UI 支持逐页搜索；多源结果仍按每源独立返回；正常无匹配返回空结果，已识别的 HTTP 200 业务错误作为源错误而非空结果。[磨铁](header-and-search-item-variable-probe.md)的 URL 选项 `headers` 字符串现可解析，实站不再卡在请求规划；此词 API 明确返回空列表。[经致文学](jingzhi-notfound-probe.md)搜索 1 条但详情返回 HTTP 200 的明确不存在页，现停在详情响应而非误报目录规则失败。调试探针对跨主机跳转、非 200 的 2xx 和搜索端点落根路径标响应可疑，仅供排查，不把所有 0 条认作规则通过。[速读谷](sudugu-paged-catalog-probe.md)旧域 POST 经 301 到新域后两端只得到搜索表单；JVM 逐跳证实最终方法变 GET，直连新域 POST 却能到已知详情。Android 尚无逐跳方法记录，不擅自改写原始源 |
| 发现分类与翻页 | 基础子集 | 支持文本 `名称::URL` 分类、URL 型 JSON 分类和 JS 返回的分类文本；`ruleExplore` 列表提取与搜索共用规则语义。复杂 `exploreScreen` 筛选仍明确拒绝；需要逐源验证分类模板与翻页结果 |
| 详情、目录、正文 | 已实现基础链路 | `ruleBookInfo.init` 的 JSON 对象与 JS 字符串基础路径已测试；详情中分类、字数、最新章、简介、封面解析失败现按官方隔离并告警，书名/作者/目录仍失败可见。目录与正文分页最多 50 页，出现循环、跳转回已处理页或超过上限会明确失败，避免缓存不完整结果；正文分页跳转到下一章或目录也会在合并前失败。[速读谷](sudugu-paged-catalog-probe.md)用指定公开详情页验证 4 页目录及 3292 个去重 URL，未验证搜索和正文。[有度](additional-cross-site-probe.md#有度目录的107-章含义)的 107 个去重目录记录含 1 个 `javascript:` 占位，探针现单列占位数，目录记录数不等于可抓取章节数。传入已知下一章 URL 后正文分页在其边界停止；拒绝已识别的升级占位正文；不执行预更新脚本和付费动作。黑岩公开目录的 2156 个 VIP 标记在 JVM/Android 与页面同数，[记录](xpath-and-optional-fields-probe.md)；不表示可读取付费正文 |
| CSS、XPath、JSONPath、正则 | 基础子集 | 支持单段选取与 `@text/@html/@href` 等单动作；`@textNodes` 有官方对照与 Java 回归、实站仍受阻，`@all` 已由主源 ESJ 单章验证，[详情](dom-actions-probe.md)；`@html` 的外层节点及 script/style 清理已由[抖音小说](html-action-probe.md)单章和合成回归验证。XPath 列表节点、谓词内 `@`、字段后接 `<js>` 已由[黑岩 JVM/Android 目录](xpath-and-optional-fields-probe.md)验证，未读付费正文。列表元素可直接用动作；`tag.a.0`、`tag.span.-1`、`tag.tr!0` 基础 DOM 索引已由真实源及合成测试验证；`tag.span[0:1]` 等方括号位置索引有真实规则样本和 Java 回归，相关站点当前网络不可达。顶层 `&&/||/%%` 有合成回归且避开 JSONPath 过滤器内部运算符，但尚未证明所有混合模式和复杂后置替换等价；普通替换正则默认不跨行 |
| 书籍元数据 | 基础字段已实现 | 搜索/详情的题材 `kind`、字数、最新章节映射至 `BookHit.genre/wordCount/lastChapter`，与媒体类型 `BookHit.kind` 区分；飞卢搜索及抖音详情已在线验证非空。搜索中分类、字数、最新章、简介、封面解析失败仍按官方隔离为字段告警，不中断有效书名/地址；但黑岩 `{$.words}字` 实为合法内嵌规则，已修复为非空字数，不再作为可选字段容错例，[更正](xpath-and-optional-fields-probe.md)。UI 搜索行显示题材、字数和最新章的精简摘要，详情诊断保留完整文本；封面仍未展示 |
| 规则内 JS、`mainJs` | 基础子集 | Rhino 执行，支持中断与 60 秒脚本上限、部分 ES6、`jsLib`、`<js>` 和受控的 `java.ajax/get/post/head/put/timeFormatUTC`；`java.t2s` 使用与原版相同版本的转换库和词组例外字典。`toast/longToast` 已通过受控通知回调显示在 App 状态栏；`startBrowserAwait` 仅明确报交互验证不可用，不能绕过防护，见[XIU2 跨站记录](xiu2-cross-site-probe.md)。DOM 节点作为 JS `result` 时暴露受控的 `attr/hasAttr/select/hasClass/text/ownText/html/outerHtml/toString/remove`，元素集合另有 `size/first/toArray/eachText/remove`；[rezero 卷规则](dom-node-js-probe.md)在 JVM/Android 均验证 23 卷+887 章，`select/hasClass` 已由经致文学目录验证，其他集合方法仍待补。`mainJs` 支持 search/getBookInfo/getChapters/getContent，`getBookInfo` 可省略；详情字段会合并到搜索结果。[宜搜](header-and-search-item-variable-probe.md)验证搜索项 `@put` 变量随书进入目录及 JS 引号内 `{{$.字段}}` 展开，JVM/Android 目录 839 项与 API 同数；其他宿主对象、变量持久化和完整生命周期尚不等价于 Legado |
| 手动登录、Cookie、WebView | 已实现基础链路 | HTTP 跳转链共享 Android Cookie；URL 选项 `webView` 按官方“非空且非 false”语义判断，思路客②非标准引号规则已在 JVM/Android 分别核对请求路径；WebView 请求仅 GET；主页面 HTTP 错误保留状态与阶段字段；正文 `webJs` 基础求值和首个正文请求的 `sourceRegex` 资源监听已由本地 Android 夹具验证；同章后续页保留 `webJs` 而不重复资源嗅探，登录验证、交互回调与验证码处理未实现。该站首章重定向到无关域名，正文未通过 |
| TXT/EPUB 导出 | 已实现 | 仅文本章节；从章节缓存逐章读取和写入，不在导出时保留整书正文；EPUB 是最小 EPUB 2，暂不含封面、插图或复杂排版。Android 6 合成站点已验证两章 TXT 与 EPUB 文件内容/ZIP 结构 |
| 漫画/音频/视频/文件 | 基础资源获取与按需下载 | 漫画从正文 HTML 的 `img` 提取 `data-original/data-src/data-lazy-src/src` 中首个可下载地址并可打包 ZIP；CBZ/ZIP 图片扩展名按实际签名，先在应用缓存暂存全部图片，累计最多 512 MiB，网络失败时不写不完整归档。音视频取第一个解析链接；文件源展示下载链接并可选一个下载。现在拒绝无图片章、非 HTTP(S) 的假资源链接、空资源及伪装为二进制的 HTML 页面；资源选择在系统文件选择器期间可经 Activity 重建恢复，并核对书源快照；`sourceRegex` 的 WebView 资源监听已由本地 Android 夹具验证；下载资源每次跨域跳转均限制敏感书源请求头，跨域 Referer 仅保留来源站点；媒体流清单、分段流和解密仍未实现，检测到 M3U8 清单时明确失败 |
| 断点继续与取消 | 基础实现 | 成功章节按书源快照、书籍上下文、章节快照和下一章边界逐章缓存；结构不完整的缓存会重新抓取；失败章节在下次导出时重抓；选章任务与逐章进度已写入应用私有目录，重新打开后可续抓；新任务若与旧任务不同需明确选择替换，损坏的任务文件须经明确选择先备份再创建新任务；导出文件成功关闭后才清除任务；搜索取消会中断子请求并隔离该次诊断；执行中的请求不会跨进程继续 |

新增[客户端跳转/域名出售页探针](client-redirect-and-parked-domain-probe.md)：四三中文和全免小说的 HTTP 200 都不是可解析搜索结果，现分别报 `CLIENT_SIDE_REDIRECT` 与 `PARKED_DOMAIN`，避免伪零结果；不执行该站页面 JS。导入预检对 `mainJs`、发现、段评、登录检测、封面解密、批量正文、付费动作、WebJs 及[脚本宿主方法的未提供/部分兼容级别](js-host-preflight-audit.md)给出字段级提示。预检只做能力提示，不证明书源实际可运行。特别是复杂书源依赖的 Java 宿主 API、共享变量、字体解密、登录态、请求时序和网页脚本，仍可能在运行阶段失败。运行错误会包含阶段和字段；UI 现展示逐源阶段错误、字段、脱敏请求与最终响应摘要；真实站点和复杂 WebView 路径仍需逐项验证。

目录失败也可能是**书源规则漂移**而非引擎缺语法：[飞卢诊断](faloo-catalog-rule-drift.md)中旧 `class.DivTd@tag.a` 指向当前页面的空白容器，真实章节改在 `DivTd3`；核心保持精确选择，不自动猜测类名。该源仍为目录失败，不计作可阅读。

不兼容样例不应默默改写为近似结果。扩展顺序建议：先收集失败样本和预期输出，补该规则语义与回归测试，再更新此矩阵。真实站点的访问状态、账号状态和规则语义要分别记录。

[跨站混合规则探针](mixed-js-jsonpath-probe.md)已验证 `<js>→JSONPath`、`{$.字段}` 内嵌值、JSON Map 动作回退及受控 JS Map 字符串视图：长佩、豆瓣阅读、猫耳 FM 在 JVM/Android 均到目录；52书库在 JVM 到目录。豆瓣 50 条目录与公开 API 同数，VIP 33 与 `price>0` 同数，但未取付费正文；猫耳只验证 6 条音频目录，不代表媒体可播放。喜马拉雅的 HTTP 200 `needLogin=true` 现报 `LOGIN_REQUIRED`，不会算搜索零结果。日志只展示去查询/选项值的 URL 摘要，避免错误规则把页面内容带进日志。

[异构机制广度探针](aoaostar-mechanism-breadth-probe.md)再选 10 个不同站点：POPO 在 JVM/Android 搜索 10 条且首条详情通过，但原规则寻找“章回列表”、现页面只有“章回”链接，目录阶段停在上游规则漂移；百合会搜索被重定向到 HTTP 200 登录页，现两端均明确报 `LOGIN_REQUIRED`，不再伪装成零命中。其余 XPath、GBK POST、图片/文件候选多在跳转、403、TLS 或超时阶段受阻，仍不能称相关规则兼容。

[URL 首匹配与分页回目录探针](url-first-and-pagination-probe.md)按官方 `isUrl=true` 的默认 DOM 行为修正了多链接行：URL 只取首个匹配；夜伴书屋因此在 JVM/Android 从非法详情 URL 前进至搜索 59、目录 71、单章 3 页。若 `nextContentUrl` 精确指回书籍/目录页，核心停止分页并报 `PAGINATION_TO_CATALOG`，避免将目录页当正文；这不证明全站内容净化或所有分页规则。[`java.post` 专项](java-post-redirect-probe.md)又补了无自动跳转的受控响应视图与 `Location` 读取，本地 302 已在 JVM/Android 分别验证；起舞中文真实源现在停在两端 TLS 关闭，未进入搜索列表，不称实站通过。

[DOM 节点 JS 专项](dom-select-vip-probe.md)补受控 `select/hasClass` 视图：经致文学换用有真实详情的“故事”后，JVM/Android 均到 12 条目录、VIP 9 条，分别与公开 HTML 的不同章节链接及 `isvip` 链接计数一致。它只证明这一目录规则，不代表 VIP 正文可读；百里中文当前搜索 HTTP 404，尚无第二站同类正对照。

[`isPay` 标记专项](is-pay-marker-probe.md)将官方独立于 `isVip` 的目录布尔值保留到 Java `Chapter`；晋江原规则 `$..point<js>…</js>` 已由合成 Java 回归及 Android 本机两章夹具验证，公开 API 只读核对了 `point/isvip` 的字段分布。原晋江书源因搜索业务码、Cookie 与可购买脚本分支尚未跑到真实目录，不能把合成结果算成该源通过。诊断 URL 摘要现保留非默认端口，但仍隐藏查询和选项值。

[XIU2 关键词/69 书吧专项](xiu2-keyword-and-69shuba-probe.md)提供另一条 JSON API 跨端正对照：酷我小说换用站内有结果的“修仙”后到 2052 项目录与单章，独立 API 计数一致；先前的 `data=[]` 只说明原词无结果。69书吧.com 的 `<js>` 本身不是合法 JS，另一条旧域 POST 跳转后变 GET，均不能为提高成功率而在核心里猜测修补。

`bookUrlPattern` 的最终 URL 直达详情与无模式空列表回退已有[合成回归及两批十站低频筛查](book-url-pattern-probe.md)，探针现在单列 `SEARCH_BRANCH` 与字段告警；仍没有触发直达分支的实站正对照。目前还未复刻原版的完整变量作用域与优先级、跨源换源和批量正文。书籍变量、目录分卷与 VIP 标记仅有基础子集，遇到依赖其他层级或组合行为的书源，仍须用阶段错误和预检结果识别后再扩展。

后续离线回归确认，同一详情 URL 属于不同书源时，书籍规则变量按“书源 ID + 书籍 URL”隔离；缺失的 `@get` 返回空字符串。媒体缓存和文件选择器恢复的资源地址也必须是可请求的 HTTP(S) URL；损坏的缓存会重新抓取。以上未扩大完整变量生命周期或真实媒体站点的兼容结论。

正文读取和章节缓存还会核对章节归属的书籍 URL；调用方传入另一本文学作品的章节时明确失败，不会按当前书的变量或分页边界处理。

[编码宿主专项](js-host-encoding-probe.md)按官方行为补 `java.base64Decode` 字符串/编码重载与 `java.encodeURI` 表单编码；后者已在和图书原搜索规则执行到 HTTP 403，请求阶段通过不等于列表通过。Base64 所在的 101 言情仍被旧域 404 阻断，因此两项都只列部分兼容，不把离线回归写成全源通过。

[全量聚合源筛选审计](aggregate-source-freshness-probe.md)发现 3907 条记录只有 2595 个有效唯一 ID，不能按聚合页同步日期推断单源新鲜度；同名 `.co/.com` 书源曾被子串过滤一起探测。JVM/Android Debug 探针现支持 `=完整书源ID` 精确筛选；Android 跳转日志区分原请求方法与未观测的最终方法。精确复测的 `.co` 源仍因 POST→GET 的跨域跳转得到搜索表单，不能计为有效零命中或可用源。

[`java.getElement/getElements` 节点专项](dom-host-getelement-probe.md)纠正了把 HTML 节点在脚本入口变成字符串数组的宿主差异；受控节点方法及返回节点进入搜索字段的路径已由合成 Java 与 Android 本地夹具验证。主书源三七轻小说本轮仍停在 HTTP 302，复杂 Cookie/AES 与其他返回模式没有实站正对照，保持部分兼容。

[`java.getStringList` 列表专项](js-getstringlist-probe.md)按官方逐项提取、`text.片段` 选择及 `isUrl` 补全去重修复了默认 DOM 基础路径；实际第三方下载规则仅提供语法样本，离线夹具验证两条相对地址，完整文件源和其他返回模式未验证。

[`java.log` 宿主专项](js-log-host-probe.md)补返回原值与可注入调试回调，默认不输出规则数据；离线回归和字段级预检通过，应用调试日志接线及真实规则阶段仍未验证，因此列部分兼容。

[`java.getStringList` JSONPath 项目边界](js-getstringlist-jsonpath-probe.md)补直接 JSONPath 列表逐项返回，避免字符串值内换行被拆成伪项目；此项仅有官方源码与合成夹具对照，尚无真实书源规则语法样本或站点正对照。

[`java.head(...).cookies()` 响应视图](java-head-cookies-probe.md)按一读小说实际搜索规则补了重复 `Set-Cookie` 保留和受控 Cookie 名值读取；离线跨模块回归通过。该站先前停在 TLS，脚本未执行，后续 `source.putLoginHeader` 仍缺失，不能视为站点通过。

[选择器→JS→选择器专项](selector-js-selector-probe.md)补了列表规则的跨行分段执行及受控 `java.getElements(...).toArray()`；“文学吧”从 CSS 解析错误、再到缺少 `toArray`，最后以“故事”返回 13 条候选。仅验证搜索列表路径，候选相关性和下游阶段未验；其他混合模式仍不能据此宣称兼容。

[书源 ID 片段与列表前缀专项](source-id-fragment-and-list-prefix-probe.md)按官方 URL 相对解析和列表入口处理修正了两类独立差异：带空格/中文 `#` 标签的书源 ID 不再使相对搜索路径丢失协议，`+` 列表前缀不再送入 CSS，`-` 列表前缀按官方倒序。八一中文在修正后仍于请求阶段 EOF，笔趣阁③为 403；SF 轻小说真实搜索 2、详情通过、目录 2023，与公开 HTML 独立链接计数相同。只到目录，不计正文或整书。

[Jsoup 安全视图专项](jsoup-safe-facade-probe.md)为规则中的 `importClass(org.jsoup.Jsoup)` 提供仅限 `parse` 的白名单，不开放 Java 类反射。SF 轻小说原先 `kind` 字段告警、分类/最新章为空；现 JVM 与 Android 均在真实规则下搜索 2、分类/最新章非空、详情与 2023 目录保持通过。其他三个候选站点停在 TLS、403 或 WebView 前提，尚无第二站的同类脚本正对照；完整 JavaImporter/Jsoup API 仍不兼容。
