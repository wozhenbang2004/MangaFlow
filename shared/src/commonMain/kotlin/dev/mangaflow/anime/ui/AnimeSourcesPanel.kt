package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

private data class EpisodeMappingDraft(val sourceId: String, val catalogKey: String, val episode: AnimeSourceEpisode)

private fun AnimeSourceTitle.referenceKey(): String = "$sourceId:${id.length}:$id:$url"

@Composable
internal fun AnimeSourcesPanel(
    visible: Boolean,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    focusEpisode: AnimeEpisode?,
    recovering: Boolean,
    onDismiss: () -> Unit,
    onPlay: (String, AnimeEpisodeOption) -> Unit,
    onAutomaticPlay: (AnimeEpisode) -> Unit,
    onOpenSourceWork: ((AnimeSourceTitle) -> Unit)? = null,
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
    var mapping by remember(subject?.id, focusEpisode?.id) { mutableStateOf<EpisodeMappingDraft?>(null) }
    var expandedSource by remember(subject?.id, focusEpisode?.id) { mutableStateOf<String?>(null) }
    var sourceDetails by remember(subject?.id, focusEpisode?.id) { mutableStateOf<String?>(null) }
    var manageExpanded by remember(subject?.id, focusEpisode?.id) {
        mutableStateOf(false)
    }
    var aggregationExpanded by remember(subject?.id) { mutableStateOf(false) }
    var searchDiagnosticsExpanded by remember(subject?.id) { mutableStateOf(false) }
    var episodeQuery by remember(focusEpisode?.id) { mutableStateOf("") }
    var mappingLimit by remember(expandedSource, episodeQuery) { mutableIntStateOf(40) }
    val preview = state.previewCatalog
    val subjectId = subject?.id
    var requestedPreview by remember(subjectId) { mutableStateOf<AnimeSourceTitle?>(null) }
    var pendingSourceWork by remember(subjectId) { mutableStateOf<AnimeSourceTitle?>(null) }
    var presentedOwner by remember { mutableStateOf<String?>(null) }
    SideEffect { if (visible) presentedOwner = subjectId }
    fun openPreview(title: AnimeSourceTitle) {
        requestedPreview = title
        controller.previewSource(title)
    }
    fun closePreview() {
        requestedPreview = null
        controller.dismissSourcePreview()
    }
    val previewKey = preview?.title?.referenceKey()
    var allPreviewEpisodes by remember(previewKey) { mutableStateOf(false) }
    val sourceScroll = key(subjectId, focusEpisode?.id) { rememberLazyListState() }
    val previewScroll = key(previewKey) { rememberLazyListState() }
    val bindings = remember(state.sourceBindings, subjectId) {
        state.sourceBindings.filter { it.subjectId == subjectId }.distinctBy { it.catalog.sourceId }
    }
    val linkedKeys = remember(bindings) { bindings.map { it.catalog.referenceKey() }.toSet() }
    val boundIds = remember(bindings) { bindings.map { it.catalog.sourceId }.toSet() }
    val catalogs = remember(state.sourceCatalogs) { state.sourceCatalogs.associateBy { it.title.referenceKey() } }
    val excludedSourceIds = state.library.firstOrNull { it.subject.id == subjectId }?.excludedSourceIds.orEmpty()
    val pending = remember(state.groupSourceCandidates, state.associationChoices, boundIds, excludedSourceIds) {
        (state.groupSourceCandidates + state.associationChoices.values.flatten()).distinctBy { it.referenceKey() }.filterNot {
            it.sourceId in boundIds || it.sourceId in excludedSourceIds
        }
    }
    val candidateKeys = remember(pending) { pending.map { it.referenceKey() }.toSet() }
    val candidateSourceIds = remember(pending) { pending.map { it.sourceId }.toSet() }
    val previewLinked = previewKey != null && previewKey in linkedKeys
    val previousBinding = preview?.let { candidate -> bindings.firstOrNull {
        it.catalog.sourceId == candidate.title.sourceId && it.catalog.referenceKey() != candidate.title.referenceKey()
    } }
    val nativeSourceId = subject?.externalIds?.get("native-source")
    val openAsNative = preview?.title?.let { title ->
        nativeSourceId != null && when (title.sourceId) {
            nativeSourceId -> controller.sourceSubject(title).id != subjectId
            else -> nativeSourceId !in boundIds
        }
    } == true
    val failedPreview = requestedPreview?.takeIf { preview == null && !state.previewLoading && state.sourceError != null }
    val previewing = preview != null || state.previewLoading || failedPreview != null
    val episodeLines = focusEpisode?.let { controller.confirmedPlaybackLines(it.id, state) }.orEmpty()
        .filter { it.catalog.referenceKey() in linkedKeys }
    val activeLine = state.selectedPlaybackOption.takeIf { state.selectedEpisodeId == focusEpisode?.id }?.let { current ->
        episodeLines.singleOrNull { controller.samePlaybackLine(it, current) }
    }
    val manualTarget = state.playbackTarget?.takeIf { it.episode.id == focusEpisode?.id }
    val manualLine = manualTarget?.manualOption?.let { requested ->
        episodeLines.singleOrNull { controller.samePlaybackLine(it, requested) }
    }
    val orderedBindings = if (focusEpisode == null) bindings.sortedBy { it.catalog.sourceId != state.preferredSourceId }
        else bindings.sortedBy { binding ->
            val sourceId = binding.catalog.sourceId
            val lines = episodeLines.filter { it.sourceId == sourceId }
            when {
                !recovering && manualTarget == null && activeLine?.sourceId == sourceId -> 0
                lines.any { controller.recentPlaybackLineFailure(focusEpisode.id, it, state) == null } -> 1
                lines.isNotEmpty() -> 2
                else -> 3
            }
        }
    val matchesBySource = remember(state.episodeOptions, linkedKeys) {
        state.episodeOptions.values.flatMap { lines ->
            lines.filter { it.confirmed && it.catalog.referenceKey() in linkedKeys }.map { it.sourceId }.distinct()
        }.groupingBy { it }.eachCount()
    }
    val associationIssues = state.sourceAggregation.filter { (id, progress) ->
        id !in boundIds && id !in candidateSourceIds && progress.phase != AnimeAssociationPhase.LINKED
    }
    LaunchedEffect(visible, subjectId, manageExpanded) {
        if (visible && manageExpanded && subjectId != null && state.sourceResults.isEmpty()) controller.searchSources()
    }
    LaunchedEffect(previewKey, requestedPreview?.referenceKey()) {
        if (previewKey != null && previewKey == requestedPreview?.referenceKey()) requestedPreview = null
    }
    LaunchedEffect(state.episodeOptions, focusEpisode?.id) {
        val draft = mapping ?: return@LaunchedEffect
        if (focusEpisode?.let { state.episodeOptions[it.id] }.orEmpty().any {
                it.catalog.referenceKey() == draft.catalogKey && it.episode == draft.episode && it.confirmed
            }) mapping = null
    }
    MotionDialog(visible, onDismissRequest = { if (previewing) closePreview() else onDismiss() }, maximumWidth = 720.dp,
        onClosed = {
            val requested = pendingSourceWork
            val owner = presentedOwner
            pendingSourceWork = null
            presentedOwner = null
            mapping = null
            expandedSource = null
            sourceDetails = null
            websiteError = null
            if (owner != null && owner == controller.state.value.detail?.subject?.id) {
                closePreview()
                if (requested != null && controller.currentVideoCandidates(listOf(requested)).isNotEmpty()) {
                    onOpenSourceWork?.invoke(requested)
                }
            }
        }) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (previewing) MotionIconButton(onClick = ::closePreview, modifier = Modifier.size(44.dp),
                        tooltip = "返回来源") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = if (previewing) 2.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(when {
                            previewLinked || openAsNative || state.previewLoading || failedPreview != null -> "来源目录"
                            previewing -> "确认作品关联"
                            else -> "播放来源"
                        }, style = MaterialTheme.typography.titleMedium)
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
                            AnimeMessage("正在读取来源目录")
                        }
                        if (preview != null) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        val nativeSource = subject?.externalIds?.get("native-source")
                                        AnimeQuietLabel(when {
                                            nativeSource != null -> "当前作品 · ${animeSourceName(nativeSource, state)}"
                                            subject?.id?.startsWith("bangumi:") == true -> "作品资料 · Bangumi"
                                            else -> "当前作品"
                                        })
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
                            if (previousBinding != null && !openAsNative) item {
                                AnimeSectionTitle("这将替换原来的来源关联", previousBinding.catalog.title)
                                Text("替换后重新核对线路与集数映射；观看记录会保留，关联改动可以撤销。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!previewLinked && !openAsNative) item {
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
                            if (preview == null) {
                                AnimeMessage("来源目录未取得", state.sourceError, icon = Icons.Outlined.ErrorOutline)
                                if (failedPreview != null) MotionTextButton(onClick = { openPreview(failedPreview) }) {
                                    Text("重试此来源目录")
                                }
                            } else AnimeDiscoveryInlineStatus("目录更新未完成，仍显示上次目录",
                                retry = { controller.previewSource(preview.title) })
                        }
                    }
                    if (preview != null && openAsNative && onOpenSourceWork != null) {
                        MotionButton(onClick = {
                            pendingSourceWork = preview.title
                            onDismiss()
                        }, enabled = visible && !state.previewLoading,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text("打开此来源的目录") }
                    } else if (preview != null && !openAsNative && !previewLinked) {
                        MotionButton(onClick = controller::bindPreviewSource, enabled = !state.previewLoading,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                            Text(if (previousBinding != null) "确认替换来源关联" else "确认关联此作品")
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false), state = sourceScroll, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bindings.isEmpty()) item(key = "no-bindings") {
                            AnimeQuietLabel(if (state.sourceAggregationRunning) "正在核对片源" else "暂无已关联的片源")
                        } else item(key = "bound-heading") {
                            AnimeSectionTitle("已关联的片源")
                        }
                        items(orderedBindings, key = { "bound:" + it.catalog.referenceKey() }) { binding ->
                            val title = binding.catalog
                            val sourceId = title.sourceId
                            val name = animeSourceName(sourceId, state)
                            val catalog = catalogs[title.referenceKey()]
                            val sourceGroups = catalog?.episodes.orEmpty().groupBy { it.group }
                            val selectedGroup = binding.selectedGroup
                            val lineFailures = focusEpisode?.let { episode ->
                                episodeLines.filter { it.sourceId == sourceId }.associateWith {
                                    controller.recentPlaybackLineFailure(episode.id, it, state)
                                }
                            }.orEmpty()
                            val lines = lineFailures.keys.sortedBy { lineFailures[it] != null }
                            val attemptable = focusEpisode?.let { episode ->
                                lines.filter { controller.canAttemptPlaybackLine(episode.id, it, state) }.toSet()
                            }.orEmpty()
                            val available = lines.filter { it in attemptable && lineFailures[it] == null }
                            val current = activeLine?.takeIf { it in lines }
                            val manual = manualLine?.takeIf { it in lines }
                            val expanded = sourceDetails == sourceId
                            val error = state.sourceCatalogErrors[sourceId]
                            val association = state.sourceAggregation[sourceId]
                            val extension = state.sourceExtensions.firstOrNull { it.id == sourceId }
                            val sourceDisabled = extension != null && (!extension.installed || !extension.enabled)
                            val showManual = manual != null && manual != current
                            val showCurrent = current != null && !showManual && !recovering && !sourceDisabled &&
                                current in available
                            val stateLabel = when {
                                extension != null && !extension.installed -> "来源已移除"
                                sourceDisabled -> "来源已停用"
                                showManual && (recovering || manualTarget?.error != null) -> "上次手动指定"
                                showManual -> if (state.resolving) "正在准备手动选择的线路" else "手动指定"
                                showCurrent -> "当前线路"
                                current != null && recovering -> "上次使用"
                                focusEpisode != null && lines.isNotEmpty() && attemptable.isEmpty() -> "本集线路暂不可用"
                                focusEpisode != null && lines.isNotEmpty() && available.isEmpty() -> "上次播放失败"
                                focusEpisode != null && available.isNotEmpty() -> "本集 ${available.size} 条已对应线路"
                                error != null -> if (catalog == null) "目录读取失败" else "目录更新失败"
                                association?.phase == AnimeAssociationPhase.SEARCHING -> "正在读取目录"
                                association?.phase == AnimeAssociationPhase.QUEUED -> "等待读取目录"
                                catalog == null -> "目录尚未读取"
                                focusEpisode != null -> "本集尚未对应"
                                else -> matchesBySource[sourceId]?.let { "已对应 $it 集" } ?: "尚未对应剧集"
                            }
                            val line = if (showManual) manual else current
                            val detail = listOfNotNull(stateLabel, line?.episode?.group?.let(::animeSourceGroupLabel),
                                line?.episode?.edition?.takeIf(String::isNotBlank),
                                "优先".takeIf { state.preferredSourceId == sourceId }).joinToString(" · ")
                            Column {
                                AnimeSourceProviderRow(sourceId, name, detail,
                                    onClickLabel = if (expanded) "收起${name}的线路" else "查看${name}的线路",
                                    onClick = {
                                        sourceDetails = if (expanded) null else sourceId
                                        expandedSource = null
                                        mapping = null
                                        episodeQuery = ""
                                    }, trailing = {
                                        if (showCurrent) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        if (focusEpisode != null && !expanded && !sourceDisabled && available.size == 1) {
                                            val choice = available.single()
                                            MotionIconButton(onClick = { onPlay(focusEpisode.id, choice) },
                                                modifier = Modifier.size(44.dp), tooltip = "用${name}播放本集") {
                                                Icon(Icons.Outlined.PlayArrow, "用${name}播放本集", Modifier.size(21.dp))
                                            }
                                        }
                                        AnimeSourceMenu(sourceId, state, controller, backdrop, onWebsite = { openWebsite(sourceId) })
                                    })
                                if (expanded) Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    AnimeQuietLabel(title.title)
                                    if (sourceDisabled) AnimeQuietLabel("启用此来源后可读取目录。")
                                    if (error != null) AnimeQuietLabel(error)
                                    if (!sourceDisabled && (error != null || catalog == null)) {
                                        MotionTextButton(onClick = { controller.refreshSource(sourceId) },
                                            modifier = Modifier.heightIn(min = 44.dp)) {
                                            Text(if (error == null) "读取目录" else "重试目录")
                                        }
                                    }
                                    if (catalog != null && error == null) AnimeCatalogNotice(catalog,
                                        retryEnabled = !sourceDisabled, onRetry = { controller.refreshSource(sourceId) })
                                    if (focusEpisode != null) {
                                        lines.forEachIndexed { index, option ->
                                            val failure = lineFailures[option]
                                            val canPlay = !sourceDisabled && option in attemptable
                                            val summary = listOfNotNull(animeSourceEpisodeSummary(option.episode).takeIf(String::isNotBlank),
                                                if (option == manual && recovering && option != current) "上次手动指定" else null,
                                                if (option == current && recovering) "上次使用" else null,
                                                if (failure != null && canPlay) "上次播放失败" else null,
                                                if (!canPlay) "当前不可用" else null).joinToString(" · ")
                                            val optionName = option.episode.title.ifBlank { "线路 ${index + 1}" }
                                            AnimeSourceProviderRow(sourceId, optionName, summary,
                                                onClickLabel = if (failure == null) "播放此线路" else "重试此线路",
                                                enabled = canPlay,
                                                onClick = { onPlay(focusEpisode.id, option) },
                                                trailing = {
                                                    Icon(when {
                                                        !canPlay -> Icons.Outlined.ErrorOutline
                                                        failure == null -> Icons.Outlined.PlayArrow
                                                        else -> Icons.Outlined.Refresh
                                                    },
                                                        null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }, showMark = false)
                                            if (failure != null) AnimeQuietLabel(failure, Modifier.padding(start = 8.dp))
                                        }
                                    }
                                    if (sourceGroups.isNotEmpty()) {
                                        AnimeSectionTitle("线路组")
                                        sourceGroups.forEach { (group, episodes) ->
                                            AnimeSourceProviderRow(sourceId, animeSourceGroupLabel(group),
                                                episodes.size.toString() + " 条记录" + if (selectedGroup == group) " · 已设定" else "",
                                                onClickLabel = "使用此线路组", enabled = !sourceDisabled, onClick = {
                                                    expandedSource = null
                                                    mapping = null
                                                    controller.selectSourceGroup(sourceId, group)
                                                }, showMark = false)
                                        }
                                    }
                                    if (focusEpisode != null && selectedGroup != null && !sourceDisabled) {
                                        MotionTextButton(onClick = {
                                            expandedSource = if (expandedSource == sourceId) null else sourceId
                                            mapping = null
                                            episodeQuery = ""
                                        }, modifier = Modifier.heightIn(min = 44.dp)) {
                                            Text(if (expandedSource == sourceId) "收起集数映射" else
                                                if (lines.isEmpty()) "为本集手动对应" else "调整本集对应")
                                        }
                                        if (expandedSource == sourceId) {
                                            AnimeTextField(episodeQuery, { episodeQuery = it }, "筛选来源集名或编号")
                                            val matchingEpisodes = sourceGroups[selectedGroup].orEmpty().filter { episode ->
                                                episodeQuery.isBlank() || episode.title.contains(episodeQuery, ignoreCase = true) ||
                                                    episode.number?.let(::animeEpisodeNumber)?.contains(episodeQuery) == true
                                            }
                                            matchingEpisodes.take(mappingLimit).forEach { episode ->
                                                val selected = mapping?.catalogKey == title.referenceKey() && mapping?.episode == episode
                                                val summary = animeSourceEpisodeSummary(episode) + if (selected) " · 待应用" else ""
                                                AnimeSourceProviderRow(sourceId, episode.title, summary,
                                                    onClickLabel = "选择此映射",
                                                    onClick = { mapping = EpisodeMappingDraft(sourceId, title.referenceKey(), episode) },
                                                    showMark = false)
                                            }
                                            if (matchingEpisodes.size > mappingLimit) MotionTextButton(onClick = { mappingLimit += 40 }) {
                                                Text("再显示 40 集 · 共 ${matchingEpisodes.size} 集")
                                            }
                                            if (matchingEpisodes.isEmpty()) AnimeQuietLabel("没有符合筛选条件的集数")
                                            mapping?.takeIf { it.catalogKey == title.referenceKey() && it.episode in matchingEpisodes }?.let { draft ->
                                                AnimeSectionTitle("确认本集映射", animeEpisodeLabel(focusEpisode) + " · " + draft.episode.title)
                                                MotionButton(onClick = {
                                                    controller.mapEpisode(focusEpisode.id, draft.sourceId, draft.episode.id)
                                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                                    Text("应用本集映射")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (focusEpisode != null) item(key = "automatic-play") {
                            val availability = controller.episodeAvailability(focusEpisode.id, state)
                            val canAutoPlay = controller.hasAutomaticPlaybackLine(focusEpisode.id)
                            val finding = state.autoMatching || (!canAutoPlay && availability == AnimeEpisodeAvailability.CHECKING)
                            when {
                                availability == AnimeEpisodeAvailability.UPCOMING -> AnimeQuietLabel("本集尚未放送")
                                canAutoPlay -> MotionTextButton(onClick = { onAutomaticPlay(focusEpisode) }, enabled = !finding,
                                    modifier = Modifier.heightIn(min = 44.dp)) {
                                    Text(if (availability == AnimeEpisodeAvailability.RECENTLY_FAILED) "重试自动选线" else "自动选线并播放")
                                }
                                else -> MotionButton(onClick = controller::retrySourceAggregation, enabled = !finding,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                                    Text(if (finding) "正在查找本集线路" else "查找本集线路")
                                }
                            }
                        }
                        if (pending.isNotEmpty()) {
                            item(key = "pending-heading") { AnimeSectionTitle("待确认的片源") }
                            items(pending, key = { "pending:" + it.referenceKey() }) { title ->
                                val name = animeSourceName(title.sourceId, state)
                                AnimeSourceProviderRow(title.sourceId, name,
                                    listOfNotNull(title.title, title.year?.toString()).joinToString(" · "),
                                    onClickLabel = "核对${name}的目录", onClick = { openPreview(title) },
                                    trailing = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                            }
                        }
                        if (state.sourceAggregationTotal > 0 || state.sourceAggregationRunning) item(key = "aggregation-progress") {
                            val unfinished = state.sourceAggregation.values.count { it.phase in setOf(
                                AnimeAssociationPhase.UNAVAILABLE, AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING) }
                            val summary = when {
                                state.sourceAggregationRunning ->
                                    "正在核对 ${state.sourceAggregationCompleted} / ${state.sourceAggregationTotal} 个来源"
                                unfinished > 0 -> "本轮已结束 · $unfinished 个未完成"
                                else -> "本轮核对已结束"
                            }
                            AnimeQuietLabel(summary)
                        }
                        if (associationIssues.isNotEmpty() || state.sourceAggregationTotal > 0 || state.sourceError != null) {
                            item(key = "association-diagnostics-toggle") {
                                AnimeSourceDisclosure(aggregationExpanded, "核对详情") { aggregationExpanded = !aggregationExpanded }
                            }
                            if (aggregationExpanded) {
                                state.sourceError?.let { error -> item(key = "source-error") { AnimeQuietLabel(error) } }
                                items(associationIssues.entries.toList(), key = { "aggregation-result:" + it.key }) { (sourceId, progress) ->
                                    AnimeSectionTitle(animeSourceName(sourceId, state), animeAssociationPhaseLabel(progress.phase))
                                }
                                item(key = "retry-source-aggregation") {
                                    MotionTextButton(onClick = controller::retrySourceAggregation,
                                        enabled = !state.sourceAggregationRunning, modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text("重新核对来源")
                                    }
                                }
                            }
                        }
                        item(key = "search-sources-toggle") {
                            AnimeSourceDisclosure(manageExpanded, "手动查找更多片源") { manageExpanded = !manageExpanded }
                        }
                        if (manageExpanded) {
                            item(key = "search-sources") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AnimeTextField(query, { query = it }, "番剧原名、别名或关键词", Modifier.weight(1f),
                                        onSubmit = { controller.searchSources(query.trim()) })
                                    MotionIconButton(onClick = { controller.searchSources(query.trim()) },
                                        modifier = Modifier.size(48.dp), tooltip = "搜索播放来源") {
                                        Icon(Icons.Outlined.Search, "搜索来源", Modifier.size(21.dp))
                                    }
                                }
                            }
                            val searchRows = state.sourceResults.map { result ->
                                result to result.items.distinctBy { it.referenceKey() }.filterNot { title ->
                                    val key = title.referenceKey()
                                    key in candidateKeys || key in linkedKeys
                                }
                            }
                            val results = searchRows.filter { (result, rows) -> rows.isNotEmpty() || result.hasMore || result.loadingMore }
                            items(results, key = { "search:" + it.first.providerId }) { (result, rows) ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    AnimeSectionTitle(result.name, "${rows.size} 个候选")
                                    rows.forEach { title ->
                                        val detail = listOfNotNull(title.year?.toString(),
                                            title.episodeCount?.let { "$it 集" },
                                            "已排除自动关联".takeIf { title.sourceId in excludedSourceIds }).joinToString(" · ")
                                        AnimeSourceProviderRow(result.providerId, title.title, detail,
                                            onClickLabel = "核对${result.name}的目录", onClick = { openPreview(title) },
                                            trailing = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                                    }
                                    if (result.error != null) {
                                        AnimeQuietLabel(result.error)
                                        MotionTextButton(onClick = { controller.retrySourceSearch(result.providerId) }) { Text("重试搜索") }
                                    }
                                    if (result.hasMore) MotionTextButton(onClick = { controller.loadMoreSourceSearch(result.providerId) },
                                        enabled = !result.loading && !result.loadingMore,
                                        modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text(if (result.loadingMore) "正在加载" else "更多候选")
                                    }
                                }
                            }
                            val searching = searchRows.count { it.first.loading }
                            val searchIssues = searchRows.filter { (result, rows) ->
                                !result.loading && !result.loadingMore && rows.isEmpty() && !result.hasMore &&
                                    (result.error != null || (result.page > 0 && result.items.isEmpty()))
                            }.map { it.first }
                            if (searching > 0) item(key = "search-progress") { AnimeQuietLabel("$searching 个来源正在搜索") }
                            if (searchIssues.isNotEmpty()) {
                                item(key = "search-diagnostics-toggle") {
                                    AnimeSourceDisclosure(searchDiagnosticsExpanded, "搜索详情") {
                                        searchDiagnosticsExpanded = !searchDiagnosticsExpanded
                                    }
                                }
                                if (searchDiagnosticsExpanded) items(searchIssues, key = { "search-diagnostics:" + it.providerId }) { result ->
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        AnimeSectionTitle(result.name, result.error ?: "没有找到候选")
                                        if (result.error != null) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                MotionTextButton(onClick = { controller.retrySourceSearch(result.providerId) }) { Text("重试搜索") }
                                                if (controller.sourceHomes[result.providerId] != null)
                                                    MotionTextButton(onClick = { openWebsite(result.providerId) }) { Text("打开来源网站") }
                                            }
                                        }
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

@Composable
private fun AnimeSourceProviderRow(sourceId: String, title: String, detail: String, onClickLabel: String,
    onClick: () -> Unit, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null,
    showMark: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .98f)
            .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (showMark) Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
                AnimeSourceMark(sourceId, title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onSurface.copy(alpha = if (enabled) 1f else .55f))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun AnimeSourceDisclosure(expanded: Boolean, label: String, onClick: () -> Unit) {
    MotionTextButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp).semantics {
        stateDescription = if (expanded) "已展开" else "已收起"
    }) {
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

private fun animeAssociationPhaseLabel(phase: AnimeAssociationPhase): String = when (phase) {
    AnimeAssociationPhase.QUEUED -> "等待核对"
    AnimeAssociationPhase.SEARCHING -> "正在搜索并核对"
    AnimeAssociationPhase.LINKED -> "作品已关联"
    AnimeAssociationPhase.NO_MATCH -> "未找到可靠候选"
    AnimeAssociationPhase.NEEDS_SELECTION -> "待确认作品或版本"
    AnimeAssociationPhase.UNAVAILABLE -> "搜索或目录读取未完成"
    AnimeAssociationPhase.EXCLUDED -> "已排除自动关联"
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
            CompactMenuAction(Icons.Outlined.StarOutline, "设为优先来源", onClick = {
                expanded = false; controller.setPreferredSource(sourceId)
            })
            CompactMenuAction(Icons.Outlined.Language, "使用直连", onClick = {
                expanded = false; controller.setSourceRoute(sourceId, DownloadRouteMode.DIRECT)
            })
            CompactMenuAction(Icons.Outlined.Public, "使用代理", onClick = {
                expanded = false; controller.setSourceRoute(sourceId, DownloadRouteMode.PROXY)
            })
            if (controller.sourceHomes[sourceId] != null) {
                CompactMenuAction(Icons.Outlined.OpenInNew, "打开来源网站", onClick = {
                    expanded = false
                    onWebsite()
                })
            }
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

private fun animeSourceGroupLabel(group: String): String = when {
    group.startsWith("cht [") -> "繁体线路 · " + group
    group.startsWith("chs [") -> "简体线路 · " + group
    else -> group.ifBlank { "默认线路" }
}
