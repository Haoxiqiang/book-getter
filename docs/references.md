# 资料、案例与采用结论

以下是本轮查证后的索引。外部资料用于确定能力边界和测试场景；实现行为以本仓库源码和新工程测试为准。

| 资料 | 可复用的认识 | 本工程处理 |
| --- | --- | --- |
| [Legado 官方仓库](https://github.com/LegadoTeam/legado)及本仓库源码 | 原版以 `AnalyzeUrl` + `AnalyzeRule` 驱动请求与解析，`WebBook` 统筹搜索/详情/目录/正文，`JsSourceBook` 走独立 JS 流程 | 保留分阶段流程，分离 Android 和存储依赖；不宣称完整语义兼容 |
| [吉的书源集](https://source.jwyihao.top/)及其 [JSON 样本](https://raw.githubusercontent.com/jiwangyihao/source-j-legado/main/bilinovel.json) | 真实源可能组合登录、Cookie、WebView、JS、目录分卷、付费和图片。该站明确对完整书源转载及再开发另设条件 | 提交的工程只保存链接与特征分析；完整书源仅下载到 Git 忽略的本地快照，用于去重及在线兼容测试，不再分发 |
| [DandanLLab/legadoSkill](https://github.com/DandanLLab/legadoSkill) | 汇集规则教程、真实书源案例和 Python 调试器；其文档明确模拟结果不能替代 Android Legado 实测 | 已作为独立 Git 子模块固定在工程内，知识资料用于整理场景；规则行为仍核对官方源码与本工程测试，案例不自动纳入可发布书源 |
| [Narylr350/book-source-creator-skill](https://github.com/Narylr350/book-source-creator-skill)及 [行为记录](https://github.com/Narylr350/book-source-creator-skill/blob/main/legado-book-source-generator/references/legado-source-behavior.md) | 将导入、请求、规则和 WebView 的差异分阶段定位；HTTP 模拟与 Android WebView 的行为存在差别 | `Diagnostic`/`SourceFailure` 带阶段与字段；真实源结果记录环境信息 |
| [AnyReader](https://github.com/aooiuu/any-reader) | 用统一模型承载小说、漫画、音视频等内容，规则引擎与应用层分离 | `ChapterContent` 统一表达文本和资源引用，核心模块不依赖界面 |
| [legado-source-toolkit](https://github.com/aminaqvq/legado-source-toolkit) | 书源集合清洗、去重和兼容预检是独立问题，验证器也不等于完整 Android 运行时 | 导入时按 `bookSourceUrl` 更新，保留预检诊断；不将“导入成功”解释成“站点可抓取” |
| [IDPF OPF 2.0.1 规范](https://idpf.org/epub/20/spec/OPF_2.0_latest.htm)与 [OCF 3.0.1 规范](https://idpf.org/epub/301/spec/epub-ocf.html) | OPF 的书名、语言、标识符及 NCX 导航与 spine 关联是必要的；OCF 要求首个 ZIP 条目是无压缩、无 BOM、无额外字段的 `mimetype` | EPUB 导出写入 OPF/NCX，并检查 ZIP 条目顺序、压缩方式及 XML 可解析性；语言无来源字段时写 `und`。OCF 3.0.1 的容器规定用于核对与 EPUB 2 相同的 `mimetype` 约束 |

## 后续真实样例记录模板

每个样例至少记录：书源仓库及版本/获取日期、站点类型、导入诊断、搜索请求及状态、详情/目录/正文各阶段输出、是否需要登录或 WebView、与原版结果的具体差异。不要把账号、Cookie、Token 或正文快照提交到仓库。若样例的许可另有条件，保留链接而不复制完整 JSON。
