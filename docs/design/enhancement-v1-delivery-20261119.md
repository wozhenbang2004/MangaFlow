# 画质增强与智能预处理 V1.0 · 实施与未闭合验收

记录时间：2026-11-19 16:08（Asia/Shanghai）。本批基于 `07bf1b85f96b9de9276fe04278e9499d20603e86` 开发，集成时保留 `6e25fab90d3cba96c81967d189b8144017ed907c` 的阅读、成人、番剧和榜单界面改动。提交身份以包含本文件的 main 提交为准。

**状态：统一中心、漫画预处理和 Windows 离线引擎已接入源码；整份 V1.0 尚未完成。** 下文把已实现、未实现与设备未验证分别列出。不能将不可用入口、接口或 CI 编译替代真实引擎与设备验收。

## 本次用户目标

本轮完整规范标题为「MangaFlow 画质增强与智能预处理系统 V1.0」，最终指令为「一次性完全落地」。精确消息 ID 未取得；下列原话均来自当前请求，原文中的 Android、Windows、普通漫画、成人漫画和番剧均保留在范围内：

- 「全档位开放、自动推荐、手动锁定、预处理优先、原画即时可用、数据真实可验证。」
- 「五个模块共用现有 ExperiencePolicyStore 的设备资源与任务准入数据，不再各自建立互不协调的资源控制器。」
- 「质量档位决定使用哪种恢复算法；放大倍率决定输出像素数量，不能混成一个选项。」
- 「极致档必须存在」「不能把 Anime4K M 改个名字假装极致。」
- 「补帧和画质增强完全独立，支持单独开启及自由组合。」
- 「当前页原图必须能够先显示」「不改变缩放比例、滚动锚点和翻页位置」。
- 「普通漫画和成人漫画继续保持书架、历史、缓存身份及数据隔离。」
- 「增强失败不会损坏或覆盖原视频」「正在被阅读或播放的文件受到租约保护」。
- 「系统不能提供的硬件数据显示‘不可用’，不得用猜测值填充。」
- 「Android 与 Windows 分别进行真实设备性能和视觉验收；CI 编译成功不代表模型已正确运行。」
- 「每批改动按功能域报告修改文件、增加/删除代码行数、运行证据及尚未通过的验收项目。」
- 「即时能看，提前能处理，最高档能选择，设备负载能看懂，失败能回退。」

当前仓库 AGENTS.md 的交付规则仍要求：不写或运行测试、不本地编译/构建、不进行设备/API 探测或 UI 自动化；直接集成最新 main，只确认正常 CI 启动。用户要求的真实设备验收因此保持开放，不能降低成源码检查。

## 需求覆盖

| ID | 要求 | 本批源码结果 | 未闭合项 |
| --- | --- | --- | --- |
| ENH-01 | 五模块共享状态、资源与任务准入 | EnhancementCenter 统一策略、引擎目录、scheduler、telemetry；ExperiencePolicyStore 提供唯一增强许可；普通/成人/番剧默认值及会话覆盖分开 | 原生高质量实时后端尚未全部接入 |
| ENH-02 | 画质、倍率独立，最高档真实 | 原画/自动/轻量/均衡/精细/极致与 1×/2×/4× 独立；已有 Anime4K S/S、M/M 支持 restore-only 1× 与 restore+upscale 2×；高级模型缺失明确说明 | Android 高质量 Vulkan 视频模型、Windows 专用实时模型未实现；不能视为最高档实时可用 |
| ENH-03 | 独立补帧、不同质量与切镜保护 | Windows 离线可只做 RIFE 或与超分组合；2×、60/120 FPS、自定义目标；精确 RIFE 文件与实际摘要，切镜保护固定启用 | 当前显示器实际刷新率未接入，自动补帧明确拒绝执行，不能偷换为 2×；实时 RIFE 未实现；重复帧识别及字幕变形专项效果未完成验收 |
| ENH-04 | 黑白、彩色、条漫采用相应模型 | 真实尺寸及颜色抽样分类，也可手动选内容类型；显式模型类型/倍率校验；Android CPU ONNX 重叠分块/行式输出，Windows MangaJaNai/IllustrationJaNai CLI | Android Vulkan/ncnn GPU 路径未实现；文字、排线、网点、接缝与锐化效果未验证；导入 ONNX 不等于兼容资格通过 |
| ENH-05 | 原图立即显示、临近漫画预处理 | 原请求始终保留；已完成增强绘制在同一原始几何和相机上；阅读方向/速度/资源/实际耗时影响窗口；跳页/换源/换策略失效旧任务 | 真机缩放、滚动锚点、双页和条漫视觉待验收；当前增强窗口还未使用长期缓存命中率来学习调整 |
| ENH-06 | 在线番剧前方 5～15 秒安全预处理 | 策略与不可用原因接入，当前继续原画或已有实时 Anime4K | 片段获取/合法缓存准入、关键帧与场景边界、准备窗口控制和音画安全衔接后端均未实现；不显示虚构准备秒数 |
| ENH-07 | 已下载视频完整增强、多版本、恢复 | 已校验原下载租约 → 原生超分/RIFE → 编码 → 音轨/字幕/章节/附件重封装及时间轴检查 → 完整视频解码检查 → 新资产发布；分段摘要、暂停/继续/取消，原始版/指定增强版播放 | 仅 Windows 适配；非零起点的安全分段、复杂轨道、HDR/Dolby/封面视频等明确拒绝；可变帧率及实际 A/V 同步资格未验证 |
| ENH-08 | P0～P3 统一仲裁、前台优先 | 当前页 P0，临近页 P1，手动整章/整集 P2，下载后自动 P3；CPU/可用内存/存储/热压力检查；当前播放让后台任务让位；Windows 原生进程未确认退出不释放许可 | 未接入可依赖的 Windows VRAM/GPU/温度采样；缺测保持不可用；实际抢占延迟和桌面流畅性未验证 |
| ENH-09 | Android 后台限制与最高档保护 | Android 离开前台暂停可选增强；内存/严重热压力保留用户目标并回退；任务和页/片段边界持久化 | 不承诺进程被系统结束后继续；Android 视频离线链路未实现；长时间真机热行为未验证 |
| ENH-10 | 衍生缓存、摘要、原子发布与租约 | 独立漫画/成人/番剧目录；原字节、来源版本、作品/集/页、模型权重、引擎版本、规范化参数和格式绑定；临时结果校验后发布；原生解码/播放租约保护，清理不删除原下载 | 跨设备增强资产传输与复用未实现；不同文件系统上的实际恢复、断电和租约行为待设备验收 |
| ENH-11 | 下载与增强双进度、整章/集任务 | 漫画下载行/详情显示独立增强页数；支持单章、选中/全部完成章；番剧行显示多个衍生版本及独立进度；可在下载完成后自动增强 | 原始下载失败仍由原下载所有者处理；增强不能修复未完成原文件；设备流程待验收 |
| ENH-12 | 实际测量，提交/GPU/呈现分开 | 保留 Anime4K 提交与纹理估算；Android 异步 disjoint timer 查询真实 GPU 耗时；原始 FPS/解码输出/丢帧各自命名；P50/P95、队列、资源、缓存及降级原因；seek 与模型/尺寸变更隔离观测 | 最终呈现 FPS、重复帧、完整 GPU 完成帧计数、通用 GPU/显存负载等没有可靠接口的项目保持不可用；Windows/iOS 不冒充 Android GPU 查询 |
| ENH-13 | 真实内容性能试用、同页/同帧比较 | 15 秒真实会话记录，本地按内容域保存；无样本/部分中断/本次内容短时结果分开；漫画真实同页分界线比较 | 没有长期稳定或通用推荐资格；番剧同帧捕获/比较未实现，入口不可用；跨设备评估/推荐尚未实现 |
| ENH-14 | Ovio/Motion/玻璃与渐进披露 | 设置全局入口、阅读/播放上下文、直接分段档位和局部详情；Android 底部安全区，Windows 侧面；当前实际状态与目标分组；保留并发 main 的作品分组及弹层交接 | 15 系统真实设备视觉、手势、键盘、大字号、窗口尺寸均未验证；不能宣称已获审美认可 |

## 阶段状态

| 阶段 | 本批达到的边界 | 状态 |
| --- | --- | --- |
| M0 | 统一状态、已有 Anime4K 1×/2×、真实计时接口、面板与试用记录 | 源码接入；部分呈现/硬件指标仍无可靠数据；设备未验证 |
| M1 | 漫画即时/临近/整章处理、缓存身份/命中、原图与相机保留 | 源码接入；设备与视觉未验证 |
| M2 | Windows 漫画 CLI 2×/4× 与视频超分/RIFE 离线版本 | 可执行调用链已接入；未安装或运行引擎，GPU/模型/媒体验收未通过 |
| M3 | 专用高质量与 RIFE 实时播放器 | 未实现，仅明确能力原因与离线替代 |
| M4 | Android GPU 原生模型/RIFE、自适应视频推理 | 未实现；本批只有 CPU ONNX 图像路径和原有 Anime4K 的共享资源接入 |
| M5 | 在线番剧安全片段预处理和切换 | 未实现 |
| M6 | 本地真实短时测量/档位记录、当前 Anime4K 帧预算降档 | 部分实现；基于历史的通用设备推荐、实际显示器自动补帧、跨设备资产复用未实现 |

## 已完成的静态交叉审查与修正

1. 高级档缺模型时不使用 Anime4K M 或非神经滤镜冒充。Android ONNX、Windows 专用模型显示真实内容/倍率/许可和设备未验证状态。
2. 普通、成人衍生缓存的 LRU 只处理自己的 namespace。Coil 与每次原生 tile 解码各自保留文件租约，不能依赖先退出的 UI 租约。
3. COMPLETE-only 查询已有增强缓存；缓存未命中不能偷偷开始实时推理。整章与阅读缓存共用相同规范化 tile/overlap、引擎版本和源摘要。
4. 关闭会话、恢复默认后，已经排队的设置写入不能使旧会话覆盖复活；试用保存串行化，失败回执可见。
5. Windows 原生取消保留已观察子进程句柄，确认退出后才让调度器释放资源；用户取消与重试不覆盖原文件。
6. RIFE 切镜检测阈值零不代表关闭。本批始终使用 0.15，精确绑定当前质量所需的真实权重文件。
7. 离线增强版标记为已处理文件，避免再次叠加 Anime4K；其 FPS 不冒称原始片源 FPS。
8. seek/暂停使旧 GL 观测 epoch 失效，GPU 查询在 GL 线程重置，Main 消费回执再次守卫；GPU 查询检查有效 counter bits 并拒绝可能溢出的长窗口。
9. 实际模型或输出尺寸改变后重新统计视频耗时；漫画前方任务只更新处理统计，不把下一页的模型冒称当前页模型。
10. 下载中各版本的播放入口共用关闭面板后的导航路径；全局番剧缓存入口初始化同一实际缓存所有者，不以空引用返回“清理成功”。
11. 合并 main 的两处冲突保留新主线的 AnimePanelSurface、按作品分组、真实下载进度、删除菜单 presence、AnimeChoiceStrip 和原有设置分组。

本批只做源文件、接口、调用点、官方固定源码/协议与差异审阅。`git diff --check` 无输出不代表编译、运行、视觉或性能通过。没有编写/执行测试，没有本地构建、模型安装、媒体转换、基准、API/设备探测或 UI 自动化。正常 main CI 触发及其真实链接单独在交付消息中报告，不推断构建成功。

## 设备验收仍需覆盖

Android 与 Windows 应分别使用同一真实页面/片段验证选档、原图先显示、增强替换、放大与滚动锚点、不同漫画内容、条漫接缝、翻页/seek/切源失效、前台争用、缓存清理与正在使用文件保护。Windows 还需验证 RTX 5060 实际模型、编码、音轨/字幕/章节保留、离线版本切换、暂停恢复和长任务。120 Hz 手机及 60/120 FPS 输出要分别记录实际呈现、丢帧、温度趋势与短时样本时长，不能用配置目标或输出文件头替代显示测量。

验收底线 1～5 已有对应源代码守卫及真实数据/缺测表达，但运行行为尚未被设备证据证实；第 6 项真实设备验收明确未通过；第 7 项按下面的文件和行数清单交付。所有尚未实现项仍属于原请求的开放范围，不视为用户撤回。

## 参考与实现入口

- [15 系统设计与具体源码采用](enhancement-center-v1.md)
- [固定模型引擎、CLI 参数、许可与研究证据](../research/enhancement-engines-20261119.md)
- [第三方声明](../../THIRD_PARTY_NOTICES.md)
- 统一业务与界面：`shared/src/commonMain/kotlin/dev/mangaflow/enhancement/`
- 平台实现：`shared/src/androidMain/kotlin/dev/mangaflow/enhancement/engine/`、`shared/src/desktopMain/kotlin/dev/mangaflow/enhancement/engine/`

## 文件与行数

统计相对于集成父提交 `6e25fab90d3cba96c81967d189b8144017ed907c`，使用 Git 的实际逐文件增加/删除行数。以下由最终差异生成，文档与源码分开汇总。

<!-- enhancement-diff-statistics -->

| 功能域 | 文件数 | 增加 | 删除 |
| --- | ---: | ---: | ---: |
| 统一策略、调度与真实指标 | 5 | +841 | −0 |
| 漫画预增强、缓存与下载接入 | 11 | +1,673 | −12 |
| 番剧播放、GPU 计时与离线版本 | 13 | +751 | −32 |
| 平台模型与原生处理引擎 | 7 | +1,706 | −0 |
| 全局与上下文面板 | 10 | +1,067 | −153 |
| 设计、状态、交接与许可 | 8 | +499 | −0 |
| **合计** | **54** | **+6,537** | **−197** |

### 逐文件清单

| 功能域 | 文件 | 增加 | 删除 |
| --- | --- | ---: | ---: |
| 统一策略、调度与真实指标 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/EnhancementCenter.kt` | +122 | −0 |
| 统一策略、调度与真实指标 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/EnhancementPolicy.kt` | +159 | −0 |
| 统一策略、调度与真实指标 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/EnhancementScheduler.kt` | +222 | −0 |
| 统一策略、调度与真实指标 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/EnhancementTelemetry.kt` | +290 | −0 |
| 统一策略、调度与真实指标 | `shared/src/commonMain/kotlin/dev/mangaflow/experience/ExperiencePolicy.kt` | +48 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/MangaApplicationServices.kt` | +9 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/EnhancedAssetCache.kt` | +374 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ReaderChapterEnhancementStore.kt` | +346 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ReaderEnhancementController.kt` | +434 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ReaderEnhancementPresentation.kt` | +213 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt` | +81 | −4 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/storage/StorageCacheStore.kt` | +3 | −2 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadBookRow.kt` | +57 | −0 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadTaskDetailsDialog.kt` | +71 | −3 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadsScreen.kt` | +83 | −3 |
| 漫画预增强、缓存与下载接入 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/MangaFlowApp.kt` | +2 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/androidMain/kotlin/dev/mangaflow/anime/player/AndroidAnime4KEffect.kt` | +32 | −12 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/androidMain/kotlin/dev/mangaflow/anime/player/AndroidAnimeGpuTimer.kt` | +112 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/androidMain/kotlin/dev/mangaflow/anime/player/AnimePlayer.android.kt` | +17 | −6 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/AnimeController.kt` | +98 | −5 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/player/Anime4KShaderLibrary.kt` | +2 | −2 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/player/AnimeLocalMedia.kt` | +1 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/player/AnimePlayer.kt` | +22 | −2 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/player/AnimeVideoEnhancement.kt` | +7 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/AnimeEnhancementSession.kt` | +41 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/VideoPreparationStore.kt` | +405 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/desktopMain/kotlin/dev/mangaflow/anime/player/AnimePlayer.desktop.kt` | +7 | −0 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/desktopMain/kotlin/dev/mangaflow/anime/player/DesktopAnime4KShaderStore.kt` | +2 | −2 |
| 番剧播放、GPU 计时与离线版本 | `shared/src/desktopMain/kotlin/dev/mangaflow/anime/player/DesktopAnimeVideoEnhancement.kt` | +5 | −3 |
| 平台模型与原生处理引擎 | `shared/src/androidMain/kotlin/dev/mangaflow/enhancement/engine/EnhancementEngine.android.kt` | +359 | −0 |
| 平台模型与原生处理引擎 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/engine/EnhancementEngine.kt` | +149 | −0 |
| 平台模型与原生处理引擎 | `shared/src/desktopMain/kotlin/dev/mangaflow/enhancement/engine/DesktopEngineRuntime.kt` | +252 | −0 |
| 平台模型与原生处理引擎 | `shared/src/desktopMain/kotlin/dev/mangaflow/enhancement/engine/DesktopVideoPreparation.kt` | +318 | −0 |
| 平台模型与原生处理引擎 | `shared/src/desktopMain/kotlin/dev/mangaflow/enhancement/engine/EnhancementEngine.desktop.kt` | +298 | −0 |
| 平台模型与原生处理引擎 | `shared/src/iosMain/kotlin/dev/mangaflow/enhancement/engine/EnhancementEngine.ios.kt` | +21 | −0 |
| 平台模型与原生处理引擎 | `shared/src/jvmMain/kotlin/dev/mangaflow/enhancement/engine/EngineFiles.kt` | +309 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/ui/AnimeDownloadsPanel.kt` | +100 | −9 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/ui/AnimePlayerPane.kt` | +1 | −1 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/ui/AnimePlayerSettings.kt` | +20 | −140 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/anime/ui/AnimeWorkspace.kt` | +7 | −3 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ui/EnhancementCenterPanel.kt` | +434 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ui/EnhancementControls.kt` | +237 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ui/EnhancementLiveStatus.kt` | +138 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/enhancement/ui/EnhancementPanelHost.kt` | +123 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/SettingsScreen.kt` | +5 | −0 |
| 全局与上下文面板 | `shared/src/commonMain/kotlin/dev/mangaflow/ui/StorageManagementDialog.kt` | +2 | −0 |
| 设计、状态、交接与许可 | `AGENTS.md` | +2 | −0 |
| 设计、状态、交接与许可 | `DESIGN.md` | +6 | −0 |
| 设计、状态、交接与许可 | `README.md` | +6 | −0 |
| 设计、状态、交接与许可 | `THIRD_PARTY_NOTICES.md` | +49 | −0 |
| 设计、状态、交接与许可 | `docs/ai-dev/PROJECT-STATE.md` | +8 | −0 |
| 设计、状态、交接与许可 | `docs/design/enhancement-center-v1.md` | +53 | −0 |
| 设计、状态、交接与许可 | `docs/design/enhancement-v1-delivery-20261119.md` | +160 | −0 |
| 设计、状态、交接与许可 | `docs/research/enhancement-engines-20261119.md` | +215 | −0 |
