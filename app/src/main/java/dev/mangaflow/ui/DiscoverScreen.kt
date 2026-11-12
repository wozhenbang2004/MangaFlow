package dev.mangaflow.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
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
    canAutoAdvance: Boolean,
    repository: MangaRepository,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    onPeriod: (RankPeriod) -> Unit,
    onAudience: (RankAudience) -> Unit,
    active: Boolean = true,
) {
    val motion = LocalMotionEnabled.current
    val browse = if (state.query.isBlank()) state else backgroundState
    val positions = rememberSaveable(saver = BrowsePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    Column(Modifier.fillMaxSize().navigationBarsPadding().hazeSource(searchBackdrop)
        .motionInputGate(active && !searchExpanded)) {
        // Filters stay outside the changing list, so their indicator keeps its velocity.
        AnimatedVisibility(
            visible = browse.mode == DiscoveryMode.RANKING,
            enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Content else 0)),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkVertically(tween(if (motion) MangaMotion.Content else 0)),
        ) {
            RankingControls(browse, onPeriod, onAudience, onRefresh,
                Modifier.motionInputGate(active && !searchExpanded && browse.mode == DiscoveryMode.RANKING))
        }
        MotionTabContent(
            selected = browse,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentKey = { it.cacheKey },
            order = { it.mode.ordinal * 8 + it.period.ordinal * 2 + it.audience.ordinal },
        ) { pane ->
            DiscoveryPane(pane, positions, repository,
                active = active && !searchExpanded && pane.cacheKey == browse.cacheKey,
                canAutoAdvance = canAutoAdvance,
                onRefresh = onRefresh, onRetry = onRetry, onMore = onMore, onOpen = onOpen)
        }
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
private fun RankingControls(
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
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val shortPeriods = maxWidth < periodWidth + 100.dp
        // A fixed icon slot leaves all four periods room, including larger text sizes.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RankingPeriods(state.period, onPeriod, Modifier.weight(1f), shortPeriods)
            Spacer(Modifier.width(4.dp))
            RankingAudienceToggle(state.audience, onAudience, Modifier.width(48.dp))
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { DiscoveryRefresh(state, onRefresh) }
        }
    }
}

@Composable
private fun RankingPeriods(period: RankPeriod, onPeriod: (RankPeriod) -> Unit, modifier: Modifier, concise: Boolean = false) {
    MotionTabs(selected = period.ordinal, labels = RankPeriod.entries.map { it.label },
        onSelect = { onPeriod(RankPeriod.entries[it]) }, role = Role.RadioButton,
        modifier = modifier, fontSize = 12.sp, indicatorHeight = 30.dp,
        selectedFontWeight = FontWeight.Medium, unselectedFontWeight = FontWeight.Normal,
        itemContent = { index, selected, color ->
            val label = RankPeriod.entries[index].label
            Text(if (concise) label.take(1) else label, color = color, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, modifier = Modifier.semantics { contentDescription = label })
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
    Box(modifier.height(48.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "切换到${next.label}", onClick = { onAudience(next) })
        .semantics(mergeDescendants = true) {
            contentDescription = "榜单分类"
            stateDescription = audience.label
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).motionPress(interaction, pressedScale = .90f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .035f)),
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
                        translationX = (6f - 9f * front + (if (female) 4f else -4f) * arc).dp.toPx()
                        translationY = (-4f + 6f * front).dp.toPx()
                        scaleX = .66f + .34f * front
                        scaleY = scaleX
                        alpha = .38f + .62f * front
                        rotationY = (if (female) -1f else 1f) * 18f * arc
                        cameraDistance = 16.dp.toPx()
                    }
                    .background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
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
    val hasItems = state.items.isNotEmpty()
    val firstResult = remember(state.cacheKey) { Animatable(if (hasItems) 1f else 0f) }
    val revealDistance = with(LocalDensity.current) { 10.dp.toPx() }
    LaunchedEffect(state.cacheKey, hasItems, motion) {
        if (hasItems) {
            if (motion) firstResult.animateTo(1f, tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing))
            else firstResult.snapTo(1f)
        }
    }
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(if (ranking) 8.dp else 16.dp),
        modifier = Modifier.fillMaxSize().bottomPullLoad(pull).graphicsLayer {
            // An uncached selection first enters as loading; reveal its real rows once they arrive.
            // Refreshes, metrics and appended pages keep this value at one and do not replay.
            val visibility = if (ranking && hasItems) firstResult.value else 1f
            alpha = visibility
            translationY = (1f - visibility) * revealDistance
        },
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
                    Text(if (state.mode == DiscoveryMode.LATEST) "最近更新" else "发现好故事",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    DiscoveryRefresh(state, onRefresh)
                }
            }
            if (ranking) {
                items(state.items, key = Manga::id, span = { GridItemSpan(maxLineSpan) }) { manga ->
                    RankRow(manga, repository, state.period) { onOpen(manga) }
                }
            } else {
                items(state.items, key = Manga::id) { manga -> CoverTile(manga, repository, dense = true) { onOpen(manga) } }
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
                        if (state.hasMore || state.loadingMore) BottomPullLoadIndicator(pull, Modifier.size(24.dp))
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .motionPress(interaction, pressedScale = .985f)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(manga, repository, Modifier.width(42.dp).height(58.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga)
        }
    }
}

@Composable
private fun DiscoveryRefresh(state: BrowseState, onRefresh: () -> Unit) {
    val busy = rememberDelayedBusy(state.refreshing)
    val motion = LocalMotionEnabled.current
    MotionIconButton(onClick = onRefresh, enabled = !state.loading && !state.refreshing) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                if (motion) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else CircularProgressIndicator(progress = { 1f }, modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Icon(Icons.Outlined.Refresh,
                if (state.updatedAt > 0) "刷新漫画，${browseUpdatedAt(state.updatedAt)}" else "刷新漫画",
                Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean, onOpen: (Manga) -> Unit) {
    val pager = rememberPagerState(pageCount = { manga.size })
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val motion = LocalMotionEnabled.current
    val autoAdvance = motion && manga.size > 1 && visible && !dragged && !pressed &&
        accessibility?.isTouchExplorationEnabled != true
    LaunchedEffect(autoAdvance, pager, manga.map { it.id }, lifecycle) {
        if (autoAdvance) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(6_000)
                if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % manga.size,
                    animationSpec = tween(420, easing = MangaMotion.EnterEasing))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(state = pager, key = { manga[it].id },
            // A page occupies the entire viewport; the next recommendation never peeks in half-cut.
            contentPadding = PaddingValues(0.dp),
            pageSpacing = 0.dp, beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "推荐 ${pager.currentPage + 1} / ${manga.size}" },
        ) { page -> FeaturedManga(manga[page], repository, interaction, onOpen) }
        if (manga.size > 1) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(width = (manga.size * 18).dp, height = 4.dp)) {
                val position = if (motion) pager.currentPage + pager.currentPageOffsetFraction else pager.currentPage.toFloat()
                repeat(manga.size) { index ->
                    val emphasis = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                    val width = (4f + 10f * emphasis).dp.toPx()
                    drawRoundRect(color.copy(alpha = .15f + .5f * emphasis),
                        topLeft = Offset((index * 18 + 9).dp.toPx() - width / 2f, 0f),
                        size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
        }
    }
}

@Composable
private fun FeaturedManga(manga: Manga, repository: MangaRepository, interaction: MutableInteractionSource, onOpen: (Manga) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val height = 28.dp + 176.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)
        .clickable(interactionSource = interaction, indication = null) { onOpen(manga) }
        .motionPress(interaction, pressedScale = .985f)
        .clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant)) {
        val coverWidth = (maxWidth * .36f).coerceIn(88.dp, 122.dp)
        // The repeated artwork is only a quiet backdrop; the actual cover below is never cropped.
        MangaCover(manga, repository, Modifier.matchParentSize().blur(24.dp)
            .graphicsLayer { alpha = .2f }, ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
            colors.surface.copy(alpha = .35f), colors.surface.copy(alpha = .88f)))))
        Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MangaCover(manga, repository, Modifier.width(coverWidth).height(176.dp)
                .clip(RoundedCornerShape(10.dp)), ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(manga.title, color = colors.onSurface, fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                MangaMetricBadges(manga, Modifier.fillMaxWidth(), compact = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(manga.authors.take(2).joinToString(" · ").ifBlank { "翻开，进入故事" },
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "查看漫画", tint = colors.onSurface,
                        modifier = Modifier.padding(start = 6.dp).size(19.dp))
                }
            }
        }
    }
}

@Composable
private fun RankRow(manga: Manga, repository: MangaRepository, period: RankPeriod, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, pressedScale = .985f)
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(manga.rank?.toString()?.padStart(2, '0') ?: "—", fontSize = 17.sp,
            fontWeight = if ((manga.rank ?: 99) <= 3) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if ((manga.rank ?: 99) <= 3) .92f else .4f),
            maxLines = 1, modifier = Modifier.widthIn(min = 30.dp))
        MangaCover(manga, repository, Modifier.width(54.dp).height(76.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga, heatLabel = when (period) {
                RankPeriod.DAY -> "日热"
                RankPeriod.WEEK -> "周热"
                RankPeriod.MONTH -> "月热"
                RankPeriod.TOTAL -> "总热"
            })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueRow(
    progress: ReadingProgress, repository: MangaRepository, onContinue: (ReadingProgress) -> Unit,
    onManage: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .combinedClickable(interactionSource = interaction, indication = null,
            onClickLabel = "继续阅读", onClick = { onContinue(progress) },
            onLongClickLabel = if (onManage != null) "管理阅读记录" else null,
            onLongClick = onManage)
        .motionPress(interaction, pressedScale = .985f).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(progress.manga, repository, Modifier.width(46.dp).height(64.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(progress.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(progress.manga)
        }
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RecentReadingSheet(
    history: List<ReadingProgress>,
    repository: MangaRepository,
    onDismiss: () -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    var managedId by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<ReadingProgress?>(null) }
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) { dismiss ->
        Text("最近阅读", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(history, key = { it.manga.id }) { progress ->
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClickLabel = "继续阅读", onClick = { onContinue(progress); dismiss() },
                            onLongClickLabel = "管理阅读记录", onLongClick = { managedId = progress.manga.id })
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MangaCover(progress.manga, repository, Modifier.width(36.dp).height(50.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(progress.manga.title, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.PlayArrow, "继续阅读", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RecordActionsMenu(managedId == progress.manga.id, onDismiss = { managedId = null },
                        removeLabel = "删除阅读记录", onRemove = { removal = progress })
                }
            }
        }
    }
    RecordRemovalDialog(
        prompt = removal?.let { RecordRemovalPrompt("删除阅读记录？", it.manga.title,
            "这本漫画的本机阅读进度会一并清除，收藏和下载保留。") },
        onDismiss = { removal = null },
        onConfirm = { removal?.let { onRemoveHistory(it.manga.id) }; removal = null },
    )
}

@Composable
internal fun CoverTile(manga: Manga, repository: MangaRepository, dense: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction)) {
        MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
        Text(manga.title, modifier = Modifier.padding(top = 9.dp, start = 2.dp, end = 2.dp),
            style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp), compact = true)
        val secondary = manga.latestChapter.ifBlank { if (dense) "" else manga.authors.firstOrNull().orEmpty() }
        if (secondary.isNotBlank()) Text(secondary, modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val request = remember(manga.id, manga.coverUrl, motion) {
        ImageRequest.Builder(context).data(manga.coverUrl)
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
