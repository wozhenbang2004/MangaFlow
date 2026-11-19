package dev.mangaflow.anime.ui

import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.AnimeSourceHomeFeed
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.localToday

private const val FEATURED_PREVIEW = 6
private const val FEATURED_CANDIDATES_PER_SOURCE = 24
private const val LATEST_PREVIEW_LIMIT = 36

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, onOpenItem: (AnimeDiscoveryItem) -> Unit,
    onChooseReference: (AnimeSourceTitle, Boolean) -> Unit, onOpenSourceGroup: (AnimeSearchGroup) -> Unit,
    onBrowseSource: (String?) -> Unit,
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
            AnimeDiscoveryTab.HOME -> AnimeDiscoveryHome(state, controller, backdrop, scrolls, contentBottomPadding,
                onOpen = onOpenItem, onScores = ::openScores, onChooseReference = onChooseReference,
                onOpenSourceGroup = onOpenSourceGroup, onBrowseSource = onBrowseSource, onResume = onResume,
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
private fun AnimeDiscoveryHome(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, contentBottomPadding: Dp, onOpen: (AnimeDiscoveryItem) -> Unit,
    onScores: (AnimeDiscoveryItem) -> Unit,
    onChooseReference: (AnimeSourceTitle, Boolean) -> Unit, onOpenSourceGroup: (AnimeSearchGroup) -> Unit,
    onBrowseSource: (String?) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit, active: Boolean) {
    val homeFeeds by controller.sourceBrowse.homeFeeds.collectAsStateWithLifecycle()
    val homeScroll = scrolls.grid("home")
    val availableFeeds = remember(homeFeeds) { homeFeeds.values.toList() }
    val usable = remember(availableFeeds) {
        availableFeeds.filter { feed -> feed.sections.any { it.items.isNotEmpty() } }
    }
    val automatic = usable.firstOrNull { it.source.id == scrolls.automaticHomeSourceId } ?: usable.firstOrNull()
    val explicit = scrolls.homeSourceId?.let(homeFeeds::get)
    val selectedFeed = explicit ?: automatic ?: availableFeeds.firstOrNull()
    SideEffect { scrolls.automaticHomeSourceId = automatic?.source?.id }
    LaunchedEffect(active, explicit?.source) {
        if (active && explicit != null) controller.sourceBrowse.prioritizeHomeSource(explicit.source.id)
    }
    var sourceMenuPresent by remember { mutableStateOf(false) }
    val contentActive = active && !sourceMenuPresent
    val sourceLabels = remember(availableFeeds) { availableFeeds.associate { it.source.id to it.source.name } }
    val featuredRows = remember(availableFeeds) {
        availableFeeds.filter { it.source.videoSource }.associate { feed ->
            feed.source.id to feed.sections.asSequence().flatMap { it.items.asSequence() }
                .distinctBy { it.id to it.url }.take(FEATURED_CANDIDATES_PER_SOURCE).toList()
        }.filterValues { it.isNotEmpty() }
    }
    val (featuredOrder, featuredGroups) = remember(featuredRows) {
        // Refresh timestamps do not reorder the gallery. Add newly arrived sources to the retained order.
        val order = scrolls.featuredSourceOrder.filter(featuredRows::containsKey) +
            (featuredRows.keys - scrolls.featuredSourceOrder.toSet())
        val groups = groupAnimeTitles(interleaveAnimeHomeRows(order.map(featuredRows::getValue)), scrolls.featuredSourceGroups)
        order to groups
    }
    SideEffect {
        scrolls.featuredSourceOrder = featuredOrder
        scrolls.featuredSourceGroups = featuredGroups
    }
    val nativeFeatured = remember(featuredGroups, sourceLabels) {
        val entries = featuredGroups.mapNotNull { group ->
            val artwork = group.sources.firstOrNull { it.coverUrl.isNotBlank() } ?: return@mapNotNull null
            val title = group.sources.first()
            val sourceCount = group.sources.distinctBy { it.sourceId }.size
            AnimeFeaturedEntry(
                key = group.key, title = title.title, coverUrl = artwork.coverUrl, sourceId = artwork.sourceId,
                sourceLabel = if (sourceCount > 1) "$sourceCount 个来源" else sourceLabels[title.sourceId].orEmpty(),
                caption = if (sourceCount == 1) title.updateText.ifBlank { title.year?.toString().orEmpty() } else "",
                sourceGroup = group,
            )
        }
        val preview = entries.take(FEATURED_PREVIEW)
        val selected = entries.firstOrNull { it.key == scrolls.featuredKey }
        if (selected != null && preview.none { it.key == selected.key }) preview.dropLast(1) + selected else preview
    }
    val seasonalItems = state.discoveryFeeds["home:season"]?.items.orEmpty()
    val featured = remember(nativeFeatured, seasonalItems) {
        nativeFeatured.ifEmpty {
            seasonalItems.asSequence().filter { it.coverUrl.isNotBlank() }.distinctBy { it.key }.take(FEATURED_PREVIEW).map { item ->
                AnimeFeaturedEntry("season:" + item.key, item.title, item.coverUrl,
                    animeDiscoverySourceId(AnimeDiscoveryProvider.BANGUMI), "Bangumi · 本季人气",
                    item.year?.toString().orEmpty(), discoveryItem = item)
            }.toList()
        }
    }
    val updateFeeds = remember(availableFeeds) {
        availableFeeds.filter { feed -> feed.source.videoSource &&
            ("latest" in feed.source.capabilities || feed.sections.any { it.key == "latest" }) }
    }
    val updateRows = remember(updateFeeds) {
        updateFeeds.map { feed ->
            feed.sections.asSequence().filter { it.key == "latest" }.flatMap { it.items.asSequence() }
                .distinctBy { it.id to it.url }.take(LATEST_PREVIEW_LIMIT).toList()
        }
    }
    val updatedGroups = remember(updateRows) {
        groupAnimeTitles(interleaveAnimeHomeRows(updateRows), scrolls.updatedSourceGroups)
    }
    SideEffect { scrolls.updatedSourceGroups = updatedGroups }
    val updates = remember(updatedGroups) { updatedGroups.take(LATEST_PREVIEW_LIMIT) }
    val updateSourceCount = remember(updates) {
        updates.flatMap { group -> group.sources.map { it.sourceId } }.toSet().size
    }
    val genres = state.discoveryFeeds[animeDiscoveryFeedKey(AnimeDiscoveryTab.GENRES)]?.query
        ?: AnimeDiscoveryQuery()
    val galleryInView by remember(homeScroll) {
        derivedStateOf { homeScroll.layoutInfo.visibleItemsInfo.any { it.key == "featured" } }
    }
    LaunchedEffect(featured.isNotEmpty(), contentActive) {
        if (featured.isNotEmpty() && contentActive && !scrolls.placedHomeFeature) {
            scrolls.placedHomeFeature = true
            // Lazy grids retain the leading key when a hero appears above the first section.
            // Expose the hero when the reader is still at the start; a scroll owns its anchor.
            if (state.resumeEntry == null && !homeScroll.isScrollInProgress && homeScroll.firstVisibleItemScrollOffset == 0 &&
                homeScroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key in
                setOf("latest-heading", "section:home:today", "section:home:season", "genre-shortcuts"))
                homeScroll.requestScrollToItem(0)
        }
    }
    val motion = LocalMotionEnabled.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
        val previewCount = if (columns == 1) 4 else columns * 2
        val updatePreviewCount = if (columns == 1) 3 else columns
        LazyVerticalGrid(GridCells.Fixed(columns), state = homeScroll, modifier = Modifier.fillMaxSize(),
            userScrollEnabled = contentActive,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.resumeEntry?.let { entry ->
                item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeContinueWatching(entry,
                        onResume = { if (contentActive) onResume(entry) },
                        onDismiss = { if (contentActive) controller.dismissResume(entry.subject.id) },
                        modifier = Modifier.motionInputGate(contentActive))
                }
            }
            if (featured.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                AnimeFeaturedCarousel(featured, active = contentActive, visible = contentActive && galleryInView,
                    selectedKey = scrolls.featuredKey, onSelected = { scrolls.featuredKey = it },
                    onOpen = { entry ->
                        if (contentActive) {
                            entry.sourceGroup?.let(onOpenSourceGroup)
                                ?: entry.discoveryItem?.let(onOpen)
                        }
                    })
            }
            if (updateFeeds.isNotEmpty()) {
                item(key = "latest-heading", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoverySectionHeading("片源最近更新",
                        if (updateSourceCount > 1) "$updateSourceCount 个来源" else "来源目录",
                        refreshing = updateFeeds.any { it.loading } && updates.isNotEmpty(),
                        expanded = scrolls.expandedUpdates,
                        moreLabel = if (scrolls.expandedUpdates) "收起片源更新" else "展开片源更新",
                        onMore = if (contentActive && updates.size > updatePreviewCount) ({
                            scrolls.expandedUpdates = !scrolls.expandedUpdates
                        }) else null)
                }
                if (updates.isEmpty()) item(key = "latest-state", span = { GridItemSpan(maxLineSpan) }) {
                    val pending = updateFeeds.any { it.loading || it.error == null && it.fetchedAt == null }
                    val failedFeeds = updateFeeds.filter { it.error != null }
                    AnimeDiscoveryInlineStatus(when {
                        pending -> "正在读取片源更新"
                        failedFeeds.isNotEmpty() -> "暂时无法读取片源更新"
                        else -> "片源暂无更新条目"
                    }, retry = if (contentActive && !pending && failedFeeds.isNotEmpty()) ({
                        controller.sourceBrowse.refreshHomeSources(failedFeeds.mapTo(mutableSetOf()) { it.source.id })
                    }) else null)
                }
                items(updates.take(if (scrolls.expandedUpdates) updates.size else updatePreviewCount),
                    key = { "latest:${it.key}" },
                    contentType = { "native-update" }) { update ->
                    val placement = if (motion) Modifier.animateItem(fadeInSpec = tween(MangaMotion.Quick),
                        fadeOutSpec = null, placementSpec = tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing))
                    else Modifier
                    AnimeSourceGroupDiscoveryCard(update, list = columns == 1,
                        sourceLabel = sourceLabels[update.sources.first().sourceId],
                        modifier = placement,
                        onOpen = { if (contentActive) onOpenSourceGroup(update) })
                }
            }
            val primarySections = state.discoverySections.filter { it.key == "home:today" || it.key == "home:season" }
            primarySections.forEach { section ->
                animeHomeMetadataSection(section, state.discoveryFeeds[section.key], columns, contentActive, onOpen, onScores,
                    onMore = { controller.openDiscoverySection(section.key) },
                    onRetry = { controller.retryDiscovery(section.key) })
            }
            item(key = "genre-shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                AnimeGenreShortcuts(genres, selected = null, active = contentActive, home = true,
                    onSelect = { controller.configureDiscovery(AnimeDiscoveryTab.GENRES, genres.copy(tag = it)) })
            }
            if (selectedFeed != null) {
                item(key = "home-source-selector", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeHomeSourceSelector(availableFeeds, selectedFeed, backdrop, active,
                        onSelect = { scrolls.homeSourceId = it },
                        onPresence = { sourceMenuPresent = it },
                        onRefresh = { controller.sourceBrowse.refreshHomeSource(selectedFeed.source.id) },
                        onBrowse = { onBrowseSource(selectedFeed.source.id) })
                }
                selectedFeed.let { feed ->
                    val source = feed.source
                    val prefix = "source-home:" + source.id + ":" + source.revision
                    val sections = feed.sections.withIndex().filter {
                        it.value.items.isNotEmpty() && (!source.videoSource || it.value.key != "latest")
                    }.take(1)
                    if (feed.error != null) item(key = "${prefix}:error", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoveryInlineStatus(feed.error, retry = {
                            if (contentActive) controller.sourceBrowse.refreshHomeSource(source.id)
                        })
                    }
                    if (feed.sections.none { it.items.isNotEmpty() } && feed.error == null) {
                        item(key = "${prefix}:state", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoveryInlineStatus(
                                when {
                                    feed.queued -> "等待读取来源内容"
                                    feed.loading || feed.fetchedAt == null -> "正在读取来源内容"
                                    else -> "这个来源暂时没有内容"
                                },
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
                        items(section.items.distinctBy { it.id }.take(if (expanded) Int.MAX_VALUE else previewCount),
                            key = { sectionKey + ":" + it.sourceId + ":" + it.id }) { title ->
                            AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = {
                                if (contentActive) onChooseReference(title, source.videoSource)
                            })
                        }
                    }
                }
            } else {
                item(key = "home-source-directory", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoverySectionHeading("片源目录", "", onMore = if (contentActive) ({
                        onBrowseSource(null)
                    }) else null, moreLabel = "浏览片源目录")
                }
            }
            state.discoverySections.filterNot { it in primarySections }.forEach { section ->
                animeHomeMetadataSection(section, state.discoveryFeeds[section.key], columns, contentActive, onOpen, onScores,
                    onMore = { controller.openDiscoverySection(section.key) },
                    onRetry = { controller.retryDiscovery(section.key) })
            }
        }
    }
}

/** Keeps the native order within each source. No cross-source update timestamp is inferred. */
private fun interleaveAnimeHomeRows(rows: List<List<AnimeSourceTitle>>): List<AnimeSourceTitle> = buildList {
    for (index in 0 until (rows.maxOfOrNull { it.size } ?: 0)) {
        for (row in rows) {
            row.getOrNull(index)?.let { add(it) }
        }
    }
}

private fun LazyGridScope.animeHomeMetadataSection(
    section: AnimeDiscoverySection, feed: AnimeDiscoveryFeed?, columns: Int, active: Boolean,
    onOpen: (AnimeDiscoveryItem) -> Unit, onScores: (AnimeDiscoveryItem) -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit,
) {
    item(key = "section:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
        val caption = if (section.query.combined) "" else animeDiscoveryProviderLabel(section.query.provider)
        AnimeDiscoverySectionHeading(section.title, caption,
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

/** Source choice discloses the existing feeds; it never enables a source or changes playback priority. */
@Composable
private fun AnimeHomeSourceSelector(feeds: List<AnimeSourceHomeFeed>, selected: AnimeSourceHomeFeed,
    backdrop: HazeState, active: Boolean, onSelect: (String) -> Unit, onPresence: (Boolean) -> Unit,
    onRefresh: () -> Unit, onBrowse: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    var pendingSource by remember { mutableStateOf<String?>(null) }
    var pendingRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) { expanded = false; pendingSource = null; pendingRefresh = false }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("片源目录", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val colors = MaterialTheme.colorScheme
        Box(Modifier.widthIn(max = 180.dp)) {
            MotionTextButton(onClick = { pendingSource = null; pendingRefresh = false; expanded = true },
                enabled = active && !present,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "切换片源目录，当前 ${selected.source.name}"
                },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface,
                    containerColor = colors.surfaceContainerLow),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(if (selected.source.videoSource) Icons.Outlined.Tv else Icons.Outlined.Explore,
                    null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(selected.source.name, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompactGlassPopover(expanded, backdrop,
                onDismiss = { expanded = false; pendingSource = null; pendingRefresh = false },
                onPresenceChange = { showing ->
                    present = showing
                    onPresence(showing)
                    if (!showing) {
                        val next = pendingSource
                        val refresh = pendingRefresh
                        pendingSource = null
                        pendingRefresh = false
                        if (active && next != null && feeds.any { it.source.id == next }) onSelect(next)
                        if (active && refresh) onRefresh()
                    }
                }) {
                AnimeQuietLabel("浏览来源", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                feeds.forEach { feed ->
                    val hasContent = feed.sections.any { it.items.isNotEmpty() }
                    val status = when {
                        feed.error != null -> if (hasContent) "更新未完成" else "暂不可用"
                        feed.queued -> "等待更新"
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
                CompactMenuAction(Icons.Outlined.Refresh, "刷新${selected.source.name}",
                    enabled = !selected.loading || selected.queued, onClick = { pendingRefresh = true; expanded = false })
            }
        }
        MotionIconButton(onClick = onBrowse, enabled = active && !present, modifier = Modifier.size(44.dp),
            tooltip = "浏览${selected.source.name}目录") {
            Icon(Icons.Outlined.ChevronRight, "浏览${selected.source.name}目录", Modifier.size(21.dp))
        }
    }
}

@Composable
private fun AnimeGenreShortcuts(query: AnimeDiscoveryQuery, selected: String?, active: Boolean,
    modifier: Modifier = Modifier, home: Boolean = false, onSelect: (String) -> Unit) {
    val options = when {
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
            else items(feed.items, key = { it.key }) { item ->
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
    MotionDialog(visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 420.dp, onClosed = onClosed) {
        LiquidGlassSurface(backdrop, Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
            role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp, softCard = true) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
                .semantics { paneTitle = "番剧来源评分" }) {
                AnimeSectionTitle("来源评分", item.title, action = {
                    MotionIconButton(onClick = onDismiss, enabled = visible, modifier = Modifier.size(44.dp),
                        tooltip = "关闭来源评分") {
                        Icon(Icons.Outlined.Close, "关闭来源评分", Modifier.size(19.dp))
                    }
                })
                item.sourceScores.forEach { score ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewSourceMark(animeDiscoveryScoreSourceId(score.provider), size = 18.dp)
                        Text(animeDiscoveryProviderLabel(score.provider), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(animeDiscoveryScoreText(score),
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
        query.tag.takeIf(String::isNotBlank)?.let { animeDiscoveryGenreLabels[it] ?: it }).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryControls(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery, backdrop: HazeState, active: Boolean,
    onOverlayPresence: (Boolean) -> Unit, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    if (tab == AnimeDiscoveryTab.CALENDAR) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            val density = LocalDensity.current
            val width = maxWidth.coerceAtLeast(44.dp * 8 * density.fontScale.coerceAtLeast(1f))
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                MotionTabs(query.weekday ?: 0, listOf("全", "一", "二", "三", "四", "五", "六", "日"),
                    onSelect = { if (active) onConfigure(query.copy(weekday = it.takeIf { day -> day > 0 })) },
                    modifier = Modifier.width(width), controlHeight = 44.dp, treatment = MotionTabTreatment.Underline,
                    tooltips = listOf("全周", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"))
            }
        }
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
                Text("筛选与排序")
            }
        }
        if (seasonal) {
            val today = localToday()
            val currentQuarter = (today.monthNumber - 1) / 3
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("本季", "下季").forEachIndexed { offset, label ->
                    val quarter = currentQuarter + offset
                    val year = today.year + quarter / AnimeSeason.entries.size
                    val season = AnimeSeason.entries[quarter % AnimeSeason.entries.size]
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

private val bangumiGenreShortcuts = linkedMapOf("剧场版" to "剧场版", "国产" to "国产",
    "热血" to "热血", "奇幻" to "奇幻", "恋爱" to "恋爱", "科幻" to "科幻", "日常" to "日常",
    "悬疑" to "悬疑", "运动" to "运动")
private val anilistHomeGenres = animeDiscoveryGenreLabels.filterKeys {
    it in setOf("Action", "Fantasy", "Romance", "Sci-Fi", "Slice of Life")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryFilterPanel(visible: Boolean, tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery,
    backdrop: HazeState, rememberedOptions: (AnimeDiscoveryProvider) -> AnimeDiscoveryQuery?,
    onDismiss: () -> Unit, onClosed: () -> Unit, onConfigure: (AnimeDiscoveryQuery) -> Unit) {
    var provider by remember { mutableStateOf(query.provider.takeUnless { query.combined }) }
    var sourceOptionsVisible by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf(query.year?.toString().orEmpty()) }
    var season by remember { mutableStateOf(query.season) }
    var tag by remember { mutableStateOf(query.tag) }
    var order by remember { mutableStateOf(query.order) }
    var error by remember { mutableStateOf<String?>(null) }
    val draftOptions = remember { mutableMapOf<AnimeDiscoveryProvider?, Pair<AnimeDiscoveryOrder, String>>() }
    val colors = MaterialTheme.colorScheme
    val orders = animeDiscoveryOrders(provider)
    val today = localToday()
    fun usesAniListTags(source: AnimeDiscoveryProvider?, sorting: AnimeDiscoveryOrder) =
        source == AnimeDiscoveryProvider.ANILIST || source == null && sorting == AnimeDiscoveryOrder.TRENDING
    fun translateTag(value: String, aniList: Boolean): String = if (aniList) {
        value.takeIf(animeDiscoveryGenreLabels::containsKey)
            ?: animeDiscoveryGenreLabels.entries.firstOrNull { it.value == value }?.key.orEmpty()
    } else animeDiscoveryGenreLabels[value] ?: value

    fun selectProvider(next: AnimeDiscoveryProvider?) {
        if (next == provider) return
        draftOptions[provider] = order to tag
        val previous = draftOptions[next] ?: next?.let(rememberedOptions)?.takeUnless { it.combined }?.let { it.order to it.tag }
        val nextOrders = animeDiscoveryOrders(next)
        order = order.takeIf { it in nextOrders } ?: previous?.first?.takeIf { it in nextOrders }
            ?: AnimeDiscoveryOrder.POPULARITY
        val aniList = usesAniListTags(next, order)
        tag = translateTag(tag, aniList).ifBlank { previous?.second?.let { translateTag(it, aniList) }.orEmpty() }
        provider = next
    }

    MotionDialog(visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 500.dp, onClosed = onClosed) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().height(minOf(maxHeight, 580.dp)),
                role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp, softCard = true) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { paneTitle = "动漫筛选与排序" }) {
                    AnimeSectionTitle("筛选与排序", when (tab) {
                        AnimeDiscoveryTab.SEASON -> "新番"
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
                                        tag = translateTag(tag, usesAniListTags(provider, option))
                                        order = option
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AnimeQuietLabel("首播年份与季度")
                            AnimeTextField(year, { value ->
                                if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                    year = value
                                    if (value.isEmpty()) season = null
                                    error = null
                                }
                            }, "年份，留空为全部", enabled = visible)
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
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AnimeQuietLabel("题材")
                            if (!usesAniListTags(provider, order)) {
                                AnimeTextField(tag, { tag = it }, "题材标签，留空为全部", enabled = visible)
                                if (provider == null && tag.isNotBlank() &&
                                    order in setOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE) &&
                                    animeDiscoveryPeerQuery(query.copy(provider = AnimeDiscoveryProvider.BANGUMI,
                                        order = order, tag = tag, combined = true)) == null) {
                                    AnimeQuietLabel("此标签由 Bangumi 检索")
                                }
                            } else FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                AnimeDiscoveryChoice("全部", tag.isBlank(), visible) { tag = "" }
                                animeDiscoveryGenreLabels.forEach { (value, label) ->
                                    AnimeDiscoveryChoice(label, tag == value, visible) { tag = value }
                                }
                            }
                        }
                        val scopeLabel = provider?.let(::animeDiscoveryProviderLabel) ?: "综合资料"
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            CompactMenuAction(if (sourceOptionsVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                "资料范围 · $scopeLabel", enabled = visible,
                                onClick = { sourceOptionsVisible = !sourceOptionsVisible })
                            if (sourceOptionsVisible) {
                                val scopes = listOf<AnimeDiscoveryProvider?>(null) + AnimeDiscoveryProvider.entries
                                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    scopes.forEach { scope ->
                                        AnimeDiscoveryChoice(scope?.let(::animeDiscoveryProviderLabel) ?: "综合资料",
                                            provider == scope, visible) { selectProvider(scope) }
                                    }
                                }
                            }
                        }
                    }

                    if (error != null) Text(error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = {
                            year = ""; season = null; tag = ""; order = AnimeDiscoveryOrder.POPULARITY; error = null
                        }, enabled = visible, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("清除条件") }
                        MotionButton(onClick = {
                            val parsed = year.takeIf(String::isNotEmpty)?.toIntOrNull()
                            if (year.isNotBlank() && (parsed == null || parsed !in 1..9998)) {
                                error = "请输入 1 到 9998 之间的年份"
                            } else {
                                onConfigure(query.copy(provider = provider ?: AnimeDiscoveryProvider.BANGUMI,
                                    year = parsed, season = season, tag = tag.trim(), order = order,
                                    combined = provider == null))
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
