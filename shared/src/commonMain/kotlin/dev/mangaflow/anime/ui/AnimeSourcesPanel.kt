package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

private data class EpisodeMappingDraft(val sourceId: String, val episode: AnimeSourceEpisode)

@Composable
internal fun AnimeSourcesPanel(
    visible: Boolean,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    focusEpisode: AnimeEpisode?,
    onDismiss: () -> Unit,
    onPlay: (String, AnimeEpisodeOption) -> Unit,
) {
    val subject = state.detail?.subject
    val uriHandler = LocalUriHandler.current
    var websiteError by remember { mutableStateOf<String?>(null) }
    val openWebsite: (String) -> Unit = { sourceId ->
        val url = controller.sourceHomes[sourceId]
        if (url != null) runCatching { uriHandler.openUri(url) }.onFailure {
            websiteError = "无法打开来源网站，请在浏览器访问 " + url
        }
    }
    var query by remember(subject?.id) { mutableStateOf(subject?.title.orEmpty()) }
    var groups by remember(subject?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mapping by remember(subject?.id, focusEpisode?.id) { mutableStateOf<EpisodeMappingDraft?>(null) }
    var expandedSource by remember(subject?.id, focusEpisode?.id) { mutableStateOf<String?>(null) }
    var manageExpanded by remember(subject?.id, focusEpisode?.id) {
        mutableStateOf(focusEpisode == null || state.episodeOptions[focusEpisode.id].orEmpty().none { it.confirmed })
    }
    var episodeQuery by remember(focusEpisode?.id) { mutableStateOf("") }
    var mappingLimit by remember(expandedSource, episodeQuery) { mutableIntStateOf(40) }
    var allPreviewEpisodes by remember(state.previewCatalog?.title?.id) { mutableStateOf(false) }
    val sourceScroll = rememberLazyListState()
    val previewScroll = key(state.previewCatalog?.title?.id) { rememberLazyListState() }
    val preview = state.previewCatalog
    val previousBinding = preview?.let { candidate -> state.sourceBindings.firstOrNull {
        it.catalog.sourceId == candidate.title.sourceId && it.catalog.id != candidate.title.id
    } }
    val previewing = preview != null || state.previewLoading
    val subjectId = subject?.id
    LaunchedEffect(visible, subjectId) {
        if (visible && subjectId != null && state.sourceResults.isEmpty()) controller.searchSources()
    }
    LaunchedEffect(state.sourceGroups) {
        groups = groups.filter { (id, group) -> state.sourceGroups[id] != group }
    }
    LaunchedEffect(state.episodeOptions, focusEpisode?.id) {
        val draft = mapping ?: return@LaunchedEffect
        if (focusEpisode?.let { state.episodeOptions[it.id] }.orEmpty().any {
                it.sourceId == draft.sourceId && it.episode.id == draft.episode.id && it.confirmed
            }) mapping = null
    }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 720.dp,
        onClosed = {
            groups = emptyMap(); mapping = null; expandedSource = null
            controller.dismissSourcePreview()
        }) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (previewing) MotionIconButton(onClick = controller::dismissSourcePreview, modifier = Modifier.size(44.dp),
                        tooltip = "返回来源") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = if (previewing) 2.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (previewing) "确认作品关联" else "播放来源", style = MaterialTheme.typography.titleMedium)
                        AnimeQuietLabel(focusEpisode?.let(::animeEpisodeLabel) ?: subject?.title.orEmpty())
                    }
                    if (state.canUndo) MotionIconButton(onClick = controller::undo, modifier = Modifier.size(44.dp),
                        tooltip = "撤销上次改动") { Icon(Icons.Outlined.Undo, "撤销", Modifier.size(19.dp)) }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭来源") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (previewing) {
                    LazyColumn(Modifier.weight(1f, fill = false), state = previewScroll, contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.previewLoading && preview == null) item {
                            AnimeMessage("正在读取来源目录", "取得真实集数后再确认关联。")
                        }
                        if (preview != null) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        AnimeQuietLabel("作品资料 · Bangumi")
                                        Text(subject?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                                        AnimeQuietLabel(subject?.let(::animeSubjectSummary).orEmpty())
                                    }
                                    Icon(Icons.Outlined.Link, null, Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        AnimeQuietLabel(state.sourceResults.firstOrNull {
                                            it.providerId == preview.title.sourceId }?.name ?: animeSourceName(preview.title.sourceId, state))
                                        Text(preview.title.title, style = MaterialTheme.typography.titleSmall)
                                        AnimeQuietLabel(listOfNotNull(preview.title.year?.toString(),
                                            preview.title.episodeCount?.let { it.toString() + " 集" }).joinToString(" · "))
                                    }
                                }
                            }
                            if (!preview.notice.isNullOrBlank() && state.sourceError == null) item(key = "preview-partial") {
                                AnimeCatalogNotice(preview, retryEnabled = !state.previewLoading,
                                    onRetry = { controller.previewSource(preview.title) })
                            }
                            if (previousBinding != null) item {
                                AnimeSectionTitle("这将替换原来的来源关联", previousBinding.catalog.title)
                                Text("替换后重新核对线路与集数映射；观看记录会保留，关联改动可以撤销。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item {
                                Text("请核对季度、正片/特别篇与版本。关联作品后还需选择线路组；同名或集数相同不代表同一版本。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item { AnimeSectionTitle("来源目录", preview.episodes.size.toString() + " 条原始记录") }
                            items(preview.episodes.take(if (allPreviewEpisodes) Int.MAX_VALUE else 10),
                                key = { it.group + "|" + it.id }) { episode ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(episode.title, style = MaterialTheme.typography.bodyMedium)
                                    AnimeQuietLabel(animeSourceEpisodeSummary(episode))
                                }
                            }
                            if (preview.episodes.size > 10) item {
                                MotionTextButton(onClick = { allPreviewEpisodes = !allPreviewEpisodes }) {
                                    Text(if (allPreviewEpisodes) "收起目录" else "查看全部 " + preview.episodes.size + " 条")
                                }
                            }
                        }
                        if (state.sourceError != null) item {
                            if (preview == null) AnimeMessage("来源目录未取得", state.sourceError, icon = Icons.Outlined.ErrorOutline)
                            else AnimeDiscoveryInlineStatus("目录更新未完成，仍可选择已加载集数",
                                retry = { controller.previewSource(preview.title) })
                        }
                    }
                    if (preview != null) MotionButton(onClick = controller::bindPreviewSource,
                        enabled = !state.previewLoading, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                        Text(if (previousBinding != null) "确认替换来源关联" else "确认关联此作品")
                    }
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false), state = sourceScroll, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (focusEpisode != null) {
                            val choices = state.episodeOptions[focusEpisode.id].orEmpty().filter { it.confirmed }
                            item(key = "episode-sources") { AnimeSectionTitle("本集来源", focusEpisode.title) }
                            items(choices, key = { "choice:" + it.sourceId + ":" + it.episode.id }) { option ->
                                AnimeSelection(option.sourceName,
                                    listOf(option.episode.group, option.episode.edition).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                    selected = option.sourceId == state.preferredSourceId,
                                    onClick = { onPlay(focusEpisode.id, option) })
                            }
                            if (choices.isEmpty()) item { AnimeQuietLabel("本集还没有确认的片源，请在下方选择作品与线路。") }
                            item {
                                MotionTextButton(onClick = { manageExpanded = !manageExpanded },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                                    Icon(if (manageExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.Tune,
                                        null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (manageExpanded) "收起关联与搜索" else "关联其他来源 / 调整本集映射")
                                }
                            }
                        }
                        if (manageExpanded || focusEpisode == null) {
                        if (state.sourceBindings.isNotEmpty()) item { AnimeSectionTitle("已关联") }
                        val waitingBindings = state.sourceBindings.filter { binding -> state.sourceCatalogs.none {
                            it.title.sourceId == binding.catalog.sourceId && it.title.id == binding.catalog.id
                        } }
                        items(waitingBindings, key = { it.catalog.sourceId + "|" + it.catalog.id }) { binding ->
                            val sourceId = binding.catalog.sourceId
                            val error = state.sourceCatalogErrors[sourceId]
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(animeSourceName(sourceId, state), style = MaterialTheme.typography.titleSmall)
                                        Text(binding.catalog.title, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    AnimeSourceMenu(sourceId, state, controller, backdrop, onWebsite = { openWebsite(sourceId) })
                                }
                                Text(error ?: "正在读取已关联目录",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AnimeQuietLabel("作品关联与已保存的观看记录仍在；目录未取得时不猜配集数。")
                                MotionTextButton(onClick = { controller.refreshSource(sourceId) },
                                    modifier = Modifier.heightIn(min = 44.dp)) { Text("重试这个来源的目录") }
                            }
                        }
                        items(state.sourceCatalogs, key = { it.title.sourceId + "|" + it.title.id }) { catalog ->
                            val sourceId = catalog.title.sourceId
                            val name = state.sourceResults.firstOrNull { it.providerId == sourceId }?.name ?: animeSourceName(sourceId, state)
                            val sourceGroups = catalog.episodes.map { it.group }.distinct()
                            val selectedGroup = state.sourceGroups[sourceId]
                            val stagedGroup = groups[sourceId] ?: selectedGroup
                            val matched = focusEpisode?.let { state.episodeOptions[it.id].orEmpty()
                                .firstOrNull { option -> option.sourceId == sourceId && option.confirmed } }
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(name, style = MaterialTheme.typography.titleSmall)
                                        Text(catalog.title.title, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (state.preferredSourceId == sourceId) AnimeQuietLabel("优先")
                                    AnimeSourceMenu(sourceId, state, controller, backdrop, onWebsite = { openWebsite(sourceId) })
                                }
                                val catalogError = state.sourceCatalogErrors[sourceId]
                                if (catalogError != null) {
                                    Text("目录更新未取得，仍显示上次目录。", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AnimeQuietLabel(catalogError)
                                    MotionTextButton(onClick = { controller.refreshSource(sourceId) },
                                        modifier = Modifier.heightIn(min = 44.dp)) { Text("重试这个来源的目录") }
                                }
                                if (catalogError == null && !catalog.notice.isNullOrBlank()) {
                                    AnimeCatalogNotice(catalog, onRetry = { controller.refreshSource(sourceId) })
                                }
                                sourceGroups.forEach { group ->
                                    val count = catalog.episodes.count { it.group == group }
                                    AnimeSelection(group.ifBlank { "默认线路" },
                                        count.toString() + " 条记录" + if (selectedGroup == group) " · 当前线路" else "",
                                        selected = stagedGroup == group,
                                        onClick = { groups = groups + (sourceId to group); mapping = null })
                                }
                                if (stagedGroup != null && stagedGroup != selectedGroup) {
                                    MotionButton(onClick = { controller.selectSourceGroup(sourceId, stagedGroup) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("应用线路") }
                                }
                                if (sourceGroups.size > 1 && selectedGroup == null)
                                    AnimeQuietLabel("请选择一条线路；不同线路可能包含不同季度或版本。")
                                if (focusEpisode != null && selectedGroup != null) {
                                    if (matched != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            AnimeQuietLabel("本集已确认")
                                            Text(matched.episode.title, style = MaterialTheme.typography.bodyMedium)
                                            AnimeQuietLabel(animeSourceEpisodeSummary(matched.episode))
                                        }
                                        MotionTextButton(onClick = { onPlay(focusEpisode.id, matched) },
                                            modifier = Modifier.heightIn(min = 44.dp)) {
                                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                                            Text("播放")
                                        }
                                    }
                                    MotionTextButton(onClick = {
                                        expandedSource = if (expandedSource == sourceId) null else sourceId
                                        mapping = null; episodeQuery = ""
                                    }, modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text(if (expandedSource == sourceId) "收起集数映射" else
                                            if (matched == null) "为这一集选择资源" else "调整本集映射")
                                    }
                                    if (expandedSource == sourceId) {
                                        AnimeTextField(episodeQuery, { episodeQuery = it }, "筛选来源集名或编号")
                                        val candidates = catalog.episodes.filter { episode ->
                                            episode.group == selectedGroup && (episodeQuery.isBlank() ||
                                                episode.title.contains(episodeQuery, ignoreCase = true) ||
                                                episode.number?.let(::animeEpisodeNumber)?.contains(episodeQuery) == true)
                                        }
                                        candidates.take(mappingLimit).forEach { episode ->
                                            AnimeSelection(episode.title, animeSourceEpisodeSummary(episode),
                                                selected = mapping?.sourceId == sourceId && mapping?.episode?.id == episode.id,
                                                onClick = { mapping = EpisodeMappingDraft(sourceId, episode) })
                                        }
                                        if (candidates.size > mappingLimit) MotionTextButton(onClick = { mappingLimit += 40 }) {
                                            Text("再显示 40 集 · 共 " + candidates.size + " 集")
                                        }
                                        if (candidates.isEmpty()) AnimeQuietLabel("这一线路没有匹配筛选条件的集数。")
                                    }
                                }
                            }
                        }
                        mapping?.let { draft -> if (focusEpisode != null) item(key = "mapping-review") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AnimeSectionTitle("确认这条映射")
                                Text(animeEpisodeLabel(focusEpisode) + " · " + focusEpisode.title,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("对应来源：" + draft.episode.title, style = MaterialTheme.typography.bodyMedium)
                                AnimeQuietLabel(animeSourceEpisodeSummary(draft.episode))
                                MotionButton(onClick = { controller.mapEpisode(focusEpisode.id, draft.sourceId, draft.episode.id) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用本集映射") }
                            }
                        } }
                        if (state.sourceError != null) item {
                            AnimeMessage("来源操作未完成", state.sourceError, icon = Icons.Outlined.ErrorOutline)
                        }
                        item {
                            AnimeSectionTitle("查找其他来源")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AnimeTextField(query, { query = it }, "番剧原名、别名或关键词", Modifier.weight(1f),
                                    onSubmit = { controller.searchSources(query.trim()) })
                                MotionIconButton(onClick = { controller.searchSources(query.trim()) },
                                    modifier = Modifier.size(48.dp), tooltip = "搜索播放来源") {
                                    Icon(Icons.Outlined.Search, "搜索来源", Modifier.size(21.dp))
                                }
                            }
                        }
                        items(state.sourceResults, key = { it.providerId }) { result ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AnimeSectionTitle(result.name,
                                    when { result.loading -> "正在搜索"; result.error != null -> "暂未取得"
                                        else -> result.items.size.toString() + " 个候选" })
                                if (result.error != null) {
                                    Text(result.error, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    MotionTextButton(onClick = { controller.retrySourceSearch(result.providerId) },
                                        modifier = Modifier.heightIn(min = 44.dp)) {
                                        Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp)); Text("重试这个来源")
                                    }
                                    if (controller.sourceHomes[result.providerId] != null)
                                        MotionTextButton(onClick = { openWebsite(result.providerId) },
                                            modifier = Modifier.heightIn(min = 44.dp)) { Text("打开来源网站") }
                                } else if (!result.loading && result.items.isEmpty()) AnimeQuietLabel("没有找到候选，试试别名。")
                                result.items.forEach { title ->
                                    val linked = state.sourceBindings.any {
                                        it.catalog.sourceId == title.sourceId && it.catalog.id == title.id
                                    }
                                    AnimeSelection(title.title,
                                        listOfNotNull(title.year?.toString(), title.episodeCount?.let { it.toString() + " 集" },
                                            if (linked) "已关联" else "查看目录后确认").joinToString(" · "),
                                        selected = linked, onClick = { controller.previewSource(title) })
                                }
                                if (result.hasMore) MotionTextButton(onClick = { controller.loadMoreSourceSearch(result.providerId) },
                                    enabled = !result.loading && !result.loadingMore,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                                    Text(if (result.loadingMore) "正在加载" else "更多候选")
                                }
                            }
                        }
                        }
                    }
                }
                if (websiteError != null) Text(websiteError.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.notice != null) Text(state.notice,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun animeSourceEpisodeSummary(episode: AnimeSourceEpisode): String =
    listOfNotNull(episode.group.takeIf { it.isNotBlank() }, episode.edition.takeIf { it.isNotBlank() },
        episode.number?.let { "编号 " + animeEpisodeNumber(it) },
        when (episode.kind) {
            AnimeEpisodeKind.MAIN -> "正片"
            AnimeEpisodeKind.SPECIAL -> "特别篇"
            AnimeEpisodeKind.OPENING -> "片头"
            AnimeEpisodeKind.ENDING -> "片尾"
            AnimeEpisodeKind.TRAILER -> "预告"
            AnimeEpisodeKind.OTHER -> "其他"
        }).joinToString(" · ")

@Composable
private fun AnimeSourceMenu(sourceId: String, state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    onWebsite: () -> Unit) {
    var expanded by remember(sourceId) { mutableStateOf(false) }
    val route = when (state.sourceRoutes[sourceId]) {
        DownloadRouteMode.DIRECT -> "直连"
        DownloadRouteMode.PROXY -> "代理"
        else -> "跟随全局"
    }
    Box {
        MotionIconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp), tooltip = "来源设置") {
            Icon(Icons.Outlined.MoreHoriz, "来源设置", Modifier.size(19.dp))
        }
        CompactGlassPopover(expanded, backdrop, onDismiss = { expanded = false }) {
            AnimeQuietLabel("后续请求 · " + route, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            CompactMenuAction(Icons.Outlined.Check, "设为优先来源", onClick = {
                expanded = false; controller.setPreferredSource(sourceId)
            })
            CompactMenuAction(Icons.Outlined.Language, "使用直连", onClick = {
                expanded = false; controller.setSourceRoute(sourceId, DownloadRouteMode.DIRECT)
            })
            CompactMenuAction(Icons.Outlined.Public, "使用代理", onClick = {
                expanded = false; controller.setSourceRoute(sourceId, DownloadRouteMode.PROXY)
            })
            CompactMenuAction(Icons.Outlined.OpenInNew, "打开来源网站", onClick = {
                expanded = false; onWebsite()
            })
            CompactMenuAction(Icons.Outlined.LinkOff, "解除作品关联", destructive = true, onClick = {
                expanded = false; controller.unlinkSource(sourceId)
            })
        }
    }
}

/** A partial catalogue is usable content with a local retry, never a failed whole-screen state. */
@Composable
internal fun AnimeCatalogNotice(catalog: AnimeSourceCatalog, retryEnabled: Boolean = true, onRetry: () -> Unit) {
    val notice = catalog.notice?.takeIf { it.isNotBlank() } ?: return
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (catalog.episodes.isNotEmpty()) AnimeQuietLabel("已加载的集数仍可选择")
        }
        MotionIconButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.size(44.dp),
            tooltip = "重新读取此来源目录") {
            Icon(Icons.Outlined.Refresh, "重试未完整加载的目录", Modifier.size(18.dp))
        }
    }
}
