# MangaFlow 全系统 UIUX 扫描 · 2026-11-14

## 任务、证据与范围

用户本轮原话：“这个全部那三个东西选了某一个就得排在最上面”，附图1000121620；随后补充：“然后接着对整个系统的 uiux 做信息层级 格式塔 最小信息原则 渐进式披露全部扫描 review”。保留此前“渐进式披露，最小信息原则”、去掉源管理垃圾桶/重复添加入口/长说明，以及“轮盘转出、不加白晕”等有效约束。

审查基线是 `0739661b0eecf139c0f8c7c2d86406217981b744`，涉及65个共享前端源码文件，按页面和真实入口关系读取UI相关实现。后续 `7d74d2c5dd3f53ce0465d9d14de7939f119e57a6` 修复LiquidGlass圆角类型；`f87d5b8ce9cc912bce63bbc431194ac5893f7ef4` 固定FILL并关闭重复刷新圈。后者删除ScreenFitSettings，因此R13的原适配行已撤销，仅保留其余同级外观项对齐的后续核对；不把已删除行再算成当前缺陷。每个证据链接固定在审查基线，便于以后复核；它不是最新构建的手机截图。

覆盖发现/搜索/热榜、作品详情/章节/评论/来源、阅读器、书架/历史/收藏页、下载/本地导入、账号/云端、设置/备份、壁纸/电脑配对、统计和共享导航/浮层。按照既有云端工作边界，仅使用GitHub源码和当前用户截图；未运行应用、设备、浏览器、测试或本地构建。阅读器图片解码/预取等内部算法不在这次信息架构审查范围。Android/iOS/桌面共享实现的扫描不等于三端实际外观或触摸验收。

## 四个检查轴

| 轴 | 本次具体检查 |
| --- | --- |
| 信息层级 | 当前任务、主对象与下一步优先；章节位置、错误恢复、版本差异先于评分、技术维护和逐项删除。 |
| 格式塔 | 同级控件共用文字列和空间语法；状态靠近所属对象；同一种来源/评分展开具有稳定入口和返回关系。 |
| 最小信息原则 | 同一容量/状态只显示一次；图标和文字按任务表达，不向正常列表重复塞动作词、技术ID或无数据的空行。 |
| 渐进式披露 | 已配置、单源、正常态保持紧凑；技术细节、维护和低频管理按需出现；当前失败的原因及恢复入口立即可得。 |

## 本批实际实现：FILTER-SELECTED-89 及截图修正91–97

`MorphGlassTabs.kt` 的当前选中项移到菜单第一行，其他选项保留原有相对顺序。仍使用原始index作为选择回调和稳定composition key，不改枚举、下载过滤规则、各筛选滚动状态和保存值。共享分类控件的书架调用得到同一行为；收起后继续显示当前图标/名称，玻璃材质、尺寸、开合和减动画规则沿用现有实现。

| 当前选择 | 下载菜单顺序 |
| --- | --- |
| 全部 | 全部、进行中、已下载 |
| 进行中 | 进行中、全部、已下载 |
| 已下载 | 已下载、全部、进行中 |

原审查完成后，用户继续提供1000121623–1000121629截图并明确授权对应修正。当前批在89之外实施统一卷缩略图和来源标记、侧边竖排来源选择、横向列数披露、详情信息主组与按需分组、搜索来源图标状态、浮动阅读坞和发现分区间距。R02、R03与R07的相关实现纳入本批，均待当前正式产物体验；其余R项仍是发现/条件风险，不能将扫描或保存报告当作全系统问题已修复。详见DESIGN.md 91–97。

## 优先处理：恢复、找得到、选得对

### R01 · P1 · 评论失败被当作无评论

- 检查轴：信息层级、渐进披露。
- 证据：[ui/CommunityComponents.kt:160](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/CommunityComponents.kt#L160)、[ui/CommunityComponents.kt:211](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/CommunityComponents.kt#L211)；[ui/DetailCommentFilter.kt:115](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DetailCommentFilter.kt#L115)、[ui/DetailCommentFilter.kt:156](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DetailCommentFilter.kt#L156)。
- 触发与用户代价：全部来源失败时，READY过滤后仍显示“还没有可显示的评论”；恢复动作在来源设置的二次展开里。用户无法区分真正没有评论和请求失败。
- 修正：全部失败时就地显示短失败状态及重试；部分成功时优先显示已有评论，各站完整错误按需披露。
- 状态：源码确认；未在手机复现。

### R02 · P1 · 本批已实作，待实际体验 · 窄屏阅读器连“更多”也会藏起来

- 检查轴：信息层级、格式塔。
- 证据：[reader/ReaderScreen.kt:818](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L818)、[reader/ReaderScreen.kt:833](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L833)、[reader/ReaderScreen.kt:856](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L856)、[reader/ReaderScreen.kt:953](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L953)。
- 触发与用户代价：左右各24dp后，360dp窗口只余312dp；6个48dp动作及间距需要348dp，整排改横向滚动，末尾更多难发现。多源时更多又可只重复已有来源入口。
- 修正：固定章节导航和一个始终完整可见的溢出入口；按实际宽度将次要动作移入，合并重复来源动作。
- 状态：尺寸/条件/操作归属由源码确认；实际裁切与手感待体验。

### R03 · P1 · 本批已实作，待实际体验 · 同站同语言的版本缺可见差异

- 检查轴：最小信息、信息层级。
- 证据：[ui/ChapterSourcePicker.kt:224](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/ChapterSourcePicker.kt#L224)、[ui/ChapterDownloadSourceSheet.kt:90](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/ChapterDownloadSourceSheet.kt#L90)。
- 触发与用户代价：副标题取第一个非空的语言/版本/汉化组；两个同语言、不同汉化组的候选可能只显示相同语言。阅读入口未传检查动作，真实差异仅留在语义描述。
- 修正：先显示候选之间真正不同的字段，再按需披露完整说明；保留真实版本ID、续读和下载状态。
- 状态：源码条件确认；具体作品候选需真实数据核对。


## 其余确定的结构问题

| ID | 位置/检查轴 | 当前问题与用户代价 | 具体修正 | 证据边界 |
| --- | --- | --- | --- | --- |
| R04 · P2 评分扇出还保留另一套大面积底光 | [ui/MetricSourceFan.kt:191](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/MetricSourceFan.kt#L191)、[ui/MetricSourceFan.kt:219](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/MetricSourceFan.kt#L219) / 格式塔、最小信息 | 径向底色中心alpha .93/.86，并有全屏.16遮罩；这条评分入口没有被此前ChapterSourceOrbit修正覆盖，动线仍近似直线加拱形。 | 去掉径向白场和无任务意义的遮罩；保持图标、数值、来源归属与同锚点转出/返回。 | 绘制路径源码确认；前次用户白晕截图是否来自此入口未确认，不能声称手机已复现或已修。 |
| R05 · P2 同一评分条随空间改变点击动作 | [ui/MangaMetricBadges.kt:222](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/MangaMetricBadges.kt#L222)、[ui/DiscoverScreen.kt:1017](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DiscoverScreen.kt#L1017)、[ui/FeaturedCarousel.kt:273](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/FeaturedCarousel.kt#L273) / 格式塔、信息层级 | 只有分数放不下时整条才绑定展开；放得下时点击由外层打开漫画。异步分数或宽度变化可能改变同一位置的动作。 | 固定评分入口语义，或只让实际溢出的堆叠拥有展开动作；继续保留空间够平铺、不够才堆叠。 | 源码确认；手势结果未在设备验证。 |
| R06 · P2 搜索候选前置路径/ID，真正差异被挤到次行 | [ui/FederatedSearchChoices.kt:204](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/FederatedSearchChoices.kt#L204)、[ui/FederatedSearchChoices.kt:220](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/FederatedSearchChoices.kt#L220)、[ui/FederatedSearchChoices.kt:239](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/FederatedSearchChoices.kt#L239) / 信息层级、渐进披露 | 同源多个条目就直接展示技术路径/ID，主标题是来源与序号；作者、章节、状态混在一行截断。与SEARCH-RESULTS-13D的按需暴露标识不符。 | 先展示真实差异标题、作者、章节和版本；仅元数据不足或仍冲突时显示标识，完整路径放更多信息。 | 源码确认；不恢复重复大封面、大标题。 |
| R07 · P2（本批已实作，待实际体验） 搜索来源披露跨查询粘住，状态重复占行 | [ui/FederatedSearchChoices.kt:112](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/FederatedSearchChoices.kt#L112)、[ui/DiscoverScreen.kt:346](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DiscoverScreen.kt#L346) / 渐进披露、最小信息 | 来源状态expanded没有查询key；查询A展开后查询B继续摊开。来源进度与结果数量又各占一行。 | 按查询重置披露；数量、加载/失败摘要合并一条辅助行，异常细节按需打开，结果优先。 | 源码确认；实际占高待当前产物核对。 |
| R08 · P2 下载条目重复容量，长错误撑高却仍看不全 | [ui/DownloadBookRow.kt:112](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadBookRow.kt#L112)、[ui/DownloadBookRow.kt:177](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadBookRow.kt#L177) / 最小信息、渐进披露 | 单话未完成的次行和状态行重复字节数；失败正文再加两行，截断后没有完整展开入口。 | 进度/容量只保留一次；短状态与重试留在行内，完整错误点开查看；保留源刷新、失败原因和重试。 | 源码确认；未进行本轮手机布局验收。 |
| R09 · P2 云端收藏把逐项移除当成常态内容 | [ui/AccountScreen.kt:321](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/AccountScreen.kt#L321) / 信息层级、渐进披露 | 每格封面下常驻“×移除”整行，阅读列表被低频管理操作打断，与本地书架操作语法不一致。现有二次确认仍有效，不能说无误删保护。 | 常态只显示作品与阅读信息；移除收到长按菜单/管理状态，继续保留云端删除确认。 | 源码确认。 |
| R10 · P2 云端续读优先放动作词和评分，章节反而靠后 | [ui/AccountScreen.kt:428](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/AccountScreen.kt#L428) / 信息层级、最小信息 | 每条重复“继续阅读/查看漫画”，标题之后先放评分徽章，章节信息排在其后；续读任务被浏览信息抢占。 | 标题与真实章节组成主要信息组；去掉重复动作词，评分从续读列表收起，保留必要操作箭头。 | 源码确认；字号/实际视觉重量待手机核对。 |
| R11 · P2 添加源流程先要求理解运行时维护 | [ui/SourceImportSheet.kt:204](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/SourceImportSheet.kt#L204)、[ui/SourceImportSheet.kt:363](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/SourceImportSheet.kt#L363)、[ui/SourceImportSheet.kt:441](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/SourceImportSheet.kt#L441) / 渐进披露、信息层级 | 引擎版本、下载/移除管理先于源选择；确认页同时铺网站、版本、语言、来源、指纹。任务选择与底层维护同权。 | 先选择源，缺组件时在导入确认阶段明确处理；已安装引擎维护与指纹进入更多信息。可信来源、兼容性和执行风险提示在恰当时机保留。 | 源码确认；不修改真实导入/验证能力。 |
| R12 · P2 已配置WebDAV重复打开仍先铺配置表单 | [ui/SettingsScreen.kt:637](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/SettingsScreen.kt#L637) / 渐进披露、格式塔 | 四个配置框与清配置/取消/保存/下载/上传同时出现；已有按钮主次样式，但重复备份仍必须穿过首次配置任务。 | 已配置态先给连接摘要及上传/恢复；编辑连接再披露表单，默认远端路径放高级项；操作时保留覆盖范围提示。 | 源码确认。 |
| R13 · P2（并发更新部分消除） 同级外观项的文字列不对齐 | [ui/ScreenFitSettings.kt:48](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/ScreenFitSettings.kt#L48)、[ui/DetailAppearanceSettings.kt:57](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DetailAppearanceSettings.kt#L57)、[ui/SettingsScreen.kt:901](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/SettingsScreen.kt#L901) / 格式塔 | 图标槽与间隔分别约32+12、20+10、20+12dp，同级标题起点约44/30/32dp。 | 统一同级图标槽、文字列和展开指示，不改变必要帮助/披露状态。 | 几何差异由源码确认；视觉影响待手机核对。 |
| R14 · P2 单源/本地阅读仍常驻多源策略设置 | [reader/ReaderSettingsPanel.kt:101](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderSettingsPanel.kt#L101)、[reader/ReaderScreen.kt:1027](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L1027) / 渐进披露、最小信息 | 当前章节无切源能力时仍展示完整切源续读三选项，调用未传实际能力。 | 多源任务才显示该段；通用默认值放次级设置，保留原策略和必要提醒。 | 源码确认；不删除真实多源策略。 |
| R15 · P2 章节分组命名在详情/阅读器不一致 | [ui/DetailCatalog.kt:561](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DetailCatalog.kt#L561)、[reader/ReaderScreen.kt:2078](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/reader/ReaderScreen.kt#L2078) / 格式塔、最小信息 | 详情已将默认组归一为“章节”，阅读器却直接显示groups，仍可能出现被否决的默认/default。 | 复用同一显示规则，保留真实group ID、当前组和切换行为。 | 源码确认。 |
| R16 · P2 书架恢复仍是没有说明的全区裸圈 | [ui/LibraryScreen.kt:159](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/LibraryScreen.kt#L159)、[ui/LibraryScreen.kt:248](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/LibraryScreen.kt#L248)、[ui/LibraryRestoreStatus.kt:29](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/LibraryRestoreStatus.kt#L29) / 信息层级、最小信息 | libraryReady=false时整个内容区显示24dp圆圈，没有恢复文案。与首页QuietLoading属于不同路径。 | 保留真实恢复及错误重试，显示轻量“正在恢复书架…”并避免全区裸圈。 | 实际调用与源码确认；不是本轮手机复现，也不意味着首页STARTUP-82失效。 |
| R17 · P2 下载筛选空态仍引导导入，与当前任务不符 | [ui/DownloadsScreen.kt:332](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/DownloadsScreen.kt#L332) / 信息层级、最小信息 | 进行中筛选为空时，仍显示CBZ/ZIP/图片/文件夹的导入说明；即使已有已下载内容，引导也不变。当前1000121620截图可见该文案。 | 按全部/进行中/已下载的真实状态给简短空态；若已有完成项，优先给查看已下载；首次本地导入说明收到添加流程。 | 本轮截图确认旧文案，源码确认统一分支；截图安装版本未知。 |
| R18 · P2 热榜评分占固定两层且缺少第四站后的溢出表达 | [ui/RankingMetrics.kt:52](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/RankingMetrics.kt#L52) / 信息层级、最小信息 | 固定min44dp两层布局最多取三站；暂无可显示评分时仍显示各站评分与箭头。与网格已有平铺/堆叠语法不一致。 | 采用一致的按宽度评分表达，保留榜单时期热度语义；空评分不保留虚空行。 | 源码确认；密度变化需当前产物复看。 |

## 条件风险，先取得实际内容/视口证据

| ID | 位置 | 风险、下一动作与状态 |
| --- | --- | --- |
| R19 · 壁纸名称可能被四个等权工具挤压 | [ui/WallpaperSettings.kt:188](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/WallpaperSettings.kt#L188) | 名称旁电脑、图片、视频、调节四个44dp入口占176dp；有窄屏和大字风险。 图片/视频合成一个本地添加入口，保留电脑配对和调节披露，先在实际窄屏/大字下确认。 **结构尺寸确认，尚非已确认视觉失败。** |
| R20 · 旧章节历史可能显示未知总页数为0 | [ui/ChapterHistorySheet.kt:127](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/ChapterHistorySheet.kt#L127)、[ui/ChapterReadMark.kt:60](https://github.com/wozhenbang2004/MangaFlow/blob/0739661b0eecf139c0f8c7c2d86406217981b744/shared/src/commonMain/kotlin/dev/mangaflow/ui/ChapterReadMark.kt#L60) | 历史无条件展示当前页/总页数，但其他读取标记允许未知总页数≤0，存在3/0页这类表现条件。 总数未知时只显示第3页，待真实旧记录确认，不制造无意义分母。 **源码条件风险；未证实用户实际遇到。** |

## 建议实施顺序及验收动作

1. R01–R03：先让失败可恢复、阅读工具始终找得到、不同版本选得对。真实用户路径分别是全来源失败的评论、320/360dp阅读控制栏、同站同语言多汉化组。
2. R04–R08、R17–R18：统一评分/来源入口，搜索先给结果和可区分内容，下载只给一次进度和按需错误；在有/无溢出、查询切换、失败与空筛选下确认归属。
3. R09–R16：按实际任务收起云端移除、运行时维护和重复配置；统一设置列、分组名及书架恢复提示。保留真正需要的确认、风险、原始标识和数据。
4. R19–R20：使用真实窄屏/大字壁纸页和未知总页数旧记录核对后再决定改动，不凭假内容判定失败。

这里的动作是后续真实用户体验验收建议，不新增自动测试资产、CI测试或额外review gate。后续截图已经授权91–97的有界实施；其余发现仍作为后续清单，未扩大为全系统重写。

## 已有结构与约束继续保留

- 空查询44dp、标签选后收拢并保留结果空间、固定布局按钮与小选择网格、轮播统一圆角/手动居中及紧凑最近阅读，在当前源码中保留。
- 书架工具按需展开，本地导入格式说明折叠，壁纸调节默认关闭，统计以总时长→热力图→作品排行组织，备份恢复说明覆盖范围；不为凑问题推翻。
- 当前章节来源已经去除玻璃白晕、来源概览恢复44dp、源管理删除按钮/长说明/双入口已修，不重复报旧问题。评分来源扇出属于另一条实际路径，按R04单独核对。
- 评论部分成功时以真实内容为主，章末评论预览沿用READER-END-02/M2-10；不能把已有明确采用设计当作天然缺陷。
- 保持现有封面/章节数据、真实源身份、下载/阅读记录、登录、更新、壁纸配对和平台边界；不靠删除能力、缩字体或增加等权卡片解决信息层级。

## 实际读取覆盖

| 范围 | 文件/读取边界 |
| --- | --- |
| 全局与共享呈现 | MangaFlowApp.kt、SegmentedGlassHeader.kt、GlassControlGroup.kt、MorphGlassTabs.kt、CompactGlassPopover.kt、AdaptiveLayout.kt、LiquidGlass.kt、Theme.kt、ScreenFitPolicy.kt、AppLaunchReveal.kt、LaunchFailureMessage.kt、LibraryRestoreStatus.kt、AppWallpaper.kt、MangaCoverTransition.kt、CoverCardSurface.kt、VolumeBookTile.kt。MangaFlowApp读取路由/抽屉/全局弹层/页面合成/顶栏；其他为相应共享UI实现。 |
| 发现/搜索/热榜 | DiscoverScreen.kt、FeaturedCarousel.kt、ExpandingSearchBar.kt、SearchScopeBar.kt、SearchScopeControl.kt、FederatedSearchChoices.kt、MangaMetricBadges.kt、MetricSourceFan.kt、AuthorWorksSheet.kt、RankingMetrics.kt、RankingMotion.kt、BrowseTabs.kt；CommunityComponents相关共享构造。 |
| 详情/阅读/来源 | DetailCatalog.kt、DetailReadingDock.kt、DetailRatings.kt、DetailCommentFilter.kt、ChapterHistorySheet.kt、ChapterSourceOrbit.kt、ChapterSourcePicker.kt、ChapterDownloadSourceSheet.kt、UnifiedSourceManager.kt、reader/ReaderControls.kt、reader/ReaderSettingsPanel.kt、reader/ChapterEndOverlay.kt、reader/ChapterDiscussion.kt、CommentTranslation.kt、PageCollectionsSheet.kt、SourceResumePrompt.kt、ChapterReadMark.kt、DetailScreen.kt、reader/ReaderScreen.kt、CommunityComponents.kt。DetailScreen为合成/封面/信息/顶栏/导航/弹层段；ReaderScreen为能力、状态、上下工具栏、来源/溢出、页导航、错页校正和目录段；CommunityComponents为评论/长评/剧透/折叠/来源失败/翻译/回复/空状态。 |
| 管理/账号/设置 | LibraryScreen.kt、DownloadsScreen.kt、DownloadBookRow.kt、BookshelfBook.kt、ReadingStatsSheet.kt、SettingsScreen.kt、AccountScreen.kt、BodySourceSettings.kt、SourceImportSheet.kt、WallpaperSettings.kt、SwipeToRemove.kt、ScreenFitSettings.kt、DetailAppearanceSettings.kt、PaperThemePicker.kt、RecordActionsMenu.kt、WebsiteAccountLogin.kt、WallpaperPcSheet.kt、LibraryRestoreStatus.kt。设置包含WebDAV和恢复确认；大文件按UI主干和相关状态逐段读取；小设置/选择器/电脑配对/恢复状态为全文件。 |

没有足够证据的动态卡顿、色彩对比、精确触区舒适度、物理屏幕边缘和实际动画节奏仍待当前正式产物体验，不能宣称全面通过。
