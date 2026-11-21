package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.ui.motion.MotionTextButton

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState,
    controller: AnimeController,
    matchingReference: Boolean,
    onOpenSubject: (AnimeSubject) -> Unit,
    onBrowseSources: () -> Unit,
) {
    val groups = search.groups.filter { it.subject != null }
    val index = search.index
    val provider = animeDiscoveryProviderLabel(search.catalogProvider)
    if (groups.isEmpty()) item(key = "search-status") {
        when {
            index.loading -> AnimeMessage("正在搜索", "正在查作品资料")
            index.error != null -> AnimeMessage("搜索未完成")
            else -> AnimeMessage(if (matchingReference) "未找到对应的作品资料" else "没有找到番剧",
                if (matchingReference) "已保留所选来源。可以换用原名或别名。" else "试试原名、别名或更短的关键词。")
        }
    }
    if (groups.isNotEmpty()) item(key = "search-summary") {
        AnimeSectionTitle("${groups.size} 部番剧")
    }
    index.error?.let { message ->
        item(key = "search-issue:index") {
            AnimeDiscoveryInlineStatus("$provider · $message", retry = controller::retrySearchIndex,
                retryLabel = if (index.page > 0) "重试下一页" else "重试搜索")
        }
    }
    items(groups, key = { it.key }) { group ->
        group.subject?.let { subject ->
            AnimeSubjectRow(subject, animeSubjectSummary(subject), onOpen = { onOpenSubject(subject) },
                ratings = if (matchingReference) null else ({ AnimeSubjectRatings(subject) }))
        }
    }
    if (index.canLoadMore) item(key = "search-more") {
        MotionTextButton(onClick = controller::loadMoreCatalog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("更多结果")
        }
    }
    if (index.loading && index.page > 0) item(key = "search-more-pending") {
        AnimeQuietLabel("正在读取下一页", Modifier.padding(12.dp))
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
