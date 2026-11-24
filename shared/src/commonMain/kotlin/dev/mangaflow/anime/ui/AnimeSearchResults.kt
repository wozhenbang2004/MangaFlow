package dev.mangaflow.anime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeSearchGroup
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.anime.canLoadMoreSearch
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

internal val AnimeUnifiedSearchState.canPageSearch: Boolean
    get() = index.canLoadMore || sources.any { it.canLoadMoreSearch }

internal val AnimeUnifiedSearchState.searchLoadingFirst: Boolean
    get() = index.loading && index.page == 0 || sources.any { it.loading }

internal val AnimeUnifiedSearchState.searchLoadingMore: Boolean
    get() = index.loading && index.page > 0 || sources.any { it.loadingMore }

/** Local hints are submitted as plain queries. No source is queried or selected by typing a hint. */
@Composable
internal fun AnimeSearchDraft(
    term: String, recents: List<String>, discovery: List<AnimeDiscoveryItem>, matchingReference: Boolean,
    active: Boolean, onSubmit: (String) -> Unit, onClear: () -> Unit,
) {
    val suggestions = remember(term, recents, discovery, matchingReference) {
        if (matchingReference) emptyList() else {
            val seen = if (term.isEmpty()) recents.mapTo(mutableSetOf()) { it.lowercase() } else emptySet()
            val recent = if (term.isEmpty()) emptySequence() else recents.asSequence().filter { it.contains(term, true) }
            val visible = discovery.asSequence().take(80).filter { item ->
                term.isEmpty() || item.title.contains(term, true) || item.aliases.any { it.contains(term, true) }
            }.map { it.title }
            (recent + visible).filter { it.isNotBlank() && !it.equals(term, true) && it.lowercase() !in seen }
                .distinctBy { it.lowercase() }.take(if (term.isEmpty()) 5 else 4).toList()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (term.isNotEmpty()) {
            MotionTextButton(onClick = { onSubmit(term) }, enabled = active,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f),
                    contentColor = MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索「$term」", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
            }
        } else if (recents.isEmpty() || matchingReference) {
            AnimeQuietLabel(if (matchingReference) "输入作品名，在资料目录中核对。" else "输入番剧名或别名。",
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        } else {
            AnimeSearchTerms("最近搜过", recents, active, onSubmit, onClear)
        }
        if (suggestions.isNotEmpty()) AnimeSearchTerms(
            if (term.isEmpty()) "从发现页找番" else "相关搜索", suggestions, active, onSubmit)
    }
}

@Composable
private fun AnimeSearchTerms(
    title: String, terms: List<String>, active: Boolean, onSubmit: (String) -> Unit, onClear: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (onClear == null) 26.dp else 44.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClear != null) MotionIconButton(onClick = onClear, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "清除番剧搜索记录") {
                Icon(Icons.Outlined.DeleteOutline, "清除番剧搜索记录", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyRow(Modifier.fillMaxWidth(), userScrollEnabled = active,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(terms, key = { it }) { term ->
                MotionTextButton(onClick = { onSubmit(term) }, enabled = active,
                    modifier = Modifier.widthIn(max = 210.dp).heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f),
                        contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(term, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState, controller: AnimeController, matchingReference: Boolean, active: Boolean,
    onOpenGroup: (AnimeSearchGroup) -> Unit, onManage: (AnimeSubject) -> Unit,
    onShowSources: () -> Unit, pull: BottomPullLoadState,
) {
    val groups = search.groups
    val providerNames = search.sources.associate { it.providerId to it.name }
    val failureCount = search.sources.count { it.error != null } + if (search.index.error == null) 0 else 1
    val looking = search.grouping || search.searchLoadingFirst
    val loading = looking || search.searchLoadingMore
    val waiting = search.index.page == 0 && search.index.error == null ||
        search.sources.any { it.page == 0 && it.error == null }
    item(key = "search-overview", contentType = "anime-search-overview") {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (loading) MotionBusyIndicator(Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant, 1.5.dp)
            Text(when {
                groups.isEmpty() && loading && failureCount > 0 -> "查找中 · 有来源未完成"
                groups.isEmpty() && loading -> "正在查找番剧"
                groups.isNotEmpty() && failureCount > 0 -> "${groups.size} 部 · 有来源未完成"
                groups.isNotEmpty() && looking -> "${groups.size} 部 · 继续查找中"
                groups.isNotEmpty() && search.searchLoadingMore -> "${groups.size} 部 · 加载更多"
                groups.isNotEmpty() -> "${groups.size} 部番剧"
                failureCount > 0 || waiting -> "搜索未完成"
                else -> "搜索结果"
            }, Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            MotionTextButton(onClick = onShowSources, enabled = active,
                modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 7.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Outlined.Public, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("搜索来源", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (groups.isEmpty() && !loading) item(key = "search-status") {
        when {
            failureCount > 0 -> AnimeMessage("暂未取得匹配结果", "有来源未能完成搜索。", action = "重试",
                icon = Icons.Outlined.Search, onAction = controller::retrySearch)
            waiting -> AnimeMessage("暂未取得匹配结果", "还有来源没有返回。", icon = Icons.Outlined.Search)
            search.canPageSearch -> AnimeMessage("当前结果没有匹配", icon = Icons.Outlined.Search)
            else -> AnimeMessage(if (matchingReference) "未找到对应番剧" else "没有匹配的番剧",
                if (matchingReference) "已保留所选来源。试试原名或别名。" else "试试原名、别名或更短的关键词。",
                icon = Icons.Outlined.Search)
        }
    }
    items(groups, key = { it.key }, contentType = { "anime-search-result" }) { group ->
        val canonical = group.subject
        val subject = animeSearchDisplaySubject(group, controller) ?: return@items
        val manage = if (!active || matchingReference) null else canonical?.let { { onManage(it) } }
        val ratings: (@Composable () -> Unit)? = if (matchingReference || canonical == null) null else
            ({ AnimeSubjectRatings(canonical, enabled = active) })
        val original = canonical?.originalTitle?.takeIf { it.isNotBlank() && !it.equals(subject.title, ignoreCase = true) }
        val facts = remember(subject.year, subject.format, subject.episodeCount, canonical?.tags) {
            (listOfNotNull(subject.year?.toString(), when (subject.format) {
                AnimeDiscoveryFormat.MOVIE -> "电影"
                AnimeDiscoveryFormat.SPECIAL -> "特别篇"
                AnimeDiscoveryFormat.SHORT -> "短片"
                else -> null
            }, subject.episodeCount?.takeIf { it > 0 && subject.format != AnimeDiscoveryFormat.MOVIE }?.let { "$it 集" }) +
                canonical?.tags.orEmpty().asSequence().map(String::trim).filter(String::isNotBlank).distinct().take(2).toList())
                .joinToString(" · ")
        }
        val sources = remember(group.sources, providerNames) {
            group.sources.mapNotNull { hit -> providerNames[hit.sourceId]?.let { hit.sourceId to it } }.distinctBy { it.first }
        }
        DesktopContextMenuArea(manage?.let { listOf(DesktopMenuAction("番剧操作", onClick = it)) }.orEmpty()) {
            AnimeSearchWorkRow(subject, facts, original, sources, active, { onOpenGroup(group) },
                if (matchingReference) "选择这部番剧" else "查看番剧详情", manage, ratings)
        }
    }
    if (search.canPageSearch || search.searchLoadingMore) item(key = "search-more") {
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            if (search.searchLoadingMore) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MotionBusyIndicator(Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant, 1.5.dp)
                AnimeQuietLabel("正在加载更多")
            } else MotionTextButton(onClick = controller::loadMoreCatalog, enabled = active && search.canPageSearch,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(if (groups.isEmpty()) "继续查找" else "更多结果")
            }
            if (pull.progress > 0f && !search.searchLoadingMore) BottomPullLoadIndicator(pull,
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(26.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeSearchWorkRow(
    subject: AnimeSubject, facts: String, original: String?, sources: List<Pair<String, String>>,
    active: Boolean, onOpen: () -> Unit, openLabel: String, onLongClick: (() -> Unit)? = null,
    ratings: (@Composable () -> Unit)? = null,
) {
    val interaction = remember(subject.id) { MutableInteractionSource() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .motionPress(interaction, enabled = active, pressedScale = .985f)
        .combinedClickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = openLabel, onClick = onOpen, onLongClick = onLongClick,
            onLongClickLabel = if (onLongClick == null) null else "番剧操作")
        .padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        AnimePoster(subject, Modifier.size(44.dp, 64.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(subject.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (original != null) Text(original, style = MaterialTheme.typography.labelSmall,
                color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.labelSmall,
                color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ratings?.invoke()
            if (sources.isNotEmpty()) Row(Modifier.semantics { contentDescription = "收录来源：" + sources.joinToString("、") { it.second } },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sources.take(3).forEach { (id, name) -> AnimeSourceMark(id, name, size = 13.dp) }
                if (sources.size == 1) AnimeQuietLabel(sources.single().second)
                else if (sources.size > 3) AnimeQuietLabel("+${sources.size - 3}")
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = muted)
    }
}

/** Keep whichever artwork the visible work actually owns, including the native request context. */
private fun animeSearchDisplaySubject(group: AnimeSearchGroup, controller: AnimeController): AnimeSubject? {
    val subject = group.subject
    if (subject != null && subject.coverUrl.isNotBlank()) return subject
    val cover = group.sources.firstOrNull { it.coverUrl.isNotBlank() }
    if (subject != null) return if (cover == null) subject else subject.copy(
        coverUrl = cover.coverUrl, externalIds = subject.externalIds + ("native-source" to cover.sourceId))
    val primary = group.sources.firstOrNull() ?: return null
    return controller.sourceSubject(primary).copy(
        coverUrl = cover?.coverUrl.orEmpty(),
        episodeCount = group.sources.mapNotNull { it.episodeCount?.takeIf { count -> count > 0 } }.distinct().singleOrNull(),
        externalIds = primary.externalIds + ("native-source" to (cover?.sourceId ?: primary.sourceId)),
    )
}

@Composable
internal fun AnimeSearchProgressSheet(
    search: AnimeUnifiedSearchState, controller: AnimeController, backdrop: HazeState, onDismiss: () -> Unit,
) {
    fun currentSearch(): AnimeUnifiedSearchState? = controller.searchState.value.takeIf { current ->
        current.query == search.query && current.indexOnly == search.indexOnly && current.catalogProvider == search.catalogProvider
    }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop) { dismiss ->
        AnimeSearchSheetHeading("搜索来源", "「${search.query}」", dismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            item(key = "catalogue-heading") { AnimeQuietLabel("番剧资料", Modifier.padding(top = 5.dp)) }
            item(key = "catalogue-progress") {
                val index = search.index
                AnimeSearchSourceProgress(animeDiscoverySourceId(search.catalogProvider), animeDiscoveryProviderLabel(search.catalogProvider),
                    index.items.size, index.page, index.loading, queued = false, failed = index.error != null,
                    retry = {
                        // A synchronous repeat of the same failure may be conflated. Match the
                        // shown value (including page and error), not its allocation identity.
                        if (currentSearch()?.index == index && index.error != null && !index.loading)
                            controller.retrySearchIndex()
                    })
            }
            if (search.sources.isNotEmpty()) item(key = "source-heading") {
                AnimeQuietLabel("播放片源", Modifier.padding(top = 9.dp))
            }
            items(search.sources, key = { "source-progress:${it.providerId}" }) { source ->
                AnimeSearchSourceProgress(source.providerId, source.name, source.items.size, source.page,
                    source.loading || source.loadingMore, source.queued, source.error != null,
                    retry = {
                        if (source.error != null && !source.loading && !source.loadingMore &&
                            currentSearch()?.sources?.any { it == source } == true)
                            controller.retrySearchProvider(source.providerId)
                    })
            }
        }
    }
}

@Composable
private fun AnimeSearchSourceProgress(
    id: String, name: String, count: Int, page: Int, loading: Boolean, queued: Boolean, failed: Boolean, retry: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimeSourceMark(id, name, size = 17.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = when {
                queued -> "等待查询"
                loading -> "正在查找"
                failed -> "未能查完"
                page == 0 -> "尚未返回"
                count == 0 -> "暂无匹配"
                else -> "已返回"
            }
            AnimeQuietLabel(state + if (count > 0) " · $count 条" else "")
        }
        if (failed && !loading) MotionTextButton(onClick = retry, modifier = Modifier.heightIn(min = 44.dp)
            .semantics { contentDescription = "重试$name" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("重试")
        }
    }
}

@Composable
private fun AnimeSearchSheetHeading(title: String, subtitle: String?, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭$title") {
            Icon(Icons.Outlined.Close, "关闭$title", Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun AnimeSearchVersionSheet(
    versions: List<AnimeSourceTitle>, controller: AnimeController, sourceName: (String) -> String,
    backdrop: HazeState, onDismiss: () -> Unit, onChoose: (AnimeSourceTitle) -> Unit,
) {
    var selected by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    MotionBottomSheet(onDismissRequest = {
        val chosen = selected
        selected = null
        onDismiss()
        chosen?.let(onChoose)
    }, containerColor = MaterialTheme.colorScheme.background, backdrop = backdrop) { dismiss ->
        AnimeSearchSheetHeading("选择要打开的版本", null, dismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            if (versions.isEmpty()) item(key = "empty") { AnimeMessage("可用版本已更新，返回搜索重试") }
            items(versions, key = { controller.sourceSubject(it).id }) { version ->
                val subtitle = listOfNotNull(version.year?.toString(),
                    version.episodeCount?.takeIf { it > 0 }?.let { "$it 集" },
                    version.updateText.takeIf(String::isNotBlank)).joinToString(" · ")
                AnimeSearchWorkRow(controller.sourceSubject(version), subtitle, null,
                    listOf(version.sourceId to sourceName(version.sourceId)), true,
                    onOpen = { selected = version; dismiss() }, openLabel = "打开此版本")
            }
        }
    }
}
