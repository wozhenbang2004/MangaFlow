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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
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
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.AnimeWatchStatus
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.experience.sourceNetwork
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.CommunityComment
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.detailMetricScore
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.mangaNumberText
import dev.mangaflow.ui.motion.*

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
    val currentDiscussion by controller.discussion.state.collectAsStateWithLifecycle()
    val discussion = currentDiscussion.takeIf { it.subjectId == subject.id && it.available }
    val uriHandler = LocalUriHandler.current
    var selectedDetailTab by rememberSaveable(subject.id, "detail-sections") { mutableIntStateOf(0) }
    var discussionLinkError by remember(subject.id) { mutableStateOf(false) }
    val detailTab = selectedDetailTab.takeIf { it < 2 || discussion != null } ?: 0
    val commentsVisible = active && discussion != null && detailTab == 2
    DisposableEffect(controller, subject.id, commentsVisible) {
        if (commentsVisible) controller.discussion.setVisible(subject.id, true)
        onDispose { if (commentsVisible) controller.discussion.setVisible(subject.id, false) }
    }
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
    val nativeMetadata = entry?.nativeMetadata.takeIf { native }
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
    val sourceNames = (bindings.sortedBy { it.catalog.sourceId != state.preferredSourceId }.map { it.catalog.sourceId } +
        state.groupSourceCandidates.map { it.sourceId } + awaitingSources).distinct()
        .map { it to animeSourceName(it, state) }
    val canonicalInfo = if (native) nativeMetadata else subject
    val detailScores = remember(state.detailScores, canonicalInfo) {
        buildList {
            canonicalInfo?.rating?.takeIf { it > 0 && it <= 10.0 }?.let {
                add(AnimeSourceScore(AnimeDiscoveryProvider.BANGUMI, it, 10))
            }
            addAll(state.detailScores.filter { it.scale > 0 && it.rating.isFinite() &&
                it.rating > 0 && it.rating <= it.scale.toDouble() })
        }.distinctBy { it.provider }
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val desktop = platformKind == PlatformKind.DESKTOP
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val horizontal = if (maxWidth >= 720.dp) 28.dp else 16.dp
        val contentWidth = minOf(maxWidth, 1080.dp)
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
                    nativeSourceName = nativeSource?.let { animeSourceName(it, state) },
                    scores = detailScores,
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
                val labels = if (discussion != null) listOf("剧集", "简介", "评论") else listOf("剧集", "简介")
                AnimeChoiceStrip(labels, detailTab, onSelect = { selectedDetailTab = it },
                    active = active, modifier = Modifier.widthIn(max = 400.dp).padding(vertical = 4.dp))
            }
            if (detailTab == 1) {
                item(key = "description") {
                    val info = canonicalInfo ?: subject
                    if (info.description.isNotBlank() || info.tags.isNotEmpty()) AnimeDetailDescription(info)
                    else AnimeMessage("暂无简介", subject.originalTitle.takeIf { it != subject.title && it.isNotBlank() })
                }
                if (native && !state.libraryReadUnavailable) {
                    nativeMetadataPicker(nativeMetadata, metadataSearch, active, metadataQuery, reviewedMetadata,
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
            if (discussion != null && detailTab == 2) {
                item(key = "comments-heading") {
                    AnimeSectionTitle("Bangumi 讨论", discussion.total?.let { "$it 条评论" }) {
                        Row {
                            if (discussion.error == null) MotionIconButton(
                                onClick = { controller.discussion.retry(subject.id) },
                                enabled = active && !discussion.loading && !discussion.loadingMore,
                                modifier = Modifier.size(44.dp), tooltip = "刷新评论",
                            ) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) }
                            discussion.sourceUrl?.takeIf(String::isNotBlank)?.let { url ->
                                MotionIconButton(onClick = {
                                    discussionLinkError = runCatching { uriHandler.openUri(url) }.isFailure
                                }, enabled = active,
                                    modifier = Modifier.size(44.dp), tooltip = "在 Bangumi 查看") {
                                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                items(discussion.items, key = { "comment:${it.id}" }) { review ->
                    Column {
                        CommunityComment(review, Modifier.padding(vertical = 6.dp), sourceId = "bangumi")
                        HorizontalDivider(Modifier.padding(start = 36.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    }
                }
                item(key = "comments-status") {
                    if (discussionLinkError) AnimeQuietLabel("暂时无法打开 Bangumi 页面")
                    when {
                        discussion.error != null -> AnimeDiscoveryInlineStatus(discussion.error,
                            retry = { controller.discussion.retry(subject.id) }.takeIf { active })
                        discussion.loading || discussion.loadingMore || !discussion.loaded -> AnimeDetailStatus(
                            if (discussion.items.isEmpty()) "正在读取评论" else "正在更新评论",
                            Icons.Outlined.Info, loading = true,
                        )
                        discussion.items.isEmpty() -> AnimeQuietLabel("暂无评论")
                    }
                    if (discussion.hasMore && discussion.error == null) MotionTextButton(
                        onClick = { controller.discussion.loadMore(subject.id) },
                        enabled = active && !discussion.loading && !discussion.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text("加载更多评论") }
                }
                return@LazyColumn
            }
            item(key = "episodes-heading") {
                Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    AnimeSectionTitle(
                        "选集",
                        when {
                            search.isNotEmpty() -> "${episodes.size} / ${selectedEpisodes.size} 集"
                            selectedEpisodes.isNotEmpty() -> "${selectedEpisodes.size} 集"
                            else -> null
                        },
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
                                    AnimeSourceBadge(sourceNames, enabled = active,
                                        modifier = Modifier.widthIn(max = 90.dp).semantics {
                                            if (awaitingSources.isNotEmpty()) stateDescription = "有播放来源待确认"
                                        },
                                        onClick = { onSources(null) })
                                } else {
                                    MotionTextButton(onClick = { onSources(null) }, enabled = active,
                                        modifier = Modifier.heightIn(min = 44.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("来源", maxLines = 1)
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
                        if (detail.episodes.isEmpty()) "集数暂不可用" else "集数更新未完成",
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
                            AnimeEpisodeCell(
                                episode = episode,
                                completed = episode.id in completed,
                                resume = episode.id == resume?.id,
                                availability = controller.episodeAvailability(episode.id, state),
                                matched = controller.confirmedPlaybackLines(episode.id, state).size,
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
            if (state.sourceError != null) item(key = "source-status") {
                AnimeDetailStatus("部分线路暂不可用", Icons.Outlined.CloudOff,
                    actionLabel = "查看线路", onAction = { onSources(null) })
            }
            state.sourceCatalogs.filter {
                (!it.notice.isNullOrBlank() || state.sourceCatalogErrors[it.title.sourceId] != null) &&
                    it != pendingNativeCatalog
            }.forEach { catalog ->
                item(key = "partial-catalog:${catalog.title.sourceId}:${catalog.title.id}") {
                    AnimeDetailStatus(
                        "${animeSourceName(catalog.title.sourceId, state)} · 目录未更新完整",
                        Icons.Outlined.Info,
                        detail = state.sourceCatalogErrors[catalog.title.sourceId] ?: catalog.notice,
                        actionLabel = "重试此来源目录", actionIcon = Icons.Outlined.Refresh,
                        onAction = { controller.refreshSource(catalog.title.sourceId) },
                    )
                }
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
            AnimeSectionTitle("Bangumi 资料", linked?.let { listOfNotNull(it.title, it.year?.toString()).joinToString(" · ") }) {
                if (search == null) MotionTextButton(onClick = onSearch,
                    enabled = active && query.isNotBlank(), modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (linked == null) "查找关联" else "更换关联")
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
                    !unlink -> AnimeQuietLabel("选择相同作品与季度，以关联简介和作品评论。")
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
    nativeSourceName: String?,
    scores: List<AnimeSourceScore>,
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
        nativeSourceName,
        (subject.year ?: metadata?.year)?.toString(),
        (subject.episodeCount ?: metadata?.episodeCount)?.takeIf { it > 0 }?.let {
            if (native && subject.episodeCount == null) "Bangumi 共 $it 集" else "$it 集"
        },
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
            val source = artwork.externalIds["native-source"] ?: BANGUMI_PROVIDER
            ImageRequest.Builder(imagePlatformContext(context)).data(artwork.coverUrl)
                .sourceNetwork(source, DownloadCategory.ANIME).size(imageWidth, imageWidth * 3 / 2)
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
                if (scores.isNotEmpty()) AnimeDetailRatingStrip(scores, backdrop, active, onRatingPresence)
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
private fun AnimeDetailRatingStrip(scores: List<AnimeSourceScore>, backdrop: HazeState, active: Boolean,
    onPresence: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) open = false }
    fun AnimeSourceScore.displayScore() = "${detailMetricScore(rating)} / $scale"
    Box {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            scores.forEach { score ->
                val provider = animeDiscoveryProviderLabel(score.provider)
                MotionTextButton(onClick = { open = true }, enabled = active,
                    modifier = Modifier.heightIn(min = 44.dp).semantics {
                        contentDescription = "$provider 评分 ${score.displayScore()}，查看各站评分"
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 5.dp)) {
                    ReviewSourceMark(score.provider.name.lowercase(), size = 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(score.displayScore(), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, softWrap = false)
                }
            }
        }
        CompactGlassPopover(open, backdrop, onDismiss = { open = false }, onPresenceChange = onPresence,
            alignToStart = true) {
            AnimeSectionTitle("各站评分")
            scores.forEach { score ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewSourceMark(score.provider.name.lowercase(), size = 18.dp)
                    Text(animeDiscoveryProviderLabel(score.provider), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(score.displayScore(), style = MaterialTheme.typography.bodyMedium)
                }
            }
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
            val play = availability != AnimeEpisodeAvailability.RECENTLY_FAILED &&
                availability != AnimeEpisodeAvailability.UPCOMING
            val action = when (availability) {
                AnimeEpisodeAvailability.MATCHED, AnimeEpisodeAvailability.CHECKING, AnimeEpisodeAvailability.UNMATCHED ->
                    if (progress != null) "继续观看" else "播放"
                AnimeEpisodeAvailability.RECENTLY_FAILED -> "查看其他线路"
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
                    AnimeEpisodeAvailability.UPCOMING -> Icons.Outlined.Schedule
                    else -> Icons.Outlined.Link
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
    matched: Int,
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
    val canAutoStart = availability != AnimeEpisodeAvailability.RECENTLY_FAILED &&
        availability != AnimeEpisodeAvailability.UPCOMING
    val sourceState = when (availability) {
        AnimeEpisodeAvailability.MATCHED -> null
        AnimeEpisodeAvailability.CHECKING -> "查找中"
        AnimeEpisodeAvailability.UNMATCHED -> "待匹配"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "线路暂不可用"
        AnimeEpisodeAvailability.UPCOMING -> upcomingEpisodeLabel(episode)
    }
    val visibleSourceState = sourceState.takeUnless {
        compact && (availability == AnimeEpisodeAvailability.CHECKING || availability == AnimeEpisodeAvailability.UNMATCHED)
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
                    onClickLabel = if (canAutoStart) "播放 $label" else "查看 $label 的来源",
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
            AnimeQuietLabel(sourceState ?: "$matched 条已匹配线路",
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
