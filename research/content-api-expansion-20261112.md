# 内容与功能接口扩展 · 2026-11-12

## 接入后的状态更新

用户后续明确“先接 mangabaka”，漫画翻译留到之后。本批已实现侧栏“找漫画”的上升／冷门／主题书单，资料条目中的相似、方向性系列关系、同作者作品；后续按用户反馈删除详情“确认作品”入口，另将详情作者名字连接到拷贝的真实作者目录；书单保留整理者并按需显示剧透内容。资料进入阅读前需要从拷贝取得实际版本并显式选择，未把 MangaBaka 当成正文源。具体当前合同与证据在 DESIGN.md 的 MB-CATALOG-01 至 MB-ATTR-01。

本批完成源码及独立审阅，没有运行测试、产品 API 探测或本地构建，按 main CI／用户验收流程交付。以下各节保留接入前调研的时间与候选身份；其中“尚未接入”不是上述已实现能力的当前状态。多种子 Mix、资讯、外部追漫账号、额外阅读来源、翻译与已读偏好过滤仍未加入本批。

## 这次解决什么

用户要求：“这个评论加载就别显示这些了啊”“登录还是会报错啊我电脑 relay 桌面端都可以啊”“再研究下还有什么功能接口可以接入拓宽我们的内容和功能”。追加要求：“时间页码那些就别弄边框了直接淡在某个角落”。精确消息 ID 未提供。

本轮实现评论流降噪、阅读角落状态和登录传输兼容；下列新增能力是研究候选，尚未接入。最终整合源码基线为 MangaFlow `cb7e41b`（包含并发到达的新版筛选与阅读器改动）；Relay 对照版本为 `0079af7ca2242b51f14d9c3cf1874b12c18888e8`。证据为官方接口规范、文档和当前源码；没有运行测试、产品 API 探测、本地构建或设备自动化。

## 当前已有，哪些才是新增

当前评分／评论已经使用拷贝、Bangumi、AniList、MangaUpdates、MyAnimeList（Tenrai）、Kitsu、Shikimori；MangaDex、腾讯、巴哈和漫画柜用于评分，MangaBaka 目前只做作品身份／编号关联。章评仅有精确章节的拷贝和严格映射的 Dantotsu。不能把已接入站点再列一遍当成果。

**建议先扩展“发现什么值得看”和“作品之间的关系”，再扩展正文来源与私人书库。** 前两项能复用现有作品映射和阅读入口；接入全文来源则需要独立来源身份、目录、图片、进度与下载语义。

| 优先级 | 用户得到什么 | 具体接口与读取条件 | 当前缺口与实施落点 |
| --- | --- | --- | --- |
| P1 | 看完一本后找相似作品；按喜欢的几本、题材、完结状态找书 | MangaBaka `GET /v2/series/{id}/readers-also-like`、`GET /v2/series/mix`；公开读取。Mix 支持种子作品、包含／排除标签、状态和媒介 | 现有适配器没有推荐能力。在详情末尾放按需展开的“相似作品”，先把推荐作品匹配到可阅读来源；不能把资料条目冒充可直接开读的漫画 |
| P1 | 热度上升、冷门佳作，减少首页反复推荐同一批书 | MangaBaka `GET /v2/series/discover/rising`、`GET /v2/series/discover/hidden-gems`；公开读取 | `rising` 的依据是近期加入书库，不是拷贝点击量；保留真实口径。可作为现有发现页内的内容选择，不另堆一屏入口 |
| P1 | 前传、续作、外传、改编关系；作者的其他漫画 | MangaBaka `GET /v1/series/{id}/relationships`、`GET /v2/series/search` 的 `staff` 参数；Bangumi `GET /v0/subjects/{subject_id}/subjects`、`/persons`、`/characters` | 先用已确认的站点 ID；关系有方向，`source` 与 `adaptation` 不可对调。漫画、单卷、小说、动画分开，角色简介默认不展开，避免剧透 |
| P2 | 别人整理的题材书单、入门书单、系列阅读顺序 | MangaBaka `GET /v2/stacks`、`GET /v2/stacks/{id}`、`GET /v2/series/{id}/stacks`；公开书单无需登录 | 书单条目仍须进行来源匹配，保留整理者与真实排序。私人／草稿书单不是公开列表，不能靠猜编号读取 |
| P2 | 更广的多语言漫画正文、翻译版本和同章不同组 | MangaDex API 5.13.1：`GET /manga`、`GET /manga/{id}/feed`、`GET /chapter/{id}`、`GET /at-home/server/{chapterId}`；规范声明这些读取无需账号 | 当前只接评分。需增加完整正文适配器。按 `translatedLanguage[]` 选语言，过滤不可读／外链章节，不混拼不同翻译组的图片；站点移除或不可用的章节不能宣称可读 |
| P2 | 手机直接读电脑／NAS 上自己的漫画，跨设备接续 | Komga `OPDS /opds/v2/catalog` 或 `/opds/v1.2/catalog`；Kavita 的 OPDS 与官方 REST API；需要用户自己的服务器和凭据 | 真实扩充私人内容，而不是新增评分。OPDS 可做统一发现／下载入口，逐页串流与精确进度需按服务器能力实现，不能假定所有 OPDS 都自带进度同步 |
| P3 | 与外部追漫书库同步想看／在看／看完、已读章数和评分 | MangaBaka `GET /v2/my/library/changes`；`PATCH /v1/my/library/{series_id}`；需要自己的 PAT 或注册应用后的 OAuth，分别申请 `library.read`／`library.write` | 当前拷贝账号与 WebDAV 备份不是外部追漫服务同步。先只读导入，再做明确的双向同步与冲突处理；个人页码留在本地，不能从外站章数猜页码 |
| P3 | 作品相关资讯与发售动态 | MangaBaka `GET /v1/series/{id}/news`、`GET /v2/news`；公开读取，保留原始出处 | 来源新闻／release 不等于当前阅读来源已更新。当前有禁止第三方网页跳转的要求，因此仅研究资讯摘要与明确来源，不恢复外链按钮，不搬运整篇原文 |
| 可选 | 外语评论在应用内翻译，之后再考虑原文 OCR | ML Kit Translation Android：动态下载所需语言模型，官方称模型约 30 MB；不是免下载的零成本接口 | 当前已有显式转交系统翻译。若升级为内联翻译，先让用户选择下载并提供删除入口；保留原文、标明译文。基础包增量、网络可达性、日中效果未测，不宣称不增体积 |
| 后续 | 美漫单期、卷、角色与出版信息 | Comic Vine 官方 API，需要用户 API Key | 候选仅是元数据，不提供漫画图片正文；单期／卷不能直接合为日漫系列。需求若仍集中在日漫／韩漫，不先增加这套身份模型 |

## 先做的最小切片

1. **相似作品 + 冷门佳作**：复用已确认的 MangaBaka ID；获取小批结果，批量去重和本地已读排除，成功匹配阅读来源后才提供“阅读”。不上传整份本地阅读历史；种子由用户当次选择。
2. **系列关系 + 同作者作品**：复用现有详情信息层，正常首屏继续以封面、章节和阅读为主，相关信息按需披露。每条边保存关系类型和方向，不仅保存“相关”两个字。
3. **主题书单**：接公开 Stacks，与推荐共用候选作品模型、来源匹配和加载缓存。
4. **MangaDex 正文**：单独切片补全来源选择、目录与图片链；进度按来源／版本存储。跨来源只尝试精确章节关联，不能复制原始页号。
5. **Komga／Kavita**：在用户有服务器时接入。优先只读发现与开读，再考虑可选的服务端进度写回。

MangaBaka 公开 REST 已覆盖以上发现功能，不需要为了普通列表功能引入 MCP 或 LLM。其 MCP 当前为实验接口，适合未来自然语言找漫画的独立能力，不作为基础浏览依赖。

## 会影响实际接入的约束

- MangaBaka 的文档当前标记大部分新增发现接口为 beta。搜索预算为每 IP 30 次／分钟、其他请求 180 次／分钟，分别有 burst 限制；按真实 `Retry-After`、官方缓存 TTL 和现有页面生命周期处理，不预抓全库。Mix 还限制结果窗口，不能把“无穷加载”映射成无边界翻页。
- MangaBaka 自有推荐／书单／关系与从其他站取得的简介、封面等不是同一类数据。延续现有出处和许可，不把代理返回的数据当成本项目自有数据库。现有 CC BY-NC-SA 4.0 归属不取消原站条件。
- AniList 已有接入，不是新的内容源。其官方条款限制批量囤积、把 API 当备份以及同类非互补追踪服务；当前文档还提示暂时按 30 次／分钟限流。因此不把全站同步或无限资料镜像作为本轮推荐路线。
- MangaUpdates release/RSS、MAL 直接 OAuth 追漫同步仍是后续分支：本轮官方文档页面读取受限，未确认最新完整契约，不用第三方包装 API 的路径冒充原站承诺。
- 腾讯、快看、B 站漫画、WEBTOON／Naver 等的新增正文／评论能力仍沿 README 的实际契约和授权缺口继续，不能以可访问网页或视频评论接口替代漫画接口。

## 已读取的官方证据

全部核对日期为 2026-11-12。仅下载规范与读取文档，没有调用这些产品数据接口。

- MangaBaka 完整 OpenAPI 3.1：`https://mangabaka.org/api.json`；开发入口 `https://mangabaka.org/developers`；REST 与 MCP 的能力范围 `https://mangabaka.org/developers/mcp`。
- MangaBaka 增量同步和授权：`https://mangabaka.org/developers/guides/sync-a-library`、`https://mangabaka.org/developers/concepts/oauth-scopes`；限制 `https://mangabaka.org/developers/concepts/rate-limits-and-caching`；归属 `https://mangabaka.org/about/data-license`。
- MangaDex 完整官方规范，版本 5.13.1：`https://api.mangadex.org/docs/static/api.yaml`；直接读取规范得到上述公开读链，网页文档入口在本轮抓取器中未能打开。
- Bangumi 公开规范：`https://github.com/bangumi/api/blob/master/open-api/v0.yaml`。人物、角色、相关条目使用 OptionalHTTPBearer；私有收藏需要令牌。这里使用真实的 `/subjects/{id}/subjects`，不照抄第三方文章中未在本规范出现的 `/relations`。
- Komga OPDS：`https://komga.org/docs/guides/opds/`；进度写回 `PATCH /api/v1/books/:bookId/read-progress` 的官方说明 `https://komga.org/docs/openapi/mark-book-read-progress/`。
- Kavita：`https://wiki.kavitareader.com/guides/features/opds/`、`https://www.kavitareader.com/docs/api/`；OPDS 标准 `https://specs.opds.io/opds-2.0`。
- ML Kit Android 翻译及模型管理：`https://developers.google.com/ml-kit/language/translation/android`。
- AniList 官方条款／限流：`https://docs.anilist.co/guide/terms-of-use`、`https://docs.anilist.co/guide/rate-limiting`。
- Comic Vine 官方 API 入口／Key 要求：`https://comicvine.gamespot.com/api/`。

## 本轮修正与未闭合项

- 评论来源的未匹配、资料失败和单源重试从混合评论正文移到筛选内按需展开的“来源设置”；真实评论、归属、分页、显式匹配和重试保留。
- 时间／页码改为右下角 50% 透明度的无底板文字，跟随独立阅读底色，避开章末操作；不显示边框、圆角或按下涟漪。
- Relay 当前源码与 MangaFlow `c2d46ad` 之后的网页登录字段、目标域名、资料验证配对已经一致。本轮额外把账号客户端固定为 Relay 默认使用的 HTTP/1.1，排除默认 HTTP/2 协商路径差异。这是兼容候选，不是对 210 根因的证明。
- TLS 实现、出口网络、设备环境和用户截图对应版本仍可能不同；没有获取个人密码或 Token，也没有真实登录结果。必须保留“待手机验证”，不能根据源码对齐或 CI 触发关闭登录问题。

下一步：主线提交并确认现有 CI 触发，由用户反馈登录及界面结果。新接口的实现优先从上面的首条切片开始，需要另行进入已授权实施范围；本研究不代表上述能力已经实现。
