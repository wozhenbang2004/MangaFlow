package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ViewModule
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.BrowseState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.RankAudience
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.LibraryStore
import androidx.compose.material.icons.outlined.History
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.HistoryActionIcon
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.HazeState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin

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
    active: Boolean = true,
) {
    val browse = if (!state.isSearch) state else backgroundState
    val positions = rememberSaveable(saver = BrowsePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    var actionsManga by remember { mutableStateOf<Manga?>(null) }
    var ratingsManga by remember { mutableStateOf<Manga?>(null) }
    val sourceFan = remember { MetricSourceFanState() }
    val coverTransition = LocalMangaCoverTransition.current
    LaunchedEffect(active, searchExpanded) {
        if (!active || searchExpanded) { actionsManga = null; ratingsManga = null; sourceFan.clear() }
    }
    LaunchedEffect(browse.cacheKey) { sourceFan.clear() }
    val unobscured = active && !searchExpanded && actionsManga == null && ratingsManga == null && sourceFan.request == null
    CompositionLocalProvider(LocalMetricSourceFan provides sourceFan) {
    Box(Modifier.fillMaxSize().mangaHazeSource(searchBackdrop)
        .background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)
        .motionInputGate(active && !searchExpanded)) {
        // Wallpaper and scroll content are one captured scene. The header remains a foreground sibling.
        wallpaper()
        Box(Modifier.fillMaxSize()) {
            MotionTabContent(
                selected = browse,
                modifier = Modifier.fillMaxSize(),
                // Selection owns one entrance. Cache hydration and network results
                // update that same pane instead of entering it a second time.
                contentKey = { it.cacheKey },
                order = { it.mode.ordinal * 8 + it.period.ordinal * 2 + it.audience.ordinal },
                soft = true,
            ) { pane ->
                val selectedPane = pane.cacheKey == browse.cacheKey
                var exitTopPadding by remember(pane.cacheKey) { mutableStateOf(contentTopPadding) }
                SideEffect { if (selectedPane) exitTopPadding = contentTopPadding }
                // The outgoing ranking keeps its last clearance while its subbar
                // returns; a new selection cannot jerk that leaving list upward.
                DiscoveryPane(pane, positions, repository,
                    columns = (if (pane.mode == DiscoveryMode.LATEST) updateColumns else discoverColumns).let {
                        if (platformKind == PlatformKind.DESKTOP) it.coerceIn(0, 8) else it.coerceIn(1, 4)
                    },
                    onColumnsChange = if (pane.mode == DiscoveryMode.LATEST) onUpdateColumnsChange else onDiscoverColumnsChange,
                    backdrop = searchBackdrop,
                    active = unobscured && selectedPane,
                    canAutoAdvance = canAutoAdvance,
                    onRefresh = onRefresh, onRetry = onRetry, onMore = onMore, onOpen = onOpen,
                    onManage = { actionsManga = it }, onRatings = { ratingsManga = it },
                    topPadding = if (selectedPane) contentTopPadding else exitTopPadding)
            }

        }
    }
    }
    MetricSourceFan(sourceFan)
    actionsManga?.let { manga ->
        MotionBottomSheet(onDismissRequest = { actionsManga = null }) { dismiss ->
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
                MangaQuickAction(Icons.Outlined.StarOutline, "各站评分") {
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
) {
    var retainedSearch by remember { mutableStateOf(state) }
    SideEffect { if (expanded) retainedSearch = state }
    val search = if (expanded) state else retainedSearch
    val federated = search.searchTarget == SearchTarget.TITLE && search.selectedTags.isEmpty() && search.query.isNotBlank()
    var sourceChoices by remember(search.cacheKey) { mutableStateOf<List<Manga>?>(null) }
    val transition = LocalMangaCoverTransition.current
    LaunchedEffect(active, expanded, search.cacheKey) {
        if (!active || !expanded) sourceChoices = null
    }
    val openResult: (Manga) -> Unit = { manga ->
        val matches = search.sourceMatches[manga.id].orEmpty()
        if (federated && manga.id in search.sourceChoiceIds && matches.size > 1) {
            transition?.select?.invoke(null)
            sourceChoices = matches
        } else onOpen(manga)
    }
    val openSource: (List<Manga>) -> Unit = { matches ->
        transition?.select?.invoke(null)
        if (matches.size == 1) onOpen(matches.first())
        else if (matches.isNotEmpty()) sourceChoices = matches
    }
    val positions = rememberSaveable(saver = SearchPositionsSaver) { LinkedHashMap<String, LazyListState>() }
    if (maxHeight <= 0.dp) return
    val retainedResults = remember(search.cacheKey) {
        val saved = positions.remove(search.cacheKey) ?: LazyListState()
        positions[search.cacheKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    LaunchedEffect(active, retainedResults) { if (!active) retainedResults.stopScroll() }
    var followStart by remember(retainedResults) {
        mutableStateOf(retainedResults.firstVisibleItemIndex == 0 && retainedResults.firstVisibleItemScrollOffset == 0)
    }
    LaunchedEffect(retainedResults) {
        snapshotFlow { retainedResults.isScrollInProgress }.collect { scrolling ->
            if (scrolling) followStart = false
        }
    }
    val resultKeys = remember(search.items) { search.items.map(Manga::id) }
    LaunchedEffect(retainedResults, resultKeys, followStart) {
        // Keyed incremental results otherwise retain an old item halfway down the viewport
        // when another source inserts matches above it. A deliberate scroll owns its anchor.
        if (followStart && !retainedResults.isScrollInProgress) retainedResults.requestScrollToItem(0)
    }
    val selectingTags = search.searchTarget == SearchTarget.TAG && search.selectedTags.isEmpty()
    val awaitingKeyword = search.query.isBlank() && search.selectedTags.isEmpty()
    // Empty title/author search is only the 44dp input row; no padded, invisible result panel.
    if (awaitingKeyword && !selectingTags) return
    val showResults = search.isSearch && !selectingTags && !awaitingKeyword
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        val contentRoom = (this.maxHeight - if (tagsOnly) 0.dp else 16.dp).coerceAtLeast(0.dp)
        val resultReserve = if (showResults) minOf(contentRoom * .5f, 120.dp * fontScale) else 0.dp
        val controlsRoom = (contentRoom - resultReserve).coerceAtLeast(0.dp)
        val fixedScope = (if (tagsOnly || scopeInToolbar) 0.dp else 42.dp) +
            (if (search.selectedTags.isEmpty()) 0.dp else 42.dp) +
            if ((federated && search.searchSources.isNotEmpty()) ||
                (showResults && search.items.isNotEmpty())) 28.dp else 0.dp
        val choicesRoom = (controlsRoom - fixedScope).coerceIn(0.dp, 200.dp)
        Column(Modifier.fillMaxWidth().heightIn(max = this.maxHeight).padding(if (tagsOnly) 0.dp else 8.dp)
            .motionInputGate(active && sourceChoices == null)) {
            // Chrome receives a finite share before the result list is measured. Even the
            // expanded tag/source directory can scroll here instead of measuring the list to zero.
            Column(Modifier.fillMaxWidth().heightIn(max = controlsRoom).verticalScroll(rememberScrollState())) {
                SearchScopeBar(search.searchTarget, search.query, search.selectedTags, search.availableTags,
                    search.tagsLoading, search.tagsError, onTarget, onTag, onRetryTags, tagsOnly = tagsOnly,
                    onTagQuery = onTagQuery, maxChoicesHeight = choicesRoom, showScope = !scopeInToolbar)
                if ((federated && search.searchSources.isNotEmpty()) ||
                    (showResults && search.items.isNotEmpty())) Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp)
                        .padding(horizontal = if (tagsOnly) 0.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (federated) SearchSourceSummary(search.searchSources, onRetry, Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (showResults && search.items.isNotEmpty()) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "已显示${search.items.size}本漫画" +
                                if (search.hasMore) "，可继续加载" else ""
                        }) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(search.items.size.toString() + if (search.hasMore) "+" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (search.loading && !federated)
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            when {
                selectingTags || awaitingKeyword || !search.isSearch -> Unit
                search.loading && search.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "正在查找漫画" },
                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                search.items.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (search.error != null) "暂不可用" else "没有匹配",
                        style = MaterialTheme.typography.bodyMedium)
                    if (search.error != null) {
                        Text(search.error, modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    if (search.error == null && search.hasMore) {
                        if (search.loadingMore) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = onMore) { Text("继续筛选") }
                    }
                }
                else -> LazyColumn(
                    state = retainedResults,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(search.items, key = Manga::id) { manga ->
                        SearchResultRow(manga, repository, "search-${search.cacheKey}",
                            horizontalInset = if (tagsOnly) 0.dp else 6.dp,
                            bodySources = if (federated) search.sourceMatches[manga.id].orEmpty() else emptyList(),
                            onSource = if (federated) openSource else null) { openResult(manga) }
                    }
                    if (search.hasMore || search.loadingMore) {
                        item(key = "search-more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (search.loadingMore) {
                                    CircularProgressIndicator(Modifier.padding(12.dp).size(19.dp), strokeWidth = 2.dp)
                                } else MotionIconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.ExpandMore, "加载更多结果", Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (search.error != null) item(key = "search-error") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(search.error, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
    sourceChoices?.let { matches ->
        FederatedSearchChoices(matches, repository, backdrop, onDismiss = { sourceChoices = null },
            onOpen = { manga -> sourceChoices = null; onOpen(manga) })
    }
}

@Composable
internal fun RankingControls(
    state: BrowseState,
    onPeriod: (RankPeriod) -> Unit,
    onAudience: (RankAudience) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
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
        if (maxWidth >= periodWidth + 92.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RankingPeriods(state.period, onPeriod, Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                RankingAudienceToggle(state.audience, onAudience, Modifier.width(44.dp))
                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { DiscoveryRefresh(state, onRefresh) }
            }
        } else {
            // Keep the actual period names and every choice at large font scales.
            // Only the tools wrap; no clipped Total tab or single-letter period names.
            val wrapLabels = maxWidth < periodWidth
            Column(Modifier.fillMaxWidth()) {
                RankingPeriods(state.period, onPeriod, Modifier.fillMaxWidth(), wrapLabels)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    RankingAudienceToggle(state.audience, onAudience, Modifier.width(44.dp))
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { DiscoveryRefresh(state, onRefresh) }
                }
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
private fun DiscoveryPane(
    state: BrowseState,
    positions: LinkedHashMap<String, LazyGridState>,
    repository: MangaRepository,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    backdrop: HazeState,
    active: Boolean,
    canAutoAdvance: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    onManage: (Manga) -> Unit,
    onRatings: (Manga) -> Unit,
    topPadding: Dp,
) {
    var layoutPresent by remember(state.cacheKey) { mutableStateOf(false) }
    val retainedGrid = remember(state.cacheKey) {
        val saved = positions.remove(state.cacheKey) ?: LazyGridState()
        positions[state.cacheKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    val loadingGrid = remember(state.cacheKey) { LazyGridState() }
    // Hydrate disk snapshots before a short loading layout can clamp the saved position.
    val grid = if (state.loading && state.items.isEmpty()) loadingGrid else retainedGrid
    LaunchedEffect(active, grid) { if (!active) grid.stopScroll() }
    val heroVisible by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.any { it.key == "hero" } }
    }
    val ranking = state.mode == DiscoveryMode.RANKING
    val motion = LocalMotionEnabled.current
    val firstEntranceIndex = remember(state.cacheKey) { retainedGrid.firstVisibleItemIndex }
    val pull = rememberBottomPullLoadState(
        key = state.cacheKey,
        enabled = active && state.items.isNotEmpty() && state.hasMore && !state.loading &&
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
    val inset = MangaLayout.sidePadding(maxWidth) + if (ranking)
        ((maxWidth - MangaLayout.ListWidth) / 2f).coerceAtLeast(0.dp) else 0.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val desktop = platformKind == PlatformKind.DESKTOP
    val gap = if (desktop) 12.dp else if (columns == 4) 8.dp else 10.dp
    // The saved setting is the preferred maximum; narrow windows and large type reflow.
    val visibleColumns = when {
        ranking || columns == 1 -> 1
        desktop && columns == 0 -> MangaLayout.automaticDesktopColumns(maxWidth, fontScale, gap, inset)
        else -> MangaLayout.columns(maxWidth, columns,
            (if (!desktop && columns == 4) 68.dp else 88.dp) * fontScale, gap, inset)
    }
    val sectionControls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.mode == DiscoveryMode.LATEST) "最近更新" else "为你发现",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            DiscoveryLayoutControl(columns, visibleColumns, backdrop, active, onColumnsChange,
                onPresenceChange = { layoutPresent = it })
            DiscoveryRefresh(state, onRefresh)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(visibleColumns), state = grid,
        contentPadding = PaddingValues(start = inset, end = inset, top = topPadding,
            bottom = 32.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(if (ranking) 6.dp else if (columns == 1) 12.dp else 10.dp),
        modifier = Modifier.fillMaxSize().bottomPullLoad(pull).motionInputGate(active),
    ) {
        if (state.mode == DiscoveryMode.RECOMMENDED && (state.loading || state.items.isNotEmpty())) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                Column(Modifier.fillMaxWidth()) {
                    FeaturedCarousel(state.items.take(6), repository,
                        active && heroVisible && canAutoAdvance && !grid.isScrollInProgress && !layoutPresent,
                        onOpen = onOpen, onRatings = onRatings, loading = state.loading)
                    if (state.items.isNotEmpty()) sectionControls()
                }
            }
        }
        if (state.loading && state.items.isEmpty()) {
            if (state.mode != DiscoveryMode.RECOMMENDED)
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading") { QuietLoading("正在翻开书页…") }
        } else if (state.items.isEmpty() && state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                EmptyMessage("暂时没能加载", state.error, "重试", onRetry)
            }
        } else if (state.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyMessage("暂时没有漫画", "来源暂时没有返回内容，可以稍后刷新。", "刷新", onRefresh)
            }
        } else {
            if (!ranking && state.mode != DiscoveryMode.RECOMMENDED)
                item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                sectionControls()
            }
            if (ranking) {
                itemsIndexed(state.items, key = { _, manga -> manga.id }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, manga ->
                    RankRow(manga, repository, state.period, state.cacheKey,
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap())
                            .rankingRowEntrance(index - firstEntranceIndex),
                        onRatings = { onRatings(manga) }, onClick = { onOpen(manga) })
                }
            } else {
                items(state.items, key = Manga::id) { manga ->
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap())) {
                        MotionTabContent(selected = columns == 1, modifier = Modifier.fillMaxWidth(),
                            contentKey = { it }, order = { if (it) 0 else 1 }, fillContent = false) { list ->
                            val placement = "discovery-grid-${state.cacheKey}-" + if (list) "list" else "tile"
                            if (list) BrowseListRow(manga, repository,
                                showChapter = state.mode == DiscoveryMode.LATEST, placement = placement,
                                onLongClick = { onManage(manga) }, onRatings = { onRatings(manga) }, onClick = { onOpen(manga) })
                            else CoverTile(manga, repository, dense = true, narrow = visibleColumns >= 4,
                                showChapter = state.mode == DiscoveryMode.LATEST, placement = placement,
                                onLongClick = { onManage(manga) }, onRatings = { onRatings(manga) }) { onOpen(manga) }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                if (state.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                } else {
                    if (state.hasMore || state.loadingMore) Box(Modifier.fillMaxWidth().height(48.dp)
                        .semantics { contentDescription = if (state.loadingMore) "正在加载" else if (pull.progress >= 1f)
                            "松手加载" else "上拉加载" }, contentAlignment = Alignment.Center) {
                        BottomPullLoadIndicator(pull, Modifier.size(32.dp))
                    } else Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    }
    }
}

@Composable
private fun SearchResultRow(manga: Manga, repository: MangaRepository, placement: String,
    horizontalInset: Dp = 6.dp, bodySources: List<Manga> = emptyList(),
    onSource: ((List<Manga>) -> Unit)? = null, onOpen: () -> Unit) {
    val transition = LocalMangaCoverTransition.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = {
                transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                onOpen()
            })
            .motionPress(interaction, pressedScale = .985f)
            .padding(horizontal = horizontalInset, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(manga, repository, Modifier.width(42.dp).height(58.dp).mangaSharedCover(manga.id, placement).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            SearchSourceMarks(bodySources, Modifier.fillMaxWidth(), onSource)
            MangaMetricBadges(manga, showMore = false)
        }
    }
}

/** The existing search-result composition, expanded for the saved single-column browse mode. */
@Composable
private fun BrowseListRow(
    manga: Manga,
    repository: MangaRepository,
    showChapter: Boolean,
    placement: String,
    onLongClick: () -> Unit,
    onRatings: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Row(Modifier.fillMaxWidth().combinedClickable(interactionSource = interaction,
        indication = null, role = Role.Button, onClickLabel = "查看${manga.title}",
        onLongClickLabel = "漫画操作", onLongClick = onLongClick, onClick = {
            transition?.select?.invoke(MangaCoverKey(manga.id, placement))
            onClick()
        }).motionPress(interaction, pressedScale = .985f), verticalAlignment = Alignment.CenterVertically) {
        MangaCover(manga, repository, Modifier.width(64.dp).height(92.dp)
            .mangaSharedCover(manga.id, placement).mangaCoverCard(9.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showChapter && manga.latestChapter.isNotBlank()) Text(manga.latestChapter,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga, Modifier.fillMaxWidth(), showMore = true, plain = true, onExpand = onRatings)
        }
    }
}

@Composable
private fun DiscoveryLayoutControl(
    columns: Int,
    actualColumns: Int,
    backdrop: HazeState,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onPresenceChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var closeAfterChoice by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) { if (!enabled) { expanded = false; closeAfterChoice = null } }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val motion = LocalMotionEnabled.current
    val desktop = platformKind == PlatformKind.DESKTOP
    val window = LocalMangaWindow.current
    val choices = remember(desktop) { if (desktop) listOf(0, 1, 2, 3, 4, 6, 8) else listOf(1, 2, 3, 4) }
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
    var availableWidth by remember { mutableStateOf(0.dp) }
    val cellWidth = 44.dp * density.fontScale.coerceIn(1f, 1.35f)
    val panelWidth = minOf(cellWidth * choices.size + 8.dp, availableWidth)
    val scroll = rememberScrollState()
    LaunchedEffect(expanded, scroll.maxValue) {
        // Selection moves inside the retained strip; it must not jump its
        // scroll origin while the new indicator is travelling or folding away.
        if (expanded) scroll.scrollTo(with(density) { (cellWidth * selected).roundToPx() }
            .coerceIn(0, scroll.maxValue))
    }
    val position = remember(density) {
        val gap = with(density) { 4.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr)
                    anchorBounds.left - gap - popupContentSize.width else anchorBounds.right + gap
                return IntOffset(x.coerceIn(margin,
                    (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)),
                    (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
                        .coerceIn(margin, (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)))
            }
        }
    }
    // The real trigger never leaves its row. Only a fixed-size sibling window
    // reveals the final-size choices horizontally, following the beUI search shell.
    Box(Modifier.size(44.dp).onGloballyPositioned { coordinates ->
        val origin = coordinates.localToWindow(Offset.Zero)
        if (origin.x.isFinite()) with(density) {
            val room = if (direction == LayoutDirection.Ltr) origin.x
                else window.width.toPx() - origin.x - coordinates.size.width
            availableWidth = (room.toDp() - 16.dp).coerceAtLeast(0.dp)
        }
    }) {
        MotionIconButton(
            onClick = { closeAfterChoice = null; expanded = !expanded },
            modifier = Modifier.size(44.dp).semantics {
                contentDescription = "浏览布局"
                stateDescription = labels[selected] + "，当前${actualColumns}列" +
                    if (expanded) "，已展开" else "，已收起"
            },
            enabled = enabled,
        ) {
            MotionTabContent(selected = columns to actualColumns, modifier = Modifier.size(23.dp),
                contentKey = { it }, order = { it.first }, fillContent = false) { (preferred, actual) ->
                Box(Modifier.size(23.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    Icon(if (preferred == 1) Icons.Outlined.ViewList else Icons.Outlined.ViewModule,
                        null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (desktop && preferred == 0) "自" else actual.toString(),
                        Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 1.dp),
                        fontSize = 9.sp, lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (present && panelWidth > 0.dp) Popup(popupPositionProvider = position,
            onDismissRequest = { closeAfterChoice = null; expanded = false },
            properties = PopupProperties(focusable = expanded && enabled)) {
            // Popup geometry stays final-sized throughout; animated optical width
            // never re-enters the position provider or moves the trigger.
            Box(Modifier.width(panelWidth).height(44.dp)) {
                val progress = reveal.value.coerceIn(0f, 1f)
                LiquidGlassSurface(backdrop,
                    Modifier.align(if (direction == LayoutDirection.Ltr) Alignment.CenterEnd else Alignment.CenterStart)
                        .width((panelWidth * progress).coerceAtLeast(1.dp)).height(44.dp)
                        .motionInputGate(enabled && expanded && progress >= .85f)
                        .graphicsLayer {
                            alpha = progress
                            // Keep the soft shadow's outset visible throughout alpha < 1,
                            // rather than revealing it only after offscreen clipping ends.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL, softCard = true,
                    elevation = 16.dp * progress, materialExpansion = { progress }) {
                    Row(Modifier.layout { measurable, constraints ->
                        val fullWidth = panelWidth.roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = fullWidth, maxWidth = fullWidth))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.placeRelative(constraints.maxWidth - fullWidth, 0)
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
                                    Icon(if (choice == 1) Icons.Outlined.ViewList else Icons.Outlined.ViewModule,
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
private fun DiscoveryRefresh(state: BrowseState, onRefresh: () -> Unit) {
    val motion = LocalMotionEnabled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.refreshing, motion, lifecycle) {
        if (!motion) rotation.snapTo(0f)
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
    MotionIconButton(onClick = onRefresh, modifier = Modifier.size(44.dp), enabled = !state.loading && !state.refreshing) {
        Icon(Icons.Outlined.Refresh,
            if (state.refreshing) "正在刷新漫画" else if (state.updatedAt > 0) "刷新漫画，${browseUpdatedAt(state.updatedAt)}" else "刷新漫画",
            Modifier.size(19.dp).graphicsLayer { rotationZ = rotation.value },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RankRow(manga: Manga, repository: MangaRepository, period: RankPeriod, listKey: String,
    modifier: Modifier = Modifier, onRatings: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Row(modifier.fillMaxWidth()
        .clickable(interactionSource = interaction, indication = null, onClick = {
            transition?.select?.invoke(MangaCoverKey(manga.id, "ranking-$listKey"))
            onClick()
        })
        .motionPress(interaction, pressedScale = .985f)
        .padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(manga.rank?.toString()?.padStart(2, '0') ?: "—", fontSize = 18.sp,
            style = mangaMetricValueStyle(18.sp, 24.sp),
            fontWeight = if ((manga.rank ?: 99) <= 3) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if ((manga.rank ?: 99) <= 3) .82f else .38f),
            maxLines = 1, modifier = Modifier.widthIn(min = 30.dp).padding(end = 4.dp))
        MangaCover(manga, repository, Modifier.width(54.dp).height(78.dp)
            .mangaSharedCover(manga.id, "ranking-$listKey").mangaCoverCard(6.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp).heightIn(min = 78.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "), fontSize = 12.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            RankingMetrics(manga, heatLabel = when (period) {
                RankPeriod.DAY -> "日热"
                RankPeriod.WEEK -> "周热"
                RankPeriod.MONTH -> "月热"
                RankPeriod.TOTAL -> "总热"
            }, onExpand = onRatings)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueRow(
    progress: ReadingProgress, repository: MangaRepository, onContinue: (ReadingProgress) -> Unit,
    onManage: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth()
        .combinedClickable(interactionSource = interaction, indication = null,
            onClickLabel = "继续阅读", onClick = { onContinue(progress) },
            onLongClickLabel = if (onManage != null) "管理阅读记录" else null,
            onLongClick = onManage)
        .motionPress(interaction, pressedScale = .985f).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(progress.manga, repository, Modifier.width(46.dp).height(64.dp).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(progress.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mangaNumberText("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress.pageCount > 0) {
                val fraction = ((progress.pageIndex + 1).toFloat() / progress.pageCount).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .62f)))
                }
            }
        }
        if (onHistory != null) MotionIconButton(onClick = onHistory, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.History, "章节阅读记录", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RecentReadingSheet(
    history: List<ReadingProgress>,
    repository: MangaRepository,
    library: LibraryStore,
    onDismiss: () -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onRemoveHistory: (String) -> Unit,
    backdrop: HazeState? = null,
) {
    var chapterManga by remember { mutableStateOf<Manga?>(null) }
    val recentPosition = rememberLazyListState()
    val recentFeedback = dev.mangaflow.ui.motion.rememberFeelFeedback()
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background,
        backdrop = backdrop) { dismiss ->
        MotionTabContent(selected = chapterManga, modifier = Modifier.fillMaxWidth(),
            contentKey = { it?.id ?: "recent-books" }, order = { if (it == null) 0 else 1 },
            fillContent = false, soft = true) { selected ->
            if (selected != null) ChapterHistoryContent(selected, library,
                onContinue = { onContinue(it); dismiss() }, onBack = { chapterManga = null })
            else Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近阅读", fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                    Text(history.size.toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = recentPosition,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(history, key = { it.manga.id }) { progress ->
                        val rowInteraction = remember(progress.manga.id) { MutableInteractionSource() }
                        SwipeToRemove(onRemove = { onRemoveHistory(progress.manga.id) }, label = "删除阅读记录",
                            transparentBackground = true) {
                            Row(Modifier.fillMaxWidth()
                                .clickable(interactionSource = rowInteraction, indication = null, role = Role.Button,
                                    onClickLabel = "继续阅读", onClick = { onContinue(progress); dismiss() })
                                .motionPress(rowInteraction, pressedScale = .985f)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                MangaCover(progress.manga, repository, Modifier.width(36.dp).height(50.dp).mangaCoverCard(5.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(progress.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(mangaNumberText("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页"),
                                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                MotionIconButton(onClick = {
                                    recentFeedback.perform(dev.mangaflow.ui.motion.FeelEvent.Selection)
                                    chapterManga = progress.manga
                                }, modifier = Modifier.size(44.dp)) {
                                    HistoryActionIcon(active = chapterManga?.id == progress.manga.id,
                                        description = "章节阅读记录", modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
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
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Column(modifier
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看${manga.title}", onLongClickLabel = if (onLongClick != null) "漫画操作" else null,
            onLongClick = onLongClick, onClick = {
                if (placement != null) transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                onClick()
            })
        .motionPress(interaction)) {
        MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .then(if (placement != null) Modifier.mangaSharedCover(manga.id, placement) else Modifier).mangaCoverCard(12.dp))
        Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
            style = if (narrow) MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp)
                else if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SearchSourceMarks(bodySources, Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp))
        MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp),
            compact = true, showMore = false, plain = true, onExpand = onRatings)
        if (showChapter && manga.latestChapter.isNotBlank()) Text(manga.latestChapter, modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val request = remember(manga.id, manga.coverUrl, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).data(manga.coverUrl)
            .memoryCacheKey("manga-cover:" + manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(25.dp))
        AsyncImage(model = request, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun QuietLoading(message: String) {
    Text(message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
}

@Composable
internal fun EmptyMessage(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
        .padding(vertical = 48.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun browseUpdatedAt(timestamp: Long): String =
    formatInstantMillis(timestamp, "HH:mm") + " 更新"

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
