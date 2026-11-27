package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localDateAt
import dev.mangaflow.ui.platform.localToday
import dev.mangaflow.ui.platform.minusDays
import dev.mangaflow.ui.platform.plusDays
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.math.roundToInt

private const val FEATURED_PREVIEW = 6
private const val WATCH_HISTORY_PREVIEW = 8

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit, onOpenHistory: () -> Unit,
    contentBottomPadding: Dp, contentTopPadding: Dp,
    onOpenSchedule: (() -> Unit)? = null, onOpenRanking: (() -> Unit)? = null) {
    val ratings = LocalAnimeRatings.current
    val browseActive = active
    fun openScores(item: AnimeDiscoveryItem) {
        if (browseActive) ratings.open(item.ratingRequest())
    }
    // Two native feed owners, one visible destination. Changing period must not animate
    // away the same 放送 surface or reset either feed's query-owned scroll position.
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it.broadcastDestination() },
        order = { it.broadcastDestination().ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding, contentTopPadding,
                onOpen = onOpenItem, onScores = ::openScores, onManage = onManage,
                onResume = onResume, onOpenHistory = onOpenHistory,
                onOpenSchedule = onOpenSchedule, onOpenRanking = onOpenRanking,
                active = browseActive && state.discoveryTab == tab)
            AnimeDiscoveryTab.SOURCES -> Unit
            else -> {
                val key = animeDiscoveryFeedKey(tab)
                val feed = state.discoveryFeeds[key]
                val tabActive = browseActive && state.discoveryTab == tab
                val ranking = tab == AnimeDiscoveryTab.RANKING
                var showFilters by remember(tab) { mutableStateOf(false) }
                LaunchedEffect(tabActive) { if (!tabActive) showFilters = false }
                val contentActive = tabActive && !showFilters
                // The slot already fences replies by query. Also reject input from an
                // outgoing ranking grid or button before its recomposition catches up.
                fun ownsCurrentFeed(): Boolean {
                    if (!ranking) return true
                    val current = controller.state.value
                    return tabActive && feed != null && current.discoveryTab == tab &&
                        current.discoveryFeeds[key]?.query == feed.query
                }
                fun configure(next: AnimeDiscoveryQuery) {
                    if (ownsCurrentFeed()) controller.configureDiscovery(tab, next)
                }
                fun refresh() {
                    if (ownsCurrentFeed()) controller.refreshDiscovery()
                }
                val broadcast = tab.broadcastDestination() == AnimeDiscoveryTab.CALENDAR
                val today = if (broadcast) animeCalendarToday(tabActive) else null
                val calendarDate = today?.takeIf { tab == AnimeDiscoveryTab.CALENDAR }
                val staleWeek = calendarDate != null && feed != null &&
                    (feed.fetchedAt != null || feed.page > 0 || feed.items.isNotEmpty()) &&
                    feed.fetchedAt?.let { localDateAt(it).animeWeekStart() } != calendarDate.animeWeekStart()
                // The endpoint supplies weekdays, not dates. Never relabel a cached prior
                // week's rows as this week's. Refresh is explicit if the saved feed is stale.
                val visibleFeed = if (staleWeek && feed != null) feed.copy(items = emptyList(), hasMore = false,
                    error = if (feed.loading) null else feed.error ?: "上次记录不是本周番表，请刷新。") else feed
                // Controls scroll with the works; only workspace navigation stays floating.
                val catalogueHeader: (@Composable (Dp) -> Unit)? = if (today != null || feed != null) {
                    { gridWidth ->
                        val gridActions: @Composable () -> Unit = {
                            AnimeCatalogueGridActions(gridWidth, state.discoveryColumns, backdrop, contentActive,
                                controller::setDiscoveryColumns,
                                onRefresh = ::refresh,
                                loading = feed?.loading == true || feed?.loadingMore == true)
                        }
                        if (today != null) AnimeBroadcastControls(
                            activeTab = tab, today = today,
                            seasonQuery = state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.SEASON)]?.query,
                            calendarQuery = state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.CALENDAR)]?.query,
                            active = tabActive, filtersOpen = showFilters,
                            onOpenFilters = { showFilters = true },
                            onConfigure = { nextTab, query ->
                                val live = controller.state.value
                                // A settling gesture cannot act on a different open feed.
                                val targetKey = animeDiscoveryFeedKey(nextTab)
                                val sameTarget = !useAnimeBroadcastDial || (tabActive &&
                                    live.discoveryFeeds[targetKey]?.query == state.discoveryFeeds[targetKey]?.query)
                                if (sameTarget && !showFilters && live.discoveryTab == tab &&
                                    live.discoveryFeeds[key]?.query == feed?.query) {
                                    controller.configureDiscovery(nextTab, query)
                                }
                            }, gridActions = gridActions)
                        else if (feed != null) AnimeDiscoveryControls(tab, feed.query, tabActive, showFilters,
                            onOpenFilters = { if (ownsCurrentFeed()) showFilters = true },
                            onConfigure = ::configure, gridActions = gridActions)
                    }
                } else null
                val grid: @Composable (Modifier, Dp) -> Unit = { modifier, topInset ->
                    AnimeDiscoveryFeedGrid(key, visibleFeed, state.discoveryColumns, scrolls, contentBottomPadding,
                        modifier = modifier.mangaHazeSource(backdrop).motionInputGate(contentActive), active = contentActive,
                        onOpen = { if (ownsCurrentFeed()) onOpenItem(it) },
                        onScores = { if (ownsCurrentFeed()) openScores(it) },
                        onManage = { if (ownsCurrentFeed()) onManage(it) }, onRefresh = ::refresh,
                        onRetry = {
                            if (ownsCurrentFeed()) {
                                if (!staleWeek) controller.retryDiscovery(key)
                                else {
                                    val current = controller.state.value
                                    val currentFeed = current.discoveryFeeds[key]
                                    if (current.discoveryTab == tab && currentFeed != null && !currentFeed.loading &&
                                        currentFeed.query == feed?.query && currentFeed.fetchedAt == feed?.fetchedAt) {
                                        controller.refreshDiscovery()
                                    }
                                }
                            }
                        },
                        onMore = { if (ownsCurrentFeed()) controller.loadMoreDiscovery(key) },
                        onClearTag = { feed?.let { configure(it.query.withTags(emptyList())) } },
                        onShowFullWeek = { feed?.let { configure(it.query.copy(weekday = null)) } },
                        catalogueHeader = catalogueHeader, contentTopPadding = topInset, calendarDate = calendarDate)
                }
                if (ranking && feed != null) {
                    Column(Modifier.fillMaxSize()) {
                        // Keep the source switch visible when the site's existing query returns
                        // to a scrolled position. Sorting/filters still travel with the list.
                        AnimeRankingProviderBar(feed.query.provider, state.discoveryColumns, contentActive, contentTopPadding) { provider ->
                            if (provider != feed.query.provider && ownsCurrentFeed()) controller.selectRankingProvider(provider)
                        }
                        grid(Modifier.fillMaxWidth().weight(1f), 0.dp)
                    }
                } else grid(Modifier.fillMaxSize(), contentTopPadding)
                // A restored query can scroll off the lazy header as soon as Apply
                // runs. The editor must stay mounted long enough to actually close.
                if (tabActive && showFilters && feed != null) androidx.compose.runtime.key(feed.query.provider.takeIf { ranking }) {
                    AnimeDiscoveryFilterPanel(tab, feed.query, controller, backdrop,
                        rememberedOptions = { controller.rememberedDiscoveryOptions(tab, it) },
                        onDismiss = { showFilters = false }, onConfigure = ::configure)
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, contentTopPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit, onManage: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, onOpenHistory: () -> Unit,
    onOpenSchedule: (() -> Unit)?, onOpenRanking: (() -> Unit)?, active: Boolean) {
    val key = animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME)
    val feed = state.discoveryFeeds[key]
    val query = feed?.query ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
    var showFilters by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) showFilters = false }
    val contentActive = active && !showFilters
    val overview = query.order == AnimeDiscoveryOrder.POPULARITY && query.region.isBlank() &&
        query.selectedTags.isEmpty() && query.year == null && query.format != AnimeDiscoveryFormat.MOVIE
    val featured = remember(feed?.items, overview) {
        val items = feed?.items.orEmpty()
        // Leave works in the catalogue even when a source returned only a short first page.
        val count = (items.size - 3).coerceIn(0, FEATURED_PREVIEW)
        if (!overview) emptyList() else items.asSequence().filter { it.coverUrl.isNotBlank() }
            .distinctBy { it.key }.take(count).map { item ->
                val provider = item.discoveryProvider ?: AnimeDiscoveryProvider.DOUBAN
                val rated = item.copy(sourceScores = animeDiscoverySourceScores(item, provider))
                AnimeFeaturedEntry(key = "douban:" + item.key, title = item.title, coverUrl = item.coverUrl,
                    sourceId = animeDiscoverySourceId(item.coverProvider ?: provider),
                    caption = listOfNotNull(item.year?.toString(),
                        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · "),
                    discoveryItem = rated)
            }.toList()
    }
    val recent = remember(state.library, state.libraryReadUnavailable, overview) {
        if (!overview || state.libraryReadUnavailable) emptyList() else state.library.asSequence()
            .filter { it.resume?.belongsToWatchHistory(it.subject.id) == true }
            .sortedByDescending { it.resume?.updatedAt ?: 0L }
            .take(WATCH_HISTORY_PREVIEW).toList()
    }
    AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
        modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(contentActive), active = contentActive,
        onOpen = onOpen, onScores = onScores, onManage = onManage,
        onRefresh = controller::refreshDiscovery, onRetry = { controller.retryDiscovery(key) },
        onMore = { controller.loadMoreDiscovery(key) },
        onClearTag = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, query.withTags(emptyList())) },
        featured = featured, contentTopPadding = contentTopPadding,
        watchShelf = if (recent.isEmpty()) null else {
            { AnimeDiscoveryWatchShelf(recent, scrolls.watchHistory, contentActive, onOpenHistory,
                continuationLabel = { entry -> animeContinuationLabel(entry, controller.continuationEpisode(entry.subject.id)) },
                onResume = onResume) }
        },
        catalogueHeader = { gridWidth ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimeHomeDiscoveryShortcuts(state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.CALENDAR)],
                    animeCalendarToday(contentActive), contentActive, onOpenSchedule, onOpenRanking)
                AnimeHomeCatalogueHeader(query, backdrop, contentActive, refreshing = feed?.loading == true && feed.items.isNotEmpty(),
                    onOrder = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, query.copy(order = it)) },
                    onFilter = { showFilters = true },
                    gridActions = {
                        AnimeCatalogueGridActions(gridWidth, state.discoveryColumns, backdrop, contentActive,
                            controller::setDiscoveryColumns, onRefresh = controller::refreshDiscovery,
                            loading = feed?.loading == true || feed?.loadingMore == true)
                    })
            }
        })
    if (active && showFilters) AnimeDiscoveryFilterPanel(AnimeDiscoveryTab.HOME, query, controller, backdrop,
        rememberedOptions = { controller.rememberedDiscoveryOptions(AnimeDiscoveryTab.HOME, it) },
        onDismiss = { showFilters = false },
        onConfigure = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, it) })
}

@Composable
private fun AnimeDiscoveryWatchShelf(entries: List<AnimeLibraryEntry>, scroll: LazyListState, active: Boolean,
    onOpenHistory: () -> Unit, continuationLabel: (AnimeLibraryEntry) -> String,
    onResume: (AnimeLibraryEntry) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AnimeDiscoverySectionHeading("最近观看", "", onMore = onOpenHistory.takeIf { active },
            moreLabel = "全部观看历史")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // At narrow widths, let the next real card peek rather than showing a fake second slot.
            val width = if (entries.size == 1) minOf(maxWidth, 290.dp) else
                (maxWidth * .61f).coerceIn(184.dp, 258.dp).coerceAtMost((maxWidth - 24.dp).coerceAtLeast(1.dp))
            LazyRow(Modifier.fillMaxWidth(), state = scroll, userScrollEnabled = active,
                contentPadding = PaddingValues(top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.subject.id }, contentType = { "watch-history" }) { entry ->
                    AnimeDiscoveryResumeRow(entry, continuationLabel(entry), active, Modifier.width(width), onResume)
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryResumeRow(entry: AnimeLibraryEntry, action: String, active: Boolean,
    modifier: Modifier, onResume: (AnimeLibraryEntry) -> Unit) {
    val progress = entry.resume ?: return
    val colors = MaterialTheme.colorScheme
    val shape = remember { RoundedCornerShape(16.dp) }
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    Row(modifier
        .motionPress(interaction, enabled = active, pressedScale = .99f)
        .clip(shape).background(colors.surfaceContainerLow)
        .clickable(interactionSource = interaction, indication = null, enabled = active,
            role = Role.Button, onClickLabel = action, onClick = { onResume(entry) })
        .heightIn(min = 84.dp).padding(start = 6.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        AnimePoster(entry.subject, Modifier.size(44.dp, 66.dp).clearAndSetSemantics { })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.subject.title, style = animeWorkTitleStyle().copy(fontSize = 15.sp, lineHeight = 20.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(animeWatchedEpisodeName(entry, progress), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A long episode title must not push the real resume timestamp out of the card.
            Text(animeWatchPosition(progress), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!progress.completed) animeWatchFraction(progress)?.let { fraction ->
                key(entry.subject.id, progress.episodeId) {
                    AnimeWatchProgressLine(fraction, Modifier.widthIn(max = 164.dp).padding(top = 2.dp))
                }
            }
        }
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp), tint = colors.primary)
    }
}

@Composable
private fun AnimeHomeCatalogueHeader(query: AnimeDiscoveryQuery, backdrop: HazeState, active: Boolean,
    refreshing: Boolean, onOrder: (AnimeDiscoveryOrder) -> Unit, onFilter: () -> Unit,
    gridActions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val restrictions = animeHomeScope(query)
    val orders = animeDiscoveryOrders(AnimeDiscoveryProvider.DOUBAN)
    fun title(order: AnimeDiscoveryOrder): String = when (order) {
        AnimeDiscoveryOrder.NEWEST -> "最近上架"
        AnimeDiscoveryOrder.SCORE -> "高分番剧"
        else -> "热门番剧"
    }
    var showOrders by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) showOrders = false }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = active, role = Role.Button, onClickLabel = "更改番剧排序") { showOrders = true }
                    .semantics(mergeDescendants = true) { heading() }
                    .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title(query.order), Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
                CompactGlassPopover(showOrders && active, backdrop, onDismiss = { showOrders = false },
                    alignToStart = true, maximumWidth = 184.dp) {
                    Column(Modifier.selectableGroup()) {
                        orders.forEach { order ->
                            val selected = order == query.order
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    showOrders = false
                                    if (!selected) onOrder(order)
                                }).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(title(order), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onSurface)
                            }
                        }
                    }
                }
            }
            if (refreshing) Icon(Icons.Outlined.Sync, "正在更新", Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            gridActions()
            val filterLabel = if (restrictions.isBlank()) "筛选番剧" else "筛选 · $restrictions"
            MotionIconButton(onClick = onFilter, enabled = active, modifier = Modifier.size(44.dp), tooltip = filterLabel) {
                Icon(if (restrictions.isBlank()) Icons.Outlined.Tune else Icons.Outlined.FilterAlt,
                    filterLabel, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (restrictions.isNotBlank()) AnimeQuietLabel(restrictions, Modifier.padding(bottom = 2.dp))
    }
}

/** Label search uses the same provider filters, retained result ranges and exact catalogue cards as browse. */
@Composable
internal fun AnimeTagSearch(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, draft: String, choosing: Boolean, active: Boolean, maxHeight: Dp,
    onChoosingChange: (Boolean) -> Unit, onPrepareFilters: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit,
    onOpen: (AnimeDiscoveryItem) -> Unit, onManage: (AnimeSubject) -> Unit) {
    val tab = AnimeDiscoveryTab.GENRES
    val key = animeDiscoveryFeedKey(tab)
    val feed = state.discoveryFeeds[key]
    val query = feed?.query ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
    val selectedTags = query.selectedTags
    val showChoices = choosing || feed == null
    var filtersOpen by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) filtersOpen = false }
    if (maxHeight <= 0.dp) return
    val contentActive = active && !filtersOpen
    fun apply(next: AnimeDiscoveryQuery) {
        if (active && controller.state.value.discoveryFeeds[key]?.query == feed?.query) onConfigure(next)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).motionInputGate(contentActive)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selectedTags.size > 1) "同时匹配所选标签" else "标签筛选",
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                AnimeQuietLabel(animeDiscoveryConditions(query.withTags(emptyList()), includeTime = true))
            }
            if (feed != null && selectedTags.isEmpty()) {
                val action = if (showChoices) "返回标签结果" else "选择标签"
                MotionIconButton(onClick = { onChoosingChange(!showChoices) }, enabled = contentActive,
                    modifier = Modifier.size(44.dp), tooltip = action) {
                    Icon(if (showChoices) Icons.Outlined.Close else Icons.Outlined.Search, action, Modifier.size(18.dp))
                }
            }
            MotionIconButton(onClick = { onPrepareFilters(); filtersOpen = true }, enabled = contentActive,
                modifier = Modifier.size(44.dp), tooltip = "标签来源与筛选") {
                Icon(Icons.Outlined.Tune, "标签来源与筛选", Modifier.size(18.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            AnimeTagPicker(controller, query.provider, selectedTags, draft, contentActive,
                maxChoicesHeight = (maxHeight - 112.dp).coerceAtLeast(0.dp),
                choosing = showChoices, onChoosing = onChoosingChange,
                onToggle = { tag ->
                    apply(query.withTags(if (tag in selectedTags) selectedTags - tag else selectedTags + tag))
                })
            if (showChoices && selectedTags.isEmpty()) MotionTextButton(
                onClick = { apply(query.withTags(emptyList())) }, enabled = contentActive,
                modifier = Modifier.heightIn(min = 44.dp)) {
                Text("浏览全部")
            }
        }
        if (!showChoices && feed != null) {
            val ratings = LocalAnimeRatings.current
            fun currentFeed() = controller.state.value.discoveryFeeds[key]?.takeIf { contentActive && it.query == query }
            fun currentItem(item: AnimeDiscoveryItem) = currentFeed()?.items?.firstOrNull { it.key == item.key }
            AnimeDiscoveryFeedGrid(key, feed, 1, scrolls, 8.dp,
                modifier = Modifier.fillMaxWidth().weight(1f), active = contentActive,
                onOpen = { item -> currentItem(item)?.let(onOpen) },
                onScores = { item -> currentItem(item)?.let { ratings.open(it.ratingRequest()) } },
                onManage = { subject -> currentFeed()?.items?.firstNotNullOfOrNull {
                    it.subject?.takeIf { current -> current.id == subject.id }
                }?.let(onManage) },
                onRefresh = { if (currentFeed() != null) controller.refreshDiscoveryTags() },
                onRetry = { if (currentFeed() != null) controller.retryDiscovery(key) },
                onMore = { if (currentFeed() != null) controller.loadMoreDiscovery(key) },
                onClearTag = { apply(query.withTags(emptyList())) })
        }
    }
    if (active && filtersOpen) AnimeDiscoveryFilterPanel(tab, query, controller, backdrop,
        rememberedOptions = { controller.rememberedDiscoveryOptions(tab, it) },
        onDismiss = { filtersOpen = false }, onConfigure = ::apply)
}

@Composable
internal fun AnimeDiscoverySectionHeading(title: String, caption: String, refreshing: Boolean = false,
    onMore: (() -> Unit)? = null, moreLabel: String = "查看全部$title", expanded: Boolean? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (caption.isNotBlank()) Text(caption, Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (refreshing) Icon(Icons.Outlined.Sync, "正在更新$title", Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onMore != null) MotionIconButton(onClick = onMore, modifier = Modifier.size(44.dp), tooltip = moreLabel) {
            Icon(when (expanded) {
                true -> Icons.Outlined.ExpandLess
                false -> Icons.Outlined.ExpandMore
                null -> Icons.Outlined.ChevronRight
            }, moreLabel, Modifier.size(21.dp))
        }
    }
}

@Composable
internal fun AnimeDiscoveryInlineStatus(text: String, retry: (() -> Unit)? = null, retryLabel: String = "重试",
    showIssue: Boolean = retry != null, detail: String? = null) {
    if (text.isBlank()) return
    var expanded by remember(text, detail) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showIssue) Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (expanded || overflow || !detail.isNullOrBlank()) MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp),
            tooltip = if (expanded) "收起详情" else "查看完整详情") {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起详情" else "查看完整详情", Modifier.size(18.dp))
        }
        if (retry != null) MotionTextButton(onClick = retry, modifier = Modifier.heightIn(min = 44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(retryLabel)
        }
    }
    CopyShelfReveal(expanded && !detail.isNullOrBlank()) {
        AnimeQuietLabel(detail.orEmpty(), Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp))
    }
    }
}

@Composable
internal fun AnimeCatalogueGridActions(width: Dp, preference: Int?, backdrop: HazeState, active: Boolean,
    onChange: (Int) -> Unit, onRefresh: (() -> Unit)? = null, loading: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DiscoveryLayoutControl(animePreferredColumns(width, preference), animeDiscoveryColumns(width, preference),
            backdrop, active, onChange = onChange, onPresenceChange = {})
        // Phone users already have the same pull-to-refresh action on the catalogue.
        if (platformKind == PlatformKind.DESKTOP && onRefresh != null) {
            MotionIconButton(onClick = onRefresh, enabled = active && !loading,
                modifier = Modifier.size(44.dp), tooltip = "刷新当前栏目") {
                Icon(Icons.Outlined.Refresh, "刷新当前栏目", Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryFeedGrid(key: String, feed: AnimeDiscoveryFeed?, preference: Int?,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, modifier: Modifier, active: Boolean,
    onOpen: (AnimeDiscoveryItem) -> Unit, onScores: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, onRefresh: () -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit, onClearTag: () -> Unit, onShowFullWeek: (() -> Unit)? = null,
    featured: List<AnimeFeaturedEntry> = emptyList(),
    watchShelf: (@Composable () -> Unit)? = null, catalogueHeader: (@Composable (Dp) -> Unit)? = null,
    contentTopPadding: Dp = 0.dp, calendarDate: LocalDate? = null) {
    val retainedScroll = scrolls.grid(key, feed?.query)
    // The query's completed page range is restored by AnimeDiscoveryLoads before this state is used.
    // A temporary empty/loading layout must not clamp that saved position to its single status row.
    val loadingScroll = remember(key, feed?.query) { LazyGridState() }
    val hasWorks = feed?.items?.isNotEmpty() == true
    val scroll = if (hasWorks) retainedScroll else loadingScroll
    val galleryInView by remember(scroll) {
        derivedStateOf { scroll.layoutInfo.visibleItemsInfo.any { it.key == "featured" } }
    }
    val works = remember(feed?.items, featured) {
        val items = feed?.items.orEmpty()
        if (featured.isEmpty()) items else {
            val featuredKeys = featured.map { it.discoveryItem.key }.toSet()
            items.filterNot { it.key in featuredKeys }
        }
    }
    val fullWeek = feed?.query?.let { it.calendar && it.weekday == null } == true
    val hasCalendarArtwork = remember(feed?.query?.calendar, works) {
        feed?.query?.calendar != true || works.any { it.coverUrl.isNotBlank() || !it.subject?.coverUrl.isNullOrBlank() }
    }
    val calendarDays = remember(fullWeek, works) {
        if (fullWeek) works.groupBy { it.airWeekday?.takeIf { day -> day in 1..7 } ?: 8 }
            .toList().sortedBy { it.first } else emptyList()
    }
    val combined = feed?.query?.combined == true
    val hasScopedErrors = combined && feed?.sourceErrors?.isNotEmpty() == true
    val desktop = platformKind == PlatformKind.DESKTOP
    val pagingAvailable = feed != null && feed.items.isNotEmpty() && feed.hasMore && !feed.loading &&
        !feed.loadingMore && feed.error == null && feed.moreError == null && !hasScopedErrors
    val pull = rememberBottomPullLoadState(key = key to feed?.query,
        enabled = !desktop && active && pagingAvailable, loading = feed?.loadingMore == true,
        canScrollForward = { scroll.canScrollForward }, onLoadMore = onMore, actionLabel = "加载更多番剧")
    Box(modifier) {
    MotionRefreshBox(enabled = active && feed?.loading != true && feed?.loadingMore != true,
        refreshing = feed?.loading == true && feed.items.isNotEmpty(), onRefresh = onRefresh,
        indicatorTop = contentTopPadding + 4.dp, actionLabel = "刷新番剧") {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preference)
        val inset = animeDiscoveryInset(maxWidth, preference, columns)
        if (desktop) {
            val trailingKeys = remember(works, columns) { works.takeLast(columns * 2).map { it.key }.toSet() }
            AnimeBrowsePagination(key = key to feed?.query, refreshKey = feed?.fetchedAt,
                itemCount = feed?.items?.size ?: 0, enabled = active && pagingAvailable,
                nearEnd = {
                    val info = scroll.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    last != null && last.index >= info.totalItemsCount - columns * 2 - 1 &&
                        (trailingKeys.isEmpty() || info.visibleItemsInfo.any { it.key in trailingKeys })
                }, onMore = onMore)
        }
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
            .then(if (desktop) Modifier else Modifier.bottomPullLoad(pull)),
            userScrollEnabled = active,
            contentPadding = PaddingValues(start = inset, end = inset, top = contentTopPadding + 4.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(animeDiscoveryGap(columns)),
            verticalArrangement = Arrangement.spacedBy(when {
                columns == 1 -> 9.dp
                desktop -> 20.dp
                columns >= 4 -> 13.dp
                else -> 16.dp
            })) {
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = active, visible = active && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpenScores = { entry -> if (active) onScores(entry.discoveryItem) },
                    onOpen = { entry -> if (active) onOpen(entry.discoveryItem) })
            }
            if (watchShelf != null) item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) { watchShelf() }
            if (catalogueHeader != null) item(key = "discovery-heading", span = { GridItemSpan(maxLineSpan) }) {
                catalogueHeader(maxWidth)
            }
            if (feed?.error != null && !hasScopedErrors) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                val listName = when (key) {
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME) -> "发现"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.SEASON) -> animeDiscoverySeasonTitle(feed.query) + "番单"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.CALENDAR) -> "番表"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.RANKING) -> "番剧榜单"
                    else -> "标签番剧"
                }
                val provider = if (feed.query.combined) "" else animeDiscoveryProviderLabel(feed.query.provider) + " · "
                val timestamp = feed.fetchedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
                val status = when {
                    feed.retryScheduled -> "${provider}等待自动重试 · 第 ${feed.retryAttempt} 次"
                    feed.items.isNotEmpty() -> "${provider}暂未更新，保留上次结果"
                    else -> "$provider${listName}暂未载入"
                }
                val detail = listOfNotNull(feed.error,
                    timestamp?.let { "当前内容更新于 $it" }).joinToString("\n")
                AnimeDiscoveryInlineStatus(status, onRetry.takeIf { active && !feed.loading },
                    retryLabel = if (feed.retryScheduled) "立即重试" else "重试", detail = detail)
            }
            if (feed != null && hasScopedErrors && feed.moreError == null) item(
                key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryPartialStatus(feed, onRetry.takeIf { active && !feed.loading && !feed.loadingMore })
            }
            if (feed == null || feed.items.isEmpty()) {
                if (feed?.error == null && !hasScopedErrors) {
                    if (feed == null || feed.loading || feed.page == 0) {
                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeQuietLabel(if ((feed?.retryAttempt ?: 0) > 0) "正在重新载入番剧" else "正在载入番剧",
                                Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        items((columns * 2).coerceIn(3, 8), key = { "pending-poster:$it" },
                            contentType = { "pending-poster" }) {
                            AnimeDiscoveryPlaceholder(list = columns == 1)
                        }
                    } else item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        val narrowCalendar = feed.query.calendar && feed.query.weekday != null
                        val reset = when {
                            !active -> null
                            narrowCalendar -> onShowFullWeek
                            feed.query.selectedTags.isNotEmpty() -> onClearTag
                            else -> null
                        }
                        AnimeDiscoveryInlineStatus(when {
                            feed.hasMore -> "本页暂无匹配的番剧"
                            narrowCalendar -> "这一天暂无放送条目"
                            feed.query.calendar -> "本周暂无放送条目"
                            else -> "没有符合当前条件的番剧"
                        }, reset, retryLabel = if (narrowCalendar) "查看全周" else "清除标签", showIssue = false)
                    }
                }
            } else if (fullWeek) {
                calendarDays.forEach { (day, shows) ->
                    item(key = "weekday:$day", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoverySectionHeading(
                            if (day in 1..7) animeBroadcastDateLabel(day, calendarDate) else "放送日期待定",
                            if (day == calendarDate?.dayOfWeek?.isoDayNumber) "今天 · ${shows.size} 部" else "${shows.size} 部")
                    }
                    items(shows, key = { it.key }, contentType = { "anime-work" }) { item ->
                        AnimeDiscoveryCard(item, feed.query, list = columns == 1, showPoster = hasCalendarArtwork,
                            onScores = onScores.takeIf { active },
                            onManage = item.subject?.takeIf { active }?.let { subject -> { onManage(subject) } },
                            onOpen = { if (active) onOpen(item) })
                    }
                }
            } else {
                val weekday = feed.query.weekday
                if (feed.query.calendar && weekday != null && weekday in 1..7) item(
                    key = "weekday:$weekday", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoverySectionHeading(animeBroadcastDateLabel(weekday, calendarDate),
                        if (weekday == calendarDate?.dayOfWeek?.isoDayNumber) "今天 · ${works.size} 部" else "${works.size} 部",
                        refreshing = feed.loading)
                }
                items(works, key = { it.key }, contentType = { "anime-work" }) { item ->
                    AnimeDiscoveryCard(item, feed.query, list = columns == 1, showPoster = hasCalendarArtwork, onScores = onScores.takeIf { active },
                        onManage = item.subject?.takeIf { active }?.let { subject -> { onManage(subject) } },
                        onOpen = { if (active) onOpen(item) })
                }
            }
            if (feed != null && hasScopedErrors && feed.moreError != null) item(
                key = "partial-more", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryPartialStatus(feed, onRetry.takeIf { active && !feed.loading && !feed.loadingMore })
            }
            if (feed?.moreError != null && !hasScopedErrors) item(key = "more-error", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(if (feed.retryScheduled) "下一页将自动重试 · 第 ${feed.retryAttempt} 次"
                    else if (combined) "下一页暂未载入" else "第 ${feed.page.toLong() + 1} 页暂未载入",
                    onRetry.takeIf { active && !feed.loadingMore },
                    retryLabel = if (feed.retryScheduled) "立即重试" else "重试本页", detail = feed.moreError)
            }
            if (feed != null && (feed.hasMore || feed.loadingMore) && (combined || feed.moreError == null)) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                if (desktop || hasScopedErrors || feed.moreError != null || feed.items.isEmpty()) MotionTextButton(onClick = onMore,
                    enabled = active && !feed.loading && !feed.loadingMore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (feed.loadingMore) "正在加载" else if (feed.items.isEmpty()) "继续查找" else "加载更多")
                } else Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    BottomPullLoadIndicator(pull, Modifier.size(32.dp))
                }
            }
        }
        if (desktop) DesktopVerticalScrollbar(scroll,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }
    }
    }
}

@Composable
private fun AnimeDiscoveryPlaceholder(list: Boolean) {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
    val poster = Modifier.background(surface, RoundedCornerShape(10.dp))
    val lines: @Composable () -> Unit = {
        Spacer(Modifier.fillMaxWidth(.80f).height(8.dp).background(ink, RoundedCornerShape(4.dp)))
        Spacer(Modifier.fillMaxWidth(.46f).height(6.dp).background(ink, RoundedCornerShape(3.dp)))
    }
    if (list) Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.size(58.dp, 84.dp).then(poster))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { lines() }
    } else Column(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Spacer(Modifier.fillMaxWidth().aspectRatio(2f / 3f).then(poster))
        lines()
    }
}

@Composable
internal fun AnimeDiscoveryLoading(label: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        // The owner supplies the actual operation label; there is no measured progress bar.
        AnimeQuietLabel(label)
    }
}

private val animeWeekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
private fun animeCalendarToday(active: Boolean): LocalDate {
    val today by produceState(localToday(), active) {
        // Keep the civil week and quarter current across midnight without polling the source.
        while (active) {
            value = localToday()
            delay(60_000L)
        }
    }
    return today
}

private fun LocalDate.animeWeekStart(): LocalDate = minusDays((dayOfWeek.isoDayNumber - 1).toLong())

private fun animeBroadcastDateLabel(day: Int, today: LocalDate?): String {
    val weekday = animeWeekdays[day - 1]
    val date = today?.animeWeekStart()?.plusDays((day - 1).toLong()) ?: return weekday
    return "$weekday · ${date.monthNumber}/${date.dayOfMonth}"
}

private fun animeHomeScope(query: AnimeDiscoveryQuery): String = listOfNotNull(
    query.region.takeIf(String::isNotBlank)?.let { if (it == "大陆") "国产" else it },
    "电影".takeIf { query.format == AnimeDiscoveryFormat.MOVIE },
    query.year?.toString(),
    query.selectedTags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
).joinToString(" · ")

@Composable
private fun AnimeDiscoveryPartialStatus(feed: AnimeDiscoveryFeed, onRetry: (() -> Unit)?) {
    val paging = feed.moreError != null
    val phase = if (paging) "部分资料来源续页未完成" else "${feed.sourceErrors.size} 个资料来源暂未更新"
    val status = if (feed.items.isEmpty()) phase else "$phase · 现有条目仍可浏览"
    val detail = feed.sourceErrors.entries.joinToString("\n") { (source, error) ->
        "${animeDiscoveryProviderLabel(source)} · $error"
    }
    AnimeDiscoveryInlineStatus(status, retry = onRetry, retryLabel = if (paging) "重试" else "重试资料",
        showIssue = true, detail = detail)
}

private fun animeDiscoverySeasonTitle(query: AnimeDiscoveryQuery): String =
    listOfNotNull(query.year?.toString(), query.season?.let(::animeSeasonLabel))
        .joinToString(" ").ifBlank { "全部季度" }

private fun animeDiscoveryConditions(query: AnimeDiscoveryQuery, includeTime: Boolean = false,
    caption: String = animeDiscoveryCaption(query)): String =
    listOfNotNull(caption,
        animeDiscoverySeasonTitle(query).takeIf { includeTime && query.year != null },
        query.selectedTags.takeIf { it.isNotEmpty() }?.joinToString(" · ") { animeDiscoveryTagLabel(query.provider, it) },
        query.region.takeIf(String::isNotBlank), query.format?.let(::animeDiscoveryFormatLabel)).joinToString(" · ")

private fun AnimeDiscoveryTab.broadcastDestination(): AnimeDiscoveryTab =
    if (this == AnimeDiscoveryTab.SEASON) AnimeDiscoveryTab.CALENDAR else this

private fun LocalDate.animeQuarterIndex(): Int = year * 4 + (monthNumber - 1) / 3

private fun AnimeDiscoveryQuery.animeQuarterIndex(): Int? =
    year?.let { year -> season?.let { year * 4 + it.ordinal } }

private fun AnimeDiscoveryQuery.withAnimeQuarter(index: Int): AnimeDiscoveryQuery =
    copy(year = index / 4, season = AnimeSeason.entries[index % 4])

private const val FirstAnimeQuarter = 4
private const val AnimeQuarterCount = 9998 * 4
private val useAnimeBroadcastDial: Boolean
    get() = platformKind == PlatformKind.ANDROID || platformKind == PlatformKind.DESKTOP

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeBroadcastControls(activeTab: AnimeDiscoveryTab, today: LocalDate,
    seasonQuery: AnimeDiscoveryQuery?, calendarQuery: AnimeDiscoveryQuery?, active: Boolean,
    filtersOpen: Boolean, onOpenFilters: () -> Unit,
    onConfigure: (AnimeDiscoveryTab, AnimeDiscoveryQuery) -> Unit, gridActions: @Composable () -> Unit) {
    val current = today.animeQuarterIndex()
    val season = seasonQuery ?: AnimeDiscoveryQuery().withAnimeQuarter(current)
    val calendar = calendarQuery ?: AnimeDiscoveryQuery(calendar = true)
    val inWeek = activeTab == AnimeDiscoveryTab.CALENDAR
    val selected = if (inWeek) current else season.animeQuarterIndex()
    val enabled = active && !filtersOpen
    val motion = LocalMotionEnabled.current
    val filterFocus = remember { FocusRequester() }
    var filterFocused by remember { mutableStateOf(false) }
    var returnFilterFocus by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, inWeek) {
        if (!active || inWeek) returnFilterFocus = false
        else if (enabled && returnFilterFocus) {
            returnFilterFocus = false
            filterFocus.requestFocus()
        }
    }

    fun openWeek(day: Int?) {
        if (enabled && (!inWeek || calendar.weekday != day)) {
            onConfigure(AnimeDiscoveryTab.CALENDAR, calendar.copy(weekday = day))
        }
    }
    fun openQuarter(quarter: Int) {
        if (enabled) onConfigure(AnimeDiscoveryTab.SEASON, season.withAnimeQuarter(quarter))
    }
    fun selectQuarter(quarter: Int) {
        if (quarter == current) openWeek(calendar.weekday) else openQuarter(quarter)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val center = selected ?: season.year?.let { it * 4 + current % 4 } ?: current
        if (useAnimeBroadcastDial) {
            AnimeBroadcastDial("季度", "上一个季度", "下一个季度",
                noSelection = if (inWeek) "当前显示周表" else animeDiscoverySeasonTitle(season),
                pageCount = AnimeQuarterCount, anchor = (center - FirstAnimeQuarter).coerceIn(0, AnimeQuarterCount - 1),
                selectionVisible = selected != null, owner = Triple(activeTab, calendarQuery, seasonQuery),
                active = enabled, pageKey = { it + FirstAnimeQuarter }, mark = { page ->
                    val quarter = page + FirstAnimeQuarter
                    val year = quarter / 4
                    val name = animeSeasonLabel(AnimeSeason.entries[quarter % 4])
                    AnimeDialMark("$year", name, "$year 年$name" + if (quarter == current) "，本季" else "")
                }, onSelect = { selectQuarter(it + FirstAnimeQuarter) })
        } else {
            val quarters = remember(center) { (center - 1..center + 1).filter { it / 4 in 1..9998 } }
            val quarterLabels = remember(quarters) { quarters.map { animeDiscoverySeasonTitle(season.withAnimeQuarter(it)) } }
            BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = "季度" }) {
                AnimeBroadcastRail(quarterLabels, quarters.indexOf(center), selected != null, maxWidth,
                    owner = Triple(activeTab, if (inWeek) calendar else season, current), enabled = enabled,
                    onSelect = { index -> selectQuarter(quarters[index]) })
            }
        }

        // Only the live weekly feed owns dated controls. A seasonal list never presents
        // a weekday selector that cannot change the works beneath it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val weekStart = today.animeWeekStart()
                val end = weekStart.plusDays(6)
                Text(if (inWeek) "本周番表" else "季度番单", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AnimeQuietLabel(if (inWeek)
                    "${weekStart.monthNumber}/${weekStart.dayOfMonth}–${end.monthNumber}/${end.dayOfMonth} · Bangumi"
                else listOfNotNull(animeDiscoverySeasonTitle(season).takeIf { selected == null },
                    animeDiscoveryConditions(season)).joinToString(" · "))
            }
            gridActions()
            if (!inWeek) MotionIconButton(onClick = {
                returnFilterFocus = filterFocused
                onOpenFilters()
            }, enabled = enabled && seasonQuery != null, modifier = Modifier.size(44.dp)
                .focusRequester(filterFocus).onFocusChanged { filterFocused = it.isFocused }, tooltip = "筛选季度番单") {
                Icon(Icons.Outlined.Tune, "筛选季度番单", Modifier.size(19.dp))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inWeek) {
                if (calendar.weekday != null) AnimeBroadcastAction("查看全周", enabled) { openWeek(null) }
                AnimeBroadcastAction("看本季番单", enabled) { openQuarter(current) }
            } else if (selected == current) {
                AnimeBroadcastAction("查看本周番表", enabled) { openWeek(calendar.weekday) }
            } else {
                AnimeBroadcastAction("回到本季", enabled) { openWeek(calendar.weekday) }
            }
        }
        // Retain the dated controls through exit and reverse the same height on return.
        // Only the live weekly feed can receive input, including during a partial exit.
        AnimatedVisibility(visible = inWeek,
            modifier = Modifier.fillMaxWidth().motionInputGate(enabled && inWeek),
            enter = expandVertically(
                animationSpec = if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                expandFrom = Alignment.Bottom),
            exit = shrinkVertically(
                animationSpec = if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap(),
                shrinkTowards = Alignment.Bottom), label = "broadcast-week") {
            if (useAnimeBroadcastDial) AnimeWeeklyDial(today, calendar, enabled && inWeek, ::openWeek)
            else AnimeWeeklyDays(today, calendar.weekday, enabled && inWeek, ::openWeek)
        }
    }
}

@Composable
private fun AnimeBroadcastAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    MotionTextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) }
}

private class BroadcastRailRelease(val position: Float, val anchor: Int, val select: Boolean,
    val fromDrag: Boolean = false)

/** Adopted fluid tabs: a single horizontal recognizer and an indicator that follows the finger. */
@Composable
private fun AnimeBroadcastRail(labels: List<String>, selected: Int, selectionVisible: Boolean,
    width: Dp, owner: Any, enabled: Boolean, onSelect: (Int) -> Unit,
    height: Dp = 48.dp, itemContent: (@Composable (Int, Boolean, Color) -> Unit)? = null) {
    val direction = LocalLayoutDirection.current
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    val latest by rememberUpdatedState(onSelect)
    val position = remember(owner, selected, width, enabled, direction, motion) { mutableStateOf<Float?>(null) }
    var release by remember(owner, selected, width, enabled, direction, motion) { mutableStateOf<BroadcastRailRelease?>(null) }
    LaunchedEffect(owner, selected, width, enabled, direction, motion, release) {
        val settling = release ?: return@LaunchedEffect
        val spec = if (motion) spring<Float>(dampingRatio = MangaMotion.TabLeadingDamping,
            stiffness = MangaMotion.TabLeadingStiffness) else snap<Float>()
        Animatable(settling.position).animateTo(settling.anchor.toFloat(), animationSpec = spec) {
            if (release === settling) position.value = value
        }
        if (release !== settling || !enabled) return@LaunchedEffect
        if (settling.select && (!selectionVisible || settling.anchor != selected)) {
            if (settling.fromDrag) feedback.perform(FeelEvent.Selection)
            latest(settling.anchor)
            // Keep the thumb on its anchor until the native query replaces this owner.
        } else position.value = null
        release = null
    }
    val gesture = if (enabled) Modifier.pointerInput(owner, selected, width, direction, motion, labels.size) {
        fun anchorAt(x: Float): Float {
            val fraction = x / size.width.coerceAtLeast(1)
            val logical = if (direction == LayoutDirection.Rtl) 1f - fraction else fraction
            return (logical * labels.size - .5f).coerceIn(0f, labels.lastIndex.toFloat())
        }
        try {
            detectHorizontalDragGestures(
                onDragStart = { release = null; position.value = anchorAt(it.x) },
                onHorizontalDrag = { change, _ -> position.value = anchorAt(change.position.x) },
                onDragCancel = { position.value?.let { release = BroadcastRailRelease(it, selected, false) } },
                onDragEnd = { position.value?.let {
                    release = BroadcastRailRelease(it, it.roundToInt().coerceIn(labels.indices), true, fromDrag = true)
                } },
            )
        } finally {
            release = null
            position.value = null
        }
    } else Modifier
    MotionTabs(selected, labels, onSelect = { index ->
        if (enabled && (!selectionVisible || index != selected)) {
            release = BroadcastRailRelease(position.value ?: selected.toFloat(), index, true)
        } else {
            release = null
            position.value = null
        }
    }, modifier = Modifier.width(width).motionInputGate(enabled).then(gesture),
        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = height,
        track = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .9f),
        selectionVisible = selectionVisible, scrubPosition = position, itemContent = itemContent)
}

@Composable
private fun AnimeWeeklyDial(today: LocalDate, calendar: AnimeDiscoveryQuery, active: Boolean, onSelect: (Int) -> Unit) {
    val weekStart = today.animeWeekStart()
    val days = remember(weekStart) { animeWeekdays.indices.map { weekStart.plusDays(it.toLong()) } }
    AnimeBroadcastDial("本周日期", "前一天", "后一天", noSelection = "当前显示全周",
        pageCount = days.size, anchor = ((calendar.weekday ?: today.dayOfWeek.isoDayNumber) - 1).coerceIn(days.indices),
        selectionVisible = calendar.weekday != null, owner = weekStart to calendar, active = active,
        pageKey = { days[it].toString() }, mark = { index ->
            val date = days[index]
            val weekday = animeWeekdays[index]
            AnimeDialMark(weekday + if (date == today) " · 今" else "", "${date.monthNumber}/${date.dayOfMonth}",
                "${date.monthNumber}月${date.dayOfMonth}日 $weekday" + if (date == today) "，今天" else "")
        }, onSelect = { index ->
            if (localToday().animeWeekStart() == weekStart) onSelect(index + 1)
        })
}

@Composable
private fun AnimeWeeklyDays(today: LocalDate, selectedDay: Int?, active: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val todayIndex = today.dayOfWeek.isoDayNumber - 1
    val weekStart = today.animeWeekStart()
    val days = remember(weekStart) { animeWeekdays.indices.map { weekStart.plusDays(it.toLong()) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val minimum = maxOf(44.dp, 38.dp * density.fontScale) * days.size
        val selectedIndex = selectedDay?.minus(1) ?: todayIndex
        val height = maxOf(58.dp, 38.dp * density.fontScale + 12.dp)
        val labels = remember(days) { days.map { it.dayOfMonth.toString() } }
        val selectDay: (Int) -> Unit = { if (active && selectedDay != it + 1) onSelect(it + 1) }
        val dayCell: @Composable (Int, Boolean, Color) -> Unit = { index, isSelected, color ->
            val date = days[index]
            val isToday = index == todayIndex
            Column(Modifier.clearAndSetSemantics {
                contentDescription = "${date.monthNumber}月${date.dayOfMonth}日 ${animeWeekdays[index]}" +
                    if (isToday) "，今天" else ""
            }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(animeWeekdays[index], style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(labels[index], style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (isToday) colors.onSurface else color, maxLines = 1,
                        fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal)
                    if (isToday) Spacer(Modifier.size(5.dp).background(colors.primary, RoundedCornerShape(50)))
                }
            }
        }
        if (minimum <= maxWidth) {
            AnimeBroadcastRail(labels, selectedIndex, selectedDay != null, maxWidth, weekStart to selectedDay,
                enabled = active, onSelect = selectDay, height = height, itemContent = dayCell)
        } else {
            // If all seven minimum hit targets can't fit, let users reveal any day without
            // selecting intermediate days. Only this overflow branch owns horizontal panning.
            val centered = with(density) {
                (minimum / days.size * (selectedIndex + .5f) - maxWidth / 2f)
                    .coerceIn(0.dp, minimum - maxWidth).roundToPx()
            }
            Box(Modifier.horizontalScroll(rememberScrollState(centered), enabled = active)) {
                MotionTabs(selectedIndex, labels, selectDay, modifier = Modifier.width(minimum).motionInputGate(active),
                    controlHeight = height, treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
                    track = colors.surfaceContainerHigh.copy(alpha = .9f),
                    selectionVisible = selectedDay != null, itemContent = dayCell)
            }
        }
    }
}

private val rankingProviders = listOf(
    AnimeDiscoveryProvider.DOUBAN, AnimeDiscoveryProvider.BANGUMI, AnimeDiscoveryProvider.ANILIST)

@Composable
private fun AnimeRankingProviderBar(provider: AnimeDiscoveryProvider, columnPreference: Int?, active: Boolean,
    topInset: Dp, onSelect: (AnimeDiscoveryProvider) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = topInset + 4.dp).motionInputGate(active)) {
        val inset = animeDiscoveryInset(maxWidth, columnPreference, animeDiscoveryColumns(maxWidth, columnPreference))
        Box(Modifier.fillMaxWidth().padding(horizontal = inset).semantics { paneTitle = "分站榜单" }) {
            AnimeFilterChoices(rankingProviders, provider, ::animeDiscoveryProviderLabel, enabled = active, onSelect = onSelect)
        }
    }
}

@Composable
private fun AnimeDiscoveryControls(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery, active: Boolean,
    filtersOpen: Boolean, onOpenFilters: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit,
    gridActions: @Composable () -> Unit) {
    val trigger = remember { FocusRequester() }
    var triggerFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val controlsActive = active && !filtersOpen
    val colors = MaterialTheme.colorScheme
    val ranking = tab == AnimeDiscoveryTab.RANKING
    val summary = animeDiscoveryConditions(query, includeTime = true, caption = if (ranking)
        animeRankingOrderLabel(query.provider, query.order) else animeDiscoveryCaption(query))
    fun openFilters() {
        returnFocus = triggerFocused
        onOpenFilters()
    }
    LaunchedEffect(active, filtersOpen) {
        if (!active) {
            returnFocus = false
        } else if (!filtersOpen && returnFocus) {
            returnFocus = false
            trigger.requestFocus()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val pageTitle = when (tab) {
                    AnimeDiscoveryTab.RANKING -> "番剧榜单"
                    else -> ""
                }
                if (pageTitle.isNotEmpty()) Text(pageTitle, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                AnimeQuietLabel(summary)
            }
            gridActions()
            MotionTextButton(onClick = ::openFilters, enabled = controlsActive, modifier = Modifier.heightIn(min = 48.dp)
                .focusRequester(trigger).onFocusChanged { triggerFocused = it.isFocused },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.onSurface, containerColor = colors.surfaceContainerHigh),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("筛选")
            }
        }
        if (ranking) {
            AnimeFilterChoices(animeDiscoveryOrders(query.provider), query.order,
                { animeRankingOrderLabel(query.provider, it) }, enabled = controlsActive) { nextOrder ->
                if (nextOrder != query.order) onConfigure(query.copy(order = nextOrder))
            }
        }
    }
}

private fun animeRankingOrderLabel(provider: AnimeDiscoveryProvider, order: AnimeDiscoveryOrder): String = when {
    order == AnimeDiscoveryOrder.RANK -> "站内排名"
    order == AnimeDiscoveryOrder.TRENDING -> "近期趋势"
    provider == AnimeDiscoveryProvider.BANGUMI && order == AnimeDiscoveryOrder.POPULARITY -> "热度"
    else -> animeDiscoveryOrderLabel(order)
}

private fun animeDiscoveryFilterOrderLabel(order: AnimeDiscoveryOrder): String = when (order) {
    AnimeDiscoveryOrder.RANK -> "Bangumi 评分榜"
    AnimeDiscoveryOrder.TRENDING -> "AniList 趋势"
    else -> animeDiscoveryOrderLabel(order)
}

@Composable
private fun AnimeDiscoveryChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onSelect, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.RadioButton },
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) colors.surfaceContainerHigh else Color.Transparent,
            contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
        // Reserve the mark's space, so a quick selection does not move the other hit targets.
        Box(Modifier.size(13.dp)) {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.fillMaxSize())
        }
        Text(label, Modifier.padding(start = 4.dp), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

internal fun animeDiscoveryTagInput(provider: AnimeDiscoveryProvider, draft: String): String? {
    val term = draft.trim().takeIf { it.isNotEmpty() && it.length <= 80 && it.none { char -> char.isISOControl() } } ?: return null
    return when (provider) {
        AnimeDiscoveryProvider.BANGUMI -> term
        AnimeDiscoveryProvider.DOUBAN -> animeDoubanGenreLabels.firstOrNull { it == term }
        AnimeDiscoveryProvider.ANILIST -> animeDiscoveryGenreLabels.entries.firstOrNull {
            it.key.equals(term, true) || it.value.equals(term, true)
        }?.key
    }
}

internal fun animeDiscoveryTagLabel(provider: AnimeDiscoveryProvider, tag: String): String =
    if (provider == AnimeDiscoveryProvider.ANILIST) animeDiscoveryGenreLabels[tag] ?: tag.removePrefix("tag:") else tag

private fun animeDiscoveryFormatLabel(format: AnimeDiscoveryFormat): String = when (format) {
    AnimeDiscoveryFormat.SERIES -> "剧集"
    AnimeDiscoveryFormat.MOVIE -> "电影"
    AnimeDiscoveryFormat.SPECIAL -> "特别篇"
    AnimeDiscoveryFormat.SHORT -> "短片"
}

/** Choices expand into real rows instead of hiding labels beyond a horizontal viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AnimeFilterChoices(options: List<T>, selected: T?, label: (T) -> String,
    enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            AnimeDiscoveryChoice(label(option), selected == option, enabled = enabled) { onSelect(option) }
        }
    }
}

@Composable
private fun AnimeFilterGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun AnimeFilterDisclosure(label: String, expanded: Boolean, onClick: () -> Unit) {
    MotionTextButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(3.dp))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
    }
}

@Composable
private fun AnimeDiscoveryFilterPanel(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery,
    controller: AnimeController, backdrop: HazeState, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onDismiss: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    val ranking = tab == AnimeDiscoveryTab.RANKING
    var provider by remember { mutableStateOf(query.provider) }
    var sourceOptionsVisible by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf(query.year?.toString().orEmpty()) }
    var season by remember { mutableStateOf(query.season) }
    var tags by remember { mutableStateOf(query.selectedTags) }
    var tagQuery by remember(provider) { mutableStateOf("") }
    var order by remember { mutableStateOf(query.order) }
    var region by remember { mutableStateOf(query.region) }
    var format by remember { mutableStateOf(query.format) }
    var error by remember { mutableStateOf<String?>(null) }
    val draftOptions = remember { mutableMapOf<AnimeDiscoveryProvider, AnimeDiscoveryQuery>() }
    val colors = MaterialTheme.colorScheme
    val orders = animeDiscoveryOrders(provider)
    val defaultOrder = if (ranking) defaultAnimeRankingQuery(provider).order else AnimeDiscoveryOrder.POPULARITY
    val currentYear = localToday().year
    val quickYears = listOf("", currentYear.toString(), (currentYear - 1).toString())
    var otherYearVisible by remember { mutableStateOf(false) }

    fun selectProvider(next: AnimeDiscoveryProvider) {
        if (next == provider) return
        draftOptions[provider] = query.withTags(tags).copy(provider = provider, year = year.toIntOrNull(), season = season,
            order = order, region = region, format = format, combined = false)
        val previous = draftOptions[next] ?: rememberedOptions(next)
        val nextOrders = animeDiscoveryOrders(next)
        order = previous?.order?.takeIf { it in nextOrders } ?: nextOrders.first()
        year = previous?.year?.toString().orEmpty()
        otherYearVisible = false
        season = previous?.season?.takeUnless { next == AnimeDiscoveryProvider.DOUBAN }
        tags = previous?.selectedTags.orEmpty()
        region = previous?.region?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }.orEmpty()
        format = previous?.format?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }
        provider = next
        error = null
    }

    val scope = listOfNotNull(animeDiscoveryProviderLabel(provider), year.takeIf(String::isNotBlank)?.let { "${it} 年" },
        season?.takeUnless { provider == AnimeDiscoveryProvider.DOUBAN }?.let(::animeSeasonLabel)).joinToString(" · ")
    val clearAvailable = year.isNotEmpty() || season != null || tags.isNotEmpty() || region.isNotEmpty() || format != null ||
        (tab != AnimeDiscoveryTab.HOME && order != defaultOrder)

    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop, containerColor = colors.surface) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding().padding(horizontal = 18.dp)
            .semantics { paneTitle = "番剧筛选" }) {
            AnimeSectionTitle(if (tab == AnimeDiscoveryTab.HOME) "筛选番剧" else "筛选与排序",
                scope, action = {
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(48.dp), tooltip = "关闭筛选") {
                        Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(20.dp))
                    }
                })
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab != AnimeDiscoveryTab.HOME) AnimeFilterGroup("排序") {
                    AnimeFilterChoices(orders, order, {
                        if (ranking) animeRankingOrderLabel(provider, it) else animeDiscoveryFilterOrderLabel(it)
                    }) { order = it }
                }
                if (provider == AnimeDiscoveryProvider.DOUBAN) {
                    AnimeFilterGroup("地区") {
                        AnimeFilterChoices(listOf("", "日本", "大陆"), region, {
                            when (it) { "" -> "全部"; "大陆" -> "中国大陆"; else -> it }
                        }) { region = it }
                    }
                    AnimeFilterGroup("类型") {
                        AnimeFilterChoices(listOf(AnimeDiscoveryFormat.SERIES, AnimeDiscoveryFormat.MOVIE),
                            format ?: AnimeDiscoveryFormat.SERIES, ::animeDiscoveryFormatLabel) { format = it }
                    }
                }
                AnimeFilterGroup(if (provider == AnimeDiscoveryProvider.DOUBAN) "上映年份" else "首播年份") {
                    AnimeFilterChoices(quickYears, year, { it.ifEmpty { "全部" } }) { chosen ->
                        year = chosen
                        if (chosen.isEmpty()) season = null
                        otherYearVisible = false
                        error = null
                    }
                    val otherYearLabel = year.takeIf { it !in quickYears }?.let { "其他年份 · $it" } ?: "其他年份"
                    AnimeFilterDisclosure(otherYearLabel, otherYearVisible) { otherYearVisible = !otherYearVisible }
                    if (otherYearVisible) AnimeTextField(year, { value ->
                        if (value.length <= 4 && value.all { it in '0'..'9' }) {
                            year = value
                            if (value.isEmpty()) season = null
                            error = null
                        }
                    }, "输入年份", emptyHint = "例如 2019")
                }
                if (provider != AnimeDiscoveryProvider.DOUBAN) AnimeFilterGroup("季度") {
                    val seasons: List<AnimeSeason?> = listOf(null) + AnimeSeason.entries
                    AnimeFilterChoices(seasons, season, { it?.let(::animeSeasonLabel) ?: "全年" }) { chosen ->
                        season = chosen
                        if (chosen != null && year.isBlank()) {
                            year = quickYears[1]
                            otherYearVisible = false
                        }
                        error = null
                    }
                }
                AnimeFilterGroup(if (tags.size > 1) "标签 · 同时匹配" else "标签") {
                    AnimeTagPicker(controller, provider, tags, tagQuery, active = true,
                        maxChoicesHeight = 240.dp, onQuery = { tagQuery = it.take(80) },
                        onToggle = { tag -> tags = if (tag in tags) tags - tag else tags + tag })
                }
                if (!seasonal && !ranking && tab != AnimeDiscoveryTab.HOME) AnimeFilterGroup("资料目录") {
                    AnimeFilterDisclosure(animeDiscoveryProviderLabel(provider), sourceOptionsVisible) {
                        sourceOptionsVisible = !sourceOptionsVisible
                    }
                    if (sourceOptionsVisible) AnimeFilterChoices(AnimeDiscoveryProvider.entries,
                        provider, ::animeDiscoveryProviderLabel, onSelect = ::selectProvider)
                }
            }
            if (error != null) Text(error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.error, style = MaterialTheme.typography.bodySmall)

            val clearFilters: @Composable () -> Unit = {
                MotionTextButton(enabled = clearAvailable, onClick = {
                    year = ""; season = null; tags = emptyList(); tagQuery = ""; region = ""; format = null
                    if (tab != AnimeDiscoveryTab.HOME) order = defaultOrder
                    otherYearVisible = false
                    error = null
                }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text("清除条件")
                }
            }
            val applyFilters: @Composable () -> Unit = {
                MotionButton(onClick = {
                    val parsed = year.takeIf(String::isNotEmpty)?.toIntOrNull()
                    if (year.isNotBlank() && (parsed == null || parsed !in 1..9998)) {
                        otherYearVisible = true
                        error = "请输入 1 到 9998 之间的年份"
                    } else {
                        onConfigure(query.withTags(tags).copy(provider = provider, year = parsed,
                            season = season.takeUnless { provider == AnimeDiscoveryProvider.DOUBAN },
                            order = order,
                            region = region.takeIf { provider == AnimeDiscoveryProvider.DOUBAN }.orEmpty(),
                            format = format.takeIf { provider == AnimeDiscoveryProvider.DOUBAN }, combined = false))
                        dismiss()
                    }
                }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onSurface, contentColor = colors.surface)) {
                    Text("应用筛选")
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                if (maxWidth < 254.dp * fontScale) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        clearFilters()
                        applyFilters()
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    clearFilters()
                    applyFilters()
                }
            }
        }
    }
}
