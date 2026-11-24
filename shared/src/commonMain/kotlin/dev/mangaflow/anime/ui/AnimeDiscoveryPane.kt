package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

private const val FEATURED_PREVIEW = 6

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit,
    contentBottomPadding: Dp, contentTopPadding: Dp) {
    val ratings = LocalAnimeRatings.current
    val browseActive = active
    fun openScores(item: AnimeDiscoveryItem) {
        if (browseActive) ratings.open(item.ratingRequest())
    }
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it }, order = { it.ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding, contentTopPadding,
                onOpen = onOpenItem, onScores = ::openScores, onManage = onManage,
                onResume = onResume,
                active = browseActive && state.discoveryTab == tab)
            AnimeDiscoveryTab.SOURCES -> Unit
            else -> {
                val key = animeDiscoveryFeedKey(tab)
                val feed = state.discoveryFeeds[key]
                val tabActive = browseActive && state.discoveryTab == tab
                var showFilters by remember(tab) { mutableStateOf(false) }
                LaunchedEffect(tabActive) { if (!tabActive) showFilters = false }
                val contentActive = tabActive && !showFilters
                // Like the home catalogue, the controls are part of the scroll
                // scene. Only the floating workspace header stays above it.
                val catalogueHeader: (@Composable () -> Unit)? = feed?.let { current ->
                    {
                        Column(Modifier.fillMaxWidth()) {
                            AnimeDiscoveryControls(tab, current.query, tabActive, showFilters,
                                onOpenFilters = { showFilters = true },
                                onConfigure = { controller.configureDiscovery(tab, it) })
                            if (tab == AnimeDiscoveryTab.GENRES) {
                                AnimeGenreShortcuts(current.query, contentActive,
                                    onSelect = { controller.configureDiscovery(tab, current.query.copy(tag = it)) })
                            }
                        }
                    }
                }
                AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
                    modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(contentActive), active = contentActive,
                    onOpen = onOpenItem, onScores = ::openScores,
                    onManage = onManage, onRefresh = controller::refreshDiscovery,
                    onRetry = { controller.retryDiscovery(key) },
                    onMore = { controller.loadMoreDiscovery(key) },
                    onClearTag = { feed?.let { controller.configureDiscovery(tab, it.query.copy(tag = "")) } },
                    onShowFullWeek = { feed?.let { controller.configureDiscovery(tab, it.query.copy(weekday = null)) } },
                    catalogueHeader = catalogueHeader, contentTopPadding = contentTopPadding)
                // A restored query can scroll off the lazy header as soon as Apply
                // runs. The editor must stay mounted long enough to actually close.
                if (tabActive && showFilters && feed != null) AnimeDiscoveryFilterPanel(tab, feed.query, backdrop,
                    rememberedOptions = { controller.rememberedDiscoveryOptions(tab, it) },
                    onDismiss = { showFilters = false },
                    onConfigure = { controller.configureDiscovery(tab, it) })
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, contentTopPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit, onManage: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, active: Boolean) {
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
            AnimeDiscoveryResumeRow(entry, contentActive, onResume)
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
    onResume: (AnimeLibraryEntry) -> Unit) {
    val progress = entry.resume ?: return
    val colors = MaterialTheme.colorScheme
    val shape = remember { RoundedCornerShape(16.dp) }
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth()
        .motionPress(interaction, enabled = active, pressedScale = .99f)
        .softSurfaceShadow(shape, light = colors.surface.luminance() > .5f, strength = .68f, radius = 14.dp)
        .clip(shape).background(colors.surfaceContainerHigh)
        .clickable(interactionSource = interaction, indication = null, enabled = active,
            role = Role.Button, onClickLabel = "继续观看", onClick = { onResume(entry) })
        .heightIn(min = 66.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimePoster(entry.subject, Modifier.width(35.dp).height(50.dp).clearAndSetSemantics { })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.subject.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("续看 · " + animeWatchLocation(entry, progress), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress.belongsToWatchHistory(entry.subject.id)) animeWatchFraction(progress)?.let { fraction ->
                AnimeWatchProgressLine(fraction, Modifier.widthIn(max = 164.dp).padding(top = 2.dp))
            }
        }
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(21.dp), tint = colors.onSurface)
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
            val filterLabel = if (restrictions.isBlank()) "筛选番剧" else "筛选 · $restrictions"
            MotionIconButton(onClick = onFilter, enabled = active, modifier = Modifier.size(44.dp), tooltip = filterLabel) {
                Icon(if (restrictions.isBlank()) Icons.Outlined.Tune else Icons.Outlined.FilterAlt,
                    filterLabel, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (restrictions.isNotBlank()) AnimeQuietLabel(restrictions, Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun AnimeGenreShortcuts(query: AnimeDiscoveryQuery, active: Boolean, onSelect: (String) -> Unit) {
    val options = when (query.provider) {
        AnimeDiscoveryProvider.DOUBAN -> doubanGenreShortcuts
        AnimeDiscoveryProvider.BANGUMI -> bangumiGenreShortcuts
        AnimeDiscoveryProvider.ANILIST -> animeDiscoveryGenreLabels
    }
    val selected = if (query.provider == AnimeDiscoveryProvider.BANGUMI) animeDiscoveryGenreLabels[query.tag] ?: query.tag
        else query.tag
    // Keep the quick row compact; the existing Filter exposes the rest and custom tags.
    val first = options.keys.take(5)
    val quick = first + listOfNotNull(selected.takeIf { it in options && it !in first })
    AnimeFilterChoices(listOf("") + quick, selected, { options[it] ?: "全部" }, enabled = active) {
        if (it != selected) onSelect(it)
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
    onRetry: () -> Unit, onMore: () -> Unit, onClearTag: () -> Unit, onShowFullWeek: (() -> Unit)? = null,
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
            horizontalArrangement = Arrangement.spacedBy(animeDiscoveryGap(columns)),
            verticalArrangement = Arrangement.spacedBy(if (columns == 1) 10.dp else 12.dp)) {
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
                            feed.query.tag.isNotBlank() -> onClearTag
                            else -> null
                        }
                        AnimeDiscoveryInlineStatus(when {
                            feed.hasMore -> "本页暂无匹配的番剧"
                            narrowCalendar -> "这一天暂无放送条目"
                            feed.query.calendar -> "本周暂无放送条目"
                            else -> "没有符合当前条件的番剧"
                        }, reset, retryLabel = if (narrowCalendar) "查看全周" else "清除题材", showIssue = false)
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

private fun animeHomeScope(query: AnimeDiscoveryQuery): String = listOfNotNull(
    query.region.takeIf(String::isNotBlank)?.let { if (it == "大陆") "国产" else it },
    "电影".takeIf { query.format == AnimeDiscoveryFormat.MOVIE },
    query.year?.toString(),
    query.tag.takeIf(String::isNotBlank),
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

private fun animeDiscoveryConditions(query: AnimeDiscoveryQuery, includeTime: Boolean = false): String =
    listOfNotNull(animeDiscoveryCaption(query),
        animeDiscoverySeasonTitle(query).takeIf { includeTime && query.year != null },
        query.tag.takeIf(String::isNotBlank)?.let { animeDiscoveryGenreLabels[it] ?: it },
        query.region.takeIf(String::isNotBlank), query.format?.let(::animeDiscoveryFormatLabel)).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryControls(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery, active: Boolean,
    filtersOpen: Boolean, onOpenFilters: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    if (tab == AnimeDiscoveryTab.CALENDAR) {
        val today = localToday().dayOfWeek.ordinal + 1
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimeDiscoverySectionHeading("本周放送", animeDiscoveryProviderLabel(query.provider))
            val selected = query.weekday ?: 0
            AnimeFilterChoices((0..animeWeekdays.size).toList(), selected, { day ->
                when (day) { 0 -> "全周"; today -> "今天"; else -> animeWeekdays[day - 1] }
            }, enabled = active) { day ->
                if (day != selected) onConfigure(query.copy(weekday = day.takeIf { it > 0 }))
            }
        }
        return
    }

    val trigger = remember { FocusRequester() }
    var triggerFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val controlsActive = active && !filtersOpen
    val colors = MaterialTheme.colorScheme
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    val summary = animeDiscoveryConditions(query, includeTime = !seasonal)
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
                    AnimeDiscoveryTab.SEASON -> animeDiscoverySeasonTitle(query)
                    AnimeDiscoveryTab.RANKING -> "番剧榜单"
                    AnimeDiscoveryTab.GENRES -> "题材分类"
                    else -> ""
                }
                if (pageTitle.isNotEmpty()) Text(pageTitle, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
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
            AnimeFilterChoices(rankingOrders, query.order, ::animeDiscoveryOrderLabel, enabled = controlsActive) { nextOrder ->
                if (nextOrder != query.order) {
                    val aniList = (!query.combined && query.provider == AnimeDiscoveryProvider.ANILIST) ||
                        (query.combined && nextOrder == AnimeDiscoveryOrder.TRENDING)
                    val nextTag = if (aniList) query.tag.takeIf(animeDiscoveryGenreLabels::containsKey)
                        ?: animeDiscoveryGenreLabels.entries.firstOrNull { it.value == query.tag }?.key.orEmpty()
                    else animeDiscoveryGenreLabels[query.tag] ?: query.tag
                    onConfigure(query.copy(order = nextOrder, tag = nextTag))
                }
            }
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
private fun animeDiscoveryFormatLabel(format: AnimeDiscoveryFormat): String = when (format) {
    AnimeDiscoveryFormat.SERIES -> "剧集"
    AnimeDiscoveryFormat.MOVIE -> "电影"
    AnimeDiscoveryFormat.SPECIAL -> "特别篇"
    AnimeDiscoveryFormat.SHORT -> "短片"
}

/** Choices expand into real rows instead of hiding labels beyond a horizontal viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AnimeFilterChoices(options: List<T>, selected: T, label: (T) -> String,
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
    var otherYearVisible by remember { mutableStateOf(false) }
    var allGenres by remember(provider) { mutableStateOf(false) }

    fun selectProvider(next: AnimeDiscoveryProvider) {
        if (next == provider) return
        draftOptions[provider] = query.copy(provider = provider, year = year.toIntOrNull(), season = season,
            tag = tag, order = order, region = region, format = format, combined = false)
        val previous = draftOptions[next] ?: rememberedOptions(next)
        val nextOrders = animeDiscoveryOrders(next)
        order = previous?.order?.takeIf { it in nextOrders } ?: nextOrders.first()
        year = previous?.year?.toString().orEmpty()
        otherYearVisible = false
        season = previous?.season?.takeUnless { next == AnimeDiscoveryProvider.DOUBAN }
        tag = previous?.tag.orEmpty()
        region = previous?.region?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }.orEmpty()
        format = previous?.format?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }
        provider = next
        error = null
    }

    val scope = listOfNotNull(animeDiscoveryProviderLabel(provider), year.takeIf(String::isNotBlank)?.let { "${it} 年" },
        season?.takeUnless { provider == AnimeDiscoveryProvider.DOUBAN }?.let(::animeSeasonLabel)).joinToString(" · ")
    val clearAvailable = year.isNotEmpty() || season != null || tag.isNotBlank() || region.isNotEmpty() || format != null ||
        (tab != AnimeDiscoveryTab.HOME && order != AnimeDiscoveryOrder.POPULARITY)

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
                    AnimeFilterChoices(orders, order, ::animeDiscoveryFilterOrderLabel) { order = it }
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
                AnimeFilterGroup("题材") {
                    val genres = when (provider) {
                        AnimeDiscoveryProvider.BANGUMI -> bangumiGenreShortcuts.toList()
                        AnimeDiscoveryProvider.DOUBAN -> doubanGenreLabels.map { it to it }
                        AnimeDiscoveryProvider.ANILIST -> animeDiscoveryGenreLabels.toList()
                    }
                    val selectedTag = tag.trim()
                    val shown = if (allGenres) genres else genres.filterIndexed { index, option ->
                        index < 5 || option.first == selectedTag
                    }
                    val customTagEditing = allGenres && provider == AnimeDiscoveryProvider.BANGUMI
                    val otherSelected = selectedTag.takeIf { value ->
                        !customTagEditing && value.isNotEmpty() && genres.none { it.first == value }
                    }
                    val labels = (listOf("" to "全部题材") + shown + listOfNotNull(otherSelected?.let {
                        it to (animeDiscoveryGenreLabels[it] ?: it)
                    })).toMap()
                    AnimeFilterChoices(labels.keys.toList(), selectedTag, { labels.getValue(it) }) { tag = it }
                    AnimeFilterDisclosure(if (allGenres) "收起题材" else "更多题材", allGenres) { allGenres = !allGenres }
                    if (customTagEditing)
                        AnimeTextField(tag, { tag = it }, "输入站内标签", emptyHint = "例如 音乐")
                }
                if (!seasonal && tab != AnimeDiscoveryTab.HOME) AnimeFilterGroup("资料目录") {
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
                    year = ""; season = null; tag = ""; region = ""; format = null
                    if (tab != AnimeDiscoveryTab.HOME) order = AnimeDiscoveryOrder.POPULARITY
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
                        onConfigure(query.copy(provider = provider, year = parsed,
                            season = season.takeUnless { provider == AnimeDiscoveryProvider.DOUBAN },
                            tag = tag.trim(), order = order,
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
