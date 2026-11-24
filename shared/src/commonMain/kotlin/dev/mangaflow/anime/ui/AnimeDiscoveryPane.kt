package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

private const val FEATURED_PREVIEW = 6

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit, onWatchHistory: () -> Unit,
    contentBottomPadding: Dp = 24.dp, contentTopPadding: Dp = 0.dp) {
    val ratings = LocalAnimeRatings.current
    val browseActive = active
    fun openScores(item: AnimeDiscoveryItem) {
        if (browseActive) ratings.open(item.ratingRequest())
    }
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it }, order = { it.ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding, contentTopPadding,
                onOpen = onOpenItem, onScores = ::openScores, onManage = onManage,
                onResume = onResume, onWatchHistory = onWatchHistory,
                active = browseActive && state.discoveryTab == tab)
            AnimeDiscoveryTab.SOURCES -> Unit
            else -> {
                val key = animeDiscoveryFeedKey(tab)
                val feed = state.discoveryFeeds[key]
                val tabActive = browseActive && state.discoveryTab == tab
                var overlayPresent by remember(tab) { mutableStateOf(false) }
                val contentActive = tabActive && !overlayPresent
                Column(Modifier.fillMaxSize().padding(top = contentTopPadding).motionInputGate(tabActive)) {
                    if (feed != null) {
                        AnimeDiscoveryControls(tab, feed.query, backdrop, tabActive,
                            onOverlayPresence = { overlayPresent = it },
                            rememberedOptions = { controller.rememberedDiscoveryOptions(tab, it) },
                            onConfigure = { controller.configureDiscovery(tab, it) })
                        if (tab == AnimeDiscoveryTab.GENRES) {
                            AnimeGenreShortcuts(feed.query, feed.query.tag, contentActive,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onSelect = { controller.configureDiscovery(tab, feed.query.copy(tag = it)) })
                        }
                    }
                    AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
                        modifier = Modifier.weight(1f).mangaHazeSource(backdrop).motionInputGate(contentActive), active = contentActive,
                        onOpen = onOpenItem, onScores = ::openScores,
                        onManage = onManage, onRefresh = controller::refreshDiscovery,
                        onRetry = { controller.retryDiscovery(key) },
                        onMore = { controller.loadMoreDiscovery(key) },
                        onClearTag = { feed?.let { controller.configureDiscovery(tab, it.query.copy(tag = "")) } })
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, contentTopPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit, onManage: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, onWatchHistory: () -> Unit, active: Boolean) {
    val key = animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME)
    val feed = state.discoveryFeeds[key]
    val query = feed?.query ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
    var showFilters by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) showFilters = false }
    val contentActive = active && !showFilters
    val overview = query.order == AnimeDiscoveryOrder.POPULARITY && query.region.isBlank() &&
        query.tag.isBlank() && query.year == null && query.format != AnimeDiscoveryFormat.MOVIE
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
    val recent = remember(state.library, overview) {
        if (!overview) null else state.library.asSequence()
            .filter { it.resume?.completed == false && it.status != AnimeWatchStatus.DROPPED }
            .maxByOrNull { it.resume?.updatedAt ?: 0L }
    }
    AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
        modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(contentActive), active = contentActive,
        onOpen = onOpen, onScores = onScores, onManage = onManage,
        onRefresh = controller::refreshDiscovery, onRetry = { controller.retryDiscovery(key) },
        onMore = { controller.loadMoreDiscovery(key) },
        onClearTag = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, query.copy(tag = "")) },
        featured = featured, contentTopPadding = contentTopPadding,
        watchShelf = recent?.let { entry -> {
            AnimeDiscoveryResumeRow(entry, contentActive, onResume, onWatchHistory)
        } },
        catalogueHeader = {
            AnimeHomeCatalogueHeader(query, backdrop, contentActive, refreshing = feed?.loading == true && feed.items.isNotEmpty(),
                onOrder = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, query.copy(order = it)) },
                onFilter = { showFilters = true })
        })
    if (active && showFilters) AnimeDiscoveryFilterPanel(AnimeDiscoveryTab.HOME, query, backdrop,
        rememberedOptions = { controller.rememberedDiscoveryOptions(AnimeDiscoveryTab.HOME, it) },
        onDismiss = { showFilters = false },
        onConfigure = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, it) })
}

@Composable
private fun AnimeDiscoveryResumeRow(entry: AnimeLibraryEntry, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit, onHistory: () -> Unit) {
    val progress = entry.resume ?: return
    val colors = MaterialTheme.colorScheme
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
            .motionPress(interaction, enabled = active, pressedScale = .99f)
            .clickable(interactionSource = interaction, indication = null, enabled = active,
                role = Role.Button, onClickLabel = "继续观看", onClick = { onResume(entry) })
            .heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimePoster(entry.subject, Modifier.width(33.dp).height(46.dp).clearAndSetSemantics { })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.subject.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("续看 · " + animeWatchLocation(entry, progress), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(21.dp), tint = colors.onSurface)
        }
        MotionIconButton(onClick = onHistory, enabled = active, modifier = Modifier.size(44.dp), tooltip = "观看历史") {
            Icon(Icons.Outlined.History, "观看历史", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeHomeCatalogueHeader(query: AnimeDiscoveryQuery, backdrop: HazeState, active: Boolean,
    refreshing: Boolean, onOrder: (AnimeDiscoveryOrder) -> Unit, onFilter: () -> Unit) {
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.clip(RoundedCornerShape(10.dp))
                .clickable(enabled = active, role = Role.Button, onClickLabel = "更改番剧排序") { showOrders = true }
                .semantics(mergeDescendants = true) { heading() }
                .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title(query.order), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
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
        if (restrictions.isNotBlank()) Text(restrictions, Modifier.widthIn(max = 104.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val filterLabel = if (restrictions.isBlank()) "筛选番剧" else "筛选 · $restrictions"
        MotionIconButton(onClick = onFilter, enabled = active, modifier = Modifier.size(44.dp), tooltip = filterLabel) {
            Icon(if (restrictions.isBlank()) Icons.Outlined.Tune else Icons.Outlined.FilterAlt,
                filterLabel, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeGenreShortcuts(query: AnimeDiscoveryQuery, selected: String?, active: Boolean,
    modifier: Modifier = Modifier, home: Boolean = false, onSelect: (String) -> Unit) {
    val options = when {
        query.provider == AnimeDiscoveryProvider.DOUBAN -> doubanGenreShortcuts
        query.provider == AnimeDiscoveryProvider.BANGUMI -> bangumiGenreShortcuts
        home -> anilistHomeGenres
        else -> animeDiscoveryGenreLabels
    }
    val selectedTag = if (query.provider == AnimeDiscoveryProvider.BANGUMI) {
        selected?.let { animeDiscoveryGenreLabels[it] ?: it }
    } else selected
    val allLabel = if (home) "分类" else "全部"
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState(), enabled = active),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        (listOf("" to allLabel) + options.entries.map { it.key to it.value }).forEach { (tag, label) ->
            val chosen = selectedTag == tag
            val palette = MaterialTheme.colorScheme
            MotionTextButton(onClick = { onSelect(tag) }, enabled = active,
                modifier = Modifier.heightIn(min = 44.dp).semantics { this.selected = chosen },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (chosen) palette.surfaceContainerHigh else palette.surfaceContainerLow,
                    contentColor = if (chosen) palette.onSurface else palette.onSurfaceVariant),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (home && tag.isEmpty()) {
                    Icon(Icons.Outlined.Category, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(label, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun AnimeDiscoverySectionHeading(title: String, caption: String, refreshing: Boolean = false,
    onMore: (() -> Unit)? = null, moreLabel: String = "查看全部$title", expanded: Boolean? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
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
private fun AnimeDiscoveryFeedGrid(key: String, feed: AnimeDiscoveryFeed?, preference: Int?,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, modifier: Modifier, active: Boolean,
    onOpen: (AnimeDiscoveryItem) -> Unit, onScores: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, onRefresh: () -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit, onClearTag: () -> Unit,
    featured: List<AnimeFeaturedEntry> = emptyList(),
    watchShelf: (@Composable () -> Unit)? = null, catalogueHeader: (@Composable () -> Unit)? = null,
    contentTopPadding: Dp = 0.dp) {
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
            horizontalArrangement = Arrangement.spacedBy(animeDiscoveryGap(columns)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = active, visible = active && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpenScores = { entry -> if (active) onScores(entry.discoveryItem) },
                    onOpen = { entry -> if (active) onOpen(entry.discoveryItem) })
            }
            if (watchShelf != null) item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) { watchShelf() }
            if (catalogueHeader != null) item(key = "discovery-heading", span = { GridItemSpan(maxLineSpan) }) {
                catalogueHeader()
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
                        AnimeDiscoveryInlineStatus(if (feed.hasMore) "本页暂无匹配的番剧" else "没有符合当前条件的番剧",
                            onClearTag.takeIf { active && feed.query.tag.isNotBlank() },
                            retryLabel = "清除题材", showIssue = false)
                    }
                }
            } else if (fullWeek) {
                calendarDays.forEach { (day, shows) ->
                    item(key = "weekday:$day", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoverySectionHeading(
                            if (day in 1..7) animeWeekdays[day - 1] else "放送日期待定",
                            if (day == localToday().dayOfWeek.ordinal + 1) "今天 · ${shows.size} 部" else "${shows.size} 部")
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
                    AnimeDiscoverySectionHeading(animeWeekdays[weekday - 1] + "放送", "${works.size} 部", refreshing = feed.loading)
                }
                items(works, key = { it.key }, contentType = { "anime-work" }) { item ->
                    AnimeDiscoveryCard(item, feed.query, list = columns == 1, showPoster = hasCalendarArtwork, onScores = onScores.takeIf { active },
                        onManage = item.subject?.takeIf { active }?.let { subject -> { onManage(subject) } },
                        onOpen = { if (active) onOpen(item) })
                }
            }
            if (feed?.error != null && !hasScopedErrors) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                val listName = when (key) {
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME) -> "发现"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.SEASON) -> animeDiscoverySeasonTitle(feed.query) + "番单"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.CALENDAR) -> "番表"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.RANKING) -> "番剧榜单"
                    else -> "分类番剧"
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
            if (feed != null && hasScopedErrors) item(
                key = "partial", span = { GridItemSpan(maxLineSpan) }) {
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
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        if (LocalMotionEnabled.current) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimeQuietLabel(label)
    }
}

private val animeWeekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun animeHomeScope(query: AnimeDiscoveryQuery): String = listOfNotNull(
    query.region.takeIf(String::isNotBlank)?.let { if (it == "大陆") "国产" else it },
    "电影".takeIf { query.format == AnimeDiscoveryFormat.MOVIE },
    query.year?.toString(),
    query.tag.takeIf(String::isNotBlank),
).joinToString(" · ")

@Composable
private fun AnimeDiscoveryPartialStatus(feed: AnimeDiscoveryFeed, onRetry: (() -> Unit)?) {
    val label = buildList {
        if (feed.error != null && feed.items.isNotEmpty()) add("显示上次结果")
        feed.sourceErrors.forEach { (source, error) -> add("${animeDiscoveryProviderLabel(source)} · $error") }
    }
    AnimeDiscoveryInlineStatus(label.joinToString("\n"), retry = onRetry, retryLabel = "重试资料", showIssue = true)
}

private fun animeDiscoverySeasonTitle(query: AnimeDiscoveryQuery): String =
    listOfNotNull(query.year?.toString(), query.season?.let(::animeSeasonLabel))
        .joinToString(" ").ifBlank { "全部季度" }

private fun animeDiscoveryConditions(query: AnimeDiscoveryQuery, includeTime: Boolean = false): String =
    listOfNotNull(animeDiscoveryCaption(query),
        animeDiscoverySeasonTitle(query).takeIf { includeTime && query.year != null },
        query.tag.takeIf(String::isNotBlank)?.let { animeDiscoveryGenreLabels[it] ?: it },
        query.region.takeIf(String::isNotBlank), query.format?.let(::animeDiscoveryFormatLabel)).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryControls(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery, backdrop: HazeState, active: Boolean,
    onOverlayPresence: (Boolean) -> Unit, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    if (tab == AnimeDiscoveryTab.CALENDAR) {
        val today = localToday().dayOfWeek.ordinal + 1
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            cornerRadius = 24.dp, role = GlassSurfaceRole.NAVIGATION, elevation = 0.dp) {
            AnimeChoiceStrip(listOf("全周") + animeWeekdays.mapIndexed { index, day ->
                if (index + 1 == today) "今天" else day
            }, selected = query.weekday ?: 0, active = active,
                onSelect = { onConfigure(query.copy(weekday = it.takeIf { day -> day > 0 })) })
        }
        return
    }

    var visible by remember { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    var triggerFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val controlsActive = active && !visible
    val colors = MaterialTheme.colorScheme
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    val summary = animeDiscoveryConditions(query, includeTime = !seasonal)
    fun openFilters() {
        returnFocus = triggerFocused
        visible = true
    }
    SideEffect { onOverlayPresence(visible) }
    LaunchedEffect(active, visible) {
        if (!active) {
            visible = false
            returnFocus = false
        } else if (!visible && returnFocus) {
            returnFocus = false
            trigger.requestFocus()
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (seasonal) Text(animeDiscoverySeasonTitle(query), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                AnimeQuietLabel(summary)
            }
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
        if (tab == AnimeDiscoveryTab.RANKING) {
            val rankingOrders = animeDiscoveryOrders(query.provider.takeUnless { query.combined })
            AnimeChoiceStrip(rankingOrders.map(::animeDiscoveryOrderLabel), rankingOrders.indexOf(query.order),
                active = controlsActive, onSelect = { index ->
                    val nextOrder = rankingOrders[index]
                    val aniList = (!query.combined && query.provider == AnimeDiscoveryProvider.ANILIST) ||
                        (query.combined && nextOrder == AnimeDiscoveryOrder.TRENDING)
                    val nextTag = if (aniList) query.tag.takeIf(animeDiscoveryGenreLabels::containsKey)
                        ?: animeDiscoveryGenreLabels.entries.firstOrNull { it.value == query.tag }?.key.orEmpty()
                    else animeDiscoveryGenreLabels[query.tag] ?: query.tag
                    onConfigure(query.copy(order = nextOrder, tag = nextTag))
                }, modifier = Modifier.padding(top = 5.dp))
        }
        if (seasonal) {
            val today = localToday()
            val currentQuarter = (today.monthNumber - 1) / 3
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("上季", "本季", "下季").forEachIndexed { index, label ->
                    val absoluteQuarter = today.year * AnimeSeason.entries.size + currentQuarter + index - 1
                    val year = absoluteQuarter / AnimeSeason.entries.size
                    val season = AnimeSeason.entries[absoluteQuarter % AnimeSeason.entries.size]
                    val selected = query.year == year && query.season == season
                    AnimeDiscoveryChoice(label, selected, controlsActive) {
                        if (!selected) onConfigure(query.copy(year = year, season = season))
                    }
                }
            }
        }
    }
    if (visible && active) AnimeDiscoveryFilterPanel(tab, query, backdrop, rememberedOptions,
        onDismiss = { visible = false }, onConfigure = onConfigure)
}

private fun animeDiscoveryOrders(provider: AnimeDiscoveryProvider?): List<AnimeDiscoveryOrder> = when (provider) {
    AnimeDiscoveryProvider.DOUBAN -> listOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE, AnimeDiscoveryOrder.NEWEST)
    AnimeDiscoveryProvider.BANGUMI -> listOf(AnimeDiscoveryOrder.RANK, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
    AnimeDiscoveryProvider.ANILIST -> listOf(AnimeDiscoveryOrder.TRENDING, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
    null -> listOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE, AnimeDiscoveryOrder.RANK, AnimeDiscoveryOrder.TRENDING)
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

private val doubanGenreLabels = listOf("热血", "校园", "恋爱", "搞笑", "治愈", "科幻", "奇幻",
    "冒险", "悬疑", "推理", "日常", "运动", "机战", "催泪")
private val doubanGenreShortcuts = linkedMapOf("热血" to "热血", "奇幻" to "奇幻", "恋爱" to "恋爱",
    "科幻" to "科幻", "日常" to "日常")
private val bangumiGenreShortcuts = linkedMapOf("剧场版" to "剧场版", "国产" to "国产",
    "热血" to "热血", "奇幻" to "奇幻", "恋爱" to "恋爱", "科幻" to "科幻", "日常" to "日常",
    "悬疑" to "悬疑", "运动" to "运动")
private val anilistHomeGenres = animeDiscoveryGenreLabels.filterKeys {
    it in setOf("Action", "Fantasy", "Romance", "Sci-Fi", "Slice of Life")
}

private fun animeDiscoveryFormatLabel(format: AnimeDiscoveryFormat): String = when (format) {
    AnimeDiscoveryFormat.SERIES -> "剧集"
    AnimeDiscoveryFormat.MOVIE -> "电影"
    AnimeDiscoveryFormat.SPECIAL -> "特别篇"
    AnimeDiscoveryFormat.SHORT -> "短片"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryFilterPanel(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery,
    backdrop: HazeState, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onDismiss: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    var provider by remember { mutableStateOf(query.provider) }
    var sourceOptionsVisible by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf(query.year?.toString().orEmpty()) }
    var season by remember { mutableStateOf(query.season) }
    var tag by remember { mutableStateOf(query.tag) }
    var order by remember { mutableStateOf(query.order) }
    var region by remember { mutableStateOf(query.region) }
    var format by remember { mutableStateOf(query.format) }
    var error by remember { mutableStateOf<String?>(null) }
    val draftOptions = remember { mutableMapOf<AnimeDiscoveryProvider, AnimeDiscoveryQuery>() }
    val colors = MaterialTheme.colorScheme
    val orders = animeDiscoveryOrders(provider)
    val currentYear = localToday().year
    val quickYears = listOf("", currentYear.toString(), (currentYear - 1).toString())
    var customYear by remember { mutableStateOf(year !in quickYears) }
    var allGenres by remember(provider) { mutableStateOf(false) }

    fun selectProvider(next: AnimeDiscoveryProvider) {
        if (next == provider) return
        draftOptions[provider] = query.copy(provider = provider, year = year.toIntOrNull(), season = season,
            tag = tag, order = order, region = region, format = format, combined = false)
        val previous = draftOptions[next] ?: rememberedOptions(next)
        val nextOrders = animeDiscoveryOrders(next)
        order = previous?.order?.takeIf { it in nextOrders } ?: nextOrders.first()
        year = previous?.year?.toString().orEmpty()
        customYear = year !in quickYears
        season = previous?.season?.takeUnless { next == AnimeDiscoveryProvider.DOUBAN }
        tag = previous?.tag.orEmpty()
        region = previous?.region?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }.orEmpty()
        format = previous?.format?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }
        provider = next
        error = null
    }

    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop, containerColor = colors.surface) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding().padding(horizontal = 18.dp)
            .semantics { paneTitle = "番剧筛选" }) {
            AnimeSectionTitle(if (tab == AnimeDiscoveryTab.HOME) "筛选番剧" else "筛选与排序",
                animeDiscoveryProviderLabel(provider), action = {
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(48.dp), tooltip = "关闭筛选") {
                        Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(20.dp))
                    }
                })
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab != AnimeDiscoveryTab.HOME) Column {
                    AnimeQuietLabel("排序")
                    AnimeChoiceStrip(orders.map(::animeDiscoveryFilterOrderLabel), orders.indexOf(order),
                        onSelect = { order = orders[it] })
                }
                if (provider == AnimeDiscoveryProvider.DOUBAN) {
                    val regions = listOf("", "日本", "大陆")
                    val formats = listOf(AnimeDiscoveryFormat.SERIES, AnimeDiscoveryFormat.MOVIE)
                    Column {
                        AnimeQuietLabel("地区")
                        AnimeChoiceStrip(listOf("全部", "日本", "中国大陆"), regions.indexOf(region),
                            onSelect = { region = regions[it] })
                    }
                    Column {
                        AnimeQuietLabel("类型")
                        AnimeChoiceStrip(formats.map(::animeDiscoveryFormatLabel), formats.indexOf(format ?: formats.first()),
                            onSelect = { format = formats[it] })
                    }
                }
                Column {
                    AnimeQuietLabel(if (provider == AnimeDiscoveryProvider.DOUBAN) "上映年份" else "首播年份")
                    AnimeChoiceStrip(listOf("全部", quickYears[1], quickYears[2], "其他"),
                        if (customYear) quickYears.size else quickYears.indexOf(year), onSelect = { index ->
                            customYear = index == quickYears.size
                            if (!customYear) {
                                year = quickYears[index]
                                if (year.isEmpty()) season = null
                            }
                            error = null
                        })
                    if (customYear) AnimeTextField(year, { value ->
                        if (value.length <= 4 && value.all { it in '0'..'9' }) {
                            year = value
                            if (value.isEmpty()) season = null
                            error = null
                        }
                    }, "输入年份", emptyHint = "例如 2019")
                    if (provider != AnimeDiscoveryProvider.DOUBAN) {
                        val seasons = AnimeSeason.entries
                        AnimeChoiceStrip(listOf("全年") + seasons.map(::animeSeasonLabel),
                            season?.ordinal?.plus(1) ?: 0, onSelect = { index ->
                                season = seasons.getOrNull(index - 1)
                                if (season != null && year.isBlank()) {
                                    year = quickYears[1]
                                    customYear = false
                                }
                                error = null
                            })
                    }
                }
                if (provider == AnimeDiscoveryProvider.BANGUMI) {
                    AnimeTextField(tag, { tag = it }, "题材", emptyHint = "全部题材")
                } else {
                    val genres = if (provider == AnimeDiscoveryProvider.DOUBAN)
                        doubanGenreLabels.map { it to it } else animeDiscoveryGenreLabels.toList()
                    val displayed = if (allGenres) genres else genres.filterIndexed { index, option ->
                        index < 5 || option.first == tag
                    }
                    Column {
                        AnimeQuietLabel("题材")
                        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AnimeDiscoveryChoice("全部", tag.isBlank(), true) { tag = "" }
                            displayed.forEach { (value, label) ->
                                AnimeDiscoveryChoice(label, tag == value, true) { tag = value }
                            }
                            MotionTextButton(onClick = { allGenres = !allGenres }) {
                                Text(if (allGenres) "收起" else "更多")
                                Icon(if (allGenres) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, Modifier.size(14.dp))
                            }
                        }
                    }
                }
                if (!seasonal && tab != AnimeDiscoveryTab.HOME) Column {
                    CompactMenuAction(if (sourceOptionsVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        "资料目录 · ${animeDiscoveryProviderLabel(provider)}", onClick = { sourceOptionsVisible = !sourceOptionsVisible })
                    if (sourceOptionsVisible) AnimeChoiceStrip(AnimeDiscoveryProvider.entries.map(::animeDiscoveryProviderLabel),
                        provider.ordinal, onSelect = { selectProvider(AnimeDiscoveryProvider.entries[it]) })
                }
            }
            if (error != null) Text(error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MotionTextButton(onClick = {
                    year = ""; season = null; tag = ""; region = ""; format = null
                    if (tab != AnimeDiscoveryTab.HOME) order = AnimeDiscoveryOrder.POPULARITY
                    customYear = false
                    error = null
                }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text("清除条件")
                }
                Spacer(Modifier.weight(1f))
                MotionButton(onClick = {
                    val parsed = year.takeIf(String::isNotEmpty)?.toIntOrNull()
                    if (year.isNotBlank() && (parsed == null || parsed !in 1..9998)) {
                        error = "请输入 1 到 9998 之间的年份"
                    } else {
                        onConfigure(query.copy(provider = provider, year = parsed,
                            season = season.takeUnless { provider == AnimeDiscoveryProvider.DOUBAN },
                            tag = tag.trim(), order = order,
                            region = region.takeIf { provider == AnimeDiscoveryProvider.DOUBAN }.orEmpty(),
                            format = format.takeIf { provider == AnimeDiscoveryProvider.DOUBAN }, combined = false))
                        dismiss()
                    }
                }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)) {
                    Text("应用筛选")
                }
            }
        }
    }
}
