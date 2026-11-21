package dev.mangaflow.anime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

private const val FEATURED_PREVIEW = 6

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, active: Boolean,
    contentBottomPadding: Dp = 24.dp) {
    var scoreItem by remember { mutableStateOf<AnimeDiscoveryItem?>(null) }
    var scoreVisible by remember { mutableStateOf(false) }
    val browseActive = active && scoreItem == null
    LaunchedEffect(active, state.discoveryTab) { scoreVisible = false }
    fun openScores(item: AnimeDiscoveryItem) {
        if (browseActive) {
            scoreItem = item
            scoreVisible = true
        }
    }
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it }, order = { it.ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, scrolls, contentBottomPadding,
                onOpen = onOpenItem, onScores = ::openScores, onResume = onResume,
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
                        onRetry = { controller.retryDiscovery(key) },
                        onMore = { controller.loadMoreDiscovery(key) },
                        onClearTag = { feed?.let { controller.configureDiscovery(tab, it.query.copy(tag = "")) } })
                }
            }
        }
    }
    scoreItem?.let { item ->
        AnimeDiscoveryScorePanel(scoreVisible && active, item, backdrop,
            onDismiss = { scoreVisible = false }, onClosed = { scoreItem = null })
    }
}

@Composable
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit, onResume: (AnimeLibraryEntry) -> Unit, active: Boolean) {
    val homeScroll = scrolls.grid("home")
    val popular = state.discoveryFeeds["home:douban:popular"]
    val featured = remember(popular?.items) {
        popular?.items.orEmpty().asSequence().filter { it.coverUrl.isNotBlank() }
            .distinctBy { it.key }.take(FEATURED_PREVIEW).map { item ->
                val provider = item.discoveryProvider ?: AnimeDiscoveryProvider.DOUBAN
                val rated = item.copy(sourceScores = animeDiscoverySourceScores(item, provider))
                AnimeFeaturedEntry(key = "douban:" + item.key, title = item.title, coverUrl = item.coverUrl,
                    sourceId = animeDiscoverySourceId(item.coverProvider ?: provider), sources = emptyList(),
                    caption = listOfNotNull(item.year?.toString(),
                        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · "),
                    discoveryItem = rated)
            }.toList()
    }
    val genres = state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.GENRES)]?.query
        ?.takeIf { it.provider == AnimeDiscoveryProvider.DOUBAN && !it.combined }
        ?: AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
    val galleryInView by remember(homeScroll) {
        derivedStateOf { homeScroll.layoutInfo.visibleItemsInfo.any { it.key == "featured" } }
    }
    LaunchedEffect(featured.isNotEmpty(), active) {
        if (featured.isNotEmpty() && active && !scrolls.placedHomeFeature) {
            scrolls.placedHomeFeature = true
            if (state.resumeEntry == null && !homeScroll.isScrollInProgress && homeScroll.firstVisibleItemScrollOffset == 0 &&
                homeScroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key in
                setOf("section:home:douban:popular", "genre-shortcuts")) homeScroll.requestScrollToItem(0)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
        LazyVerticalGrid(GridCells.Fixed(columns), state = homeScroll, modifier = Modifier.fillMaxSize(),
            userScrollEnabled = active,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.resumeEntry?.let { entry ->
                item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeContinueWatching(entry,
                        onResume = { if (active) onResume(entry) },
                        onDismiss = { if (active) controller.dismissResume(entry.subject.id) },
                        modifier = Modifier.motionInputGate(active))
                }
            }
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = active, visible = active && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpenSources = { entry -> if (active) entry.discoveryItem?.let(onScores) },
                    onOpen = { entry -> if (active) entry.discoveryItem?.let(onOpen) })
            }
            item(key = "genre-shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                AnimeGenreShortcuts(genres, selected = null, active = active, home = true,
                    onSelect = { controller.configureDiscovery(AnimeDiscoveryTab.GENRES, genres.copy(tag = it)) })
            }
            state.discoverySections.filter { it.key in setOf("home:douban:popular", "home:douban:japan",
                "home:douban:china", "home:douban:movies") }.forEach { section ->
                animeHomeMetadataSection(section, state.discoveryFeeds[section.key], columns, active, onOpen, onScores,
                    onMore = { controller.openDiscoverySection(section.key) },
                    onRetry = { controller.retryDiscovery(section.key) })
            }
        }
    }
}

private fun LazyGridScope.animeHomeMetadataSection(
    section: AnimeDiscoverySection, feed: AnimeDiscoveryFeed?, columns: Int, active: Boolean,
    onOpen: (AnimeDiscoveryItem) -> Unit, onScores: (AnimeDiscoveryItem) -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit,
) {
    item(key = "section:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
        AnimeDiscoverySectionHeading(section.title, "",
            refreshing = feed?.loading == true, onMore = onMore.takeIf { active })
    }
    if (feed != null && feed.items.isNotEmpty()) {
        items(feed.items.distinctBy { it.key }.take(if (columns == 1) 3 else columns),
            key = { section.key + ":" + it.key }) { item ->
            AnimeDiscoveryCard(item, feed.query, list = columns == 1, onScores = onScores.takeIf { active },
                onOpen = { if (active) onOpen(item) })
        }
    }
    val hasScopedErrors = feed?.query?.combined == true && feed.sourceErrors.isNotEmpty()
    if (feed?.error != null && !hasScopedErrors) {
        item(key = "error:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
            val status = if (feed.items.isNotEmpty()) "暂未更新，显示上次结果" else feed.error
            AnimeDiscoveryInlineStatus(status, retry = onRetry.takeIf { active })
        }
    } else if (!hasScopedErrors && (feed == null || feed.items.isEmpty())) {
        item(key = "empty:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
            AnimeDiscoveryInlineStatus(if (feed == null || feed.loading || feed.fetchedAt == null) "正在载入" else "暂无内容")
        }
    }
    if (feed != null && hasScopedErrors) {
        item(key = "partial:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
            AnimeDiscoveryPartialStatus(feed, onRetry.takeIf { active && !feed.loading })
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
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    showIssue: Boolean = retry != null) {
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showIssue) Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (expanded || overflow) MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp),
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
}

@Composable
private fun AnimeDiscoveryFeedGrid(key: String, feed: AnimeDiscoveryFeed?, preference: Int?,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, modifier: Modifier, active: Boolean,
    onOpen: (AnimeDiscoveryItem) -> Unit, onScores: (AnimeDiscoveryItem) -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit, onClearTag: () -> Unit) {
    val scroll = scrolls.grid(key)
    val combined = feed?.query?.combined == true
    val hasScopedErrors = combined && feed?.sourceErrors?.isNotEmpty() == true
    LaunchedEffect(key, feed?.query) {
        feed?.query?.let { query ->
            val previous = scrolls.queries.put(key, query)
            if (previous != null && previous != query) scroll.scrollToItem(0)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preference)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize(),
            userScrollEnabled = active,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (feed?.query?.calendar == true || feed?.loading == true && feed.items.isNotEmpty()) item(
                key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (feed?.query?.calendar == true) AnimeQuietLabel(animeDiscoveryCaption(feed.query), Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (feed?.loading == true && feed.items.isNotEmpty()) AnimeQuietLabel("更新中")
                }
            }
            if (feed?.error != null && !hasScopedErrors) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                val listName = when (key) {
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.SEASON) -> animeDiscoverySeasonTitle(feed.query) + "番单"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.CALENDAR) -> "番表"
                    animeDiscoveryFeedKey(AnimeDiscoveryTab.RANKING) -> "番剧榜单"
                    else -> "分类番剧"
                }
                val provider = if (feed.query.combined) "" else animeDiscoveryProviderLabel(feed.query.provider) + " · "
                val timestamp = feed.fetchedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
                val status = if (feed.items.isNotEmpty() && timestamp != null) "${provider}显示 $timestamp 的上次结果"
                    else "$provider${listName}暂未载入"
                Column {
                    AnimeDiscoveryInlineStatus(status, onRetry.takeIf { active })
                    AnimeQuietLabel(feed.error)
                }
            }
            if (feed == null || feed.items.isEmpty()) {
                if (feed?.error == null && !hasScopedErrors) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (feed == null || feed.loading || feed.page == 0) AnimeDiscoveryInlineStatus("正在载入番剧")
                    else AnimeDiscoveryInlineStatus("没有符合当前条件的番剧",
                        onClearTag.takeIf { active && feed.query.tag.isNotBlank() }, retryLabel = "清除题材", showIssue = false)
                }
            }
            else if (feed.query.calendar && feed.query.weekday == null) {
                feed.items.groupBy { it.airWeekday?.takeIf { day -> day in 1..7 } ?: 8 }
                    .toList().sortedBy { it.first }.forEach { (day, shows) ->
                        item(key = "weekday:$day", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoverySectionHeading(
                                if (day in 1..7) listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day - 1]
                                else "放送日未标注", "${shows.size} 部")
                        }
                        items(shows, key = { it.key }) { item ->
                            AnimeDiscoveryCard(item, feed.query, list = columns == 1, onScores = onScores.takeIf { active },
                                onOpen = { if (active) onOpen(item) })
                        }
                    }
            } else items(feed.items, key = { it.key }) { item ->
                AnimeDiscoveryCard(item, feed.query, list = columns == 1, onScores = onScores.takeIf { active },
                    onOpen = { if (active) onOpen(item) })
            }
            if (feed != null && hasScopedErrors) item(
                key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryPartialStatus(feed, onRetry.takeIf { active && !feed.loading && !feed.loadingMore })
            }
            if (feed?.moreError != null && !hasScopedErrors) item(key = "more-error", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    AnimeDiscoveryInlineStatus(if (combined) "下一页暂未载入" else "第 ${feed.page.toLong() + 1} 页暂未载入",
                        onMore.takeIf { active }, retryLabel = "重试本页")
                    AnimeQuietLabel(feed.moreError)
                }
            }
            if (feed?.hasMore == true && (combined || feed.moreError == null)) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                MotionTextButton(onClick = onMore, enabled = active && !feed.loadingMore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (feed.loadingMore) "正在加载" else "加载更多")
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryPartialStatus(feed: AnimeDiscoveryFeed, onRetry: (() -> Unit)?) {
    val label = buildList {
        if (feed.error != null && feed.items.isNotEmpty()) add("显示上次结果")
        feed.sourceErrors.forEach { (source, error) -> add("${animeDiscoveryProviderLabel(source)} · $error") }
    }
    AnimeDiscoveryInlineStatus(label.joinToString("\n"), retry = onRetry, retryLabel = "重试资料", showIssue = true)
}

@Composable
private fun AnimeDiscoveryScorePanel(visible: Boolean, item: AnimeDiscoveryItem, backdrop: HazeState,
    onDismiss: () -> Unit, onClosed: () -> Unit) {
    val heading = if (item.sourceScores.isEmpty()) "资料来源" else "来源评分"
    MotionDialog(visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 420.dp, onClosed = onClosed) {
        AnimePanelSurface(backdrop, Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
                .semantics { paneTitle = heading }) {
                AnimeSectionTitle(heading, item.title, action = {
                    MotionIconButton(onClick = onDismiss, enabled = visible, modifier = Modifier.size(44.dp),
                        tooltip = "关闭$heading") {
                        Icon(Icons.Outlined.Close, "关闭$heading", Modifier.size(19.dp))
                    }
                })
                animeDiscoverySourceProviders(item).forEach { provider ->
                    val score = item.sourceScores.firstOrNull { it.provider == provider }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewSourceMark(animeDiscoveryScoreSourceId(provider), size = 18.dp)
                        Text(animeDiscoveryProviderLabel(provider), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        if (score == null) Text("暂无评分", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                        else Text(animeDiscoveryScoreText(score),
                            Modifier.semantics {
                                contentDescription = "${detailMetricScore(score.rating)} 分，满分 ${score.scale}"
                            }, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
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
        AnimeChoiceStrip(listOf("全周", "周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            selected = query.weekday ?: 0, active = active,
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
    val seasonal = tab == AnimeDiscoveryTab.SEASON
    val summary = animeDiscoveryConditions(query, includeTime = !seasonal)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (seasonal) Text(animeDiscoverySeasonTitle(query), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                AnimeQuietLabel(summary)
            }
            MotionTextButton(onClick = {
                returnFocus = triggerFocused
                revision++
                present = true
                visible = true
            }, enabled = controlsActive, modifier = Modifier.heightIn(min = 48.dp)
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
                        if (!seasonal) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
