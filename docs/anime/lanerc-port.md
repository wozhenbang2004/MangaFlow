# Lanerc feature transfer into MangaFlow

## 片源探测、换线路与评论 · 2026-11-20

- 取址和媒体探测分开更新。探测能读取忽略 Range 的服务器返回的限量视频前缀、HLS 子清单和片段、DASH 清单及样本内完整的 MP4 视频轨道。来源面板区分等待目录、本集未对应、HTTP 错误、媒体采样和实播数据。
- 播放器在正常播放的控制栏、打开视频和缓冲首帧时都提供「换线路」。面板绑定当前集，打开时保留正在尝试的播放请求，选定线路后沿用同集切换和观看位置确认流程。
- 番剧评论使用漫画的评论面板。Bangumi 作品短评、AniList 与 MyAnimeList 长评按各自确认的条目读取，分页及错误独立处理。原生片源可从评论页进入作品关联。播放页本集讨论使用 Bangumi 对应集的主题与回复。 [Bangumi 分集讨论接口](https://github.com/bangumi/server-private/blob/master/routes/private/routes/episode.ts) · [Tenrai 动画长评接口](https://api.tenrai.org/documentation)

## 起播与下一集接续 · 2026-11-20

- 已确认的集数直接取址。快速线路拿到地址后立即交给播放器，其他线路同时取消。已经匹配的集数切换时不再重启后台全源核对。缺少可用线路时继续按当前集查找和恢复。
- 下一集已准备的可用线路在普通排序变化后仍可接续。手选线路和明确设置的优先来源继续优先。来源版本、网络线路、集数、画质与有效期仍在使用前核对。
- 当前播放短暂缓冲或暂停时，挂起正在进行的下一集缓存和预解码。保留已解析的地址与已完成的缓存。稳定播放后继续准备，切集时由播放器按确切起播位置接用可复用内容。

本轮交付依据为调用链和源码审阅。Android、Windows 上与 Lanerc 的首帧、缓冲和连播表现仍需同一片源与网络下的设备观测。

## Lanerc 上游与次元城交互参考 · 2026-11-20

Lanerc 的发布脚本是核对上游接口的依据。MangaFlow 直接调用已识别的公开目录、剧集与媒体协议。次元城按交互参考使用，上一轮加入的账号和取流入口已撤回。已保存的追番和观看记录仍由原有存储保留。

| 参考中的入口 | 可追溯的上游 | MangaFlow 当前路径 |
| --- | --- | --- |
| AniCh | `anich.sends.eu.org` 的 `/bangumi` 目录、`/vod` 逐集线路与 `/danmaku` 弹幕 | 内建源直接读取最近更新和本集分页弹幕，和现有原生搜索、详情、播放共用来源身份。 |
| LMM85 | `www.lmm85.com` 的公开更新页、搜索页、作品与播放页 | 新安装优先使用内建的公开 HTML 适配。原来已启用的脚本版和观看身份按原选择保留，可从同一来源管理项选择内建版。 |
| 金牌、优可 | 分别为金牌的公开 API 主机与优可 `ss.trgfd.cn` 的主机配置 | 沿用现有原生客户端。优可实际媒体仍由所选剧集返回。 |
| AkiAnime、动漫巴士 | 原站公开 HTML 和已声明的解析页 | 沿用现有原生静态解析。 |
| 次元城 | 发布页与网页客户端资产可说明交互和业务接口，实际媒体供应方尚未获得可核验证据 | 借鉴长剧集的区间导航。MangaFlow 播放器沿用原有组件，超长目录分区间定位，查找仍覆盖整个分组。 |

未核验的解析服务、加密包和实际 CDN 身份继续保留原始依据。源码接线尚未在设备上验收。 [AniCh 固定脚本](https://js.z1i.cn/js/anich.js) · [LMM85 固定脚本](https://js.z1i.cn/js/lmm85.js) · [次元城网页版](https://www.cycani.org/)

官网主线路与备用线路下载得到同一份 Android 5.6.1 arm64 APK，包名 `com.ynwtandroid.fork.xl`，版本码 `1789561015`，APK v2 签名校验通过，SHA-256 `d282d183f0dff5694fc82d78d8729fc23dce705a6aee22746549cac24dad145e`。发布页标示的 5.6.0 与 APK 清单内版本有差异。静态读取 APK 中 `classes6.dex`、`classes7.dex` 确认登录字面量，`classes7.dex` 同时含搜索字面量。公网网页主机为 `www.cycani.org`，已与发布域 `cycity.pro` 一起加入 Relay 的自动分流直连。 [官网发布页](https://www.cycity.pro/) · [官方网页](https://www.cycani.org/) · [Relay 规则](https://github.com/wozhenbang2004/Relay-Desktop/commit/48ae7d5a18bc6af30597079b09ab723f6230f310)

## 片源入口及失败恢复 · 2026-11-20

Lanerc 主源的三条 Protobuf 取流调用明确要求 ISO-8859-1。宿主现在保留原始字节，并为脚本提供 Latin-1 语义的 `atob` 和 `btoa`。主源和 LMM85 的完整明文 HLS 片段共用图片头识别，覆盖在线、缓存及离线播放。Lanerc 站内弹幕直接使用剧集标记里的原生作品 ID 和集序。豆瓣图床封面请求从地址中去掉脚本附加标记，并送出该图床所需的 Referer。后台队列等待并发许可时不再占用片源会话锁，播放仍可优先取址。

来源管理可以从内置来源打开对应的固定脚本版入口。AkiAnime、动漫巴士等脚本版提供原有的推荐、分类和筛选。继续用固定摘要和版本校验安装，保留两种实现各自的来源、作品和剧集身份。

原生片源详情接入脚本返回的简介、封面、年份及已声明的集数。只收到作品资料时，保留可读详情和重试入口。播放保留有效的清晰度和字幕选项，所选清晰度使用对应的格式和请求头。对公开接口明确返回的拒绝、限流和服务故障，取址诊断保留 HTTP 状态。

作品自动关联按作品、季、目录以及本集对应关系判断。正常播放取得地址后直接交给播放器，限量探测用于选线排序和诊断。实播失败继续沿原集执行同源重试或切换已确认线路。LMM85 固定脚本的数字标记能确认同作品的线路，本集标题、集数和版本一致时可在这些线路间切换。脚本直链失败时可在应用内读取已声明的静态媒体，依赖验证或执行网页播放器脚本的线路仍需要兼容能力。

iOS 复用通用脚本传输对七条已审核的匿名公开 HTTP 入口做精确地址、请求方法和公共 IP 校验。HTTPS 校验保持生效，明文接口拒绝通用认证头。来源实际响应与设备端播放未验证。

## 调用分层及来源可用性 · 2026-11-20

本轮以原 APK 1.1.6 对应的固定来源清单及下载字节为准。22 份可读脚本提供 154 个宿主操作，其中 151 个为内容、配置与菜单，3 个为会话。可静态追踪到 54 个不同 HTTP 调用点和 4 个媒体嗅探点。七份脚本还验证到 44 个应用层业务分发或相对路径模板，此计数与 HTTP 模板有重叠。网络实际调用次数取决于来源、分页、缓存和重试。

| 范围 | 已确认 | 目前的接入状态 |
| --- | --- | --- |
| 16 个视频源、豆瓣发现、弹弹play | 漫流保留原生和已装扩展的真实来源身份，按搜索、资料、剧集、取址及弹幕消费支持的操作。 | 既有接入。账号类操作仍依据支持的访问方式，是否可播由实际线路响应决定。 |
| 酷漫星、咸番鸭、yulu、漫时光 | 均使用 `search(keyword,page)`、`detail(id)` 与 `play(flag)`。卡片保留 source 和 id，章节保留 opaque flag，读页交给同源解析。 | 四个原脚本分别涉及上游应用身份、设备校验、会员操作或长期存储凭据。漫时光匿名目录在本机得到 HTTP 000。漫流依接入状态展示其公开前提。 |
| 囧次元 | 下载包与固定记录相符。 | ENC1 发布包待来源方提供兼容形式。 |
| 次元城 | 已复核 Android APK 和官方网页调用。 | 主要作为界面与交互参考，媒体供应方未确认。 |

将可用来源保留在原有搜索与正文聚合中。同一作品卡继续持有各个原生来源身份。首批结果可先使用，尚在排队的查询与刚发出的查询有独立状态。HTTP 401、403、429 或 5xx 后的空回复保留可解释的来源问题，真正取得的有效结果仍可显示。普通漫画来源管理可以按需展开已核对但未就绪的四个来源，目的与状态分组相同。采用既有 Motion 控件与布局原则 DP-05、DP-06、DP-27。 [Mihon 官方来源管理](https://mihon.app/docs/guides/getting-started) · [次元城发布页](https://www.cycity.pro/)

禁漫继续使用已有原生收藏、热门标签和阅读路径。聚合阶段的基础栏目无需先请求周榜和推荐信息，选定来源时仍可用完整目录。账号快照同时保留显示名、图片会话和变更代次。图片缓存与正文授权仍沿原会话和路由隔离。

### LMM85 已声明直链的媒体格式

固定摘要的 LMM85 脚本将静态直链和需浏览器解析的线路分别处理，并说明部分 HLS 分段在 MPEG-TS 前带图片。Android 的既有媒体提取器已有图片边界检查。MangaFlow 的 Windows 与 iOS 回环播放器现也会对 LMM85 已声明直链的完整 HLS 分段做同样检查，在线响应、已缓存分段和已下载分段共用识别规则。

完整 PNG/JPEG 边界之后需要连续五个有效的 188 字节包，才将去掉前缀的 MPEG-TS 交给播放器。回环清单为图片后缀与离线分段提供本地 .ts 路径，兼容 libavformat 的分段扩展名检查。上游传输与已缓存文件以原字节保存。按范围引用、加密、分片 MP4 和低延迟清单继续用原有媒体路径。窗口边界与来源格式完成静态核对，来源响应、首次起播和真机仍需运行证据。 [固定脚本](https://js.z1i.cn/js/lmm85.js) · [FFmpeg HLS 分段检查](https://github.com/FFmpeg/FFmpeg/blob/n8.0/libavformat/hls.c)

同轮再次核对固定文件。酷漫星声明 8 项宿主操作，咸番鸭、yulu、漫时光各声明 7 项。囧次元发布文件为 81,541 字节，仍是相同摘要与 ENC1 头。以上只读取分发文件。

## 首帧恢复与线路实测 · 2026-11-19

`1000122121.jpg` 为《FX战士久留美》第 1 集的 GiriGiriLove 容器解析错误。用户手动重试后，`1000122122.jpg` 显示同一集从 GiriGiriLove 播放到 4:39。两次截图无法辨别站内线路组、具体地址或上游媒体响应。旧的自动恢复会排除当前失败线路，手动重试则允许重新解析。

- Android 在首帧前对特定容器和清单解析错误保留原有限量媒体检查。当没有识别到明确拒绝、限流或不支持的响应时，同一播放意图最多重新解析原集原线路一次。该次会话从上游读取媒体，已有缓存仍保留。恢复检查原版本、网络路由、集数、播放位置、手选线路与明确指定的画质，保留自动恢复设置和再换线的时限。解码、DRM、渲染和本地文件故障仍沿用各自处理。
- 来源面板「测本集线路」通过正常解析器为每个已匹配来源测一条候选的取址时间，最多两条并行。只保留耗时、结果和明确的原站画质标注。该检查不启播视频。首帧时间、解码分辨率和实际传输样本来自正常成功播放，缓存命中和平台无读数时保留未知。近期观测按确切集、线路、版本及路由识别，取址和播放数据有独立有效期。
- 自动初选把近期失败后移，尊重明确设置的优先来源。其余先考虑实播与首帧表现，同档比较实际画质与传输采样；只有取址时按取址观测作为初选参考。未观测线路仍参与回退。旧记录只有优先开关和关联方式，自动关联的优先项继续提供排序参考，手动关联的优先项沿用优先权；新设置明确保存用户的选择。面板与新起播使用同一排序，手动选线仍保留。

本次按 `16124c8` 审源码和差异。旧版本手动 GiriGiriLove 起播已有用户证据，新版自动补救和实际线路排序仍待运行验证。

## 剧集入口与来源面板 · 2026-11-19

`1000122119.jpg` 中《魔法骑士雷阿斯》的 AGE 和金牌勾选来自「作品已关联」布尔值，其它已关联来源被置于逐源报告，报告的「20/20」也计入 11 个未完成的请求。`1000122120.jpg` 中《学生会也有洞！》第 2 集显示 TvTFun 取址 HTTP 403。顶栏 AGE 表示目录匹配候选，旧界面缺少足够的区分。其余 `1000122116.jpg` 至 `1000122118.jpg` 延续此前播放反馈。单次 403 或目录读取失败无法证明整个来源下线。

- 已关联来源每源一行，优先展开本集可尝试线路。继续保留核对版本、线路组、手动集数映射、撤销与逐源重试。只有当前确切线路有选中勾，近期失败与手动指定按实际状态显示。待确认候选仅预览，未完成与无可靠结果进入可展开核对详情。
- 详情与播放器从当前有效绑定、目录、集数和线路判断是否提供直接播放。本集尚未匹配时，在来源面板继续核对。待播资料、原集数与观看记录保留。原生主目录未建立时允许显式打开候选自己的目录，避免把其它来源的集数当作原目录身份。
- TvTFun 优先采用该集已声明且通过原 URL 检查的直接媒体候选，手选仍锁定确切线路。回执标注目录、线路、地址或页面解析阶段，失败标记显示实际来源。短暂失败在同集、同源版本和同路由下短期降序。宿主明确收到不支持的网页嗅探请求时，该集线路退出自动播放候选，同站其它作品和集数保留。

源码审阅以 `78d791f` 为基线。两个新截图的实际播放、各站现有访问权限与真机面板显示仍需观察。

## 来源徽标与播放回归 · 2026-11-19

用户反馈「现在动漫也播不了了」并追问「我要的 tag baget 去哪了？还是说你没设计图标？」`1000122114.jpg` 显示首页仅有来源数量和一条未确认属于动漫的柚子轮播内容。`1000122117.jpg` 显示《冰之城墙 第二季》第 15 集的 AniCh 重复标识错误。`1000122116.jpg` 和 `1000122118.jpg` 显示取播放地址阶段通用的 HTTP 403 文案，对应前者和《FX战士久留美》第 1 集。截图没有请求来源或安装版本，无法确认是哪一个上游接口拒绝。

- 首页轮播、最近更新、搜索、详情和播放共用可点击的来源标记。内置站点有图形或字标，资料站保留既有评分标记。封面继续打开聚合详情，徽标展示该卡的真实来源和可选候选。搜索候选可以在原作品下预览来源目录。已关联、候选以及已确认剧集线路有各自的交互和状态。
- 默认轮播和跨源最近更新收录声明原生 `latest` 能力且提供动漫更新目录的来源。当前为 AGE、金牌与 yzx。没有可用的轮播封面时读取现有本季资料。已启用来源的具体分区仍可从片源目录入口浏览。
- AniCh 在同一作品与剧集接口内合并相同标识和完整 URL 的记录。自动线路继续尝试该集返回的不同地址。手选线路在自身标识出现多个不同地址时提示改选，旧的唯一标识继续可用。
- 来源召回有独立时间上限。欠缺证据的暂存搜索结果会触发补查别名，并为核对来源目录预留时间。自动播放在既有时间预算内继续尝试已确认的同集线路，并可补查一轮未完成的来源。错误区分取地址与播放器阶段，保留实际来源以及自动查找的停止原因。手选线路继续锁定该线路。

本轮基于 `1d8c598` 做源码、调用点和差异审阅。两部番在用户设备与网络上的起播、实际换线结果和徽标展示待验证。

## 番剧聚合、资料恢复与处理观测 · 2026-11-19

用户反馈集中在两张同名番剧卡、失败栏目只显示重试、Anime4K 无法判断是否实际在工作、必须先选资料站以及详情缺少丰富的评分和评论体验。对应截图为 `1000122097.jpg` 至 `1000122104.jpg`。以普通漫画的作品卡、来源选择、评分标记和讨论组件为功能与界面参照。

- 轮播和最近更新先按作品分组，再截取预览数量。金牌和优可返回完整同名且没有已知冲突时可以共用一张卡。搜索也使用相同的片源展示分组。年份冲突、权威作品 ID 冲突、同站多条同名等情况继续区分。组内原始条目进入详情，用作实际目录核对的候选。片源目录、选集和起播继续保留原始来源、作品和集数身份。
- 新番、分类和可比较的排行条件默认组合 Bangumi 与 AniList，独立加载、分页和失败恢复。资料范围移入筛选的次级区域。专属指数继续由对应来源提供。相同的可信站点 ID，或可唯一核对的全名、年份及已知作品格式，才合并资料和评分。评分标记复用漫画的来源标记和排版，保留各站实际分制。打开详情时已有观察先显示，AniList 评分按真实 ID 或可唯一核对的官方标题查询。
- 已确认的 Bangumi 番剧有评论页签。复用普通漫画的公开短评解析和评论组件，支持剧透区分、缓存、刷新、失败恢复和分页。来源面板显示聚合带入的候选和自动查到的歧义条目，可直接核对目录。已关联、待确认和排除状态分别显示。
- 发现资料原有自动首次加载与缓存。本次增加按栏目条件、页码和线路读取，短暂失败会有界自动重试，进入页面或切换线路时重新检查。成功结果留在原位，健康来源可以继续分页。线路配置或确定性错误等候修改或手动处理，真实限流按服务端信号处理。
- Android Anime4K 面板展示实际成功提交的处理帧数、近期提交速度、渲染线程采样 CPU 时间和增强中间纹理的容量估算。等待、暂停、回退和缺少可用观测各有独立反馈。实时增强以处理帧为单位，系统 GPU 利用率和最终呈现帧率仍无对应观测。

源码核对覆盖聚合身份、来源与分页归属、缓存和重试、作品详情生命周期与 Android 处理计数。真机体验、各来源直连与实际延时仍需对应运行证据。之前未接入的站点协议和进阶功能见下文清单。

## 来源状态、搜索和续看 · 2026-11-19

用户要求「彻底优化重做动漫模块的产品逻辑以及各种体验」，问来源「这么多没准备」是实现问题还是无法支持，并希望以 Lanerc 的功能、流畅和免代理体验为参照。当前对话附图 `1000122094.jpg` 和 `1000122095.jpg` 分别展示视频和其他分类，未提供安装版本。用户补充资料库已有 Lanerc APK，可按需准备逆向工具。

| 截图条目 | 源码依据与处理 |
| --- | --- |
| yzx、AkiAnime、DMBus、AniCh | 已有随包适配，金牌也属于这一组。旧界面把目录条目与运行中的内置源混列，还给内置项提供必然失败的脚本更新操作。同站合并为一行，本机已有历史脚本时仍可查看和管理两个实现，各自保留真实 ID、绑定和开关。TvTFun、AGE、GiriGiriLove、LMM22 四个固定内置源也进入管理视图。 |
| 23、Cain | `38fb37f` 曾因 HTTP 兼容性默认停用。现版已有对应的限定接口适配，新的自动准备允许启用。旧存储只有开关值，无法区分初始默认与手动停用。保留保存值，详情解释历史原因并提供开关。 |
| LMM85 | 有公开直链适配。页面验证或媒体嗅探所需的宿主能力尚未接入，仍可能遇到特定线路不可用。LMM85 与 LMM22 是独立来源。 |
| 囧次元 | 原参考是 ENC1 包，当前宿主未实现这一格式，也未确认其完整能力。显示在参考目录。 |
| 酷漫星、咸番鸭、yulu、漫时光 | 这四项声明的是漫画图片接口。动漫播放器无法消费该接口，漫画宿主也尚未移植这些协议。显示在参考目录。 |
| 弹弹play | 目录中的独立脚本可准备。播放器弹幕面板当前通过原有官方服务按凭据匹配，另支持有明确适配的站内弹幕。脚本安装状态不能代表播放器正在使用它。 |

### 体验与验收

- 来源首屏直接显示当前启用的视频来源，待准备、停用、添加和参考内容通过「更多来源」进入。浏览和弹幕保留真实用途。沿用项目的 MotionDialog、LiquidGlass PANEL、Ovio MotionTabs、AnimeSelection 和现有设置开关。来源详情保留各自的操作，实际退出面板后才跳转 Bangumi 或 AniList。观察项使用真实来源 ID，当前线路配置与浏览、剧集及关联搜索反馈各自归属。
- 自动准备同时最多处理两个来源。下载失败退避，摘要不符停止执行，组件、兼容性和保存失败显示各自原因。失败条目可按状态单独重试。停用选择、移除记录、已安装版本、固定摘要和现有来源数据继续作为权威状态。
- 首页先读已有分源缓存，后台原生和脚本分别限流。打开单个来源可优先获取，批量重试沿用后台限流。首页保留显式选择和可复用轮播项。搜索优先发送已取得的结果，允许较快来源追加下一页，失败按来源和实际页重试。普通 Bangumi 搜索使用现有缓存有效期，显式重试或更改该线路后重新请求。
- 续看读取到保存的同一集时复用普通播放入口。已有确认线路直接尝试，尚无线路时显示本集匹配过程。目录缺少该集时保留历史并给反馈。保存上一段进度失败时提示，允许继续打开本集。进度变化本身不会再触发整部番的来源选项重建。
- 「播放设置 → 缓冲与恢复」显示当前资源来源、实际选用的应用线路和会话打开至首个视频帧的观测，包含同一会话内内核重试时间。只在播放器报告已经渲染画面后显示该用时，纯音频、失败和缺少观测的平台分别有反馈。此计时不含打开作品、找集或解析地址。

依据使用 [Lanerc 1.1.6 上传样本研究](https://chatgpt.com/space/page_e184ced85e8c8191985ad8153f1c6375) 的配置先显示缓存、多源结果逐源到达、同集换源和首帧分阶段做法，以及 [Mihon 来源管理](https://mihon.app/docs/guides/getting-started) 对扩展目录与实际来源的区分。上传的 ARM64 样本已完成静态调用链分析，先前模拟器未成功启动样本。本版沿用项目已采用的玻璃、Motion 和播放器实现，没有依赖反编译产物。国内 Wi-Fi 和移动网络直连及首帧速度仍需要实际设备观测。

本批完成源码和差异审阅。正式构建使用 main 的既有工作流，界面密度、键盘／触摸、安装后速度、真实播放和来源可达性按设备证据验收。仍开放的源协议见下文清单。

## Anime discovery and playback redesign · 2026-11-19

Lanerc's [resource FAQ](https://app.lanerc.com/newsinfo/1070035.html) describes ingesting, encoding and publishing episodes ahead of playback. Its [release notes](https://app.lanerc.com/newsinfo/1070160.html) describe offline caching and super-resolution separately. A [LINUX DO report](https://linux.do/t/topic/1064787) covers one older client and connection; national routing and present startup timings remain unmeasured. The visual reference uses an artwork-led home with direct paths to updates, rankings and categories. [Animeko](https://github.com/open-ani/animeko/blob/main/docs/contributing/code/media-framework.md) documents independent source results and preference-based playback. [Kazumi](https://github.com/Predidit/Kazumi) demonstrates native source-rule discovery, and [Mihon](https://github.com/mihonapp/mihon) demonstrates useful local history, library and offline behavior.

| Scope | Implementation contract |
| --- | --- |
| Workspace | Keep the independent Anime sidebar entry, one anime navigation row and existing cover density controls. Discovery shows real featured content, source updates, the broadcast calendar and season highlights. Categories own independent filters. The source directory is reachable from discovery and the menu, retains source-scoped positions, and exposes actual source categories, filters and pagination. Video cards can open their native catalogue directly. |
| Catalogue identity | Distinguish a broadcast date, a source upload, a ranking metric, and a verified playable episode. Keep source work, canonical work, season, edition, line and episode identifiers throughout discovery, search, resume and downloads. |
| Cold return | Use the existing anime cache owner to restore individual source feeds with source/revision/route identity. Refresh independently, retain usable content and allow scoped retry. Defer the native player until entering playback, with position updates observed in the player pane. |
| Search | Start Bangumi and eligible native sources independently. Bound concurrent requests, publish arrivals progressively, and retry the failed provider and cursor while preserving successful hits. Group suggestions retain exact source references and require the existing association checks. |
| Detail | Place the poster, title, primary playback action and exact resume progress together. Keep episode-group selection, play state, source selection and search accessible in the episode area. Disclose description and secondary controls on demand. |
| Playback | Use confirmed episode options and honor explicit choices. Prefer a known stable exact route for automatic playback and preserve the fallback path. Prepare only the actual next episode's short-lived resolved address during stable foreground playback. Keep the existing native player, download owner, format support and 4K enhancement distinction. |
| Verification | Review source and publish one guarded latest-main batch. CI status, native media availability and domestic telco playback are independent observations. For device evaluation, measure app-entry-to-content and playback-intent-to-first-rendered-frame separately, across cold/warm starts and the user's Wi-Fi/mobile routes. |

Source implementation checkpoint: 2026-11-18. This document distinguishes implemented application paths from the complete requested inventory. It does not certify installed behavior, source availability, rendering performance or successful packaging.

## Current source integration inventory · 2026-11-18

This section supersedes older AGE-first, optional-only QuickJS, six-source private-key blacklist and LMM85-as-LMM22-alias statements below. DESIGN ANIME-INTEGRATION-01–09 preserves the newest user request and evidence boundary. The original 23 unique references contain **16 positively identified video scripts, 1 browse-only provider, 4 comic providers, 1 danmaku provider and 1 ENC1 encrypted package of unconfirmed capability**. They are not 23 usable video sources. The directory now also exposes five distinct native alternatives; these are equivalent integration paths, not five additional original references.

### Actual video integration paths

| Original reference | Application path in this batch | Remaining source-specific qualification |
| --- | --- | --- |
| Jinpai / 金牌 | NativeJinpaiProvider, anime-native-jinpai | Native latest/search/catalogue/resolve; keyword search follows the original first-page-only contract. |
| yzx / Youke 3.0 | NativeYzxProvider, anime-native-yzx | Native latest/paged search/catalogue/resolve; Android/JVM GCM capability required. Saved old script bindings remain supported separately. |
| AniCh | Existing native anime-native-ani | Keep actual anonymous API and source/episode/line identity; no synthetic episodes. |
| AkiAnime | Existing native anime-native-akianime | Public direct paths; verification/encrypted routes are not certified. |
| DMBus / 动漫巴士 | Existing native anime-native-dmbus | Public direct paths; dynamic encrypted routes are not certified. |
| Lanerc | Automatically prepare pinned anime-lanerc-lanerc | Guest/account/verification-dependent capabilities remain subject to actual host support; no borrowed account credentials. |
| Guazi / 瓜子 | Automatically prepare pinned anime-lanerc-guazi | Anonymous state survives fresh JS calls; first-page-only keyword contract retained. |
| Shixiong / 师兄 | Automatically prepare pinned anime-lanerc-sx | Fixed AppV7 HTTP endpoint and actual search.pg; dynamic parser paths still require supported public URLs. |
| LMM85 | Automatically prepare pinned anime-lanerc-lmm85 | Distinct from native LMM22. Direct paths only; WebView validation/sniff capability is not implemented. |
| Gugu / 柚子 | Automatically prepare pinned anime-lanerc-gugu | Fixed AppV7 HTTP endpoint and actual search.pg. |
| Manguo / 追剧 | Automatically prepare pinned anime-lanerc-manguo | Fixed AppV7 HTTP endpoint and actual search.pg. |
| 23 | Automatically prepare pinned anime-lanerc-23 | Fixed HTTP protobuf API, actual binary request bytes and page field. Previously disabled choices remain disabled. |
| Cain | Automatically prepare pinned anime-lanerc-ain | Fixed HTTP static bootstrap, actual page/size contract; returned business URLs still require HTTPS. Previously disabled choices remain disabled. |
| Nmeng / 柠檬 | Automatically prepare pinned anime-lanerc-nmeng | Fixed AppV7 HTTP endpoint and actual search.pg. |
| Shandian / 闪电 | Automatically prepare pinned anime-lanerc-sd | Retain prior response/pagination evidence guards. |
| 07 / 零七 | Automatically prepare pinned anime-lanerc-07 | Fixed AppV7 HTTP endpoint and actual search.pg. |

Thus **5 native alternatives + 11 automatically prepared pinned video scripts** are connected to application paths. Douban and danmaku are also prepared as their own types. Automatic preparation downloads the fixed script and validates it using the existing revision/storage owner; it does not embed every third-party script body in the APK and it does not prove a remote source plays. Existing removal tombstones and disabled settings win. Legacy source IDs/bindings are not migrated by guessing; explicit native choices win over inherited defaults.

### Native protocol correspondence

Jinpai independently adapts [jinpaiapp.js](https://js.z1i.cn/js/jinpaiapp.js), SHA-256 6f924a30e23fac63681e3d4d23ee5adb5ad76c10459b2e063d442a7a96286dfe. Preserve the original sorted unescaped parameter/timestamp MD5→SHA1 signing, anonymous headers, six published HTTPS hosts, anime type 4, vodId/nid and real resolution fields. Host discovery is bounded and cached per route; no total-count-derived episodes or guessed 1080P labels. Actual playback Origin/Referer/UA are retained. There is no dependency on downloading this script for the native path.

yzx independently adapts [yzx.js](https://js.z1i.cn/js/yzx.js), SHA-256 6056afbb33ffbc7bbb71e2632a31c66fe6d575389983b4f2076dcae599a14e16. This pinned source is **Youke 3.0 / com.youkevideo.app**, not the older community Baiyun 2.5 protocol. Its HTTPS bootstrap is https://ss.trgfd.cn/cache/index/com.youkevideo.app.json. Preserve the 12-byte-nonce AES-GCM layout, separate content/play protocol keys, actual sid/ji/jiIndex/group meanings, local random device identity, host/version/route-scoped in-memory device response and one refresh on 10301/10303. Re-read the actual catalogue before resolving to reject reordered indices; source mode attempts 3→2→0 keep the same selected episode. Ordinary HLS/TS, HTTPS direct and image-prefixed HLS candidates retain actual source meaning; their playability remains unobserved.

Both native catalogue methods may re-read an old saved revision after disable/re-enable or an application update, while checking the exact source/work/canonical URL. Returned catalogues carry the current revision; resolve requires that exact revision. This corrects the pre-integration review blocker without rewriting history or accepting stale assets.

### Script host and transport corrections

The public RSA constants in Guazi and five AppV7 sources are client response/parser decoding material, not a user's account private key. The earlier blanket classification blocked real integrations and is removed; digest validation, private user options and media/public-host guards remain. The host now carries the original UA alias, noCookie and binary body encoding through to the transport. HTTP Referer is validated independently of the actual HTTPS media URL; the exact pinned old yzx “never” sentinel means omit Referer.

Only the following static public protocol requests gain cleartext support on Android/JVM. The source ID and reviewed script digest must match before the host creates this capability; raw JS request parameters cannot grant it.

| Source | Exact endpoint | Method |
| --- | --- | --- |
| 师兄 | http://app2.dsx.ac/ndsx.php/v7/logs | POST |
| 柚子 | http://91.110.236.125:38569/nxyz.php/v7/logs | POST |
| 追剧 | http://23.224.111.70:7788/nzdr.php/v7/logs | POST |
| 柠檬 | http://198.16.32.138:12345/nxnm.php/v7/logs | POST |
| 零七 | http://23.224.53.35:2345/nllq.php/v7/logs | POST |
| 23 | http://118.184.173.5:19987/app/bn/v2 | POST |
| Cain static bootstrap | http://frontend-static.kurte.cn/server_list_v2.json | GET |

No query, fragment or embedded credentials are accepted on these HTTP requests. The JVM and iOS transports snapshot the host-granted list, check every hop, retain actual public DNS/IP and route ownership, reject generic Cookie/Authorization on HTTP, reject cross-origin body redirects and HTTPS→HTTP downgrade. Android network security remains closed by default with only the seven exact hosts admitted. Dynamic HTTP parser hosts, HTTP media and data-URL manifests still require separate support.

[Guazi original](https://js.z1i.cn/js/guazi.js), digest b1de6ca1f34b47d11d351ee540445da321d547902b08502edfe062fa501e2be9, keeps device/token/node/cooldown in top-level globals. Creating a new JS context per operation previously lost that anonymous session. LanercSessionBridge saves only those validated fields through the existing private getItem/setItem owner, capped at 16 KiB and scoped to source, digest, revision and route. It skips explicit ext overrides, preserves a real recent registration failure even with an empty token, clears stale/future cooldown, and cannot save a cancelled or superseded invocation. It does not persist detail caches or copy original crypto constants.

### Runtime, home and playback

The existing pinned QuickJS-kt 1.0.18 API dependency already supplied the engine libraries, but packaging excluded them. Android now includes its four supported ABI libraries and loads the bundled library through the existing runtime owner; existing license notices and exact upstream bytes are retained. Runtime packaging checks those bytes/ABIs and keeps optional engine archives for compatibility with older APKs. Only QuickJS changes to bundled; inference engines/models and Steam keep their existing policies. Actual APK size and installed loading are not yet observed.

AnimeSourceBrowseController removes the AGE special case. It loads source latest/homeSections independently and shares the source/revision/route-keyed first-page request with source browsing. A failed feed returns failure data without cancelling sibling feeds. The retained FeaturedCarousel interleaves real video objects from completed feeds; its first feed follows actual successful completion, not fixed AGE ordering. Bangumi can enrich discovery independently. Source title objects remain authoritative for catalogue navigation; metadata-only cards do not become playable by title resemblance.

The Android player removes HLS→Progressive container fallback: opening an HLS playlist as a progressive media file is not a stream repair. Existing bounded network retries and the prior guarded image-prefix extractor remain. AGE automatic alternatives now use the selected group and strictly matching known episode/edition routes, avoiding guessed mappings across incomplete catalogues. Private playback diagnostics keep at most 12 records / 64 KiB, recording real error code/stage, source ID, generation, build and cause classes; no URLs, titles, tokens, cookies or exception bodies. The existing manual report entry can include them; nothing is automatically uploaded.

### Explicitly open capability and evidence gaps

- LMM85 WebView/validation/sniff branches and Jcy ENC1 unpacking are not implemented. The encrypted package is not an empty script or a proven working video adapter.
- Conditional unreviewed dynamic HTTP parser/media or data-manifest returns may remain unsupported. Static script review cannot establish which branch a live episode returns.
- Four Lanerc comic protocols have not been ported into the manga host. They are separate from the preceding JM bitmap-restoration fix, whose successful installed image outcome remains unknown.
- iOS crypto/HTTP capabilities differ from Android/JVM. Registration and common source code do not establish full iOS source support.
- All 23 original static references were fetched and matched to the recorded digests; no scripts were executed and no business/media endpoint was probed. Independent source review is not compilation or playback.
- The latest screenshot does not identify an installed build or response bytes. Actual no-proxy reachability, first-frame time, continued playback, first-use preparation and UI/gesture quality remain pending actual CI/user evidence.

Publish as one expected-head latest-main batch with ordinary CI activation only, under the project validation boundary. Do not wait for artifacts, add a status-only push or announce “all sources instantly playable”.


## Historical carousel and stream compatibility · 2026-11-18

DESIGN ANIME-REPAIR-01–06 records the current report and three before screenshots. The anime carousel adapts MangaFlow's existing FeaturedCarousel: first six already-loaded AGE updates, otherwise an already-loaded discovery section. No extra metadata request or loading gate. Original source/discovery objects remain the navigation authority. Manual paging, a visible next card, retained item identity, bounded height, cover-derived color, full poster, compact indicators and reduced-motion/input handling add hierarchy while retaining recent/today/season feeds. Installed visual/gesture behavior is not yet observed.

### Diagnosis and reference scope

The screenshot's “这条线路返回的内容无法作为视频文件读取” is emitted by AnimePlayer.android.kt for Media3 container-parsing failures. It does not identify the fetched bytes, proxy requirement or a Bangumi outage. Bangumi owns metadata/calendar identity; native/script providers resolve playback. Independent source fetching and direct native-title opening already existed at the base.

Read reference: [community Windows Lanerc casting adapter](https://github.com/Asern-l/lanerc-cast-windows/blob/661bce36ab79381f0e56dff1ef8dca8369733e78/lanerc_proxy.py), specifically its PNG-boundary and MPEG-TS recognisers. This is community interoperability code, not proven official Lanerc APK source or speed evidence. It establishes a concrete compatibility candidate: an actual image prefix before transport-stream bytes. MangaFlow implements its own guarded extractor handling, without transplanting a proxy, credentials or network owner. ECxiaozong/lanerc-source exposes EasyBangumi compatibility scripts, not verified official source; embedded guest credentials are not suitable application-wide credentials and are not copied.

### Pinned Android HLS contract

The project uses Media3 1.11.1. Actual sources read:
- [HlsExtractorFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsExtractorFactory.java), DefaultHlsExtractorFactory and HlsMediaChunkExtractor: preserve subtitle configuration, text output format and lifecycle delegation.
- [HlsMediaChunk](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsMediaChunk.java): each network open creates a fresh ExtractorInput; reusable extractors span segments. Clear retries request the consumed subrange. AES retries reopen/decrypt and skip consumed plaintext. nextLoadPosition comes from the actual input position.
- [ExtractorInput](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/ExtractorInput.java): peek and committed read/skip positions are separate.

AnimeHlsExtractorFactory first peeks eight signature bytes; ordinary media stays untouched. Only an image signature enables a bounded scan. PNG requires IHDR and a complete IEND chunk boundary; JPEG requires EOI. The following five complete 188-byte packets require MPEG-TS sync and valid basic header bits. Prefix budget: 256KiB. On confirmation, reset peek and consume exactly the prefix through the original input. Truncated, malformed and over-budget candidates consume nothing; actual I/O errors propagate to Media3.

The wrapper checks each new input, including reused-extractor segments and retries, then delegates parsing. Online CacheDataSource and offline FileDataSource retain original bytes/positions, without transformed caches or artificial lengths. Both HLS paths use the factory. This is plaintext extraction: a prefix outside AES ciphertext or another encapsulation is not implemented or verified. It is not a universal media repair or browser/player emulator.

Automatic AGE selection now places known dynamic-only xigua wrappers after current-parser-compatible lines. Sorting follows original road/episode URL construction, retaining direct media, manual selection, source fairness/cooldown and exact canonical/source/episode/group identity. Existing Vurl resolution and equivalent-episode fallback remain unchanged; age-player-contract.md records unsupported signed Go/WASM player cases.

### Outcome boundary

Source/interface inspection found no concrete API or retry-position blocker. No failing response capture, playback/source probe, local build/compile, test or UI automation was performed. This batch adds guarded compatibility and source ordering; successful playback and direct-connect latency still require actual runtime/user evidence. JM restoration is separately scoped in DESIGN ANIME-REPAIR-06; catalogue counts and source completion do not prove displayed pages.

## Independent anime entry and UI correction · 2026-11-17

The user explicitly rejected the previous layered navigation and requested a direct anime sidebar entry without altering the manga experience. DESIGN ANIME-UX-01–08 records the exact correction, adopted layout and source evidence. Existing sibling root routes are retained, with adjacent 漫画/动漫 labels, no media switch inside either page and no extra manga-toolbar width reserved for anime.

Anime now has one visible navigation row: 首页、追番、榜单、新番、番表、片源. The five discovery feeds below remain independent owners; 追番 adds its saved collection/history/folder view at the same application level. Root search, feed layout and one retained More menu replace the scattered global tools. The menu contains accounts, offline downloads and source management; source browsing owns its local refresh. A failed section still retries locally. Pending review remains reachable even when the current library filter is empty.

The detail renderer prioritizes play/resume, work source context and episodes, with description and related works below. Episode management remains exact and available through its local More menu. Saved detail/library choices, source-preview return context, app-owned player, canonical/source/revision authority, offline leases and Anime4K are unchanged in ownership. Actual scene/menu exits gate underlying input and hand off before opening the next panel. Source/diff/contract reviews are complete; current installed visual, motion, system-return and large-font acceptance remains unverified under the existing no-tests/no-local-build boundary.

## Reference and decisions

The user requested that Lanerc's sources, functions and interaction designs be implemented in MangaFlow, then improved. The reference is the uploaded Lanerc 1.1.6 / `com.qsreod` APK, SHA-256 `ee44900cb1217d00f2fe4a7c53f39cbac071f8dddb564e45f99edb1c71f837d2`. [The original analysis](https://chatgpt.com/space/page_e184ced85e8c8191985ad8153f1c6375) contains APK identities, public-source findings and the unsuccessful emulator attempt. Full application source licensing was not verified. MangaFlow implements compatible behavior independently and retains its own existing glass/motion components. It does not embed the APK, its native libraries, private credentials or the remote script bodies in this repository.

The original compatibility integration base was `7e71ae5e95291eb8c771ffa8a74d25722950d16d`. The richer discovery slice below starts from `a475e48cc04dc12634e688daf3a20b2d2e65ff1e`, preserving the later unified bookshelf, reader-experience and Anime4K work. Existing canonical Bangumi identities, the four native providers, tracking review, native cache owners and manga behavior remain the foundation. The compatibility protocol is separate from manga script semantics.

## Richer discovery: research and adoption · 2026-11-16

The latest user rejects sparse anime content and two-cover rows, and asks what Lanerc and open clients actually connect to. Exact wording, scope and observable requirements are DESIGN ANIME-DISCOVERY-01–08. The current provider inferred from the user's spoken “BGN” is Bangumi/BGM. Source inspection found that the previous popular tab was a Bangumi rating rank, newest meant first-air-date ordering, the calendar was a weekly broadcast schedule, and search reused the discovery result owner. Native playback sources had no recent-update browser. Installed script browsing was hidden behind a dialog and dropped source remarks. These are source findings, not an observation of the user's installed screen.

### What Lanerc actually combines

Lanerc 1.1.6 has separate content responsibilities; it is not one comprehensive Bangumi endpoint:

| Layer | Read evidence | Meaning and limitation |
| --- | --- | --- |
| Default discovery home | Public configuration `sources.defaultKey=douban`; the retained Douban script exposes popular animation, Japanese anime, animated films and Chinese animation categories. | Browse-only metadata. Its cards are not a video catalogue or playable asset. |
| Independent rankings | Public [lanerc_rank.js](https://js.z1i.cn/js/lanerc_rank.js), script version3.1.0, SHA-256 `68ba0b790bad8d18ce015e94526f13cacd8a4b4fd3196b12bc1a2725dcfe891e`. APK configuration/repository classes include C1670e10, C3273xp, C0657Kp and C0397Ap; ranking state includes C3250xZ/C3090vZ and EnumC2850sZ. | Calls the old Lanerc `/app/rank` contract. “Trending” selects the current-quarter group; “popularity” selects the largest non-current-quarter group; “score” combines groups, deduplicates native IDs and sorts `vod_score`. None of these labels alone proves current viewing activity. |
| Independent week schedule | Public [lanerc_week.js](https://js.z1i.cn/js/lanerc_week.js), version3.1.0, SHA-256 `78cea10e0779ebaaefd130ace955292bd36722fb5454aef8715bee96225421d3`; C0979Xa owns calendar state and Asia/Tokyo treatment. | Calls `/app/week`. `vod_total` and a time parsed from remarks do not establish that a matching playable episode is available. |
| Each source's home | Read Douban, AkiAnime and AniCh scripts plus the typed `homeSections/categories/searchFiltered/related` bridge. | Source-specific recommendations, latest entries, categories and sort/filter choices. They must retain their source identity and actual capabilities. |

The two rank/week scripts contain the default candidate host `https://v3.yyghk.com/` and dynamic host discovery. Research read the script text only, without calling its business endpoints, adopting private credentials or presenting these hosts as currently available. The configured discover script URL `https://js.z1i.cn/admin/sources/discover.js` returned404 while reading public source material; that is not a live test of the application. The observed public configuration remains version8, updated2026-11-05, plaintext SHA-256 `7413fdc6754d94ac65887b7f2deabea815de423b4d86d47109efb633e4176aeb`.

### Open-client and API comparisons

| Reference and actual reading | Adopted decision | Boundary / why not copy the whole product |
| --- | --- | --- |
| [Animeko architecture](https://github.com/open-ani/animeko/blob/42ef5d4616bb64f4530ebb56247f7847c3329da8/docs/contributing/architecture.md), exploration screen/view model, trends and schedule services at `42ef5d4616bb64f4530ebb56247f7847c3329da8`. | REFERENCE independent exploration feeds, full ranking/schedule destinations and separation of metadata from media sources. | Its richer exploration also uses its own backend. AGPL implementation code is not copied or linked; its service availability is not promised for MangaFlow. |
| [Bangumi server v0 contract](https://github.com/bangumi/server/blob/522cc1dedb6ff4f683b17856c3ba970e820ab6b4/openapi/v0.yaml), subject schema and [search heat implementation](https://github.com/bangumi/server/blob/522cc1dedb6ff4f683b17856c3ba970e820ab6b4/internal/search/subject/doc.go); [calendar contract](https://github.com/bangumi/api/blob/master/open-api/api.yml). | ADAPT public search queries with exact rank/score/collection-popularity meanings, year/quarter date ranges and tags. Keep Bangumi canonical subject and episode identities. | Heat is the sum of five collection states, not concurrent viewers. Experimental search and upstream cache behavior remain upstream constraints. The calendar is a broadcast schedule. |
| [AniList official media query guide](https://github.com/AniList/docs/blob/03281c0a4bbf0c7f2097e0c935cddaed1096aa65/docs/guide/graphql/queries/media.md), [field definitions](https://github.com/AniList/docs/blob/03281c0a4bbf0c7f2097e0c935cddaed1096aa65/docs/reference/object/media.md), pagination and rate-limit documentation. | COMBINE complementary recent trends, popularity, score and seasonal/genre queries. Use actual `pageInfo.hasNextPage`, native AniList/MyAnimeList IDs, score scale100 and real next-airing metadata where supplied. | Trending is recent related activity; popularity is list membership. IDs are not Bangumi IDs. No catalogue mirror or artificial combined ranking. Existing request/backoff behavior remains; no assumed rate-limit guarantee. |
| [Miru AGE v0.0.4](https://github.com/miru-2-0/repo/blob/aea94f7e8a16689aeb9e510a86cd4a6cadab286f/repo/agedm.io.js), MIT/appdevelpo. | ADAPT native recent-update browsing: `/v2/catalog`, `order=time`, page and size50, `videos` with `id/name/cover/uptodate`. | No script installation needed. Source update remarks stay raw; a list is not proof that every episode resolves or plays. Existing attribution covers this additional catalogue adaptation. |
| Aniyomi source popular/latest capability, Miru latest/search/checkUpdate contracts, and Anikku feed documentation/current source surfaces. | REFERENCE explicit capabilities and separate browsing from update detection. | Do not treat a method name, manga-oriented feed or documented feature as an observed working anime flow in this application. |
| Jikan public MAL API documentation. | DEFER an additional catalogue provider for this slice. | Another upstream rate/cache policy and identity mapping would add maintenance without resolving the current content/density gaps better than the chosen Bangumi/AniList combination. |

The decision is to combine the useful content structure with public metadata queries and known native/source browse contracts. MangaFlow's own server is not required for this slice. Real cross-device accounts, push notification delivery and the other service-dependent capabilities in the open inventory remain separate work.

### Implemented discovery contract

The discovery workspace has five destinations: home, rankings, seasons, weekly broadcast calendar and sources. Home contains independently owned today/current-season/recent-trend/high-rank/next-season sections plus the AGE recent-update summary. A section opens its full destination with the same query. Ranking and season filters use each provider's actual supported sort/genre/date contract; switching provider does not reuse an incompatible sort as if it were equivalent.

`AnimeDiscoveryRepository` keeps provider/query/page cache identity and original observation times. Every weekday projects the same cached weekly response; a same-instance lock rechecks cache freshness after an in-flight weekly request completes. Explicit refresh still requests its own update and never claims to bypass the upstream's cache. `AnimeController` gives each feed and search separate results, page/error state, cancellation and generations. Failed refresh retains readable content; a page failure retries that page. Deduplication affects display only: a valid overlapping page advances the cursor and preserves the upstream continuation flag.

`AnimeSourceBrowseController` is application-owned. Native AGE and enabled, installed script sources retain separate capabilities; selection, revision, route, category, filters and related-title requests fence late results. The home summary and AGE first page share one in-flight request and success cache. Its freshness uses the existing DISCOVERY cache preferences; explicit refresh and expired cache re-read while preserving previous cards on failure. Source contracts that only return a page list continue on a nonempty page and stop on a legitimate empty list; schema errors are not represented as successful empty results.

Cards retain raw `updateText`, explicit `ratingText` and known episode count. A source may use remarks for a rating or tagline; the UI must not prepend an invented “updated to” claim. AniList references keep foreign identity and require an explicit Bangumi counterpart unless an exact already-saved association exists. Saved native matches compare source, ID and URL, and current discovery revisions are checked again before shortcuts or preview/association. A same-title hit is never automatic identity proof.

Density reuses the adopted cover, glass, Ovio/MotionTabs and retained popover family plus the existing `DiscoveryLayoutControl`. Ordinary phones default to three columns, wider windows adapt and the explicit column preference is preserved locally and in backups. Narrow/large-font constraints protect readable card widths. Home previews expose different sections with compact cover/title/metadata rows; complete results remain available in each destination. Search and source browsing keep their own return context. This is source implementation of the selected design, not installed visual, animation or accessibility acceptance.

Research remains open for the explicit broader inventory below. Current API reachability, installed appearance, source playback, provider-specific login and actual frame rate were not probed. This slice follows the established single guarded latest-main publication and ordinary CI-activation boundary.

## Implemented user journeys

| Journey | Application behavior | Principal source |
| --- | --- | --- |
| Find a show | Independent home sections; Bangumi/AniList rankings and seasonal queries; broadcast weekday selection; native AGE recent updates; separate canonical search, paging, related subjects and history. Real source/metric meanings and query-local errors remain visible. | `AnimeDiscoveryRepository`, `AnimeController`, `AnimeDiscoveryPane` |
| Open a show | Display retained metadata first; read bound source catalogues independently of the metadata request. A failed refresh preserves usable data and reports its own error. | `AnimeController`, `AnimeMetadataCache` |
| Browse a provider | A full discovery destination exposes native AGE updates and installed video/browse sources' real home sections, categories, filters, paged and related cards. Source/version/route guards preserve identity; browse-only cards select a canonical counterpart and cannot become video assets. | `AnimeSourceBrowseController`, `LanercBrowse`, `AnimeSourceBrowsePanel` |
| Associate multiple sources | Search providers independently; retry or append one bucket. The user chooses the work/season/line, with explicit native episode mappings where ambiguous. Source version changes invalidate stale preparation and selections. | `AnimeSourcesPanel`, `AnimeController` |
| Continue watching | One dismissible resume dock opens the saved canonical episode after its exact sources are ready. A later playback checkpoint can make the dock reappear. | `AnimeLibraryPane`, `AnimeLibraryStore` |
| Organize the library | Followed/history views, local folders, pinning and disclosed status/actions. Clearing positions preserves followed/completed state; removal is confirmed in the owning panel. A persisted per-subject clearing marker suppresses legacy unowned position records. | `AnimeLibraryPane`, `AnimeLibraryStore` |
| Change episode/source | Resolve while retaining the current native session. Cancellation leaves the old playback intact. Save its current checkpoint before committing replacement; cross-asset positions require an explicit continue/from-start choice. Failed opening exposes one guarded previous-session restore action. | `AnimeController` |
| Use the player | Retained native surface across fullscreen/layout changes, current-episode positioning, reverse order, episode-number search, keyboard actions, double-tap seek, hold speed, input lock and one retained settings panel. | `AnimePlayerPane`, `AnimePlayerSettings` |
| Adjust playback | Source resolutions, real native video/audio/subtitle tracks, scale, speed, volume, background intent, actual PiP where available, buffer targets and bounded recovery. Unsupported native capabilities do not appear as working controls. | `AnimePlayer` platform owners |
| Continue automatically | Persistent autoplay, next-episode address and startup-byte preparation, opening/ending skip and optional exact-source recovery. On supported backends a confirmed predecoded frame can reuse its paused native player. A sleep deadline or episode-end pause cancels pending continuation. The temporary startup work is separate from the offline queue. | `AnimeController`, `AnimePlaybackSettings`, `AnimePlayer` platform backends |
| Save a video | Explicit app-owned queue, pause/resume/retry/delete, byte/segment progress, strong-validator resume and atomic completion. Android can retain the queue through a foreground WorkManager task. Play completed files through a store-issued local capability. Active local readers retain a lease while deletion waits. | `anime/downloads`, `AnimeLocalMedia` |
| Show danmaku | Official search and explicit episode selection, plus the observed exact AniCh station protocol. Settings include opacity, size, density, color, duplicates, blocked words and signed time offset. Timeline seeks use binary search; the visible overlay interpolates the native playback clock and shares physical rows. | `anime/danmaku`, `AnimeDanmakuOverlay`, `AnimeDanmakuPane` |
| Back up and restore | Existing local/WebDAV backup includes anime entries, mappings, positions, settings, folders, search history and privately installed source packages. All documents validate before mutation; older backups leave anime data intact. Credentials, cookies, private Ext parameters, expiring stream addresses and video bytes are excluded. Partial multi-store failures identify the stores already changed. | `storage/LibraryStore`, `AnimeLibraryStore`, `LanercSourceStore` |
| Compare tracking accounts | Existing Bangumi/AniList per-field review, exact main-episode numbers, owner/revision guards and persistent local undo remain in place. No remote destructive action is inferred from missing/failed reads. | `AnimeTrackingRepository`, `AnimeTrackingPanels` |
| Enhance anime during playback | The existing video-settings panel offers original/light/fine profiles, persists the choice and reports configured/processed/unavailable/failed separately. All three native backends use the original pinned Anime4K CNN sources; enhancement does not create a new media asset, route or progress record. | `Anime4KShaderLibrary`, `AnimeVideoEnhancement`, platform player adapters |

## Sources and compatibility

The reference directory contains **23 distinct script references**, originating from 24 remote registration rows. It classifies **16 video, 1 browse, 4 comic, 1 danmaku and 1 unknown** script; these are capability findings, not 23 working video services. The four native TvTFun/AGE/giriGiriLove/LMM adapters remain independently available.

| Type | Referenced scripts | Current treatment |
| --- | --- | --- |
| Video | Lanerc, 金牌, 瓜子, 师兄, yzx, AkiAnime, LMM85, 柚子, DMBus, 追剧, 23, AniCh, Cain, 柠檬, 闪电, 零七 | Explicit installation, enable/disable, update, previous-version rollback, video/browse adaptation and full opaque episode flags. Individual sources may require capabilities or accounts described below. |
| Browse | 豆瓣发现 | Real provider discovery only; canonical search before association. |
| Comic | 酷漫星, 咸番鸭, yulu, 漫时光 | Listed with their actual image/comic capability. Their reader/plugin conversion remains open; they are not registered as video providers. |
| Danmaku | 弹弹play script reference | Listed/installed by capability. The full public script contract was not retained in the prior research, so its payload is not guessed. The separate official authenticated integration and exact AniCh path are implemented. |
| Unknown | 囧次元 | Listed with an explicit unverified-protocol reason; not promoted to playable status. |

`LanercJavascriptHost` supplies the observed synchronous host protocol on the existing optional QuickJS engine. The runtime isolates each invocation, serializes mutable per-source sessions, freezes route and installation revision, bounds memory/network work, propagates cancellation and rejects late publications. HTTP binary responses, cookies, encoding/crypto helpers, private KV and Ext are handled through the existing platform transport/storage. Parameters and cookies are private per source; backup imports never execute scripts during validation. Installation itself needs the application's JavaScript component to be available.

The inventory is intentionally pinned to the researched references, including provenance digests and alias information. Updates replace only explicitly installed packages and retain the previous version. Script content in a private user backup is validated against its recorded hash and a known descriptor. The public catalogue is not evidence of reachable sites, accepted credentials or playable streams.

## Actual platform boundaries

| Capability | Android | Desktop | iOS |
| --- | --- | --- | --- |
| Native backend | Media3/ExoPlayer | Existing Mediamp/mpv | Existing Mediamp/AVKit |
| First video frame acknowledgement | Native rendered-frame callback | Unknown when not exposed by backend | Unknown when not exposed by backend |
| Next-episode frame preparation | Acknowledged first frame in a retained paused standby | On Windows, mpv reports a decoded frame at the requested offset in a retained paused standby | Address and supported startup-media cache |
| Audio/subtitle/video tracks | Native track groups and selections | Exposed mpv feature tracks | Hidden where Mediamp does not expose track metadata/selection |
| Picture in picture | Actual Activity capability with Manifest support | Not advertised | Not advertised by this bridge |
| Scale, speed, volume | Native | Native | Supported exposed features only |
| Background audio | Existing app/session owner and foreground policy | Native audio with reduced video work | Existing audio background configuration and exposed player behavior |
| Offline playback | Whitelisted file DataSource and lease | Whitelisted local range server and lease | Whitelisted local range server and lease |
| Anime4K | Media3 effect, OpenGL ES 3 and signed FP16 intermediates; explicit x2 Restore/Upscale profiles | Existing libmpv GPU shader hooks; accepted/read-back configuration only, actual processed frames unconfirmed | Same AVPlayerItem video output and Metal on iOS 15 / Apple GPU family 3 or later; original AVPlayerLayer fallback |

Windows uses Mediamp's regular Skiko-selected renderer to initialize a small transparent standby surface. The optional decoder stays muted and paused and prepares the resolved source's exact start position through the existing routed loopback. `video-frame-info` reads an actual current mpv video-output frame and `time-pos` verifies it belongs at the prepared offset. This is a decoder receipt. The app's full-size surface has its own presentation lifecycle. Media byte admission is capped during preroll. Inactive network reads park once a frame is prepared and resume only when the same engine promotes. A changed source, route, offset, options, playback context or expired asset falls back to the normal start path. Source contracts were inspected in [Mediamp 0.5.1](https://github.com/open-ani/mediamp/tree/v0.5.1) and [mpv's video-frame property](https://github.com/mpv-player/mpv/blob/e48ac7ce08462f5e33af6ef9deeac6fa87eef01e/player/command.c#L2585). Device latency, GPU costs and renderer handoff remain unverified.

### Anime4K: required local enhancement

The latest user correction makes Anime4K a required part of the player; see DESIGN ANIME4K-01–06. The shared library contains the complete original Restore CNN S/M and Upscale CNN x2 S/M files from [bloc97/Anime4K `7684e958`](https://github.com/bloc97/Anime4K/tree/7684e9586f8dcc738af08a1cdceb024cc184f426). Light combines S/S; fine combines M/M. These are application profiles, not the complete upstream Mode A/B/C presets. Native 4K source decoding is a separate capability. Upscaling 1920×1080 by two in each dimension yields 3840×2160; it does not recover the original master or make a 720p source into native 4K.

The UI retains the existing PANEL/MotionTabs controls and starts with original video. Settings and portable backups preserve the selected profile. Processing is local GPU work; no enhancement account, cloud API or uploaded video is required. The small/medium original learned coefficients and all dependent passes remain intact. GPU intermediates use signed floating-point textures; this is not an unrelated sharpen filter carrying the Anime4K name.

Android adapts the fixed GLSL dialect to Media3's actual SDR effect pipeline. Its selected profiles explicitly perform x2 upscaling, even if the final display scales the result down. The current decoder format supplies the source dimensions; the effect reports its own input/output dimensions. Normal mode changes replace effects on the existing player. A failed video-processing graph gets one guarded replacement native renderer on the same media source, route, media-session token, position, speed, volume and track settings, with Anime4K disabled for that attempt. Both synchronous and asynchronous failure before the recovered native first frame are local renderer failures, not permission to consume source-recovery attempts or select a different provider.

Desktop writes the embedded shaders into a private versioned resource directory, verifies complete contents and atomically replaces files before appending both literal paths to the current mpv shader list. It preserves the existing VO, hardware-decoding and route/header owners. A complete list read-back yields `CONFIGURED`, never a fabricated `ACTIVE`, output resolution or GPU timing. While enabled, an approximately 500 ms check observes dimensions, color transfer and VO, removing the list when PQ/HLG or unsupported/unknown color is observed. This is not atomic with every decoded frame: a changing track may retain the previous configuration until the next scheduled observation, and that interval is not a strict response bound. If clearing cannot be confirmed, the interface explicitly asks to reopen the video. The original upstream viewport-dependent upscale condition remains in force.

iOS adapts [Anime4KMetal `c46b8652`](https://github.com/imxieyi/Anime4KMetal/tree/c46b8652a203d259ffbc3d39f21aa54263e11584), specifically `Shared/MPVShader.swift` and `Shared/Anime4K.swift`, while retaining the current routed AVPlayerItem. Asynchronous shader/plan preparation, CVMetalTexture-backed decoded frames, an arena of FP16 intermediate textures and an MTKView perform the actual passes. One GPU frame is in flight; stale item/configuration/surface callbacks cannot promote it. `ACTIVE` requires both GPU completion and drawable presentation acknowledgement. Reported processing dimensions describe the final CNN texture, not the screen or its letterbox. The upstream viewport condition determines whether the x2 pass is needed. Background/surface removal and OFF stop new enhancement work and restore original rendering. HDR/wide-gamut, protected video, embedded soft subtitles and unsupported geometry/format paths keep the original system presentation. The application deployment target remains iOS 14; unsupported devices retain original playback.

Licenses are retained in the embedded sources, repository notices and actual platform resources. The original shader bytes were compared with their pinned upstream blobs; source/API review and diff checks do not establish shader compilation, visual equivalence, sustained FPS, energy use or installed acceptance. Those remain unverified under the current no-local-build/no-probe boundary.

Offline admission covers progressive video files and finite, unencrypted HLS playlists with whole-file TS/fMP4 resources. A single media playlist can carry multiplexed audio. A master with one video variant and exactly one declared external default audio rendition is saved with both media playlists and their validated resources. The offline master references only files in the existing local lease, so both tracks must complete before the episode becomes playable. Multiple video variants, non-default alternate audio, packed AAC/MP3 segments, live/updating playlists, encryption keys, byte ranges, DASH and externally attached subtitles remain outside this format contract. Unsupported manifests receive a format-specific message without publishing partial media as a completed episode.

`MangaApplicationServices` owns one `AnimeDownloadStore` for playback, download UI and Android WorkManager. Android's network/storage-constrained worker uses the existing data-sync foreground service and one download-progress notification for the video queue. It can continue a new saved download in the background once admitted by Android. A stopped worker releases its execution lease. The store retains the request, journal and validated partial media while awaiting system rescheduling or the app's return. Only records with persisted pending intent are automatically restored. Explicitly paused, removed, failed and pre-upgrade interrupted entries preserve their previous resume semantics. Current playback opening/buffering still takes priority. Windows keeps its queue running while the application process is alive and restores interrupted entries paused when relaunched. iOS uses foreground execution and explicit resume after process interruption. Android service admission, notification behavior, battery policy and installed file playback require device observation.

Recognized standalone offline containers are MP4/MKV/WebM/TS on Android and desktop, and MP4 on iOS. HLS uses its separately validated TS/fMP4 resources. Container recognition is not a guarantee that every device can decode every contained codec.

Official danmaku requires this application's own AppId/AppSecret, stored in platform secret storage. With no credentials the interface explains configuration and does not pretend that a search succeeded. Reference documentation: [official access guide](https://doc.dandanplay.com/open/), [official Swagger](https://api.dandanplay.net/swagger/v2/swagger.json). No Lanerc client identity is borrowed. The official endpoint is independently registered for source routing.

## Complete inventory: remaining work

The user asked for the entire product inventory. The following are **open**, not completed by the presence of APK classes, menu labels or a source-directory row. There is no claim that all Lanerc functionality has been ported.

| Open capability | Concrete missing implementation or dependency |
| --- | --- |
| Dynamic browser media sniffing | A platform host that executes the target site's page while preserving route, cookies, cancellation and media identity. The compatibility runtime currently returns an explicit capability error. Static/native resolvers remain available. |
| Some advanced iOS script crypto | The current platform codec does not provide all RSA/GCM modes required by individual scripts. Such sources fail with a concrete capability reason; there is no TLS downgrade or dummy decrypt result. |
| Protected source login/captcha/binding | Source-specific authentication/UI contracts and a legitimate user session are needed. `needLogin`/`needBind` are surfaced; no embedded token or fabricated authorization is used. |
| Four additional comic scripts | Convert their verified image contracts into the manga plugin/reader pipeline with its exact chapter/image ownership. They are not videos and are not silently installed as manga adapters. |
| Unknown 囧次元 protocol | A readable verified provider contract is required before an adapter can be implemented. |
| Cast/DLNA/AirPlay | Device discovery, native renderer connection, URL/header/route compatibility and session transfer. The player does not display a nonfunctional cast button. |
| Shared watching | A real room/signaling service, ownership, reconnect, clock synchronization and invitation/authentication design. The APK's room strings do not supply a reusable service contract. |
| Community, comments, posts and rewards | Service contracts, the application's own account model and moderation/business requirements. The uploaded client backend is not an authorized MangaFlow community service. |
| Update notifications/calendar subscription | Durable scheduling, update comparison and platform notification behavior. The visible calendar does not imply a subscription/background notifier. |
| Whole-account import, automatic cross-device synchronization and remote deletion | Explicit account-wide pagination, field ownership, deletion/conflict semantics and review UX beyond the existing subject-level Bangumi/AniList comparison. |
| Extra native engines and external subtitle file import | Additional supported engine/decode and native file-selection contracts. Anime4K now has the real integrated source paths and explicit boundaries documented above. |
| Offline format expansion | Multiple video variants, on-demand alternate audio/subtitles, packed audio segments, encrypted/byte-range HLS and DASH still require format-specific selection and integrity handling. |

## Review and delivery evidence

This batch uses source/API review and `git diff --check` only. It does not run local builds, tests, API/source probes or device/UI automation, following the repository's established boundary. Installed animation quality, first-frame latency, provider reachability, file playback, accessibility and platform packaging remain unmeasured until an actual artifact is installed or CI reports a result.

The current base had real shared Kotlin compiler failures. Android [run 43853588475](https://github.com/wozhenbang2004/MangaFlow/actions/runs/43853588475), and Windows/iOS [run 43853588249](https://github.com/wozhenbang2004/MangaFlow/actions/runs/43853588249) logs identify the missing `onDismiss`, undefined button name and five missing `runtime` arguments. The reviewed source correction from [commit a8ea310](https://github.com/wozhenbang2004/MangaFlow/commit/a8ea31075a9a249f29486d8c9a41547df1dcafb3) is incorporated with attribution. This does not claim that the newly expanded batch has passed CI.

Root publishes the integrated latest-main batch with an expected-head guard, confirms ordinary Android and multiplatform CI activation, and stops waiting for artifacts. The resulting commit and run links are delivered in chat. No status-only follow-up push or invented runtime result is part of this checkpoint.

Additional public protocol references: [Bangumi v0 specification](https://github.com/bangumi/api/blob/master/open-api/v0.yaml), [Bangumi calendar specification](https://github.com/bangumi/api/blob/master/open-api/api.yml), [Mediamp v0.5.1](https://github.com/open-ani/mediamp/tree/v0.5.1). Existing dependency versions and licenses are retained; no new native engine is bundled by this work.


## Episode-first source loading · 2026-11-17

User feedback: “动漫功能要全部重做卡的要死一堆加载不出来而且 uiux 也不好你看看人家 lanerc 呢人家从来都不会说加载不出来” (message Sentinel_7da0b9f533c08191804a56b38203d928). This slice addresses source catalogue work, not a claim that the entire request is complete.

- AniCh previously fetched every episode's `/vod` station list before returning a catalogue, under an eight-second budget, so a real episode could disappear from the visible catalogue merely because its media request had not finished. The catalogue now comes solely from actual `bangumi/episodes` rows, never the metadata total. Each row exposes explicit automatic selection; only selecting that exact work/episode requests `/vod`.
- TvTFun previously fetched missing station lists serially for every episode in `catalog`. It now gives every real episode an automatic-group entry, retains embedded explicit stations as additional choices, and defers absent station lists until that actual episode is selected. A partly embedded season therefore still has one complete automatic group. Automatic selection tries at most three returned stations, with five-second per-station resolve budgets; it never crosses an episode identity. Legacy explicitly chosen station IDs/slugs stay exact and are not silently replaced.
- Native revisions change with this catalogue contract. Existing source bindings may need fresh catalogue/group confirmation; user library data is not removed or inferred from a failed request. Automatic station resolution is not proof that the resulting media actually plays on a device.
- Seven native adapters are already available without script installation (TvTFun, AGE, GiriGiri, LMM, AniCh, AkiAnime and DMBus), subject to saved disable/removal choices. Optional scripts still use pinned-byte verification; download, validation and save failures now report the actual phase and source name without exposing response data or credentials. The screenshot's old combined failure message did not prove which phase failed.
- Dynamic browser sniffing remains unimplemented: `sniffMedia`/`sniffAll` explicitly fail in the JS host. This change does not borrow Lanerc credentials, alter TLS, install certificates or pretend static resolvers have full browser execution.

Validation: source review and whitespace/diff checks only. No tests, local build, network probes or installed performance/visual acceptance.

### Integration note · 2026-11-17

- Legacy calendar cover failure was traced to the actual official `/calendar` JSON advertising `http://lain.bgm.tv/...`, while the same subject's v0 endpoint advertises HTTPS at the same large-cover path. Normalize only this known CDN from HTTP port 80 to HTTPS port 443; retain public-source HTTPS validation and use the next valid declared size when necessary. Raw calendar caches benefit from decoding the corrected URL without deleting the cache.
- Metadata search, detail, related JSON/HTML parsing and discovery-document serialization now run away from the UI dispatcher. Subject and episode metadata requests share exact subject identity and run concurrently; neither incomplete identity nor failed episodes are presented as a complete detail.
- Existing native bindings whose selected group disappeared are retained and explicitly ask for a new line selection rather than silently selecting the first new group. Original resume records remain intact and cannot be applied to a guessed new episode ID. Existing source-panel selection is the recovery action.
- This batch was inspected statically, with whitespace validation only. No local build or tests ran; user-device loading speed, images, playback and UI remain pending built-app experience.


## Script-engine installation dependency · 2026-11-17

The user screenshot's “来源下载、校验或保存失败” is a combined error, not a confirmed phone diagnosis. Source inspection establishes a separate deterministic first-use defect: optional source installation calls `LanercScriptRuntime.validate`, Android preparation only calls `PanelRuntimeStore.ensureLoaded`, and `ensureLoaded` throws when QuickJS has never been installed. Unlike the manga starter-source installer, the anime installer did not prepare that dependency.

After the script passes the existing pinned digest check, anime installation now awaits the existing component owner's QuickJS download/discovery before validation. A shared mutex avoids duplicate preparation observers; `PanelRuntimeStore` retains OS job deduplication, same-APK-signature receipt validation, exact ABI/version/archive/library hashes, pending-removal and already-loaded protections. Cancelling a source operation releases its observer without cancelling another consumer's OS-owned component download. Source installation uses the application-owned scope and waits for the component terminal state without a two-minute cut-off, so leaving the screen or a slow component download does not discard the current operation. This is in-process continuation only; the component task is durable, but the script installation intent is not yet persisted across process death. No unverified native download, TLS change, private credential or route override is introduced.

All 23 locally retained reference scripts match the configured SHA256 digests and are below the transport limit. This establishes consistency of the retained snapshot only, not current remote bytes or the user's network, runtime installation or disk state. Script packages use the existing `SOURCE_PACKAGES` routing scope; the optional QuickJS component uses `COMPONENTS`. Playback uses the selected anime source route. Changing a playback route alone therefore does not reconfigure package/component downloads.

Dynamic browser sniffing remains a separate open slice. The Android isolated-profile, strict TLS and lifecycle cleanup patterns in `CopyWebsiteLogin.kt` are reusable references, not a media extractor. A production bridge must keep every page/resource request on the selected source route, isolate source cookies from unrelated accounts, bind results to the exact episode/revision/session, propagate cancellation and destroy the WebView. Unsupported protected media, service-worker/blob-only flows and unverified media identities cannot be treated as directly playable URLs. No browser sniffer was added by this dependency fix.

Validation: source/API review and diff checks only, with independent review requested. No tests, local build, live network probes or device acceptance.

## Automatic extension preparation · 2026-11-17

The owner then explicitly requested built-in/default automatic source preparation instead of individual installation taps. The native seven-provider baseline remains download-free. A packaged, digest-pinned desired-source manifest now prepares Lanerc, Jinpai, yzx, 23, Cain, Shandian, Douban discovery and the danmaku extension in the application-owned store. This means automatic preparation, not a claim that every upstream protocol or playback URL works. 23/Cain currently depend on cleartext bootstrap endpoints rejected by the host; their verified packages are prepared but disabled with an explicit adaptation status. Native AkiAnime/AniCh/DMBus/LMM alternatives are linked instead of redundantly installing legacy scripts that would suppress native adapters.

Source installation success remains in the existing atomic package store. On process restart, the same packaged manifest resumes only missing eligible packages, while durable per-source removal preferences prevent automatic reinstallation against the user's choice. Existing installed revisions, disabled settings and user options are never auto-updated or overwritten. In-process preparation has per-package deduplication and three bounded attempts (15/60-second retry delays); capability failures stop immediately. The UI observes real pending/preparing/retrying/ready/disabled/failed states, with a shared retry action rather than fabricated installed badges. A separate explicit per-source restore action clears only that source's removal preference and schedules it under the same preparation gate. Global retry never clears removal choices; restore also preserves default-inactive protocol packages and does not enable an already-installed disabled package. A per-source user-action gate serializes the entire remove/restore sequence across preference and package changes. Background installation never takes that gate; cancellation/join happens before acquiring the package gate, avoiding a removal deadlock.

Six retained script references embed third-party private keys (Guazi, Shixiong, Youzi, Zhuiju, Ningmeng and Lingqi). They are not copied or automatically downloaded; new installation is blocked with an independent-adaptation explanation. Existing package data is not deleted or made unreadable by this policy. Comic and unknown script protocols remain explicitly non-video. A script's presence or compatibility with the JS validator does not authorize registration, login, CAPTCHA completion or reuse of another client's credentials.

Read-only retrieval of the two URLs visible in the owner's screenshots returned HTTP 200 with 31,884 bytes for guazi.js and 47,064 for lanerc.js; both hashes exactly matched the packaged pins and retained source snapshots. This rules out observed cloud-side file replacement/downloading failure at that moment, not the user's phone route or storage. The first-use missing-QuickJS path remains an independently established code defect addressed by the prior slice. Package requests retain SOURCE_PACKAGES routing, the native component retains COMPONENTS routing, and playback retains its exact anime source route; none is silently changed.

Validation remains source review/diff checks only. Automatic first-use, cancellation, process restoration, upstream source reachability and playback require real product acceptance; no local tests/builds or synthesized execution were run.

### Native detail identity enrichment (2026-11-17)

Search cards from AniCh/AkiAnime/DMBus omit release years, so exact-name recall alone
cannot confirm a season. Catalogs now preserve the exact native work ID/URL and can
supplement release year from that same work's detail response: AniCh structured
`airdate` (retained reference `ani.js:detail`), DMBus
`og:video:release_date`, and AkiAnime's year-filter link strictly inside its
`.detail-info` block. Detail title must equal the search title, years must be
unambiguous and must not conflict with an existing year. Missing/ambiguous evidence
stays missing, never copied from the requested Bangumi subject. AniCh metadata is
optional and bounded to two seconds; failure does not discard actual episodes.
LMM22 has not inherited the different LMM85 site's metadata contract.

This is source review, not live playback, mainland-network or device acceptance.
Dynamic WebView sniffing remains unsupported: interception alone does not guarantee
per-source routing, while AndroidX ProxyController changes every WebView in the
process. No global proxy, borrowed browser session, TLS bypass, external secret or
unrestricted remote-JavaScript bridge was added.

### Progressive all-source association and playback recovery · 2026-11-17

The owner clarified that anime must follow manga's automatic all-source association behavior, not stop at the first successful playback source. The existing manga implementation was read: `UnifiedMangaRepository.startDiscovery` owns independent per-work source tasks; each reliable result attaches progressively and the view model reprojects the catalogue without changing the reader cursor. Anime now follows that ownership pattern, not manga's chapter-number identity rules (different anime seasons commonly restart at episode one).

Opening a detail starts independent discovery for every currently eligible installed source, with four concurrent slots and per-source deadlines. Each verified work identity is linked and its observed episodes are projected immediately; other sources keep running when playback starts or its foreground preparation is cancelled. Later installed/re-enabled sources are picked up separately. New identity metadata reconsiders retained candidates instead of blindly rerunning every search. Search failure/timeout stays unavailable, distinct from a successfully empty result. Explicit unlink is a durable per-work exclusion; normal retry never overrides it, manual re-association clears only that exclusion, and existing manual bindings/groups remain authoritative.

Association records structural identity only, not playback health. Default play consumes all uniquely verified same-episode options with bounded per-option resolution and a foreground deadline; later-arriving options can join recovery without restarting discovery. A failed existing option therefore no longer traps all subsequent default plays on the same source. Explicitly selected options remain locked. Actual player errors use the existing platform recovery-allowed guard and bounded recovery count, but now retain automatic-versus-explicit play intent; automatic retry is not hardwired to the failed source. New/missing settings default to recovery enabled; saved false values are preserved. Renderer/protected-media failures and nonzero cross-asset resume confirmation retain their existing safeguards.

Recall and confirmation remain separate: full title/original-title recall may lack year, but only trusted detail year or a nonconflicting shared authoritative metadata ID confirms association. Strong shared IDs permit aliases. Detail reads are limited to plausible records, not all search results; unreadable rivals are unknown rather than negative evidence and equally proven versions require selection. Native-owned works retain their IDs and only exact known kind/number can map to another source, never list position. Unknown or conflicting seasons/editions are not forced into playable cells.

For GiriGiriLove, only exact `chs`/`cht` groups from a positively identified native `vod_id` record may act as equivalent subtitle tracks. Complete kind/number/normalized labels must agree. Simplified subtitles are the initial preference, not a speed/health claim. Unknown groups, title mismatch and missing identity remain conservative; an explicit group is not rewritten.

AGE group equivalence is limited to the known xigua/ffm3u8/bfzym3u8/wjm3u8/lzm3u8 routes emitted from one requested detail response, with identical complete episode keys and labels. Unknown/partial groups stay manual. M3U8-named routes precede xigua only as a parsing order, never a measured health claim. New native default groups are marked automatic so equivalent tracks can expand them; legacy saved groups remain explicit because their original user intent cannot be reconstructed. Background association can invalidate a prior whole-entry undo receipt; stale undo remains refused rather than overwriting newer bindings.

Validation: independent static review completed; whitespace checked. No local build/tests, live-source requests or device execution was performed for this change. Structural association, successful address resolution and actual first-frame playback remain separate results.


### Fair bounded playback recovery · 2026-11-17

Default resolution keeps the 45-second foreground budget and at most eight seconds
per option, but no longer spends that budget on every route from the first source.
It visits sources round-robin. Failed or timed-out actual resolutions join the same
60-second low-priority cooldown as player errors; cancellation is not failure.
Within each cooldown tier, bounded in-memory canonical-work/episode/route source and option attempt
order makes later retries start with least-recently attempted candidates, rather
than repeating a fixed prefix. Cooldown demotes candidates instead of hiding them.

The budget is checked before attempt admission, so an option not requested due to
exhausted time is not marked tried or failed. Stale candidates rejected before
admission are excluded from that foreground cycle without being recorded as network
attempts. Explicit option playback remains one locked option. All identity, route,
source-revision, cancellation and delayed-start checks remain in force. Histories
are in-memory only and bounded to 256 entries per map; no health/speed claims,
persistent blacklist or background media requests were introduced. Admission captures the exact candidate, source revisions and route; configuration changes and stale identities never add a failure penalty to the new route. Invalid route settings have no sortable history key and fail only that candidate inside resolution, without aborting healthy sources or substituting a direct/system route. Static review
is required; no local builds or automated tests are run for this slice.

### Unreadable local library must not block online details · 2026-11-17

Observed user image `1000121989.jpg` shows the Bangumi work《你好 我是受心上人所托来做恋爱药的魔女》with zero episodes, disabled playback and the local-library read-failure message. Source tracing confirms `awaitReady()` rejected before the metadata request, and also gated native catalogues, source aggregation and playback resolution. The screenshot does not establish disk exhaustion or the underlying JSON/I/O failure.

Loading completion is now separate from writable readiness. On failed reads, the original document stays untouched and the store exposes an explicit read-unavailable session. Only native/automatic playback associations can populate that session in memory; old favourites, progress, exclusions and manual bindings remain unknown, not recovered or deleted. Metadata, catalogue and playback resolution can proceed. Progress checkpoints are explicitly unsaved, including the outgoing-playback checkpoint, so the damaged library cannot block starting or changing episodes. Persistent edits, export and tracking remain write-gated. Existing explicit validated backup restore replaces the session and increments restore authority, cancelling old playback and requiring the detail to reopen. No automatic restore, reset or overwrite was added.

Detail and library views retain an explicit local-data warning; unread records are not presented as an empty collection or a known unfollowed status. No local builds, tests, probes or playback verification were run. Source review and ordinary CI remain distinct from installed acceptance; diagnosing the original read failure remains open without device evidence.

### Native subject metadata round-trip compatibility · 2026-11-17

Source inspection found a concrete format-1 writer/reader mismatch introduced with native browsing in `2cd36a8`: `sourceSubject` retains source title external IDs, and `nativeDetail` also retains catalogue external IDs. AGE catalogue adds `age`, GiriGiri may add its verified `girigiri` ID, and source search may supply a Bangumi metadata ID. A normal native playback checkpoint serializes this enriched subject, while the native subject decoder required the key set to contain only `native-source`, rejecting the whole library on reopen.

The decoder now retains these additional string metadata IDs, just as the writer does. Native source syntax, exact native source namespace/hash shape, library binding source and catalogue-ID/URL hash, native episode ownership, and the canonical Bangumi branch remain unchanged. Additional metadata IDs do not convert a native subject to a canonical subject or enable tracking; the native-source discriminator still owns that path. No records are dropped or default-repaired and loading does not rewrite the document. This is a demonstrated source-level round-trip incompatibility, not proof of the user's particular file or I/O failure. No local builds/tests/probes were run; actual device recovery remains unverified.

Independent review identified the second same-path mismatch: native full-source aggregation persists bindings to other verified sources, but the decoder required every binding to be the native owner. It now validates each native subject's binding group has exactly one intrinsic owner with the unchanged source/catalogue-ID/URL hash, while retaining its additional associations. Existing per-subject/per-source uniqueness and entry ownership checks still apply; no cross-source binding can stand in for a missing or conflicting native owner. Native entries without any bindings remain readable as before (for example a follow action before a catalogue finishes). Both fixes are read-only compatibility changes, with no document rewrite on load.
