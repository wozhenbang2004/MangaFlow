# Wallpaper Engine 与 MangaFlow 壁纸接入研究

## 当前直接配对实现 · 2026-11-14

本段替代下方2026-11-13的“外部LocalSend首片”作为当前采用路线。用户本轮原话：“我们要的能直接和 wallpaper 那个配对直接拿到壁纸的功能怎么没看到？只要精态和动态的继续交互的没必要”，随后明确“去做啊”。“精态”按此前已确认的静态图片理解；范围是静态图片/动态视频，不实现交互场景播放器。历史LocalSend研究保留，不能当成已满足直接配对。

本轮GitHub code search找到[Mirage固定服务端](https://github.com/laobamac/MirageWallpaper/blob/e66f14b9cffa8129602b3582711f5881df91d159/Mirage/Mirage%20Wallpaper/Services/MobilePairingService.swift)，完整读取握手、分帧、认证和发送实现，改变了原“没有可取得合同”的判断。它是第三方Mac兼容服务端，不是官方Windows协议文档，不证明我方互操作。根LICENSE为GPL3且文件头另有版权声明；我方仅从公开协议事实独立编写接收器，未移植其代码。

事实合同：UDP7884/239.100.0.1与WallpaperEngine/Ping；TCP7889裸LE32长度/无符号大端RSA exponent、modulus；客户端三帧RSA-OAEP-SHA1发送UUID、随机IV16、AESkey32；后续LE32 framed AES256-CBC/PKCS7、按legacy协议逐帧重新使用会话IV；version4/PIN/name/deviceUserName/displayWidth/displayHeight认证；AuthOK后beginUpload/transmissionStart/transmissionContinue与真实数据块。没有已证实的手机端图库查询或完成ACK；UI指导电脑“发送到移动设备”，本地持久化成功才算入库。

[MobilePackageArchive](https://github.com/laobamac/MirageWallpaper/blob/e66f14b9cffa8129602b3582711f5881df91d159/Mirage/Mirage%20Wallpaper/Services/MobilePackageArchive.swift)和[MobileMPKGExporter](https://github.com/laobamac/MirageWallpaper/blob/e66f14b9cffa8129602b3582711f5881df91d159/Mirage/Mirage%20Wallpaper/Services/MobileMPKGExporter.swift)确认PKGM0014未压缩表/视频主媒体；MIT的[XHR666 pkg-extract](https://github.com/XHR666/dsh-mpkg-wallpaper/blob/ab8d50db9b4a29916be7ba8b45aed452ee80d538/lib/pkg-extract.js)进一步给出PKGM/PKGV四位版本族相同目录表。本片只读未压缩表及project.json明确的图片/视频主文件，检查路径/唯一性/offset/size/overlap、魔数并继续走Store实际decoder。未知格式/压缩媒体/Scene/Web/Application明确拒绝，不用preview冒充，不加渲染/LZ4依赖。

正式入口：“设置→外观→壁纸旁电脑图标”。PANEL/MotionDialog接LAN列表、手动IPv4、四位PIN、已配对复连/重新配对/忘记、真实进度/入库计数。Android/Windows共用小型JVM接收器；iOS明确暂未接通。用既有Android Keystore/Windows DPAPI保存PIN、UUID和RSA指纹，复连先验公钥；不记日志，不对公网建立连接。每份限512MiB、分帧有界、串行导入，失败/取消清理本次临时文件，保持既有壁纸。离开面板/后台结束接收，无常驻服务。

按AGENTS仅cloud connector/in-memory authoring、完整main批次及既有CI，无本地操作/测试/构建/设备探测。源码实现不等于互操作通过；官方Windows额外指令、原生包兼容、真实PIN/解码/取消和UI手感仍待当前CI产物与用户反馈。


更新时间：2026-11-13。状态：用户已选择“互动不用我只要图片和动态视频”（root 转达；精确消息 ID 未取得），首片采用外部 LocalSend 传输加 Android 分享导入。分享接收源码已准备，尚未编译或设备验证；应用内 PC 直连、官方 WE PIN、Scene 转换／播放器未实现。本文不把研究候选、源码存在或作者自报结果记为本项目运行通过。

用户原话：“再派一个子代理专项做和电脑wallpaper链接传输壁纸然后弄到我们的壁纸里这个得好好先研究下成熟的方案社区”。本轮只读取公开资料、相关源码、软件安装元信息和本项目壁纸实现；没有读取私人壁纸或账号数据，没有传输用户文件、运行候选程序、测试、构建或网络探测。

## 当前结论

推荐组合 **PC 端选取／转换 + LocalSend 传输 + MangaFlow 原生图片／视频壁纸库**。把“传到手机”和“能正确播放”分开实现和报告。图片与原视频可直接接入；Scene／Web 若转成视频，会失去实时交互、音频响应和动态时钟等能力。若用户明确需要这些能力，另做真实场景播放器切片，不能用静态预览或抽出的单个图层冒充完整场景。

本机只读确认：运行进程为 `wallpaper64.exe`，位于 `E:\SteamLibrary\steamapps\common\wallpaper_engine\wallpaper64.exe`，文件／产品版本均为 **2.8.0.42**。这是本轮实际安装证据，不代表其私人素材已被读取或可任意再分发。

## 官方能力与边界

- 官方 Android Companion 通过局域网发现与四位 PIN 配对，使用 UDP 7884／TCP 7889；Scene 发送前会做移动适配。官方也允许导出 `.mpkg` 后人工传到手机。[配对说明](https://help.wallpaperengine.io/en/mobile/pairing.html)、[端口及手动导出](https://help.wallpaperengine.io/en/mobile/pairing-fixes.html)
- 本轮未取得公开的正式第三方配对协议／SDK，也未定位成熟的兼容配对客户端；不能只按端口与 PIN 界面自造协议并宣称兼容。开发者社区说明桌面端会从各网卡广播，这也解释了 VPN／虚拟网卡／隔离网络下的发现问题。[开发者原帖](https://steamcommunity.com/app/431960/discussions/2/3194737800625636707/)
- 原视频本来就是媒体文件。Scene 则是实时渲染资源，官方没有把它直接导成 MP4 的一般接口；官方推荐打开独立播放窗口后用 OBS 等录制。[格式说明](https://help.wallpaperengine.io/en/functionality/editingwallpapers.html)、[录制说明](https://help.wallpaperengine.io/en/functionality/export.html)
- 官方 CLI 的 `-control getWallpaper` 可取得当前选中路径，`openWallpaper -playInWindow` 可打开指定场景。这可作为 PC 辅助端的选取／录制接入点；本轮没有调用这些命令。[CLI 文档](https://help.wallpaperengine.io/en/functionality/cli.html)
- 官方移动转移还受作者是否接受移动端协议约束。实现应处理自有或获许可素材，不绕过官方拒绝转移的条件。[官方移动转移说明](https://help.wallpaperengine.io/en/mobile/workshop.html)

## 候选与实际阅读

以下 pins 均在本轮通过公开 GitHub 元信息核对。README、源码阅读与实际运行状态分列；均未在本项目执行。

| 对象与固定版本 | 许可／实际阅读 | 用途与限制 |
| --- | --- | --- |
| [LocalSend 1.18.2](https://github.com/localsend/localsend/tree/af0416be50770a97760f7070684bc667b759a15c)；[协议 v2.2](https://github.com/localsend/protocol/blob/62bd3406ec80d62f2ed46269cdc06c4dcc391083/README.md) | 应用 Apache-2.0；读发布说明、协议、平台配置和 iOS entitlement | 首选开放传输基础。发现、接收确认、取消、逐文件授权与校验已有合同；不是 Wallpaper Engine 场景播放器。 |
| [mouse233/localsend-kotlin](https://github.com/mouse233/localsend-kotlin/tree/1c20af5ffc26db740480848c0d6bcff7aced5517)，README 标称 0.8.0-alpha | Apache-2.0；读 README、`server/LocalSendServer.kt`、`security/TlsIdentity.kt`、变更记录 | 可参考拆出 Android 发现／接收／TLS 模块。实际有 NanoHTTPD、证书指纹、PIN 和流式接收代码；它不是 KMP SDK，作者仍标明长期传输、机型及性能待验，不能整包当稳定跨端库。 |
| [unio207/wp2video](https://github.com/unio207/wp2video/tree/b6f3194c96cc1f6e60ee1382367e13c3c86a2ad0) | MIT；读 README 与许可，未读完整录制器实现 | PC 自动录制候选：调用已安装 WE 渲染窗口，由 FFmpeg 捕获；原视频可直接复制。不是 WE 原生视频导出 API，不支持 application 壁纸；作者演示／性能数据未在本机复验。 |
| [XHR666/dsh-mpkg-wallpaper 5.0.0](https://github.com/XHR666/dsh-mpkg-wallpaper/tree/ab8d50db9b4a29916be7ba8b45aed452ee80d538) | `lib/**` MIT，`renderer/**` GPL-3.0-or-later；读 package、README、许可边界、PKG 目录表／LZ4／嵌入视频定向源码及作者验证记录 | 解析层可参考媒体提取；`PKGV`／`PKGM`、边界检查和读取入口真实存在。视频提取、静态图层、独立 WebGL 场景渲染是不同能力；作者的语料与性能数字不是本项目验证。 |
| [notscuffed/RePKG](https://github.com/notscuffed/repkg/tree/8005eb271f4cb5b9e375bafa912029a0d67b5eb8) | MIT；读 README／命令能力，核对版本与许可 | 成熟 PKG／TEX 格式参考及 PC 提取候选。提取图片或资源不等于重现粒子、脚本与互动。 |
| [aqnya/unmpkg](https://github.com/aqnya/unmpkg/tree/0704e3577d03e9a2d52f7bda38478cb2888e9062) | GPL-3.0；读固定 README、模块说明与许可元信息 | 有可复用 C++20 `unmpkg_core`，职责是容器解码，不是完整播放器；采用需明确组件许可与平台绑定。 |
| [Linux Wallpaper Engine](https://github.com/Almamu/linux-wallpaperengine/tree/a0ddce586e9ff12e9d31ca6b103f6036cc1b68fe)；[catsout scene renderer](https://github.com/catsout/wallpaper-scene-renderer/tree/8dde7e7454c1d3906189d7294fd93a3c97bb4ff0) | 前者 GPL-3.0、后者 GPL-2.0 且已归档；读 README／功能清单和仓库元信息 | 仅作渲染／格式参考。Linux 依赖栈不能直接当 Android／iOS SDK；后者明确缺少 SceneScript、时间线、用户属性等能力。 |

社区经验需按版本解释：LocalSend 曾涉及 Android 文件流跨语言传输的内存／性能问题，相关上游问题在 2026-07 已关闭，不能照搬成“当前必然有 bug”。应避免为每个数据块跨 UI 线程，接收时保持流式处理。[维护者原始 issue 与修复链接](https://github.com/flutter-cavalry/saf_stream/issues/4)

## 传输路线选择

1. **成熟工具接入，最短正式路径**：PC 用官方 WE 取出原媒体或按官方方法录制；LocalSend 负责跨端文件传输；MangaFlow 通过系统分享／文件选择导入壁纸库。这条能复用现有工具，但不能宣传成已直接兼容 WE 的配对按钮。
2. **应用内直连，推荐后续正式方向**：PC MangaFlow／轻量辅助端负责选取与必要转换；手机内嵌 LocalSend 兼容接收层，用户确认文件后直接加入现有库。共享协议／传输状态可共用，服务端、证书与平台权限仍要分别落地；不为文件接收引入整套 Flutter 应用。
3. **WE 官方私有配对兼容**：目前仅保留研究入口，不作为已采用实现；缺公开合同及互操作证据。

LocalSend 协议的默认发现为 `224.0.0.167:53317`，上传采用先提供元信息、接收方确认、再按会话及逐文件 token 接收的过程。应独立处理取消与摘要校验。不要把浏览器链接备用下载当成同等安全默认方案：其官方说明使用未加密 HTTP。也不要为局域网接收改掉漫画源／账号客户端的 TLS 策略。

iOS 自动发现有额外边界：官方 LocalSend 配置了 `com.apple.developer.networking.multicast`，本地网络权限及签名能力需落实；不能因 Android 模块可编译就称三端已接通。[固定配置](https://github.com/localsend/localsend/blob/af0416be50770a97760f7070684bc667b759a15c/app/ios/Runner/Runner.entitlements)、[Apple 本地网络说明](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)

## MangaFlow 当前接入点

本节已于 2026-11-13 12:59 UTC 重新读取当前工作区，替代首轮“只有单壁纸”的旧快照。以下是源码事实，不代表新包已发布或真机通过。

- [`WallpaperStore.kt`](../shared/src/commonMain/kotlin/dev/mangaflow/storage/WallpaperStore.kt) 已有 `WallpaperItem`、`WallpaperState.items` 和持久化壁纸库；`importMedia(uri)` 现在追加并选择新项，已有 `select`／`remove`。它仍只接收图片／视频，明确拒绝工程、`.pkg`／`.mpkg`／HTML 等，单文件限 512 MB，并在复制后校验媒体。
- Store 已有自动轮换，间隔 `1/5/10/30/60` 分钟，默认间隔 10 分钟、开关默认关闭。多个保留页面共享一个计时任务；`setActive(owner, active)` 决定是否存在可见拥有者，关闭显示／后台／浓度为零时不继续轮换。
- [`WallpaperSettings.kt`](../shared/src/commonMain/kotlin/dev/mangaflow/ui/WallpaperSettings.kt) 已有多项缩略图、选择／移除、轮换开关与间隔入口。当前文件选择仍逐次添加，不应写成已实现批量 PC 接收。
- [`AppWallpaper.kt`](../shared/src/commonMain/kotlin/dev/mangaflow/ui/AppWallpaper.kt) 把页面 active、生命周期 RESUMED 与背景浓度合成可见状态，交给 Store 管理轮换，并复用原有静音视频与静态预览路径。
- 当前 `importMedia` 仍有递增 intent／取消保护。PC 一次发来多份文件时应串行追加，或由库所有者提供显式批量入口；不能并发调用导致旧导入被新意图取消。接收完成只是前半程，还需通过媒体校验并实际加入库后再显示成功。

## 最小完整切片与待定事项

若用户选择图片／动态视频优先：PC 明确选取一个视频或获许可素材的录制结果 → 显示目标设备 → 手机确认文件 → 流式写临时文件并完成校验 → 串行调用现有壁纸库导入 → 显示缩略图并可加入轮换。取消／失败不改变已存在的壁纸。统一预设可优先采用移动端通用 H.264 MP4；具体画质、截取时长及编码兼容仍待实际产物反馈。

若用户要求 Scene 互动：先界定需要触控、鼠标视差、音频响应、动态时钟中的哪些能力，再比较独立 WebGL 渲染器与原生适配。不要把容器解码器、preview.gif、静态纹理或提取出的局部视频当完整兼容。

尚待取得的证据：选定路线的真实设备互操作、传输中断／恢复行为、导入后平台解码、后台与能耗、Scene 效果对齐。本轮未建立对应通过结论；也未实现私有协议、PC 桥接、接收服务或场景播放器。

## 首片实施：Android 分享导入（源码就绪，设备未验）

用户已明确不需要互动，当前切片不再研究或引入 Scene 运行时。PC 使用已存在的 Wallpaper Engine／资源管理器选择可转移的原图片或视频，由外部 LocalSend 发到手机；收到后用 Android 分享选择 **“添加到漫流壁纸”**。这是一条依赖外部传输应用的实际入口，不是 MangaFlow 内已实现电脑发现或 WE 配对。Scene／Web 录制转换仍留作后续 PC 工具，不能把工程文件改后缀后当视频导入。

源码范围仅为：

- `app/src/main/AndroidManifest.xml`：为专用 `WallpaperImportActivity` 注册 `ACTION_SEND`／`ACTION_SEND_MULTIPLE` 的 `image/*`、`video/*`；不注册通用 `*/*`、网页链接或默认文件查看器入口。
- `app/src/main/java/dev/mangaflow/WallpaperImportActivity.kt`：接收并去重 `EXTRA_STREAM`／`ClipData`，仅接可读取的 `content://` 媒体；最多 20 项、每项最多 512 MB。先显示文件名、数量、已知大小和可用图片缩略图，用户按“添加”后才串行调用现有 `WallpaperStore.importMedia`。库内媒体的最终校验仍由现有 Store 执行。
- `app/src/main/res/values/wallpaper_import.xml`：独立的分享目标名称、确认／进度／逐项错误／结束文案。Root 负责在原壁纸设置中接电脑来源指引，本片没有修改 Store、AppWallpaper、MainActivity 或设置页。

确认前取消不会调用 Store；确认后可以停止，已有成功项保留并显示逐项结果。旋转由当前 Activity 的 ViewModel 保留任务；切后台停止进行中的导入，进程恢复不自动重放上一批。应用不持久化外部 URI 或可持久授权，缩略图禁用磁盘／内存缓存，复制完成后释放临时输入引用；临时权限随 Android 接收 Activity 生命周期处理。没有新增服务、网络权限、网络传输层或依赖。

本次只完成源码 authoring，未运行测试、构建、真实分享／传输或 UI 探测。LocalSend 实际分享 MIME／URI 授权、旋转、取消、平台解码、UI 外观与最终入库结果均待对应 CI 和用户设备反馈。成功添加的媒体可直接使用现有多壁纸选择与定时轮换。
