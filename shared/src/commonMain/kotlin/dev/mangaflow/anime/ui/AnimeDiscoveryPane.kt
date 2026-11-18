package dev.mangaflow.anime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.AnimeSourceHomeFeed
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpen: (AnimeSubject) -> Unit,
    onChooseReference: (AnimeSourceTitle, Boolean) -> Unit, active: Boolean,
    contentBottomPadding: Dp = 24.dp) {
    fun open(item: AnimeDiscoveryItem) {
        item.subject?.let(onOpen) ?: item.reference?.let { onChooseReference(it, false) }
    }
    MotionTabContent(state.discoveryTab, Modifier.fillMaxSize(), contentKey = { it }, order = { it.ordinal }) { tab ->
        when (tab) {
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding,
                onOpen = ::open, onChooseReference = onChooseReference,
                active = active && state.discoveryTab == tab)
            AnimeDiscoveryTab.SOURCES -> Unit
            else -> {
                val key = animeDiscoveryFeedKey(tab)
                val feed = state.discoveryFeeds[key]
                Column(Modifier.fillMaxSize()) {
                    if (feed != null) AnimeDiscoveryControls(tab, feed.query, backdrop, active && state.discoveryTab == tab,
                        onConfigure = { controller.configureDiscovery(tab, it) })
                    AnimeDiscoveryFeedGrid(key, feed, state.discoveryColumns, scrolls, contentBottomPadding,
                        modifier = Modifier.weight(1f), onOpen = ::open,
                        onRetry = { controller.retryDiscovery(key) },
                        onMore = { controller.loadMoreDiscovery(key) })
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onChooseReference: (AnimeSourceTitle, Boolean) -> Unit, active: Boolean) {
    val homeFeeds by controller.sourceBrowse.homeFeeds.collectAsStateWithLifecycle()
    val homeScroll = scrolls.grid("home")
    val availableFeeds = remember(homeFeeds) { homeFeeds.values.toList() }
    val firstContent = availableFeeds.filter { feed -> feed.sections.any { it.items.isNotEmpty() } }
        .minByOrNull { it.fetchedAt ?: Long.MAX_VALUE }
    val selectedFeed = availableFeeds.firstOrNull { it.source.id == scrolls.homeSourceId }
        ?: firstContent ?: availableFeeds.firstOrNull()
    // Only the first usable feed is chosen automatically. A deliberate selection, including
    // a failed source, remains selected while its own refresh/retry updates the same objects.
    LaunchedEffect(firstContent?.source?.id, scrolls.homeSourceId) {
        if (scrolls.homeSourceId == null && firstContent != null)
            scrolls.homeSourceId = firstContent.source.id
    }
    var sourceMenuPresent by remember { mutableStateOf(false) }
    val contentActive = active && !sourceMenuPresent
    val featured = remember(homeFeeds) {
        val rows = homeFeeds.values.filter { it.source.videoSource }
            .sortedBy { it.fetchedAt ?: Long.MAX_VALUE }.map { feed ->
            feed.sections.flatMap { it.items }.distinctBy { it.sourceId to it.id }.map { title ->
                AnimeFeaturedEntry(
                    key = "source:" + title.sourceId + ":" + title.id,
                    title = title.title, coverUrl = title.coverUrl, sourceId = title.sourceId,
                    sourceLabel = feed.source.name,
                    caption = title.updateText.ifBlank { title.year?.toString().orEmpty() },
                    sourceTitle = title,
                )
            }
        }
        // Keep original source objects, taking one from each available video feed per round.
        // Browse-only metadata never acquires a playable-source shortcut.
        buildList {
            var index = 0
            while (size < 6) {
                var found = false
                rows.forEach { row ->
                    row.getOrNull(index)?.let { entry ->
                        found = true
                        if (size < 6) add(entry)
                    }
                }
                if (!found) break
                index++
            }
        }
    }
    val galleryInView by remember(homeScroll) {
        derivedStateOf { homeScroll.layoutInfo.visibleItemsInfo.any { it.key == "featured" } }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
        val previewCount = if (columns == 1) 4 else columns * 2
        LazyVerticalGrid(GridCells.Fixed(columns), state = homeScroll, modifier = Modifier.fillMaxSize(),
            userScrollEnabled = contentActive,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = contentActive, visible = contentActive && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpen = { entry ->
                        if (contentActive) entry.sourceTitle?.let { onChooseReference(it, true) }
                    })
            }
            if (selectedFeed != null) {
                item(key = "home-source-selector", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeHomeSourceSelector(availableFeeds, selectedFeed, backdrop, active,
                        onSelect = { scrolls.homeSourceId = it },
                        onPresence = { sourceMenuPresent = it },
                        onRefresh = { controller.sourceBrowse.refreshHomeSource(selectedFeed.source.id) })
                }
                selectedFeed.let { feed ->
                    val source = feed.source
                    val prefix = "source-home:" + source.id + ":" + source.revision
                    val sections = feed.sections.withIndex().filter { it.value.items.isNotEmpty() }
                    if (feed.error != null) item(key = "${prefix}:error", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoveryInlineStatus(feed.error, retry = {
                            if (contentActive) controller.sourceBrowse.refreshHomeSource(source.id)
                        })
                    }
                    if (sections.isEmpty() && feed.error == null) {
                        item(key = "${prefix}:state", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoveryInlineStatus(
                                if (feed.loading || feed.fetchedAt == null) "正在读取来源内容" else "这个来源暂时没有内容",
                            )
                        }
                    }
                    sections.forEach { (index, section) ->
                        val sectionKey = prefix + ":" + section.key + ":" + index
                        val expanded = sectionKey in scrolls.expandedHomeSections
                        item(key = "${sectionKey}:heading", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoverySectionHeading(section.title, "", expanded = expanded,
                                moreLabel = if (expanded) "收起" + section.title else "展开" + section.title,
                                onMore = if (contentActive && section.items.size > previewCount) ({
                                    scrolls.expandedHomeSections = if (expanded)
                                        scrolls.expandedHomeSections - sectionKey else scrolls.expandedHomeSections + sectionKey
                                }) else null)
                        }
                        items(section.items.take(if (expanded) Int.MAX_VALUE else previewCount),
                            key = { sectionKey + ":" + it.sourceId + ":" + it.id }) { title ->
                            AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = {
                                if (contentActive) onChooseReference(title, source.videoSource)
                            })
                        }
                    }
                }
            }
            state.discoverySections.forEach { section ->
                val feed = state.discoveryFeeds[section.key]
                item(key = "section:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoverySectionHeading(section.title, animeDiscoveryProviderLabel(section.query.provider),
                        refreshing = feed?.loading == true,
                        onMore = if (contentActive) ({ controller.openDiscoverySection(section.key) }) else null)
                }
                if (feed?.error != null) item(key = "error:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(feed.error, retry = {
                        if (contentActive) controller.retryDiscovery(section.key)
                    })
                }
                if (feed == null || feed.items.isEmpty()) item(key = "empty:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(if (feed == null || feed.loading) "正在载入" else if (feed.error == null) "暂无内容" else "")
                }
                else items(feed.items.take(if (columns == 1) 3 else columns), key = { section.key + ":" + it.key }) { item ->
                    AnimeDiscoveryCard(item, feed.query, list = columns == 1, onOpen = {
                        if (contentActive) onOpen(item)
                    })
                }
            }
        }
    }
}

/** Source choice discloses the existing feeds; it never enables a source or changes playback priority. */
@Composable
private fun AnimeHomeSourceSelector(feeds: List<AnimeSourceHomeFeed>, selected: AnimeSourceHomeFeed,
    backdrop: HazeState, active: Boolean, onSelect: (String) -> Unit, onPresence: (Boolean) -> Unit,
    onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    var pendingSource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        if (!active) { expanded = false; pendingSource = null }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("来源内容", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.widthIn(max = 180.dp)) {
            MotionTextButton(onClick = { pendingSource = null; expanded = true }, enabled = active && !present,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(if (selected.source.videoSource) Icons.Outlined.Tv else Icons.Outlined.Explore,
                    null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(selected.source.name, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, "切换浏览来源", Modifier.size(16.dp))
            }
            CompactGlassPopover(expanded, backdrop,
                onDismiss = { expanded = false; pendingSource = null },
                onPresenceChange = { showing ->
                    present = showing
                    onPresence(showing)
                    if (!showing) {
                        val next = pendingSource
                        pendingSource = null
                        if (active && next != null && feeds.any { it.source.id == next }) onSelect(next)
                    }
                }) {
                AnimeQuietLabel("浏览来源", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                feeds.forEach { feed ->
                    val hasContent = feed.sections.any { it.items.isNotEmpty() }
                    val status = when {
                        feed.error != null -> if (hasContent) "更新未完成" else "暂不可用"
                        feed.loading || feed.fetchedAt == null -> "更新中"
                        !hasContent -> "暂无内容"
                        else -> null
                    }
                    CompactMenuAction(
                        if (feed.source.id == selected.source.id) Icons.Outlined.Check
                        else if (feed.error != null) Icons.Outlined.CloudOff
                        else if (feed.loading || feed.fetchedAt == null) Icons.Outlined.Sync
                        else if (feed.source.videoSource) Icons.Outlined.Tv else Icons.Outlined.Explore,
                        listOfNotNull(feed.source.name, status).joinToString(" · "),
                        onClick = { pendingSource = feed.source.id; expanded = false },
                    )
                }
            }
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (selected.loading) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
            else if (selected.error == null) MotionIconButton(onClick = onRefresh, enabled = active && !present,
                modifier = Modifier.size(44.dp), tooltip = "更新${selected.source.name}") {
                Icon(Icons.Outlined.Refresh, "更新当前来源", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun AnimeDiscoverySectionHeading(title: String, caption: String, refreshing: Boolean = false,
    onMore: (() -> Unit)? = null, moreLabel: String = "查看全部$title", expanded: Boolean? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
internal fun AnimeDiscoveryInlineStatus(text: String, retry: (() -> Unit)? = null) {
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (retry != null) Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (expanded || overflow) MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp),
            tooltip = if (expanded) "收起详情" else "查看完整详情") {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起详情" else "查看完整详情", Modifier.size(18.dp))
        }
        if (retry != null) MotionIconButton(onClick = retry, modifier = Modifier.size(44.dp), tooltip = "重试此内容") {
            Icon(Icons.Outlined.Refresh, "重试此内容", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AnimeDiscoveryFeedGrid(key: String, feed: AnimeDiscoveryFeed?, preference: Int?,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, modifier: Modifier, onOpen: (AnimeDiscoveryItem) -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit) {
    val scroll = scrolls.grid(key)
    LaunchedEffect(key, feed?.query) {
        feed?.query?.let { query ->
            val previous = scrolls.queries.put(key, query)
            if (previous != null && previous != query) scroll.scrollToItem(0)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preference)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize(),
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
            if (feed?.error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(feed.error, onRetry)
            }
            if (feed == null || feed.items.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(if (feed == null || feed.loading) "正在载入番剧" else if (feed.error == null) "没有符合条件的番剧" else "")
            }
            else items(feed.items, key = { it.key }) { item ->
                AnimeDiscoveryCard(item, feed.query, list = columns == 1, onOpen = { onOpen(item) })
            }
            if (feed?.moreError != null) item(key = "more-error", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(feed.moreError, onMore)
            }
            if (feed?.hasMore == true) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                MotionTextButton(onClick = onMore, enabled = !feed.loadingMore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (feed.loadingMore) "正在加载" else "加载更多")
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscoveryControls(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery, backdrop: HazeState, active: Boolean,
    onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    var providerMenu by remember(tab) { mutableStateOf(false) }
    var orderMenu by remember(tab) { mutableStateOf(false) }
    var seasonMenu by remember(tab) { mutableStateOf(false) }
    var filters by remember(tab) { mutableStateOf(false) }
    var filtersAfterSeason by remember(tab) { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            filtersAfterSeason = false; providerMenu = false; orderMenu = false; seasonMenu = false; filters = false
        }
    }
    if (tab == AnimeDiscoveryTab.CALENDAR) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            val density = LocalDensity.current
            val width = maxWidth.coerceAtLeast(44.dp * 8 * density.fontScale.coerceAtLeast(1f))
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                MotionTabs(query.weekday ?: 0, listOf("全", "一", "二", "三", "四", "五", "六", "日"),
                    onSelect = { onConfigure(query.copy(provider = AnimeDiscoveryProvider.BANGUMI, calendar = true,
                        weekday = it.takeIf { day -> day > 0 }, year = null, season = null, tag = "")) },
                    modifier = Modifier.width(width), controlHeight = 44.dp, treatment = MotionTabTreatment.Underline)
            }
        }
        return
    }
    val filterSummary = listOfNotNull(
        query.year?.toString().takeIf { tab == AnimeDiscoveryTab.RANKING },
        query.season?.let(::animeSeasonLabel).takeIf { tab == AnimeDiscoveryTab.RANKING },
        query.tag.takeIf(String::isNotBlank)?.let { animeGenreLabels[it] ?: it },
    ).joinToString(" · ")
    val conditionSummary = listOfNotNull(
        animeDiscoveryOrderLabel(query.order).takeIf { tab == AnimeDiscoveryTab.SEASON },
        filterSummary.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.widthIn(max = 128.dp)) {
            MotionTextButton(onClick = { providerMenu = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(animeDiscoveryProviderLabel(query.provider), Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            CompactGlassPopover(providerMenu, backdrop, onDismiss = { providerMenu = false }, alignToStart = true) {
                AnimeDiscoveryProvider.entries.forEach { provider ->
                    CompactMenuAction(if (query.provider == provider) Icons.Outlined.Check else Icons.Outlined.Public,
                        animeDiscoveryProviderLabel(provider), onClick = {
                            if (provider != query.provider) onConfigure(query.copy(provider = provider,
                                order = AnimeDiscoveryOrder.POPULARITY, tag = ""))
                            providerMenu = false
                        })
                }
                AnimeQuietLabel("切换资料源会重置排序与题材", Modifier.padding(12.dp))
            }
        }
        Box(Modifier.weight(1f)) {
            MotionTextButton(onClick = { if (tab == AnimeDiscoveryTab.SEASON) seasonMenu = true else orderMenu = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (tab == AnimeDiscoveryTab.SEASON) listOfNotNull(query.year?.toString(), query.season?.let(::animeSeasonLabel))
                        .joinToString(" ").ifBlank { "选择季度" } else animeDiscoveryOrderLabel(query.order),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conditionSummary.isNotBlank()) Text(conditionSummary, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            CompactGlassPopover(orderMenu, backdrop, onDismiss = { orderMenu = false }) {
                animeDiscoveryOrders(query.provider).forEach { order ->
                    CompactMenuAction(if (order == query.order) Icons.Outlined.Check else Icons.Outlined.Sort,
                        animeDiscoveryOrderLabel(order), onClick = { onConfigure(query.copy(order = order)); orderMenu = false })
                }
            }
            CompactGlassPopover(seasonMenu, backdrop, onDismiss = { seasonMenu = false },
                onPresenceChange = { present ->
                    if (!present && filtersAfterSeason) { filtersAfterSeason = false; if (active) filters = true }
                }) {
                listOf(false to "本季新番", true to "下季期待").forEach { (next, label) ->
                    CompactMenuAction(Icons.Outlined.CalendarMonth, label, onClick = {
                        val today = localToday()
                        val index = (today.monthNumber - 1) / 3 + if (next) 1 else 0
                        onConfigure(query.copy(year = today.year + index / 4, season = AnimeSeason.entries[index % 4]))
                        seasonMenu = false
                    })
                }
                CompactMenuAction(Icons.Outlined.Tune, "年份与季度", onClick = { filtersAfterSeason = true; seasonMenu = false })
            }
        }
        MotionIconButton(onClick = { filters = true }, modifier = Modifier.size(44.dp),
            tooltip = if (filterSummary.isBlank()) "年份、季度与题材" else "筛选：$filterSummary") {
            Icon(Icons.Outlined.FilterList, "年份、季度与题材", Modifier.size(20.dp),
                tint = if (filterSummary.isNotBlank())
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    AnimeDiscoveryFilterPanel(filters, tab, query, backdrop, onDismiss = { filters = false }, onConfigure = onConfigure)
}

private fun animeDiscoveryOrders(provider: AnimeDiscoveryProvider): List<AnimeDiscoveryOrder> = when (provider) {
    AnimeDiscoveryProvider.BANGUMI -> listOf(AnimeDiscoveryOrder.RANK, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
    AnimeDiscoveryProvider.ANILIST -> listOf(AnimeDiscoveryOrder.TRENDING, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
}

private val animeGenreLabels = linkedMapOf("Action" to "动作", "Adventure" to "冒险", "Comedy" to "喜剧",
    "Drama" to "剧情", "Fantasy" to "奇幻", "Horror" to "恐怖", "Mahou Shoujo" to "魔法少女",
    "Mecha" to "机战", "Music" to "音乐", "Mystery" to "悬疑", "Psychological" to "心理", "Romance" to "恋爱",
    "Sci-Fi" to "科幻", "Slice of Life" to "日常", "Sports" to "运动", "Supernatural" to "超自然", "Thriller" to "惊悚")

@Composable
private fun AnimeDiscoveryFilterPanel(visible: Boolean, tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery,
    backdrop: HazeState, onDismiss: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    var year by remember { mutableStateOf("") }
    var season by remember { mutableStateOf<AnimeSeason?>(null) }
    var tag by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(query.order) }
    var error by remember { mutableStateOf<String?>(null) }
    var genreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) { year = query.year?.toString().orEmpty(); season = query.season; tag = query.tag; order = query.order; error = null }
    }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 480.dp,
        onClosed = { genreMenu = false }) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimeSectionTitle("筛选番剧", animeDiscoveryProviderLabel(query.provider), action = {
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                })
                AnimeTextField(year, { year = it; error = null }, "年份，留空不限")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val availableWidth = maxWidth
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        MotionTabs(season?.ordinal?.plus(1) ?: 0, listOf("不限", "冬季", "春季", "夏季", "秋季"),
                            onSelect = { season = AnimeSeason.entries.getOrNull(it - 1); error = null },
                            modifier = Modifier.width(availableWidth.coerceAtLeast(300.dp)), controlHeight = 44.dp,
                            treatment = MotionTabTreatment.Soft)
                    }
                }
                if (query.provider == AnimeDiscoveryProvider.BANGUMI) AnimeTextField(tag, { tag = it }, "题材标签，留空不限")
                else Box {
                    MotionTextButton(onClick = { genreMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Text(animeGenreLabels[tag] ?: "全部题材")
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                    CompactGlassPopover(genreMenu, backdrop, onDismiss = { genreMenu = false }) {
                        CompactMenuAction(if (tag.isBlank()) Icons.Outlined.Check else Icons.Outlined.Category, "全部题材",
                            onClick = { tag = ""; genreMenu = false })
                        animeGenreLabels.forEach { (value, label) ->
                            CompactMenuAction(if (tag == value) Icons.Outlined.Check else Icons.Outlined.Category, label,
                                onClick = { tag = value; genreMenu = false })
                        }
                    }
                }
                if (tab == AnimeDiscoveryTab.SEASON) {
                    val orders = animeDiscoveryOrders(query.provider)
                    MotionTabs(orders.indexOf(order).coerceAtLeast(0), orders.map(::animeDiscoveryOrderLabel),
                        onSelect = { order = orders[it] }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
                        treatment = MotionTabTreatment.Soft)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotionTextButton(onClick = { year = ""; season = null; tag = ""; error = null },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("清空") }
                    MotionButton(onClick = {
                        val parsed = year.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                        if (year.isNotBlank() && (parsed == null || parsed !in 1..9998)) error = "请输入有效年份。"
                        else if (season != null && parsed == null) error = "选择季度时请填写年份。"
                        else { onConfigure(query.copy(year = parsed, season = season, tag = tag.trim(), order = order)); onDismiss() }
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("应用") }
                }
            }
        }
    }
}
