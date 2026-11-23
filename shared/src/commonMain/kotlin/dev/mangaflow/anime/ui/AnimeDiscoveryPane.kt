package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

private const val FEATURED_PREVIEW = 6

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onManage: (AnimeSubject) -> Unit, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit, onWatchHistory: () -> Unit,
    contentBottomPadding: Dp = 24.dp) {
    val ratings = LocalAnimeRatings.current
    val browseActive = active
    fun openScores(item: AnimeDiscoveryItem) {
        if (browseActive) ratings.open(item.ratingRequest())
    }
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it }, order = { it.ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding,
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
                Column(Modifier.fillMaxSize().motionInputGate(tabActive)) {
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
                        modifier = Modifier.weight(1f).motionInputGate(contentActive), active = contentActive,
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
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit, onManage: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, onWatchHistory: () -> Unit, active: Boolean) {
    val key = animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME)
    val feed = state.discoveryFeeds[key]
    val query = feed?.query ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
    var overlayPresent by remember { mutableStateOf(false) }
    val contentActive = active && !overlayPresent
    val featured = remember(feed?.items, query) {
        val unfiltered = query.order == AnimeDiscoveryOrder.POPULARITY && query.region.isBlank() &&
            query.tag.isBlank() && query.year == null && query.format != AnimeDiscoveryFormat.MOVIE
        if (!unfiltered) emptyList() else feed?.items.orEmpty().asSequence().filter { it.coverUrl.isNotBlank() }
            .distinctBy { it.key }.take(FEATURED_PREVIEW).map { item ->
                val provider = item.discoveryProvider ?: AnimeDiscoveryProvider.DOUBAN
                val rated = item.copy(sourceScores = animeDiscoverySourceScores(item, provider))
                AnimeFeaturedEntry(key = "douban:" + item.key, title = item.title, coverUrl = item.coverUrl,
                    sourceId = animeDiscoverySourceId(item.coverProvider ?: provider),
                    caption = listOfNotNull(item.year?.toString(),
                        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · "),
                    discoveryItem = rated)
            }.toList()
    }
    val continueWatching = remember(state.library, query) {
        val defaultFeed = query.order == AnimeDiscoveryOrder.POPULARITY && query.region.isBlank() &&
            query.tag.isBlank() && query.year == null && query.format != AnimeDiscoveryFormat.MOVIE
        if (!defaultFeed) emptyList()
        else state.library.asSequence()
            .filter { it.resume != null && !it.resume.completed && it.status != AnimeWatchStatus.DROPPED }
            .sortedByDescending { it.resume?.updatedAt ?: 0L }
            .take(5).toList()
    }
    Column(Modifier.fillMaxSize().motionInputGate(active)) {
        AnimeDiscoveryControls(AnimeDiscoveryTab.HOME, query, backdrop, active,
            onOverlayPresence = { overlayPresent = it },
            rememberedOptions = { controller.rememberedDiscoveryOptions(AnimeDiscoveryTab.HOME, it) },
            onConfigure = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, it) })
        AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
            modifier = Modifier.weight(1f).motionInputGate(contentActive), active = contentActive,
            onOpen = onOpen, onScores = onScores, onManage = onManage,
            onRefresh = controller::refreshDiscovery, onRetry = { controller.retryDiscovery(key) },
            onMore = { controller.loadMoreDiscovery(key) },
            onClearTag = { controller.configureDiscovery(AnimeDiscoveryTab.HOME, query.copy(tag = "")) },
            featured = featured,
            watchShelf = if (continueWatching.isEmpty()) null else ({
                AnimeDiscoveryWatchShelf(continueWatching, contentActive, onResume, onWatchHistory)
            }))
    }
}

@Composable
private fun AnimeDiscoveryWatchShelf(entries: List<AnimeLibraryEntry>, active: Boolean,
    onResume: (AnimeLibraryEntry) -> Unit, onHistory: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AnimeDiscoverySectionHeading("继续观看", "", onMore = onHistory.takeIf { active }, moreLabel = "观看历史")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileWidth = (maxWidth - if (entries.size > 1) 22.dp else 0.dp)
                .coerceIn(1.dp, if (entries.size == 1) 340.dp else 232.dp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), userScrollEnabled = active) {
                items(entries, key = { it.subject.id }) { entry ->
                    val progress = entry.resume ?: return@items
                    val colors = MaterialTheme.colorScheme
                    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
                    Row(Modifier.width(tileWidth).heightIn(min = 70.dp).clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceContainerLow).motionPress(interaction, enabled = active, pressedScale = .98f)
                        .clickable(interactionSource = interaction, indication = null, enabled = active,
                            role = Role.Button, onClickLabel = "继续观看", onClick = { onResume(entry) })
                        .padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        AnimePoster(entry.subject, Modifier.width(36.dp).height(51.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.subject.title, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(animeWatchLocation(entry, progress), color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            animeWatchFraction(progress)?.let { AnimeWatchProgressLine(it, Modifier.fillMaxWidth()) }
                        }
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(21.dp), tint = colors.onSurface)
                    }
                }
            }
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
    watchShelf: (@Composable () -> Unit)? = null) {
    val retainedScroll = scrolls.grid(key, feed?.query)
    // The query's completed page range is restored by AnimeDiscoveryLoads before this state is used.
    // A temporary empty/loading layout must not clamp that saved position to its single status row.
    val loadingScroll = remember(key, feed?.query) { LazyGridState() }
    val hasWorks = feed?.items?.isNotEmpty() == true
    val scroll = if (hasWorks) retainedScroll else loadingScroll
    val galleryInView by remember(scroll) {
        derivedStateOf { scroll.layoutInfo.visibleItemsInfo.any { it.key == "featured" } }
    }
    val featuredKeys = remember(featured) { featured.map { it.discoveryItem.key }.toSet() }
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
        indicatorTop = 4.dp, actionLabel = "刷新番剧") {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preference)
        val inset = animeDiscoveryInset(maxWidth, preference, columns)
        if (desktop) AnimeBrowsePagination(key = key to feed?.query, refreshKey = feed?.fetchedAt,
            itemCount = feed?.items?.size ?: 0, enabled = active && pagingAvailable,
            nearEnd = {
                val info = scroll.layoutInfo
                val trailingKeys = feed?.items.orEmpty().takeLast(columns * 2).map { it.key }.toSet()
                info.visibleItemsInfo.any { it.key in trailingKeys } &&
                    (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - columns * 2 - 1
            }, onMore = onMore)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
            .then(if (desktop) Modifier else Modifier.bottomPullLoad(pull)),
            userScrollEnabled = active,
            contentPadding = PaddingValues(start = inset, end = inset, top = 4.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(animeDiscoveryGap(columns)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = active, visible = active && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpenScores = { entry -> if (active) onScores(entry.discoveryItem) },
                    onOpen = { entry -> if (active) onOpen(entry.discoveryItem) })
            }
            if (watchShelf != null) item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) { watchShelf() }
            if (key == animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME) &&
                feed != null && feed.items.any { it.key !in featuredKeys }) {
                item(key = "discovery-heading", span = { GridItemSpan(maxLineSpan) }) {
                    val restrictions = animeHomeScope(feed.query)
                    val heading = when (feed.query.order) {
                        AnimeDiscoveryOrder.NEWEST -> "最近上架"
                        AnimeDiscoveryOrder.SCORE -> "高分番剧"
                        else -> "热门番剧"
                    }
                    AnimeDiscoverySectionHeading(heading, restrictions, refreshing = feed.loading)
                }
            }
            if (feed?.loading == true && feed.items.isNotEmpty() && key != animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME)) item(
                key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    AnimeQuietLabel(when {
                        feed.retryScheduled -> "等待自动重试 · 第 ${feed.retryAttempt} 次"
                        feed.retryAttempt > 0 -> "自动重试中 · 第 ${feed.retryAttempt} 次"
                        else -> "更新中"
                    })
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
            if (feed == null || feed.items.isEmpty()) {
                if (feed?.error == null && !hasScopedErrors) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (feed == null || feed.loading || feed.page == 0) AnimeDiscoveryLoading(
                        if ((feed?.retryAttempt ?: 0) > 0) "正在重新载入番剧" else "正在载入番剧")
                    else AnimeDiscoveryInlineStatus(if (feed.hasMore) "本页暂无匹配的番剧" else "没有符合当前条件的番剧",
                        onClearTag.takeIf { active && feed.query.tag.isNotBlank() }, retryLabel = "清除题材", showIssue = false)
                }
            }
            else if (feed.query.calendar && feed.query.weekday == null) {
                feed.items.groupBy { it.airWeekday?.takeIf { day -> day in 1..7 } ?: 8 }
                    .toList().sortedBy { it.first }.forEach { (day, shows) ->
                        item(key = "weekday:$day", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoverySectionHeading(
                                if (day in 1..7) animeWeekdays[day - 1] else "放送日期待定",
                                if (day == localToday().dayOfWeek.ordinal + 1) "今天" else "")
                        }
                        items(shows, key = { it.key }) { item ->
                            AnimeDiscoveryCard(item, feed.query, list = columns == 1, onScores = onScores.takeIf { active },
                                onManage = item.subject?.takeIf { active }?.let { subject -> { onManage(subject) } },
                                onOpen = { if (active) onOpen(item) })
                        }
                    }
            } else items(feed.items.filterNot { it.key in featuredKeys }, key = { it.key }) { item ->
                AnimeDiscoveryCard(item, feed.query, list = columns == 1, onScores = onScores.takeIf { active },
                    onManage = item.subject?.takeIf { active }?.let { subject -> { onManage(subject) } },
                    onOpen = { if (active) onOpen(item) })
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
        AnimeChoiceStrip(listOf("全周") + animeWeekdays.mapIndexed { index, day ->
            if (index + 1 == today) "今天" else day
        }, selected = query.weekday ?: 0, active = active,
            onSelect = { onConfigure(query.copy(weekday = it.takeIf { day -> day > 0 })) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        return
    }

    var visible by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val trigger = remember { FocusRequester() }
    var triggerFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val controlsActive = active && !present
    val colors = MaterialTheme.colorScheme
    val home = tab == AnimeDiscoveryTab.HOME
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    val summary = animeDiscoveryConditions(query, includeTime = !seasonal)
    fun openFilters() {
        returnFocus = triggerFocused
        revision++
        present = true
        visible = true
    }
    SideEffect { onOverlayPresence(present) }
    LaunchedEffect(active, present) {
        if (!active) {
            visible = false
            returnFocus = false
        } else if (!present && returnFocus) {
            returnFocus = false
            trigger.requestFocus()
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        if (home) {
            val orders = listOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.NEWEST, AnimeDiscoveryOrder.SCORE)
            val scope = animeHomeScope(query)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MotionTabs(orders.indexOf(query.order), listOf("热门", "最新", "高分"),
                    onSelect = { if (controlsActive) onConfigure(query.copy(order = orders[it])) },
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp).motionInputGate(controlsActive),
                    treatment = MotionTabTreatment.Soft, controlHeight = 44.dp)
                MotionTextButton(onClick = ::openFilters, enabled = controlsActive,
                    modifier = Modifier.heightIn(min = 44.dp).focusRequester(trigger).onFocusChanged { triggerFocused = it.isFocused },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (scope.isBlank()) colors.surfaceContainerLow else colors.surfaceContainerHigh,
                        contentColor = colors.onSurface),
                    contentPadding = PaddingValues(horizontal = 11.dp)) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scope.isBlank()) "筛选" else "已筛选")
                }
            }
        } else {
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
    key(revision) {
        AnimeDiscoveryFilterPanel(visible && active, tab, query, backdrop, rememberedOptions,
            onDismiss = { visible = false }, onClosed = { present = false }, onConfigure = onConfigure)
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
            containerColor = if (selected) colors.surfaceContainerHigh else colors.surfaceContainerLow,
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
private fun AnimeDiscoveryFilterPanel(visible: Boolean, tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery,
    backdrop: HazeState, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onDismiss: () -> Unit, onClosed: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
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
    val today = localToday()

    fun selectProvider(next: AnimeDiscoveryProvider) {
        if (next == provider) return
        draftOptions[provider] = query.copy(provider = provider, year = year.toIntOrNull(), season = season,
            tag = tag, order = order, region = region, format = format, combined = false)
        val previous = draftOptions[next] ?: rememberedOptions(next)
        val nextOrders = animeDiscoveryOrders(next)
        order = previous?.order?.takeIf { it in nextOrders } ?: nextOrders.first()
        year = previous?.year?.toString().orEmpty()
        season = previous?.season?.takeUnless { next == AnimeDiscoveryProvider.DOUBAN }
        tag = previous?.tag.orEmpty()
        region = previous?.region?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }.orEmpty()
        format = previous?.format?.takeIf { next == AnimeDiscoveryProvider.DOUBAN }
        provider = next
        error = null
    }

    MotionDialog(visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 500.dp, onClosed = onClosed) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            AnimePanelSurface(backdrop, Modifier.fillMaxWidth().height(minOf(maxHeight, 580.dp))) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { paneTitle = "番剧筛选与排序" }) {
                    AnimeSectionTitle("筛选与排序", when (tab) {
                        AnimeDiscoveryTab.HOME -> "豆瓣 · 动画目录"
                        AnimeDiscoveryTab.SEASON -> "Bangumi 新番"
                        AnimeDiscoveryTab.RANKING -> "榜单"
                        else -> "分类"
                    }, action = {
                        MotionIconButton(onClick = onDismiss, enabled = visible, modifier = Modifier.size(48.dp),
                            tooltip = "关闭筛选，不应用修改") {
                            Icon(Icons.Outlined.Close, "关闭筛选，不应用修改", Modifier.size(20.dp))
                        }
                    })
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AnimeQuietLabel("排序")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                orders.forEach { option ->
                                    AnimeDiscoveryChoice(animeDiscoveryFilterOrderLabel(option), order == option, visible) {
                                        order = option
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AnimeQuietLabel(if (provider == AnimeDiscoveryProvider.DOUBAN) "上映年份" else "首播年份与季度")
                            AnimeTextField(year, { value ->
                                if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                    year = value
                                    if (value.isEmpty()) season = null
                                    error = null
                                }
                            }, "年份，留空为全部", enabled = visible)
                            if (provider != AnimeDiscoveryProvider.DOUBAN) {
                                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AnimeDiscoveryChoice("全年", season == null, visible) { season = null; error = null }
                                    AnimeSeason.entries.forEach { option ->
                                        AnimeDiscoveryChoice(animeSeasonLabel(option), season == option, visible) {
                                            if (year.isBlank()) year = today.year.toString()
                                            season = option
                                            error = null
                                        }
                                    }
                                }
                            }
                        }
                        if (provider == AnimeDiscoveryProvider.DOUBAN) {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                AnimeQuietLabel("地区")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("" to "全部", "日本" to "日本", "大陆" to "中国大陆").forEach { (value, label) ->
                                        AnimeDiscoveryChoice(label, region == value, visible) { region = value }
                                    }
                                }
                            }
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                AnimeQuietLabel("类型")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(AnimeDiscoveryFormat.SERIES, AnimeDiscoveryFormat.MOVIE).forEach { option ->
                                        val selected = (format ?: AnimeDiscoveryFormat.SERIES) == option
                                        AnimeDiscoveryChoice(animeDiscoveryFormatLabel(option), selected, visible) { format = option }
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AnimeQuietLabel("题材")
                            if (provider == AnimeDiscoveryProvider.DOUBAN) {
                                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AnimeDiscoveryChoice("全部", tag.isBlank(), visible) { tag = "" }
                                    doubanGenreLabels.forEach { label ->
                                        AnimeDiscoveryChoice(label, tag == label, visible) { tag = label }
                                    }
                                }
                            } else if (provider == AnimeDiscoveryProvider.BANGUMI) {
                                AnimeTextField(tag, { tag = it }, "题材标签，留空为全部", enabled = visible)
                            } else FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                AnimeDiscoveryChoice("全部", tag.isBlank(), visible) { tag = "" }
                                animeDiscoveryGenreLabels.forEach { (value, label) ->
                                    AnimeDiscoveryChoice(label, tag == value, visible) { tag = value }
                                }
                            }
                        }
                        if (!seasonal && tab != AnimeDiscoveryTab.HOME) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            CompactMenuAction(if (sourceOptionsVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                "资料目录 · ${animeDiscoveryProviderLabel(provider)}", enabled = visible,
                                onClick = { sourceOptionsVisible = !sourceOptionsVisible })
                            if (sourceOptionsVisible) FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                AnimeDiscoveryProvider.entries.forEach { scope ->
                                    AnimeDiscoveryChoice(animeDiscoveryProviderLabel(scope), provider == scope, visible) { selectProvider(scope) }
                                }
                            }
                        }
                    }
                    if (error != null) Text(error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = {
                            year = ""; season = null; tag = ""; region = ""; format = null
                            order = AnimeDiscoveryOrder.POPULARITY; error = null
                        }, enabled = visible, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("清除条件") }
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
                                onDismiss()
                            }
                        }, enabled = visible, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface,
                                contentColor = colors.surface)) { Text("应用筛选") }
                    }
                }
            }
        }
    }
}
