package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeNativeMetadataSearch
import dev.mangaflow.anime.AnimeScreenState
import dev.mangaflow.anime.AnimeScoreCheck
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.AnimeWatchStatus
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.detailMetricScore
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.mangaNumberText
import dev.mangaflow.ui.motion.*
import androidx.compose.ui.unit.sp

@Composable
internal fun AnimeDetailPane(
    detail: AnimeDetail,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit,
    onOpenRelated: (AnimeSubject) -> Unit,
    onSources: (AnimeEpisode?) -> Unit,
    onTracking: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val subject = detail.subject
    val nativeSource = subject.externalIds["native-source"]
    val native = nativeSource != null
    val douban = subject.canonicalDoubanId() != null
    val supportsMetadataLink = native || douban
    val currentDiscussion by controller.discussion.state.collectAsStateWithLifecycle()
    val discussion = currentDiscussion.takeIf { it.subjectId == subject.id }
    var detailTab by rememberSaveable(subject.id, "detail-sections") { mutableIntStateOf(0) }
    var discussionFilter by remember(subject.id) { mutableStateOf(false) }
    var discussionChecksPresent by remember(subject.id) { mutableStateOf(false) }
    var discussionOpenRevision by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    fun selectDetailTab(index: Int) {
        if (index == 2) discussionOpenRevision++
        discussionFilter = false
        detailTab = index
    }
    val commentsVisible = active && detailTab == 2
    DisposableEffect(controller, subject.id, commentsVisible) {
        if (commentsVisible) controller.discussion.setVisible(subject.id, true)
        onDispose { if (commentsVisible) controller.discussion.setVisible(subject.id, false) }
    }
    val detailTabs = listOf("剧集", "简介", "评论")
    val bindings = remember(state.sourceBindings, subject.id) {
        state.sourceBindings.filter { it.subjectId == subject.id }
    }
    val pendingNativeCatalog = state.sourceCatalogs.firstOrNull { catalog ->
        catalog.title.sourceId == nativeSource &&
            !catalog.notice.isNullOrBlank() && detail.episodes.isEmpty()
    }
    val missingSelectedGroup = pendingNativeCatalog?.let { catalog ->
        val selectedGroup = bindings.firstOrNull { binding ->
            binding.catalog.sourceId == catalog.title.sourceId && binding.catalog.id == catalog.title.id &&
                binding.catalog.url == catalog.title.url
        }?.selectedGroup
        selectedGroup != null && catalog.episodes.none { it.group == selectedGroup }
    } == true
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    val linkedMetadata = (detail.metadataSubject ?: entry?.nativeMetadata.takeIf { supportsMetadataLink })
        ?.takeIf { it.canonicalBangumiId() != null }
    val metadataSearch = state.nativeMetadataSearch?.takeIf { it.subjectId == subject.id }
    var metadataQuery by rememberSaveable(subject.id, "metadata-query") { mutableStateOf(subject.title) }
    var reviewedMetadata by rememberSaveable(subject.id, "metadata-candidate") { mutableStateOf<String?>(null) }
    var unlinkMetadata by rememberSaveable(subject.id, "metadata-unlink") { mutableStateOf(false) }
    val completed = entry?.completedEpisodeIds.orEmpty()
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var episodeReversed by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var episodeSearch by rememberSaveable(subject.id) { mutableStateOf(false) }
    var statusPresent by remember(subject.id) { mutableStateOf(false) }
    var ratingPresent by remember(subject.id) { mutableStateOf(false) }
    var episodeMenuOwner by remember(subject.id) { mutableStateOf<String?>(null) }

    // Native catalogue order and IDs are authoritative for the selected line.
    val (main, extra) = remember(detail.episodes, native) {
        if (native) detail.episodes to emptyList() else detail.episodes.partition { it.kind == AnimeEpisodeKind.MAIN }
    }
    val selectedTab = if (main.isEmpty() && extra.isNotEmpty()) 1 else if (extra.isEmpty()) 0 else episodeTab
    val selectedEpisodes = if (selectedTab == 0) main else extra
    val search = episodeQuery.trim()
    val episodes = remember(selectedEpisodes, episodeReversed, search) {
        val ordered = if (episodeReversed) selectedEpisodes.asReversed() else selectedEpisodes
        if (search.isEmpty()) ordered else ordered.filter { episode ->
            animeEpisodeLabel(episode).contains(search, ignoreCase = true) ||
                episode.title.contains(search, ignoreCase = true)
        }
    }
    val savedProgress = entry?.resume?.takeUnless { it.completed }
    val resume = detail.episodes.firstOrNull { it.id == savedProgress?.episodeId }
    val first = resume ?: main.firstOrNull { it.id !in completed }
        ?: main.firstOrNull() ?: extra.firstOrNull { it.id !in completed } ?: extra.firstOrNull()
    val firstAvailability = first?.let { controller.episodeAvailability(it.id, state) }
    val awaitingSources = state.associationChoices.filterValues { it.isNotEmpty() }.keys
    val linkedSourceIds = bindings.sortedBy { it.catalog.sourceId != state.preferredSourceId }
        .map { it.catalog.sourceId }.distinct()
    val sourceNames = linkedSourceIds
        .map { it to animeSourceName(it, state) }
    val pendingSourceCount = (state.groupSourceCandidates.map { it.sourceId } + awaitingSources)
        .distinct().count { it !in linkedSourceIds }
    val linkedSourceCount = linkedSourceIds.size
    val sourceSummary = when {
        linkedSourceCount > 0 -> "$linkedSourceCount 个已关联片源" +
            if (pendingSourceCount > 0) " · $pendingSourceCount 个待确认" else ""
        pendingSourceCount > 0 -> "$pendingSourceCount 个来源待确认"
        else -> null
    }
    val incompleteSources = (state.sourceCatalogs.filter {
        !it.notice.isNullOrBlank() && it != pendingNativeCatalog
    }.map { it.title.sourceId } + state.sourceCatalogErrors.keys).distinct()
    val canonicalInfo = subject.takeIf { it.canonicalBangumiId() != null } ?: linkedMetadata
    val detailScores = remember(state.detailScores, state.detailScoreChecks, subject, canonicalInfo) {
        buildList {
            addAll(state.detailScores.filter { it.scale > 0 && it.rating.isFinite() &&
                it.rating > 0 && it.rating <= it.scale.toDouble() })
            val fallback = listOfNotNull(
                subject.takeIf { douban }?.let { AnimeDiscoveryProvider.DOUBAN to it },
                canonicalInfo?.let { AnimeDiscoveryProvider.BANGUMI to it },
            )
            fallback.forEach { (provider, info) ->
                val check = state.detailScoreChecks[provider]
                info.rating?.takeIf { it.isFinite() && it > 0 && it <= 10.0 &&
                    (check == null || check.loading || check.error != null) }?.let {
                    add(AnimeSourceScore(provider, it, 10, info.ratingCount))
                }
            }
        }.distinctBy { it.provider }
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val desktop = platformKind == PlatformKind.DESKTOP
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val horizontal = if (maxWidth >= 720.dp) 28.dp else 16.dp
        val contentWidth = minOf(maxWidth, 1080.dp)
        if (detailTab == 2) {
            val commentSources = discussion?.sources.orEmpty()
            Column(Modifier.widthIn(max = 1080.dp).fillMaxSize().padding(top = 10.dp)) {
                Text(subject.title, Modifier.fillMaxWidth().padding(horizontal = horizontal)
                    .padding(bottom = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal),
                    verticalAlignment = Alignment.CenterVertically) {
                    AnimeChoiceStrip(detailTabs, detailTab, onSelect = ::selectDetailTab,
                        active = active && !discussionChecksPresent,
                        modifier = Modifier.weight(1f).widthIn(max = 400.dp))
                    MotionIconButton(onClick = {
                        commentSources.forEach { controller.discussion.retry(subject.id, it.id) }
                        if (discussion?.matching != true) controller.discussion.retryMatch(subject.id)
                    }, enabled = active && !discussionChecksPresent && commentSources.none { it.loadingMore } &&
                        (canonicalInfo != null), modifier = Modifier.size(44.dp), tooltip = "刷新评论") {
                        Icon(Icons.Outlined.Refresh, "刷新评论", Modifier.size(19.dp))
                    }
                    MotionIconButton(onClick = { discussionFilter = !discussionFilter },
                        enabled = active && !discussionChecksPresent,
                        modifier = Modifier.size(44.dp), tooltip = "筛选评论") {
                        Icon(Icons.Outlined.Tune, "筛选评论", Modifier.size(19.dp))
                    }
                }
                AnimeDiscussionSourcesStrip(subject.id, discussion?.checks.orEmpty(), backdrop = backdrop,
                    enabled = active, canRetry = canonicalInfo != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontal),
                    onRetry = { controller.discussion.retry(subject.id, it) },
                    onPresence = { discussionChecksPresent = it })
                if (supportsMetadataLink && canonicalInfo == null) {
                    Box(Modifier.padding(horizontal = horizontal, vertical = 8.dp)) {
                        AnimeDetailStatus("补充评论资料", Icons.Outlined.Info,
                            detail = "确认对应作品和季度后，可读取 Bangumi 等来源的评论。",
                            actionLabel = "补充资料", onAction = { selectDetailTab(1) })
                    }
                } else {
                    CommunityPanel(mangaId = "anime:${subject.id}", sources = commentSources,
                        loading = discussion == null || discussion.matching,
                        onMore = { controller.discussion.loadMore(subject.id, it) },
                        onRetry = { controller.discussion.retry(subject.id, it) },
                        onMatch = { _, _ -> }, onRelink = {}, loadReplies = { _, _, _ -> ReviewPage(emptyList()) },
                        modifier = Modifier.weight(1f).motionInputGate(active && !discussionChecksPresent),
                        backdrop = backdrop, active = active,
                        openRevision = discussionOpenRevision,
                        filterOpen = discussionFilter, onFilterDismiss = { discussionFilter = false })
                }
            }
            return@BoxWithConstraints
        }
        val columns = ((contentWidth - horizontal * 2).value / ((if (desktop) 100f else 78f) * fontScale))
            .toInt().coerceIn(1, 8)
        val episodeRows = remember(episodes, columns) { episodes.chunked(columns) }
        val wideHero = contentWidth >= 620.dp && fontScale <= 1.3f
        val stackHeroActions = contentWidth < 340.dp || fontScale > 1.3f
        val motion = LocalMotionEnabled.current
        LazyColumn(
            Modifier.widthIn(max = 1080.dp).fillMaxSize()
                .motionInputGate(active && !statusPresent && !ratingPresent && episodeMenuOwner == null),
            state = scroll,
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "identity") {
                AnimeDetailHero(
                    subject = subject,
                    metadata = canonicalInfo,
                    scores = detailScores,
                    scoresLoading = state.detailScoreLoading,
                    scoresError = state.detailScoreError,
                    scoresMessage = state.detailScoreMessage,
                    scoreChecks = state.detailScoreChecks,
                    onRefreshScores = { controller.refreshDetailScores() },
                    onRefreshScore = { controller.refreshDetailScores(it) },
                    episode = first,
                    availability = firstAvailability,
                    progress = savedProgress.takeIf { resume != null },
                    entry = entry,
                    libraryAvailable = !state.libraryReadUnavailable,
                    active = active,
                    wide = wideHero,
                    stackActions = stackHeroActions,
                    backdrop = backdrop,
                    native = native,
                    onPlay = onPlay,
                    onSources = { onSources(it) },
                    onFollow = { controller.setFollowed(subject, entry?.followed != true) },
                    onStatus = { controller.setWatchStatus(subject.id, it) },
                    onTracking = onTracking,
                    onStatusPresence = { statusPresent = it },
                    onRatingPresence = { ratingPresent = it },
                )
            }
            if (state.libraryReadUnavailable) item(key = "library-read-warning") {
                AnimeDetailStatus(
                    "临时浏览 · 不保存进度", Icons.Outlined.Info,
                    detail = "本地追番记录未能读取，原收藏与观看记录已保留。",
                )
            }
            item(key = "detail-tabs") {
                AnimeChoiceStrip(detailTabs, detailTab, onSelect = ::selectDetailTab,
                    active = active, modifier = Modifier.widthIn(max = 400.dp).padding(vertical = 4.dp))
            }
            if (detailTab == 1) {
                item(key = "description") {
                    val info = subject.copy(
                        description = subject.description.ifBlank { canonicalInfo?.description.orEmpty() },
                        tags = subject.tags.ifEmpty { canonicalInfo?.tags.orEmpty() },
                    )
                    if (info.description.isNotBlank() || info.tags.isNotEmpty()) AnimeDetailDescription(info)
                    else AnimeMessage("暂无简介", subject.originalTitle.takeIf { it != subject.title && it.isNotBlank() })
                }
                if (supportsMetadataLink && !state.libraryReadUnavailable) {
                    nativeMetadataPicker(linkedMetadata, metadataSearch, active, metadataQuery, reviewedMetadata,
                        unlinkMetadata,
                        onQueryChange = { metadataQuery = it },
                        onSearch = {
                            reviewedMetadata = null
                            unlinkMetadata = false
                            controller.searchNativeMetadata(subject.id, metadataQuery)
                        },
                        onReview = { reviewedMetadata = it },
                        onRequestUnlink = { unlinkMetadata = it },
                        onSave = { controller.setNativeMetadata(subject.id, it) },
                        onClose = {
                            unlinkMetadata = false
                            reviewedMetadata = null
                            controller.dismissNativeMetadataSearch(subject.id)
                        })
                }
                if (state.relatedSubjects.isNotEmpty()) {
                    item(key = "related-heading") { AnimeSectionTitle("相关番剧", modifier = Modifier.semantics { heading() }) }
                    items(state.relatedSubjects, key = { "related:" + it.id }) { related ->
                        AnimeSubjectRow(related, animeSubjectSummary(related), onOpen = { onOpenRelated(related) })
                    }
                }
                return@LazyColumn
            }
            item(key = "episodes-heading") {
                Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    AnimeSectionTitle(
                        "选集",
                        listOfNotNull(when {
                            search.isNotEmpty() -> "${episodes.size} / ${selectedEpisodes.size} 集"
                            selectedEpisodes.isNotEmpty() -> "${selectedEpisodes.size} 集"
                            else -> null
                        }, sourceSummary, "继续找源中".takeIf { state.sourceAggregationRunning })
                            .joinToString(" · ").takeIf { it.isNotBlank() },
                        modifier = Modifier.semantics { heading() },
                        action = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (episodeSearch || detail.episodes.size > 1) {
                                    MotionIconButton(
                                        onClick = {
                                            episodeSearch = !episodeSearch
                                            if (!episodeSearch) episodeQuery = ""
                                        },
                                        modifier = Modifier.size(44.dp),
                                        tooltip = if (episodeSearch) "关闭选集搜索" else "查找集数",
                                    ) {
                                        Icon(
                                            if (episodeSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                                            if (episodeSearch) "关闭选集搜索" else "查找集数", Modifier.size(18.dp),
                                        )
                                    }
                                    val sortLabel = if (episodeReversed) "改为正序" else "改为倒序"
                                    MotionIconButton(
                                        onClick = { episodeReversed = !episodeReversed },
                                        modifier = Modifier.size(44.dp), tooltip = sortLabel,
                                    ) {
                                        Icon(Icons.Outlined.SwapVert, sortLabel, Modifier.size(18.dp))
                                    }
                                }
                                if (sourceNames.isNotEmpty()) {
                                    AnimeSourceBadge(sourceNames, enabled = active, compact = true,
                                        modifier = Modifier.heightIn(min = 44.dp).widthIn(max = 124.dp).semantics {
                                            stateDescription = "$linkedSourceCount 个已关联片源" +
                                                if (pendingSourceCount > 0) "，$pendingSourceCount 个待确认" else ""
                                        },
                                        onClick = { onSources(null) })
                                } else {
                                    MotionIconButton(onClick = { onSources(null) }, enabled = active,
                                        modifier = Modifier.size(44.dp), tooltip = "查看播放来源") {
                                        Icon(Icons.Outlined.Link, "查看播放来源", Modifier.size(18.dp))
                                    }
                                }
                            }
                        },
                    )
                    AnimatedVisibility(
                        visible = episodeSearch,
                        enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                            fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                        exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                            fadeOut(tween(if (motion) MangaMotion.Quick else 0)),
                    ) {
                        AnimeTextField(
                            episodeQuery, { episodeQuery = it }, "集号或标题",
                            Modifier.motionInputGate(episodeSearch).padding(bottom = 6.dp),
                        )
                    }
                    if (main.isNotEmpty() && extra.isNotEmpty()) {
                        MotionTabs(
                            selectedTab, listOf("正片", "特别篇等"),
                            onSelect = { episodeTab = it },
                            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                            controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                        )
                    }
                }
            }
            if (episodes.isEmpty() || state.detailLoading || state.detailError != null) item(key = "episode-status") {
                when {
                    state.detailLoading -> AnimeDetailStatus(
                        when {
                            detail.episodes.isEmpty() -> "正在加载集数"
                            state.detailFromCache -> "显示上次集数 · 正在更新"
                            else -> "正在更新集数"
                        },
                        Icons.Outlined.Info, loading = true,
                    )
                    state.detailError != null -> AnimeDetailStatus(
                        if (subject.canonicalDoubanId() != null && detail.episodes.isNotEmpty()) "资料补充未完成"
                        else if (detail.episodes.isEmpty()) "集数暂不可用" else "集数更新未完成",
                        Icons.Outlined.CloudOff, detail = state.detailError,
                        actionLabel = "重试加载集数", actionIcon = Icons.Outlined.Refresh,
                        onAction = controller::retryCurrentDetail,
                    )
                    pendingNativeCatalog != null -> AnimeDetailStatus(
                        if (missingSelectedGroup) "原线路暂不可用" else "原线路尚未加载完整",
                        Icons.Outlined.Info, detail = pendingNativeCatalog.notice,
                        actionLabel = if (missingSelectedGroup) null else "继续加载原线路",
                        onAction = { controller.refreshSource(pendingNativeCatalog.title.sourceId) },
                    )
                    search.isNotEmpty() -> AnimeDetailStatus(
                        "没有匹配的集数", Icons.Outlined.Search,
                        actionLabel = "清除集数搜索", actionIcon = Icons.Outlined.Close, onAction = { episodeQuery = "" },
                    )
                    else -> AnimeDetailStatus("暂无集数", Icons.Outlined.Info)
                }
            }
            if (savedProgress != null && resume == null && !state.detailLoading) item(key = "missing-resume") {
                AnimeDetailStatus("上次位置 ${animeTime(savedProgress.positionMs)} 已保留", Icons.Outlined.History,
                    detail = "当前线路暂未找到上次观看的剧集。", actionLabel = "查看作品线路",
                    actionIcon = Icons.Outlined.Link, onAction = { onSources(null) })
            }
            items(episodeRows, key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { episode ->
                        key(episode.id) {
                            val lines = controller.confirmedPlaybackLines(episode.id, state)
                            AnimeEpisodeCell(
                                episode = episode,
                                completed = episode.id in completed,
                                resume = episode.id == resume?.id,
                                availability = controller.episodeAvailability(episode.id, state),
                                sources = animeEpisodeSources(episode.id, lines, controller, state),
                                backdrop = backdrop,
                                active = active,
                                compact = desktop,
                                libraryAvailable = !state.libraryReadUnavailable,
                                onMenuPresenceChange = { present ->
                                    if (present) episodeMenuOwner = episode.id
                                    else if (episodeMenuOwner == episode.id) episodeMenuOwner = null
                                },
                                onPlay = { onPlay(episode) },
                                onSources = { onSources(episode) },
                                onCompleted = { controller.setEpisodeCompleted(episode.id, episode.id !in completed) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (state.sourceError != null || incompleteSources.isNotEmpty()) item(key = "source-status") {
                AnimeDetailStatus(
                    if (incompleteSources.isNotEmpty()) "${incompleteSources.size} 个来源目录待更新" else "部分线路暂不可用",
                    Icons.Outlined.Info, actionLabel = "查看来源详情", actionIcon = Icons.Outlined.Link,
                    onAction = { onSources(null) },
                )
            }
        }
    }
}

private fun LazyListScope.nativeMetadataPicker(
    linked: AnimeSubject?,
    search: AnimeNativeMetadataSearch?,
    active: Boolean,
    query: String,
    reviewedId: String?,
    unlink: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onReview: (String) -> Unit,
    onRequestUnlink: (Boolean) -> Unit,
    onSave: (String?) -> Unit,
    onClose: () -> Unit,
) {
    item(key = "native-metadata") {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimeSectionTitle("补充资料", linked?.let { listOfNotNull(it.title, it.year?.toString()).joinToString(" · ") }) {
                if (search == null) MotionTextButton(onClick = onSearch,
                    enabled = active && query.isNotBlank(), modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (linked == null) "关联 Bangumi" else "更换关联")
                } else {
                    MotionIconButton(onClick = onClose, enabled = active && !search.saving,
                        modifier = Modifier.size(44.dp), tooltip = "关闭资料选择") {
                        Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
                    }
                }
            }
            if (search != null) {
                val editable = active && !search.saving
                if (unlink && linked != null) {
                    Text("解除 ${linked.title} 的资料与评论关联？", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = { onRequestUnlink(false) }, enabled = editable) { Text("保留关联") }
                        MotionButton(onClick = { onSave(null) }, enabled = editable) { Text("解除关联") }
                    }
                } else {
                    AnimeTextField(query, onQueryChange, "全名或原名", enabled = editable,
                        onSubmit = { if (editable && query.isNotBlank()) onSearch() })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = onSearch, enabled = editable && query.isNotBlank()) { Text("搜索 Bangumi") }
                        if (linked != null) MotionTextButton(onClick = { onRequestUnlink(true) }, enabled = editable) {
                            Text("解除关联")
                        }
                    }
                }
                when {
                    search.saving -> AnimeDetailStatus("正在保存资料关联", Icons.Outlined.Info, loading = true)
                    search.loading -> AnimeDetailStatus("正在查找 ${search.query}", Icons.Outlined.Search, loading = true)
                    search.error != null -> AnimeDetailStatus("资料操作未完成", Icons.Outlined.Info, detail = search.error)
                    !unlink && search.candidates.isEmpty() -> AnimeQuietLabel("未找到 ${search.query} 的资料，可用原名或加上季度再搜索。")
                    !unlink -> AnimeQuietLabel("选择相同作品与季度，补充剧集与评论资料。")
                }
            }
        }
    }
    if (search != null && !unlink) items(search.candidates, key = { "metadata:${it.id}" }) { candidate ->
        val reviewed = candidate.id == reviewedId
        val enabled = active && !search.saving
        Column(Modifier.motionInputGate(enabled)) {
            AnimeSubjectRow(candidate, subtitle = listOfNotNull(
                candidate.year?.toString(), candidate.episodeCount?.let { "$it 集" },
                "#${candidate.id.substringAfter(':')}",
                candidate.originalTitle.takeIf { it.isNotBlank() && it != candidate.title },
            ).joinToString(" · "), onOpen = { if (enabled) onReview(candidate.id) }, trailing = {
                if (reviewed || candidate.id == linked?.id) Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp))
            })
            if (reviewed && candidate.id != linked?.id) {
                Column(Modifier.padding(start = 68.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyMedium)
                    MotionButton(onClick = { onSave(candidate.id) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 44.dp)) { Text("确认关联") }
                }
            }
        }
    }
}

@Composable
private fun AnimeDetailHero(
    subject: AnimeSubject,
    metadata: AnimeSubject?,
    scores: List<AnimeSourceScore>,
    scoresLoading: Boolean,
    scoresError: String?,
    scoresMessage: String?,
    scoreChecks: Map<AnimeDiscoveryProvider, AnimeScoreCheck>,
    onRefreshScores: () -> Unit,
    onRefreshScore: (AnimeDiscoveryProvider) -> Unit,
    episode: AnimeEpisode?,
    availability: AnimeEpisodeAvailability?,
    progress: AnimeWatchProgress?,
    entry: AnimeLibraryEntry?,
    libraryAvailable: Boolean,
    active: Boolean,
    wide: Boolean,
    stackActions: Boolean,
    backdrop: HazeState,
    native: Boolean,
    onPlay: (AnimeEpisode) -> Unit,
    onSources: (AnimeEpisode) -> Unit,
    onFollow: () -> Unit,
    onStatus: (AnimeWatchStatus) -> Unit,
    onTracking: () -> Unit,
    onStatusPresence: (Boolean) -> Unit,
    onRatingPresence: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val facts = listOfNotNull(
        (subject.year ?: metadata?.year)?.toString(),
        (subject.episodeCount ?: metadata?.episodeCount)?.takeIf { it > 0 }?.let { "$it 集" },
    ).joinToString(" · ")
    val alternateTitle = listOfNotNull(subject.originalTitle, metadata?.originalTitle, metadata?.title)
        .firstOrNull { it.isNotBlank() && it != subject.title }
    val artwork = metadata?.takeIf { subject.coverUrl.isBlank() && it.coverUrl.isNotBlank() } ?: subject
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val stacked = maxWidth < 300.dp || density.fontScale > 1.35f
        val posterWidth = minOf(maxWidth * if (stacked) .54f else .42f, 224.dp)
        val imageWidth = with(density) { (posterWidth.roundToPx() * 2).coerceIn(1, 1200) }
        val request = remember(context, artwork.coverUrl, artwork.externalIds, imageWidth, motion) {
            ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(artwork.coverUrl, animePosterSourceId(artwork))
                .size(imageWidth, imageWidth * 3 / 2)
                .crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit)
        val surface = colors.surface
        // The same bounded cover decode supplies the sharp poster and the detail's artwork field.
        if (artwork.coverUrl.isNotBlank()) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to surface.copy(alpha = .52f), .54f to surface.copy(alpha = .80f), .89f to surface, 1f to surface,
            )))
            if (!stacked) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to Color.Transparent, .28f to Color.Transparent, .64f to surface.copy(alpha = .86f), 1f to surface,
            )))
        }
        val artworkPoster: @Composable () -> Unit = {
            LiquidGlassSurface(backdrop, Modifier.width(posterWidth), cornerRadius = 12.dp,
                role = GlassSurfaceRole.ARTWORK, elevation = 0.dp, softCard = false) {
                Box(Modifier.fillMaxWidth().padding(2.dp).aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Tv, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant.copy(alpha = .26f))
                    Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                }
            }
        }
        val artworkInformation: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(if (wide) 7.dp else 5.dp)) {
                val titleStyle = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                Text(subject.title, Modifier.semantics { heading() },
                    style = titleStyle.copy(fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontWeight = FontWeight.SemiBold))
                if (alternateTitle != null) {
                    Text(alternateTitle, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (facts.isNotBlank()) {
                    Text(facts, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (metadata != null || subject.canonicalDoubanId() != null || scores.isNotEmpty()) AnimeDetailRatingStrip(
                    subject.id, scores, scoresLoading, scoresError, scoresMessage, scoreChecks,
                    backdrop, active, onRefreshScores, onRefreshScore, onRatingPresence,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stacked) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { artworkPoster() }
                artworkInformation(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    artworkPoster()
                    artworkInformation(Modifier.weight(1f))
                }
            }
            AnimeDetailActions(
                episode, availability, progress, entry, libraryAvailable, active, stackActions, backdrop, native,
                onPlay, onSources, onFollow, onStatus, onTracking, onStatusPresence,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDetailRatingStrip(
    ownerId: String, scores: List<AnimeSourceScore>, loading: Boolean, error: String?, message: String?,
    checks: Map<AnimeDiscoveryProvider, AnimeScoreCheck>, backdrop: HazeState, active: Boolean,
    onRefresh: () -> Unit, onRefreshSource: (AnimeDiscoveryProvider) -> Unit, onPresence: (Boolean) -> Unit,
) {
    var open by remember(ownerId) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) open = false }
    fun AnimeSourceScore.displayScore() = "${detailMetricScore(rating)} / $scale"
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        MotionTextButton(onClick = { open = true }, enabled = active,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = scores.joinToString("，") {
                    "${animeDiscoveryProviderLabel(it.provider)} ${it.displayScore()}" +
                        (it.ratingCount?.let { count -> "，$count 人评分" } ?: "")
                }.ifBlank { "评分来源" } + "，查看各站评分与人数"
            }, contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scores.forEach { score ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReviewSourceMark(score.provider.name.lowercase(), size = 14.dp)
                        Text(score.displayScore(), style = mangaMetricValueStyle(13.sp),
                            maxLines = 1, softWrap = false)
                    }
                }
                if (scores.isEmpty()) Text(if (loading) "正在读取评分" else "评分",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            if (loading) MotionBusyIndicator(Modifier.size(14.dp))
            else Icon(if (error == null) Icons.Outlined.ChevronRight else Icons.Outlined.Info,
                null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        CompactGlassPopover(open, backdrop, onDismiss = { open = false }, onPresenceChange = onPresence,
            alignToStart = true, maximumWidth = 340.dp) {
            AnimeSectionTitle("各站评分", action = {
                MotionIconButton(onClick = onRefresh, enabled = active && !loading,
                    modifier = Modifier.size(44.dp), tooltip = "刷新评分") {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                }
            })
            (scores.map { it.provider } + checks.keys).distinct().sortedBy { it.ordinal }.forEach { provider ->
                val score = scores.firstOrNull { it.provider == provider }
                val check = checks[provider]
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReviewSourceMark(provider.name.lowercase(), size = 20.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(animeDiscoveryProviderLabel(provider), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (score != null) Text(score.ratingCount?.let { "$it 人评分" } ?: "人数未提供",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        if (score != null) Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "${score.displayScore()}，" +
                                    (score.ratingCount?.let { "$it 人评分" } ?: "评分人数未提供")
                            }) {
                            Text(detailMetricScore(score.rating), Modifier.alignByBaseline(),
                                style = mangaMetricValueStyle(20.sp, 26.sp), maxLines = 1, softWrap = false)
                            Text("/ ${score.scale}", Modifier.alignByBaseline(),
                                style = mangaMetricValueStyle(12.sp, 16.sp), color = colors.onSurfaceVariant,
                                maxLines = 1, softWrap = false)
                        }
                        if (check?.loading == true) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                MotionBusyIndicator(Modifier.size(16.dp))
                            }
                        } else MotionIconButton(onClick = { onRefreshSource(provider) }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "刷新 ${animeDiscoveryProviderLabel(provider)} 评分") {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        }
                    }
                    val status = when {
                        check?.loading == true -> "正在更新"
                        check?.error != null -> check.error
                        check?.message != null -> check.message
                        score == null -> "暂无评分"
                        else -> null
                    }
                    if (status != null) AnimeQuietLabel(status, Modifier.padding(start = 30.dp, bottom = 4.dp))
                }
            }
            if (checks.isEmpty()) when {
                loading -> AnimeDetailStatus("正在更新评分", Icons.Outlined.Info, loading = true)
                error != null -> AnimeDetailStatus("部分评分读取未完成", Icons.Outlined.Info,
                    detail = error, actionLabel = "重试", onAction = onRefresh)
                message != null -> AnimeQuietLabel(message, Modifier.padding(vertical = 6.dp))
                scores.isEmpty() -> AnimeQuietLabel("暂未取得可显示的评分", Modifier.padding(vertical = 6.dp))
            }
            AnimeQuietLabel("各站保留原始分制，不合并总分；人数仅在来源提供时显示。",
                Modifier.padding(top = 8.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun AnimeDetailActions(
    episode: AnimeEpisode?,
    availability: AnimeEpisodeAvailability?,
    progress: AnimeWatchProgress?,
    entry: AnimeLibraryEntry?,
    libraryAvailable: Boolean,
    active: Boolean,
    stackActions: Boolean,
    backdrop: HazeState,
    native: Boolean,
    onPlay: (AnimeEpisode) -> Unit,
    onSources: (AnimeEpisode) -> Unit,
    onFollow: () -> Unit,
    onStatus: (AnimeWatchStatus) -> Unit,
    onTracking: () -> Unit,
    onStatusPresence: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val episodeAction: @Composable (Modifier) -> Unit = { modifier ->
        if (episode != null && availability != null) {
            val play = availability != AnimeEpisodeAvailability.UPCOMING
            val action = when (availability) {
                AnimeEpisodeAvailability.MATCHED, AnimeEpisodeAvailability.CHECKING, AnimeEpisodeAvailability.UNMATCHED ->
                    if (progress != null) "继续观看" else "播放"
                AnimeEpisodeAvailability.RECENTLY_FAILED -> "重试播放"
                AnimeEpisodeAvailability.UPCOMING -> upcomingEpisodeLabel(episode, fullDate = true)
            }
            MotionButton(
                onClick = { if (play) onPlay(episode) else onSources(episode) },
                enabled = active,
                modifier = modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (play) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (play) colors.onPrimary else colors.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                val icon = when (availability) {
                    AnimeEpisodeAvailability.MATCHED, AnimeEpisodeAvailability.CHECKING,
                    AnimeEpisodeAvailability.UNMATCHED -> Icons.Outlined.PlayArrow
                    AnimeEpisodeAvailability.RECENTLY_FAILED -> Icons.Outlined.Refresh
                    AnimeEpisodeAvailability.UPCOMING -> Icons.Outlined.Schedule
                }
                Icon(icon, null, Modifier.size(21.dp))
                Spacer(Modifier.width(6.dp))
                Text("$action　${animeEpisodeLabel(episode)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val follow: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (episode != null) MotionIconButton(onClick = { onSources(episode) }, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "${animeEpisodeLabel(episode)} 的线路") {
                Icon(Icons.Outlined.Link, "本集线路", Modifier.size(20.dp))
            }
            AnimeDetailFollow(entry, backdrop, libraryAvailable, active, native, onFollow, onStatus, onTracking, onStatusPresence)
        }
    }
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (stackActions) {
            episodeAction(Modifier.fillMaxWidth())
            follow()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                episodeAction(Modifier.weight(1f))
                follow()
            }
        }
        if (progress != null) {
            val position = animeTime(progress.positionMs)
            val duration = progress.durationMs.takeIf { it > 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (duration != null) {
                    val fraction = (progress.positionMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(colors.outlineVariant)) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
                    }
                }
                Text(mangaNumberText(if (duration == null) position else "$position / ${animeTime(duration)}"),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "已观看 $position" })
            }
        }
    }
}

@Composable
private fun AnimeDetailFollow(
    entry: AnimeLibraryEntry?,
    backdrop: HazeState,
    libraryAvailable: Boolean,
    active: Boolean,
    native: Boolean,
    onFollow: () -> Unit,
    onStatus: (AnimeWatchStatus) -> Unit,
    onTracking: () -> Unit,
    onPresence: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var trackingAfterStatus by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            open = false
            trackingAfterStatus = false
        }
    }
    val followed = entry?.followed == true
    val status = entry?.takeIf { it.followed }?.status
    Box {
        LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val followLabel = if (followed) "取消追番" else "加入追番"
                MotionIconButton(onClick = onFollow, enabled = libraryAvailable && active,
                    modifier = Modifier.size(44.dp), tooltip = followLabel) {
                    Icon(if (followed) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        followLabel, Modifier.size(19.dp),
                        tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                MotionIconButton(
                    onClick = { open = true }, enabled = libraryAvailable && active,
                    modifier = Modifier.size(44.dp),
                    tooltip = status?.let { "追番状态 · " + animeStatusLabel(it) } ?: "追番状态",
                ) {
                    Icon(Icons.Outlined.MoreHoriz, status?.let { "追番状态 · " + animeStatusLabel(it) } ?: "追番状态",
                        Modifier.size(18.dp))
                }
            }
        }
        CompactGlassPopover(
            open, backdrop,
            onDismiss = { open = false; trackingAfterStatus = false },
            onPresenceChange = { present ->
                onPresence(present)
                if (!present && trackingAfterStatus) {
                    trackingAfterStatus = false
                    if (active) onTracking()
                }
            },
        ) {
            AnimeWatchStatus.entries.forEach { option ->
                CompactMenuAction(
                    if (option == status) Icons.Outlined.Check else Icons.Outlined.RadioButtonUnchecked,
                    animeStatusLabel(option), onClick = { open = false; onStatus(option) },
                )
            }
            if (!native) CompactMenuAction(Icons.Outlined.Sync, "核对追番进度", onClick = {
                trackingAfterStatus = true
                open = false
            })
        }
    }
}

@Composable
private fun AnimeDetailDescription(subject: AnimeSubject) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (subject.description.isNotBlank()) Text(subject.description,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (subject.tags.isNotEmpty()) Text(subject.tags.joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnimeDetailStatus(
    title: String,
    icon: ImageVector,
    detail: String? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    actionIcon: ImageVector = Icons.Outlined.Refresh,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember(detail) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
        else Icon(icon, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            if (expanded && !detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (!detail.isNullOrBlank()) {
            val label = if (expanded) "收起原因" else "查看原因"
            MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp), tooltip = label) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, label, Modifier.size(18.dp))
            }
        }
        if (actionLabel != null) {
            MotionIconButton(onClick = onAction, modifier = Modifier.size(44.dp), tooltip = actionLabel) {
                Icon(actionIcon, actionLabel, Modifier.size(18.dp))
            }
        }
    }
}

private fun upcomingEpisodeLabel(episode: AnimeEpisode, fullDate: Boolean = false): String {
    val date = episode.airDate?.takeIf { it.isNotBlank() } ?: return "尚未放送"
    return "预计 ${if (fullDate) date else date.substringAfter('-')}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimeEpisodeCell(
    episode: AnimeEpisode,
    completed: Boolean,
    resume: Boolean,
    availability: AnimeEpisodeAvailability,
    sources: AnimeEpisodeSources,
    backdrop: HazeState,
    active: Boolean,
    compact: Boolean,
    libraryAvailable: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onCompleted: () -> Unit,
    onMenuPresenceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val native = episode.subjectId.startsWith("native:")
    val label = animeEpisodeLabel(episode)
    val subtitle = animeEpisodeSubtitle(episode)
    val canPlay = availability == AnimeEpisodeAvailability.MATCHED
    val canAutoStart = availability != AnimeEpisodeAvailability.UPCOMING
    val sourceState = when (availability) {
        AnimeEpisodeAvailability.MATCHED -> null
        AnimeEpisodeAvailability.CHECKING -> "查找中"
        AnimeEpisodeAvailability.UNMATCHED -> "待匹配"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "可重试"
        AnimeEpisodeAvailability.UPCOMING -> upcomingEpisodeLabel(episode)
    }
    val visibleSourceState = sourceState.takeUnless {
        availability == AnimeEpisodeAvailability.CHECKING || compact && availability == AnimeEpisodeAvailability.UNMATCHED
    }
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    var menuVisible by remember(episode.id) { mutableStateOf(false) }
    var sourcesAfterMenu by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            menuVisible = false
            sourcesAfterMenu = false
        }
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 54.dp else 64.dp)
                .clip(RoundedCornerShape(if (compact) 9.dp else 13.dp))
                .background(if (resume) colors.primaryContainer else colors.surfaceContainerLow)
                .motionPress(interaction, enabled = active, pressedScale = .97f)
                .combinedClickable(
                    interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
                    onClickLabel = when (availability) {
                        AnimeEpisodeAvailability.UPCOMING -> "查看 $label 的来源"
                        AnimeEpisodeAvailability.RECENTLY_FAILED -> "重试播放 $label"
                        else -> "播放 $label"
                    },
                    onClick = if (canAutoStart) onPlay else onSources,
                    onLongClickLabel = "本集操作", onLongClick = { menuVisible = true },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(label, subtitle).joinToString(" ")
                    val progressState = when {
                        resume && canPlay -> "续看"
                        resume -> "上次看至本集"
                        completed -> "已看"
                        else -> "未看"
                    }
                    val availabilityState = if (availability == AnimeEpisodeAvailability.UPCOMING)
                        upcomingEpisodeLabel(episode, fullDate = true) else sourceState
                    stateDescription = listOfNotNull(progressState, availabilityState).joinToString("，")
                    if (active) customActions = listOf(CustomAccessibilityAction("本集操作") { menuVisible = true; true })
                }.padding(horizontal = 7.dp, vertical = if (compact) 6.dp else 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(label, minLines = if (native) 2 else 1, maxLines = if (native) 2 else 1,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = if (resume) colors.onPrimaryContainer else colors.onSurface)
            if (!native && (subtitle != null || !compact && sourceState == null)) Text(subtitle.orEmpty(), minLines = 1, maxLines = 1,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (resume) colors.onPrimaryContainer else colors.onSurfaceVariant)
            if (visibleSourceState != null) Text(visibleSourceState, maxLines = 1,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (resume) colors.onPrimaryContainer else colors.onSurfaceVariant)
            AnimeEpisodeSourceBadges(sources, Modifier.fillMaxWidth(), enabled = active,
                ink = if (resume) colors.onPrimaryContainer else colors.onSurfaceVariant, onOpen = onSources)
        }
        if (resume || completed) {
            Icon(
                when {
                    resume && canPlay -> Icons.Outlined.PlayArrow
                    resume -> Icons.Outlined.History
                    else -> Icons.Outlined.Check
                }, null,
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp),
                tint = if (resume) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
        CompactGlassPopover(
            menuVisible, backdrop,
            onDismiss = { menuVisible = false; sourcesAfterMenu = false },
            onPresenceChange = { present ->
                onMenuPresenceChange(present)
                if (!present && sourcesAfterMenu) {
                    sourcesAfterMenu = false
                    if (active) onSources()
                }
            },
        ) {
            AnimeQuietLabel(sourceState ?: "${sources.sources.size} 个来源 · ${sources.lineCount} 条已匹配线路",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            CompactMenuAction(
                if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (completed) "取消已看" else "标记已看", enabled = libraryAvailable,
                onClick = { menuVisible = false; onCompleted() },
            )
            CompactMenuAction(Icons.Outlined.SwapHoriz, "本集来源与映射",
                onClick = { sourcesAfterMenu = true; menuVisible = false })
        }
    }
}
