# Windows 性能专项 · 2026-11-19

## 目标、授权与证据边界

用户明确要求：“这不是一次简单的 Bug 修复，而是 Windows 平台的系统性性能优化工程”，“优先保证实际帧时间、帧稳定性和输入延迟，不准以显示高 FPS 数字替代真实流畅度”，“没有 Windows 实机时，完成所有能进行的源码优化、构建与静态核查，并交付可以在用户电脑实际运行的诊断方案”。本轮授权包含实际代码、构建、Lint、Profiling、提交、推送及修复本次引入的 CI 编译问题，不包含新增单元测试或端到端测试框架。

本轮起点为 main `f170938b2983f8d5930ef5fb5e19ef004678a3a5`，已整合 main `3bf567b5c91370ff3059a5d5a24cfe52060bf447`，保留并行工作的播放显示模式、TemporalFix、Anime4K、实时 Vulkan、番剧独立分区与侧栏、增强状态语义和原有网络设置变更。实际构建结论见文末。本记录取代旧批次“只触发 CI、不做本地编译”的限制，仅适用于本次明确授权的 Windows 性能工程。

目标设备为用户的 i5-14600KF、RTX 5060 和高刷新率 Windows 显示器。当前实施环境为 Linux，没有该显卡、Windows DWM、Windows 输入设备或高刷新率屏幕。**本轮源码优化、编译结果、Windows 安装包与实机流畅度是四种不同证据；没有把任意一种代替另一种。所有优化前后 Windows P95/P99、输入像素响应、GPU Present、120–300Hz 稳定性均未实测。**

### 需求对应

| 标识 | 原任务范围 | 本轮落实与保留边界 |
| --- | --- | --- |
| WIN-PERF-01 | A：实际后端、VSync、DWM、DPI、多屏、窗口与互操作 | 固定版本源码调查、运行时实际后端/显示模式/DPI 记录、单次进程后端对照脚本；保留默认后端、VSync 和原生窗口框架。DWM/Present 与多屏结果待实机 ETW。 |
| WIN-PERF-02 | B：主要页面、状态粒度、列表、排序、绘制分配 | 根状态投影、窗口 Local、侧栏布局阶段更新、下载目录按需投影、多源搜索后台聚合；保留已有稳定 key 和正确排序缓存。 |
| WIN-PERF-03 | C：Haze、玻璃、弹簧、Morph | 只构造实际使用的材质分支、缓存静态绘制资源和文字度量；保留模糊、折射、阴影、弹簧、几何与图层语义。未声称 GPU 离屏次数已实测下降。 |
| WIN-PERF-04 | D：图片、分块、取消、复用与批次提交 | 图片作用域内复用已核实的 WebP reader/元数据/已读压缩输入，受界闲置输入，取消过期任务，桌面分块按请求帧合并发布，去除 fallback 的全尺寸 ROI 中间副本。 |
| WIN-PERF-05 | E：统一调度、前台优先、视频与 AI 竞争、空闲 | 继续由 ExperiencePolicyStore 准入；缓存配置去重；CPU 场景压力、交互与可见图等待使可选增强让路；空队列及暂停视频不定时轮询；原生属性读取离开 UI 线程。 |
| WIN-PERF-06 | F：真实监控、日志、外部工具 | 隐藏开发者面板、真实 Skiko CPU 场景回调、EDT 排队、资源/GC/图片/MPV 数据、JSONL/原始 CSV/JFR 与 PresentMon 分组分析器。无数据明确为空。 |
| WIN-PERF-07 | Android/功能/数据/审美约束 | 不改账户、缓存身份、书架、进度、下载格式，不删除功能或降低清晰度；新增平台工厂在 Android/iOS 仍委托原路径；共享变更进行编译及源码语义审查。 |
| WIN-PERF-08 | 自主交付、对照验收、迁移门槛 | 正常集成最新 main、提交和推送，跟进本次 CI；14 个场景和原始数据采集方法见下文。没有可靠同效果对照，不迁移 GPUI/Makepad/Qt/WinUI。 |

## 真实架构与渲染决定

实际项目依赖为 Kotlin 2.4.21、Compose Multiplatform 1.12.2、Skiko 0.150.2、Coil 3.2.0、Haze 2.1.0、Mediamp 0.5.1。Windows 应用使用 JDK/JVM 21；共享 JVM 字节码目标仍遵循原构建配置。窗口是 ComposeWindow/AWT，原生 Windows caption/Snap/resize 由 `DesktopWindowChrome.kt` 保留，视频与网页存在 Compose/原生 MPV/JavaFX/Swing 互操作。

### 默认与回退

- 未设置覆盖值的 Windows x64 路径从 Skiko `DIRECT3D` 开始，其后为 `OPENGL → SOFTWARE_FAST → SOFTWARE_COMPAT`；这里依据固定 `v0.150.2` 的 `bestRenderApiForCurrentOS` / `fallbackRenderApiQueue`，不是根据运行系统猜一个值。运行时仍以 `ComposeWindow.renderApi` 为准。
- 保留 Skiko 默认 VSync 开启、默认缓冲策略、默认适配器选择、默认资源缓存策略。没有设置固定 60/120/300fps 的自制计时器，没有强行切 OpenGL、ANGLE 或软件后端，也没有应用来自更新 Compose 版本的实验配置。
- `SKIKO_RENDER_API` 仅用于一次对照进程。Auto 清除脚本本次子进程的该环境覆盖；如果机器另有 `-Dskiko.renderApi` 或驱动覆盖，实际运行日志会反映最终后端，不能把 Auto 名称当作实际 Direct3D 的证据。
- 固定 Mediamp 0.5.1 的 Windows Direct3D 视频路径使用共享 D3D11 纹理与 Skia D3D12 互操作；OpenGL 路径存在每帧 CPU 读回和 bitmap 拷贝。因此 OpenGL 是兼容性/实测对照选项，不能推广为通用视频性能优化。
- 保留 `compose.interop.blending=true`。未在没有原生播放器、网页、弹窗及输入对照的条件下改变混合合成模式。

### 帧、刷新率与窗口

`SkiaLayer.renderDelegate.onRender` 在当前固定版本中记录 EDT 的场景 Picture。结束后的 Picture 完成、渲染线程绘制、纹理上传、GPU 队列、Present、DWM 合成和扫描输出均不在该计时内。新增压力信号量到的是 **CPU 场景工作的一部分**；即使它很低也不能排除 GPU 或 DWM 瓶颈。

显示模式来自主窗口 `GraphicsConfiguration.device.displayMode.refreshRate`，不是从回调反推，也不把未知替换为 60Hz；记录 DPI、窗口尺寸和可见性。显示器切换事件会刷新观察，采集时每秒再次读取。该 API 的整数模式值不是 VRR 的每帧扫描频率；实际多屏/DPI/VRR 仍需要 ETW 与用户显示设置一起解释。

窗口 resize 原本通过静态 CompositionLocal 使整个内容提供范围失效；改为跟踪实际读取者的动态 Local。侧栏宽度弹簧仍改变布局，只把动画值读取推迟到布局阶段，减少整个导航树随每帧重组。没有声称消除了必要的重测量、DWM 开销或驱动同步。

### 11 月 24 日：交互优先的桌面续做

侧栏宽度现在一次提交给实际内容；选中、按压及标签仍由现有 Motion 绘制，标签动画不再驱动画廊与详情的布局。普通桌面浏览时，未展开的作品菜单不建立浮层过渡状态，展开后仍保留退出和焦点语义。关闭性能面板且未录制时，不更新可选诊断计数和样本环；资源策略使用的独立 EDT 区段测量继续工作，鼠标拖动、滚轮、键盘及窗口缩放的策略触发都保留。打开面板显示本次诊断会话的样本，录制关闭后仍安全写完已进入日志的记录。

这些是源码证实的工作量变化，不能计算真实 FPS 或宣称帧率变稳。验收优先比较相同素材和窗口的滚轮、侧栏开关、连续缩放及番剧播放中滚动；在同一 Windows 设备使用相同显示模式、高 DPI 与主题，CPU 场景样本和原生 Present 分开解释。没有更改渲染后端、VSync、帧率上限、视频和图片质量或下载策略。

## 已确认的机制与代码改动

“确认”表示在当前源码、调用链或固定依赖源码中可复核机制；下表不把它当作该设备上的排名或耗时结论。

| 位置 | 原机制与证据 | 实施 |
| --- | --- | --- |
| `ui/AdaptiveLayout.kt` | 频繁变化的窗口宽高放进 staticCompositionLocal，更新会使整个提供范围失效。 | 改为 compositionLocalOf；断点、字号和响应式消费者不变。 |
| `ui/DesktopAppShell.kt` | 侧栏在组合阶段读取每帧变化的宽度。 | 在布局 modifier 内读宽度；原 spring、展开尺寸和 padding 保留。 |
| `ui/MangaFlowApp.kt`、`SettingsScreen.kt` | 根部订阅完整体验预算、同步进度，使并不消费这些字段的树随发布刷新。 | 高刷布尔投影、设置模式投影；同步状态在保留的弹层/实际分支读取；账户展示使用派生值。 |
| `reader/ReaderScreen.kt`、`ReaderExperienceStatus.kt` | 根阅读器订阅完整预算，增强窗口 effect 对无关状态变化重启。 | 根只订阅 6 项实际行为依赖；完整状态由阅读状态条自身订阅，增强 effect 仅依赖其真正消费字段并读取最新预算。 |
| `ui/DetailScreen.kt`、`FeaturedCarousel.kt` | Windows resize 每个像素变化都进入显式封面请求的 remember key。 | 仅 Windows 的请求宽高向上按 128px 桶变化；可见几何连续变化，保留原上限且不降低需求，移动端分支不变。 |
| `ui/DownloadsScreen.kt` | Lazy item DSL 外为所有作品执行章节 mapNotNull，字节/页进度变化会重建屏外作品的列表。 | 移入可见/预组合的作品 item；展开分支按需投影，稳定 key、目录排序及操作对象不变。 |
| `ui/ScopedBrowseController.kt` | 多来源结果每次发布在 UI scope 对累计内容去重、分组、排序和整理标签。 | 冻结输入后在 Default 聚合，发布前校验查询代次/发布序号，保留来源进度、取消和结果顺序。 |
| `ui/motion/MotionTabs.kt` | 绘制过程中重复分配坐标列表/Stroke/静态颜色，动画相关重组重复测量同一文字。 | drawWithCache 和 FloatArray；动画值在绘制阶段读取；度量复用且保留字体解析失效。 |
| `ui/LiquidGlass.kt`、`ui/glass/BackdropSoftShadow.kt` | 同次组合准备不使用的材质分支，静态组合对象和 shadow tint 重复构造。 | 只准备实际材质分支，按实际参数缓存；视觉质量参数及 Haze 采样关系不变。 |
| `reader/ReaderDesktop.kt` | 每片重新建立 reader、读取元数据；固定 WebP provider 的 readHeader 把整个压缩文件读入 fData，导致同页分块反复读取和分配。 | 每个显示原图作用域持有受界 WebP reader 池；输入尺寸/修改时间检查，EXIF 元数据复用，异常或取消的 reader 不回池。 |
| `reader/PortableReaderImage.kt` | 每块成功立即新建绘制列表，批量到达时连续发布；窗口 resize 重建整条图解码作用域。 | 仅桌面使用 conflated 通知，按已请求帧合并发布；条图尺寸通过快照观察，作用域保留到原图身份变化。 |
| `reader/ReaderDesktop.kt` fallback | 先分配全分辨率 ROI bitmap 再缩成 LOD，产生本可避免的本地内存副本。 | 直接把源区域绘制到目标 LOD surface；保留整源像素上限与损坏图片 readPixels 校验。 |
| `experience/ExperiencePolicy.kt` | 高频 publish 重复应用未改变的缓存配置，其中 setRangeCleanup 会请求维护。 | 结构性配置去重，保留配置版本、失败后重试、现有清理与过期权威。 |
| `experience/ExperienceMeasurements.kt` | 无新输入时，资源快照和准入仍重复排序相同解码/页面大小样本。 | 新样本到达时计算缓存统计，读取复用；桌面 CPU 场景固定环最多每 500ms 后台汇总。 |
| `enhancement/EnhancementScheduler.kt` | 空队列一秒超时轮询，图片命中/进度等 usage 更新驱动无关队列计算。 | 事件唤醒；仅待处理/运行任务持有已有资源观察；Windows 进度最多 10Hz，阶段变化/结束立即发出。 |
| `anime/player/AnimePlayer.desktop.kt` | 原生视频统计读数陈旧，或在 UI 更新路径同步读取 native getter。 | IO 读取，媒体 ready 后首次采样，实际播放时 1Hz；暂停/后台等待状态事件，结果带会话/handle 检查。 |
| `DesktopAnimeVideoEnhancement.kt`、`DesktopAnimeJaNaiEnhancement.kt` | 同步 MPV getter/vo-pass 扫描可能等待原生锁，暂停/后台仍周期扫描。 | 原生观察移出 Main；不满足格式/尺寸/VO/shader 条件不扫描 pass；暂停等待；旧会话异常不能清理新会话的滤镜。 |

### 逐屏检查记录

| 入口 | 已核查与保留 | 尚需实测定位的成本 |
| --- | --- | --- |
| 发现/更新 | DiscoverScreen / DiscoveryPane 的 LATEST/推荐分支、FeaturedCarousel；稳定 key/contentType、视口 warmup 与 Default 取色已有。没有虚构独立 UpdatesScreen。 | 高 DPI 封面纹理上传与 Haze 背景更新。 |
| 排行/获奖 | DiscoveryPane 排行、SiteRankingsScreen、MangaAwardsScreen；已有 remember 排序与稳定 key。 | 超大目录首次分组、切筛选和返回的长尾。 |
| 搜索 | DiscoverySearchResults、DesktopSearchField、ScopedBrowseController；保留 450ms debounce、代次与源身份；新增后台合并。 | 多来源大量返回时真实耗时及取消后的可用性。 |
| 书架/历史 | LibraryScreen、BookshelfBook、ChapterHistorySheet；已有排序缓存、稳定 key 和 IO 章节读取。长按 dragPoint 路径有非 Desktop 守卫，未误改。 | 巨量书架首次排序/条件改变的组合开销。 |
| 下载 | DownloadsScreen、DownloadBookRow、DesktopDownloadChapterPane；已有结构目录缓存，新增可见条目章节投影。 | entries 全量流的 O(任务总数) 筛选/索引仍存在，未声称实现全部按行订阅。 |
| 详情/章节 | DetailScreen、DetailCatalog、ChapterGroups、ChapterTile、VolumeBookTile；分组/定位/过滤已有缓存，卷封面按可见条目和 Semaphore(2) 请求。 | 大目录 resize、多源标记、独立目录弹窗。 |
| 成人区 | 普通组件 adultProfile、ScopedBrowseController、ContentProfileAccountScreen/AdultShelfSyncSheet；账号按 active 加载，已有 Semaphore(3)。 | 多源结果与账号增量更新期间交互；原来源页级语义需设备复核。 |
| 番剧 | AnimeWorkspace、AnimeDiscoveryPane、AnimeSearchResults、AnimeDetailPane、AnimePlayerPane；根只订阅 playbackPhase，完整播放状态在播放器路由内。 | MPV/Swing/Compose 合成与 GPU 增强竞争；七日分组未被无依据当作主要瓶颈。 |
| 设置/增强 | SettingsScreen、ExperienceSettingsDialog、EnhancementCenterPanel；保持面板打开条件、恢复/保存所有权与真实进度。 | 已打开增强中心的局部任务过滤/排序，不影响关闭时才有意义。 |
| 阅读器 | ReaderScreen、ReaderPageImage、AdjacentPagePrefetch、ReaderChaptersDialog、PortableReaderImage 与平台解码；新增根行为投影，AdjacentPagePrefetch 已有任务窗口 snapshotFlow，未机械再删字段。 | 原生取消粒度、LOD/色彩/EXIF/接缝、缩放上传、双页和实际惯性。 |

Coil 的普通 AsyncImage 已通过布局约束解析尺寸，已有前台准入和滚动取消 warmup，不应因为代码没有显式 `.size()` 就判为原图解码。构建仍提示 Coil 3.2.0 声明 Skiko 0.9.4 而 Compose 统一到 0.150.2；固定 JAR 的只读符号检查覆盖 coil-core 的 5 个直接消费者类、34 个唯一成员引用（含继承），未发现缺失成员。该检查不覆盖 native 图像格式/颜色/JNI 运行行为，故保留警告与运行验收，不擅自升级/降级依赖。Haze material3 本身已有 remember、样式具有结构相等性，也不能断言旧对象构造一定导致 GPU cache 失效。

### 图片预算、所有权与取消

原始文件、显示预览、已解码图片和增强衍生缓存继续由既有 owner 管理；没有新建平行全局 bitmap 缓存、改 disk key、改源身份或改下载格式。前台 visible decode 与 optional prefetch 的准入仍经过 ExperiencePolicyStore。长图继续使用原生区域/LOD，并先完成可见片再准备邻片；原图恢复及页级进度未改。

新增 reader 池只保留每张当前显示图片至多 4 个闲置 reader，不是新的解码并发上限。目前仅固定 WebP 0.3.3 获得保留资格：已核查它没有持有返回的解码 bitmap。实际 JDK 21 源码中 PNG 的 `theImage` 和 JPEG 的 `target` raster 会在 read 返回后保留输出，因此 JPEG/PNG/未知插件继续及时 dispose，不用反射清字段，也不把只清 `image` 字段误判为全部输出已释放。

固定 WebP provider 已知压缩输入的**全进程闲置保留预算**为 `min(maxHeap / 64, 64 MiB)`；这是压缩字节而非全部 native 内存或显存。活跃解码仍按现有硬件/手动策略准入。只记录可证实的字节，不能把它当完整 native 内存统计。Windows 封面尺寸向上量化可能多解码少量像素，以减少 resize 请求变化；既有清晰度要求和详情 1024px 上限保留，不能把所有图片路径的内存变化笼统描述为下降。

原图 effect 的全部子任务结束后才关闭 reader。交叉审阅进一步发现 preview/dimension effect 的磁盘租约可能早于 sibling tile effect 的原生解码释放；现在 `ReaderOriginalSource` 原子关联路径和精确 cache key，桌面 tile effect 单独持有匹配的 snapshot，等子任务结束、reader 关闭后才释放。本地文件沿原 owner 路径，Android/iOS 不增加这份租约。租约失效回到现有预览/错误状态，取消后旧错误不回写。

可中断文件 IO 与 ImageIO progress abort 尽快响应取消；固定 WebP JNI 不提供逐步中断回调，在途 JNI 必须自然返回后再释放，过期结果不会发布。不能承诺瞬间中止不可抢占的原生解码。

## 调度、GPU 竞争与运行时

没有添加第二个资源调度器。ExperiencePolicyStore 继续统筹可见图片、预取、下载、增强、播放预留和离线处理。桌面主窗口观测到实际输入或原生窗口移动/resize 时延续现有交互租约；非客户区几何事件只用于让路，不伪造输入延迟样本。可见图片等待、交互租约以及 CPU 场景 P95 超出当前显示预算的 85% 时，让可选增强暂缓。压力恢复具有 3 秒缓冲，统计至少 10 个样本、最多每 500ms 聚合；不同显示预算或闲置后的旧样本不混入该压力窗口。

这些是调度保守信号，不是 GPU 利用率检测。RTX 5060 即使总体 GPU 利用率低也可能存在 render/video/compute 队列争用、带宽和同步等待；内部面板不伪造这些指标。保留当前 realtime owner 预留和 shared owner 关联处理规则，未调整用户手动并发/内存/预取数值。原生增强只能在引擎支持的取消/分段检查点让路，不声称硬件级即时抢占。离线处理已完成分段保留，当前取消分段仍可能重做。

`gradle.properties` 的 `org.gradle.jvmargs` 只配置构建 JVM，不能提高已安装应用堆。实际 Windows 应用参数在 `desktopApp/build.gradle.kts` 的 `compose.desktop.application.jvmArgs`；本轮保留既有参数和 JVM 21 ergonomics，没有盲目设置固定巨大堆、线程优先级或所谓 GPU 线程开关。诊断记录实际 heap 上限、使用量、GC 名称与收集计数/时间，真实暂停用 JFR。打包 runtime 增加 `jdk.jfr` 和 `jdk.management.jfr` 模块。

已核查 `NativeIncrementalUpdate.desktop.kt` 与 `scripts/native_release.py`：运行时 fingerprint 覆盖 runtime 的全部文件。新增模块改变 fingerprint，旧运行时不会错误套用仅 app 文件增量，而是继续走原完整安装包路径；没有改 Upgrade UUID 或用户数据目录。此为升级分支源码核查，实际安装/跨版本升级仍需 Windows 验收。

## 开发者诊断：来源与局限

Windows 主窗口按 **Ctrl + Shift + F12** 打开现有 MotionDialog 风格的开发者面板。记录/停止和打开目录在面板内；关闭面板不终止正在进行的有界采集。平常不显示此面板，资源/EDT 诊断采样仅在面板打开或主动采集期间以 1Hz 工作。Skiko 钩子只观察真实发生的回调，不请求额外帧、不使用永久 frame-clock 循环。

| 数据 | 实际来源 | 解释边界 |
| --- | --- | --- |
| 实际后端 | ComposeWindow.renderApi | 回退后的当前枚举；不把启动参数当实际后端。 |
| 显示 Hz、DPI、窗口 | AWT GraphicsConfiguration/displayMode | 可能未知；不是 VRR 扫描频率。 |
| CPU P50/P95/P99 | 主窗口 SkiaLayer.onRender 前后 nanoTime | EDT 场景回调，未覆盖 Picture 完成/提交/Present；独立 Dialog 未挂此钩子。 |
| 回调间隔 | 相邻真实 onRender 起点 | 原始 CSV 保留所有间隔；面板只聚合明确输入后 1 秒的交互片段，静置不算掉帧。 |
| CPU 超预算/连续次数 | CPU 场景时间与当帧显示模式预算 | 不是实际丢帧计数；PresentMon 才观察 Present/Displayed 数据。 |
| 输入关联 | AWT 分发到下一次主窗口场景回调结束 | 最老待匹配事件，1 秒内未匹配排除；不知道哪一帧改变哪个像素，不是 end-to-end 输入延迟。 |
| AWT 事件年龄 | InputEvent.when 与当前 wall clock | 毫秒级排队参考，受 wall clock 影响。 |
| EDT 等待/阻塞 | 每秒最多一个 invokeLater 探针 | 队列等待参考；pending 年龄保留阻塞证据，JFR 查调用栈。 |
| 进程 CPU、Heap、GC | JDK MXBean / Runtime | GC collection time 不等于精确 stop-the-world 暂停；暂停及 allocation 用 JFR。 |
| RSS、系统内存 | 已有 Experience resource sampler/OSHI | 受原有采样周期影响，记录样本年龄；不是每秒都重新扫硬件。 |
| Coil 解码/命中/任务 | 既有 Experience 真实事件与活动计数 | 生命周期累计和滚动样本各有口径，不把请求数当解码数。 |
| 区域解码 | 新 ReaderDecodeDiagnostics 固定 256 样本环 | 准入后的原生读取+解码+转换，不含 GPU 上传/Present；复用数及已知闲置压缩字节独立。 |
| MPV | 实际 hwdec/VO/decoder/output dropped 属性 | 初次 ready 与实际播放采样，暂停保留旧值并显示年龄；estimated-vf-fps/vo-delayed 仍为估计。 |
| GPU/显存/Present | 内部明确 null/不可用 | 用 PresentMon、WPR/WPA、GPUView；不从 FPS 或 CPU 回调推导。 |

活跃面板的百分位最多来自最近 4096 个回调/关联样本，分块最多 256 个，策略环为 360 个；它们不代表整个采集会话。CPU 原始 CSV 才能计算完整会话分布。CPU 原始记录队列有 16384 条上限，日志显示 overflow，不能忽略溢出后称为完整记录。每次采集默认 120 秒，可设 10–600 秒；JFR 为 profile 配置，单次上限 64 MiB。不同进程与不同后端应分别采集，不能平均多个 P95/P99 当全局分位数。

日志目录为 `%LOCALAPPDATA%\MangaFlow\diagnostics`，每次独立 ID 加时间后缀，保留 `.jsonl`、`-cpu-frames.csv` 和成功时的 `.jfr`。JSONL schema 1 包含指标来源、实际后端、显示设置、版本、PID、时间及未知值；没有 URL、漫画名、账户令牌或媒体地址。JFR 包含线程/类/栈等常规 profiling 信息，提交问题时应只选择必要诊断材料。

## 在 Windows 进行真实对照

先关闭同一数据目录的旧应用，再分别启动一次对照。脚本不终止既有应用、不变更机器环境、不下载或提权工具，不清缓存或用户数据；PresentMon 使用独占随机 session。它从本次应用 JSONL 的 PID 并验证进程开始时间/父子链后才附加，不能误抓其他同名进程。

```powershell
# 在仓库根目录运行；Executable 指向本次安装/解压的实际 Windows exe。
.\scripts\windows\capture-performance.ps1 `
  -Executable 'C:\MangaFlow\MangaFlow.exe' `
  -Renderer Auto -Seconds 120 -Label shelf-auto `
  -PresentMon 'C:\Tools\PresentMon.exe'

# 应用结束后，以相同数据、窗口、主题、缩放、显示设置重做相同动作。
.\scripts\windows\capture-performance.ps1 `
  -Executable 'C:\MangaFlow\MangaFlow.exe' `
  -Renderer OpenGL -Seconds 120 -Label shelf-opengl `
  -PresentMon 'C:\Tools\PresentMon.exe'

# 不提供 -PresentMon 时仍采集应用 CPU/资源/JFR。
# 更多分析参数以脚本 --help 为准。
python .\scripts\windows\summarize-performance.py --help
python .\scripts\windows\summarize-performance.py 'C:\captures\before-cpu-frames.csv' 'C:\captures\after-cpu-frames.csv'
python .\scripts\windows\summarize-performance.py 'C:\captures\shelf-presentmon.csv' --refresh-hz 144
```

Renderer 支持 Auto、Direct3D、OpenGL 和 Software（SOFTWARE_FAST）。软件路径用于定位硬件驱动/后端差异，不是最终质量或性能替代方案。退出这一轮再正常启动即可恢复原来后端选择；实际值以 JSONL 为准。

PresentMon 采用官方 console 参数，保留工具版本、stdout/stderr、PID 和会话信息。没有 ETW 权限或当前版本不支持参数时，脚本如实报告失败，仍保留应用采集，不把空 CSV 当通过。PresentMon 起始点在首条应用采样之后；CPU 与 ETW 并非同时起止，也未做时钟同步。因此该脚本用于稳定交互区间，不适合报告进程启动到首帧时间。

### 对比本次修改之前的版本

旧安装包没有新增 JSONL/PID 握手，不能直接交给 `capture-performance.ps1` 后把握手失败视为旧版性能故障。与本轮前的版本比较时，两版都使用同一 PresentMon 版本、相同参数和目标窗口的外部采集；先在任务管理器“详细信息”中核实本次实际应用 PID，再执行下例。不要选择安装器/更新器 PID，不同时运行两个使用同一数据目录的版本。旧版无应用内 CPU 原始数据时，只比较双方都有的 Present/Displayed 列，不能拿旧版 Present 与新版 CPU 场景时间混比。

```powershell
$mangaPid = 1234 # 替换为已核实的本次 MangaFlow 应用 PID。
$traceSession = 'MangaFlowCompare-' + [guid]::NewGuid().ToString('N')
& 'C:\Tools\PresentMon.exe' --process_id $mangaPid `
  --output_file 'C:\captures\before-presentmon.csv' --timed 120 `
  --terminate_after_timed --terminate_on_proc_exit --track_gpu_video `
  --session_name $traceSession
# 换成新版后重新核实 PID；输出 after-presentmon.csv，再分析两个文件。
python .\scripts\windows\summarize-performance.py `
  'C:\captures\before-presentmon.csv' 'C:\captures\after-presentmon.csv' --refresh-hz 144
```

分析器对 CPU 原始数据以及 PresentMon 的每个 process/swapchain 独立统计；FrameTime、MsBetweenPresents、DisplayedTime、MsBetweenDisplayChange、GPUTime 等列保留各自定义，不拼接成同一种 FPS。带多个 MPV/窗口 swapchain 时，先确认目标窗口/渲染路径，不应把它们合并为一个应用帧率。JSONL 已聚合分位数只作为窗口快照，不再次求合并分位数。

### 14 场景与统一记录

每个稳定交互场景至少做 3 次同样长度的原始采集，分别记录结果和样本数；不要挑最好一次。版本、显示分辨率/Hz/缩放、驱动、窗口大小、内容源及数据、主题/动效、后台任务、视频及增强配置都需要一致。初次文件缓存与热缓存分开；不要删除用户缓存来构造所谓冷启动。

| 场景 | 操作与需保留的条件 | 主要观察与当前状态 |
| --- | --- | --- |
| 1 冷/热启动 | 同一数据分别记录系统启动后首开和正常再次打开；WPR/PresentMon 从进程启动前开始，记录首个可交互画面。 | 启动到首次 Present/可用输入、JFR 初始化热点。当前未测；应用内部后附加采集不能证明冷启动时间。 |
| 2 书架快滚 | 固定书架数量、视图和缩放，连续往返。 | CPU/Present P95/P99、连续长间隔、封面命中/GC。未测。 |
| 3 发现封面 | 固定源及已保存的结果规模，区分网络加载与热缓存。 | 上传/解码与列表回调关联、稳定几何。未测。 |
| 4 排行与筛选 | 固定站点、榜单，滚动并切筛选再返回。 | 筛选反馈、旧结果归属、GPU/CPU 事件。未测。 |
| 5 搜索 | 相同输入节奏、多源返回规模、快速换查询。 | EDT 队列、后台聚合、取消后不覆盖新查询。未测。 |
| 6 详情 | 连续展开/收起同一作品，反向打断动画。 | Morph 连续性、回调长尾、弹窗独立 swapchain。未测。 |
| 7 章节 | 大目录快滚、选择和返回，后台下载同步运行。 | 可见 item 工作与进度干扰。未测。 |
| 8 长条漫 | 同一超长图连续滚动和快速反向。 | 可见区域优先、取消、reader 复用、RSS/GC。未测。 |
| 9 高清缩放/双页 | 相同原图、EXIF、倍率和拖动路径，双页切换。 | LOD 清晰度/接缝、取消响应、GPU 上传和 native 内存。未测。 |
| 10 玻璃动画 | 抽屉/Popover/Dialog/导航反复打开并反向。 | 同等玻璃效果、阴影/裁剪、主窗及弹窗分开。未测。 |
| 11 窗口 | 快速拖动/resize/最大化/全屏，多屏切换和不同 DPI。 | DWM、显示模式更新、EDT 与 Present 长间隔。未测。 |
| 12 番剧交互 | 固定视频/解码器/分辨率与增强档位，播放时开菜单。 | MPV 真丢帧、Video/Compute/3D 队列、UI swapchain。未测。 |
| 13 并行工作 | 固定下载数与增强任务，前台阅读，随后静置/暂停。 | 准入/让路、任务恢复、后台 CPU/唤醒与界面长尾。未测。 |
| 14 高刷持续交互 | 120/144/165/240Hz，设备支持时 300Hz 以上，逐档相同动作。 | 每档实际刷新、Present/Displayed 分布和连续超预算；全部未测。 |

预算分别为 120Hz≈8.33ms、144Hz≈6.94ms、165Hz≈6.06ms、240Hz≈4.17ms、300Hz≈3.33ms。它们是每帧目标预算，不是本轮成绩。P95/P99 改善应来自相同口径的原始样本；高 FPS 数字不能证明尾部、连续卡顿或像素输入延迟达标。若需真正输入到光子延迟，另用可靠输入/显示联合测量或高速摄像；应用回调只提供关联定位。

当 Present 长尾而 CPU 场景很低，用 WPR 的 GPU activity/CPU sampling、WPA 的线程等待与 GPU 调度、GPUView 的队列/提交/显示事件定位 DWM、同步、纹理上传和视频增强竞争；当 CPU 场景或 EDT 等待偏高，用 JFR 检查 AWT-EventQueue 的采样栈、分配、锁及 GC pause。保持工具同版本、相同采集开销，详细方法使用微软/Intel 原始文档。

## 审美与平台兼容复核

沿用 [现有 15 系统约束](../design/reading-and-discovery-redesign-20261119.md)。产品界面不重做；新增面板仅为快捷键披露的开发工具，复用 MotionDialog、MotionIconButton 和主题角色。独立面板没有已捕获 backdrop，使用现有可读 Surface fallback，避免为调试窗口采样自身。

| 体系 | 本轮决定与复核 |
| --- | --- |
| 配色与表面 | 原主题/玻璃参数保留；诊断使用现有 surface 与 onSurfaceVariant。 |
| 形状与圆角 | 原产品几何不变；诊断沿用 24dp 面板形状。 |
| 空间与密度 | 不增加常驻控制台；诊断单层标题与滚动内容。 |
| 排版可读性 | 标签/值对齐，数值使用等宽字体，长来源说明可换行。 |
| 组件/状态 | 原组件与状态所有者保留；记录/停止/打开目录是具名动作。 |
| 动效/过渡 | 原 spring、Morph、retained presence 不改，优化读取与绘制资源。 |
| 图标/符号 | 图标保留可访问名称、tooltip；未知显示 —/不可用，不显示假零。 |
| 图像/媒体 | 不降低原图、LOD 上限或增强质量；来源/页面/缓存身份保持。 |
| 导航/架构 | 不修改普通/成人/番剧入口，开发工具不占日常导航。 |
| 尺寸/输入 | 动态 Local 和原布局约束；面板随可用尺寸滚动，保持键盘关闭/按钮入口。 |
| 阴影/层级/材质 | Haze 采样和阴影几何不改；只复用真实参数相同的静态对象。 |
| 内容/文案 | CPU 场景、回调间隔、输入关联、Present 明确分开，披露采样年龄。 |
| 手感/感知性能 | 高变化值在实际消费作用域读取；慢数据不改稳定几何或结果归属。 |
| 可视化/监控 | 原始 CSV 与来源标签，零/未知/旧样本分开，不用 FPS 替代体验。 |
| 任务/恢复 | 取消后不发布旧结果，旧 native 任务不能清新会话，文件写入失败可见。 |

以上是代码与设计对应关系，不代表已完成 Windows 安装后的视觉、字体、玻璃等效、触控板惯性或用户审美验收。Android 不挂 Windows 渲染钩子/面板，不启用桌面分块合并发布或桌面增强进度节流；平台 factory 保留原生 Android/iOS 区域解码路径。共享排序、缓存去重、队列唤醒与状态投影的语义通过源码复核和构建检查，不冒充 Android 设备验收。

## 构建、交付与开放项

本轮不新增测试或测试框架，也没有执行单元或端到端测试。整合 `3bf567b` 并完成 reader 保留范围与磁盘租约修正后的源代码，在 Linux、JDK 21、Gradle 9.8.2 下完成以下检查：

| 检查 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `:desktopApp:compileKotlin` | 成功，含 shared desktop 编译及桌面入口。 | JVM/Compose 桌面目标可编译，不是 Windows native 运行或性能验收。 |
| `:app:compileDebugKotlin` | 成功，含 shared Android 编译及 app Kotlin。 | Android 编译兼容，不是安装、网络、图片或手势设备验收。 |
| 上述两目标一次执行 | `BUILD SUCCESSFUL in 8m 59s`；21 tasks，8 executed、13 up-to-date。 | 构建耗时，不是应用帧时间或前后性能结果。 |
| PowerShell 脚本 | PowerShell 7.4.6 的实际 Language.Parser 无语法错误。 | 未在 Windows PowerShell 5.1 或 ETW 上执行。 |
| Python 分析器 | AST 与编译语法检查成功。 | 未用伪造帧数据充当性能结果，没有运行新增测试。 |
| 差异与交叉审阅 | `git diff --check` 无错误；图片所有权、策略/原生会话、UI 与最新主线合并已交叉复核。 | 不替代视觉、设备或 native ABI 验收。 |

本地构建使用单 worker、in-process Kotlin compiler 和临时 `-Xmx6g` 构建 JVM 参数；它们没有写入应用 JVM 配置。实际任务与参数如下，需使用项目对应的 JDK/Android SDK：

```sh
gradle --no-daemon --console=plain --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :desktopApp:compileKotlin :app:compileDebugKotlin
```

Windows 完整 runtime、EXE/MSI 和 Android 签名 release 由本提交对应的现有 main 工作流分别构建。关联 Actions、Release 的源 SHA 和真实产物是最终发布依据，交付消息给出实际结果；不把其他提交的成功或本地 Kotlin 编译算成本提交的安装包。新增 JFR 模块的 jlink/jpackage 及 Windows 安装行为需要这一独立构建与设备证据。

仍需目标设备完成：实际 Direct3D/OpenGL 对照；120–300Hz 持续交互；不同 DPI/多屏；触控板与滚轮惯性手感；区域解码颜色/EXIF/损坏文件/长图接缝；原生播放+AI 同时工作的 GPU 队列；独立弹窗 Present；安装/升级保留数据。当前没有相同效果下足以支持技术栈迁移的对照数据，继续优化 Compose。

### 原始参考

- [Skiko v0.150.2 属性、后端及回退](https://github.com/JetBrains/skiko/blob/v0.150.2/skiko/src/jvmMain/kotlin/org/jetbrains/skiko/SkikoProperties.kt)
- [Skiko v0.150.2 AWT 场景录制和重绘](https://github.com/JetBrains/skiko/blob/v0.150.2/skiko/src/awtMain/kotlin/org/jetbrains/skiko/SkiaLayer.awt.kt)
- [ComposeWindow 1.12.2 实际 renderApi](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui/src/desktopMain/kotlin/androidx/compose/ui/awt/ComposeWindow.desktop.kt)
- [Mediamp v0.5.1 原生 MPV 与 Skia 互操作](https://github.com/open-ani/mediamp/blob/v0.5.1/mediamp-mpv/src/desktopMain/kotlin/MPVHandle.desktop.kt)
- [固定 WebP provider 0.3.3](https://github.com/darkxanter/webp-imageio/tree/9c5ae162b31170be18c7a0dc3249331fb34ee3b3)
- [Haze 2.1.0](https://github.com/chrisbanes/haze/tree/2.1.0)
- [PresentMon Console 官方使用与列定义](https://github.com/GameTechDev/PresentMon/blob/main/README-ConsoleApplication.md)
- [Windows Performance Recorder](https://learn.microsoft.com/windows-hardware/test/wpt/windows-performance-recorder)
- [GPUView](https://learn.microsoft.com/windows-hardware/drivers/display/using-gpuview)
- [JDK 21 JFR Recording](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/Recording.html)
