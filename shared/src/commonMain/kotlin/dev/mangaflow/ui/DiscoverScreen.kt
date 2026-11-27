package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.Info
import dev.mangaflow.experience.sourceNetwork

import dev.mangaflow.typography.MangaFontScene

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

import androidx.compose.material.icons.outlined.ViewList

import androidx.compose.foundation.layout.asPaddingValues

import dev.mangaflow.ui.platform.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarOutline
import dev.mangaflow.ui.motion.MotionButton as Button
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.BrowseState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.RankAudience
import dev.mangaflow.storage.ReadingProgress
import androidx.compose.material.icons.outlined.History
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.bottomPullLoad
import dev.mangaflow.ui.motion.rememberBottomPullLoadState
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.HazeState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin

/** Native feeds/options belong to UI identity; non-search BrowseState keys omit their source scope. */
private fun browseContentKey(state: BrowseState): String =
    if (state.sourceScope.startsWith("scoped:"))
        "browse:${state.sourceScope.length}:${state.sourceScope}:${state.cacheKey}"
    else state.cacheKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: BrowseState,
    backgroundState: BrowseState,
    searchExpanded: Boolean,
    searchBackdrop: HazeState,
    wallpaper: @Composable () -> Unit = {},
    backgroundInParent: Boolean = false,
    contentTopPadding: Dp,
    canAutoAdvance: Boolean,
    repository: MangaRepository,
    discoverColumns: Int = 3,
    updateColumns: Int = 3,
    onDiscoverColumnsChange: (Int) -> Unit,
    onUpdateColumnsChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    isFavorite: (Manga) -> Boolean,
    onToggleFavorite: (Manga) -> Unit,
    onManageSources: (() -> Unit)? = null,
    onExplore: (() -> Unit)? = null,
    sourceControls: (@Composable (active: Boolean, onPresenceChange: (Boolean) -> Unit) -> Unit)? = null,
    onRetrySourceFilters: (() -> Unit)? = null,
    adultProfile: Boolean = false,
    active: Boolean = true,
    prepareNextPage: (suspend (BrowseState) -> Unit)? = null,
) {
    val browse = if (!state.isSearch) state else backgroundState
    val browseKey = browseContentKey(browse)
    val featuredIds = rememberFeaturedIds(browse, adultProfile)
    val positions = rememberSaveable(saver = BrowsePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    var actionsManga by remember { mutableStateOf<Manga?>(null) }
    var ratingsManga by remember { mutableStateOf<Manga?>(null) }
    val sourceFan = remember { MetricSourceFanState() }
    var sourceToolsOwner by remember { mutableStateOf<String?>(null) }
    val coverTransition = LocalMangaCoverTransition.current
    LaunchedEffect(active, searchExpanded) {
        if (!active || searchExpanded) { actionsManga = null; ratingsManga = null; sourceFan.clear() }
    }
    LaunchedEffect(browseKey) { sourceFan.clear() }
    val unobscured = active && !searchExpanded && actionsManga == null && ratingsManga == null &&
        sourceFan.request == null && sourceToolsOwner != browseKey
    val desktopActions: (Manga) -> List<DesktopMenuAction> = { manga ->
        listOfNotNull(
            DesktopMenuAction("查看详情", icon = Icons.Outlined.Info) {
                coverTransition?.select?.invoke(null)
                onOpen(manga)
            },
            desktopShelfAction(isFavorite(manga)) { onToggleFavorite(manga) },
            if (adultProfile) null else DesktopMenuAction("各站评分", icon = Icons.Outlined.StarOutline) { ratingsManga = manga },
        )
    }
    CompositionLocalProvider(LocalMetricSourceFan provides sourceFan,
        LocalBrowseWarmupEnabled provides unobscured) {
    Box(Modifier.fillMaxSize().mangaHazeSource(searchBackdrop)
        .background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)
        .motionInputGate(active && !searchExpanded)) {
        // Wallpaper and scroll content are one captured scene. The header remains a foreground sibling.
        wallpaper()
        MotionTabContent(
            selected = browse,
            modifier = Modifier.fillMaxSize(),
            // Selection owns one entrance. Cache hydration and network results
            // update that same pane instead of entering it a second time.
            contentKey = ::browseContentKey,
            order = { it.mode.ordinal * 8 + it.period.ordinal * 2 + it.audience.ordinal },
            soft = true,
        ) { pane ->
            val paneKey = browseContentKey(pane)
            val selectedPane = paneKey == browseKey
            var exitTopPadding by remember(paneKey) { mutableStateOf(contentTopPadding) }
            SideEffect { if (selectedPane) exitTopPadding = contentTopPadding }
            val sourceHeader: (@Composable () -> Unit)? = if (sourceControls == null) null else ({
                sourceControls(active && !searchExpanded && selectedPane) { present ->
                    if (present && selectedPane) sourceToolsOwner = paneKey
                    else if (!present && sourceToolsOwner == paneKey) sourceToolsOwner = null
                }
            })
            // Header clearance travels with the list; neither source controls nor
            // the outgoing ranking's last offset constrain the scroll viewport.
            DiscoveryPane(pane, positions, repository,
                featuredIds = featuredIds,
                columns = (if (pane.mode == DiscoveryMode.LATEST) updateColumns else discoverColumns).let {
                    if (platformKind == PlatformKind.DESKTOP) it.coerceIn(0, 8) else it.coerceIn(1, 4)
                },
                onColumnsChange = if (pane.mode == DiscoveryMode.LATEST) onUpdateColumnsChange else onDiscoverColumnsChange,
                backdrop = searchBackdrop,
                active = unobscured && selectedPane,
                canAutoAdvance = canAutoAdvance,
                onRefresh = onRefresh, onRetry = onRetry, onMore = onMore, onOpen = onOpen,
                prepareNextPage = prepareNextPage,
                onManageSources = onManageSources, onExplore = onExplore,
                sourceHeader = sourceHeader, onRetrySourceFilters = onRetrySourceFilters.takeIf { selectedPane },
                adultProfile = adultProfile,
                onManage = { actionsManga = it }, onRatings = { ratingsManga = it },
                desktopActions = desktopActions,
                topPadding = if (selectedPane) contentTopPadding else exitTopPadding)
        }
    }
    }
    MetricSourceFan(sourceFan, backdrop = searchBackdrop)
    actionsManga?.let { manga ->
        MotionBottomSheet(onDismissRequest = { actionsManga = null }, desktopCloseButton = true, desktopWidth = 440.dp) { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MangaCover(manga, repository, Modifier.width(48.dp).height(72.dp).mangaCoverCard(8.dp))
                    Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MangaQuickAction(Icons.AutoMirrored.Outlined.ArrowForward, "查看详情") {
                    coverTransition?.select?.invoke(null)
                    onOpen(manga); dismiss()
                }
                MangaQuickAction(Icons.Outlined.BookmarkBorder, if (isFavorite(manga)) "移出书架" else "加入书架") {
                    onToggleFavorite(manga); dismiss()
                }
                if (!adultProfile) MangaQuickAction(Icons.Outlined.StarOutline, "各站评分") {
                    actionsManga = null
                    ratingsManga = manga
                }
            }
        }
    }
    ratingsManga?.let { DetailRatingSheet(it, onDismiss = { ratingsManga = null }) }
}

@Composable
private fun MangaQuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .985f).padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Lives with the root search host, so dismissing the shell retains per-query positions. */
@Composable
internal fun DiscoverySearchResults(
    state: BrowseState,
    expanded: Boolean,
    active: Boolean,
    maxHeight: Dp,
    repository: MangaRepository,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    onTarget: (SearchTarget) -> Unit,
    onTag: (MangaTag) -> Unit,
    onRetryTags: () -> Unit,
    tagsOnly: Boolean = false,
    onTagQuery: ((String) -> Unit)? = null,
    backdrop: HazeState? = null,
    scopeInToolbar: Boolean = false,
    desktopMainContent: Boolean = false,
    desktopActions: ((Manga) -> List<DesktopMenuAction>)? = null,
    sourceControls: (@Composable (onPresenceChange: (Boolean) -> Unit) -> Unit)? = null,
    translationGroupAvailable: Boolean = true,
    recentCopyGroupTerms: List<String> = emptyList(),
    onCopyGroupTerm: ((String) -> Unit)? = null,
    onClearCopyGroupTerms: (() -> Unit)? = null,
    onEditQuery: (() -> Unit)? = null,
) {
    val adultProfile = sourceControls != null
    var retainedSearch by remember { mutableStateOf(state) }
    SideEffect { if (expanded) retainedSearch = state }
    val search = if (expanded) state else retainedSearch
    val searchKey = browseContentKey(search)
    val federated = search.isFederatedSearch
    var sourceChoices by remember(searchKey) { mutableStateOf<List<Manga>?>(null) }
    var sourceToolsPresent by remember { mutableStateOf(false) }
    val contentActive = active && expanded && sourceChoices == null && !sourceToolsPresent
    val transition = LocalMangaCoverTransition.current
    LaunchedEffect(active, expanded, searchKey) {
        if (!active || !expanded) sourceChoices = null
    }
    val openResult: (Manga) -> Unit = { manga ->
        if (contentActive) {
            val matches = search.sourceMatches[manga.id].orEmpty()
            if (adultProfile && manga.id in search.sourceChoiceIds && matches.size > 1) {
                transition?.select?.invoke(null)
                sourceChoices = matches
            } else {
                sourceChoices = null
                onOpen(manga)
            }
        }
    }
    val openSource: (List<Manga>) -> Unit = { matches ->
        if (contentActive) {
            transition?.select?.invoke(null)
            if (matches.size == 1) onOpen(matches.first())
            else if (matches.isNotEmpty()) sourceChoices = matches
        }
    }
    val positions = rememberSaveable(saver = SearchPositionsSaver) { LinkedHashMap<String, LazyListState>() }
    if (maxHeight <= 0.dp) return
    val retainedResults = remember(searchKey) {
        val saved = positions.remove(searchKey) ?: LazyListState()
        positions[searchKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    LaunchedEffect(contentActive, retainedResults) { if (!contentActive) retainedResults.stopScroll() }
    var followStart by remember(retainedResults) {
        mutableStateOf(retainedResults.firstVisibleItemIndex == 0 && retainedResults.firstVisibleItemScrollOffset == 0)
    }
    LaunchedEffect(retainedResults) {
        snapshotFlow { retainedResults.isScrollInProgress }.collect { scrolling ->
            if (scrolling) followStart = false
        }
    }
    val resultKeys = remember(search.items) { search.items.map(Manga::id) }
    LaunchedEffect(retainedResults, resultKeys, followStart, contentActive) {
        // Keyed incremental results otherwise retain an old item halfway down the viewport
        // when another source inserts matches above it. A deliberate scroll owns its anchor.
        if (contentActive && followStart && !retainedResults.isScrollInProgress) retainedResults.requestScrollToItem(0)
    }
    val selectingTags = search.searchTarget == SearchTarget.TAG && search.selectedTags.isEmpty()
    val awaitingKeyword = search.query.isBlank() && search.selectedTags.isEmpty()
    val selectingCopyGroup = !tagsOnly && !adultProfile && translationGroupAvailable &&
        search.searchTarget == SearchTarget.TRANSLATION_GROUP
    // A restricted search can narrow its sources before the first query.
    if (awaitingKeyword && !selectingTags && !selectingCopyGroup && !adultProfile) return
    val showResults = search.isSearch && !selectingTags && !awaitingKeyword
    val searchFocus = if (desktopMainContent) LocalDesktopSearchFocus.current else null
    val rowTargets = if (searchFocus != null) remember(retainedResults) { mutableMapOf<String, FocusRequester>() } else null
    val canFocusResults = contentActive && showResults && search.items.isNotEmpty()
    if (searchFocus != null && rowTargets != null && canFocusResults) DisposableEffect(searchFocus, retainedResults) {
        val enter: () -> Boolean = {
            val layout = retainedResults.layoutInfo
            val rows = layout.visibleItemsInfo
            // Prefer the first entirely visible work. A short window may only show a partial row.
            val target = rows.firstNotNullOfOrNull { info ->
                if (info.offset >= layout.viewportStartOffset && info.offset + info.size <= layout.viewportEndOffset)
                    (info.key as? String)?.let(rowTargets::get) else null
            } ?: rows.firstNotNullOfOrNull { (it.key as? String)?.let(rowTargets::get) }
            if (target == null) false else { target.requestFocus(); true }
        }
        searchFocus.results = enter
        onDispose { if (searchFocus.results === enter) searchFocus.results = null }
    }
    val initialSearchWait = showResults && search.items.isEmpty() &&
        (search.loading || search.refreshing || search.loadingMore)
    val slowSearch = desktopMainContent && rememberDelayedBusy(initialSearchWait && contentActive)
    val desktopMotion = desktopMainContent && contentActive && LocalMotionEnabled.current
    // Only a wait long enough to be visible gets an arrival. A cache hit or a
    // change in result count must not replay an entrance for the whole list.
    val awaitingInk = if (desktopMotion) animateFloatAsState(if (slowSearch) 1f else 0f,
        tween(if (slowSearch) MangaMotion.Quick else MangaMotion.Content, easing = MangaMotion.EnterEasing),
        label = "desktop-search-arrival") else null
    val arrival = if (awaitingInk == null) Modifier else Modifier.graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = 1f - .20f * awaitingInk.value
        translationY = 4.dp.toPx() * awaitingInk.value
    }
    var firstBatch by remember(searchKey) { mutableStateOf<Set<String>?>(null) }
    if (desktopMainContent && federated && resultKeys.isNotEmpty() && firstBatch == null) {
        SideEffect { firstBatch = resultKeys.toSet() }
    }
    val animateMatches = desktopMotion && federated && firstBatch != null && !retainedResults.isScrollInProgress
    val showCacheStatus = showResults && backdrop != null && !adultProfile
    val showSourceSummary = federated && search.searchSources.isNotEmpty() && sourceControls == null
    val showSummary = showSourceSummary ||
        (showResults && (desktopMainContent || search.items.isNotEmpty())) || showCacheStatus
    BrowseCoverWarmup(search.items, repository,
        contentActive && showResults, retainedResults)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val searchTail = if (desktopMainContent && !adultProfile) remember(search.items) { browsePageTail(search.items, Manga::id) }
        else emptyMap()
    if (desktopMainContent) DesktopBrowsePagination(search,
        enabled = contentActive && showResults,
        nearEnd = { count ->
            val info = retainedResults.layoutInfo
            if (adultProfile) info.totalItemsCount == count + 1 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 5
            else !retainedResults.lastScrolledBackward && (retainedResults.lastScrolledForward || !retainedResults.canScrollForward) &&
                nearBrowsePage(info.visibleItemsInfo, searchTail, {
                    it.contentType == "search-result" && it.size > 0 && it.offset < info.viewportEndOffset &&
                        it.offset + it.size > info.viewportStartOffset
                }, { it.key })
        }, onMore = onMore)
    CompositionLocalProvider(LocalBrowseWarmupEnabled provides contentActive) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        val index = desktopMainContent && fitsDesktopIndex(maxWidth)
        val contentRoom = (this.maxHeight - if (tagsOnly) 0.dp else 16.dp).coerceAtLeast(0.dp)
        val resultReserve = if (showResults) minOf(contentRoom * .5f, 120.dp * fontScale) else 0.dp
        val controlsRoom = (contentRoom - resultReserve).coerceAtLeast(0.dp)
        val fixedScope = (if (tagsOnly || scopeInToolbar) 0.dp else 42.dp) +
            (if (search.selectedTags.isEmpty()) 0.dp else 42.dp) +
            if (showSummary) if (showCacheStatus) 44.dp else 28.dp else 0.dp
        val choicesRoom = (controlsRoom - fixedScope).coerceIn(0.dp, 200.dp)
        val resultSurface = if (platformKind == PlatformKind.ANDROID && !adultProfile && scopeInToolbar)
            Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .62f)) else Modifier
        Column(Modifier.then(if (desktopMainContent)
                Modifier.align(Alignment.TopCenter).fillMaxHeight()
                    .semantics { paneTitle = "搜索结果" }.focusGroup()
                else Modifier)
            .fillMaxWidth().heightIn(max = this.maxHeight).then(resultSurface)
            .padding(horizontal = if (desktopMainContent) MangaLayout.sidePadding(maxWidth) else if (tagsOnly) 0.dp else 8.dp,
                vertical = if (tagsOnly) 0.dp else 8.dp)
            .motionInputGate(active && expanded && sourceChoices == null)) {
            // Chrome receives a finite share before the result list is measured. Even the
            // expanded tag/source directory can scroll here instead of measuring the list to zero.
            Column(Modifier.fillMaxWidth().heightIn(max = controlsRoom)
                .verticalScroll(rememberScrollState(), enabled = contentActive)) {
                if (index && showResults) {
                    val term = if (search.searchTarget == SearchTarget.TAG && search.selectedTags.isNotEmpty())
                        search.selectedTags.joinToString(" / ") { it.name } else search.query
                    val scope = when (search.searchTarget) {
                        SearchTarget.AUTHOR -> "作者搜索"
                        SearchTarget.TAG -> "标签搜索"
                        SearchTarget.TRANSLATION_GROUP -> "汉化组搜索"
                        SearchTarget.TITLE -> "作品搜索"
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(term, Modifier.weight(1f, fill = false).alignByBaseline().semantics { heading() },
                            style = browseTitleStyle(30.sp, 38.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(scope, Modifier.alignByBaseline(), style = MaterialTheme.typography.labelMedium,
                            color = browseNoteColor(), maxLines = 1)
                    }
                }
                // The toolbar keeps its owner's active state while its retained overlays
                // freeze only the results and search controls beneath them.
                sourceControls?.invoke { sourceToolsPresent = it }
                Column(Modifier.fillMaxWidth().motionInputGate(contentActive)) {
                    SearchScopeBar(search.searchTarget, search.query, search.selectedTags, search.availableTags,
                        search.tagsLoading, search.tagsError, onTarget, onTag, onRetryTags, tagsOnly = tagsOnly,
                        onTagQuery = onTagQuery, maxChoicesHeight = choicesRoom, showScope = !scopeInToolbar,
                        translationGroupAvailable = translationGroupAvailable)
                    if (selectingCopyGroup && (recentCopyGroupTerms.isNotEmpty() || awaitingKeyword)) {
                        RecentCopyGroupSearches(recentCopyGroupTerms, search.query, onCopyGroupTerm, onClearCopyGroupTerms)
                    }
                    if (showSummary) Row(
                        Modifier.fillMaxWidth().heightIn(min = if (showCacheStatus) 44.dp else 28.dp)
                            .padding(horizontal = if (tagsOnly) 0.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showSourceSummary) SearchSourceSummary(search.searchSources, onRetry, Modifier.weight(1f),
                            showNames = index)
                        else Spacer(Modifier.weight(1f))
                        if (desktopMainContent && showResults && search.items.isEmpty() && initialSearchWait) {
                            Text("正在查找作品…", modifier = Modifier.semantics {
                                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (showResults && search.items.isNotEmpty()) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "已显示${search.items.size}本漫画" +
                                    if (search.hasMore) "，可继续加载" else ""
                            }) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (index) "已载入 ${search.items.size} 部" else
                                search.items.size.toString() + if (search.hasMore) "+" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (search.loading && !federated)
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        }
                        if (showCacheStatus && backdrop != null) BrowseCacheStatus(search, backdrop,
                            active = contentActive)
                    }
                }
            }
            if (index && showResults && (search.items.isNotEmpty() || initialSearchWait))
                DesktopIndexLabels("正文来源", if (adultProfile) null else "热度与评分", modifier = Modifier.padding(end = 12.dp))
            when {
                selectingTags || awaitingKeyword || !search.isSearch -> Unit
                desktopMainContent && search.items.isEmpty() -> DesktopSearchFeedback(
                    search, index, adultProfile, contentActive, slowSearch, onEditQuery,
                    onRetry, onMore, onTarget, onTag,
                    Modifier.weight(1f).fillMaxWidth().then(arrival))
                search.loading && search.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "正在查找漫画" },
                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                search.items.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 16.dp).motionInputGate(contentActive),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (search.error != null) "暂不可用" else "没有匹配",
                        style = MaterialTheme.typography.bodyMedium)
                    if (search.error != null) {
                        Text(search.error, modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = onRetry, enabled = contentActive) { Text("重试") }
                    } else if (adultProfile) {
                        Text(if (search.searchSources.values.any { it.error != null }) "可用来源没有匹配结果"
                            else "试试缩短关键词，或调整搜索范围。",
                            modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    if (search.error == null && search.hasMore) {
                        if (search.loadingMore) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = onMore, enabled = contentActive) { Text("继续筛选") }
                    }
                }
                else -> Box(Modifier.fillMaxWidth().weight(1f, fill = desktopMainContent).then(arrival).motionInputGate(contentActive)) {
                    LazyColumn(
                        state = retainedResults,
                        userScrollEnabled = contentActive,
                        modifier = Modifier.fillMaxWidth().then(if (desktopMainContent) Modifier.fillMaxHeight() else Modifier)
                            .desktopSearchPaneKey(searchFocus?.field?.takeIf { canFocusResults }?.let { field ->
                                { field.requestFocus(); true }
                            }),
                        contentPadding = PaddingValues(end = if (desktopMainContent) 12.dp else 0.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(search.items, key = Manga::id, contentType = { "search-result" }) { manga ->
                            val rowFocus = if (rowTargets != null) {
                                val requester = remember(manga.id) { FocusRequester() }
                                DisposableEffect(retainedResults, manga.id, requester) {
                                    rowTargets[manga.id] = requester
                                    onDispose { if (rowTargets[manga.id] === requester) rowTargets.remove(manga.id) }
                                }
                                Modifier.focusRequester(requester)
                            } else Modifier
                            val placement = if (animateMatches) Modifier.animateItem(
                                fadeInSpec = if (firstBatch?.contains(manga.id) == false) tween(MangaMotion.Quick) else null,
                                fadeOutSpec = null,
                                placementSpec = spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) else Modifier
                            val row: @Composable () -> Unit = {
                                DesktopBrowseActions(desktopActions?.invoke(manga).orEmpty(), contentActive) {
                                    val direct = if (federated) search.sourceMatches[manga.id].orEmpty().ifEmpty { listOf(manga) }
                                        else emptyList()
                                    val aligned = if (adultProfile) emptyList() else search.alignedTagSources[manga.id].orEmpty()
                                    SearchResultRow(manga, repository, "search-$searchKey", modifier = rowFocus,
                                        desktop = desktopMainContent, index = index, query = search.query,
                                        horizontalInset = if (tagsOnly) 0.dp else 6.dp,
                                        adultProfile = adultProfile,
                                        bodySources = direct, alignedSources = aligned,
                                        tagSearch = search.searchTarget == SearchTarget.TAG,
                                        onSource = if (federated) openSource else null) { openResult(manga) }
                                }
                            }
                            if (desktopMainContent) Box(placement) { row() } else row()
                        }
                        if (search.hasMore || search.loadingMore) {
                            item(key = "search-more") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (search.loadingMore) {
                                        CircularProgressIndicator(Modifier.padding(12.dp).size(19.dp), strokeWidth = 2.dp)
                                    } else MotionIconButton(onClick = onMore, enabled = contentActive, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Outlined.ExpandMore, "加载更多结果", Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (search.error != null) item(key = "search-error") {
                            if (desktopMainContent) CatalogueState("部分结果暂未载入", search.error, selectableDetail = true) {
                                TextButton(onClick = onRetry, enabled = contentActive && !search.loadingMore) {
                                    Text("重新载入")
                                }
                            } else Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(search.error, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = onRetry, enabled = contentActive) { Text("重试") }
                            }
                        }
                    }
                if (desktopMainContent) DesktopVerticalScrollbar(retainedResults,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                }
            }
        }
    }
    sourceChoices?.let { matches ->
        FederatedSearchChoices(matches, repository, backdrop, onDismiss = { sourceChoices = null },
            onOpen = { manga -> sourceChoices = null; onOpen(manga) })
    }
    }
}

@Composable
private fun DesktopSearchFeedback(search: BrowseState, index: Boolean, adult: Boolean, active: Boolean, showSkeleton: Boolean,
    onEdit: (() -> Unit)?, onRetry: () -> Unit, onMore: () -> Unit, onTarget: (SearchTarget) -> Unit,
    onTag: (MangaTag) -> Unit, modifier: Modifier = Modifier) {
    val loading = search.loading || search.refreshing || search.loadingMore
    val failures = search.searchSources.values.filter { it.error != null }
    val allFailed = search.searchSources.isNotEmpty() && failures.size == search.searchSources.size
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (loading) {
                if (showSkeleton) repeat(4) {
                    SearchResultPlaceholder(index, adult)
                }
            } else {
                val tag = search.selectedTags.lastOrNull().takeIf { search.searchTarget == SearchTarget.TAG }
                val title = when {
                    allFailed -> "搜索来源暂不可用"
                    failures.isNotEmpty() -> "已返回的来源里没有匹配"
                    search.error != null -> "搜索未完成"
                    tag != null -> "没有符合这组标签的作品"
                    search.searchTarget == SearchTarget.AUTHOR -> "没有找到这位作者的作品"
                    search.searchTarget == SearchTarget.TRANSLATION_GROUP -> "没有找到这个汉化组的作品"
                    else -> "没有找到匹配的作品"
                }
                val detail = search.error ?: when {
                    failures.isNotEmpty() -> failures.joinToString("、") { it.sourceName } + " 暂未返回结果。"
                    search.hasMore -> "当前页没有匹配项，还可以继续查找后续页。"
                    tag != null -> "可以移除最近选中的标签「${tag.name}」，扩大查找范围。"
                    else -> "试试缩短关键词，或使用作品的其他译名。"
                }
                CatalogueState(title, detail, selectableDetail = search.error != null) {
                    if (search.error != null || failures.isNotEmpty()) TextButton(onClick = onRetry, enabled = active) {
                        Text("重新查找")
                    }
                    if (search.hasMore && search.error == null) TextButton(onClick = onMore, enabled = active) {
                        Text("继续查找")
                    }
                    if (tag != null) TextButton(onClick = { onTag(tag) }, enabled = active) {
                        Text(if (search.selectedTags.size > 1) "移除最后一个标签" else "重选标签")
                    } else {
                        if (onEdit != null) TextButton(onClick = onEdit, enabled = active) { Text("修改关键词") }
                        if (search.searchTarget == SearchTarget.AUTHOR && search.query.isNotBlank())
                            TextButton(onClick = { onTarget(SearchTarget.TITLE) }, enabled = active) { Text("按作品名查找") }
                    }
                }
            }
        }
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun RankingControls(
    state: BrowseState,
    onPeriod: (RankPeriod) -> Unit,
    onAudience: (RankAudience) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
    active: Boolean = true,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val labelWidth = with(density) {
        RankPeriod.entries.maxOf {
            measurer.measure(it.label, style, maxLines = 1).size.width
        }.toDp()
    }
    val choiceWidth = maxOf(44.dp, labelWidth + 16.dp)
    val periodWidth = choiceWidth * RankPeriod.entries.size
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val inlineRefresh = backdrop == null || maxWidth >= periodWidth + 136.dp
        val toolsWidth = if (backdrop != null && inlineRefresh) 132.dp else 88.dp
        val tools: @Composable () -> Unit = {
            RankingAudienceToggle(state.audience, onAudience, Modifier.width(44.dp))
            if (backdrop != null) BrowseCacheStatus(state, backdrop, active,
                onRefresh = if (inlineRefresh) null else onRefresh)
            if (inlineRefresh) DiscoveryRefresh(state, onRefresh, active)
        }
        if (fitsDesktopIndex(maxWidth, 620.dp)) {
            Row(Modifier.fillMaxWidth().motionInputGate(active), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopIndexNote("榜期")
                RankingPeriods(state.period, onPeriod, Modifier.width(periodWidth + 28.dp))
                DesktopIndexNote("分区", Modifier.padding(start = 8.dp))
                MotionTabs(state.audience.ordinal, RankAudience.entries.map { it.label },
                    onSelect = { if (active) onAudience(RankAudience.entries[it]) }, role = Role.RadioButton,
                    modifier = Modifier.width(110.dp * density.fontScale.coerceAtLeast(1f)),
                    treatment = MotionTabTreatment.Soft, controlHeight = 36.dp, fontSize = 12.sp,
                    indicatorHeight = 30.dp, fullWidthIndicator = true)
                Spacer(Modifier.weight(1f))
                if (backdrop != null) BrowseCacheStatus(state, backdrop, active)
                DiscoveryRefresh(state, onRefresh, active)
            }
        } else if (maxWidth >= periodWidth + toolsWidth + 4.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RankingPeriods(state.period, onPeriod, Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                tools()
            }
        } else {
            val wrapLabels = maxWidth < periodWidth
            Column(Modifier.fillMaxWidth()) {
                RankingPeriods(state.period, onPeriod, Modifier.fillMaxWidth(), wrapLabels = wrapLabels)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) { tools() }
            }
        }
    }
}

@Composable
private fun RankingPeriods(period: RankPeriod, onPeriod: (RankPeriod) -> Unit, modifier: Modifier, wrapLabels: Boolean = false) {
    val density = LocalDensity.current
    val height = if (wrapLabels) maxOf(44.dp, with(density) { 28.sp.toDp() + 16.dp }) else 44.dp
    MotionTabs(selected = period.ordinal, labels = RankPeriod.entries.map { it.label },
        onSelect = { onPeriod(RankPeriod.entries[it]) }, role = Role.RadioButton,
        modifier = modifier, fontSize = 12.sp, controlHeight = height,
        treatment = MotionTabTreatment.Underline,
        indicator = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
        muted = MaterialTheme.colorScheme.onSurface.copy(alpha = .44f),
        selectedFontWeight = FontWeight.Medium, unselectedFontWeight = FontWeight.Normal,
        itemContent = { index, selected, color ->
            val label = RankPeriod.entries[index].label
            Text(label, color = color, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = if (wrapLabels) 2 else 1,
                modifier = Modifier.semantics { contentDescription = label })
        })
}

@Composable
private fun RankingAudienceToggle(audience: RankAudience, onAudience: (RankAudience) -> Unit, modifier: Modifier) {
    val next = RankAudience.entries[(audience.ordinal + 1) % RankAudience.entries.size]
    val interaction = remember { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current
    val depth = animateFloatAsState(
        if (audience == RankAudience.FEMALE) 1f else 0f,
        if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(),
        label = "audience-depth",
    )
    val femaleInFront by remember { derivedStateOf { depth.value >= .5f } }
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > .5f
    Box(modifier.height(44.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "切换到${next.label}", onClick = { onAudience(next) })
        .semantics(mergeDescendants = true) {
            contentDescription = "榜单分类"
            stateDescription = audience.label
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp).motionPress(interaction, pressedScale = .94f),
            contentAlignment = Alignment.Center) {
            // Both faces retain their identity. The rear face travels around the front one,
            // so rapid taps reverse the same depth motion without replacing either icon.
            RankAudience.entries.forEach { shown ->
                val female = shown == RankAudience.FEMALE
                val tint = if (female) {
                    if (light) Color(0xFFAF587D) else Color(0xFFE3A0BC)
                } else {
                    if (light) Color(0xFF426F9D) else Color(0xFF91BDE6)
                }
                Box(Modifier.size(26.dp)
                    .zIndex(if (female == femaleInFront) 1f else 0f)
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        val progress = depth.value
                        val front = if (female) progress else 1f - progress
                        val arc = sin(progress * PI).toFloat()
                        translationX = (3f - 3f * front + (if (female) 9f else -9f) * arc).dp.toPx()
                        translationY = (-2f + 2f * front).dp.toPx()
                        scaleX = .48f + .52f * front
                        scaleY = scaleX
                        alpha = .035f + .965f * front * front
                        rotationY = (if (female) -1f else 1f) * 18f * arc
                        cameraDistance = 16.dp.toPx()
                    }, contentAlignment = Alignment.Center) {
                    Icon(if (female) Icons.Outlined.Female else Icons.Outlined.Male,
                        contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberFeaturedIds(state: BrowseState, adultProfile: Boolean): List<String> {
    var savedEdition by rememberSaveable { mutableStateOf("") }
    var savedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    if (adultProfile) return emptyList()
    if (state.mode != DiscoveryMode.RECOMMENDED || state.items.isEmpty()) return savedIds

    val today = localToday()
    val day = today.year * 366 + today.dayOfYear
    val edition = "${browseContentKey(state)}:$day"
    val picks = remember(state.items, edition, savedEdition, savedIds) {
        val available = state.items.mapTo(LinkedHashSet(), Manga::id)
        val retained = if (edition == savedEdition) savedIds.filter { it in available } else emptyList()
        if (retained.size == 6) savedIds else {
            val candidates = available.toList()
            val daily = if (candidates.size <= 6) candidates else List(6) { index ->
                candidates[(day % candidates.size + index) % candidates.size]
            }
            ArrayList((retained + daily).distinct().take(6))
        }
    }
    // Persist identities only. Appending a grid page keeps the gallery order and refreshed
    // records still supply the current cover, title and metrics for each surviving work.
    SideEffect {
        savedEdition = edition
        savedIds = picks
    }
    return picks
}

@Composable
private fun DiscoveryPane(
    state: BrowseState,
    positions: LinkedHashMap<String, LazyGridState>,
    repository: MangaRepository,
    featuredIds: List<String>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    backdrop: HazeState,
    active: Boolean,
    canAutoAdvance: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onManageSources: (() -> Unit)?,
    prepareNextPage: (suspend (BrowseState) -> Unit)?,
    onExplore: (() -> Unit)?,
    sourceHeader: (@Composable () -> Unit)?,
    onRetrySourceFilters: (() -> Unit)?,
    adultProfile: Boolean,
    onOpen: (Manga) -> Unit,
    onManage: (Manga) -> Unit,
    onRatings: (Manga) -> Unit,
    desktopActions: (Manga) -> List<DesktopMenuAction>,
    topPadding: Dp,
) {
    val paneKey = browseContentKey(state)
    var layoutPresent by remember(paneKey) { mutableStateOf(false) }
    val retainedGrid = remember(paneKey) {
        val saved = positions.remove(paneKey) ?: LazyGridState()
        positions[paneKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    val loadingGrid = remember(paneKey) { LazyGridState() }
    // Hydrate disk snapshots before a short loading layout can clamp the saved position.
    val initialLoad = state.loading && state.items.isEmpty()
    val grid = if (initialLoad) loadingGrid else retainedGrid
    LaunchedEffect(active, grid) { if (!active) grid.stopScroll() }
    val heroVisible by remember(grid, adultProfile) {
        val key = if (adultProfile) "section" else "hero"
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.any { it.key == key } }
    }
    val ranking = state.mode == DiscoveryMode.RANKING
    val recommended = state.mode == DiscoveryMode.RECOMMENDED
    val aggregateAdult = adultProfile && state.sourceScope.startsWith("scoped:*:")
    val booksBySource = remember(state.items, adultProfile, aggregateAdult, ranking) {
        if (adultProfile && (aggregateAdult || ranking)) state.items.groupBy(Manga::sourceId) else emptyMap()
    }
    val displayedBooks = remember(state.items, state.searchSources.keys, aggregateAdult, ranking) {
        if (!aggregateAdult || ranking) state.items
        else {
            // Keep each site's own order. In the combined view a faster response
            // must not bury all the other sites several pages below it.
            val lanes = (state.searchSources.keys + booksBySource.keys).mapNotNull(booksBySource::get)
            buildList(state.items.size) {
                repeat(lanes.maxOfOrNull { it.size } ?: 0) { index ->
                    lanes.forEach { lane -> lane.getOrNull(index)?.let { add(it) } }
                }
            }
        }
    }
    val motion = LocalMotionEnabled.current
    val firstEntranceIndex = remember(paneKey) { retainedGrid.firstVisibleItemIndex }
    val bookPlacement = remember(motion) {
        if (motion) spring<IntOffset>(MangaMotion.NavigationDamping, MangaMotion.GestureStiffness,
            visibilityThreshold = IntOffset(1, 1)) else snap()
    }
    // Compute the editorial rotation only for the pane that actually renders a carousel.
    val featured = when {
        !recommended -> emptyList()
        adultProfile -> remember(displayedBooks, aggregateAdult) {
            if (aggregateAdult) displayedBooks.asSequence().filter { it.coverUrl.isNotBlank() }
                .distinctBy(Manga::id).take(6).toList() else emptyList()
        }
        else -> remember(state.items, featuredIds) {
            featuredIds.mapNotNull { id -> state.items.firstOrNull { it.id == id } }
        }
    }
    val showFeatured = recommended && (!adultProfile || aggregateAdult) &&
        (featured.isNotEmpty() || state.loading)
    val mixedSources = when {
        !adultProfile -> false
        booksBySource.isNotEmpty() -> booksBySource.size > 1
        else -> remember(state.items) {
            val firstSource = state.items.firstOrNull()?.sourceId
            state.items.any { it.sourceId != firstSource }
        }
    }
    val desktop = platformKind == PlatformKind.DESKTOP
    val androidManga = platformKind == PlatformKind.ANDROID && !adultProfile
    val coverPlaceholders = !ranking && columns != 1 && (androidManga || adultProfile || desktop)
    val rankGutter = if (ranking) browseRankGutter(state.items, booksBySource) else 0.dp
    val pull = rememberBottomPullLoadState(
        key = paneKey,
        enabled = !desktop && active && state.items.isNotEmpty() && state.hasMore && !state.loading &&
            !state.refreshing && state.error == null,
        loading = state.loadingMore,
        canScrollForward = { grid.canScrollForward },
        onLoadMore = onMore,
    )
    dev.mangaflow.ui.motion.MotionRefreshBox(
        enabled = active && !state.loading && !state.loadingMore, refreshing = state.refreshing,
        onRefresh = onRefresh, indicatorTop = topPadding + 4.dp,
        showRefreshingIndicator = false,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val sidePadding = MangaLayout.sidePadding(maxWidth)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val indexed = fitsDesktopIndex(maxWidth)
    val gap = if (desktop && indexed) 18.dp else if (desktop) 12.dp else if (columns == 4) 8.dp else 10.dp
    // A saved count is a maximum. Desktop artwork keeps a readable target size,
    // and sparse/list modes get their own line width instead of enlarging posters.
    val visibleColumns = when {
        ranking || columns == 1 -> 1
        desktop && columns == 0 -> MangaLayout.automaticDesktopColumns(maxWidth, fontScale, gap, sidePadding)
        else -> MangaLayout.columns(maxWidth, columns,
            (if (desktop) 156.dp else if (columns == 4) 68.dp else 88.dp) * fontScale, gap, sidePadding)
    }
    BrowseCoverWarmup(displayedBooks, repository, active, grid, visibleColumns)
    val preferredWidth = when {
        indexed -> maxWidth
        ranking || desktop && columns == 1 -> MangaLayout.ListWidth
        desktop && columns > 1 -> 240.dp * fontScale * visibleColumns +
            gap * (visibleColumns - 1) + sidePadding * 2
        else -> maxWidth
    }
    val inset = sidePadding + ((maxWidth - preferredWidth) / 2f).coerceAtLeast(0.dp)
    if (!desktop && !adultProfile && prepareNextPage != null)
        PrepareDiscoveryPage(state, grid, active && !layoutPresent, prepareNextPage)
    val bookTail = if (desktop && !adultProfile) remember(state.items) { browsePageTail(state.items, Manga::id) }
        else emptyMap()
    if (desktop) DesktopBrowsePagination(state, enabled = active && !layoutPresent,
        nearEnd = { count ->
            val info = grid.layoutInfo
            val chromeItems = 1 + (if (ranking && !indexed) 0 else 1) + (if (showFeatured && !adultProfile) 1 else 0) +
                (if (sourceHeader == null) 0 else 1) +
                (if (ranking && aggregateAdult) booksBySource.size else 0)
            if (!adultProfile) !grid.lastScrolledBackward && (grid.lastScrolledForward || !grid.canScrollForward) &&
                info.nearBrowsePage(bookTail) { it == "browse-book" || it == "ranking-book" }
            else info.totalItemsCount == count + chromeItems &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                    info.totalItemsCount - visibleColumns * 2 - 1
        }, onMore = onMore)
    val sectionWidth = (maxWidth - inset * 2).coerceAtLeast(0.dp)
    val cellWidth = (sectionWidth - gap * (visibleColumns - 1)) / visibleColumns
    val wideCards = desktop && !ranking && columns != 1 && cellWidth >= maxOf(292.dp, 112.dp + 122.dp * fontScale)
    val decodeSize = if (desktop && !ranking && columns != 1) LocalDensity.current.coverDecodeSize(
        if (wideCards) 96.dp else cellWidth, if (wideCards) 96f / 140f else 2f / 3f) else null
    val scrollSectionActions = sectionWidth < 132.dp
    val showCategoryLabel = indexed || androidManga && sectionWidth >= 330.dp && fontScale <= 1.3f
    // Put the larger gap below each book's caption, keeping the section heading close to its first row.
    val afterCover = when {
        ranking || columns == 1 -> 0.dp
        desktop -> 16.dp
        visibleColumns >= 4 -> 8.dp
        else -> 12.dp
    }
    val sectionControls: @Composable () -> Unit = {
        val colors = MaterialTheme.colorScheme
        val access = LocalPlatformAccessibilityPreferences.current
        val toolsBackground = if (access.reduceTransparency || access.increaseContrast)
            lerp(colors.surface, colors.onSurface, if (access.increaseContrast) .10f else .055f)
        else colors.onSurface.copy(alpha = if (colors.surface.luminance() > .5f) .047f else .085f)
        val title = when {
            state.mode == DiscoveryMode.LATEST -> "最近更新"
            adultProfile && !aggregateAdult -> "作品"
            adultProfile && showFeatured -> "继续发现"
            else -> "为你发现"
        }
        val provider = state.items.firstOrNull()?.takeIf { aggregateAdult && !mixedSources }
        if (indexed) {
            Row(Modifier.fillMaxWidth().padding(top = if (showFeatured) 16.dp else 5.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.alignByBaseline().semantics { heading() }, style = browseTitleStyle(24.sp, 31.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (provider != null) MangaBrowseSource(provider, foreground = browseNoteColor())
                    else if (state.items.isNotEmpty()) Text("已载入 ${state.items.size} 部", Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.labelMedium, color = browseNoteColor(), maxLines = 1)
                }
                if (onExplore != null) TextButton(onClick = onExplore, enabled = active,
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Explore, null, Modifier.size(16.dp))
                    Text("分类与专题", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
                DiscoveryLayoutControl(columns, visibleColumns, backdrop, active, onColumnsChange,
                    showFullChoice = true, onPresenceChange = { layoutPresent = it })
                BrowseCacheStatus(state, backdrop, active)
                DiscoveryRefresh(state, onRefresh, active)
            }
        } else Layout(modifier = Modifier.fillMaxWidth(), content = {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (desktop) 22.sp else 20.sp, lineHeight = if (desktop) 28.sp else 26.sp,
                    letterSpacing = (-.3f).sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (provider != null) MangaBrowseSource(provider, foreground = browseNoteColor())
            }
            if (onExplore != null) {
                if (showCategoryLabel) {
                    TextButton(onClick = onExplore, enabled = active, modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.Explore, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("分类", style = MaterialTheme.typography.labelMedium)
                    }
                } else MotionIconButton(onClick = onExplore, enabled = active,
                    modifier = Modifier.size(if (androidManga) 48.dp else 36.dp), tooltip = "分类与专题") {
                    Icon(Icons.Outlined.Explore, "分类与专题", Modifier.size(18.dp),
                        tint = if (access.increaseContrast) colors.onSurface else colors.primary)
                }
            }
            // A low strip of ink groups the three utility glyphs. Their touch targets stay full height.
            Row(Modifier.then(if (scrollSectionActions) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .drawBehind {
                    val h = minOf(size.height, 32.dp.toPx())
                    val insetX = minOf(size.width / 2f, 2.dp.toPx())
                    drawRoundRect(toolsBackground, Offset(insetX, (size.height - h) / 2f),
                        Size(size.width - insetX * 2f, h), CornerRadius(h / 2f))
                },
                verticalAlignment = Alignment.CenterVertically) {
                BrowseCacheStatus(state, backdrop, active)
                DiscoveryLayoutControl(columns, visibleColumns, backdrop, active, onColumnsChange,
                    onPresenceChange = { layoutPresent = it })
                DiscoveryRefresh(state, onRefresh, active)
            }
        }) { children, bounds ->
            val loose = bounds.copy(minWidth = 0, minHeight = 0)
            val category = if (onExplore != null) children[1].measure(loose) else null
            val titleGap = if (category != null) 4.dp.roundToPx() else 0
            val categoryWidth = (category?.width ?: 0) + titleGap
            val heading = children.first().measure(loose.copy(maxWidth = (bounds.maxWidth - categoryWidth).coerceAtLeast(0)))
            val actions = children.last().measure(loose)
            val stacked = heading.width + categoryWidth + 8.dp.roundToPx() + actions.width > bounds.maxWidth
            val titleHeight = maxOf(heading.height, category?.height ?: 0)
            val firstLine = if (stacked) titleHeight else maxOf(titleHeight, actions.height)
            layout(bounds.maxWidth, firstLine + if (stacked) actions.height else 0) {
                heading.placeRelative(0, (firstLine - heading.height) / 2)
                category?.let {
                    val x = minOf(heading.width + titleGap, bounds.maxWidth - it.width)
                    it.placeRelative(x, (firstLine - it.height) / 2)
                }
                actions.placeRelative(bounds.maxWidth - actions.width,
                    if (stacked) firstLine else (firstLine - actions.height) / 2)
            }
        }
    }
    val gallery: @Composable () -> Unit = {
        if (showFeatured) FeaturedCarousel(featured, repository,
            active && heroVisible && canAutoAdvance && !layoutPresent,
            onOpen = onOpen, onRatings = onRatings.takeUnless { adultProfile }, loading = state.loading,
            showSource = adultProfile, desktopActions = desktopActions)
    }
    val loadingLabel = if (ranking) "正在加载${state.period.label}" else "正在加载漫画"
    LazyVerticalGrid(
        columns = GridCells.Fixed(visibleColumns), state = grid,
        contentPadding = PaddingValues(start = inset, end = inset, top = topPadding,
            bottom = 32.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(if (indexed && (ranking || columns == 1)) 0.dp
            else if (!ranking && columns == 1) 12.dp else 6.dp),
        modifier = Modifier.fillMaxSize().then(if (desktop) Modifier else Modifier.bottomPullLoad(pull))
            .then(if (initialLoad && (ranking || columns == 1 || coverPlaceholders)) Modifier.semantics {
                stateDescription = loadingLabel
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            } else Modifier)
            .motionInputGate(active),
    ) {
        if (sourceHeader != null) item(span = { GridItemSpan(maxLineSpan) }, key = "source-controls",
            contentType = "source-controls") { sourceHeader() }
        if (showFeatured && !adultProfile) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hero", contentType = "featured") {
                gallery()
            }
        }
        if (!ranking)
            item(span = { GridItemSpan(maxLineSpan) }, key = "section", contentType = "section-control") {
                    // Keep the gallery and its section in one query-owned anchor.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (adultProfile) gallery()
                    sectionControls()
                    if (indexed && columns == 1) DesktopIndexLabels(
                        if (state.mode == DiscoveryMode.LATEST) "最新章节" else if (mixedSources) "正文来源" else "题材",
                        if (adultProfile) null else "热度与评分")
                }
            }
        if (indexed && ranking) item(span = { GridItemSpan(maxLineSpan) }, key = "section", contentType = "rank-heading") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (adultProfile) state.period.label else state.audience.label + " · " + state.period.label,
                        Modifier.weight(1f).semantics { heading() }, style = browseTitleStyle(24.sp, 31.sp))
                    if (state.items.isNotEmpty()) DesktopIndexNote("已载入 ${state.items.size} 部")
                }
                DesktopIndexLabels("最新章节", if (adultProfile) null else "热度与评分", rankGutter.coerceAtLeast(44.dp))
            }
        }
        if (state.items.isNotEmpty() && state.error != null && state.failedPage == 0) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "refresh-error") {
                var expanded by remember(paneKey, state.error) { mutableStateOf(false) }
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(enabled = active, role = Role.Button,
                                onClickLabel = if (expanded) "收起原因" else "查看原因") { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = muted)
                            Text("更新失败，已保留当前列表", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp).graphicsLayer {
                                rotationZ = if (expanded) 180f else 0f
                            }, tint = muted)
                        }
                        TextButton(onClick = onRetry, enabled = active) { Text("重试") }
                    }
                    if (expanded) Text(state.error, style = MaterialTheme.typography.bodySmall, color = muted,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        if (initialLoad) {
            if (ranking || columns == 1) {
                items(4, key = { "loading-row:$it" }, contentType = { "list-placeholder" }) { index ->
                    val label = loadingLabel.takeIf { index == 0 && !showFeatured }
                    if (indexed) DesktopBrowsePlaceholder(if (ranking) rankGutter.coerceAtLeast(44.dp) else 0.dp,
                        !adultProfile, label)
                    else DiscoveryListPlaceholder(ranking, rankGutter, label)
                }
            } else if (coverPlaceholders) items(visibleColumns * 2,
                key = { "loading-cover:$it" }, contentType = { "cover-placeholder" }) {
                Box(Modifier.padding(bottom = afterCover)) {
                    if (wideCards) DesktopShelfPlaceholder()
                    else DiscoveryCoverPlaceholder(narrow = visibleColumns >= 4, showMetrics = !adultProfile,
                        showSource = aggregateAdult, showChapter = state.mode == DiscoveryMode.LATEST, desktopCatalogue = desktop)
                }
            }
            else if (!showFeatured) item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                QuietLoading("正在翻开书页…")
            }
        } else if (state.items.isEmpty() && state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                when {
                    onRetrySourceFilters != null ->
                        EmptyMessage("暂时无法读取来源筛选", state.error, "重试", onRetrySourceFilters)
                    adultProfile && state.searchSources.isEmpty() && state.failedPage == null ->
                        EmptyMessage("来源尚未就绪", state.error,
                            action = onManageSources?.let { "选择来源" },
                            onAction = { onManageSources?.invoke() })
                    else -> EmptyMessage("暂时没能加载", state.error, "重试", onRetry)
                }
            }
        } else if (state.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                when {
                    state.loadingMore -> QuietLoading("正在继续加载…")
                    state.hasMore -> EmptyMessage("这一页暂无内容", "来源仍有后续页面，可以继续加载。", "继续加载", onMore)
                    adultProfile && ranking && state.searchSources.isEmpty() ->
                        EmptyMessage("当前范围没有可用榜单", "可切换榜单时段或选择其他来源。",
                            action = onManageSources?.let { "选择来源" }, onAction = { onManageSources?.invoke() })
                    ranking -> EmptyMessage("当前榜单没有条目", "可以切换时段或刷新当前榜单。", "刷新", onRefresh)
                    else -> EmptyMessage("暂时没有漫画", "来源暂时没有返回内容，可以稍后刷新。", "刷新", onRefresh)
                }
            }
        } else {
            if (ranking) {
                if (adultProfile) {
                    // Each native provider owns its own ordering. Never merge JM/Pica rank
                    // values or draw Copy heat metrics over either provider's list.
                    booksBySource.forEach { (source, sourceItems) ->
                        if (aggregateAdult) item(key = "rank-provider:$source", span = { GridItemSpan(maxLineSpan) }) {
                            Text(sourceItems.first().sourceName.ifBlank { sourceDisplayName(source) },
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        }
                        itemsIndexed(sourceItems, key = { _, manga -> manga.id },
                            contentType = { _, _ -> "source-ranking-book" },
                            span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, manga ->
                            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                                placementSpec = bookPlacement)) {
                                DesktopBrowseActions(if (desktop) desktopActions(manga) else emptyList(), active) {
                                    RankRow(manga.copy(rank = index + 1), repository, state.period, paneKey, rankGutter,
                                        showProvider = false, indexed = indexed, onRatings = { onRatings(manga) }, onClick = { onOpen(manga) })
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(state.items, key = { _, manga -> manga.id }, contentType = { _, _ -> "ranking-book" },
                        span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, manga ->
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = bookPlacement)
                            .rankingRowEntrance(index - firstEntranceIndex)) {
                            DesktopBrowseActions(if (desktop) desktopActions(manga) else emptyList(), active) {
                                RankRow(manga, repository, state.period, paneKey, rankGutter,
                                    indexed = indexed, onRatings = { onRatings(manga) }, onClick = { onOpen(manga) })
                            }
                        }
                    }
                }
            } else {
                items(displayedBooks, key = Manga::id, contentType = { "browse-book" }) { manga ->
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = bookPlacement)
                        .padding(bottom = afterCover)) {
                        DesktopBrowseActions(if (desktop) desktopActions(manga) else emptyList(), active) {
                            MotionTabContent(selected = columns == 1, modifier = Modifier.fillMaxWidth(),
                                contentKey = { it }, order = { 0 }, fillContent = false) { list ->
                                val placement = "discovery-grid-$paneKey-" + if (list) "list" else "tile"
                                if (list && indexed) DesktopBrowseIndexRow(manga, repository, placement,
                                    detail = {
                                        when {
                                            state.mode == DiscoveryMode.LATEST -> {
                                                if (manga.latestChapter.isNotBlank()) BrowseChapterLine(manga.latestChapter)
                                                else DesktopIndexNote("—")
                                            }
                                            mixedSources -> MangaBrowseSource(manga)
                                            else -> DesktopIndexNote(manga.tags.take(3).joinToString(" / ").ifBlank { "—" })
                                        }
                                    }, figure = if (adultProfile) null else ({
                                        MangaMetricBadges(manga, foreground = browseNoteColor(), compact = true,
                                            plain = true, showMore = true, onExpand = { onRatings(manga) })
                                    }), onLongClick = { onManage(manga) }) { onOpen(manga) }
                                else if (!list && desktop) DesktopShelfCard(manga, repository, placement,
                                    wide = wideCards, narrow = visibleColumns >= 4, decodeSize = decodeSize,
                                    showChapter = state.mode == DiscoveryMode.LATEST, showSource = mixedSources,
                                    showMetrics = !adultProfile, onManage = { onManage(manga) },
                                    onRatings = { onRatings(manga) }, onOpen = { onOpen(manga) })
                                else if (list) BrowseListRow(manga, repository,
                                    showChapter = state.mode == DiscoveryMode.LATEST, placement = placement,
                                    showSource = mixedSources, showMetrics = !adultProfile,
                                    onLongClick = { onManage(manga) }, onRatings = { onRatings(manga) }, onClick = { onOpen(manga) })
                                else CoverTile(manga, repository, dense = true, narrow = visibleColumns >= 4,
                                    showSource = mixedSources, showMetrics = !adultProfile,
                                    showChapter = state.mode == DiscoveryMode.LATEST, placement = placement,
                                    onLongClick = { onManage(manga) }, onRatings = { onRatings(manga) }) { onOpen(manga) }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "more", contentType = "load-more") {
                if (state.error != null && state.failedPage == 0) Spacer(Modifier.height(12.dp))
                else if (state.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                } else {
                    if (state.hasMore || state.loadingMore) Box(Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center) {
                        if (desktop) {
                            if (state.loadingMore) CircularProgressIndicator(
                                Modifier.size(20.dp).semantics { contentDescription = "正在加载更多漫画" }, strokeWidth = 2.dp)
                            else TextButton(onClick = onMore, enabled = active && !state.loading && !state.refreshing) {
                                Text("加载更多")
                            }
                        } else Box(Modifier.semantics {
                            contentDescription = if (state.loadingMore) "正在加载" else if (pull.progress >= 1f)
                                "松手加载" else "上拉加载"
                        }) { BottomPullLoadIndicator(pull, Modifier.size(32.dp)) }
                    } else Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    if (desktop) DesktopVerticalScrollbar(grid,
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }
    }
}

private val BrowseListCoverHeight = 92.dp
private val SearchResultCoverHeight = 78.dp
private val SearchResultCoverFrame = Modifier.size(width = 54.dp, height = SearchResultCoverHeight)
private val BrowseRankCoverHeight = 98.dp
private val BrowseListCoverFrame = Modifier.size(width = 64.dp, height = BrowseListCoverHeight)
private val BrowseRankCoverFrame = Modifier.size(width = 68.dp, height = BrowseRankCoverHeight)
private val BrowseTileCoverFrame = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
private val BrowseTileCoverRadius = 12.dp
private val BrowseTileCaption = Modifier.fillMaxWidth().padding(start = 1.dp, end = 1.dp, top = 9.dp)
private val BrowseTileFootnote = Modifier.fillMaxWidth().padding(top = 3.dp)

@Composable
internal fun browseTitleStyle(size: TextUnit, leading: TextUnit, weight: FontWeight = FontWeight.SemiBold) =
    MaterialTheme.typography.titleMedium.copy(fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
        fontSize = size, lineHeight = leading, fontWeight = weight, letterSpacing = (-.15f).sp,
        lineBreak = LineBreak.Heading)

@Composable
private fun browseNoteColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface
        else lerp(colors.onSurface, colors.onSurfaceVariant, .76f)
}

@Composable
private fun BrowseChapterLine(chapter: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ink = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface
        else lerp(colors.primary, colors.onSurface, .28f)
    Text(mangaNumberText(chapter), modifier, color = ink,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun browseRankStyle() = mangaMetricValueStyle(14.sp, 20.sp).copy(
    fontWeight = FontWeight.Medium, letterSpacing = .4f.sp)

@Composable
private fun browseRankGutter(items: List<Manga>, booksBySource: Map<String, List<Manga>>): Dp {
    val digits = remember(items, booksBySource) {
        val largest = if (booksBySource.isNotEmpty()) booksBySource.values.maxOfOrNull { it.size }
            else items.maxOfOrNull { it.rank ?: 0 }
        (largest ?: 0).toString().length.coerceAtLeast(2)
    }
    val measurer = rememberTextMeasurer()
    val widthPx = measurer.measure("8".repeat(digits), browseRankStyle(), maxLines = 1).size.width
    return with(LocalDensity.current) { (widthPx.toDp() + 8.dp).coerceAtLeast(30.dp) }
}

/** Draw the waiting marks inside the same line boxes that will receive the actual text. */
private fun Modifier.placeholderLines(ink: Color, vararg fractions: Float): Modifier = fillMaxWidth().drawBehind {
    val line = size.height / fractions.size
    val stroke = minOf(6.dp.toPx(), line * .32f)
    fractions.forEachIndexed { index, fraction ->
        val width = size.width * fraction
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
        drawRoundRect(ink, Offset(x, line * (index + .5f) - stroke / 2f),
            Size(width, stroke), CornerRadius(stroke / 2f))
    }
}

@Composable
private fun DiscoveryListPlaceholder(ranked: Boolean, rankGutter: Dp, loadingLabel: String?) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = if (ranked) 6.dp else 0.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.Top) {
        if (ranked) Spacer(Modifier.width(rankGutter))
        Spacer((if (ranked) BrowseRankCoverFrame else BrowseListCoverFrame)
            .background(ink.copy(alpha = .07f), RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = if (platformKind == PlatformKind.DESKTOP) 44.dp else 0.dp)
            .heightIn(min = if (ranked) BrowseRankCoverHeight else BrowseListCoverHeight),
            verticalArrangement = Arrangement.SpaceBetween) {
            if (loadingLabel != null) Text(loadingLabel, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            else Text("", Modifier.placeholderLines(ink.copy(alpha = .075f), .74f, .46f),
                style = browseTitleStyle(17.sp, 23.sp), minLines = 2, maxLines = 2)
            Spacer(Modifier.height(22.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                .placeholderLines(ink.copy(alpha = .045f), .34f))
        }
    }
}

@Composable
internal fun DiscoveryCoverPlaceholder(narrow: Boolean, showMetrics: Boolean, showSource: Boolean, showChapter: Boolean,
    desktopCatalogue: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    val detailInk = ink.copy(alpha = .045f)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Spacer(BrowseTileCoverFrame.background(ink.copy(alpha = .055f),
            RoundedCornerShape(if (desktopCatalogue) 6.dp else BrowseTileCoverRadius)))
        if (desktopCatalogue) {
            DesktopCoverCaption(null, narrow, showChapter, showSource, showMetrics)
            return@Column
        }
        Column(BrowseTileCaption) {
            CoverTileTitle(null, dense = true, narrow = narrow)
            if (showChapter) BrowseChapterLine("", Modifier.padding(top = 2.dp).placeholderLines(detailInk, .60f))
            if (showMetrics) Spacer(BrowseTileFootnote.height(22.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                .placeholderLines(detailInk, .42f))
            else if (showSource) Text("", BrowseTileFootnote.heightIn(min = 18.dp).placeholderLines(detailInk, .50f),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun DesktopBrowsePlaceholder(ordinalWidth: Dp, figures: Boolean, label: String?) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)
    DesktopIndexColumns(Modifier.clearAndSetSemantics {}.padding(vertical = 7.dp), ordinalWidth,
        work = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Spacer(Modifier.size(43.dp, 60.dp).background(ink, RoundedCornerShape(5.dp)))
                Column(Modifier.weight(1f)) {
                    if (label != null) DesktopIndexNote(label)
                    else Spacer(Modifier.height(23.dp).placeholderLines(ink, .72f))
                    Spacer(Modifier.height(18.dp).placeholderLines(ink, .43f))
                }
            }
        }, detail = { Spacer(Modifier.height(18.dp).placeholderLines(ink, .52f)) },
        figure = if (figures) ({ Spacer(Modifier.height(18.dp).placeholderLines(ink, .36f)) }) else null)
}

@Composable
private fun DesktopShelfPlaceholder() {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.size(96.dp, 140.dp).background(ink, RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).height(140.dp).padding(end = 24.dp, top = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.height(48.dp).placeholderLines(ink, .85f, .51f))
            Spacer(Modifier.height(20.dp).placeholderLines(ink, .46f))
        }
    }
}

/**
 * One automatic request per growing result set. A duplicate/empty page keeps the
 * explicit footer available instead of chaining requests while the viewport is unchanged.
 */
@Composable
private fun DesktopBrowsePagination(
    state: BrowseState,
    enabled: Boolean,
    nearEnd: (itemCount: Int) -> Boolean,
    onMore: () -> Unit,
) {
    val latestState by rememberUpdatedState(state)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestNearEnd by rememberUpdatedState(nearEnd)
    val latestMore by rememberUpdatedState(onMore)
    val contentKey = remember(state) { browseContentKey(state) }
    val latestContentKey by rememberUpdatedState(contentKey)
    // Federated search timestamps every completed source round, even if it added no work.
    val updatedPerRound = state.isFederatedSearch && !state.sourceScope.startsWith("scoped:")
    LaunchedEffect(contentKey, if (updatedPerRound) 0L else state.updatedAt) {
        var requestedCount = 0
        var firstPageAt = 0L
        snapshotFlow {
            val current = latestState
            val count = current.items.size
            val eligible = latestEnabled && latestContentKey == contentKey && count > 0 &&
                current.hasMore && !current.loading && !current.loadingMore && !current.refreshing &&
                current.error == null && current.failedPage == null && latestNearEnd(count)
            val first = if (updatedPerRound && current.page == 0) current.updatedAt else 0L
            first to when {
                updatedPerRound && (current.loading || current.refreshing) -> -1
                eligible -> count
                else -> 0
            }
        }.collect { (first, count) ->
            if (count < 0 || first != 0L && first != firstPageAt) requestedCount = 0
            if (first != 0L) firstPageAt = first
            if (count > requestedCount) {
                requestedCount = count
                latestMore()
            }
        }
    }
}

/** The existing work actions stay local to their book; mouse and keyboard share one menu. */
@Composable
internal fun DesktopBrowseActions(
    actions: List<DesktopMenuAction>,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (platformKind != PlatformKind.DESKTOP || actions.isEmpty()) {
        content()
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    DesktopContextMenuArea(if (enabled) actions else emptyList()) {
        Box(Modifier.fillMaxWidth()
            .hoverable(interactionSource = interaction, enabled = enabled)
            .onFocusChanged { focused = it.hasFocus }.focusGroup()) {
            content()
            if (enabled && (hovered || focused || menuOpen)) Box(Modifier.align(Alignment.TopEnd)) {
                DesktopMoreButton(actions, label = "漫画操作", onExpandedChange = { menuOpen = it })
            }
        }
    }
}

@Composable
private fun SearchResultPlaceholder(index: Boolean, adult: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    val work: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(SearchResultCoverFrame.background(ink, RoundedCornerShape(5.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Spacer(Modifier.height(25.dp).placeholderLines(ink, .72f))
                Spacer(Modifier.height(18.dp).placeholderLines(ink, .41f))
            }
        }
    }
    val geometry = Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics {}
    if (index) DesktopIndexColumns(geometry, work = work,
        detail = { Spacer(Modifier.height(18.dp).placeholderLines(ink, .54f)) },
        figure = if (adult) null else ({ Spacer(Modifier.height(18.dp).placeholderLines(ink, .40f)) }))
    else Box(geometry.padding(start = 2.dp, end = 40.dp)) { work() }
}

@Composable
private fun searchMatchText(value: String, term: String, accent: Color): AnnotatedString =
    remember(value, term, accent) {
        buildAnnotatedString {
            append(value)
            if (term.isNotEmpty()) {
                val first = value.indexOf(term, ignoreCase = true)
                if (first >= 0) addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), first, first + term.length)
            }
        }
    }

@Composable
private fun SearchResultRow(manga: Manga, repository: MangaRepository, placement: String,
    horizontalInset: Dp = 6.dp, bodySources: List<Manga> = emptyList(),
    alignedSources: List<Manga> = emptyList(), tagSearch: Boolean = false,
    adultProfile: Boolean = false, onSource: ((List<Manga>) -> Unit)? = null,
    desktop: Boolean = false, index: Boolean = false, query: String = "",
    modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val transition = LocalMangaCoverTransition.current
    val interaction = remember { MutableInteractionSource() }
    val target = modifier.fillMaxWidth()
        .browseIntentWarmup(manga, repository, interaction)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看${manga.title}", onClick = {
                transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                onOpen()
            })
        .motionPress(interaction, pressedScale = if (desktop) .996f else .985f)
    val secondary = browseNoteColor()
    val cover: @Composable () -> Unit = {
        MangaCover(manga, repository, SearchResultCoverFrame.mangaSharedCover(manga.id, placement)
            .mangaCoverCard(if (desktop) 5.dp else 8.dp, shadowRadius = if (desktop) 3.dp else 6.dp))
    }
    if (desktop) {
        val accent = MaterialTheme.colorScheme.primary
        val term = query.trim()
        val alias = remember(manga, term) {
            if (term.isEmpty() || manga.title.contains(term, ignoreCase = true)) null
            else manga.alternativeTitles.firstOrNull { it.contains(term, ignoreCase = true) }
        }
        val authors = remember(manga.authors) { manga.authors.filter(String::isNotBlank).distinct().take(3).joinToString(" / ") }
        val identity: @Composable () -> Unit = {
            Text(searchMatchText(manga.title, term, accent), style = browseTitleStyle(18.sp, 24.sp),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (alias != null) Text(searchMatchText(alias, term, accent), style = MaterialTheme.typography.bodySmall,
                color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (authors.isNotEmpty()) Text(searchMatchText(authors, term, accent), style = MaterialTheme.typography.bodySmall,
                color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sources: @Composable () -> Unit = {
            if (!index && adultProfile && bodySources.size <= 1) MangaBrowseSource(manga, foreground = secondary)
            else if (bodySources.isNotEmpty()) SearchSourceMarks(bodySources, Modifier.fillMaxWidth(), onSource,
                alignedSources, tagSearch, showNames = index)
            else if (index) MangaBrowseSource(manga, foreground = secondary)
        }
        val metrics: @Composable () -> Unit = {
            MangaMetricBadges(manga, foreground = secondary, compact = true, plain = true, showMore = false)
        }
        val geometry = target.padding(vertical = 8.dp)
        if (index) DesktopIndexColumns(geometry, work = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                cover()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { identity() }
            }
        }, detail = sources, figure = if (adultProfile) null else metrics)
        else Row(geometry.padding(start = 2.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            cover()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                identity()
                sources()
                if (!adultProfile) metrics()
            }
        }
        return
    }
    Row(target.padding(horizontal = horizontalInset, vertical = 7.dp), verticalAlignment = Alignment.Top) {
        cover()
        Column(Modifier.weight(1f).heightIn(min = SearchResultCoverHeight).padding(start = 13.dp,
            end = if (platformKind == PlatformKind.DESKTOP) 44.dp else 0.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(manga.title, style = browseTitleStyle(16.sp, 21.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall, color = secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                if (adultProfile && bodySources.size <= 1) MangaBrowseSource(manga, foreground = secondary)
                else SearchSourceMarks(bodySources, Modifier.fillMaxWidth(), onSource, alignedSources, tagSearch)
                if (!adultProfile) MangaMetricBadges(manga, foreground = secondary, compact = true,
                    plain = true, showMore = false)
            }
        }
    }
}

/** Sparse desktop column settings leave room for credits beside a bounded cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopShelfCard(manga: Manga, repository: MangaRepository, placement: String,
    wide: Boolean, narrow: Boolean, decodeSize: IntSize?, showChapter: Boolean, showSource: Boolean,
    showMetrics: Boolean, onManage: () -> Unit, onRatings: () -> Unit, onOpen: () -> Unit) {
    val currentBook by rememberUpdatedState(manga)
    val currentSize by rememberUpdatedState(decodeSize)
    // The catalogue can put credits beside or below the same cover during a single window drag.
    val artwork = remember(repository, manga.sourceId, manga.id) {
        movableContentOf { frame: Modifier -> MangaCover(currentBook, repository, frame, decodeSize = currentSize) }
    }
    if (!wide) {
        CoverTile(manga, repository, dense = true, narrow = narrow, desktopCatalogue = true,
            showSource = showSource, showMetrics = showMetrics, showChapter = showChapter, placement = placement,
            onLongClick = onManage, onRatings = onRatings, artwork = artwork, onClick = onOpen)
        return
    }
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val note = browseNoteColor()
    Row(Modifier.fillMaxWidth().browseIntentWarmup(manga, repository, interaction)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看${manga.title}", onLongClickLabel = "漫画操作", onLongClick = onManage, onClick = {
                transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                onOpen()
            }).motionPress(interaction, pressedScale = .993f),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        artwork(Modifier.size(96.dp, 140.dp).mangaSharedCover(manga.id, placement).mangaCoverCard(8.dp))
        Column(Modifier.weight(1f).heightIn(min = 140.dp).padding(end = 16.dp, top = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(manga.title, Modifier.padding(end = 16.dp), style = browseTitleStyle(18.sp, 24.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manga.authors.isNotEmpty()) DesktopIndexNote(manga.authors.take(3).joinToString(" / "))
                if (showChapter && manga.latestChapter.isNotBlank()) BrowseChapterLine(manga.latestChapter)
                else if (manga.tags.isNotEmpty()) DesktopIndexNote(manga.tags.take(3).joinToString(" · "))
            }
            if (showSource) MangaBrowseSource(manga, foreground = note)
            else if (showMetrics) MangaMetricBadges(manga, foreground = note, compact = true, plain = true, showMore = false)
        }
    }
}

@Composable
private fun BrowseListRow(
    manga: Manga,
    repository: MangaRepository,
    showChapter: Boolean,
    placement: String,
    showSource: Boolean = false,
    showMetrics: Boolean = true,
    onCoverReady: (() -> Unit)? = null,
    onLongClick: () -> Unit,
    onRatings: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Row(Modifier.fillMaxWidth().browseIntentWarmup(manga, repository, interaction)
        .combinedClickable(interactionSource = interaction,
        indication = null, role = Role.Button, onClickLabel = "查看${manga.title}",
        onLongClickLabel = "漫画操作", onLongClick = onLongClick, onClick = {
            transition?.select?.invoke(MangaCoverKey(manga.id, placement))
            onClick()
        }).motionPress(interaction, pressedScale = .985f), verticalAlignment = Alignment.Top) {
        MangaCover(manga, repository, BrowseListCoverFrame
            .mangaSharedCover(manga.id, placement).mangaCoverCard(9.dp, shadowRadius = 7.dp), onReady = onCoverReady)
        val note = browseNoteColor()
        Column(Modifier.weight(1f).padding(start = 14.dp,
            end = if (platformKind == PlatformKind.DESKTOP) 44.dp else 0.dp).heightIn(min = BrowseListCoverHeight),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manga.title, style = browseTitleStyle(17.sp, 23.sp), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showChapter && manga.latestChapter.isNotBlank()) BrowseChapterLine(manga.latestChapter)
                if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall, color = note, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showSource) MangaBrowseSource(manga, Modifier.padding(top = 4.dp), foreground = note)
            else if (showMetrics) MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 4.dp),
                foreground = note, showMore = true, plain = true, onExpand = onRatings)
        }
    }
}

private enum class LayoutMenuSide { LEFT, RIGHT, ABOVE, BELOW }
private data class LayoutMenuPlacement(val side: LayoutMenuSide, val width: Int)

@Composable
internal fun DiscoveryLayoutControl(
    columns: Int,
    actualColumns: Int,
    backdrop: HazeState,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    showFullChoice: Boolean = false,
    onPresenceChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var closeAfterChoice by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) { if (!enabled) { expanded = false; closeAfterChoice = null } }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val motion = LocalMotionEnabled.current
    val desktop = platformKind == PlatformKind.DESKTOP
    val choices = remember(desktop, columns) {
        if (desktop) (listOf(0, 1, 2, 3, 4, 6, 8) + columns).distinct().sorted()
        else listOf(1, 2, 3, 4)
    }
    val labels = remember(choices) { choices.map { if (it == 0) "自动" else if (it == 1) "列表" else "${it}列" } }
    val selected = choices.indexOf(columns).coerceAtLeast(0)
    var displayedChoice by remember { mutableStateOf(columns) }
    LaunchedEffect(columns) { displayedChoice = columns }
    LaunchedEffect(closeAfterChoice, expanded, motion) {
        if (closeAfterChoice != null && expanded) {
            // The real layout changes on the tap. Only the folding chrome waits
            // one short beat, so the moving choice can arrive before it leaves.
            if (motion) delay(MangaMotion.Quick.toLong())
            expanded = false
            closeAfterChoice = null
        }
    }
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = expanded && enabled
    val transition = rememberTransition(presence, label = "layout-choice-reveal")
    val reveal = transition.animateFloat(transitionSpec = {
        if (motion) tween(if (targetState) MangaMotion.SearchClip else MangaMotion.Enter,
            easing = if (targetState) MangaMotion.SearchEasing else MangaMotion.ExitEasing) else snap()
    }, label = "layout-choice-width") { if (it) 1f else 0f }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    val cellWidth = 44.dp * density.fontScale.coerceIn(1f, 1.35f)
    val fullWidth = with(density) { (cellWidth * choices.size + 8.dp).roundToPx() }
    val minimumWidth = with(density) { (cellWidth + 8.dp).roundToPx() }
    var placement by remember { mutableStateOf<LayoutMenuPlacement?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(expanded, scroll.maxValue) {
        // Selection moves inside the retained strip; it must not jump its
        // scroll origin while the new indicator is travelling or folding away.
        if (expanded) scroll.scrollTo(with(density) { (cellWidth * selected).roundToPx() }
            .coerceIn(0, scroll.maxValue))
    }
    val position = remember(density, fullWidth, minimumWidth, desktop) {
        val gap = with(density) { 4.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val marginX = minOf(margin, ((windowSize.width - minimumWidth) / 2).coerceAtLeast(0))
                val available = (windowSize.width - 2 * marginX).coerceAtLeast(1)
                val left = (anchorBounds.left - gap - marginX).coerceIn(0, available)
                val right = (windowSize.width - marginX - anchorBounds.right - gap).coerceIn(0, available)
                val ltr = layoutDirection == LayoutDirection.Ltr
                val start = if (ltr) left else right
                val end = if (ltr) right else left
                val below = windowSize.height - margin - anchorBounds.bottom - gap
                val above = anchorBounds.top - gap - margin
                val side = if (maxOf(start, end) < minimumWidth) {
                    if (below >= popupContentSize.height || below >= above) LayoutMenuSide.BELOW else LayoutMenuSide.ABOVE
                } else {
                    val preferStart = start >= fullWidth || start >= end || !desktop && start >= minimumWidth
                    if (preferStart == ltr) LayoutMenuSide.LEFT else LayoutMenuSide.RIGHT
                }
                val room = when (side) {
                    LayoutMenuSide.LEFT -> left
                    LayoutMenuSide.RIGHT -> right
                    else -> available
                }
                placement = LayoutMenuPlacement(side, minOf(fullWidth, room))
                val x = when (side) {
                    LayoutMenuSide.LEFT -> anchorBounds.left - gap - popupContentSize.width
                    LayoutMenuSide.RIGHT -> anchorBounds.right + gap
                    else -> anchorBounds.center.x - popupContentSize.width / 2
                }
                val y = when (side) {
                    LayoutMenuSide.BELOW -> anchorBounds.bottom + gap
                    LayoutMenuSide.ABOVE -> anchorBounds.top - gap - popupContentSize.height
                    else -> anchorBounds.center.y - popupContentSize.height / 2
                }
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                val insetX = minOf(marginX, maxX / 2)
                val insetY = minOf(margin, maxY / 2)
                return IntOffset(x.coerceIn(insetX, maxX - insetX), y.coerceIn(insetY, maxY - insetY))
            }
        }
    }
    // Keep the trigger in its row while the choices unfold beside it.
    val triggerWidth = if (showFullChoice) 66.dp * density.fontScale.coerceAtLeast(1f) else 44.dp
    val triggerBounds = Modifier.width(triggerWidth).height(44.dp)
    Box(triggerBounds) {
        MotionIconButton(
            onClick = { closeAfterChoice = null; expanded = !expanded },
            modifier = triggerBounds.semantics {
                contentDescription = "浏览布局"
                stateDescription = labels[selected] + "，当前${actualColumns}列" +
                    if (expanded) "，已展开" else "，已收起"
            },
            enabled = enabled, tooltip = if (showFullChoice) "浏览布局" else null,
        ) {
            MotionTabContent(selected = columns to actualColumns, modifier = Modifier.width(triggerWidth - 2.dp).height(32.dp),
                contentKey = { it }, order = { it.first }, fillContent = false) { (preferred, actual) ->
                val colors = MaterialTheme.colorScheme
                val ink = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface
                    else if (expanded) colors.primary else colors.onSurfaceVariant
                val automatic = desktop && preferred == 0
                Row(Modifier.fillMaxSize().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                    Icon(if (preferred == 1) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                        null, Modifier.size(16.dp), tint = ink)
                    Text(if (showFullChoice) labels[choices.indexOf(preferred).coerceAtLeast(0)]
                        else if (automatic) "自" else actual.toString(), color = ink,
                        style = if (automatic) MaterialTheme.typography.labelMedium else mangaMetricValueStyle(),
                        maxLines = 1, softWrap = false)
                }
            }
        }
        if (present) Popup(popupPositionProvider = position,
            onDismissRequest = { closeAfterChoice = null; expanded = false },
            properties = PopupProperties(focusable = expanded && enabled)) {
            val bounds = placement
            val panelWidth = with(density) { (bounds?.width ?: fullWidth).toDp() }
            // Only the inner lens animates; the popup keeps its measured width for positioning.
            Box(Modifier.width(panelWidth).height(44.dp)) rail@{
                if (bounds == null) return@rail
                val horizontal = bounds.side == LayoutMenuSide.LEFT || bounds.side == LayoutMenuSide.RIGHT
                val opensToStart = (bounds.side == LayoutMenuSide.LEFT) == (direction == LayoutDirection.Ltr)
                val progress = reveal.value.coerceIn(0f, 1f)
                LiquidGlassSurface(backdrop,
                    Modifier.align(if (horizontal && opensToStart) Alignment.CenterEnd else Alignment.CenterStart)
                        .width(if (horizontal) (panelWidth * progress).coerceAtLeast(1.dp) else panelWidth).height(44.dp)
                        .motionInputGate(enabled && expanded && progress >= .85f)
                        .graphicsLayer {
                            alpha = progress
                            if (!horizontal) translationY = (if (bounds.side == LayoutMenuSide.BELOW) -4.dp.toPx()
                                else 4.dp.toPx()) * (1f - progress)
                            // Keep the soft shadow's outset visible throughout alpha < 1,
                            // rather than revealing it only after offscreen clipping ends.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL, softCard = true,
                    elevation = 16.dp * progress, materialExpansion = { progress }) {
                    Row(Modifier.layout { measurable, constraints ->
                        val railWidth = panelWidth.roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = railWidth, maxWidth = railWidth))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.placeRelative(if (horizontal && opensToStart) constraints.maxWidth - railWidth else 0, 0)
                        }
                    }.width(panelWidth).height(44.dp).horizontalScroll(scroll).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MotionTabs(
                            selected = choices.indexOf(displayedChoice).coerceAtLeast(0),
                            labels = labels, role = Role.RadioButton,
                            modifier = Modifier.width(cellWidth * choices.size),
                            controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                            fullWidthIndicator = true, indicatorHeight = 34.dp, indicatorCornerRadius = 17.dp,
                            indicator = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                            onSelect = { index ->
                                val choice = choices[index]
                                displayedChoice = choice
                                if (choice != columns) onChange(choice)
                                if (!motion || choice == columns) {
                                    closeAfterChoice = null
                                    expanded = false
                                } else closeAfterChoice = choice
                            },
                            itemContent = { index, _, color ->
                                val choice = choices[index]
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clearAndSetSemantics {}) {
                                    Icon(if (choice == 1) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                        null, Modifier.size(17.dp), tint = color)
                                    if (choice != 1) Text(if (choice == 0) "自" else choice.toString(),
                                        color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            })

                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryRefresh(state: BrowseState, onRefresh: () -> Unit, active: Boolean = true) {
    val motion = LocalMotionEnabled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.refreshing, motion, lifecycle, active) {
        if (!motion || !active) rotation.snapTo(0f)
        else if (state.refreshing) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    rotation.animateTo(360f, tween(1400, easing = LinearEasing))
                    rotation.snapTo(0f)
                }
            }
        } else if (rotation.value > 0f) {
            rotation.animateTo(360f, tween(380, easing = MangaMotion.EnterEasing))
            rotation.snapTo(0f)
        }
    }
    MotionIconButton(onClick = onRefresh, modifier = Modifier.size(44.dp), enabled = active && !state.loading && !state.refreshing) {
        Icon(Icons.Outlined.Refresh,
            if (state.refreshing) "正在刷新漫画" else if (state.updatedAt > 0) "刷新漫画，${browseUpdatedAt(state.updatedAt)}" else "刷新漫画",
            Modifier.size(19.dp).graphicsLayer { rotationZ = rotation.value },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RankRow(manga: Manga, repository: MangaRepository, period: RankPeriod, listKey: String, rankGutter: Dp,
    modifier: Modifier = Modifier, showProvider: Boolean = true, indexed: Boolean = false,
    onRatings: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val colors = MaterialTheme.colorScheme
    val heatLabel = when (period) {
        RankPeriod.DAY -> "日热"
        RankPeriod.WEEK -> "周热"
        RankPeriod.MONTH -> "月热"
        RankPeriod.TOTAL -> "总热"
    }
    if (indexed) {
        DesktopBrowseIndexRow(manga, repository, "ranking-$listKey",
            detail = {
                if (manga.latestChapter.isNotBlank()) BrowseChapterLine(manga.latestChapter)
                else DesktopIndexNote("—")
            }, figure = if (showProvider) ({
                if (manga.sourceId == "copy") RankingMetrics(manga, heatLabel = heatLabel, onExpand = onRatings)
                else MangaBrowseSource(manga)
            }) else null, ordinalWidth = rankGutter.coerceAtLeast(44.dp),
            ordinal = {
                val position = manga.rank
                Text(position?.toString()?.padStart(2, '0') ?: "—", style = browseRankStyle(),
                    color = if (position != null && position in 1..3) colors.primary else colors.onSurfaceVariant)
            }, onOpen = onClick)
        return
    }
    Row(modifier.fillMaxWidth().browseIntentWarmup(manga, repository, interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = {
            transition?.select?.invoke(MangaCoverKey(manga.id, "ranking-$listKey"))
            onClick()
        })
        .motionPress(interaction, pressedScale = .985f)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        val note = browseNoteColor()
        val rank = manga.rank
        val ordinalInk = if (rank != null && rank in 1..3 && !LocalPlatformAccessibilityPreferences.current.increaseContrast)
            lerp(colors.primary, colors.onSurface, .20f) else note
        Text(rank?.toString()?.padStart(2, '0') ?: "—", style = browseRankStyle(), color = ordinalInk,
            textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            modifier = Modifier.width(rankGutter).padding(end = 8.dp).alignByBaseline())
        MangaCover(manga, repository, BrowseRankCoverFrame
            .mangaSharedCover(manga.id, "ranking-$listKey").mangaCoverCard(9.dp, shadowRadius = 7.dp))
        Column(Modifier.weight(1f).alignByBaseline().padding(start = 14.dp,
                end = if (platformKind == PlatformKind.DESKTOP) 44.dp else 0.dp).heightIn(min = BrowseRankCoverHeight),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manga.title, style = browseTitleStyle(17.sp, 23.sp), color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall, color = note, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (manga.sourceId == "copy") {
                RankingMetrics(manga, heatLabel = heatLabel, onExpand = onRatings)
            } else {
                Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (manga.latestChapter.isNotBlank()) BrowseChapterLine(manga.latestChapter)
                    if (showProvider) MangaBrowseSource(manga, foreground = note)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueRow(
    progress: ReadingProgress, repository: MangaRepository, onContinue: (ReadingProgress) -> Unit,
    onManage: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    namedHistory: Boolean = false,
    prominent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val note = browseNoteColor()
    val coverWidth = if (!prominent) 46.dp else if (LocalDensity.current.fontScale > 1.4f) 54.dp else 68.dp
    val featuredPaper = if (prominent) {
        val storedColor = remember(progress.manga.coverUrl) { FeaturedCoverColors.get(progress.manga.coverUrl) }
        val pigment = storedColor ?: colors.primary
        val nearCover = lerp(colors.surface, pigment, .12f)
        val paper = listOf(nearCover, colors.surfaceContainerLow)
        val tones = if (LocalLayoutDirection.current == LayoutDirection.Rtl) paper.reversed() else paper
        if (LocalPlatformAccessibilityPreferences.current.increaseContrast)
            Modifier.background(colors.surface, RoundedCornerShape(17.dp))
        else Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(tones), RoundedCornerShape(17.dp))
    } else Modifier
    Row(Modifier.fillMaxWidth()
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "继续阅读", onClick = { onContinue(progress) },
            onLongClickLabel = if (onManage != null) "管理阅读记录" else null,
            onLongClick = onManage)
        .motionPress(interaction, pressedScale = .985f).then(featuredPaper)
        .padding(horizontal = if (prominent) 10.dp else 0.dp, vertical = if (prominent) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(progress.manga, repository, Modifier
            .size(coverWidth, coverWidth * 1.4f)
            .mangaCoverCard(if (prominent) 10.dp else 7.dp, shadowRadius = 6.dp))
        Column(Modifier.weight(1f).padding(start = if (prominent) 14.dp else 12.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (prominent) 5.dp else 3.dp)) {
            Text(progress.manga.title, color = colors.onSurface,
                style = if (prominent) browseTitleStyle(20.sp, 25.sp) else browseTitleStyle(16.sp, 21.sp),
                maxLines = if (prominent) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (prominent) {
                if (progress.chapterTitle.isNotBlank()) Text(progress.chapterTitle,
                    color = note, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val page = if (progress.pageCount > 0) "${progress.pageIndex + 1}/${progress.pageCount} 页"
                    else "第 ${progress.pageIndex + 1} 页"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mangaNumberText(page), style = MaterialTheme.typography.labelSmall,
                        color = note, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detailReadingFraction(progress)?.let { fraction ->
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurface.copy(alpha = .08f))) {
                            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(colors.primary))
                        }
                    }
                }
            } else {
                Text(mangaNumberText(recentReadingLocation(progress)), style = MaterialTheme.typography.bodySmall,
                    color = note, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detailReadingFraction(progress)?.let { fraction ->
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))
                        .background(colors.onSurface.copy(alpha = .07f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(colors.primary.copy(alpha = .72f)))
                    }
                }
            }
        }
        if (onHistory == null) Box(Modifier.size(if (prominent) 32.dp else 24.dp)
            .then(if (prominent) Modifier.background(colors.primaryContainer, RoundedCornerShape(50)) else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp),
                tint = if (prominent) colors.onPrimaryContainer else note)
        }
        else MotionIconButton(onClick = onHistory, modifier = Modifier.size(44.dp),
            tooltip = if (namedHistory) "${progress.manga.title}的章节记录" else "章节阅读记录") {
            Icon(Icons.Outlined.History, "章节阅读记录", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(
    manga: Manga,
    repository: MangaRepository,
    dense: Boolean = false,
    narrow: Boolean = false,
    modifier: Modifier = Modifier,
    showChapter: Boolean = false,
    placement: String? = null,
    onLongClick: (() -> Unit)? = null,
    onRatings: (() -> Unit)? = null,
    bodySources: List<Manga> = emptyList(),
    showSource: Boolean = false,
    showMetrics: Boolean = true,
    onCoverReady: (() -> Unit)? = null,
    desktopCatalogue: Boolean = false,
    artwork: (@Composable (Modifier) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Column(modifier.browseIntentWarmup(manga, repository, interaction)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看${manga.title}", onLongClickLabel = if (onLongClick != null) "漫画操作" else null,
            onLongClick = onLongClick, onClick = {
                if (placement != null) transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                onClick()
            })
        .motionPress(interaction)) {
        val frame = BrowseTileCoverFrame
            .then(if (placement != null) Modifier.mangaSharedCover(manga.id, placement) else Modifier)
            .mangaCoverCard(if (desktopCatalogue) 6.dp else BrowseTileCoverRadius,
                shadowRadius = if (desktopCatalogue) 4.dp else if (narrow) 7.dp else 9.dp)
        if (artwork == null) MangaCover(manga, repository, frame, onReady = onCoverReady)
        else artwork(frame)
        if (desktopCatalogue) {
            DesktopCoverCaption(manga, narrow, showChapter, showSource, showMetrics, bodySources, onRatings)
            return@Column
        }
        Column(BrowseTileCaption) {
            val note = browseNoteColor()
            CoverTileTitle(manga.title, dense, narrow)
            if (showChapter) {
                // Keep the figures on a common baseline even when one source omits a chapter.
                val chapter = manga.latestChapter
                val readingOrder = if (chapter.isBlank()) Modifier.clearAndSetSemantics {} else Modifier
                BrowseChapterLine(chapter, Modifier.padding(top = 2.dp).then(readingOrder))
            }
            SearchSourceMarks(bodySources, Modifier.fillMaxWidth())
            if (showSource) MangaBrowseSource(manga, BrowseTileFootnote, foreground = note)
            else if (showMetrics) MangaMetricBadges(manga, BrowseTileFootnote, foreground = note,
                compact = true, showMore = false, plain = true, onExpand = onRatings)
        }
    }
}

/** Titles and credits stay together; comparable values align across a shelf. */
@Composable
private fun DesktopCoverCaption(manga: Manga?, narrow: Boolean, showChapter: Boolean, showSource: Boolean,
    showMetrics: Boolean, bodySources: List<Manga> = emptyList(), onRatings: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val titleStyle = if (narrow) browseTitleStyle(16.sp, 21.sp) else browseTitleStyle(18.sp, 24.sp)
    val note = browseNoteColor()
    val credits = remember(manga, showChapter) {
        if (manga == null) "" else listOfNotNull(
            manga.latestChapter.takeIf { showChapter && it.isNotBlank() },
            manga.authors.filter(String::isNotBlank).distinct().take(2).joinToString(" / ").takeIf(String::isNotEmpty),
        ).joinToString(" · ")
    }
    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = (if (narrow) 62.dp else 68.dp) * scale),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (manga == null) {
                Text("", Modifier.placeholderLines(colors.onSurface.copy(alpha = .075f), .82f, .50f),
                    style = titleStyle, minLines = 2, maxLines = 2)
                Spacer(Modifier.height(17.dp * scale).placeholderLines(note.copy(alpha = .11f), .47f))
            } else {
                Text(manga.title, style = titleStyle, color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (credits.isNotEmpty()) Text(credits, style = MaterialTheme.typography.bodySmall, color = note,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (manga == null) {
            if (showSource || showMetrics) Spacer(Modifier.height(22.dp * scale)
                .placeholderLines(note.copy(alpha = .09f), .37f))
        } else {
            SearchSourceMarks(bodySources, Modifier.fillMaxWidth())
            if (showSource) MangaBrowseSource(manga, foreground = note)
            else if (showMetrics) MangaMetricBadges(manga, foreground = note, compact = true,
                maxScores = 1, showMore = false, plain = true, onExpand = onRatings)
        }
    }
}

@Composable
private fun CoverTileTitle(title: String?, dense: Boolean, narrow: Boolean) {
    val placeholder = if (title == null) Modifier.placeholderLines(MaterialTheme.colorScheme.onSurface.copy(alpha = .075f),
        .84f, .54f) else Modifier
    val style = when {
        narrow -> browseTitleStyle(14.sp, 19.sp, FontWeight.Medium)
        dense -> browseTitleStyle(15.5f.sp, 21.sp)
        else -> browseTitleStyle(17.sp, 23.sp)
    }
    Text(title.orEmpty(), modifier = placeholder, style = style, color = MaterialTheme.colorScheme.onSurface,
        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun MangaBrowseSource(manga: Manga, modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val name = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
    Row(modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ChapterSourceLogo(manga.sourceId, name, Modifier.size(13.dp), muted = true)
        Text(name, style = MaterialTheme.typography.labelSmall, color = foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal val LocalMangaCoverFade = staticCompositionLocalOf { true }

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier,
    scale: ContentScale = ContentScale.Crop, onReady: (() -> Unit)? = null) =
    MangaCover(manga, repository, modifier, decodeSize = null, scale = scale, onReady = onReady)

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier,
    decodeSize: IntSize?, scale: ContentScale = ContentScale.Crop, onReady: (() -> Unit)? = null) {
    val context = LocalContext.current
    val desktop = platformKind == PlatformKind.DESKTOP
    val fade = !desktop && LocalMangaCoverFade.current && LocalMotionEnabled.current
    val identity = mangaCoverIdentity(manga, repository)
    val source = remember(manga.id) { dev.mangaflow.data.SourceKeys.decodeManga(manga.id).sourceId }
    val target = if (decodeSize != null) {
        var largest by remember(source, identity) { mutableStateOf<IntSize>(decodeSize) }
        val next = IntSize(maxOf(largest.width, decodeSize.width), maxOf(largest.height, decodeSize.height))
        if (next != largest) SideEffect { largest = next }
        next
    } else null
    val request = remember(context, source, identity, fade, target) {
        identity.url.takeIf(String::isNotBlank)?.let { url ->
            val memoryKey = "manga-cover:" + identity.cacheKey
            ImageRequest.Builder(imagePlatformContext(context)).data(url)
                .sourceNetwork(source)
                .memoryCacheKey(memoryKey)
                .placeholderMemoryCacheKey(memoryKey)
                .apply {
                    if (target != null) size(target.width, target.height)
                }
                .diskCacheKey(identity.cacheKey)
                .httpHeaders(NetworkHeaders.Builder().apply {
                    identity.headers.forEach { (name, value) -> set(name, value) }
                }.build()).crossfade(if (fade) MangaMotion.Quick else 0).build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        var needsPlaceholder by remember(context, manga.id, source, identity) { mutableStateOf(true) }
        if (needsPlaceholder) Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), modifier = Modifier.size(25.dp))
        if (request == null) return@Box
        key(manga.id, source, identity) {
            var displayed by remember { mutableStateOf<Painter?>(null) }
            AsyncImage(model = request, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize(),
                transform = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Loading -> displayed?.let { state.copy(painter = it) } ?: state
                        is AsyncImagePainter.State.Error -> displayed?.let { state.copy(painter = it) } ?: state
                        else -> state
                    }
                }, onState = { state ->
                    needsPlaceholder = state.painter == null
                    if (state is AsyncImagePainter.State.Success) {
                        if (desktop) displayed = state.painter
                        onReady?.invoke()
                    }
                })
        }
    }
}

@Composable
internal fun QuietLoading(message: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 34.dp)
        .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(13.dp).clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp)
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyMessage(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface, textAlign = TextAlign.Center)
            Text(description, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            if (action != null) TextButton(onClick = onAction, modifier = Modifier.padding(top = 12.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) { Text(action) }
        }
    }
}

internal fun recentReadingLocation(progress: ReadingProgress): String =
    progress.chapterTitle.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty() + if (progress.pageCount > 0)
        "${progress.pageIndex + 1}/${progress.pageCount} 页" else "第${progress.pageIndex + 1}页"

private fun browseUpdatedAt(timestamp: Long): String =
    "列表生成 / 保存于 " + formatInstantMillis(timestamp, "HH:mm")

private val BrowsePositionsSaver = listSaver<LinkedHashMap<String, LazyGridState>, Any>(
    save = { positions -> positions.entries.flatMap { (key, state) ->
        listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    } },
    restore = { saved -> LinkedHashMap<String, LazyGridState>().apply {
        saved.chunked(3).forEach { entry ->
            if (entry.size == 3) put(entry[0] as String, LazyGridState(entry[1] as Int, entry[2] as Int))
        }
    } },
)


private val SearchPositionsSaver = listSaver<LinkedHashMap<String, LazyListState>, Any>(
    save = { positions -> positions.entries.flatMap { (key, state) ->
        listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    } },
    restore = { saved -> LinkedHashMap<String, LazyListState>().apply {
        saved.chunked(3).forEach { entry ->
            if (entry.size == 3) put(entry[0] as String, LazyListState(entry[1] as Int, entry[2] as Int))
        }
    } },
)
