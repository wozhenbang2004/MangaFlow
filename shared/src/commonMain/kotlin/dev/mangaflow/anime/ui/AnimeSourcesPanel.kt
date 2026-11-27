package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

private data class EpisodeMappingDraft(val sourceId: String, val catalogKey: String, val episode: AnimeSourceEpisode)

private fun episodeRangeLabel(localNumber: Int, endpoint: AnimeController.EpisodeRangeEndpoint): String =
    "本番第 $localNumber 集 → 来源第 ${endpoint.number} 集" +
        endpoint.caption.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()

private enum class AnimeSourceTools { CANDIDATES, SEARCH, DIAGNOSTICS }

private enum class AnimeBoundSourceMark { CURRENT, AUTOMATIC, MANUAL, LOADING, ISSUE }

private fun AnimeSourceTitle.referenceKey(): String = "$sourceId:${id.length}:$id:$url"

@Composable
internal fun AnimeSourcesPanel(
    visible: Boolean,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    focusEpisode: AnimeEpisode?,
    overVideo: Boolean,
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
    var rangeReview by remember(subject?.id, focusEpisode?.id, visible) { mutableStateOf<AnimeController.EpisodeRangeReview?>(null) }
    var expandedSource by remember(subject?.id, focusEpisode?.id) { mutableStateOf<String?>(null) }
    var sourceDetails by remember(subject?.id, focusEpisode?.id) { mutableStateOf<String?>(null) }
    var lineDetails by remember(subject?.id, focusEpisode?.id, sourceDetails) { mutableStateOf<AnimeEpisodeOption?>(null) }
    var groupDetails by remember(subject?.id, focusEpisode?.id, sourceDetails) { mutableStateOf<Boolean?>(null) }
    var tools by remember(subject?.id, focusEpisode?.id) { mutableStateOf<AnimeSourceTools?>(null) }
    var cataloguesExpanded by remember(subject?.id, focusEpisode?.id) { mutableStateOf(false) }
    var allEpisodeLines by remember(subject?.id, focusEpisode?.id) { mutableStateOf(false) }
    val manageExpanded = tools == AnimeSourceTools.SEARCH
    val aggregationExpanded = tools == AnimeSourceTools.DIAGNOSTICS
    fun toggleTool(tool: AnimeSourceTools) { tools = if (tools == tool) null else tool }
    var searchDiagnosticsExpanded by remember(subject?.id) { mutableStateOf(false) }
    var showOtherSearchResults by remember(subject?.id) { mutableStateOf(false) }
    var episodeQuery by remember(focusEpisode?.id) { mutableStateOf("") }
    var mappingLimit by remember(expandedSource, episodeQuery) { mutableIntStateOf(40) }
    val preview = state.previewCatalog
    val subjectId = subject?.id
    var requestedPreview by remember(subjectId) { mutableStateOf<AnimeSourceTitle?>(null) }
    var pendingSourceWork by remember(subjectId) { mutableStateOf<AnimeSourceTitle?>(null) }
    var presentedOwner by remember { mutableStateOf<String?>(null) }
    SideEffect { if (visible) presentedOwner = subjectId }
    val presenting = visible || presentedOwner != null
    fun openPreview(title: AnimeSourceTitle) {
        rangeReview = null
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
    val desktopScrolls = remember(subjectId, focusEpisode?.id) { mutableMapOf<Pair<AnimeSourceTools?, Boolean>, LazyListState>() }
    val previewScroll = key(previewKey) { rememberLazyListState() }
    val bindings = remember(presenting, state.sourceBindings, subjectId) {
        if (presenting) state.sourceBindings.filter { it.subjectId == subjectId }.distinctBy { it.catalog.sourceId }
        else emptyList()
    }
    val linkedKeys = remember(bindings) { bindings.map { it.catalog.referenceKey() }.toSet() }
    val boundIds = remember(bindings) { bindings.map { it.catalog.sourceId }.toSet() }
    val catalogs = remember(presenting, state.sourceCatalogs) {
        if (presenting) state.sourceCatalogs.associateBy { it.title.referenceKey() } else emptyMap()
    }
    val excludedSourceIds = state.library.firstOrNull { it.subject.id == subjectId }?.excludedSourceIds.orEmpty()
    val pending = remember(presenting, state.groupSourceCandidates, state.associationChoices, boundIds, excludedSourceIds) {
        if (!presenting) emptyList() else
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
    val episodeLines = focusEpisode?.takeIf { presenting }?.let { controller.confirmedPlaybackLines(it.id, state) }.orEmpty()
        .filter { it.catalog.referenceKey() in linkedKeys }
    val workSamples = if (visible && focusEpisode == null) controller.workPlaybackMeasurements(state) else emptyMap()
    val rankedLines = if (presenting) focusEpisode?.let { controller.rankedPlaybackLines(it.id) }.orEmpty() else emptyList()
    val recommendedLine = rankedLines.firstOrNull()?.let { recommended ->
        episodeLines.singleOrNull { controller.samePlaybackLine(it, recommended) }
    }
    val sourceOrder = rankedLines.map { it.sourceId }.distinct().withIndex().associate { it.value to it.index }
    val activeLine = state.selectedPlaybackOption.takeIf { state.selectedEpisodeId == focusEpisode?.id }?.let { current ->
        episodeLines.singleOrNull { controller.samePlaybackLine(it, current) }
    }
    val focusId = focusEpisode?.id
    val detailVisit = controller.lineLookupDetailVisit
    val lookupVisit = remember(controller, visible, subjectId, focusId, detailVisit) {
        if (visible && subjectId != null && focusId != null && focusEpisode?.subjectId == subjectId)
            controller.lineLookupVisit(subjectId, focusId) else null
    }
    var lookupAttached by remember(lookupVisit) { mutableStateOf(false) }
    var unfinishedExpanded by remember(subjectId, focusId, detailVisit) { mutableStateOf(false) }
    DisposableEffect(controller, lookupVisit) {
        lookupAttached = lookupVisit?.let(controller::attachLineLookup) == true
        onDispose { lookupVisit?.let(controller::releaseLineLookup) }
    }
    fun closeChooser() {
        lookupVisit?.let(controller::releaseLineLookup)
        onDismiss()
    }
    val manualTarget = state.playbackTarget?.takeIf { it.episode.id == focusEpisode?.id }
    val manualLine = manualTarget?.manualOption?.let { requested ->
        episodeLines.singleOrNull { controller.samePlaybackLine(it, requested) }
    }
    // Keep existing source buttons in place while sample results arrive.
    val orderedBindings = remember(visible, subjectId, focusEpisode?.id, bindings) {
        if (focusEpisode == null) bindings.sortedBy { it.catalog.sourceId != state.preferredSourceId }
        else bindings.sortedBy { binding ->
            val sourceId = binding.catalog.sourceId
            when {
                !recovering && manualTarget == null && activeLine?.sourceId == sourceId -> -1
                else -> sourceOrder[sourceId] ?: Int.MAX_VALUE
            }
        }
    }
    val compactPlaybackCoverage = platformKind == PlatformKind.DESKTOP && overVideo && focusId != null &&
        subjectId != null && focusEpisode?.subjectId == subjectId
    val episodeCoverage = if (presenting && focusId != null) {
        val confirmedSources = episodeLines.mapTo(mutableSetOf()) { it.sourceId }
        state.sourceAggregation.filter { (id, progress) ->
            id !in confirmedSources && (progress.episodeId == null || progress.episodeId == focusId)
        }
    } else emptyMap()
    val unfinishedSources = if (compactPlaybackCoverage) {
        val unlisted = state.sourceExtensions.filterNot { it.installed && it.enabled }.mapTo(mutableSetOf()) { it.id }
        val playing = state.selectedPlaybackOption?.takeIf { state.selectedEpisodeId == focusId }?.sourceId
        val manual = manualTarget?.manualOption?.sourceId
        episodeCoverage.filter { (id, progress) ->
            id !in excludedSourceIds && id !in unlisted && id != playing && id != manual &&
                (progress.phase.checking || progress.phase == AnimeAssociationPhase.PAUSED ||
                    progress.phase == AnimeAssociationPhase.UNAVAILABLE)
        }.keys
    } else emptySet()
    val visibleBindings = if (compactPlaybackCoverage) orderedBindings.filter { binding ->
        val id = binding.catalog.sourceId
        // Do not unmount a source the owner already opened when a late check changes its status.
        val inUse = id == sourceDetails || id == expandedSource
        if (id in unfinishedSources) unfinishedExpanded || (cataloguesExpanded && inUse)
        else cataloguesExpanded || (unfinishedExpanded && inUse)
    } else if (focusEpisode == null || cataloguesExpanded) orderedBindings else emptyList()
    val matchesBySource = remember(state.episodeOptions, linkedKeys) {
        if (linkedKeys.isEmpty()) emptyMap() else state.episodeOptions.values.flatMap { lines ->
            lines.filter { it.confirmed && it.catalog.referenceKey() in linkedKeys }.map { it.sourceId }.distinct()
        }.groupingBy { it }.eachCount()
    }
    val workLineCounts = remember(state.episodeOptions, linkedKeys, focusEpisode == null) {
        if (linkedKeys.isEmpty() || focusEpisode != null) emptyMap() else
        state.episodeOptions.values.flatten().filter { it.confirmed && it.catalog.referenceKey() in linkedKeys }
            .map { Triple(it.sourceId, it.catalog.referenceKey(), it.episode.group) }
            .distinct().groupingBy { it.first }.eachCount()
    }
    val associationIssues = state.sourceAggregation.filter { (id, progress) ->
        id !in boundIds && id !in candidateSourceIds && progress.phase != AnimeAssociationPhase.LINKED
    }
    val automaticEpisode = if (presenting) focusEpisode ?: subjectId?.let(controller::continuationEpisode) else null
    val canAutoPlay = if (focusEpisode != null) rankedLines.isNotEmpty()
        else automaticEpisode?.let { controller.hasAutomaticPlaybackLine(it.id) } == true
    val attemptingAutomaticPlayback = state.autoMatching && automaticEpisode != null &&
        state.playbackTarget?.episode?.id == automaticEpisode.id
    val associating = state.sourceAggregationRunning || state.episodeOptionsLoading
    val measurementBatch = state.sourceMeasurements?.takeIf {
        it.subjectId == subjectId && it.episodeId == focusEpisode?.id
    }
    // A refreshed catalogue can replace a provider's line token during a batch.
    // Use the current confirmed lines to associate a result with the visible row.
    val currentLineMeasurements = remember(episodeLines, measurementBatch) {
        episodeLines.mapNotNull { line ->
            measurementBatch?.sources?.get(line.sourceId)?.lineMeasurements?.entries
                ?.lastOrNull { controller.samePlaybackLine(it.key, line) }?.value?.let { line to it }
        }.toMap()
    }
    val measuring = measurementBatch?.running == true
    val motion = LocalMotionEnabled.current
    LaunchedEffect(visible, subjectId, focusEpisode?.id) {
        if (visible && subjectId != null) controller.verifyEpisodeSources(focusEpisode?.id)
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
    AnimeLinePanel(visible, overVideo, backdrop, onDismiss = { if (previewing) closePreview() else closeChooser() },
        onClosed = {
            val requested = pendingSourceWork
            val owner = presentedOwner
            pendingSourceWork = null
            presentedOwner = null
            mapping = null
            rangeReview = null
            expandedSource = null
            sourceDetails = null
            tools = null
            cataloguesExpanded = false
            unfinishedExpanded = false
            allEpisodeLines = false
            searchDiagnosticsExpanded = false
            websiteError = null
            if (owner != null && owner == controller.state.value.detail?.subject?.id) {
                controller.cancelEpisodeSourceMeasurements(clearProgress = true)
                closePreview()
                if (requested != null && controller.currentVideoCandidates(listOf(requested)).isNotEmpty()) {
                    onOpenSourceWork?.invoke(requested)
                }
            }
        }) {
            val window = LocalMangaWindow.current
            Column(Modifier.fillMaxWidth().then(if (overVideo) Modifier.height(window.height)
                else Modifier.heightIn(max = window.panelHeight))) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (previewing) MotionIconButton(onClick = ::closePreview, modifier = Modifier.size(44.dp),
                        tooltip = "返回来源") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = if (previewing) 2.dp else 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(when {
                            previewLinked || openAsNative || state.previewLoading || failedPreview != null -> "来源目录"
                            previewing -> "确认作品关联"
                            focusEpisode != null -> "播放线路"
                            else -> "作品来源"
                        }, style = MaterialTheme.typography.titleLarge)
                        val context = if (previewing) subject?.title else if (overVideo) focusEpisode?.let(::animeEpisodeLabel) else listOfNotNull(
                            focusEpisode?.let(::animeEpisodeLabel), subject?.title).joinToString(" · ")
                        if (!context.isNullOrBlank()) Text(context, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!previewing && subjectId != null && (focusEpisode == null || episodeLines.isNotEmpty() || aggregationExpanded)) {
                        val probeLabel = if (focusEpisode != null) "探测本集线路，不开始播放" else "探测各来源的一集，不开始播放"
                        if (measuring && measurementBatch?.manual == true) Box(Modifier.size(44.dp).semantics { contentDescription = "正在探测来源画质与媒体网络" },
                            contentAlignment = Alignment.Center) { MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.5.dp) }
                        else MotionIconButton(onClick = {
                            controller.verifyEpisodeSources(focusEpisode?.id, retryFailed = true)
                            bindings.filter { binding ->
                                state.sourceAggregation[binding.catalog.sourceId]?.phase == AnimeAssociationPhase.LINKED &&
                                    (binding.catalog.referenceKey() !in catalogs || binding.catalog.sourceId in state.sourceCatalogErrors)
                            }.forEach { controller.refreshSource(it.catalog.sourceId) }
                            if (focusEpisode != null) controller.measureEpisodeSources(focusEpisode.id, force = true)
                            else controller.measureWorkSources(force = true)
                        },
                            enabled = visible,
                            modifier = Modifier.size(44.dp), tooltip = probeLabel) {
                            Icon(Icons.Outlined.Speed, probeLabel, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MotionIconButton(onClick = ::closeChooser, modifier = Modifier.size(44.dp), tooltip = "关闭线路面板") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (previewing) {
                    AnimeScrollableList(Modifier.weight(1f, fill = false), state = previewScroll, contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.previewLoading && preview == null) item {
                            AnimeMessage("正在读取来源目录")
                        }
                        if (preview != null) {
                            val previewDetail = state.detail
                            val identityIssue = if (previewDetail == null) "当前作品资料尚未取得。"
                                else animeCatalogIdentityIssue(previewDetail, preview)
                            val routeCount = preview.episodes.map { it.group }.distinct().size
                            val multiEpisodeWork = subject?.format == AnimeDiscoveryFormat.SERIES ||
                                subject?.format != AnimeDiscoveryFormat.MOVIE &&
                                maxOf(subject?.episodeCount ?: 0,
                                    previewDetail?.episodes?.count { it.kind == AnimeEpisodeKind.MAIN } ?: 0) > 1
                            val unnumberedSeries = multiEpisodeWork && preview.episodes.isNotEmpty() &&
                                preview.episodes.all { it.number == null }
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
                            if (!openAsNative && (!previewLinked || unnumberedSeries)) item {
                                val explanation = listOfNotNull(
                                    (identityIssue ?: "作品身份已核对，可对应的集数与线路会自动整理。").takeIf { !previewLinked },
                                    "来源目录未标注集号，暂时无法对应到本番的具体集。".takeIf { unnumberedSeries },
                                ).joinToString(" ")
                                Text(explanation,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item { AnimeSectionTitle("来源目录", "$routeCount 条线路 · ${preview.episodes.size} 条播放记录") }
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
                                    Text(if (allPreviewEpisodes) "收起目录" else "查看全部 " + preview.episodes.size + " 条播放记录")
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
                            closeChooser()
                        }, enabled = visible && !state.previewLoading,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text("打开此来源的目录") }
                    } else if (preview != null && !openAsNative && !previewLinked) {
                        MotionButton(onClick = controller::bindPreviewSource, enabled = !state.previewLoading,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                            Text(if (previousBinding != null) "确认替换来源关联" else "确认关联此作品")
                        }
                    }
                } else {
                    val canInspect = focusEpisode != null || associationIssues.isNotEmpty() ||
                        state.sourceAggregationTotal > 0 || state.sourceError != null || state.detailFirstConfirmedLineMs != null
                    val entries: LazyListScope.(Boolean) -> Unit = { wide ->
                        val overview = !wide || tools == null && (focusEpisode == null || !cataloguesExpanded)
                        if (overview && focusEpisode != null) {
                            item(key = "episode-auto") {
                                val availability = if (canAutoPlay) AnimeEpisodeAvailability.MATCHED
                                    else controller.episodeAvailability(focusEpisode.id, state)
                                val recoveringThisEpisode = recovering &&
                                    (state.selectedEpisodeId == focusEpisode.id || manualTarget != null)
                                val retry = recoveringThisEpisode || availability == AnimeEpisodeAvailability.RECENTLY_FAILED
                                val title = when {
                                    availability == AnimeEpisodeAvailability.UPCOMING -> "待放送"
                                    attemptingAutomaticPlayback && canAutoPlay -> "正在开播"
                                    attemptingAutomaticPlayback -> "正在找线路"
                                    retry -> "自动重试"
                                    canAutoPlay -> "播放本集"
                                    else -> "查找并播放"
                                }
                                val status = when {
                                    availability == AnimeEpisodeAvailability.UPCOMING -> "本集尚未放送"
                                    attemptingAutomaticPlayback && canAutoPlay -> "已找到本集线路"
                                    attemptingAutomaticPlayback -> "找到本集线路后自动播放"
                                    recoveringThisEpisode && canAutoPlay -> "有其他本集线路可尝试"
                                    recoveringThisEpisode -> "上一条线路未能播放"
                                    activeLine != null -> "当前线路 · ${activeLine.sourceName}"
                                    recommendedLine != null -> "优先尝试 ${recommendedLine.sourceName}"
                                    canAutoPlay -> "已对应本集 · 自动选线"
                                    availability == AnimeEpisodeAvailability.CHECKING -> "正在核对本集线路"
                                    episodeLines.isNotEmpty() -> "仍可尝试重试本集线路"
                                    else -> "暂未找到本集线路"
                                }
                                AnimeLineChoice(
                                    title = title,
                                    detail = status,
                                    icon = if (retry) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                                    active = true,
                                    busy = attemptingAutomaticPlayback,
                                    enabled = !attemptingAutomaticPlayback && availability != AnimeEpisodeAvailability.UPCOMING,
                                    onClick = { onAutomaticPlay(focusEpisode) },
                                )
                            }
                            val otherCoverage = episodeCoverage.values
                            val checkingSources = otherCoverage.count { it.phase.checking }
                            val pausedSources = otherCoverage.count { it.phase == AnimeAssociationPhase.PAUSED }
                            val failedSources = otherCoverage.count { it.phase == AnimeAssociationPhase.UNAVAILABLE }
                            val canContinue = lookupAttached && lookupVisit?.let(controller::canContinueLineLookup) == true
                            val checkingEpisode = state.episodeOptionsLoading && !state.detailLoading
                            val showCoverage = episodeLines.isNotEmpty() || canContinue || checkingEpisode ||
                                if (compactPlaybackCoverage) unfinishedSources.isNotEmpty()
                                else checkingSources > 0 || pausedSources > 0 || failedSources > 0
                            if (showCoverage) item(key = "episode-source-coverage") {
                                Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
                                    val coverage = buildList {
                                        add("已确认 ${episodeLines.size} 条")
                                        if (checkingSources > 0) add("$checkingSources 个来源在排队或核对")
                                        else if (checkingEpisode) add("正在核对本集")
                                        if (pausedSources > 0) add("$pausedSources 个来源已暂停")
                                        if (failedSources > 0) add("$failedSources 个来源未查完")
                                    }
                                    if (compactPlaybackCoverage && unfinishedSources.isNotEmpty()) {
                                        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                            AnimeSourceDisclosure(unfinishedExpanded, "还有 ${unfinishedSources.size} 个来源没查完") {
                                                unfinishedExpanded = !unfinishedExpanded
                                            }
                                        }
                                    } else {
                                        val summary = when {
                                            !compactPlaybackCoverage -> coverage.joinToString(" · ")
                                            canContinue -> "还有来源可以继续核对"
                                            checkingEpisode -> "正在核对本集"
                                            else -> "已确认 ${episodeLines.size} 条线路"
                                        }
                                        AnimeQuietLabel(summary, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    }
                                    if (canContinue && lookupVisit != null) MotionTextButton(onClick = {
                                        controller.continueLineLookup(lookupVisit)
                                    }, enabled = visible, modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text("继续找本集线路")
                                    }
                                }
                            }
                            if (episodeLines.isNotEmpty()) {
                                item(key = "episode-lines-heading") {
                                    AnimeSectionTitle("手动选线", modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                }
                                // Keep the active and explicitly requested lines visible without sorting by every new sample.
                                val featured = (listOfNotNull(activeLine, manualLine) + episodeLines).distinct().take(8).toSet()
                                val visibleLines = if (allEpisodeLines) episodeLines else episodeLines.filter { it in featured }
                                items(visibleLines, key = { "line:" + controller.optionKey(it) }) { option ->
                                    val failure = controller.recentPlaybackLineFailure(focusEpisode.id, option, state)
                                    val enabled = controller.canAttemptPlaybackLine(focusEpisode.id, option, state)
                                    val current = option == activeLine && !recovering
                                    val openingLine = option == manualLine && state.resolving && !recovering && manualTarget?.error == null
                                    val status = when {
                                        openingLine -> "正在打开"
                                        current -> "当前线路"
                                        !enabled -> "暂不可用"
                                        failure != null -> "最近播放失败，可重试"
                                        option == recommendedLine -> "自动首选"
                                        else -> "已对应本集"
                                    }
                                    val metrics = controller.playbackMeasurements(focusEpisode.id, option)
                                    val probe = currentLineMeasurements[option]
                                    val group = option.episode.group.takeIf(String::isNotBlank)?.let(::animeSourceGroupLabel)
                                    AnimeLineChoice(
                                        title = option.sourceName,
                                        detail = listOfNotNull(group, option.episode.edition.takeIf(String::isNotBlank), status)
                                            .distinct().joinToString(" · "),
                                        sourceId = option.sourceId,
                                        icon = when {
                                            current -> Icons.Outlined.Check
                                            failure != null -> Icons.Outlined.Refresh
                                            else -> Icons.Outlined.PlayArrow
                                        },
                                        active = current,
                                        busy = openingLine,
                                        enabled = enabled && !current && !openingLine,
                                        actionLabel = if (failure != null) "重试 ${option.sourceName}" else "用 ${option.sourceName} 播放本集",
                                        supporting = if (metrics != null || probe != null) ({
                                            AnimeSourceMetricsStrip(metrics, progress = probe)
                                        }) else null,
                                        onClick = { onPlay(focusEpisode.id, option) },
                                    )
                                }
                                if (episodeLines.size > 8) item(key = "episode-more-lines") {
                                    AnimeSourceDisclosure(allEpisodeLines,
                                        if (allEpisodeLines) "收起其余线路" else "查看全部 ${episodeLines.size} 条线路") {
                                        allEpisodeLines = !allEpisodeLines
                                    }
                                }
                            } else if (!associating && !manageExpanded) {
                                item(key = "episode-find-source") {
                                    MotionTextButton(onClick = { toggleTool(AnimeSourceTools.SEARCH) }) {
                                        Text("手动查找片源")
                                    }
                                }
                            }
                            if (!wide && bindings.any { !compactPlaybackCoverage || it.catalog.sourceId !in unfinishedSources })
                                item(key = "source-catalogues") {
                                AnimeSourceDisclosure(cataloguesExpanded, "来源目录与集数校正") {
                                    cataloguesExpanded = !cataloguesExpanded
                                }
                            }
                        }
                        if (overview && focusEpisode == null && bindings.isNotEmpty()) item(key = "source-availability") {
                            val sourceCount = matchesBySource.size
                            val lineCount = workLineCounts.values.sum()
                            AnimeQuietLabel(when {
                                sourceCount > 0 -> "已对应 $sourceCount 个来源 · $lineCount 条线路"
                                else -> "已关联 ${bindings.size} 个来源 · 选集待对应"
                            }, Modifier.padding(start = 8.dp, bottom = 2.dp))
                        }
                        if ((overview || aggregationExpanded) && measurementBatch != null && measurementBatch.sources.isNotEmpty() &&
                            (measurementBatch.manual || aggregationExpanded)) item(key = "probe-coverage") {
                            val progress = measurementBatch.sources.values
                            val counts = progress.groupingBy { it.phase }.eachCount()
                            val hasLineBatch = progress.any { it.lineMeasurements.isNotEmpty() }
                            val lineResults = currentLineMeasurements.values
                            val lineCounts = lineResults.groupingBy { it.phase }.eachCount()
                            val scope = if (focusEpisode == null) "媒体探测 · 各源采样一集" else "本集媒体探测"
                            val testedLines = currentLineMeasurements.filterValues { it.phase.evidencePriority > 0 }
                            val testedSources = if (hasLineBatch) testedLines.keys.map { it.sourceId }.distinct().size
                                else progress.count { it.phase.evidencePriority > 0 }
                            val summary = when {
                                !hasLineBatch -> "$scope · 已测 $testedSources 个来源"
                                lineResults.isEmpty() -> "$scope · 线路已更新，可重新探测"
                                else -> "$scope · 已测 $testedSources 个来源 · ${testedLines.size}/${lineResults.size} 条线路"
                            }
                            val status = when {
                                measuring -> " · 探测中"
                                state.sourceAggregationRunning && focusEpisode != null -> " · 正在找片源"
                                else -> ""
                            }
                            AnimeQuietLabel(summary + status,
                                Modifier.padding(start = 8.dp, bottom = 2.dp))
                            val outcomes = listOf(
                                AnimeSourceMeasurementPhase.COMPLETE to if (measurementBatch.manual) "样本可读" else "已有媒体证据",
                                AnimeSourceMeasurementPhase.MANIFEST_ONLY to "仅清单",
                                AnimeSourceMeasurementPhase.ADDRESS_ONLY to "仅取址",
                                AnimeSourceMeasurementPhase.FAILED to "失败",
                                AnimeSourceMeasurementPhase.WAITING_CATALOG to "待目录",
                                AnimeSourceMeasurementPhase.NO_EPISODE to "尚无对应集",
                                AnimeSourceMeasurementPhase.UNAVAILABLE to "待重试",
                                AnimeSourceMeasurementPhase.CANCELLED to "已停止",
                            ).mapNotNull { (phase, label) ->
                                val perSource = !hasLineBatch || phase == AnimeSourceMeasurementPhase.WAITING_CATALOG ||
                                    phase == AnimeSourceMeasurementPhase.NO_EPISODE
                                val count = if (perSource) counts[phase] else lineCounts[phase]
                                count?.let { "$label $it" + if (perSource) " 源" else " 条" }
                            }
                            if (outcomes.isNotEmpty()) AnimeQuietLabel(outcomes.joinToString(" · "),
                                Modifier.padding(start = 8.dp, bottom = 2.dp))
                        }
                        if (overview && focusEpisode == null && automaticEpisode != null) item(key = "automatic-play") {
                            val availability = if (canAutoPlay) AnimeEpisodeAvailability.MATCHED
                                else controller.episodeAvailability(automaticEpisode.id, state)
                            when {
                                availability == AnimeEpisodeAvailability.UPCOMING -> AnimeQuietLabel("本集尚未放送", Modifier.padding(8.dp))
                                else -> MotionTextButton(onClick = { onAutomaticPlay(automaticEpisode) }, enabled = !attemptingAutomaticPlayback,
                                    modifier = Modifier.heightIn(min = 44.dp)) {
                                    if (attemptingAutomaticPlayback) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                    else Icon(if (availability == AnimeEpisodeAvailability.RECENTLY_FAILED)
                                        Icons.Outlined.Refresh else Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(when {
                                        attemptingAutomaticPlayback && canAutoPlay -> "正在开播"
                                        attemptingAutomaticPlayback -> "正在查找本集线路"
                                        availability == AnimeEpisodeAvailability.RECENTLY_FAILED -> "重试本集播放"
                                        canAutoPlay -> "自动选线播放"
                                        else -> "找源并播放"
                                    })
                                }
                            }
                        } else if (overview && focusEpisode == null && bindings.isEmpty()) item(key = "no-bindings") {
                            AnimeQuietLabel(if (state.sourceAggregationRunning) "正在核对片源" else "暂无已关联的片源",
                                Modifier.padding(8.dp))
                        }
                        if (wide && cataloguesExpanded && tools == null && focusEpisode != null && visibleBindings.isEmpty()) {
                            item { AnimeMessage("本集暂无已关联的来源", action = "查找片源", icon = Icons.Outlined.Link,
                                onAction = { tools = AnimeSourceTools.SEARCH }) }
                        }
                        items(if (!wide || tools == null) visibleBindings else emptyList(), key = { "bound:" + it.catalog.referenceKey() }) { binding ->
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
                            val lines = lineFailures.keys.sortedBy { option ->
                                rankedLines.indexOfFirst { controller.samePlaybackLine(option, it) }.takeIf { it >= 0 } ?: Int.MAX_VALUE
                            }
                            val attemptable = focusEpisode?.let { episode ->
                                lines.filter { controller.canAttemptPlaybackLine(episode.id, it, state) }.toSet()
                            }.orEmpty()
                            val available = lines.filter { it in attemptable && lineFailures[it] == null }
                            val current = activeLine?.takeIf { it in lines }
                            val manual = manualLine?.takeIf { it in lines }
                            val recommendation = recommendedLine?.takeIf { it in attemptable }
                            val expanded = sourceDetails == sourceId
                            val error = state.sourceCatalogErrors[sourceId]
                            val association = state.sourceAggregation[sourceId]
                            val extension = state.sourceExtensions.firstOrNull { it.id == sourceId }
                            val sourceDisabled = extension != null && (!extension.installed || !extension.enabled)
                            val showManual = manual != null && manual != current
                            val showCurrent = current != null && !showManual && !recovering && !sourceDisabled &&
                                current in available
                            val line = (if (showManual) manual else current) ?: recommendation
                                ?: lines.firstOrNull { it in attemptable }
                            val reading = association?.phase == AnimeAssociationPhase.SEARCHING && catalog == null
                            val issue = sourceDisabled || error != null || (lines.isNotEmpty() && available.isEmpty())
                            val stateLabel = when {
                                extension != null && !extension.installed -> "来源已移除"
                                sourceDisabled -> "来源已停用"
                                showManual && (recovering || manualTarget?.error != null) -> "上次手动指定"
                                current != null && recovering -> "上次使用"
                                lines.isNotEmpty() && attemptable.isEmpty() -> "线路暂不可用"
                                lines.isNotEmpty() && available.isEmpty() -> "本集线路近期失败"
                                lines.size > 1 -> "${lines.size} 条线路"
                                lines.size == 1 -> "本集已对应 · 1 条线路"
                                error != null -> if (catalog == null) "目录读取失败" else "目录更新失败"
                                reading -> "读取目录中"
                                association?.phase == AnimeAssociationPhase.QUEUED && catalog == null -> "等待目录"
                                association?.phase == AnimeAssociationPhase.PAUSED && catalog == null -> "目录核对已暂停"
                                catalog == null -> "目录尚未读取"
                                focusEpisode != null && lines.isEmpty() -> "本集未对应"
                                (matchesBySource[sourceId] ?: 0) > 0 ->
                                    "已对应 ${matchesBySource.getValue(sourceId)} 集 · ${workLineCounts[sourceId] ?: 0} 条线路"
                                else -> "作品已关联 · 选集待对应"
                            }
                            val mark = when {
                                showCurrent -> AnimeBoundSourceMark.CURRENT
                                issue -> AnimeBoundSourceMark.ISSUE
                                showManual -> AnimeBoundSourceMark.MANUAL
                                line != null && line == recommendation -> AnimeBoundSourceMark.AUTOMATIC
                                reading -> AnimeBoundSourceMark.LOADING
                                else -> null
                            }
                            val sample = workSamples[sourceId]
                            val measurementProgress = measurementBatch?.sources?.get(sourceId)
                            val hasMappings = if (focusEpisode == null) (matchesBySource[sourceId] ?: 0) > 0 else lines.isNotEmpty()
                            val displayedMeasurement = measurementProgress?.takeUnless { hasMappings &&
                                it.phase in setOf(AnimeSourceMeasurementPhase.WAITING_CATALOG, AnimeSourceMeasurementPhase.NO_EPISODE) }
                            val probeLine = focusEpisode?.let { episode ->
                                val primary = line?.let { controller.playbackMeasurements(episode.id, it) }
                                if (primary?.playbackObservedAt != null ||
                                    primary?.mediaVerified == true && primary.mediaFailure == null) line
                                else {
                                    val samples = measurementProgress?.lines.orEmpty().mapNotNull { candidate ->
                                        if (lines.none { controller.samePlaybackLine(it, candidate) }) return@mapNotNull null
                                        controller.playbackMeasurements(episode.id, candidate)
                                            ?.takeIf { it.mediaVerified == true }?.let { candidate to it }
                                    }
                                    samples.firstOrNull { it.second.mediaFailure == null }?.first
                                        ?: line.takeIf { primary?.mediaVerified == true }
                                        ?: samples.firstOrNull()?.first ?: line
                                }
                            }
                            val sampleEpisode = sample?.let { animeEpisodeLabel(it.episode) } ?: probeLine
                                ?.takeIf { line != null && !controller.samePlaybackLine(it, line) }
                                ?.episode?.group?.takeIf(String::isNotBlank)?.let(::animeSourceGroupLabel)
                            val measurements = if (focusEpisode == null) sample?.measurements
                                else probeLine?.let { controller.playbackMeasurements(focusEpisode.id, it) }
                            val routeLabel = when (state.sourceRoutes[sourceId]) {
                                DownloadRouteMode.DIRECT -> "直连"
                                DownloadRouteMode.PROXY -> "代理"
                                DownloadRouteMode.SYSTEM -> "跟随系统"
                                else -> null
                            }
                            val directPlay = available.singleOrNull()?.takeIf { !expanded && !sourceDisabled }
                            Column {
                                AnimeBoundSourceRow(sourceId, name, stateLabel, mark, expanded,
                                    onExpand = {
                                        sourceDetails = if (expanded) null else sourceId
                                        expandedSource = null
                                        mapping = null
                                        rangeReview = null
                                        episodeQuery = ""
                                    }, onPlay = if (focusEpisode != null && directPlay != null) ({ onPlay(focusEpisode.id, directPlay) }) else null,
                                    metrics = {
                                        if (measurements != null || displayedMeasurement?.phase?.checking == true)
                                            AnimeSourceMetricsStrip(measurements, episodeLabel = sampleEpisode,
                                                progress = displayedMeasurement?.let { AnimeSourceLineMeasurement(it.phase, it.reason) })
                                    })
                                AnimatedVisibility(expanded,
                                    enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                                        fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                                    exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                                        fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                                    Column(Modifier.fillMaxWidth().motionInputGate(expanded)
                                        .padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                val alternateName = title.title.takeIf { it.isNotBlank() && it != subject?.title }
                                                Text(alternateName ?: "来源设置", style = MaterialTheme.typography.labelLarge,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                if (focusEpisode == null) matchesBySource[sourceId]?.let { AnimeQuietLabel("已对应 $it 集") }
                                            }
                                            AnimeSourceMenu(sourceId, state, controller, backdrop, onWebsite = { openWebsite(sourceId) })
                                        }
                                        if (focusEpisode == null) {
                                            sample?.option?.episode?.let { sampledLine ->
                                                val description = listOfNotNull(sampledLine.group.takeIf(String::isNotBlank)?.let(::animeSourceGroupLabel),
                                                    sampledLine.edition.takeIf(String::isNotBlank)).distinct().joinToString(" · ")
                                                if (description.isNotBlank()) AnimeQuietLabel(description)
                                            }
                                            if (sample?.measurements != null) {
                                                AnimeSourceMetricsDetails(sample.measurements, episodeLabel = sampleEpisode,
                                                    routeLabel = routeLabel,
                                                    progress = displayedMeasurement?.let { AnimeSourceLineMeasurement(it.phase, it.reason) })
                                            } else if (displayedMeasurement?.phase?.checking == true) {
                                                AnimeQuietLabel("正在读取媒体数据")
                                            } else displayedMeasurement?.takeUnless {
                                                it.phase in setOf(AnimeSourceMeasurementPhase.WAITING_CATALOG, AnimeSourceMeasurementPhase.NO_EPISODE)
                                            }?.reason?.let { AnimeQuietLabel("媒体探测：$it") }
                                        }
                                        if (focusEpisode != null) displayedMeasurement?.reason
                                            ?.takeUnless { lines.size == 1 && it == measurements?.mediaFailure }
                                            ?.let { AnimeQuietLabel(it) }
                                        if (sourceDisabled) AnimeQuietLabel("启用此来源后可读取目录。")
                                        if (error != null) AnimeQuietLabel(error)
                                        if (error == null && catalog?.notice.isNullOrBlank() &&
                                            (if (focusEpisode == null) (matchesBySource[sourceId] ?: 0) == 0 else lines.isEmpty()))
                                            association?.message?.let { AnimeQuietLabel(it) }
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
                                                val singleLine = lines.size == 1
                                                val showDetails = singleLine || lineDetails?.let { controller.samePlaybackLine(option, it) } == true
                                                val lineName = option.episode.group.takeIf { lines.size > 1 && it.isNotBlank() }
                                                    ?.let(::animeSourceGroupLabel)
                                                val summary = listOfNotNull(option.episode.edition.takeIf(String::isNotBlank),
                                                    if (option == manual && recovering && option != current) "上次手动指定" else null,
                                                    if (option == current && recovering) "上次使用" else null,
                                                    if (failure != null && canPlay) "近期失败" else null,
                                                    if (!canPlay) "暂不可用" else null).distinct().joinToString(" · ")
                                                val optionName = lineName ?: option.episode.title.ifBlank { "线路 ${index + 1}" }
                                                val optionMeasurements = controller.playbackMeasurements(focusEpisode.id, option)
                                                val lineProgress = currentLineMeasurements[option]
                                                val action = if (failure == null) "播放${optionName}" else "重试${optionName}"
                                                AnimeSourceProviderRow(sourceId, optionName, summary,
                                                    onClickLabel = if (singleLine) action else if (showDetails) "收起线路数据" else "查看线路数据",
                                                    enabled = !singleLine || canPlay,
                                                    onClick = {
                                                        if (singleLine) onPlay(focusEpisode.id, option)
                                                        else lineDetails = if (showDetails) null else option
                                                    },
                                                    supporting = if (canPlay && !showDetails) ({
                                                        AnimeSourceMetricsStrip(optionMeasurements, progress = lineProgress)
                                                    }) else null,
                                                    trailing = if (canPlay) ({
                                                        MotionIconButton(onClick = { onPlay(focusEpisode.id, option) },
                                                            modifier = Modifier.size(44.dp), tooltip = action) {
                                                            Icon(if (failure == null) Icons.Outlined.PlayArrow else Icons.Outlined.Refresh,
                                                                action, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }) else null, showMark = false,
                                                    signal = AnimeBoundSourceMark.AUTOMATIC.takeIf { option == recommendation && !showCurrent })
                                                if (showDetails) {
                                                    AnimeSourceMetricsDetails(optionMeasurements, modifier = Modifier.padding(start = 8.dp),
                                                        routeLabel = routeLabel, progress = lineProgress)
                                                    if (failure != null && failure != optionMeasurements?.mediaFailure)
                                                        AnimeQuietLabel(failure, Modifier.padding(start = 8.dp))
                                                    if (!sourceDisabled && (optionMeasurements?.mediaVerified != true ||
                                                            optionMeasurements.mediaFailure != null))
                                                        MotionTextButton(onClick = {
                                                            controller.measureEpisodeSources(focusEpisode.id, force = true, onlyLine = option)
                                                        }, enabled = !measuring && !state.resolving,
                                                            modifier = Modifier.heightIn(min = 44.dp)) {
                                                            Text(if (optionMeasurements?.mediaObservedAt == null) "探测此线路" else "重新探测此线路")
                                                        }
                                                }
                                            }
                                        }
                                        if (sourceGroups.isNotEmpty()) {
                                            val showGroups = groupDetails ?: (focusEpisode != null && lines.isEmpty())
                                            AnimeSourceDisclosure(showGroups, "线路组 · ${sourceGroups.size}") { groupDetails = !showGroups }
                                            if (showGroups) sourceGroups.forEach { (group, episodes) ->
                                                AnimeSourceProviderRow(sourceId, animeSourceGroupLabel(group),
                                                    episodes.size.toString() + " 条记录" + if (selectedGroup == group) " · 已设定" else "",
                                                    onClickLabel = "使用此线路组", enabled = !sourceDisabled, onClick = {
                                                        expandedSource = null
                                                        mapping = null
                                                        rangeReview = null
                                                        controller.selectSourceGroup(sourceId, group)
                                                    }, showMark = false)
                                            }
                                        }
                                        if (focusEpisode != null && selectedGroup != null && !sourceDisabled) {
                                            MotionTextButton(onClick = {
                                                expandedSource = if (expandedSource == sourceId) null else sourceId
                                                mapping = null
                                                rangeReview = null
                                                episodeQuery = ""
                                            }, modifier = Modifier.heightIn(min = 44.dp)) {
                                                Text(if (expandedSource == sourceId) "收起集数映射" else
                                                    if (lines.isEmpty()) "为本集手动对应" else "调整本集对应")
                                            }
                                            if (expandedSource == sourceId) {
                                                val offeredRange = if (visible && !overVideo && lines.isEmpty() && catalog != null) {
                                                    val entryRevision = state.library.firstOrNull { it.subject.id == subjectId }?.revision
                                                    remember(binding, catalog, state.detail, state.sourceCatalogs, state.sourceAggregation,
                                                        state.sourceRoutes, state.sourceCatalogErrors, entryRevision, state.resolving, state.playbackTarget) {
                                                        state.detail?.let { controller.reviewEpisodeRange(it, binding, catalog) }
                                                    }
                                                } else null
                                                val selectedRange = rangeReview?.takeIf { it.binding.catalog.sourceId == sourceId }
                                                if (selectedRange == null && offeredRange != null) {
                                                    MotionTextButton(onClick = {
                                                        mapping = null
                                                        rangeReview = state.detail?.let {
                                                            controller.reviewEpisodeRange(it, binding, offeredRange.catalog)
                                                        }
                                                    },
                                                        modifier = Modifier.heightIn(min = 44.dp)) { Text("核对当前可见区间") }
                                                } else if (selectedRange != null && selectedRange != offeredRange) {
                                                    AnimeQuietLabel("原集数或线路已变化，未应用任何对应。")
                                                    MotionTextButton(onClick = { rangeReview = null }) { Text("返回核对") }
                                                } else if (selectedRange != null) {
                                                    val count = selectedRange.mappings.size
                                                    AnimeSectionTitle("核对当前可见区间", "$name · ${animeSourceGroupLabel(selectedGroup)}")
                                                    AnimeQuietLabel("来源作品：${selectedRange.catalog.title.title}")
                                                    AnimeQuietLabel(episodeRangeLabel(1, selectedRange.first))
                                                    AnimeQuietLabel(episodeRangeLabel(count, selectedRange.last))
                                                    AnimeQuietLabel("两边各有 $count 个连续、不重复的正片编号。只保存这条线路组，不开始播放。")
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        MotionTextButton(onClick = { rangeReview = null },
                                                            modifier = Modifier.heightIn(min = 44.dp)) { Text("取消") }
                                                        MotionButton(onClick = {
                                                            rangeReview = null
                                                            controller.applyEpisodeRange(selectedRange)
                                                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用 $count 集对应") }
                                                    }
                                                }
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
                                                        onClick = {
                                                            rangeReview = null
                                                            mapping = EpisodeMappingDraft(sourceId, title.referenceKey(), episode)
                                                        },
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
                        }
                        val candidatesExpanded = tools == AnimeSourceTools.CANDIDATES ||
                            (tools == null && focusEpisode == null && bindings.isEmpty())
                        if (!wide) item(key = "source-tools") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (associating && (focusEpisode == null || aggregationExpanded)) {
                                    val progress = if (aggregationExpanded && state.sourceAggregationRunning)
                                        "核对 ${state.sourceAggregationCompleted}/${state.sourceAggregationTotal}"
                                    else "核对来源中"
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        Text(progress, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else Spacer(Modifier.weight(1f))
                                if (pending.isNotEmpty() && (focusEpisode != null || bindings.isNotEmpty())) AnimeSourceTool(Icons.Outlined.Link,
                                    "候选片源，${pending.size} 个", selected = candidatesExpanded, count = pending.size,
                                    onClick = { toggleTool(AnimeSourceTools.CANDIDATES) })
                                if (focusEpisode == null || episodeLines.isNotEmpty() || manageExpanded || associating)
                                    AnimeSourceTool(Icons.Outlined.Search, "手动查找片源", selected = manageExpanded,
                                        onClick = { toggleTool(AnimeSourceTools.SEARCH) })
                                if (canInspect) AnimeSourceTool(Icons.Outlined.Info, "核对详情", selected = aggregationExpanded,
                                    onClick = { toggleTool(AnimeSourceTools.DIAGNOSTICS) })
                                if (state.canUndo) MotionIconButton(onClick = controller::undo, modifier = Modifier.size(44.dp),
                                    tooltip = "撤销上次改动") { Icon(Icons.Outlined.Undo, "撤销上次改动", Modifier.size(18.dp)) }
                            }
                        }
                        val visibleCandidates = when {
                            candidatesExpanded -> pending
                            compactPlaybackCoverage && unfinishedExpanded -> pending.filter { it.sourceId in unfinishedSources }
                            else -> emptyList()
                        }
                        if (wide && tools == AnimeSourceTools.CANDIDATES && visibleCandidates.isEmpty()) {
                            item { AnimeMessage("暂时没有待核对的候选", action = "查找片源", icon = Icons.Outlined.Search,
                                onAction = { tools = AnimeSourceTools.SEARCH }) }
                        }
                        if (visibleCandidates.isNotEmpty()) {
                            item(key = "pending-heading") {
                                Text("候选片源", Modifier.padding(start = 8.dp, top = 6.dp),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            items(visibleCandidates, key = { "pending:" + it.referenceKey() }) { title ->
                                val name = animeSourceName(title.sourceId, state)
                                val progress = state.sourceAggregation[title.sourceId]
                                val check = progress?.candidateChecks?.firstOrNull { it.title.referenceKey() == title.referenceKey() }
                                val checkedEpisode = state.detail?.episodes?.firstOrNull { it.id == progress?.episodeId }
                                val retryable = check?.phase == AnimeAssociationPhase.UNAVAILABLE
                                AnimeSourceProviderRow(title.sourceId, name,
                                    listOfNotNull(title.title, title.year?.toString()).joinToString(" · "),
                                    onClickLabel = "核对${name}的目录", onClick = { openPreview(title) },
                                    supporting = {
                                        if (check != null) {
                                            val scope = checkedEpisode?.let(::animeEpisodeLabel)
                                            AnimeQuietLabel(listOfNotNull(scope, check.message).joinToString(" · "))
                                        }
                                    },
                                    trailing = {
                                        if (retryable) MotionIconButton(onClick = {
                                            controller.verifyEpisodeSources(focusEpisode?.id ?: checkedEpisode?.id,
                                                retry = true, sourceId = title.sourceId)
                                        }, modifier = Modifier.size(44.dp), tooltip = "重试${name}的核对") {
                                            Icon(Icons.Outlined.Refresh, "重试来源核对", Modifier.size(18.dp))
                                        } else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    })
                            }
                        }
                        val showDiagnostics = canInspect && aggregationExpanded
                        if (wide && aggregationExpanded && !canInspect) item {
                            AnimeMessage("暂无来源核对记录", icon = Icons.Outlined.Info)
                        }
                        if (showDiagnostics) {
                            state.detailFirstConfirmedLineMs?.let { elapsed ->
                                item(key = "first-confirmed-line") {
                                    AnimeQuietLabel("打开详情 → 首条已确认线路 · $elapsed ms", Modifier.padding(start = 8.dp))
                                }
                            }
                            if (state.sourceAggregationTotal > 0 && !state.sourceAggregationRunning) item(key = "aggregation-summary") {
                                val unfinished = state.sourceAggregation.values.count { it.phase in setOf(
                                    AnimeAssociationPhase.UNAVAILABLE,
                                    AnimeAssociationPhase.PAUSED) || it.phase.checking }
                                AnimeQuietLabel(if (unfinished > 0) "本轮已结束 · $unfinished 个未完成" else "本轮核对已结束",
                                    Modifier.padding(start = 8.dp))
                            }
                            state.sourceError?.let { error -> item(key = "source-error") { AnimeQuietLabel(error) } }
                        }
                        val visibleIssues = when {
                            showDiagnostics -> associationIssues
                            compactPlaybackCoverage && unfinishedExpanded -> associationIssues.filterKeys { it in unfinishedSources }
                            else -> emptyMap()
                        }
                        if (visibleIssues.isNotEmpty()) {
                            items(visibleIssues.entries.toList(), key = { "aggregation-result:" + it.key }) { (sourceId, progress) ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val name = animeSourceName(sourceId, state)
                                    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { AnimeSourceMark(sourceId, name, size = 15.dp) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        AnimeQuietLabel(progress.message ?: animeAssociationPhaseLabel(progress.phase))
                                    }
                                    if (progress.phase == AnimeAssociationPhase.UNAVAILABLE)
                                        MotionIconButton(onClick = {
                                            controller.verifyEpisodeSources(focusEpisode?.id, retry = true, sourceId = sourceId)
                                        }, modifier = Modifier.size(44.dp), tooltip = "重试${name}的核对") {
                                            Icon(Icons.Outlined.Refresh, "重试来源核对", Modifier.size(18.dp))
                                        }
                                }
                            }
                        }
                        if (showDiagnostics) {
                            item(key = "retry-source-aggregation") {
                                MotionTextButton(onClick = controller::retrySourceAggregation,
                                    enabled = !state.sourceAggregationRunning, modifier = Modifier.heightIn(min = 44.dp)) {
                                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新核对")
                                }
                            }
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
                                }.sortedByDescending { title -> subject?.let { autoPlayTitleCandidate(it, title) } == true }
                            }
                            val results = searchRows.filter { (result, rows) -> rows.isNotEmpty() || result.hasMore || result.loadingMore }
                            items(results, key = { "search:" + it.first.providerId }) { (result, rows) ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    val related = rows.filter { title -> subject?.let { autoPlayTitleCandidate(it, title) } == true }
                                    val otherCount = rows.size - related.size
                                    AnimeSectionTitle(result.name,
                                        if (related.isNotEmpty()) "${related.size} 个同名候选"
                                        else "没有可靠的同名候选")
                                    val displayed = if (showOtherSearchResults) rows else related
                                    displayed.forEach { title ->
                                        val detail = listOfNotNull(title.year?.toString(),
                                            title.episodeCount?.let { "$it 集" },
                                            "已排除自动关联".takeIf { title.sourceId in excludedSourceIds }).joinToString(" · ")
                                        AnimeSourceProviderRow(result.providerId, title.title, detail,
                                            onClickLabel = "核对${result.name}的目录", onClick = { openPreview(title) },
                                            trailing = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                                    }
                                    if (otherCount > 0) MotionTextButton(onClick = {
                                        showOtherSearchResults = !showOtherSearchResults
                                    }, modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text(if (showOtherSearchResults) "收起其他结果" else "其他搜索结果 · $otherCount")
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
                            if (wide && searching == 0 && results.isEmpty() && searchIssues.isEmpty()) item {
                                AnimeMessage("没有其他候选片源", "可更换作品原名或别名再查找。", icon = Icons.Outlined.Search)
                            }
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
                    AnimePaneLayout(Modifier.weight(1f, fill = false), minimumWidth = 712.dp, selectionKey = tools to cataloguesExpanded,
                        showSidebar = !overVideo && subjectId != null, sidebar = {
                            AnimeScrollableList(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                item {
                                    AnimePaneChoice(if (focusEpisode == null) "播放来源" else "本集线路",
                                        tools == null && (focusEpisode == null || !cataloguesExpanded),
                                        { tools = null; cataloguesExpanded = false }, Icons.Outlined.PlayArrow,
                                        count = if (focusEpisode == null) bindings.size.toString() else episodeLines.size.toString())
                                }
                                if (focusEpisode != null && (bindings.isNotEmpty() || cataloguesExpanded)) item {
                                    AnimePaneChoice("目录与集数", tools == null && cataloguesExpanded,
                                        { tools = null; cataloguesExpanded = true }, Icons.Outlined.List,
                                        count = bindings.size.toString())
                                }
                                if (pending.isNotEmpty() || tools == AnimeSourceTools.CANDIDATES) item {
                                    AnimePaneChoice("候选片源", tools == AnimeSourceTools.CANDIDATES,
                                        { tools = AnimeSourceTools.CANDIDATES }, Icons.Outlined.Link, count = pending.size.toString())
                                }
                                item {
                                    AnimePaneChoice("查找片源", manageExpanded, { tools = AnimeSourceTools.SEARCH }, Icons.Outlined.Search)
                                }
                                if (canInspect || aggregationExpanded) item {
                                    AnimePaneChoice("核对详情", aggregationExpanded, { tools = AnimeSourceTools.DIAGNOSTICS },
                                        Icons.Outlined.Info, detail = if (state.sourceAggregationRunning)
                                            "${state.sourceAggregationCompleted} / ${state.sourceAggregationTotal}" else null)
                                }
                            }
                        }) { wide ->
                        val listState = if (wide) desktopScrolls.getOrPut(tools to (cataloguesExpanded && tools == null)) {
                            LazyListState()
                        } else sourceScroll
                        AnimeScrollableList(Modifier.weight(1f, fill = false), state = listState,
                            contentPadding = PaddingValues(start = if (wide) 3.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            entries(wide)
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

@Composable
private fun AnimeLinePanel(visible: Boolean, overVideo: Boolean, backdrop: HazeState,
    onDismiss: () -> Unit, onClosed: () -> Unit, content: @Composable () -> Unit) {
    if (overVideo) AnimePlaybackPanel(visible, true, null, onDismiss, onClosed, content)
    else MotionDialog(visible, onDismiss, maximumWidth = animePanelWidth(916.dp, 720.dp), properties = animePanelDialogProperties, onClosed = onClosed) {
        AnimePanelSurface(backdrop) { content() }
    }
}

@Composable
private fun AnimeLineChoice(
    title: String,
    detail: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    sourceId: String? = null,
    busy: Boolean = false,
    actionLabel: String? = null,
    supporting: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val ink = if (active) colors.primary else colors.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape)
            .background(if (active) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .045f))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .985f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sourceId != null) Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            AnimeSourceMark(sourceId, title, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, modifier = if (sourceId == null) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                else Modifier, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.invoke()
        }
        if (busy) MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        else Icon(icon, null, Modifier.size(22.dp), tint = ink)
    }
}

@Composable
private fun AnimeBoundSourceRow(sourceId: String, title: String, detail: String?, signal: AnimeBoundSourceMark?,
    expanded: Boolean, onExpand: () -> Unit, onPlay: (() -> Unit)?, metrics: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(sourceId) { MutableInteractionSource() }
    val window = LocalMangaWindow.current
    val identity: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                signal?.let { AnimeSourceSignal(it) }
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inlineMetrics = maxWidth >= 455.dp * window.fontScale
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = if (expanded) "收起${title}详情" else "展开${title}详情", onClick = onExpand)
                .motionPress(interaction, pressedScale = .99f)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) { AnimeSourceMark(sourceId, title, size = 18.dp) }
                val content = Modifier.weight(1f).padding(start = 11.dp, top = 6.dp, bottom = 6.dp)
                if (inlineMetrics) Row(content, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { identity() }
                    metrics()
                } else Column(content, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    identity()
                    metrics()
                }
                Box(Modifier.width(if (onPlay == null) 44.dp else 24.dp), contentAlignment = Alignment.Center) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                        Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
            if (onPlay != null) MotionIconButton(onClick = onPlay, modifier = Modifier.size(44.dp), tooltip = "用${title}播放本集") {
                Icon(Icons.Outlined.PlayArrow, "用${title}播放本集", Modifier.size(20.dp),
                    tint = if (signal == AnimeBoundSourceMark.AUTOMATIC || signal == AnimeBoundSourceMark.CURRENT)
                        colors.primary else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AnimeSourceSignal(signal: AnimeBoundSourceMark) {
    val palette = mangaStatusColors()
    val primary = MaterialTheme.colorScheme.primary
    when (signal) {
        AnimeBoundSourceMark.CURRENT -> Icon(Icons.Outlined.Check, "当前线路", Modifier.size(14.dp), tint = primary)
        AnimeBoundSourceMark.AUTOMATIC -> Icon(Icons.Outlined.AutoAwesome, "自动首选", Modifier.size(14.dp), tint = primary)
        AnimeBoundSourceMark.MANUAL -> Icon(Icons.Outlined.Edit, "手动指定", Modifier.size(13.dp), tint = primary)
        AnimeBoundSourceMark.ISSUE -> Icon(Icons.Outlined.ErrorOutline, "来源或线路暂不可用", Modifier.size(14.dp), tint = palette.waiting)
        AnimeBoundSourceMark.LOADING -> MotionBusyIndicator(Modifier.size(12.dp).semantics { contentDescription = "读取目录中" },
            color = palette.activity, strokeWidth = 1.5.dp)
    }
}

@Composable
private fun AnimeSourceTool(icon: ImageVector, label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = if (selected) colors.primary else colors.onSurfaceVariant
    MotionIconButton(onClick = onClick, modifier = Modifier.size(44.dp).semantics {
        stateDescription = if (selected) "已展开" else "已收起"
    }, tooltip = label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, label, Modifier.size(if (count == null) 18.dp else 16.dp), tint = ink)
            if (count != null) Text(count.toString(), Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun AnimeSourceProviderRow(sourceId: String, title: String, detail: String, onClickLabel: String,
    onClick: () -> Unit, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null,
    showMark: Boolean = true, supporting: (@Composable () -> Unit)? = null, signal: AnimeBoundSourceMark? = null) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .98f)
            .padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (showMark) Box(Modifier.size(34.dp).background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                AnimeSourceMark(sourceId, title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = if (enabled) 1f else .55f))
                    signal?.let { AnimeSourceSignal(it) }
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.invoke()
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
    AnimeAssociationPhase.PAUSED -> "核对已暂停"
    AnimeAssociationPhase.LINKED -> "作品已关联"
    AnimeAssociationPhase.NO_MATCH -> "未找到可靠候选"
    AnimeAssociationPhase.NEEDS_SELECTION -> "待确认作品或版本"
    AnimeAssociationPhase.EPISODE_UNMATCHED -> "本集尚未对应"
    AnimeAssociationPhase.UNAVAILABLE -> "搜索或目录读取未完成"
    AnimeAssociationPhase.EXCLUDED -> "已排除自动关联"
}

internal fun animeSourceEpisodeSummary(episode: AnimeSourceEpisode): String =
    listOfNotNull(episode.group.takeIf { it.isNotBlank() }, episode.edition.takeIf { it.isNotBlank() },
        episode.number?.let { "编号 " + animeEpisodeNumber(it) } ?: "未标集号",
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
