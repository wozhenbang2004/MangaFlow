package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.MotionTextButton

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState,
    controller: AnimeController,
    matchingReference: Boolean,
    onOpenSubject: (AnimeSubject) -> Unit,
    onBrowseSources: () -> Unit,
    onManage: (AnimeSubject) -> Unit,
    pull: BottomPullLoadState,
) {
    val groups = search.groups.filter { it.subject != null }
    val index = search.index
    val provider = animeDiscoveryProviderLabel(search.catalogProvider)
    if (groups.isEmpty() && index.error == null) item(key = "search-status") {
        when {
            index.loading -> AnimeDiscoveryLoading("正在查找番剧")
            index.canLoadMore -> AnimeMessage("本页暂无匹配")
            else -> AnimeMessage(if (matchingReference) "未找到对应的番剧" else "没有找到匹配的番剧",
                if (matchingReference) "已保留所选来源。试试原名或别名。" else "试试原名、别名或更短的关键词。")
        }
    }
    if (groups.isNotEmpty()) item(key = "search-summary") {
        AnimeDiscoverySectionHeading("作品", provider, refreshing = index.loading && index.page == 0)
    }
    index.error?.let { message ->
        item(key = "search-issue:index") {
            AnimeDiscoveryInlineStatus(if (index.page > 0) "暂时无法取得更多作品" else "暂时无法搜索番剧",
                retry = controller::retrySearchIndex, retryLabel = if (index.page > 0) "重试下一页" else "重试",
                detail = "$provider · $message")
        }
    }
    items(groups, key = { it.key }) { group ->
        group.subject?.let { subject ->
            DesktopContextMenuArea(if (matchingReference) emptyList() else
                listOf(DesktopMenuAction("番剧操作") { onManage(subject) })) {
                val original = subject.originalTitle.takeIf { it.isNotBlank() && !it.equals(subject.title, ignoreCase = true) }
                val metadata = listOfNotNull(subject.year?.toString(),
                    "电影".takeIf { subject.format == AnimeDiscoveryFormat.MOVIE }
                        ?: subject.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ")
                AnimeSubjectRow(subject, metadata, onOpen = { onOpenSubject(subject) },
                    onOpenLabel = if (matchingReference) "选择这部番剧" else "查看详情",
                    onLongClick = if (matchingReference) null else ({ onManage(subject) }),
                    ratings = if (matchingReference) null else ({ AnimeSubjectRatings(subject) }),
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
    }
    if (index.canLoadMore) item(key = "search-more") {
        if (platformKind == PlatformKind.DESKTOP || matchingReference || groups.isEmpty()) MotionTextButton(onClick = controller::loadMoreCatalog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(if (groups.isEmpty()) "继续查找" else "更多结果")
        } else Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            BottomPullLoadIndicator(pull, Modifier.size(32.dp))
        }
    }
    if (index.loading && index.page > 0) item(key = "search-more-pending") {
        if (platformKind != PlatformKind.DESKTOP && !matchingReference)
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                BottomPullLoadIndicator(pull, Modifier.size(32.dp))
            }
        else AnimeQuietLabel("正在读取下一页", Modifier.padding(12.dp))
    }
    if (!matchingReference && !index.loading && (groups.isEmpty() || index.error != null)) {
        item(key = "search-source-directory") {
            MotionTextButton(onClick = onBrowseSources,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text("浏览片源目录")
            }
        }
    }
}
