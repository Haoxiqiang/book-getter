# 不同站点补充探针：搜索边界与目录占位（2026-09-27）

为扩大**独立站点 × 规则机制**覆盖，从 [AOAOSTAR 128 源集合](https://legado.aoaostar.com/sources/4dc410d1.json)按站点名称挑选 19 条、各用“斗破苍穹”搜一次。集合 SHA-256 为 `05da3da45910c8342d4935044c9af6298be0fbcee62af910b6aea2550fee44de`。其中有度中文网、一读小说、完本小说在此前记录中已测试，本轮为复测；其余 16 条未见于先前的跨站探针记录。原始第三方源 JSON 不提交，也不抓取正文或整书。JVM 探针限速 1.5 秒，HTTP 连接/请求超时 12/15 秒；实时状态会变化。

复现搜索批次（`probeSource` 只选指定书源，实际执行顺序随集合顺序）：

```sh
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=番薯免费小说,爱下电子书,看书神,企鹅听书,下书文学,笔书阁❶,有度中文网,一读小说,完本小说' '-PprobeQueries=斗破苍穹' --offline
./gradlew :source-core:liveSourceProbe -PprobeJsonUrl=https://legado.aoaostar.com/sources/4dc410d1.json '-PprobeSource=笔趣阁①,笔趣阁③,顶点小说③,六九中文,趣书吧,大文学①,恋上你看书,蚂蚁阅读,小说部落,UU小说' '-PprobeQueries=斗破苍穹' --offline
```

| 书源 | 搜索阶段观察 | 归因边界 |
| --- | --- | --- |
| 番薯免费小说、六九中文、恋上你看书、一读小说 | TLS 握手被远端关闭 | 未到规则；一读是旧状态复核 |
| 爱下电子书、笔书阁❶、蚂蚁阅读、UU小说 | EOF | 未到规则 |
| 顶点小说③ | 证书主机名不匹配 `www.23uswx.net` | 保持 TLS 校验，不宣称规则错误 |
| 看书神、完本小说 | 搜索 HTTP 404 | 旧接口不可用；完本是复核 |
| 笔趣阁①、笔趣阁③、趣书吧、小说部落 | 搜索 HTTP 403 | 访问控制，不当成零结果 |
| 大文学① | 原域 GET 经 301 到 `www.dephui.com` 的同路径，最终 403 | 先是主机迁移，再被拒绝；未到规则 |
| 下书文学 | 签名请求返回 HTTP 302，Location 指向 `ww80.xiashuzhan.com`；JVM 未跟随该响应 | `SEARCH.searchUrl / UNRESOLVED_REDIRECT`，是请求/上游跳转边界，不是空书 |
| 企鹅听书 | HTTP 200，32 字节 JSON：`ok=true`、`books=[]`、`total=0` | 对这个词，服务端明确给出空列表；不外推全站收录或规则兼容 |
| 有度中文网 | POST HTTP 200、15 条；首条详情 HTTP 200；目录去重后 107 条 | 既有搜索/规则正对照的复核；下面补充占位 URL 诊断 |

## 有度目录的“107 章”含义

同一首条《斗破苍穹19·勇闯中域》的公开详情 `/book/17551/`，`id=chapterList` 中有 108 个 `<li class="w33p">`：106 个数字 `.html` 章节链接，另有“第五十章 报酬”和“第一百章 恐怖对轰”两个 `href="javascript:"`。上游书源规则逐条取 `a@href`。官方 [BookChapterList.kt](../../app/src/main/java/io/legado/app/model/webBook/BookChapterList.kt) 不检查 URL scheme，随后用 `LinkedHashSet<BookChapter>` 去重；[BookChapter.kt](../../app/src/main/java/io/legado/app/data/entities/BookChapter.kt) 按 URL 判断相等。因此两个同 URL 占位只保留一个，结果应是 **107 个目录记录，不是 107 个可直接获取的章节**。本工程同样得到 107 条，其中 `javascriptPlaceholders=1`；没有修改源规则或丢弃原目录记录。

合成 Java 回归固定“4 个列表项中 2 个同 URL `javascript:` → 3 个目录记录、1 个占位”，同时单测校验占位识别。JVM/Android 同源同词复测均得到搜索 15、详情成功、目录 107、占位 1；Android 搜索方法确为 POST。新的探针字段 `nonVolume` 仅表示非卷条目，不再叫 `readable`。这项诊断也修正了此前[有度探针](post-and-pagination-probe.md)对“107 章”的宽泛措辞；首章单章测试不证明占位章节可读。

未解决的边界：目录占位前后的正文 `nextContentUrl` 可能无法用这个占位 URL 作为下一章边界；本轮没有请求邻近章节，也没有证明整书分页安全。后续若做批量验收，应先区分可请求 URL 与占位，并对邻近章节做分段边界验证，不能直接把目录总数作为待下载章节数。
