package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeSearchGroup
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.anime.canLoadMoreSearch
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

internal val AnimeUnifiedSearchState.canPageSearch: Boolean
    get() = index.canLoadMore || sources.any { it.canLoadMoreSearch }

internal val AnimeUnifiedSearchState.searchLoadingFirst: Boolean
    get() = index.loading && index.page == 0 || sources.any { it.loading }

internal val AnimeUnifiedSearchState.searchLoadingMore: Boolean
    get() = index.loading && index.page > 0 || sources.any { it.loadingMore }

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState,
    controller: AnimeController,
    matchingReference: Boolean,
    onOpenGroup: (AnimeSearchGroup) -> Unit,
    onManage: (AnimeSubject) -> Unit,
    pull: BottomPullLoadState,
) {
    val loading = search.grouping || search.searchLoadingFirst || search.searchLoadingMore
    val hasFailure = search.index.error != null || search.sources.any { it.error != null }
    val pageFailure = search.index.error != null && search.index.page > 0 ||
        search.sources.any { it.error != null && it.page > 0 }
    val groups = search.groups
    if (groups.isEmpty()) item(key = "search-status") {
        when {
            loading -> AnimeDiscoveryLoading("正在查找番剧")
            hasFailure && !search.canPageSearch -> AnimeMessage("暂时未能取得搜索结果", action = "重试",
                onAction = controller::retrySearch)
            search.canPageSearch -> AnimeMessage("本页暂无匹配")
            else -> AnimeMessage(if (matchingReference) "未找到对应的番剧" else "没有找到匹配的番剧",
                if (matchingReference) "已保留所选来源。试试原名或别名。" else "试试原名、别名或更短的关键词。")
        }
    }
    items(groups, key = { it.key }) { group ->
        val canonical = group.subject
        val subject = animeSearchDisplaySubject(group, controller) ?: return@items
        val manage = if (matchingReference) null else canonical?.let { { onManage(it) } }
        val ratings: (@Composable () -> Unit)? = if (matchingReference || canonical == null) null else
            ({ AnimeSubjectRatings(canonical) })
        DesktopContextMenuArea(manage?.let { listOf(DesktopMenuAction("番剧操作", onClick = it)) }.orEmpty()) {
            val original = canonical?.originalTitle?.takeIf { it.isNotBlank() && !it.equals(subject.title, ignoreCase = true) }
            val metadata = listOfNotNull(subject.year?.toString(),
                "电影".takeIf { subject.format == AnimeDiscoveryFormat.MOVIE }
                    ?: subject.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ")
            AnimeSubjectRow(subject, metadata, onOpen = { onOpenGroup(group) },
                onOpenLabel = if (matchingReference) "选择这部番剧" else "查看详情",
                onLongClick = manage,
                ratings = ratings,
                supporting = original?.let { title ->
                    { Text(title, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                trailing = {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                })
        }
    }
    if (search.canPageSearch || search.searchLoadingMore) item(key = "search-more") {
        if (platformKind == PlatformKind.DESKTOP || matchingReference || groups.isEmpty()) {
            MotionTextButton(onClick = controller::loadMoreCatalog, enabled = search.canPageSearch,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(when {
                    search.searchLoadingMore && !search.canPageSearch -> "正在加载更多"
                    groups.isEmpty() -> "继续查找"
                    else -> "更多结果"
                })
            }
        } else Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            BottomPullLoadIndicator(pull, Modifier.size(32.dp))
        }
    } else if (groups.isNotEmpty() && !loading && pageFailure) item(key = "search-retry-more") {
        MotionTextButton(onClick = controller::retrySearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("重试加载更多")
        }
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
internal fun AnimeSearchVersionSheet(
    versions: List<AnimeSourceTitle>,
    controller: AnimeController,
    sourceName: (String) -> String,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onChoose: (AnimeSourceTitle) -> Unit,
) {
    var selected by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    MotionBottomSheet(onDismissRequest = {
        val chosen = selected
        selected = null
        onDismiss()
        chosen?.let(onChoose)
    }, containerColor = MaterialTheme.colorScheme.background, backdrop = backdrop) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("选择要打开的版本", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭版本选择") {
                Icon(Icons.Outlined.Close, "关闭版本选择", Modifier.size(18.dp))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            if (versions.isEmpty()) item(key = "empty") { AnimeMessage("可用版本已更新，返回搜索重试") }
            items(versions, key = { controller.sourceSubject(it).id }) { version ->
                val source = sourceName(version.sourceId)
                val subtitle = listOfNotNull(version.year?.toString(),
                    version.updateText.takeIf(String::isNotBlank),
                    version.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ")
                AnimeSubjectRow(controller.sourceSubject(version), subtitle,
                    onOpen = { selected = version; dismiss() },
                    onOpenLabel = "打开此版本",
                    supporting = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AnimeSourceMark(version.sourceId, source, size = 16.dp)
                            AnimeQuietLabel(source)
                        }
                    })
            }
        }
    }
}
