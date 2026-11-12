package dev.mangaflow.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemInfo
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
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
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.bottomPullLoad
import dev.mangaflow.ui.motion.rememberBottomPullLoadState
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: BrowseState,
    backgroundState: BrowseState,
    searchExpanded: Boolean,
    searchBackdrop: HazeState,
    contentTopPadding: Dp,
    canAutoAdvance: Boolean,
    repository: MangaRepository,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    isFavorite: (Manga) -> Boolean,
    onToggleFavorite: (Manga) -> Unit,
    active: Boolean = true,
) {
    val motion = LocalMotionEnabled.current
    val browse = if (state.query.isBlank()) state else backgroundState
    val positions = rememberSaveable(saver = BrowsePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    var actionsManga by remember { mutableStateOf<Manga?>(null) }
    var ratingsManga by remember { mutableStateOf<Manga?>(null) }
    val coverTransition = LocalMangaCoverTransition.current
    LaunchedEffect(active, searchExpanded) {
        if (!active || searchExpanded) { actionsManga = null; ratingsManga = null }
    }
    val unobscured = active && !searchExpanded && actionsManga == null && ratingsManga == null
    var retainedBrowse by remember { mutableStateOf(browse) }
    // Wait on data, never on an animation clock. A slow uncached selection keeps the previous
    // real list visible instead of replacing it with loading and then entering a second time.
    val holdingContent = browse.loading && browse.items.isEmpty() && retainedBrowse.items.isNotEmpty()
    val displayedBrowse = if (holdingContent) retainedBrowse else browse
    SideEffect { if (!holdingContent) retainedBrowse = browse }
    val retainedAlpha = animateFloatAsState(if (holdingContent) .48f else 1f,
        if (motion) tween(MangaMotion.Content) else snap(), label = "browse-pending-content")
    Box(Modifier.fillMaxSize().navigationBarsPadding().hazeSource(searchBackdrop)
        .motionInputGate(active && !searchExpanded)) {
        // The root header samples only these scrolling pixels, never its own controls.
        Box(Modifier.fillMaxSize()) {
            MotionTabContent(
                selected = displayedBrowse,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = retainedAlpha.value },
                // Loading -> first result gets one transition. Ratings, appends and refreshes
                // retain the populated key and never replay the whole list's entrance.
                contentKey = { it.cacheKey to it.items.isEmpty() },
                order = { it.mode.ordinal * 8 + it.period.ordinal * 2 + it.audience.ordinal },
                soft = true,
            ) { pane ->
                DiscoveryPane(pane, positions, repository,
                    active = unobscured && !holdingContent && pane.cacheKey == browse.cacheKey,
                    canAutoAdvance = canAutoAdvance,
                    onRefresh = onRefresh, onRetry = onRetry, onMore = onMore, onOpen = onOpen,
                    onManage = { actionsManga = it }, onRatings = { ratingsManga = it },
                    topPadding = contentTopPadding)
            }
            if (holdingContent) {
                val label = if (browse.mode == DiscoveryMode.RANKING)
                    "${browse.audience.label} · ${browse.period.label}" else browse.mode.label
                Row(Modifier.align(Alignment.TopEnd).padding(top = contentTopPadding + 8.dp, end = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .96f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (motion) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("正在载入$label", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    actionsManga?.let { manga ->
        MotionBottomSheet(onDismissRequest = { actionsManga = null }) { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MangaCover(manga, repository, Modifier.width(48.dp).height(72.dp).mangaCoverCard(8.dp))
                    Text(manga.title, style = MaterialTheme.typography.titleSmall,
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
) {
    var retainedSearch by remember { mutableStateOf(state) }
    SideEffect { if (expanded) retainedSearch = state }
    val search = if (expanded) state else retainedSearch
    val positions = rememberSaveable(saver = SearchPositionsSaver) { LinkedHashMap<String, LazyListState>() }
    if (search.query.isBlank() || maxHeight <= 0.dp) return
    val retainedResults = remember(search.cacheKey) {
        val saved = positions.remove(search.cacheKey) ?: LazyListState()
        positions[search.cacheKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(8.dp)
        .motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("搜索结果", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (search.loading && search.items.isEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (search.items.isNotEmpty()) {
                Text("${search.items.size} 条", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            search.loading && search.items.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center,
            ) { Text("正在查找…", style = MaterialTheme.typography.bodySmall) }
            search.items.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (search.error != null) "搜索暂时不可用" else "没有找到相关漫画",
                    style = MaterialTheme.typography.bodyMedium)
                if (search.error != null) {
                    Text(search.error, modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            else -> LazyColumn(
                state = retainedResults,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(search.items, key = Manga::id) { manga ->
                    SearchResultRow(manga, repository) { onOpen(manga) }
                }
                if (search.hasMore || search.loadingMore) {
                    item(key = "search-more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (search.loadingMore) {
                                CircularProgressIndicator(Modifier.padding(12.dp).size(19.dp), strokeWidth = 2.dp)
                            } else TextButton(onClick = onMore) { Text("更多结果") }
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
        Box(Modifier.size(38.dp).motionPress(interaction, pressedScale = .90f),
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
    val retainedGrid = remember(state.cacheKey) {
        val saved = positions.remove(state.cacheKey) ?: LazyGridState()
        positions[state.cacheKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    val loadingGrid = remember(state.cacheKey) { LazyGridState() }
    // Hydrate disk snapshots before a short loading layout can clamp the saved position.
    val grid = if (state.loading && state.items.isEmpty()) loadingGrid else retainedGrid
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), state = grid,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(if (ranking) 12.dp else 0.dp),
        modifier = Modifier.fillMaxSize().bottomPullLoad(pull).motionInputGate(active),
    ) {
        if (state.loading && state.items.isEmpty()) {
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
            if (state.mode == DiscoveryMode.RECOMMENDED) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    FeaturedCarousel(state.items.take(6), repository,
                        active && heroVisible && canAutoAdvance && !grid.isScrollInProgress, onOpen)
                }
            }
            if (!ranking) item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.mode == DiscoveryMode.LATEST) "最近更新" else "为你发现",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    DiscoveryRefresh(state, onRefresh)
                }
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
                    CoverTile(manga, repository, dense = true, modifier = Modifier.padding(bottom = 16.dp),
                        showChapter = state.mode == DiscoveryMode.LATEST, placement = "discovery-grid-${state.cacheKey}",
                        onLongClick = { onManage(manga) }) { onOpen(manga) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                if (state.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().height(64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                        if (state.hasMore || state.loadingMore) BottomPullLoadIndicator(pull, Modifier.size(32.dp))
                        Text(if (state.loadingMore) "正在加载" else if (!state.hasMore) "已经到底了"
                            else if (pull.progress >= 1f) "松手加载" else "上拉加载",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(manga: Manga, repository: MangaRepository, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .motionPress(interaction, pressedScale = .985f)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(manga, repository, Modifier.width(42.dp).height(58.dp).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga, showMore = false)
        }
    }
}

@Composable
private fun DiscoveryRefresh(state: BrowseState, onRefresh: () -> Unit) {
    val motion = LocalMotionEnabled.current
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.refreshing, motion) {
        if (!motion) rotation.snapTo(0f)
        else if (state.refreshing) {
            while (true) {
                rotation.animateTo(360f, tween(1400, easing = LinearEasing))
                rotation.snapTo(0f)
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

/** Material 3 Multi-browse owns changing item widths, rounded masks, inertia and snapping. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean, onOpen: (Manga) -> Unit) {
    if (manga.isEmpty()) return
    val state = rememberCarouselState { manga.size }
    val fling = CarouselDefaults.singleAdvanceFlingBehavior(state)
    val items = remember { mutableStateMapOf<Int, CarouselItemInfo>() }
    val focused by remember(items, manga.size) {
        derivedStateOf { items.entries.filter { it.key in manga.indices }.maxByOrNull { it.value.size }?.key ?: 0 }
    }
    val position by remember(items, manga.size) {
        derivedStateOf {
            var total = 0f
            var weighted = 0f
            items.forEach { (index, info) ->
                if (index in manga.indices) {
                    val weight = ((info.size / info.maxSize.coerceAtLeast(1f) - .35f) / .65f).coerceIn(0f, 1f)
                    total += weight
                    weighted += index * weight
                }
            }
            if (total > 0f) weighted / total else focused.toFloat()
        }
    }
    var touched by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val motion = LocalMotionEnabled.current
    val speed = with(LocalDensity.current) { 900.dp.toPx() }
    val autoAdvance = motion && visible && !touched && manga.size > 1 && accessibility?.isTouchExplorationEnabled != true
    LaunchedEffect(autoAdvance, state, fling, manga.map { it.id }, lifecycle) {
        if (autoAdvance) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(6_000)
                if (!state.isScrollInProgress) {
                    if (focused >= manga.lastIndex) direction = -1
                    if (focused <= 0) direction = 1
                    // Delegate one-item settling to the same native fling behavior used by touch.
                    state.scroll { with(fling) { performFling(speed * direction) } }
                }
            }
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val indicator by animateFloatAsState(focused.toFloat(),
        if (motion) tween(240, easing = MangaMotion.EnterEasing) else snap(), label = "featured-position")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val itemHeight = (224f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
            val peekWidth = (maxWidth * .15f).coerceIn(44.dp, 80.dp)
            HorizontalMultiBrowseCarousel(state = state,
                preferredItemWidth = if (manga.size == 1) maxWidth else (maxWidth - peekWidth - 8.dp).coerceAtLeast(180.dp),
                itemSpacing = 8.dp, minSmallItemWidth = peekWidth, maxSmallItemWidth = peekWidth,
                contentPadding = PaddingValues(0.dp), flingBehavior = fling,
                modifier = Modifier.fillMaxWidth().height(itemHeight)
                    .semantics { stateDescription = "推荐 ${focused + 1} / ${manga.size}" }
                    .pointerInput(manga.size) {
                        awaitEachGesture {
                            try {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                touched = true
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.id == down.id && it.pressed })
                            } finally { touched = false }
                        }
                    },
            ) { index ->
                val info = carouselItemInfo
                DisposableEffect(index, info) {
                    items[index] = info
                    onDispose { if (items[index] === info) items.remove(index) }
                }
                FeaturedManga(manga[index], repository,
                    modifier = Modifier.fillMaxWidth().height(itemHeight).mangaCoverCard(20.dp).maskClip(RoundedCornerShape(20.dp)),
                    emphasis = { ((info.size / info.maxSize.coerceAtLeast(1f) - .35f) / .65f).coerceIn(0f, 1f) },
                    offset = { if (motion) (index - position).coerceIn(-1f, 1f) * (if (rtl) -1f else 1f) else 0f },
                    onOpen = onOpen)
            }
        }
        if (manga.size > 1) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(width = (manga.size * 16).dp, height = 4.dp).clearAndSetSemantics { }) {
                repeat(manga.size) { index ->
                    val visualIndex = if (rtl) manga.lastIndex - index else index
                    val weight = (1f - kotlin.math.abs(indicator - index)).coerceIn(0f, 1f)
                    val width = (4f + 10f * weight).dp.toPx()
                    drawRoundRect(color.copy(alpha = .13f + .45f * weight),
                        topLeft = Offset((visualIndex * 16 + 8).dp.toPx() - width / 2f, 0f),
                        size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
        }
    }
}

@Composable
private fun FeaturedManga(
    manga: Manga,
    repository: MangaRepository,
    modifier: Modifier,
    emphasis: () -> Float,
    offset: () -> Float,
    onOpen: (Manga) -> Unit,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val metrics = observedDetailMetrics(manga)
    val heat = manga.totalPopularity ?: manga.popularity
    val transition = LocalMangaCoverTransition.current
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = "查看${manga.title}") {
            transition?.select?.invoke(MangaCoverKey(manga.id, "discovery-featured"))
            onOpen(manga)
        }.motionPress(interaction, pressedScale = .988f)
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        MangaCover(manga, repository, Modifier.matchParentSize().blur(22.dp).graphicsLayer { alpha = .38f }
            .clearAndSetSemantics { }, ContentScale.Crop)
        MangaCover(manga, repository, Modifier.matchParentSize()
            .then(if (emphasis() >= .98f) Modifier.mangaSharedCover(manga.id, "discovery-featured") else Modifier)
            .graphicsLayer { translationX = offset() * 4.dp.toPx() }.clearAndSetSemantics { }, ContentScale.Fit)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.42f)
            .background(Brush.verticalGradient(0f to Color.Transparent, .28f to Color.Black.copy(alpha = .32f),
                .7f to Color.Black.copy(alpha = .58f), 1f to Color.Black.copy(alpha = .72f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().graphicsLayer {
                alpha = emphasis()
                translationX = offset() * 8.dp.toPx()
            }.padding(horizontal = 15.dp).padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manga.title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(
                shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 5f)),
                fontSize = 17.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                heat?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReviewSourceMark("copy", size = 16.dp, onDark = true, heat = true)
                    Text(detailMetricCount(it), color = Color.White.copy(alpha = .88f), fontSize = 11.sp, lineHeight = 15.sp)
                } }
                metrics.firstOrNull()?.let { metric -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReviewSourceMark(metric.sourceId, size = 18.dp, onDark = true)
                    Text(detailMetricScore(metric.score ?: 0.0) +
                        (if (metric.scoreScale > 0 && metric.scoreScale != 10) "/${metric.scoreScale}" else ""),
                        color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                } }
            }
        }
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
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(manga.rank?.toString()?.padStart(2, '0') ?: "—", fontSize = 18.sp,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = if ((manga.rank ?: 99) <= 3) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if ((manga.rank ?: 99) <= 3) .82f else .38f),
            maxLines = 1, modifier = Modifier.widthIn(min = 30.dp).padding(end = 4.dp))
        MangaCover(manga, repository, Modifier.width(68.dp).height(98.dp)
            .mangaSharedCover(manga.id, "ranking-$listKey").mangaCoverCard(8.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp).heightIn(min = 98.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(manga.title, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(manga.authors.take(2).joinToString(" / "), fontSize = 12.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f), minLines = 1, maxLines = 1,
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
            Text(progress.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(progress.manga)
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
) {
    var chapterManga by remember { mutableStateOf<Manga?>(null) }
    val recentPosition = rememberLazyListState()
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) { dismiss ->
        MotionTabContent(selected = chapterManga, modifier = Modifier.fillMaxWidth(),
            contentKey = { it?.id ?: "recent-books" }, order = { if (it == null) 0 else 1 },
            fillContent = false, soft = true) { selected ->
            if (selected != null) ChapterHistoryContent(selected, library,
                onContinue = { onContinue(it); dismiss() }, onBack = { chapterManga = null })
            else Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近阅读", fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                    Text(history.size.toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = recentPosition,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(history, key = { it.manga.id }) { progress ->
                        SwipeToRemove(onRemove = { onRemoveHistory(progress.manga.id) }, label = "删除阅读记录") {
                            Row(Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "继续阅读", onClick = { onContinue(progress); dismiss() })
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                MangaCover(progress.manga, repository, Modifier.width(32.dp).height(44.dp).mangaCoverCard(5.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(progress.manga.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页",
                                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                MotionIconButton(onClick = { chapterManga = progress.manga }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.History, "章节阅读记录", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    modifier: Modifier = Modifier,
    showChapter: Boolean = false,
    placement: String? = null,
    onLongClick: (() -> Unit)? = null,
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
        Text(manga.title, modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
            style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp),
            compact = true, showMore = false, plain = true)
        if (showChapter) Text(manga.latestChapter, modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val request = remember(manga.id, manga.coverUrl, motion) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .memoryCacheKey("manga-cover:" + manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(25.dp))
        AsyncImage(model = request, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun QuietLoading(message: String) {
    val visible = rememberDelayedBusy(true)
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(25.dp)) {
            if (visible) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 1.5.dp)
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyMessage(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun browseUpdatedAt(timestamp: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp)) + " 更新"

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
