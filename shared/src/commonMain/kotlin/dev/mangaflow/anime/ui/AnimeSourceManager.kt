package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.BUNDLED_ANIME_SOURCE_IDS
import dev.mangaflow.anime.sources.lanerc.LanercSourceKind
import dev.mangaflow.anime.sources.lanerc.LanercSourceListing
import dev.mangaflow.anime.sources.lanerc.SourcePreparationPhase
import dev.mangaflow.anime.sources.lanerc.SourcePreparationState
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

private val preparingPhases = setOf(
    SourcePreparationPhase.PENDING, SourcePreparationPhase.PREPARING, SourcePreparationPhase.RETRYING,
)

private enum class AnimeSourceManagerTab(val label: String) {
    VIDEO("播放"), BROWSE("番剧资料"), DANMAKU("弹幕")
}

/** Catalogue presence, successful preparation and actual playback are distinct. */
@Composable
internal fun AnimeSourceManager(visible: Boolean, state: AnimeScreenState, controller: AnimeController,
    backdrop: HazeState, onDismiss: () -> Unit,
    onBrowse: (AnimeDiscoveryProvider) -> Unit) {
    var tab by remember { mutableStateOf(AnimeSourceManagerTab.VIDEO) }
    var moreVideoSources by remember { mutableStateOf(false) }
    var referenceExpanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var returnSourceId by remember { mutableStateOf<String?>(null) }
    var pendingBrowse by remember { mutableStateOf<AnimeDiscoveryProvider?>(null) }
    val videoScroll = rememberLazyListState()
    val moreScroll = rememberLazyListState()
    val browseScroll = rememberLazyListState()
    val danmakuScroll = rememberLazyListState()
    val extensions = state.sourceExtensions
    val fixedBuiltIns = controller.builtInVideoSources
    val videoGroups = remember(extensions, fixedBuiltIns) { animeVideoGroups(extensions, fixedBuiltIns) }
    val selected = videoGroups.asSequence().flatMap { it.sources.asSequence() }.firstOrNull { it.id == selectedId }
        ?: extensions.firstOrNull { it.id == selectedId }
    val references = extensions.filter { !it.installable && it.kind in setOf(LanercSourceKind.COMIC, LanercSourceKind.UNKNOWN) }

    fun openSource(source: LanercSourceListing) {
        returnSourceId = null
        selectedId = source.id
    }
    fun closeManager() {
        pendingBrowse = null
        onDismiss()
    }
    fun goUp() {
        when {
            selected != null -> {
                selectedId = returnSourceId
                returnSourceId = null
            }
            moreVideoSources -> moreVideoSources = false
            else -> closeManager()
        }
    }

    MotionDialog(visible, onDismissRequest = ::goUp, maximumWidth = 640.dp,
        onClosed = {
            selectedId = null
            returnSourceId = null
            moreVideoSources = false
            referenceExpanded = false
            val provider = pendingBrowse
            pendingBrowse = null
            if (provider != null) onBrowse(provider)
        }) {
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                val nested = selected != null || moreVideoSources
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (nested) MotionIconButton(onClick = ::goUp, modifier = Modifier.size(44.dp), tooltip = "返回来源目录") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                    Text(selected?.displayName ?: if (moreVideoSources) "更多播放来源" else "管理番剧来源",
                        Modifier.weight(1f).padding(start = if (nested) 0.dp else 8.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = ::closeManager, modifier = Modifier.size(44.dp),
                        tooltip = "关闭来源管理") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (selected != null) {
                    val alternatives = videoGroups.firstOrNull { selected in it.sources }?.sources.orEmpty()
                        .filter { it.id != selected.id && it.installable }
                    key(selected.id) {
                        AnimeSourceManagerDetail(selected, alternatives, selected.id in fixedBuiltIns, state, controller,
                            Modifier.weight(1f, fill = false),
                            onSelectAlternative = { alternative ->
                                returnSourceId = if (alternative.id == returnSourceId) null else selected.id
                                selectedId = alternative.id
                            },
                            onRemoved = { selectedId = returnSourceId; returnSourceId = null })
                    }
                } else if (moreVideoSources) {
                    val inactive = videoGroups.map { it.primary }.filterNot { it.active }
                    val (automatic, manual) = inactive.partition { it.automaticPreparation && !it.installed &&
                        state.sourcePreparation[it.id]?.phase != SourcePreparationPhase.DISABLED }
                    val (paused, available) = manual.partition { it.installed || it.id in BUNDLED_ANIME_SOURCE_IDS ||
                        state.sourcePreparation[it.id]?.phase == SourcePreparationPhase.DISABLED }
                    val failed = automatic.any { state.sourcePreparation[it.id]?.retryableFailure == true }
                    LazyColumn(Modifier.weight(1f, fill = false), state = moreScroll, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (automatic.isNotEmpty()) {
                            item(key = "automatic-header") {
                                AnimeSectionTitle("自动准备")
                            }
                            sourceManagerRows(automatic, state, ::openSource)
                            if (failed) item(key = "automatic-retry") {
                                CompactMenuAction(Icons.Outlined.Refresh, "重试可恢复的来源", enabled = state.sourceOperationIds.isEmpty(),
                                    onClick = controller::retryAutomaticSources)
                            }
                        }
                        if (paused.isNotEmpty()) {
                            item(key = "paused-header") { AnimeSectionTitle("当前不参与找源") }
                            sourceManagerRows(paused, state, ::openSource, fixedBuiltIns.keys)
                        }
                        if (available.isNotEmpty()) {
                            item(key = "available-header") { AnimeSectionTitle("可添加") }
                            sourceManagerRows(available, state, ::openSource)
                        }
                        if (references.isNotEmpty()) {
                            item(key = "reference-toggle") {
                                CompactMenuAction(if (referenceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    "待接入 · ${references.size} 个", onClick = { referenceExpanded = !referenceExpanded })
                            }
                            if (referenceExpanded) sourceManagerRows(references, state, ::openSource)
                        }
                        if (inactive.isEmpty() && references.isEmpty()) item {
                            AnimeMessage("暂无需要管理的其他来源")
                        }
                    }
                } else {
                    MotionTabs(tab.ordinal, AnimeSourceManagerTab.entries.map { it.label },
                        onSelect = { tab = AnimeSourceManagerTab.entries[it] },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), controlHeight = 44.dp,
                        treatment = MotionTabTreatment.Soft)
                    when (tab) {
                        AnimeSourceManagerTab.VIDEO -> {
                            val active = videoGroups.map { it.primary }.filter { it.active }
                            val remaining = videoGroups.size - active.size
                            LazyColumn(Modifier.weight(1f, fill = false), state = videoScroll, contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                item(key = "active-video-header") {
                                    AnimeSectionTitle("参与找源 · ${active.size}", subtitle = "启用不代表线路可播放") {
                                        if (remaining > 0 || references.isNotEmpty()) {
                                            MotionTextButton(onClick = { moreVideoSources = true }) { Text("管理其他来源") }
                                        }
                                    }
                                }
                                sourceManagerRows(active, state, ::openSource, fixedBuiltIns.keys)
                                if (active.isEmpty()) item { AnimeMessage("暂无已启用的视频来源") }
                            }
                        }
                        AnimeSourceManagerTab.BROWSE -> {
                            val browse = extensions.filter { it.kind == LanercSourceKind.BROWSE }
                            LazyColumn(Modifier.weight(1f, fill = false), state = browseScroll, contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                item(key = "builtin-browse-heading") {
                                    AnimeSectionTitle("作品与播出资料", subtitle = "只提供资料，不提供播放线路")
                                }
                                items(AnimeDiscoveryProvider.entries, key = { "builtin-browse:" + it.name }) { provider ->
                                    AnimeSelection(animeDiscoveryProviderLabel(provider),
                                        when (provider) {
                                            AnimeDiscoveryProvider.DOUBAN -> "作品主目录、评分、分类"
                                            AnimeDiscoveryProvider.BANGUMI -> "评分、新番、番表"
                                            AnimeDiscoveryProvider.ANILIST -> "评分、人气、趋势"
                                        }, selected = false,
                                        onClick = { pendingBrowse = provider; onDismiss() })
                                }
                                if (browse.isNotEmpty()) {
                                    item(key = "extension-browse-heading") { AnimeSectionTitle("扩展目录") }
                                    sourceManagerRows(browse, state, ::openSource)
                                }
                            }
                        }
                        AnimeSourceManagerTab.DANMAKU -> {
                            val danmaku = extensions.filter { it.kind == LanercSourceKind.DANMAKU }
                            LazyColumn(Modifier.weight(1f, fill = false), state = danmakuScroll, contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                item(key = "builtin-danmaku") {
                                    AnimeSectionTitle("弹弹play 官方服务", subtitle = "在播放时打开弹幕面板，配置应用凭据后搜索剧集")
                                }
                                if (danmaku.isNotEmpty()) {
                                    item(key = "extension-danmaku-heading") { AnimeSectionTitle("脚本扩展") }
                                    sourceManagerRows(danmaku, state, ::openSource)
                                }
                            }
                        }
                    }
                }
                if (state.notice != null && state.notice != selected?.error) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(state.notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionIconButton(onClick = controller::dismissNotice, modifier = Modifier.size(44.dp),
                            tooltip = "关闭提示") { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

private data class AnimeVideoSourceGroup(val primary: LanercSourceListing, val sources: List<LanercSourceListing>)

private fun animeVideoGroups(extensions: List<LanercSourceListing>, builtIns: Map<String, String>): List<AnimeVideoSourceGroup> {
    val directory = builtIns.map { (id, name) ->
        LanercSourceListing(id, name, LanercSourceKind.VIDEO, scriptUrl = "", installed = true, enabled = true)
    } + extensions.filter { it.kind == LanercSourceKind.VIDEO }
    val ids = directory.mapTo(hashSetOf()) { it.id }
    return directory.groupBy { it.bundledAlternativeId?.takeIf(ids::contains) ?: it.id }.map { (id, variants) ->
        val native = variants.firstOrNull { it.id == id }
        val primary = native?.takeIf { it.active } ?: variants.firstOrNull { it.active } ?: native ?: variants.first()
        AnimeVideoSourceGroup(primary, variants)
    }
}

private val LanercSourceListing.active: Boolean get() = installed && enabled
private val LanercSourceListing.displayName: String get() = name.removeSuffix(" · 内建")
private val SourcePreparationState.retryableFailure: Boolean get() = phase == SourcePreparationPhase.FAILED && canRetry

private fun LazyListScope.sourceManagerRows(sources: List<LanercSourceListing>, state: AnimeScreenState,
    onSelect: (LanercSourceListing) -> Unit, fixedBuiltInIds: Set<String> = emptySet(),
    rowTitle: (LanercSourceListing) -> String = { it.displayName }) {
    items(sources, key = { it.id }) { source ->
        val native = source.id in BUNDLED_ANIME_SOURCE_IDS || source.id in fixedBuiltInIds
        val title = rowTitle(source)
        val colors = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = "管理 $title", onClick = { onSelect(source) })
                .motionPress(interaction, pressedScale = .99f)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(34.dp).background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                AnimeSourceMark(source.id, source.displayName, size = 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                AnimeQuietLabel(animeSourceManagerLabel(source, state, native))
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeSourceManagerDetail(
    source: LanercSourceListing,
    alternatives: List<LanercSourceListing>,
    fixedBuiltIn: Boolean,
    state: AnimeScreenState,
    controller: AnimeController,
    modifier: Modifier = Modifier,
    onSelectAlternative: (LanercSourceListing) -> Unit,
    onRemoved: () -> Unit,
) {
    var technicalExpanded by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }
    val native = fixedBuiltIn || source.id in BUNDLED_ANIME_SOURCE_IDS
    val preparation = state.sourcePreparation[source.id]
    val preparationNote = preparation?.message
    val busy = source.id in state.sourceOperationIds || "all-video" in state.sourceOperationIds ||
        preparation?.phase in preparingPhases
    val homeFeeds by controller.sourceBrowse.homeFeeds.collectAsState()
    val home = homeFeeds[source.id]
    val search = state.sourceResults.firstOrNull { it.providerId == source.id }
    val catalogueError = state.sourceCatalogErrors[source.id]
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnimeSectionTitle(animeExtensionKind(source.kind), subtitle = animeSourceManagerLabel(source, state, native))
        }
        if (source.preparationUnavailableReason != null) item {
            Text(source.preparationUnavailableReason, style = MaterialTheme.typography.bodySmall)
        }
        if (source.installed && !source.enabled && source.id in setOf("anime-lanerc-23", "anime-lanerc-ain")) item {
            AnimeQuietLabel("旧版曾因接口兼容性默认停用。此处保留本机已保存的选择，可通过下方开关启用。")
        }
        preparationNote?.takeIf { it.isNotBlank() }?.let { note ->
            item { Text(note, style = MaterialTheme.typography.bodySmall) }
        }
        if (source.error != null && preparationNote?.startsWith(source.error) != true) item {
            Text(source.error, style = MaterialTheme.typography.bodySmall,
                color = if (source.installable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source.kind == LanercSourceKind.DANMAKU) item {
            AnimeQuietLabel("观看时可使用官方弹幕服务，或读取已接入的站内弹幕。")
        }
        state.sourceRoutes[source.id]?.takeIf { source.installable }?.let { route ->
            item { AnimeQuietLabel("网络设置 · " + downloadRouteLabel(route)) }
        }
        if (home?.error != null) item { AnimeQuietLabel("本次浏览 · " + home.error) }
        if (catalogueError != null) item { AnimeQuietLabel("本次剧集读取 · " + catalogueError) }
        if (search != null) item {
            AnimeQuietLabel(when {
                search.loading -> "正在搜索关联作品"
                search.error != null -> "本次关联搜索 · " + search.error
                else -> "本次关联搜索 · ${search.items.size} 个结果"
            })
        }
        if (removeConfirm) item {
            Text("移除已安装脚本。作品关联与观看记录保留，可重新准备后继续。", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotionTextButton(onClick = { removeConfirm = false }) { Text("取消") }
                MotionButton(onClick = { controller.removeSource(source.id); onRemoved() }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("确认移除") }
            }
        } else when {
            fixedBuiltIn -> item { AnimeQuietLabel("内置视频来源随应用版本更新。") }
            native -> item {
                Box(Modifier.motionInputGate(!busy)) {
                    SettingsToggle("启用这个来源", source.active, Icons.Outlined.PowerSettingsNew,
                        onCheckedChange = { if (!busy) controller.enableSource(source.id, it) })
                }
            }
            source.installed -> {
                item {
                    Box(Modifier.motionInputGate(!busy)) {
                        SettingsToggle(if (source.kind == LanercSourceKind.DANMAKU) "启用脚本扩展" else "启用这个来源",
                            source.enabled, Icons.Outlined.PowerSettingsNew,
                            onCheckedChange = { if (!busy) controller.enableSource(source.id, it) })
                    }
                }
                item { CompactMenuAction(Icons.Outlined.Refresh, "检查并更新脚本", enabled = !busy,
                    onClick = { controller.updateSource(source.id) }) }
                if (source.previousVersion != null) item {
                    CompactMenuAction(Icons.Outlined.Undo, "恢复上一个脚本版本", enabled = !busy,
                        onClick = { controller.rollbackSource(source.id) })
                }
                item { CompactMenuAction(Icons.Outlined.DeleteOutline, "移除脚本", enabled = !busy, destructive = true,
                    onClick = { removeConfirm = true }) }
            }
            source.automaticPreparation -> when {
                preparation?.retryableFailure == true -> item {
                    CompactMenuAction(Icons.Outlined.Refresh, "重试准备此来源", enabled = !busy,
                        onClick = { controller.retryAutomaticSource(source.id) })
                }
                preparation?.phase == SourcePreparationPhase.DISABLED -> item {
                    CompactMenuAction(Icons.Outlined.Refresh, "重新准备此来源", enabled = !busy,
                        onClick = { controller.restoreAutomaticSource(source.id) })
                }
                else -> Unit
            }
            source.installable -> item {
                MotionButton(onClick = { controller.installSource(source.id) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (busy) "正在添加" else if (source.bundledAlternativeId != null) "添加脚本版" else "添加来源")
                }
            }
            else -> Unit
        }
        if (alternatives.isNotEmpty()) {
            item(key = "alternatives-heading") {
                AnimeSectionTitle("同一来源的其他实现", subtitle = "打开后可分别管理")
            }
            val builtInIds = controller.builtInVideoSources.keys
            sourceManagerRows(alternatives, state, onSelectAlternative, builtInIds) { alternative ->
                when {
                    alternative.id in BUNDLED_ANIME_SOURCE_IDS || alternative.id in builtInIds -> "内置实现"
                    "homeSections" in alternative.capabilities && "searchFiltered" in alternative.capabilities -> "脚本版 · 推荐与分类"
                    else -> "脚本版"
                }
            }
        }
        val declared = source.capabilities.mapNotNull { animeExtensionCapability(it, source.kind) }.distinct()
        val hasTechnical = native || source.aliases.isNotEmpty() || declared.isNotEmpty() || source.scriptUrl.isNotBlank()
        if (hasTechnical) item {
            CompactMenuAction(if (technicalExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (technicalExpanded) "收起技术信息" else "技术信息", onClick = { technicalExpanded = !technicalExpanded })
        }
        if (technicalExpanded) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimeQuietLabel("来源标识 · " + source.id)
                if (source.aliases.isNotEmpty()) AnimeQuietLabel(source.aliases.joinToString(" · "))
                if (source.installed && source.version.isNotBlank()) AnimeQuietLabel("版本 · " + source.version)
                if (source.installed && source.digest.isNotBlank()) AnimeQuietLabel("摘要 · " + source.digest.take(12))
                if (declared.isNotEmpty()) AnimeQuietLabel((if (native) "已接入 · " else "脚本声明 · ") + declared.joinToString(" · "))
                val sourceUrl = if (native) controller.sourceHomes[source.id] ?: source.scriptUrl else source.scriptUrl
                if (sourceUrl.isNotBlank()) AnimeQuietLabel(sourceUrl)
            }
        }
    }
}

private fun animeExtensionKind(kind: LanercSourceKind): String = when (kind) {
    LanercSourceKind.VIDEO -> "视频来源"
    LanercSourceKind.BROWSE -> "浏览资料"
    LanercSourceKind.COMIC -> "漫画来源"
    LanercSourceKind.DANMAKU -> "弹幕脚本"
    LanercSourceKind.UNKNOWN -> "类型尚未确认"
}

private fun animeExtensionCapability(function: String, kind: LanercSourceKind): String? = when (function) {
    "search" -> "关键词搜索"
    "searchFiltered", "categories" -> "分类筛选"
    "homeSections", "latest" -> "发现内容"
    "detail" -> if (kind == LanercSourceKind.COMIC) "作品和章节" else "作品资料"
    "related" -> "相关推荐"
    "play" -> if (kind == LanercSourceKind.COMIC) "章节取图" else "播放解析"
    "danmu" -> "弹幕"
    "login" -> "来源登录"
    else -> null
}

private fun animeSourceManagerLabel(source: LanercSourceListing, state: AnimeScreenState, native: Boolean): String {
    val preparation = state.sourcePreparation[source.id]
    return when {
        source.id in state.sourceOperationIds -> "正在处理"
        source.kind == LanercSourceKind.COMIC -> "漫画 · 待接入"
        source.kind == LanercSourceKind.UNKNOWN -> "ENC1 · 待兼容"
        !source.installable -> "暂不兼容 · 查看原因"
        native && source.error != null -> "内置 · 读取状态失败"
        native -> when {
            source.active -> "内置 · 已启用"
            source.installed -> "内置 · 已停用"
            else -> "内置 · 已移除"
        }
        source.kind == LanercSourceKind.DANMAKU && source.active -> "脚本已启用 · 播放器尚未接入"
        source.active && source.preparationUnavailableReason != null -> "扩展 · 部分线路待适配"
        source.active -> if (source.kind == LanercSourceKind.BROWSE) "已启用 · 只提供资料" else "扩展 · 已启用"
        source.installed -> "扩展 · 已停用"
        preparation?.phase == SourcePreparationPhase.PENDING -> "等待自动准备"
        preparation?.phase == SourcePreparationPhase.PREPARING -> "正在自动准备"
        preparation?.phase == SourcePreparationPhase.RETRYING -> "等待自动重试"
        preparation?.phase == SourcePreparationPhase.DISABLED -> "已移除 · 可重新准备"
        preparation?.retryableFailure == true -> "准备失败 · 可重试"
        preparation?.phase == SourcePreparationPhase.FAILED -> "准备失败 · 查看原因"
        source.automaticPreparation -> "等待准备状态"
        source.error != null -> "添加失败 · 查看原因"
        else -> "可添加"
    }
}
