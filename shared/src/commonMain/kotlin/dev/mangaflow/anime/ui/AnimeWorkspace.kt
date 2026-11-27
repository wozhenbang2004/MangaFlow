package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.platform.LocalWindowFullscreen
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.flow.collect

private data class AnimeRoute(
    val key: String,
    val depth: Int,
    val detailState: AnimeScreenState? = null,
    val relatedDepth: Int = 0,
)
private data class AnimeDetailVisit(
    val detail: AnimeDetail, val related: AnimeRelatedState,
    val candidates: List<AnimeSourceTitle>, val scores: List<AnimeSourceScore>,
    val tagQuery: AnimeDiscoveryQuery? = null,
)
private data class AnimeDetailSearchOrigin(
    val subjectId: String, val query: String, val tagMode: Boolean, val tagDraft: String,
    val choosingTags: Boolean, val candidate: AnimeSourceTitle?, val tagQuery: AnimeDiscoveryQuery?,
)
private data class AnimeSourceAssociation(val title: AnimeSourceTitle, val subjectId: String)
private data class AnimeSearchOpening(val group: AnimeSearchGroup, val version: AnimeSourceTitle?)

/** Display enrichment is harmless; work/edition evidence and native ownership must stay exact. */
private fun AnimeSourceTitle.sameSearchReference(other: AnimeSourceTitle): Boolean =
    sourceId == other.sourceId && id == other.id && url == other.url && sourceRevision == other.sourceRevision &&
        title == other.title && year == other.year && episodeCount == other.episodeCount && externalIds == other.externalIds

private fun AnimeUnifiedSearchState.currentReferences(group: AnimeSearchGroup): List<AnimeSourceTitle> {
    val live = sources.associateBy { it.providerId }
    // Source rows can already contain new artwork or a rating while the background group still holds the displayed row.
    return group.sources.mapNotNull { shown -> live[shown.sourceId]?.items?.singleOrNull { it.sameSearchReference(shown) } }
}

internal enum class AnimeWorkspaceDestination(val label: String, val icon: ImageVector) {
    HOME("发现", Icons.Outlined.Explore), LIBRARY("追番", Icons.Outlined.Bookmarks),
    HISTORY("观看历史", Icons.Outlined.History), RANKING("榜单", Icons.Outlined.Leaderboard),
    SEASON("新番", Icons.Outlined.NewReleases), CALENDAR("放送", Icons.Outlined.CalendarMonth),
    GENRES("标签", Icons.AutoMirrored.Outlined.Label), DIRECTORY("片源目录", Icons.Outlined.Public),
    DOWNLOADS("离线下载", Icons.Outlined.DownloadForOffline), TRACKING("追番账号", Icons.Outlined.AccountCircle),
    SOURCES("管理片源", Icons.Outlined.Tune),
}
private val animePrimaryLabels = listOf(AnimeWorkspaceDestination.HOME.label, AnimeWorkspaceDestination.CALENDAR.label)
private val animePrimaryTypeSize = 13.sp

private enum class AnimeWorkspacePage(val discovery: AnimeDiscoveryTab? = null) {
    HOME(AnimeDiscoveryTab.HOME), SEASON(AnimeDiscoveryTab.SEASON),
    CALENDAR(AnimeDiscoveryTab.CALENDAR), RANKING(AnimeDiscoveryTab.RANKING),
    LIBRARY,
}
private enum class AnimeWorkspacePane { DISCOVERY, DIRECTORY, LIBRARY }
private fun AnimeDiscoveryTab.isBroadcastTab(): Boolean =
    this == AnimeDiscoveryTab.CALENDAR || this == AnimeDiscoveryTab.SEASON

private sealed interface AnimeRecentChoice {
    data class Resume(val subjectId: String) : AnimeRecentChoice
    data object FullHistory : AnimeRecentChoice
}

/** The root owns one controller/player. Pane identity never follows the player's ticking position. */
@Composable
internal fun AnimeWorkspace(
    controller: AnimeController,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    navigationVisible: Boolean = true,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    searchRequest: Int = 0,
    backRequest: Int = 0,
    browseRequest: Int = 0,
    onBrowseRequestConsumed: () -> Unit = {},
    onPlaybackVisibility: (Boolean) -> Unit = {},
    requestedDestination: AnimeWorkspaceDestination = AnimeWorkspaceDestination.HOME,
    navigationRequest: Int = 0,
    onDestinationChange: (AnimeWorkspaceDestination) -> Unit = {},
    onOpenMangaDownloads: () -> Unit = {},
) {
    AnimeRatingHost(controller, backdrop, active,
        listOf(navigationRequest, searchRequest, backRequest, requestedDestination)) {
        AnimeWorkspaceContent(controller, backdrop, navigation, navigationVisible, modifier, active,
            searchRequest, backRequest, browseRequest, onBrowseRequestConsumed, onPlaybackVisibility,
            requestedDestination, navigationRequest, onDestinationChange, onOpenMangaDownloads)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeWorkspaceContent(
    controller: AnimeController,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    navigationVisible: Boolean = true,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    searchRequest: Int = 0,
    backRequest: Int = 0,
    browseRequest: Int = 0,
    onBrowseRequestConsumed: () -> Unit = {},
    onPlaybackVisibility: (Boolean) -> Unit = {},
    requestedDestination: AnimeWorkspaceDestination = AnimeWorkspaceDestination.HOME,
    navigationRequest: Int = 0,
    onDestinationChange: (AnimeWorkspaceDestination) -> Unit = {},
    onOpenMangaDownloads: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playbackPhase by controller.playbackPhase.collectAsStateWithLifecycle()
    val sourceBrowseState by controller.sourceBrowse.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var directoryOpen by rememberSaveable { mutableStateOf(false) }
    var lastBroadcastTab by rememberSaveable { mutableStateOf(AnimeDiscoveryTab.CALENDAR.name) }
    var consumedNavigationRequest by rememberSaveable { mutableIntStateOf(0) }
    val page = if (tab == 1) AnimeWorkspacePage.LIBRARY else
        AnimeWorkspacePage.entries.firstOrNull { it.discovery == state.discoveryTab } ?: AnimeWorkspacePage.HOME
    val pane = when {
        directoryOpen -> AnimeWorkspacePane.DIRECTORY
        tab == 1 -> AnimeWorkspacePane.LIBRARY
        else -> AnimeWorkspacePane.DISCOVERY
    }
    val discoveryScrolls = remember { AnimeDiscoveryScrolls() }
    val libraryScroll = rememberLazyListState()
    val historyScroll = rememberLazyListState()
    val libraryViewState = rememberAnimeLibraryViewState()
    val baseDestination = when {
        directoryOpen -> AnimeWorkspaceDestination.DIRECTORY
        tab == 1 -> if (libraryViewState.filter == 2) AnimeWorkspaceDestination.HISTORY else AnimeWorkspaceDestination.LIBRARY
        else -> when (page) {
            AnimeWorkspacePage.HOME -> AnimeWorkspaceDestination.HOME
            AnimeWorkspacePage.SEASON, AnimeWorkspacePage.CALENDAR -> AnimeWorkspaceDestination.CALENDAR
            AnimeWorkspacePage.RANKING -> AnimeWorkspaceDestination.RANKING
            AnimeWorkspacePage.LIBRARY -> AnimeWorkspaceDestination.LIBRARY
        }
    }
    val directoryDetailOpen = directoryOpen && sourceBrowseState.selectedTitle != null
    val detailState = rememberSaveableStateHolder()
    val detailScrolls = remember { mutableMapOf<String, LazyListState>() }
    val detailHistory = remember(controller) { mutableStateListOf<AnimeDetailVisit>() }
    val searchScroll = rememberLazyListState()
    val search by controller.searchState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(controller.searchState.value.query) }
    var tagSearchMode by rememberSaveable { mutableStateOf(false) }
    var tagDraft by rememberSaveable { mutableStateOf("") }
    var tagChoicesOpen by rememberSaveable { mutableStateOf(false) }
    var consumedSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    var consumedBackRequest by rememberSaveable { mutableIntStateOf(0) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var followSearchStart by rememberSaveable { mutableStateOf(true) }
    var returnToSearch by rememberSaveable { mutableStateOf(false) }
    var focusSearchInput by rememberSaveable { mutableStateOf(true) }
    var searchPresent by remember { mutableStateOf(false) }
    var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
    var sourcesOverVideo by remember { mutableStateOf(false) }
    var sourceManagerVisible by remember { mutableStateOf(false) }
    var downloadsVisible by remember { mutableStateOf(false) }
    var storageVisible by remember { mutableStateOf(false) }
    var storagePresent by remember { mutableStateOf(false) }
    var storageModelsVisible by remember { mutableStateOf(false) }
    var storageDestination by remember { mutableStateOf<String?>(null) }
    var browseCandidate by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    var detailSearchOrigin by remember(controller) { mutableStateOf<AnimeDetailSearchOrigin?>(null) }
    var pendingSearchRestore by remember(controller) { mutableStateOf<AnimeDetailSearchOrigin?>(null) }
    val tagSearch = tagSearchMode && browseCandidate == null
    val choosingTags = tagChoicesOpen || tagDraft.isNotBlank()
    var searchVersionChoice by remember { mutableStateOf<AnimeSearchGroup?>(null) }
    var searchProgressVisible by remember(active, searchExpanded, search.query, search.indexOnly, search.catalogProvider) {
        mutableStateOf(false)
    }
    var pendingAssociation by remember { mutableStateOf<AnimeSourceAssociation?>(null) }
    var sourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var trackingVisible by remember { mutableStateOf(false) }
    var reviewsVisible by remember { mutableStateOf(false) }
    var recentWatchingOpen by remember { mutableStateOf(false) }
    var pendingRecentChoice by remember { mutableStateOf<AnimeRecentChoice?>(null) }
    var actionSubject by remember { mutableStateOf<AnimeSubject?>(null) }
    var pendingWorkAction by remember { mutableStateOf<AnimeWorkAction?>(null) }
    val ratingUi = LocalAnimeRatings.current
    val snackbar = remember { SnackbarHostState() }
    var openingSubject by remember { mutableStateOf<AnimeSubject?>(null) }
    var openingReference by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    var openingSearch by remember { mutableStateOf<AnimeSearchOpening?>(null) }
    val windowFullscreen = LocalWindowFullscreen.current
    var layoutFullscreen by rememberSaveable { mutableStateOf(false) }
    val playerFullscreen = windowFullscreen?.active ?: layoutFullscreen
    fun setPlayerFullscreen(enabled: Boolean) {
        if (windowFullscreen != null) windowFullscreen.setActive(enabled)
        else layoutFullscreen = enabled
    }
    var recheckRequest by remember { mutableStateOf<AnimeTrackingReview?>(null) }
    val playing = state.playbackTarget != null || state.resolving ||
        playbackPhase != AnimePlayerPhase.IDLE && playbackPhase != AnimePlayerPhase.RELEASED
    var playbackBrowseVisit by remember(controller) { mutableStateOf<AnimePlayerBrowseVisit?>(null) }
    var playbackBrowseRequest by remember(controller) { mutableIntStateOf(0) }
    val browseVisit = playbackBrowseVisit?.takeIf { active && playing && it.subjectId == state.detail?.subject?.id }
    fun closePlaybackBrowse(visit: AnimePlayerBrowseVisit) {
        if (playbackBrowseVisit === visit) {
            playbackBrowseVisit = null
            playbackBrowseRequest = 0
        }
    }
    LaunchedEffect(active, playing, state.detail?.subject?.id) {
        if (!active || !playing || playbackBrowseVisit != null && browseVisit == null) {
            playbackBrowseVisit = null
            playbackBrowseRequest = 0
        }
    }
    val route = when {
        playing -> AnimeRoute("player", 2, relatedDepth = detailHistory.size)
        state.detail != null || state.detailLoading || state.detailError != null ->
            AnimeRoute("detail:" + (state.detail?.subject?.id ?: openingSubject?.id.orEmpty()), 1, state, detailHistory.size)
        else -> AnimeRoute("home", 0)
    }
    val searchingDetail = route.depth == 1 && detailSearchOrigin?.subjectId == state.detail?.subject?.id &&
        detailSearchOrigin != null
    val searchRouteActive = route.depth == 0 || searchingDetail
    val overlayVisible = downloadsVisible || sourcesVisible || sourceManagerVisible || trackingVisible || reviewsVisible ||
        storagePresent || storageModelsVisible || ratingUi.obscured || recentWatchingOpen || actionSubject != null ||
        searchVersionChoice != null || searchProgressVisible
    val displayedDestination = when {
        sourceManagerVisible -> AnimeWorkspaceDestination.SOURCES
        downloadsVisible -> AnimeWorkspaceDestination.DOWNLOADS
        trackingVisible -> AnimeWorkspaceDestination.TRACKING
        else -> baseDestination
    }
    val currentDestinationChange by rememberUpdatedState(onDestinationChange)
    LaunchedEffect(active, displayedDestination, navigationRequest, consumedNavigationRequest) {
        if (active && navigationRequest == consumedNavigationRequest) currentDestinationChange(displayedDestination)
    }
    val rootActive = active && route.depth == 0 && !searchPresent && !overlayVisible
    LaunchedEffect(active, route.key, page, directoryOpen) {
        searchVersionChoice = null
        searchProgressVisible = false
        recentWatchingOpen = false
        pendingRecentChoice = null
        actionSubject = null
        pendingWorkAction = null
        if (!active) { storageVisible = false; storageModelsVisible = false; storageDestination = null }
    }
    LaunchedEffect(active, state.notice) {
        val message = state.notice
        if (!active || message == null) {
            snackbar.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
        if (controller.state.value.notice == message) controller.dismissNotice()
    }
    val currentPlaybackVisibility by rememberUpdatedState(onPlaybackVisibility)
    LaunchedEffect(playing) { currentPlaybackVisibility(playing) }
    DisposableEffect(controller) {
        onDispose { currentPlaybackVisibility(false) }
    }
    val reportWindowPlayback = windowFullscreen?.onPlaybackVisibilityChange
    DisposableEffect(reportWindowPlayback, playing) {
        if (playing) reportWindowPlayback?.invoke(true)
        onDispose { if (playing) reportWindowPlayback?.invoke(false) }
    }
    val discoveryVisible = rootActive && !searchExpanded && pane == AnimeWorkspacePane.DISCOVERY
    val tagResultsVisible = active && searchRouteActive && searchExpanded && tagSearch && !choosingTags &&
        !overlayVisible
    DisposableEffect(controller, discoveryVisible, tagResultsVisible) {
        if (discoveryVisible || tagResultsVisible) controller.setDiscoveryVisible(discoveryVisible, showTags = tagResultsVisible)
        onDispose { if (discoveryVisible || tagResultsVisible) controller.setDiscoveryVisible(false) }
    }
    LaunchedEffect(controller, directoryOpen) {
        if (directoryOpen) controller.sourceBrowse.activate()
    }
    LaunchedEffect(browseRequest) {
        if (browseRequest != 0) {
            layoutFullscreen = false
            pendingAssociation = null
            browseCandidate = null
            tab = 0
            directoryOpen = false
            searchExpanded = false
            detailSearchOrigin = null
            pendingSearchRestore = null
            returnToSearch = false
            detailHistory.clear()
            sourcesVisible = false
            sourceManagerVisible = false
            downloadsVisible = false
            trackingVisible = false
            reviewsVisible = false
            onBrowseRequestConsumed()
        }
    }
    LaunchedEffect(state.discoveryTab) {
        val selected = state.discoveryTab
        if (selected.isBroadcastTab()) lastBroadcastTab = selected.name
        else if (selected == AnimeDiscoveryTab.SOURCES || selected == AnimeDiscoveryTab.GENRES)
            controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME)
    }
    LaunchedEffect(searchRequest, active) {
        if (active && searchRequest != consumedSearchRequest) {
            consumedSearchRequest = searchRequest
            val live = controller.state.value
            val livePhase = controller.playbackPhase.value
            if (platformKind == PlatformKind.DESKTOP && (playing || live.resolving || live.playbackTarget != null ||
                    livePhase != AnimePlayerPhase.IDLE && livePhase != AnimePlayerPhase.RELEASED)) {
                // Desktop search can browse beside the same video; it must not close it.
                playbackBrowseRequest = searchRequest
                return@LaunchedEffect
            }
            layoutFullscreen = false
            controller.closePlayback()
            controller.closeDetail()
            sourcesVisible = false
            sourceManagerVisible = false
            downloadsVisible = false
            browseCandidate = null
            pendingAssociation = null
            trackingVisible = false
            reviewsVisible = false
            directoryOpen = false
            detailSearchOrigin = null
            pendingSearchRestore = null
            returnToSearch = false
            detailHistory.clear()
            tagSearchMode = false
            focusSearchInput = true
            searchExpanded = true
        }
    }
    LaunchedEffect(state.detail?.subject?.id) {
        sourceEpisode = null
        sourcesVisible = false
    }

    fun closePlayback() {
        layoutFullscreen = false
        playbackBrowseVisit = null
        playbackBrowseRequest = 0
        controller.closePlayback()
    }
    fun submitSearch(term: String) {
        val next = term.trim()
        val matchingReference = browseCandidate != null
        val provider = if (matchingReference) AnimeDiscoveryProvider.BANGUMI else AnimeDiscoveryProvider.DOUBAN
        val previous = controller.searchState.value
        if (previous.query != next || previous.indexOnly != matchingReference || previous.catalogProvider != provider) {
            followSearchStart = true
            searchScroll.requestScrollToItem(0)
        }
        controller.search(next, indexOnly = matchingReference, provider = provider)
    }
    fun openTagSearch() {
        tagSearchMode = true
        focusSearchInput = false
        searchExpanded = true
    }
    fun closeSearch() {
        searchExpanded = false
        focusSearchInput = false
        val origin = detailSearchOrigin
        if (origin != null) {
            pendingSearchRestore = origin
            detailSearchOrigin = null
        } else browseCandidate = null
    }
    // Keep the tag results intact through the shell's exit, then restore the outer search.
    LaunchedEffect(searchPresent, searchExpanded, pendingSearchRestore) {
        val previous = pendingSearchRestore
        if (!searchPresent && !searchExpanded && previous != null) {
            pendingSearchRestore = null
            query = previous.query
            tagSearchMode = previous.tagMode
            tagDraft = previous.tagDraft
            tagChoicesOpen = previous.choosingTags
            browseCandidate = previous.candidate
            previous.tagQuery?.let(controller::configureDiscoveryTags)
        }
    }
    fun openDetailTagSearch(subjectId: String, next: AnimeDiscoveryQuery) {
        if (!active || playing || controller.state.value.detail?.subject?.id != subjectId) return
        val previous = controller.state.value.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.GENRES)]?.query
        if (detailSearchOrigin == null) detailSearchOrigin = AnimeDetailSearchOrigin(subjectId, query,
            tagSearchMode, tagDraft, tagChoicesOpen || tagSearchMode && previous == null, browseCandidate, previous)
        controller.configureDiscoveryTags(next)
        browseCandidate = null
        tagDraft = ""
        tagChoicesOpen = false
        openTagSearch()
    }
    fun searchDetailTag(subjectId: String, owner: AnimeSubject, label: String) {
        val nativeTag = animeDiscoveryTagInput(AnimeDiscoveryProvider.DOUBAN, label)
            .takeIf { owner.canonicalDoubanId() != null }
        // Unsupported catalogue types remain literal tag searches in Bangumi's free-tag catalogue.
        // This chooses a search destination; it never links or changes the current work's identity.
        val provider = if (nativeTag != null) AnimeDiscoveryProvider.DOUBAN else AnimeDiscoveryProvider.BANGUMI
        val tag = nativeTag ?: animeDiscoveryTagInput(provider, label) ?: return
        openDetailTagSearch(subjectId, AnimeDiscoveryQuery(provider = provider, tag = tag))
    }
    fun beginDetailNavigation() {
        openingReference = null
        openingSearch = null
        val latest = controller.state.value
        val origin = detailSearchOrigin
        val current = latest.detail
        if (origin != null && current != null && current.subject.id == origin.subjectId) {
            detailHistory += AnimeDetailVisit(current, latest.related, latest.groupSourceCandidates,
                latest.detailScores, latest.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.GENRES)]?.query)
            closeSearch()
        }
        if (route.depth == 0) {
            detailHistory.clear()
            returnToSearch = searchExpanded
        }
        layoutFullscreen = false
        searchExpanded = false
    }
    fun open(subject: AnimeSubject) {
        if (pendingAssociation?.subjectId != subject.id) pendingAssociation = null
        beginDetailNavigation()
        openingSubject = subject
        controller.open(subject)
    }
    fun openRelated(subject: AnimeSubject) {
        val latest = controller.state.value
        val current = latest.detail ?: return
        if (current.subject.id == subject.id) return
        detailHistory += AnimeDetailVisit(current, latest.related, latest.groupSourceCandidates, latest.detailScores)
        open(subject)
    }
    fun openSourceRelated(request: AnimeRelatedSourceRequest, title: AnimeSourceTitle) {
        if (!active || !controller.canOpenDetailSourceRelated(request, title)) return
        val latest = controller.state.value
        val current = latest.detail?.takeIf { it.subject.id == request.subjectId } ?: return
        detailHistory += AnimeDetailVisit(current, latest.related, latest.groupSourceCandidates, latest.detailScores)
        pendingAssociation = null
        browseCandidate = null
        beginDetailNavigation()
        openingSubject = controller.sourceSubject(title)
        controller.openSourceTitle(title)
    }
    fun returnFromDetail() {
        pendingAssociation = null
        if (detailHistory.isNotEmpty()) {
            val previous = detailHistory.removeAt(detailHistory.lastIndex)
            openingSubject = previous.detail.subject
            controller.returnToVisitedDetail(previous.detail, previous.related, previous.candidates, previous.scores)
            previous.tagQuery?.let { openDetailTagSearch(previous.detail.subject.id, it) }
            val candidates = controller.searchState.value.groups
                .firstOrNull { returnToSearch && it.subject?.id == previous.detail.subject.id }?.sources.orEmpty()
            if (candidates.isNotEmpty()) controller.stageSearchCandidates(previous.detail.subject.id, candidates)
        } else {
            openingSubject = null
            openingSearch = null
            controller.closeDetail()
            if (returnToSearch) {
                focusSearchInput = false
                searchExpanded = true
                if (!tagSearchMode || browseCandidate != null) {
                    val current = controller.searchState.value
                    val indexOnly = browseCandidate != null
                    val provider = if (indexOnly) AnimeDiscoveryProvider.BANGUMI else AnimeDiscoveryProvider.DOUBAN
                    if (query.isNotBlank() && (current.query != query.trim() || current.indexOnly != indexOnly ||
                            current.catalogProvider != provider)) submitSearch(query)
                }
            }
            returnToSearch = false
        }
    }
    fun resume(entry: AnimeLibraryEntry) {
        pendingAssociation = null
        browseCandidate = null
        beginDetailNavigation()
        openingSubject = entry.subject
        controller.resume(entry.subject.id)
    }
    fun resumeEpisode(entry: AnimeLibraryEntry, progress: AnimeWatchProgress) {
        pendingAssociation = null
        browseCandidate = null
        beginDetailNavigation()
        openingSubject = entry.subject
        controller.resumeEpisode(entry.subject.id, progress.episodeId)
    }
    fun showWorkActions(subject: AnimeSubject) {
        if (!active || !searchRouteActive || actionSubject != null) return
        focusSearchInput = false
        pendingWorkAction = null
        actionSubject = subject
    }
    fun completeWorkActions() {
        val subject = actionSubject
        val action = pendingWorkAction
        actionSubject = null
        pendingWorkAction = null
        if (!active || !searchRouteActive || subject == null || action == null) return
        when (action) {
            AnimeWorkAction.OPEN -> open(subject)
            AnimeWorkAction.FOLLOW -> controller.setFollowed(subject, true)
            AnimeWorkAction.UNFOLLOW -> controller.setFollowed(subject, false)
            AnimeWorkAction.RATINGS -> ratingUi.open(AnimeRatingRequest(subject.id, subject.title, subject,
                animeSubjectRatingSeeds(subject)))
        }
    }
    fun playDownload(selection: AnimeDownloadSelection) {
        downloadsVisible = false
        beginDetailNavigation()
        pendingAssociation = null
        browseCandidate = null
        openingSubject = controller.downloads.state.value.entries.firstOrNull { it.id == selection.downloadId }?.subject
        when {
            selection.originalOnly -> controller.playOriginalDownload(selection.downloadId)
            selection.versionId != null -> controller.playEnhancedDownload(selection.downloadId, selection.versionId)
            else -> controller.playDownload(selection.downloadId)
        }
    }
    fun chooseReference(title: AnimeSourceTitle, videoSource: Boolean) {
        if (!videoSource && title.sourceId in setOf("anime-douban", "anime-lanerc-douban")) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = null
            openingReference = title
            controller.openCatalogueReference(title)
            return
        }
        controller.knownDiscoverySubject(title, allowIndexIdentity = !videoSource)?.let { known ->
            browseCandidate = null
            pendingAssociation = if (videoSource && known.externalIds["native-source"] == null &&
                !controller.stillBound(known.id, title))
                AnimeSourceAssociation(title, known.id) else null
            open(known)
            return
        }
        if (videoSource) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = controller.sourceSubject(title)
            controller.openSourceTitle(title)
            return
        }
        browseCandidate = title
        query = title.title
        submitSearch(title.title)
        focusSearchInput = true
        searchExpanded = true
    }
    fun openDiscoveryItem(item: AnimeDiscoveryItem) {
        val subject = item.subject
        if (subject != null) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = subject
            controller.openDiscoveryItem(item)
        } else item.reference?.let { chooseReference(it, false) }
    }
    fun openSearchResult(visibleGroup: AnimeSearchGroup, chosenVersion: AnimeSourceTitle? = null) {
        if (!active || !searchExpanded || !searchRouteActive) return
        val current = controller.searchState.value
        if (current.query != query.trim() || current.indexOnly != (browseCandidate != null)) return
        val matched = when {
            chosenVersion != null -> current.groups.singleOrNull { group ->
                group.sources.any { it.sameSearchReference(chosenVersion) }
            }
            visibleGroup.subject != null -> current.groups.firstOrNull { it.subject?.id == visibleGroup.subject.id }
            else -> current.groups.firstOrNull { it.key == visibleGroup.key && it.sources.any { source ->
                visibleGroup.sources.any { source.sameSearchReference(it) }
            } }
        } ?: return
        val group = matched.copy(sources = current.currentReferences(matched))
        pendingAssociation = null
        if (current.indexOnly) {
            group.subject?.let(::open)
            return
        }
        val version = chosenVersion?.let { chosen ->
            controller.currentVideoCandidates(group.sources).firstOrNull { it.sameSearchReference(chosen) } ?: return
        }
        // Keep an explicitly chosen native version even if metadata arrived while the picker was closing.
        val requestedGroup = if (version == null) group else group.copy(subject = null)
        val primary = version ?: controller.searchOpeningReference(requestedGroup)
        val subject = primary?.let(controller::sourceSubject) ?: requestedGroup.subject
        if (subject == null) {
            if (controller.currentVideoCandidates(group.sources).isNotEmpty()) {
                focusSearchInput = false
                searchVersionChoice = group
            }
            return
        }
        beginDetailNavigation()
        openingSubject = subject
        openingSearch = AnimeSearchOpening(requestedGroup, primary)
        controller.openSearchGroup(requestedGroup, preferredReference = primary)
    }
    fun dismissSources() {
        sourcesVisible = false
        controller.dismissSourcePreview()
    }
    fun showSources(episode: AnimeEpisode? = null) {
        controller.cancelPendingResume()
        sourceEpisode = episode
        sourcesOverVideo = playing
        sourcesVisible = true
    }
    fun requestPlayback(episode: AnimeEpisode, option: AnimeEpisodeOption?) {
        if (option == null && controller.episodeAvailability(episode.id) == AnimeEpisodeAvailability.UPCOMING) {
            showSources(episode)
            return
        }
        controller.dismissNotice()
        controller.play(episode.id, option)
    }
    fun openDirectory(sourceId: String? = null) {
        controller.sourceBrowse.activate(sourceId)
        directoryOpen = true
    }
    fun selectDiscovery(destination: AnimeDiscoveryTab) {
        if (destination == AnimeDiscoveryTab.GENRES) { openTagSearch(); return }
        val current = controller.state.value.discoveryTab
        if (destination.isBroadcastTab()) lastBroadcastTab = destination.name
        else if (current.isBroadcastTab()) lastBroadcastTab = current.name
        directoryOpen = false
        tab = 0
        controller.selectDiscoveryTab(destination)
    }
    fun selectBroadcast() {
        val current = controller.state.value.discoveryTab
        val selected = current.takeIf { it.isBroadcastTab() } ?: when (lastBroadcastTab) {
            AnimeDiscoveryTab.SEASON.name -> AnimeDiscoveryTab.SEASON
            else -> AnimeDiscoveryTab.CALENDAR
        }
        selectDiscovery(selected)
    }
    fun selectLibrary(history: Boolean = false) {
        directoryOpen = false
        tab = 1
        if (history) libraryViewState.filter = 2
    }
    fun returnFromDirectory() {
        if (controller.sourceBrowse.state.value.selectedTitle != null) controller.sourceBrowse.closeTitle()
        else directoryOpen = false
    }
    fun back() {
        val liveBrowse = playbackBrowseVisit?.takeIf {
            active && it.subjectId == controller.state.value.detail?.subject?.id
        }
        when {
            sourceManagerVisible -> sourceManagerVisible = false
            downloadsVisible -> downloadsVisible = false
            sourcesVisible -> dismissSources()
            trackingVisible -> trackingVisible = false
            reviewsVisible -> reviewsVisible = false
            recentWatchingOpen -> { recentWatchingOpen = false; pendingRecentChoice = null }
            actionSubject != null -> { actionSubject = null; pendingWorkAction = null }
            searchVersionChoice != null -> searchVersionChoice = null
            searchProgressVisible -> searchProgressVisible = false
            searchExpanded -> closeSearch()
            pendingSearchRestore != null -> Unit
            playing && playerFullscreen -> setPlayerFullscreen(false)
            liveBrowse != null -> if (!liveBrowse.returnToResults()) closePlaybackBrowse(liveBrowse)
            playing -> closePlayback()
            route.depth == 1 -> returnFromDetail()
            directoryOpen -> returnFromDirectory()
            baseDestination != AnimeWorkspaceDestination.HOME -> selectDiscovery(AnimeDiscoveryTab.HOME)
        }
    }

    LaunchedEffect(navigationRequest, active) {
        if (!active || navigationRequest == 0 || navigationRequest == consumedNavigationRequest) return@LaunchedEffect
        consumedNavigationRequest = navigationRequest
        layoutFullscreen = false
        controller.closeDetail()
        pendingAssociation = null
        browseCandidate = null
        searchVersionChoice = null
        sourceEpisode = null
        openingSubject = null
        openingSearch = null
        recheckRequest = null
        searchExpanded = false
        detailSearchOrigin = null
        pendingSearchRestore = null
        returnToSearch = false
        detailHistory.clear()
        sourcesVisible = false
        sourceManagerVisible = false
        downloadsVisible = false
        trackingVisible = false
        reviewsVisible = false
        recentWatchingOpen = false
        pendingRecentChoice = null
        actionSubject = null
        pendingWorkAction = null
        when (requestedDestination) {
            AnimeWorkspaceDestination.DOWNLOADS -> downloadsVisible = true
            AnimeWorkspaceDestination.TRACKING -> trackingVisible = true
            AnimeWorkspaceDestination.SOURCES -> sourceManagerVisible = true
            AnimeWorkspaceDestination.GENRES -> {
                controller.sourceBrowse.closeTitle()
                openTagSearch()
            }
            AnimeWorkspaceDestination.DIRECTORY -> {
                controller.sourceBrowse.closeTitle()
                openDirectory()
            }
            AnimeWorkspaceDestination.LIBRARY, AnimeWorkspaceDestination.HISTORY -> {
                directoryOpen = false
                tab = 1
                libraryViewState.filter = if (requestedDestination == AnimeWorkspaceDestination.HISTORY) 2 else 1
            }
            AnimeWorkspaceDestination.HOME -> selectDiscovery(AnimeDiscoveryTab.HOME)
            AnimeWorkspaceDestination.CALENDAR -> selectBroadcast()
            AnimeWorkspaceDestination.SEASON -> selectDiscovery(AnimeDiscoveryTab.SEASON)
            AnimeWorkspaceDestination.RANKING -> selectDiscovery(AnimeDiscoveryTab.RANKING)
        }
    }

    LaunchedEffect(backRequest, active) {
        if (active && backRequest != consumedBackRequest) { consumedBackRequest = backRequest; back() }
    }
    LaunchedEffect(state.detail, state.detailLoading, state.detailError, recheckRequest) {
        val review = recheckRequest
        if (review != null && state.detail?.subject?.id == review.subjectId && !state.detailLoading) {
            recheckRequest = null
            if (state.detailError == null) controller.reviewTracking(review.providerId, review.remoteSubjectId)
        }
    }
    LaunchedEffect(state.detail?.subject?.id, state.detailLoading, pendingAssociation) {
        val association = pendingAssociation ?: return@LaunchedEffect
        if (state.detail?.subject?.id == association.subjectId && !state.detailLoading) {
            pendingAssociation = null
            sourceEpisode = null
            controller.previewSource(association.title)
            sourcesOverVideo = false
            sourcesVisible = true
        }
    }

    val canGoBack = route.depth > 0 || baseDestination != AnimeWorkspaceDestination.HOME
    MotionBackHandler(active && canGoBack && !overlayVisible && !searchExpanded && !searchPresent,
        state = null, onBack = ::back)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MotionTabContent(selected = route, contentKey = { it.key }, order = { it.depth + it.relatedDepth },
                modifier = Modifier.fillMaxSize()) { shown ->
                if (shown.depth == 2) {
                    AnimePlayerPane(controller, state, backdrop,
                        active = active && shown.key == route.key, fullscreen = playerFullscreen,
                        onFullscreenChange = ::setPlayerFullscreen,
                        obscured = overlayVisible || state.pendingPlayback != null,
                        onBack = ::closePlayback,
                        onRetry = controller::retryPlayback,
                        onKeepPlayback = controller::cancelPlaybackResolution,
                        onRestorePlayback = controller::restorePreviousPlayback,
                        onPlayDownload = ::playDownload,
                        onSources = ::showSources,
                        onEpisode = { requestPlayback(it, null) }, modifier = Modifier.fillMaxSize(),
                        onStorage = { storagePresent = true; storageVisible = true },
                        browseVisit = browseVisit, browseRequest = playbackBrowseRequest,
                        onBrowseOpen = { visit ->
                            if (active && shown.key == route.key && playbackBrowseVisit == null &&
                                controller.state.value.detail?.subject?.id == visit.subjectId) playbackBrowseVisit = visit
                        },
                        onBrowseClose = ::closePlaybackBrowse,
                        onBrowseRequestConsumed = { request ->
                            if (playbackBrowseRequest == request) playbackBrowseRequest = 0
                        })
                } else {
                    val shownState = shown.detailState
                    val shownDetail = shownState?.detail
                    var heroCollapsed by remember(shown.key) { mutableStateOf(false) }
                    AnimeHeaderViewport(header = {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val fromBroadcast = baseDestination == AnimeWorkspaceDestination.CALENDAR
                            val rankingPage = shown.depth == 0 && baseDestination == AnimeWorkspaceDestination.RANKING
                            val workspaceTabs = shown.depth == 0 &&
                                (baseDestination == AnimeWorkspaceDestination.HOME || fromBroadcast)
                            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                            val actionsWidth = 88.dp // Recent watching and the overlaid search trigger.
                            val headerRoom = maxWidth - 16.dp - actionsWidth - if (navigationVisible) 56.dp else 6.dp
                            val tabsWidth = headerRoom.coerceIn(112.dp * fontScale, 184.dp * fontScale)
                            val stackDesktopTabs = workspaceTabs && !navigationVisible && headerRoom < tabsWidth
                            val controlsAlpha = {
                                if (searchExpanded) 0f else 1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f)
                            }
                            val returnLabel = when {
                                shown.depth == 0 && directoryDetailOpen -> "返回片源目录"
                                rankingPage -> "返回发现"
                                shown.relatedDepth > 0 && detailHistory.lastOrNull()?.tagQuery != null -> "返回标签结果"
                                shown.relatedDepth > 0 -> "返回上一部番剧"
                                returnToSearch -> "返回搜索结果"
                                else -> "返回${baseDestination.label}"
                            }
                            val primaryTabs: @Composable () -> Unit = {
                                MotionTabs(if (fromBroadcast) 1 else 0, animePrimaryLabels,
                                    onSelect = { if (rootActive) when (it) {
                                        0 -> selectDiscovery(AnimeDiscoveryTab.HOME)
                                        1 -> selectBroadcast()
                                        else -> Unit
                                    } },
                                    modifier = Modifier.motionInputGate(rootActive),
                                    treatment = MotionTabTreatment.Capsule,
                                    selectionBackdrop = backdrop,
                                    selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
                                    controlHeight = 44.dp, fontSize = animePrimaryTypeSize)
                            }
                            Column {
                                AnimeWorkspaceHeader(root = shown.depth == 0 && !directoryDetailOpen && !rankingPage,
                                    title = if (shown.depth == 0) (if (directoryDetailOpen) sourceBrowseState.selectedTitle?.title.orEmpty()
                                        else "番剧 · ${baseDestination.label}") else
                                        shownDetail?.subject?.title ?: openingSubject?.title ?: "番剧详情",
                                    backdrop = backdrop, navigationVisible = navigationVisible,
                                    primary = primaryTabs.takeIf { workspaceTabs && !stackDesktopTabs },
                                    showTitle = shownDetail == null || heroCollapsed,
                                    tabsWidth = tabsWidth, actionsWidth = actionsWidth, controlsAlpha = controlsAlpha,
                                    navigation = {
                                        if (shown.depth == 0 && !directoryDetailOpen && !rankingPage) navigation()
                                        else MotionIconButton(onClick = {
                                            when {
                                                rankingPage -> if (rootActive) selectDiscovery(AnimeDiscoveryTab.HOME)
                                                shown.depth == 0 -> returnFromDirectory()
                                                else -> returnFromDetail()
                                            }
                                        }, enabled = active && shown.key == route.key && !searchExpanded && !searchPresent &&
                                            !overlayVisible && (!rankingPage || rootActive),
                                            modifier = Modifier.size(44.dp), tooltip = returnLabel) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, returnLabel, Modifier.size(20.dp))
                                        }
                                    },
                                    actions = if (shown.depth != 0 || directoryDetailOpen) null else ({
                                        Row(Modifier.heightIn(min = 44.dp)
                                            .motionInputGate(active && route.depth == 0 && !searchPresent && !overlayVisible),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            MotionIconButton(modifier = Modifier.size(44.dp), enabled = rootActive,
                                                tooltip = "最近观看", onClick = {
                                                    pendingRecentChoice = null
                                                    recentWatchingOpen = true
                                                }) {
                                                HistoryActionIcon(active = recentWatchingOpen, description = "最近观看",
                                                    modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                                            }
                                            // The rightmost slot matches ExpandingSearchBar's overlaid trigger.
                                            Spacer(Modifier.size(44.dp))
                                        }
                                    }))
                                if (stackDesktopTabs) HeaderGlassSurface(backdrop,
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                        .graphicsLayer { alpha = controlsAlpha() },
                                    quietShadow = true, ink = rememberBrowseTabInk(animePrimaryLabels, animePrimaryTypeSize, 0.dp)) {
                                    primaryTabs()
                                }
                            }
                        }
                    }) { headerInset ->
                        if (shown.depth == 0) {
                            MotionTabContent(pane, Modifier.fillMaxSize().motionInputGate(rootActive),
                                contentKey = { it }, order = { it.ordinal }) { shownPane ->
                                val paneActive = rootActive && shownPane == pane
                                when (shownPane) {
                                    AnimeWorkspacePane.DISCOVERY -> AnimeDiscovery(state, controller, backdrop, discoveryScrolls,
                                        onOpenItem = ::openDiscoveryItem,
                                        onManage = ::showWorkActions, active = paneActive,
                                        onResume = ::resume, onOpenHistory = { if (paneActive) selectLibrary(history = true) },
                                        onOpenSchedule = { if (paneActive) selectDiscovery(AnimeDiscoveryTab.CALENDAR) },
                                        onOpenRanking = { if (paneActive) selectDiscovery(AnimeDiscoveryTab.RANKING) },
                                        contentTopPadding = headerInset,
                                        contentBottomPadding = 32.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding())
                                    AnimeWorkspacePane.DIRECTORY -> Box(Modifier.fillMaxSize()
                                        .padding(top = headerInset).mangaHazeSource(backdrop)) {
                                        AnimeSourceBrowsePane(controller, backdrop, discoveryScrolls,
                                            columns = state.discoveryColumns, active = paneActive,
                                            onChooseTitle = ::chooseReference, contentBottomPadding = 24.dp)
                                    }
                                    AnimeWorkspacePane.LIBRARY -> Box(Modifier.fillMaxSize().padding(top = headerInset)) {
                                        AnimeLibrary(state,
                                            if (libraryViewState.filter == 2) historyScroll else libraryScroll, controller, backdrop,
                                            onOpen = ::open, onResume = ::resume,
                                            onResumeEpisode = ::resumeEpisode,
                                            onDiscover = { tab = 0; controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME) },
                                            onReview = { reviewsVisible = true }, viewState = libraryViewState,
                                            active = paneActive, contentBottomPadding = 24.dp)
                                    }
                                }
                            }
                        } else if (shownState != null && shownDetail != null) {
                            Box(Modifier.fillMaxSize()) {
                                detailState.SaveableStateProvider(shown.key) {
                                    AnimeDetailPane(shownDetail, shownState, controller, backdrop,
                                        remember(shownDetail.subject.id) {
                                            detailScrolls.getOrPut(shownDetail.subject.id) { LazyListState() }
                                        },
                                        onPlay = { requestPlayback(it, null) }, onOpenRelated = ::openRelated,
                                        onTag = { owner, label -> searchDetailTag(shownDetail.subject.id, owner, label) },
                                        onOpenSourceRelated = ::openSourceRelated, onSources = ::showSources,
                                        onTracking = { trackingVisible = true }, modifier = Modifier.fillMaxSize(),
                                        onHeroCollapsed = { heroCollapsed = it }, contentTopPadding = headerInset,
                                        active = active && shown.key == route.key && !overlayVisible && !searchExpanded && !searchPresent)
                                }
                            }
                        } else Box(Modifier.fillMaxSize().padding(top = headerInset)) {
                            AnimeMessage(if (state.detailLoading) "正在载入番剧" else "番剧详情未取得",
                                state.detailError ?: openingSubject?.title ?: openingReference?.title,
                                action = if (state.detailLoading) null else "重试",
                                onAction = {
                                    val result = openingSearch
                                    if (result != null) controller.openSearchGroup(result.group, preferredReference = result.version)
                                    else openingReference?.let(controller::openCatalogueReference)
                                        ?: openingSubject?.let(controller::open)
                                })
                        }
                    }
                }
            }
            fun applyTagQuery(next: AnimeDiscoveryQuery) {
                if (!active || !searchRouteActive || !searchExpanded || !tagSearchMode || browseCandidate != null) return
                controller.configureDiscoveryTags(next)
                tagDraft = ""
                tagChoicesOpen = false
            }
            if (!playing && (route.depth == 0 && !directoryDetailOpen || searchExpanded || searchPresent)) ExpandingSearchBar(
                expanded = searchExpanded, query = if (tagSearch) tagDraft else query,
                active = active && searchRouteActive && (!directoryDetailOpen || searchExpanded || searchPresent) &&
                    !overlayVisible, backdrop = backdrop,
                onExpandedChange = {
                    if (it) {
                        focusSearchInput = true
                        searchExpanded = true
                        tagSearchMode = false
                    } else closeSearch()
                },
                onPresenceChange = { searchPresent = it },
                onForegroundState = { searchForeground = it },
                onQuery = { if (tagSearch) { tagDraft = it.take(80); tagChoicesOpen = true } else query = it },
                onSearch = {
                    if (tagSearch) {
                        val current = controller.state.value.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.GENRES)]?.query
                            ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
                        animeDiscoveryTagInput(current.provider, tagDraft)?.let { applyTagQuery(current.withTags(current.selectedTags + it)) }
                    } else submitSearch(query)
                },
                placeholder = if (tagSearch) "筛选标签" else "搜索番剧",
                searchTarget = if (browseCandidate != null || searchingDetail || pendingSearchRestore != null) null
                    else if (tagSearch) SearchTarget.TAG else SearchTarget.TITLE,
                onSearchTarget = {
                    if (!searchingDetail && browseCandidate == null) {
                        focusSearchInput = it == SearchTarget.TITLE
                        tagSearchMode = it == SearchTarget.TAG
                    }
                },
                titleAndTagsOnly = true, collapsedLabel = "搜索番剧或标签",
                showEmptyResults = true, focusInputOnExpand = focusSearchInput,
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(vertical = 2.dp)) { height, inputActive ->
                val keyboard = LocalSoftwareKeyboardController.current
                val focus = LocalFocusManager.current
                fun releaseSearchInput() {
                    focusSearchInput = false
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                }
                if (tagSearch) {
                    AnimeTagSearch(state, controller, backdrop, discoveryScrolls, tagDraft, choosingTags,
                        active = inputActive, maxHeight = height,
                        onChoosingChange = { choosing ->
                            if (inputActive) {
                                tagDraft = ""
                                tagChoicesOpen = choosing
                            }
                        }, onPrepareFilters = ::releaseSearchInput, onConfigure = {
                            if (inputActive) { releaseSearchInput(); applyTagQuery(it) }
                        }, onOpen = { releaseSearchInput(); openDiscoveryItem(it) }, onManage = ::showWorkActions)
                    return@ExpandingSearchBar
                }
                val matchingReference = browseCandidate != null
                val catalogProvider = if (matchingReference) AnimeDiscoveryProvider.BANGUMI else AnimeDiscoveryProvider.DOUBAN
                val term = query.trim()
                val ownsResults = term.isNotEmpty() && term == search.query &&
                    search.catalogProvider == catalogProvider && search.indexOnly == matchingReference
                LaunchedEffect(searchExpanded, matchingReference, catalogProvider, search.query, search.indexOnly, search.catalogProvider) {
                    if (searchExpanded && search.query.isNotBlank() && search.query == query.trim() &&
                        (search.indexOnly != matchingReference || search.catalogProvider != catalogProvider)) submitSearch(search.query)
                }
                if (inputActive && ownsResults) {
                    val keys = remember(search.groups) { search.groups.map { it.key } }
                    LaunchedEffect(searchScroll) {
                        snapshotFlow { searchScroll.isScrollInProgress }.collect { scrolling ->
                            if (scrolling) followSearchStart = false
                        }
                    }
                    // The ordinary manga search follows incrementally inserted exact hits only
                    // while the reader has not deliberately taken over the viewport.
                    LaunchedEffect(keys, followSearchStart) {
                        if (followSearchStart && !searchScroll.isScrollInProgress) searchScroll.requestScrollToItem(0)
                    }
                }
                val desktop = platformKind == PlatformKind.DESKTOP
                val canPage = inputActive && ownsResults && !matchingReference && search.canPageSearch && search.groups.isNotEmpty()
                val searchPull = rememberBottomPullLoadState(
                    key = listOf(search.query, search.catalogProvider, search.indexOnly),
                    enabled = !desktop && canPage, loading = search.searchLoadingMore,
                    canScrollForward = { searchScroll.canScrollForward }, onLoadMore = controller::loadMoreCatalog,
                    actionLabel = "加载更多番剧")
                if (desktop) AnimeBrowsePagination(
                    key = listOf(search.query, search.catalogProvider, search.indexOnly), refreshKey = Unit,
                    itemCount = search.groups.size, enabled = canPage && !search.grouping && !search.searchLoadingFirst,
                    nearEnd = {
                        val info = searchScroll.layoutInfo
                        val trailingKeys = search.groups.takeLast(4).map { it.key }.toSet()
                        info.visibleItemsInfo.any { it.key in trailingKeys } &&
                            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 5
                    }, onMore = controller::loadMoreCatalog)
                Box(Modifier.fillMaxWidth().heightIn(max = height)) {
                LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = MangaLayout.ListWidth)
                    .fillMaxWidth().heightIn(max = height).motionInputGate(inputActive)
                    .then(if (desktop) Modifier else Modifier.bottomPullLoad(searchPull)),
                    state = searchScroll, userScrollEnabled = inputActive,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    browseCandidate?.let { candidate -> item(key = "source-association") {
                        AnimeSectionTitle("选择对应番剧", candidate.title + " · " +
                            if (candidate.sourceId in setOf("anime-anilist", "anime-bangumi")) animeProviderName(candidate.sourceId)
                            else animeSourceName(candidate.sourceId, state), action = {
                            MotionIconButton(onClick = { browseCandidate = null }, modifier = Modifier.size(44.dp),
                                tooltip = "取消来源关联") { Icon(Icons.Outlined.Close, "取消来源关联", Modifier.size(18.dp)) }
                        })
                    } }
                    when {
                        term.isEmpty() || term != search.query -> item(key = "search-draft") {
                            AnimeSearchDraft(term, state.searchHistory,
                                state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME)]?.items.orEmpty(),
                                matchingReference, inputActive, onSubmit = { keyword ->
                                    releaseSearchInput()
                                    query = keyword
                                    submitSearch(keyword)
                                }, onClear = controller::clearSearchHistory)
                        }
                        !ownsResults -> item(key = "search-awaiting-scope") {
                            AnimeDiscoveryLoading("正在查找番剧")
                        }
                        else -> animeSearchResults(search, controller, matchingReference, inputActive,
                            onOpenGroup = { openSearchResult(it) }, onManage = ::showWorkActions,
                            onShowSources = { releaseSearchInput(); searchProgressVisible = true }, pull = searchPull)
                    }
                }
                if (desktop) Box(Modifier.matchParentSize()) {
                    DesktopVerticalScrollbar(searchScroll,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                }
                }
            }
            if (active && !playing) SnackbarHost(snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }

    searchVersionChoice?.let { choice ->
        AnimeSearchVersionSheet(controller.currentVideoCandidates(search.currentReferences(choice)), controller,
            sourceName = { animeSourceName(it, state) }, backdrop = backdrop,
            onDismiss = { searchVersionChoice = null },
            onChoose = { openSearchResult(choice, it) })
    }

    if (active && searchRouteActive && searchExpanded && searchProgressVisible) {
        AnimeSearchProgressSheet(search, controller, backdrop, onDismiss = { searchProgressVisible = false })
    }

    if (recentWatchingOpen) AnimeRecentWatchingSheet(state.library, state.libraryReadUnavailable, backdrop,
        continuationLabel = { entry -> animeContinuationLabel(entry, controller.continuationEpisode(entry.subject.id)) },
        onChoose = { if (pendingRecentChoice == null) pendingRecentChoice = AnimeRecentChoice.Resume(it.subject.id) },
        onOpenHistory = { if (pendingRecentChoice == null) pendingRecentChoice = AnimeRecentChoice.FullHistory },
        onDismiss = {
            val choice = pendingRecentChoice
            pendingRecentChoice = null
            recentWatchingOpen = false
            if (active && route.depth == 0) when (choice) {
                is AnimeRecentChoice.Resume ->
                    controller.state.value.library.firstOrNull { it.subject.id == choice.subjectId }?.let(::resume)
                AnimeRecentChoice.FullHistory -> selectLibrary(history = true)
                null -> Unit
            }
        })
    actionSubject?.let { subject ->
        AnimeWorkActionsSheet(subject, state.library.firstOrNull { it.subject.id == subject.id }?.followed == true,
            backdrop, onDismiss = ::completeWorkActions, onChoose = { pendingWorkAction = it })
    }

    if (storagePresent) {
        val context = dev.mangaflow.platform.LocalMangaContext.current
        val services = remember(context) { dev.mangaflow.MangaApplicationServices.get(context) }
        StorageManagementDialog(storageVisible, backdrop, services.library, services.repository, active,
            onDismiss = { storageVisible = false },
            onDownloads = { storageDestination = "manga"; storageVisible = false },
            onAnimeDownloads = { storageDestination = "anime"; storageVisible = false },
            onModels = { storageDestination = "models"; storageVisible = false },
            onClosed = {
                val destination = storageDestination
                storageDestination = null
                storagePresent = false
                if (active) when (destination) {
                    "anime" -> downloadsVisible = true
                    "models" -> storageModelsVisible = true
                    "manga" -> onOpenMangaDownloads()
                    else -> Unit
                }
            })
    }
    dev.mangaflow.reader.panels.PanelModelsDialog(storageModelsVisible, backdrop) { storageModelsVisible = false }
    AnimeDownloadsPanel(downloadsVisible, controller, state, backdrop,
        onDismiss = { downloadsVisible = false }, onPlay = ::playDownload)
    AnimeSourceManager(sourceManagerVisible, state, controller, backdrop,
        onDismiss = { sourceManagerVisible = false }, onBrowse = { provider ->
            if (active) {
                directoryOpen = false
                tab = 0
                controller.selectRankingProvider(provider)
            }
        })
    AnimeSourcesPanel(sourcesVisible, state, controller, backdrop, sourceEpisode,
        overVideo = sourcesOverVideo,
        recovering = playbackPhase == AnimePlayerPhase.ERROR || state.playbackTarget?.error != null,
        onDismiss = ::dismissSources, onAutomaticPlay = { episode ->
            sourcesVisible = false
            requestPlayback(episode, null)
        }, onPlay = { episodeId, option ->
            sourcesVisible = false
            state.detail?.episodes?.firstOrNull { it.id == episodeId }?.let { requestPlayback(it, option) }
        }, onOpenSourceWork = { chooseReference(it, true) })
    AnimeTrackingPanel(trackingVisible, state, controller, backdrop,
        onDismiss = { trackingVisible = false }, onReview = { reviewsVisible = true })
    AnimeProgressReviewPanel(reviewsVisible, state, controller, backdrop, onDismiss = { reviewsVisible = false },
        onConnect = { reviewsVisible = false; trackingVisible = true },
        onRecheck = { review ->
            if (state.detail?.subject?.id == review.subjectId && !state.detailLoading)
                controller.reviewTracking(review.providerId, review.remoteSubjectId)
            else {
                recheckRequest = review
                open(state.library.firstOrNull { it.subject.id == review.subjectId }?.subject ?:
                    AnimeSubject(review.subjectId, review.subjectTitle))
            }
        })
    AnimeResumePanel(state.pendingPlayback, backdrop, onChoose = controller::confirmPlayback,
        onDismiss = controller::dismissPlaybackChoice)
}

/** Measures the floating chrome without reducing the page viewport. */
@Composable
private fun AnimeHeaderViewport(header: @Composable () -> Unit, content: @Composable (Dp) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val toolbar = subcompose("header", header).single().measure(constraints.copy(minHeight = 0))
        val inset = toolbar.height.toDp()
        val body = subcompose("page") { Box(Modifier.fillMaxSize()) { content(inset) } }
            .single().measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            toolbar.placeRelative(0, 0, zIndex = 1f)
        }
    }
}

/** Desktop has a permanent navigation rail, so it must not receive an empty menu surface. */
@Composable
private fun AnimeWorkspaceHeader(root: Boolean, title: String, backdrop: HazeState,
    navigationVisible: Boolean, navigation: @Composable () -> Unit,
    primary: (@Composable () -> Unit)?, showTitle: Boolean,
    tabsWidth: Dp, actionsWidth: Dp, controlsAlpha: () -> Float,
    actions: (@Composable () -> Unit)?) {
    val headerModifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
        .padding(horizontal = 8.dp, vertical = 2.dp)
    if (root && primary != null && actions != null) {
        val tabInk = rememberBrowseTabInk(animePrimaryLabels, animePrimaryTypeSize, 0.dp)
        if (navigationVisible) {
            SegmentedGlassHeader(backdrop, navigation, headerModifier.semantics { paneTitle = title },
                controlsWidth = tabsWidth, actionsWidth = actionsWidth, controlsAlpha = controlsAlpha,
                quietShadow = true, actions = actions, tabInk = tabInk) { primary() }
        } else Box(headerModifier.semantics { paneTitle = title }) {
            GlassControlGroup(backdrop, tabsWidth, actionsWidth, controlsAlpha, actions,
                content = { primary() }, quietShadow = true, tabInk = tabInk, actionInk = rememberHeaderActionInk(actionsWidth))
        }
        return
    }
    val heading: @Composable () -> Unit = {
        if (showTitle) Text(title,
            style = if (root) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val fadingActions: @Composable () -> Unit = {
        Box(Modifier.graphicsLayer { alpha = controlsAlpha() }) { actions?.invoke() }
    }
    if (root && !navigationVisible) {
        Row(headerModifier.fillMaxWidth().heightIn(min = 44.dp).semantics { paneTitle = title }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).padding(start = 8.dp)) { heading() }
            if (actions != null) LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION) {
                fadingActions()
            }
        }
    } else PageTitleHeader(title, backdrop, navigation, modifier = headerModifier,
        summary = heading, actions = if (actions == null) null else fadingActions)
}

internal fun animeSubjectSummary(subject: AnimeSubject): String =
    listOfNotNull(subject.year?.toString(), subject.episodeCount?.takeIf { it > 0 }?.let { it.toString() + " 集" })
        .joinToString(" · ").ifBlank { subject.originalTitle }
