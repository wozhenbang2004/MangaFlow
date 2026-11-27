package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeNativeMetadataSearch
import dev.mangaflow.anime.AnimeRelatedSourceRequest
import dev.mangaflow.anime.AnimeScreenState
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.AnimeWatchStatus
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.anime.sources.isRetiredAnimeSource
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.DesktopSectionTabs
import dev.mangaflow.ui.DetailAction
import dev.mangaflow.ui.DetailTag
import dev.mangaflow.ui.screenFitContentInsets
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.platform.softwareBitmap
import coil3.Image as CoilImage
import dev.mangaflow.ui.motion.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimeDetailPane(
    detail: AnimeDetail,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit,
    onOpenRelated: (AnimeSubject) -> Unit,
    onTag: (AnimeSubject, String) -> Unit,
    onOpenSourceRelated: (AnimeRelatedSourceRequest, AnimeSourceTitle) -> Unit,
    onSources: (AnimeEpisode?) -> Unit,
    onTracking: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onHeroCollapsed: (Boolean) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
) {
    val subject = detail.subject
    val nativeSource = subject.externalIds["native-source"]
    val native = nativeSource != null
    val retiredNativeSource = nativeSource?.let(::isRetiredAnimeSource) == true
    val douban = subject.canonicalDoubanId() != null
    val supportsMetadataLink = native || douban
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    val linkedMetadata = (entry?.nativeMetadata.takeIf { supportsMetadataLink }
        ?: detail.metadataSubject.takeUnless { entry?.metadataAutoMatchDisabled == true })
        ?.takeIf { it.canonicalBangumiId() != null }
    val canonicalInfo = subject.takeIf { it.canonicalBangumiId() != null } ?: linkedMetadata
    val tagOwner = subject.takeIf { it.tags.any(String::isNotBlank) }
        ?: canonicalInfo?.takeIf { it.tags.any(String::isNotBlank) }
    val workReferenceId = subject.canonicalBangumiId() ?: (entry?.nativeMetadata
        ?: detail.metadataSubject.takeUnless { entry?.metadataAutoMatchDisabled == true })?.canonicalBangumiId()
    val currentDiscussion by controller.discussion.state.collectAsStateWithLifecycle()
    val observedWork = currentDiscussion.workContext?.takeIf {
        it.subjectId == subject.id && it.referenceId == workReferenceId
    }
    val canMatchCommentWork = supportsMetadataLink && entry?.metadataAutoMatchDisabled != true && !state.libraryReadUnavailable
    val checkingCommentWork = canMatchCommentWork && currentDiscussion.subjectId == subject.id && currentDiscussion.workReferencePending
    var detailTab by rememberSaveable(subject.id, "detail-sections") { mutableIntStateOf(0) }
    var discussionFilter by remember(subject.id) { mutableStateOf(false) }
    var discussionFilterChanged by remember(subject.id) { mutableStateOf(false) }
    var discussionChecksPresent by remember(subject.id) { mutableStateOf(false) }
    var discussionOpenRevision by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var statusPresent by remember(subject.id) { mutableStateOf(false) }
    var episodeMenuOwner by remember(subject.id) { mutableStateOf<String?>(null) }
    val sections = rememberSaveableStateHolder()
    val groups = rememberSaveableStateHolder()
    val detailTabs = listOf("选集", "简介", "评论", "相关")
    fun selectDetailTab(index: Int) {
        if (index == 2) discussionOpenRevision++
        discussionFilter = false
        detailTab = index
    }
    val commentsVisible = active && detailTab == 2 && observedWork != null
    DisposableEffect(controller, observedWork, commentsVisible) {
        if (commentsVisible && observedWork != null) controller.discussion.setVisible(observedWork, true)
        onDispose { if (commentsVisible && observedWork != null) controller.discussion.setVisible(observedWork, false) }
    }
    val relatedRoutes by controller.relatedSourceRoutes.collectAsStateWithLifecycle()
    val availableRelatedRequests = remember(controller, subject.id, state.sourceBindings, state.sourceCatalogs,
        state.sourceExtensions, state.libraryReadUnavailable, relatedRoutes, controller.relatedSourceVisit) {
        (controller.relatedSourceRequests(state) + controller.retainedRelatedSourceRequests(state)).distinct()
    }
    var leavingRelatedRequests by remember(subject.id) { mutableStateOf(emptyList<AnimeRelatedSourceRequest>()) }
    SideEffect { if (active) leavingRelatedRequests = availableRelatedRequests }
    val relatedRequests = if (active) availableRelatedRequests else leavingRelatedRequests
    var selectedRelatedSource by rememberSaveable(subject.id, "related-source") { mutableStateOf<String?>(null) }
    val relatedRequest = relatedRequests.firstOrNull { detailRelatedSourceKey(it) == selectedRelatedSource }
        ?: relatedRequests.firstOrNull()
    val relatedSourceResults = remember(controller, subject.id) {
        mutableStateMapOf<AnimeRelatedSourceRequest, AnimeDetailRecommendations>()
    }
    val retainedRecommendations = remember(controller, relatedRequest) {
        relatedRequest?.let(controller::cachedDetailSourceRelated)
    }
    val sourceRecommendations = relatedRequest?.let { request ->
        relatedSourceResults[request] ?: AnimeDetailRecommendations(
            result = retainedRecommendations?.let { Result.success(it) }, items = retainedRecommendations.orEmpty())
    }
    val ownsRecommendations = relatedRequest?.let(controller::ownsRelatedSource) == true
    val relatedVisible = active && detailTab == 3 && !statusPresent && !discussionChecksPresent && episodeMenuOwner == null
    SideEffect {
        if (relatedVisible && relatedRequest != null) selectedRelatedSource = detailRelatedSourceKey(relatedRequest)
    }
    val relatedLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, availableRelatedRequests) {
        if (active) relatedSourceResults.keys.filterNot { it in availableRelatedRequests }.forEach { relatedSourceResults.remove(it) }
    }
    LaunchedEffect(relatedRequest, sourceRecommendations?.revision, ownsRecommendations, relatedVisible, relatedLifecycle) {
        val request = relatedRequest ?: return@LaunchedEffect
        val reading = sourceRecommendations ?: return@LaunchedEffect
        if (!relatedVisible || !ownsRecommendations) return@LaunchedEffect
        relatedLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val latest = relatedSourceResults[request] ?: reading
            if (!latest.pending) return@repeatOnLifecycle
            val result = try { Result.success(controller.readDetailSourceRelated(request)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { Result.failure(failure) }
            if (controller.ownsRelatedSource(request)) {
                val current = relatedSourceResults[request] ?: reading
                if (current.revision == latest.revision) relatedSourceResults[request] = current.copy(
                    result = result, items = result.getOrNull() ?: current.items, completedRevision = latest.revision)
            }
        }
    }
    val metadataSearch = state.nativeMetadataSearch?.takeIf { it.subjectId == subject.id }
    var metadataQuery by rememberSaveable(subject.id, "metadata-query") { mutableStateOf(subject.title) }
    var reviewedMetadata by rememberSaveable(subject.id, "metadata-candidate") { mutableStateOf<String?>(null) }
    var unlinkMetadata by rememberSaveable(subject.id, "metadata-unlink") { mutableStateOf(false) }
    fun LazyListScope.metadataPicker(paneActive: Boolean, forComments: Boolean = false) {
        nativeMetadataPicker(linkedMetadata.takeUnless { forComments }, metadataSearch, paneActive,
            metadataQuery, reviewedMetadata, unlinkMetadata && !forComments,
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
            }, forComments = forComments,
            automaticPending = native && checkingCommentWork,
            automaticError = state.nativeMetadataError.takeIf { native },
            onRetryAutomatic = { controller.retryNativeMetadata(subject.id) })
    }
    val completed = entry?.completedEpisodeIds.orEmpty()
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var episodeReversed by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var episodeSearch by rememberSaveable(subject.id) { mutableStateOf(false) }
    var locateRevision by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var appliedLocate by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    val (main, extra) = remember(detail.episodes, native) {
        if (native) detail.episodes to emptyList() else detail.episodes.partition { it.kind == AnimeEpisodeKind.MAIN }
    }
    val selectedTab = if (main.isEmpty() && extra.isNotEmpty()) 1 else if (extra.isEmpty()) 0 else episodeTab
    val selectedEpisodes = if (selectedTab == 0) main else extra
    val search = episodeQuery.trim()
    val episodes = remember(selectedEpisodes, episodeReversed, search) {
        val ordered = if (episodeReversed) selectedEpisodes.asReversed() else selectedEpisodes
        if (search.isEmpty()) ordered else ordered.filter { episode ->
            animeEpisodeLabel(episode).contains(search, ignoreCase = true) || episode.title.contains(search, ignoreCase = true)
        }
    }
    val savedProgress = entry?.resume
    val savedEpisode = detail.episodes.firstOrNull { it.id == savedProgress?.episodeId && it.subjectId == subject.id }
    val stockFirst = detailPlayEpisode(controller, state, detail, savedProgress)
    val savedLineEpisode = savedProgress?.let { controller.savedResumeEpisode(detail, it) }
    val first = stockFirst ?: savedLineEpisode
    val offeredSavedLine = savedLineEpisode != null && savedLineEpisode.id == first?.id
    val firstAvailability = first?.let { controller.episodeAvailability(it.id, state) }
    val bindings = remember(state.sourceBindings, subject.id) { state.sourceBindings.filter { it.subjectId == subject.id } }
    val firstLines = first?.let { controller.confirmedPlaybackLines(it.id, state) }.orEmpty()
    val firstCanRetry = first != null && firstAvailability == AnimeEpisodeAvailability.RECENTLY_FAILED &&
        firstLines.any { controller.canAttemptPlaybackLine(first.id, it, state) }
    val lastSource = firstLines.firstOrNull {
        savedProgress?.episodeId == first?.id && savedProgress?.sourceId == it.sourceId
    }?.let { it.sourceId to it.sourceName }
    val hasSourceIssues = state.sourceError != null || state.sourceCatalogErrors.isNotEmpty()
    val sourceSearchInProgress = state.sourceAggregationRunning || state.episodeOptionsLoading
    val resumeCheckpoint = savedProgress?.let { detailResumeCheckpoint(state, subject.id, it) ?: it.takeIf { offeredSavedLine } }
    val offlineProgress = detailOfflineProgress(state, subject.id, first, savedProgress)
    val offline = if (offlineProgress != null) {
        val downloads = controller.downloads.state
        val projection = remember(downloads, subject.id, offlineProgress.localDownloadId,
            offlineProgress.episodeId, offlineProgress.assetId, offlineProgress.sourceId) {
            downloads.map { animeDetailOffline(it, subject.id, offlineProgress) }.distinctUntilChanged()
        }
        projection.collectAsStateWithLifecycle(initialValue = animeDetailOffline(downloads.value, subject.id, offlineProgress)).value
    } else AnimeDetailOffline.MISSING.takeIf { savedProgress?.localDownloadId != null && !savedProgress.completed &&
        (first == null || first.id == savedProgress.episodeId) }
    val checkingSavedEpisode = savedProgress != null && state.pendingHistoryEpisodeId == savedProgress.episodeId
    val playControl = animeDetailPlayControl(first, savedProgress, firstAvailability,
        checkingSavedEpisode, firstCanRetry, offline, resumeCheckpoint, offeredSavedLine)
    val pendingNativeCatalog = state.sourceCatalogs.firstOrNull {
        it.title.sourceId == nativeSource && !it.notice.isNullOrBlank() && detail.episodes.isEmpty()
    }
    val missingSelectedGroup = pendingNativeCatalog?.let { catalog ->
        bindings.firstOrNull { it.catalog.sourceId == catalog.title.sourceId && it.catalog.id == catalog.title.id &&
            it.catalog.url == catalog.title.url }?.selectedGroup?.let { group -> catalog.episodes.none { it.group == group } }
    } == true
    val detailScores = (state.detailScores + animeSubjectRatingSeeds(subject).filter {
        state.detailScoreChecks[it.provider] == null
    }).distinctBy { it.provider }
    val liveScores = observedAnimeRatingSnapshot(subject, detailScores, enabled = active).scores
    val motion = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    val header = key(subject.id) { rememberTopAppBarState(initialHeightOffsetLimit = 0f) }
    var catalogueHeaderOffset by remember(subject.id) { mutableFloatStateOf(0f) }
    var informationOwnsHeader by remember(subject.id) { mutableStateOf(false) }
    var measuredHero by remember(subject.id) { mutableIntStateOf(0) }
    var activeCatalogScroll by remember(subject.id) { mutableStateOf(scroll) }
    var contentsRequested by remember(subject.id) { mutableStateOf(false) }
    var headerJump by remember(subject.id) { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val reportHeroCollapsed by rememberUpdatedState(onHeroCollapsed)
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // The paper sits above the window wallpaper and below the artwork and scrolling content.
        Box(Modifier.matchParentSize().mangaHazeSource(backdrop, zIndex = -.5f)
            .background(colors.surface.copy(alpha = .94f)))
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val compactHero = maxHeight - contentTopPadding < 450.dp
        val toolbarPixels = with(density) { contentTopPadding.roundToPx() }
        val desktop = platformKind == PlatformKind.DESKTOP
        val wide = if (desktop) maxWidth >= 780.dp * fontScale && maxHeight - contentTopPadding >= 325.dp * fontScale
            else maxWidth >= (if (compactHero) 650.dp else 840.dp) * fontScale.coerceAtMost(1.5f)
        val width = minOf(maxWidth, if (wide) 1400.dp else 1080.dp)
        val horizontal = if (wide) 24.dp else 16.dp
        val contentInsets = screenFitContentInsets()
        val bottomInset = contentInsets.asPaddingValues().calculateBottomPadding()
        val showDock = playControl != null && WindowInsets.ime.getBottom(density) == 0
        var measuredDock by remember(subject.id, width, density, compactHero) { mutableIntStateOf(0) }
        // Lists draw edge-to-edge, but their last row must scroll completely clear of the dock.
        val dockClearance = if (showDock && !wide) maxOf((38f * fontScale + 10f).coerceAtLeast(56f).dp,
            with(density) { measuredDock.toDp() }) + 16.dp else 0.dp
        val listEnd = 18.dp + bottomInset + dockClearance
        fun locateSavedEpisode() {
            val alreadyInCatalogue = detailTab == 0
            savedEpisode?.let { episodeTab = if (native || it.kind == AnimeEpisodeKind.MAIN) 0 else 1 }
            episodeQuery = ""
            episodeSearch = false
            locateRevision++
            contentsRequested = true
            selectDetailTab(0)
            if (!wide && alreadyInCatalogue) {
                headerJump?.cancel()
                headerJump = scope.launch {
                    if (motion) Animatable(header.heightOffset).animateTo(header.heightOffsetLimit,
                        tween(320, easing = MangaMotion.EnterEasing)) { header.heightOffset = value }
                    else header.heightOffset = header.heightOffsetLimit
                    contentsRequested = false
                }
            } else if (wide) contentsRequested = false
        }
        val dockTravel = with(density) { 96.dp.toPx() }
        val dockCollapse by remember(header, subject.id, detailTab, wide, dockTravel) {
            derivedStateOf {
                if (wide || detailTab != 0) 0f else {
                    val coverTravel = (-header.heightOffset).coerceIn(0f, measuredHero.toFloat())
                    val listTravel = if (activeCatalogScroll.firstVisibleItemIndex > 0) 1f
                        else (activeCatalogScroll.firstVisibleItemScrollOffset / dockTravel).coerceIn(0f, 1f)
                    maxOf((coverTravel / dockTravel).coerceIn(0f, 1f), listTravel)
                }
            }
        }
        val playDock: @Composable (Modifier) -> Unit = { dockModifier ->
            playControl?.let { control ->
                val dockActive = active && !statusPresent && !discussionChecksPresent && !discussionFilter && episodeMenuOwner == null
                AnimeDetailPlayAction(subject.id, control, backdrop = backdrop, active = dockActive,
                    persistent = wide, collapseProgress = { dockCollapse }, modifier = dockModifier,
                    onHeightChanged = { measuredDock = maxOf(measuredDock, it) },
                    onEpisodes = {
                        if (dockActive && controller.state.value.detail?.subject?.id == subject.id) locateSavedEpisode()
                    },
                    onLines = stockFirst?.takeUnless { state.resolving || state.playbackTarget != null }?.let { target ->
                        {
                            val latest = controller.state.value
                            val current = latest.detail?.takeIf { it.subject.id == subject.id }
                            if (dockActive && current != null && !latest.resolving && latest.playbackTarget == null) {
                                val previous = latest.library.firstOrNull { it.subject.id == subject.id }?.resume
                                val episode = detailPlayEpisode(controller, latest, current, previous)
                                if (episode != null && episode.id == target.id) onSources(episode)
                            }
                        }
                    },
                    onActivate = play@{
                        val latest = controller.state.value
                        val liveDetail = latest.detail?.takeIf { it.subject.id == subject.id } ?: return@play
                        if (!active || latest.resolving || latest.playbackTarget != null) return@play
                        val liveProgress = latest.library.firstOrNull { it.subject.id == subject.id }?.resume
                        if (offeredSavedLine && control.mode == AnimeDetailPlayMode.PLAY) {
                            if (liveProgress != null && savedProgress != null && liveProgress.episodeId == savedProgress.episodeId &&
                                liveProgress.assetId == savedProgress.assetId && !liveProgress.completed)
                                controller.resumeSavedEpisode(subject.id, liveProgress)
                            return@play
                        }
                        val liveEpisode = detailPlayEpisode(controller, latest, liveDetail, liveProgress)
                        // No old visible title may start another episode (e.g. history was just edited).
                        if (liveEpisode?.id != first?.id || liveProgress?.episodeId != savedProgress?.episodeId ||
                            liveProgress?.completed != savedProgress?.completed || liveProgress?.assetId != savedProgress?.assetId ||
                            liveProgress?.localDownloadId != savedProgress?.localDownloadId) return@play
                        when (control.mode) {
                            AnimeDetailPlayMode.WAIT -> Unit
                            AnimeDetailPlayMode.SOURCES, AnimeDetailPlayMode.UPCOMING -> onSources(liveEpisode)
                            AnimeDetailPlayMode.PLAY, AnimeDetailPlayMode.FIND -> {
                                val liveOffline = detailOfflineProgress(latest, subject.id, liveEpisode, liveProgress)
                                if (liveOffline != null) {
                                    if (offline != AnimeDetailOffline.READY ||
                                        liveOffline.localDownloadId != offlineProgress?.localDownloadId ||
                                        liveOffline.assetId != offlineProgress?.assetId ||
                                        animeDetailOffline(controller.downloads.state.value, subject.id, liveOffline) != AnimeDetailOffline.READY)
                                        return@play
                                } else if (offline != null || liveEpisode == null ||
                                    controller.episodeAvailability(liveEpisode.id, latest) == AnimeEpisodeAvailability.UPCOMING) return@play
                                if (liveProgress != null) {
                                    val liveCheckpoint = detailResumeCheckpoint(latest, subject.id, liveProgress)
                                    if ((resumeCheckpoint != null) != (liveCheckpoint != null)) return@play
                                    val exactEpisode = liveEpisode?.id ?: liveOffline?.episodeId ?: return@play
                                    controller.dismissNotice()
                                    controller.resumeEpisode(subject.id, exactEpisode)
                                } else liveEpisode?.let(onPlay)
                            }
                        }
                    })
            }
        }
        LaunchedEffect(subject.id, header, wide) {
            snapshotFlow { !wide && header.heightOffsetLimit < 0f && header.heightOffset <= header.heightOffsetLimit + 1f }
                .collect { reportHeroCollapsed(it) }
        }
        LaunchedEffect(detailTab, measuredHero, wide, motion) {
            if (wide || measuredHero <= 0) return@LaunchedEffect
            if (detailTab != 0 && !informationOwnsHeader) {
                catalogueHeaderOffset = header.heightOffset
                informationOwnsHeader = true
            }
            if (detailTab == 0 && !informationOwnsHeader && !contentsRequested) return@LaunchedEffect
            val target = (if (detailTab != 0 || contentsRequested) header.heightOffsetLimit
                else catalogueHeaderOffset).coerceIn(header.heightOffsetLimit, 0f)
            if (motion) Animatable(header.heightOffset).animateTo(target,
                spring(dampingRatio = MangaMotion.NavigationDamping, stiffness = 240f)) {
                header.heightOffset = value.coerceIn(header.heightOffsetLimit, 0f)
            } else header.heightOffset = target
            if (detailTab == 0) { informationOwnsHeader = false; contentsRequested = false }
        }
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = header, snapAnimationSpec = null, flingAnimationSpec = rememberSplineBasedDecay())
        val scrolling = remember(behavior) { object : NestedScrollConnection by behavior.nestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) headerJump?.cancel()
                return behavior.nestedScrollConnection.onPreScroll(available, source)
            }
        } }
        val hero: @Composable () -> Unit = {
            AnimeDetailHero(subject, canonicalInfo, liveScores, wide, compactHero, active, backdrop,
                contentTopPadding = contentTopPadding + if (wide) 8.dp else 0.dp,
                followAction = {
                    AnimeDetailFollow(entry, backdrop, !state.libraryReadUnavailable, active, native,
                        onFollow = { controller.setFollowed(subject, entry?.followed != true) },
                        onStatus = { controller.setWatchStatus(subject.id, it) }, onTracking = onTracking,
                        onPresence = { statusPresent = it })
                })
        }
        val sectionPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 4.dp).heightIn(min = 48.dp)
                    .motionInputGate(active && !statusPresent && !discussionChecksPresent && episodeMenuOwner == null),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (desktop && wide) DesktopSectionTabs(detailTab, detailTabs, ::selectDetailTab,
                            Modifier.widthIn(max = 340.dp * fontScale).fillMaxWidth())
                        else LiquidGlassSurface(backdrop, Modifier.widthIn(max = 320.dp * fontScale.coerceAtMost(1.4f)).fillMaxWidth(),
                            cornerRadius = 24.dp, role = GlassSurfaceRole.NAVIGATION, elevation = 1.dp) {
                            MotionTabs(detailTab, detailTabs, ::selectDetailTab, controlHeight = 48.dp,
                                fontSize = 13.sp, fullWidthIndicator = true)
                        }
                    }
                    if (detailTab == 2 && observedWork != null) {
                        AnimeDiscussionSourcesStrip(subject.id, currentDiscussion.checks, backdrop,
                            enabled = active, canRetry = true,
                            onRetry = { controller.discussion.retry(observedWork, it) },
                            onPresence = { discussionChecksPresent = it }, compact = true)
                        DetailAction(Icons.Outlined.Refresh, "刷新评论",
                            enabled = active && !discussionChecksPresent &&
                                !currentDiscussion.matching && currentDiscussion.sources.none { it.loadingMore },
                            onClick = {
                                currentDiscussion.sources.forEach { controller.discussion.retry(observedWork, it.id) }
                                controller.discussion.retryMatch(observedWork)
                            })
                        DetailAction(Icons.Outlined.FilterList, "筛选评论",
                            selected = discussionFilter || discussionFilterChanged,
                            enabled = active && !discussionChecksPresent,
                            onClick = { discussionFilter = !discussionFilter })
                    }
                }
                MotionTabContent(detailTab, Modifier.weight(1f).fillMaxWidth(), soft = true) { target ->
                    sections.SaveableStateProvider(target) {
                        val paneActive = active && target == detailTab && !statusPresent &&
                            !discussionChecksPresent && episodeMenuOwner == null
                        when (target) {
                            2 -> if (workReferenceId == null) {
                                if (supportsMetadataLink && !state.libraryReadUnavailable) {
                                    val position = rememberLazyListState()
                                    Box(Modifier.fillMaxSize()) {
                                        LazyColumn(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(paneActive),
                                            state = position, userScrollEnabled = paneActive,
                                            contentPadding = PaddingValues(start = horizontal, top = 8.dp,
                                                end = horizontal, bottom = listEnd)) {
                                            if (checkingCommentWork && metadataSearch == null) item(key = "discussion-work-loading") {
                                                AnimeDetailStatus("正在核对作品评论来源", Icons.Outlined.Search, loading = true)
                                            } else {
                                                val problem = state.detailError.takeIf { douban && canMatchCommentWork && metadataSearch == null }
                                                if (problem != null) item(key = "discussion-work-retry") {
                                                    AnimeDiscoveryInlineStatus(problem, retry = if (paneActive) ({
                                                        if (controller.state.value.detail?.subject?.id == subject.id)
                                                            controller.retryCurrentDetail()
                                                    }) else null)
                                                }
                                                metadataPicker(paneActive, forComments = true)
                                            }
                                        }
                                        if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                    }
                                } else AnimeMessage("作品评论暂不可用",
                                    if (state.libraryReadUnavailable) "本机资料关联无法读取，暂不能关联评论。"
                                    else "没有可核对的作品评论来源。", icon = Icons.Outlined.Info)
                            } else if (observedWork == null) {
                                val opening = state.detailLoading || currentDiscussion.subjectId == subject.id
                                AnimeMessage(if (opening) "正在核对作品评论来源" else "作品评论暂不可用",
                                    detail = "请重新打开这部番剧后重试。".takeUnless { opening }, icon = Icons.Outlined.Info)
                            } else CommunityPanel(mangaId = "anime:${observedWork.subjectId}:${observedWork.referenceId}",
                                sources = currentDiscussion.sources, loading = currentDiscussion.matching,
                                onMore = { controller.discussion.loadMore(observedWork, it) },
                                onRetry = { controller.discussion.retry(observedWork, it) },
                                onMatch = { _, _ -> }, onRelink = {}, loadReplies = { _, _, _ -> ReviewPage(emptyList()) },
                                modifier = Modifier.fillMaxSize().motionInputGate(paneActive),
                                backdrop = backdrop, active = paneActive, bottomPadding = listEnd,
                                openRevision = discussionOpenRevision,
                                filterOpen = discussionFilter, onFilterDismiss = { discussionFilter = false },
                                onFilterChanged = { discussionFilterChanged = it })
                            1 -> {
                                val position = rememberLazyListState()
                                Box(Modifier.fillMaxSize()) {
                                    LazyColumn(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(paneActive),
                                        state = position, userScrollEnabled = paneActive,
                                        contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = listEnd)) {
                                        item(key = "description") {
                                            val description = subject.description.ifBlank { canonicalInfo?.description.orEmpty() }
                                            if (description.isNotBlank() || tagOwner != null) AnimeDetailDescription(
                                                subject.id, description, tagOwner, paneActive, onTag)
                                            else if (!native || !checkingCommentWork) AnimeMessage("暂无简介",
                                                subject.originalTitle.takeIf { it != subject.title && it.isNotBlank() })
                                        }
                                        if (supportsMetadataLink && !state.libraryReadUnavailable) metadataPicker(paneActive)
                                    }
                                    if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                }
                            }
                            3 -> {
                                val position = rememberLazyListState()
                                val relatedReferenceId = canonicalInfo?.id
                                val related = state.related.takeIf { it.referenceId == relatedReferenceId }
                                val relatedItems = related?.items.orEmpty()
                                val canReadRelated = controller.canReadDetailRelated(subject.id)
                                val relatedGroups = remember(relatedItems) {
                                    val grouped = relatedItems.groupBy { detailRelatedSection(it.relation) }
                                    listOf("前作", "后续", "本篇与番外", "其他版本", "其他关联").mapNotNull { label ->
                                        grouped[label]?.let { label to it }
                                    }
                                }
                                val sourceRows = sourceRecommendations?.items.orEmpty()
                                val checkingSources = state.detailLoading || sourceSearchInProgress
                                val awaitingRelations = !state.libraryReadUnavailable && relatedItems.isEmpty() &&
                                    (relatedReferenceId == null && checkingCommentWork || related?.loading == true)
                                val awaitingRecommendations = if (relatedRequest != null)
                                    sourceRecommendations?.result == null && (ownsRecommendations || checkingSources)
                                else checkingSources
                                var listShown by remember { mutableStateOf(false) }
                                val restoringPosition = position.firstVisibleItemIndex > 0 || position.firstVisibleItemScrollOffset > 0
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val columns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
                                    val gap = animeDiscoveryGap(columns)
                                    val inset = maxOf(horizontal, animeDiscoveryInset(maxWidth, state.discoveryColumns, columns))
                                    val relationColumns = if (desktop && wide && maxWidth - inset * 2 >= 600.dp * fontScale) 2 else 1
                                    val relationRows = remember(relatedGroups, relationColumns) {
                                        relatedGroups.map { (label, group) -> label to group.chunked(relationColumns) }
                                    }
                                    val recommendationRows = remember(sourceRows, columns) { sourceRows.chunked(columns) }
                                    // Status and choice slots keep their indices while refreshing either section.
                                    val itemCount = 5 + relationRows.sumOf { it.second.size + 1 } + recommendationRows.size
                                    val waitForRestore = !listShown && restoringPosition && sourceRows.isEmpty() &&
                                        position.firstVisibleItemIndex >= itemCount && (awaitingRelations || awaitingRecommendations)
                                    if (waitForRestore) {
                                        Box(Modifier.fillMaxSize().padding(horizontal = inset, vertical = 8.dp)) {
                                            AnimeDetailStatus("正在恢复相关番剧", Icons.Outlined.Info, loading = true)
                                        }
                                    } else {
                                        SideEffect { listShown = true }
                                        LazyColumn(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(paneActive),
                                            state = position, userScrollEnabled = paneActive,
                                            contentPadding = PaddingValues(start = inset, end = inset, bottom = listEnd)) {
                                            item(key = "related-heading") {
                                                AnimeSectionTitle("作品关联", subtitle = "Bangumi",
                                                    modifier = Modifier.semantics { heading() })
                                            }
                                            item(key = "related-status") {
                                                when {
                                                    state.libraryReadUnavailable && relatedItems.isEmpty() ->
                                                        AnimeQuietLabel("本机资料关联暂不可读取")
                                                    !canReadRelated && relatedItems.isEmpty() ->
                                                        AnimeQuietLabel("重新打开这部番剧后，可查看作品关联")
                                                    relatedReferenceId == null && checkingCommentWork ->
                                                        AnimeDetailStatus("正在核对作品关联", Icons.Outlined.Search, loading = true)
                                                    relatedReferenceId == null -> AnimeDiscoveryInlineStatus("确认作品资料后可查看关联动画",
                                                        retry = if (paneActive && supportsMetadataLink && !state.libraryReadUnavailable)
                                                            ({ selectDetailTab(1) }) else null,
                                                        retryLabel = "补充资料", showIssue = false)
                                                    related?.loading == true ->
                                                        AnimeDetailStatus(if (relatedItems.isEmpty()) "正在读取关联动画" else "正在更新关联动画",
                                                        Icons.Outlined.Info, loading = true)
                                                    related?.error != null ->
                                                        AnimeDiscoveryInlineStatus(if (relatedItems.isEmpty()) "关联动画暂未载入" else "关联动画更新失败",
                                                        detail = related.error,
                                                        retry = if (paneActive) ({ controller.retryDetailRelated() }) else null)
                                                    related?.loaded != true -> AnimeDiscoveryInlineStatus("作品关联尚未读取",
                                                        retry = if (paneActive) ({ controller.retryDetailRelated() }) else null,
                                                        retryLabel = "读取资料", showIssue = false)
                                                    relatedItems.isEmpty() -> AnimeQuietLabel("Bangumi 暂未收录关联动画")
                                                    else -> Unit
                                                }
                                            }
                                            relationRows.forEach { (label, rows) ->
                                                item(key = "related-group:$label") {
                                                    AnimeSectionTitle(label, modifier = Modifier.semantics { heading() })
                                                }
                                                items(rows, key = { "related:${it.first().subject.id}:${it.first().relation}" }) { row ->
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                                        row.forEach { entry -> key(entry.subject.id, entry.relation) {
                                                            val summary = animeSubjectSummary(entry.subject)
                                                            AnimeSubjectRow(entry.subject,
                                                                listOf(entry.relation, summary).filter(String::isNotBlank).joinToString(" · "),
                                                                modifier = Modifier.weight(1f),
                                                                onOpen = { if (paneActive) onOpenRelated(entry.subject) })
                                                        } }
                                                        if (row.size < relationColumns) Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                            item(key = "source-related-heading") {
                                                AnimeSectionTitle("你可能也喜欢",
                                                    subtitle = relatedRequest?.sourceName?.let { "来自 $it" },
                                                    modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                                            }
                                            item(key = "source-related-choices") {
                                                if (relatedRequests.size > 1) AnimeChoiceStrip(relatedRequests.map { it.sourceName },
                                                    relatedRequests.indexOfFirst { it == relatedRequest }, active = paneActive,
                                                    onSelect = { index ->
                                                        if (paneActive) relatedRequests.getOrNull(index)?.let {
                                                            selectedRelatedSource = detailRelatedSourceKey(it)
                                                        }
                                                    })
                                            }
                                            val recommendation = sourceRecommendations
                                            item(key = "source-related-status") {
                                                when {
                                                    relatedRequest == null -> {
                                                        if (state.libraryReadUnavailable) AnimeQuietLabel("本机来源关联暂不可读取")
                                                        else if (checkingSources) AnimeDetailStatus("正在核对推荐来源", Icons.Outlined.Search, loading = true)
                                                        else AnimeQuietLabel("当前来源暂不提供相关推荐")
                                                    }
                                                    !ownsRecommendations -> {
                                                        if (checkingSources) AnimeDetailStatus("正在核对推荐来源", Icons.Outlined.Search, loading = true)
                                                        else AnimeDiscoveryInlineStatus("推荐来源暂不可用",
                                                        retry = if (paneActive) ({ onSources(null) }) else null,
                                                        retryLabel = "查看来源", showIssue = false)
                                                    }
                                                    recommendation?.pending == true ->
                                                        AnimeDetailStatus(if (sourceRows.isEmpty()) "正在读取来源推荐" else "正在更新来源推荐",
                                                        Icons.Outlined.Info, loading = true)
                                                    recommendation?.result?.isFailure == true ->
                                                        AnimeDiscoveryInlineStatus(if (sourceRows.isEmpty()) "来源推荐暂未载入" else "来源推荐更新失败",
                                                        detail = recommendation.result?.exceptionOrNull()?.message,
                                                        retry = if (paneActive && relatedRequest != null) ({
                                                            if (controller.ownsRelatedSource(relatedRequest)) {
                                                                val current = relatedSourceResults[relatedRequest] ?: recommendation
                                                                relatedSourceResults[relatedRequest] = current.copy(revision = current.revision + 1)
                                                            }
                                                        }) else null)
                                                    recommendation?.result?.isSuccess == true && sourceRows.isEmpty() ->
                                                        AnimeQuietLabel("这个来源暂时没有推荐")
                                                    else -> Unit
                                                }
                                            }
                                            if (relatedRequest != null) items(recommendationRows,
                                                key = { row -> "source-related:${detailRelatedSourceKey(relatedRequest)}:${row.first().id}" }) { row ->
                                                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(gap)) {
                                                    row.forEach { title -> key(title.sourceId, title.id) {
                                                        AnimeSourceDiscoveryCard(title,
                                                            modifier = Modifier.weight(1f).then(if (ownsRecommendations) Modifier
                                                                else Modifier.semantics { disabled() }),
                                                            list = columns == 1,
                                                            onOpen = {
                                                                if (paneActive && controller.canOpenDetailSourceRelated(relatedRequest, title))
                                                                    onOpenSourceRelated(relatedRequest, title)
                                                            })
                                                    } }
                                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                                }
                                            }
                                        }
                                        if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                    }
                                }
                            }
                            else -> Column(Modifier.fillMaxSize()) {
                                AnimeDetailLineRow(first, firstAvailability, firstLines.size, firstCanRetry, lastSource,
                                    sourceSearchInProgress, hasSourceIssues, paneActive, Modifier.padding(horizontal = horizontal),
                                    onOpen = { onSources(first) }, onManage = { onSources(null) })
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = horizontal)
                                    .motionInputGate(paneActive),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (main.isNotEmpty() && extra.isNotEmpty()) MotionTabs(selectedTab, listOf("正片", "特别篇"),
                                        onSelect = { episodeTab = it }, modifier = Modifier.weight(1f), compact = true,
                                        controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                                    else Text(if (selectedEpisodes.isEmpty()) "全部剧集" else "${selectedEpisodes.size} 集",
                                        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                                        modifier = Modifier.weight(1f).semantics { heading() })
                                    if (detail.episodes.isNotEmpty() && state.detailError != null)
                                        AnimeDetailRefreshNotice(state.detailError, backdrop, paneActive, controller::retryCurrentDetail)
                                    if (savedProgress != null) DetailAction(Icons.Outlined.MyLocation, "定位上次观看集",
                                        enabled = paneActive, onClick = ::locateSavedEpisode)
                                    if (detail.episodes.size > 1 || episodeSearch) {
                                        DetailAction(if (episodeSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                                            if (episodeSearch) "关闭集数查找" else "查找集数", selected = episodeSearch,
                                            enabled = paneActive, onClick = { episodeSearch = !episodeSearch; if (!episodeSearch) episodeQuery = "" })
                                        DetailAction(Icons.Outlined.SwapVert, if (episodeReversed) "改为正序" else "改为倒序",
                                            selected = episodeReversed, enabled = paneActive, onClick = { episodeReversed = !episodeReversed })
                                    }
                                }
                                AnimatedVisibility(episodeSearch,
                                    enter = expandVertically(if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                                        expandFrom = Alignment.Bottom),
                                    exit = shrinkVertically(if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                                        shrinkTowards = Alignment.Bottom)) {
                                    AnimeTextField(episodeQuery, { episodeQuery = it }, "集号或标题",
                                        Modifier.padding(horizontal = horizontal, vertical = 4.dp).motionInputGate(paneActive && episodeSearch))
                                }
                                groups.SaveableStateProvider("$selectedTab:${search.isNotEmpty()}") {
                                    val position = if (selectedTab == 0 && search.isEmpty()) scroll else rememberLazyListState()
                                    SideEffect { if (target == detailTab) activeCatalogScroll = position }
                                    var lastSearch by rememberSaveable(subject.id) { mutableStateOf("") }
                                    LaunchedEffect(search, paneActive) {
                                        if (paneActive && search.isNotEmpty() && lastSearch != search) {
                                            position.requestScrollToItem(0); lastSearch = search
                                        }
                                    }
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                        val freeWidth = maxWidth - horizontal * 2
                                        val namedCatalogue = remember(selectedEpisodes, native) {
                                            val sample = selectedEpisodes.take(24)
                                            native || sample.isNotEmpty() && sample.count { animeEpisodeSubtitle(it) != null } * 2 >= sample.size
                                        }
                                        val listRows = desktop && wide && namedCatalogue && freeWidth >= 300.dp * fontScale
                                        val columns = if (listRows) ((freeWidth + 8.dp) / (282.dp * fontScale + 8.dp)).toInt().coerceIn(1, 3)
                                            else animeEpisodeGridColumns(freeWidth, fontScale, wide)
                                        val rows = remember(episodes, columns) { episodes.chunked(columns) }
                                        val showStatus = episodes.isEmpty()
                                        val missingResume = savedProgress != null && savedEpisode == null && !state.detailLoading
                                        val prefix = (if (state.libraryReadUnavailable) 1 else 0) + (if (showStatus) 1 else 0) +
                                            (if (missingResume) 1 else 0)
                                        val locateMotion = motion && !LocalTouchExplorationEnabled.current
                                        val landing = remember(position) { Animatable(0f) }
                                        var locatedId by remember(position) { mutableStateOf<String?>(null) }
                                        val landingInk = MaterialTheme.colorScheme.onPrimaryContainer
                                        LaunchedEffect(locateRevision, selectedTab, episodes, columns, paneActive, prefix, position, locateMotion) {
                                            if (!paneActive || locateRevision == appliedLocate || search.isNotEmpty()) return@LaunchedEffect
                                            val index = episodes.indexOfFirst { it.id == savedProgress?.episodeId }
                                            if (index < 0) return@LaunchedEffect
                                            appliedLocate = locateRevision
                                            val row = prefix + index / columns
                                            val visibleRows = position.layoutInfo.visibleItemsInfo.size
                                            if (locateMotion && visibleRows > 0 && abs(row - position.firstVisibleItemIndex) <= visibleRows)
                                                position.animateScrollToItem(row)
                                            else position.scrollToItem(row)
                                            if (locateMotion) {
                                                locatedId = episodes[index].id
                                                try {
                                                    landing.snapTo(1f)
                                                    landing.animateTo(0f, tween(550, easing = MangaMotion.ExitEasing))
                                                } finally {
                                                    locatedId = null
                                                }
                                            }
                                        }
                                        Column(Modifier.fillMaxSize()) {
                                            if (!native && selectedTab == 0 && search.isEmpty() && rows.size > 9) {
                                                AnimeEpisodeRanges(rows, position, prefix, paneActive, horizontal)
                                            }
                                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                                LazyColumn(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(paneActive),
                                                    state = position,
                                                    contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 4.dp, bottom = listEnd),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (state.libraryReadUnavailable) item(key = "library-read-warning") {
                                                        AnimeDetailStatus("临时浏览 · 不保存进度", Icons.Outlined.Info,
                                                            detail = "本地追番记录未能读取，原收藏与观看记录已保留。")
                                                    }
                                                    if (showStatus) item(key = "episode-status") {
                                                        when {
                                                            state.detailLoading -> AnimeDetailStatus(if (detail.episodes.isEmpty()) "正在加载集数" else "正在更新集数",
                                                                Icons.Outlined.Info, loading = true)
                                                            state.detailError != null && detail.episodes.isEmpty() -> AnimeDetailStatus("集数暂不可用",
                                                                Icons.Outlined.CloudOff, detail = state.detailError,
                                                                actionLabel = "重新加载".takeUnless { retiredNativeSource },
                                                                onAction = controller::retryCurrentDetail)
                                                            pendingNativeCatalog != null -> AnimeDetailStatus(if (missingSelectedGroup) "原线路暂不可用" else "原线路尚未加载完整",
                                                                Icons.Outlined.Info, detail = pendingNativeCatalog.notice,
                                                                actionLabel = if (missingSelectedGroup) null else "继续加载原线路",
                                                                onAction = { controller.refreshSource(pendingNativeCatalog.title.sourceId) })
                                                            search.isNotEmpty() -> AnimeDetailStatus("没有匹配的集数", Icons.Outlined.Search,
                                                                actionLabel = "清除查找", actionIcon = Icons.Outlined.Close, onAction = { episodeQuery = "" })
                                                            else -> AnimeDetailStatus("暂无集数", Icons.Outlined.Info)
                                                        }
                                                    }
                                                    if (missingResume) item(key = "missing-resume") {
                                                        AnimeDetailStatus("上次观看记录已保留", Icons.Outlined.History,
                                                            detail = "当前目录尚未找到对应剧集，不会用其他集替代。",
                                                            actionLabel = "查看作品线路", actionIcon = Icons.Outlined.Link, onAction = { onSources(null) })
                                                    }
                                                    items(rows, key = { row -> "episodes:" + row.first().id }) { row ->
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            row.forEach { episode -> key(episode.id) {
                                                                AnimeEpisodeCell(episode, episode.id in completed,
                                                                    episode.id == savedEpisode?.id,
                                                                    state.episodeProgress[episode.id]?.takeIf { it.subjectId == subject.id },
                                                                    controller.episodeAvailability(episode.id, state),
                                                                    sources = { animeEpisodeSources(episode.id,
                                                                        controller.confirmedPlaybackLines(episode.id, state), controller, state) },
                                                                    backdrop = backdrop, active = active && target == detailTab,
                                                                    libraryAvailable = !state.libraryReadUnavailable,
                                                                    onPlay = { onPlay(episode) }, onSources = { onSources(episode) },
                                                                    onCompleted = { controller.setEpisodeCompleted(episode.id, episode.id !in completed) },
                                                                    listRow = listRows,
                                                                    onMenuPresenceChange = { present -> if (present) episodeMenuOwner = episode.id
                                                                        else if (episodeMenuOwner == episode.id) episodeMenuOwner = null },
                                                                    modifier = Modifier.weight(1f).then(if (episode.id == locatedId) Modifier.drawWithContent {
                                                                        drawContent()
                                                                        val amount = landing.value.coerceIn(0f, 1f)
                                                                        val width = size.width * (.18f + .44f * amount)
                                                                        val stroke = 2.dp.toPx()
                                                                        drawRoundRect(landingInk, Offset((size.width - width) / 2f, size.height - stroke * 2f),
                                                                            Size(width, stroke), CornerRadius(stroke / 2f), alpha = amount)
                                                                    } else Modifier))
                                                            } }
                                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                                        }
                                                    }
                                                }
                                                if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.width(width).fillMaxHeight().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val workWidth = if (desktop) (width * .31f).coerceIn(242.dp * fontScale, 370.dp * fontScale)
                else (width * .36f).coerceIn(280.dp, 420.dp)
            Column(Modifier.width(workWidth).fillMaxHeight()) {
                val summaryPosition = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(summaryPosition)
                        .padding(end = 12.dp, bottom = if (showDock) 8.dp else listEnd)) {
                        hero()
                        val summary = subject.description.ifBlank { canonicalInfo?.description.orEmpty() }
                        if (desktop && detailTab != 1 && summary.isNotBlank()) {
                            var clipped by remember(summary) { mutableStateOf(false) }
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (compactHero) 3 else 5, overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { clipped = it.hasVisualOverflow })
                                if (clipped) MotionTextButton(onClick = { selectDetailTab(1) }, enabled = active,
                                    contentPadding = PaddingValues(0.dp)) { Text("完整简介") }
                            }
                        }
                    }
                    DesktopVerticalScrollbar(summaryPosition, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if (showDock) playDock(Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp, bottom = bottomInset + 4.dp))
            }
            sectionPane(Modifier.weight(1f).fillMaxHeight().padding(top = contentTopPadding + 8.dp))
        } else Box(Modifier.widthIn(max = 1080.dp).fillMaxSize()) {
            // Like the manga detail, drags outside a tile share the current catalogue's scroll owner.
            val catalogueDrag = Modifier.scrollable(activeCatalogScroll, Orientation.Vertical, reverseDirection = true,
                enabled = active && detailTab == 0 && !statusPresent && !discussionChecksPresent &&
                    !discussionFilter && episodeMenuOwner == null)
            Column(Modifier.fillMaxSize().then(if (detailTab == 0) Modifier.nestedScroll(scrolling) else Modifier)) {
                Box(Modifier.fillMaxWidth().clipToBounds().then(catalogueDrag)
                    .layout { measurable, constraints ->
                        val content = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val expanded = content.height
                        if (measuredHero != expanded) measuredHero = expanded
                        val limit = -expanded.toFloat()
                        if (header.heightOffsetLimit != limit) {
                            val previousLimit = header.heightOffsetLimit
                            val fullyCollapsed = previousLimit < 0f && header.heightOffset <= previousLimit + 1f
                            header.heightOffsetLimit = limit
                            header.heightOffset = if (fullyCollapsed) limit else header.heightOffset.coerceIn(limit, 0f)
                        }
                        val offset = header.heightOffset.roundToInt().coerceIn(-expanded, 0)
                        // The artwork moves under Back; only the pinned section controls need a safe floor.
                        val visible = maxOf(toolbarPixels, expanded + offset).coerceIn(constraints.minHeight, constraints.maxHeight)
                        layout(content.width, visible) {
                            content.placeRelative(0, offset)
                        }
                    }) { hero() }
                sectionPane(Modifier.weight(1f).fillMaxWidth().then(catalogueDrag))
            }
            if (showDock) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(contentInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
                playDock(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Keep a failed refresh beside the last real rows, with one read revision per selected catalogue. */
private data class AnimeDetailRecommendations(
    val result: Result<List<AnimeSourceTitle>>? = null,
    val items: List<AnimeSourceTitle> = emptyList(),
    val revision: Int = 0,
    val completedRevision: Int = -1,
) {
    val pending: Boolean get() = revision != completedRevision
}

private fun detailRelatedSourceKey(request: AnimeRelatedSourceRequest): String =
    "${request.sourceId}:${request.titleId}:${request.titleUrl}"

private fun detailRelatedSection(relation: String): String = when (relation) {
    "前传" -> "前作"
    "续集" -> "后续"
    "主线故事", "番外篇", "衍生" -> "本篇与番外"
    "总集篇", "全集", "不同演绎" -> "其他版本"
    else -> "其他关联"
}

/** Use the current same-kind continuation; do not advance a completed watch into a known future release. */
private fun detailPlayEpisode(controller: AnimeController, state: AnimeScreenState, detail: AnimeDetail,
    progress: AnimeWatchProgress?): AnimeEpisode? {
    val subjectId = detail.subject.id
    val candidateId = controller.continuationEpisode(subjectId)?.id ?: return null
    val candidate = detail.episodes.firstOrNull { it.id == candidateId && it.subjectId == subjectId } ?: return null
    if (progress?.completed == true && candidate.id != progress.episodeId &&
        controller.episodeAvailability(candidate.id, state) == AnimeEpisodeAvailability.UPCOMING)
        return detail.episodes.firstOrNull { it.id == progress.episodeId && it.subjectId == subjectId }
    return candidate
}

private fun detailResumeCheckpoint(state: AnimeScreenState, subjectId: String,
    saved: AnimeWatchProgress): AnimeWatchProgress? = state.episodeProgress[saved.episodeId]?.takeIf {
    it.subjectId == subjectId && it.updatedAt == saved.updatedAt && it.assetId == saved.assetId &&
        it.localDownloadId == saved.localDownloadId && it.completed == saved.completed && it.positionMs == saved.positionMs
}

/** Only the native per-episode checkpoint can reopen a proven offline target. */
private fun detailOfflineProgress(state: AnimeScreenState, subjectId: String, episode: AnimeEpisode?,
    previous: AnimeWatchProgress?): AnimeWatchProgress? {
    if (previous == null) return null
    val progress = if (!previous.completed && (episode == null || episode.id == previous.episodeId))
        detailResumeCheckpoint(state, subjectId, previous)
        else episode?.let { state.episodeProgress[it.id]?.takeIf { row -> row.subjectId == subjectId } }
    return progress?.takeIf { it.localDownloadId != null }
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
    forComments: Boolean,
    automaticPending: Boolean,
    automaticError: String?,
    onRetryAutomatic: () -> Unit,
) {
    item(key = "native-metadata") {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimeSectionTitle(
                if (forComments) "尚未关联作品评论" else "补充资料",
                if (forComments) "确认同一部番剧和季度后，可读取 Bangumi 等来源的作品评论。"
                else linked?.let { listOfNotNull(it.title, it.year?.toString()).joinToString(" · ") },
            ) {
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
            if (search == null) {
                if (automaticPending) AnimeDetailStatus("正在补充作品资料", Icons.Outlined.Search, loading = true)
                else if (automaticError != null) AnimeDiscoveryInlineStatus(automaticError,
                    retry = onRetryAutomatic.takeIf { active })
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
    wide: Boolean,
    compact: Boolean,
    active: Boolean,
    backdrop: HazeState,
    contentTopPadding: Dp,
    followAction: @Composable () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val colors = MaterialTheme.colorScheme
    val facts = listOfNotNull(
        (subject.year ?: metadata?.year)?.toString(),
        when (subject.format ?: metadata?.format) {
            AnimeDiscoveryFormat.SERIES -> "剧集"
            AnimeDiscoveryFormat.MOVIE -> "剧场版"
            AnimeDiscoveryFormat.SPECIAL -> "特别篇"
            AnimeDiscoveryFormat.SHORT -> "短片"
            null -> null
        },
        (subject.episodeCount ?: metadata?.episodeCount)?.takeIf { it > 0 }?.let { "$it 集" },
    ).joinToString(" · ")
    val alternateTitle = listOfNotNull(subject.originalTitle, metadata?.originalTitle, metadata?.title)
        .firstOrNull { it.isNotBlank() && it != subject.title }
    val artwork = metadata?.takeIf { subject.coverUrl.isBlank() && it.coverUrl.isNotBlank() } ?: subject
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val ratings = LocalAnimeRatings.current
    val openRatings = { ratings.open(AnimeRatingRequest(subject.id, subject.title, subject, scores)) }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(if (wide) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp))) {
        val gutter = if (compact) 12.dp else 18.dp
        val bottomGutter = if (compact) 6.dp else 14.dp
        val innerWidth = (maxWidth - gutter * 2).coerceAtLeast(1.dp)
        val stacked = wide && !compact || innerWidth < 245.dp * density.fontScale.coerceAtLeast(1f)
        val posterWidth = when {
            stacked -> minOf(innerWidth * .58f, if (wide) 200.dp else 120.dp)
            compact -> minOf(innerWidth * .23f, 66.dp)
            else -> minOf(innerWidth * .34f, 128.dp)
        }
        val imageWidth = with(density) { (posterWidth.roundToPx() * 3).coerceIn(1, 1200) }
        val source = animePosterSourceId(artwork)
        var decoded by remember(source, artwork.coverUrl) { mutableStateOf<CoilImage?>(null) }
        val request = remember(context, artwork.coverUrl, source, imageWidth, motion) {
            ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(artwork.coverUrl, source)
                .softwareBitmap().size(imageWidth, imageWidth * 3 / 2).crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
            onSuccess = { decoded = it.result.image })
        val hue = rememberAnimePosterTint(artwork.coverUrl, source, decoded)
        Box(Modifier.matchParentSize().mangaHazeSource(backdrop).background(surface).drawWithCache {
            val x = gutter.toPx() + posterWidth.toPx() * .48f
            val origin = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - x else x, size.height * .36f)
            val radius = maxOf(size.width * .54f, size.height * .68f, 1f)
            val wash = hue?.let { Brush.radialGradient(
                0f to lerp(surface, it, .48f), .58f to lerp(surface, it, .20f),
                1f to surface.copy(alpha = 0f), center = origin, radius = radius) }
            onDrawBehind { if (wash != null) drawCircle(wash, radius, origin) }
        })
        val poster: @Composable () -> Unit = {
            Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).mangaCoverCard(12.dp, shadowRadius = 8.dp)
                .background(hue?.let { lerp(surface, it, .22f) } ?: colors.surfaceContainerLow), contentAlignment = Alignment.Center) {
                if (decoded == null) Icon(Icons.Outlined.Tv, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                if (artwork.coverUrl.isNotBlank()) Image(painter, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop))
            }
        }
        val information: @Composable (Modifier) -> Unit = { infoModifier ->
            Column(infoModifier, verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant)
                Text(subject.title, Modifier.semantics { heading() },
                    style = animeWorkTitleStyle(large = true).copy(fontSize = if (compact) 20.sp else 25.sp,
                        lineHeight = if (compact) 25.sp else 31.sp),
                    maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
                if (alternateTitle != null && !compact) Text(alternateTitle, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (scores.isNotEmpty()) AnimeRatingBadges(subject.id, scores, Modifier.fillMaxWidth(),
                    onExpand = openRatings.takeIf { active && ratings.active })
                else if (metadata != null || subject.canonicalDoubanId() != null) MotionTextButton(
                    onClick = openRatings, enabled = active && ratings.active,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.StarOutline, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("各站评分", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = gutter, end = gutter,
            top = contentTopPadding + if (compact) 6.dp else 18.dp, bottom = bottomGutter),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 14.dp)) {
            if (stacked) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { poster() }
                information(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) { poster(); information(Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { followAction() }
        }
    }
}

@Composable
private fun AnimeDetailLineRow(
    episode: AnimeEpisode?,
    availability: AnimeEpisodeAvailability?,
    lineCount: Int,
    canRetry: Boolean,
    lastSource: Pair<String, String>?,
    looking: Boolean,
    hasIssues: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val caption = when (availability) {
        AnimeEpisodeAvailability.MATCHED -> "自动选线 · $lineCount 条"
        AnimeEpisodeAvailability.CHECKING -> "正在找线路"
        AnimeEpisodeAvailability.UNMATCHED -> "暂无匹配线路"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> if (canRetry) "播放失败 · 可重试" else "线路暂不可用"
        AnimeEpisodeAvailability.UPCOMING -> episode?.let { upcomingEpisodeLabel(it, fullDate = true) } ?: "尚未放送"
        null -> when {
            looking -> "正在查找播放来源"
            hasIssues -> "部分来源暂不可用"
            else -> "查看作品来源"
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerLow)
            .clickable(enabled = active, role = Role.Button, interactionSource = interaction, indication = null,
                onClickLabel = "查看播放线路", onClick = onOpen)
            .motionPress(interaction, enabled = active)
            .heightIn(min = 50.dp).padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                if (lastSource != null) stateDescription = "上次使用 ${lastSource.second}"
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (availability == AnimeEpisodeAvailability.CHECKING || (availability == null && looking)) {
                MotionBusyIndicator(Modifier.size(16.dp), color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
            } else if (availability == AnimeEpisodeAvailability.MATCHED && lastSource != null) {
                AnimeSourceMark(lastSource.first, lastSource.second, size = 17.dp)
            } else Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(episode?.let { "${animeEpisodeLabel(it)}线路" } ?: "播放线路",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(caption, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        }
        if (episode != null) DetailAction(Icons.Outlined.Link, "管理作品来源", enabled = active, onClick = onManage)
    }
}

@Composable
private fun AnimeDetailRefreshNotice(error: String, backdrop: HazeState, active: Boolean, onRetry: () -> Unit) {
    var noticeOpen by remember(error) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) noticeOpen = false }
    Box {
        DetailAction(Icons.Outlined.CloudOff, "资料刷新失败，查看原因", enabled = active, onClick = { noticeOpen = true })
        CompactGlassPopover(noticeOpen && active, backdrop, onDismiss = { noticeOpen = false }) {
            AnimeQuietLabel(error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            CompactMenuAction(Icons.Outlined.Refresh, "重新加载资料", onClick = { noticeOpen = false; onRetry() })
        }
    }
}

@Composable
private fun AnimeEpisodeRanges(
    rows: List<List<AnimeEpisode>>,
    position: LazyListState,
    prefix: Int,
    active: Boolean,
    horizontal: Dp,
) {
    val groupHeight = 6
    val scope = rememberCoroutineScope()
    val ranges = remember(rows) { rows.chunked(groupHeight) }
    val selected by remember(position, prefix) {
        derivedStateOf {
            val lastRangeReached = position.canScrollBackward && !position.canScrollForward
            val visibleIndex = if (lastRangeReached) position.layoutInfo.totalItemsCount - 1 else position.firstVisibleItemIndex
            (visibleIndex - prefix).coerceAtLeast(0) / groupHeight
        }
    }
    val rangePosition = rememberLazyListState()
    LaunchedEffect(selected, ranges.size, active) {
        if (active && selected in ranges.indices &&
            rangePosition.layoutInfo.visibleItemsInfo.none { it.index == selected }) {
            rangePosition.scrollToItem(selected)
        }
    }
    LazyRow(Modifier.fillMaxWidth().motionInputGate(active), state = rangePosition,
        contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(ranges, key = { _, range -> range.first().first().id }) { index, range ->
            val first = range.first().first()
            val last = range.last().last()
            val label = if (first.id == last.id) animeEpisodeNumber(first.number)
                else "${animeEpisodeNumber(first.number)}–${animeEpisodeNumber(last.number)}"
            val interaction = remember { MutableInteractionSource() }
            val colors = MaterialTheme.colorScheme
            val current = index == selected
            Box(Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (current) colors.primaryContainer else Color.Transparent)
                .clickable(enabled = active, role = Role.Button, onClickLabel = "跳到第 $label 集",
                    interactionSource = interaction, indication = null,
                    onClick = { scope.launch { position.scrollToItem(prefix + index * groupHeight) } })
                .motionPress(interaction, enabled = active).heightIn(min = 44.dp)
                .semantics { this.selected = current }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant)
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
    val motion = LocalMotionEnabled.current
    val touchExploration = LocalTouchExplorationEnabled.current
    val feedback = rememberFeelFeedback()
    val fill = animateFloatAsState(if (followed) 1f else 0f,
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
        label = "anime-follow-bookmark")
    Box {
        LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.ARTWORK,
            contentColor = MaterialTheme.colorScheme.onSurface, trackPointer = active && libraryAvailable,
            preferSource = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val followLabel = if (followed) "取消追番" else "加入追番"
                MotionTextButton(onClick = {
                    if (motion && !touchExploration) feedback.perform(FeelEvent.Selection)
                    onFollow()
                }, enabled = libraryAvailable && active,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = followLabel
                        stateDescription = if (followed) "已追番" else "未追番"
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                    Box(Modifier.size(17.dp).graphicsLayer {
                        val amount = fill.value.coerceIn(0f, 1f)
                        translationY = 2.dp.toPx() * 4f * amount * (1f - amount)
                    }) {
                        Icon(Icons.Outlined.BookmarkBorder, null, Modifier.matchParentSize())
                        Icon(Icons.Filled.Bookmark, null, Modifier.matchParentSize().drawWithContent {
                            val edge = size.height * fill.value.coerceIn(0f, 1f)
                            if (edge > 0f) clipRect(bottom = edge) { this@drawWithContent.drawContent() }
                        }, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(4.dp))
                    val measure = rememberTextMeasurer()
                    val style = LocalTextStyle.current
                    val density = LocalDensity.current
                    val labelWidth = remember(measure, style, density) {
                        with(density) { measure.measure("已追番", style, maxLines = 1).size.width.toDp() }
                    }
                    Text(if (followed) "已追番" else "追番", Modifier.widthIn(min = labelWidth), maxLines = 1)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDetailDescription(subjectId: String, description: String, tagOwner: AnimeSubject?,
    active: Boolean, onTag: (AnimeSubject, String) -> Unit) {
    val tags = remember(tagOwner?.tags) { tagOwner?.tags.orEmpty().map(String::trim).filter(String::isNotBlank).distinct() }
    var allTags by rememberSaveable(subjectId, tagOwner?.id) { mutableStateOf(false) }
    val duration = if (LocalMotionEnabled.current) MangaMotion.Content else 0
    val tag: @Composable (Int, String, Boolean) -> Unit = { index, label, enabled ->
        DetailTag(label, index, enabled = enabled, largeTouchTarget = true,
            onClickLabel = "搜索${label}番剧", onClick = tagOwner?.let { owner -> ({ onTag(owner, label) }) })
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tags.isNotEmpty()) Column {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.take(8).forEachIndexed { index, label -> key(label) { tag(index, label, active) } }
                if (tags.size > 8) DetailTag(if (allTags) "收起" else "+${tags.size - 8}", tags.size,
                    enabled = active, largeTouchTarget = true,
                    onClickLabel = if (allTags) "收起更多标签" else "展开全部标签", onClick = { allTags = !allTags })
            }
            AnimatedVisibility(allTags && tags.size > 8,
                enter = expandVertically(tween(duration)) + fadeIn(tween(duration)),
                exit = shrinkVertically(tween(duration)) + fadeOut(tween(duration))) {
                FlowRow(Modifier.fillMaxWidth().motionInputGate(active && allTags),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.drop(8).forEachIndexed { index, label -> key(label) { tag(index + 8, label, active && allTags) } }
                }
            }
        }
        if (description.isNotBlank()) {
            Text("剧情简介", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface)
        }
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
    val motion = LocalMotionEnabled.current
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (loading) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
            else Icon(icon, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            Text(title, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            if (!detail.isNullOrBlank()) {
                val label = if (expanded) "收起原因" else "查看原因"
                val angle = animateFloatAsState(if (expanded) 180f else 0f,
                    if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                    label = "anime-detail-disclosure")
                MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp), tooltip = label) {
                    Icon(Icons.Outlined.ExpandMore, label, Modifier.size(18.dp).graphicsLayer { rotationZ = angle.value })
                }
            }
            if (actionLabel != null) {
                MotionIconButton(onClick = onAction, modifier = Modifier.size(44.dp), tooltip = actionLabel) {
                    Icon(actionIcon, actionLabel, Modifier.size(18.dp))
                }
            }
        }
        if (!detail.isNullOrBlank()) {
            AnimatedVisibility(expanded, modifier = Modifier.motionInputGate(expanded),
                enter = expandVertically(if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                    expandFrom = Alignment.Top),
                exit = shrinkVertically(if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                    shrinkTowards = Alignment.Top)) {
                Text(detail, Modifier.padding(start = 24.dp, end = 8.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun upcomingEpisodeLabel(episode: AnimeEpisode, fullDate: Boolean = false): String {
    val date = episode.airDate?.takeIf { it.isNotBlank() } ?: return "尚未放送"
    return "预计 ${if (fullDate) date else date.substringAfter('-')}"
}

@Composable
private fun AnimeEpisodeCell(
    episode: AnimeEpisode,
    completed: Boolean,
    resume: Boolean,
    progress: AnimeWatchProgress?,
    availability: AnimeEpisodeAvailability,
    sources: () -> AnimeEpisodeSources,
    backdrop: HazeState,
    active: Boolean,
    libraryAvailable: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onCompleted: () -> Unit,
    onMenuPresenceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listRow: Boolean = false,
) {
    var menuVisible by remember(episode.id) { mutableStateOf(false) }
    var menuMounted by remember(episode.id) { mutableStateOf(false) }
    var sourcesAfterMenu by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { menuVisible = false; sourcesAfterMenu = false } }
    Box(modifier) {
        val openMenu = { menuMounted = true; menuVisible = true }
        if (listRow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimeEpisodeTile(episode, availability, progress, completed, "上次观看".takeIf { resume },
                active, onPlay, onSources, modifier = Modifier.weight(1f), listRow = true, onMore = openMenu, titleFirst = true)
            MotionIconButton(onClick = openMenu, enabled = active, modifier = Modifier.size(38.dp), tooltip = "本集操作") {
                Icon(Icons.Outlined.MoreHoriz, "本集操作", Modifier.size(18.dp))
            }
        } else AnimeEpisodeTile(episode, availability, progress, completed, "上次观看".takeIf { resume },
            active, onPlay, onSources, onMore = openMenu)
        if (menuMounted) CompactGlassPopover(menuVisible, backdrop,
            onDismiss = { menuVisible = false; sourcesAfterMenu = false },
            onPresenceChange = { present ->
                onMenuPresenceChange(present)
                if (!present && !menuVisible) {
                    menuMounted = false
                    if (sourcesAfterMenu) { sourcesAfterMenu = false; if (active) onSources() }
                }
            }) {
            val options = sources()
            val openLines = { sourcesAfterMenu = true; menuVisible = false }
            val label = listOfNotNull(animeEpisodeLabel(episode), animeEpisodeSubtitle(episode)).joinToString(" · ")
            AnimeQuietLabel(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            if (options.sources.isNotEmpty()) {
                AnimeEpisodeSourceBadges(options, Modifier.padding(horizontal = 10.dp).fillMaxWidth(),
                    enabled = active, onOpen = openLines)
            }
            val status = when {
                options.lineCount > 0 -> "${options.lineCount} 条线路"
                availability == AnimeEpisodeAvailability.UPCOMING -> upcomingEpisodeLabel(episode, fullDate = true)
                else -> null
            }
            AnimeQuietLabel(listOfNotNull(animeWatchProgressDescription(progress, completed), status).joinToString(" · "),
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            CompactMenuAction(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (completed) "取消已看" else "标记已看", enabled = libraryAvailable,
                onClick = { menuVisible = false; onCompleted() })
            CompactMenuAction(Icons.Outlined.SwapHoriz, "本集播放线路", onClick = openLines)
        }
    }
}
