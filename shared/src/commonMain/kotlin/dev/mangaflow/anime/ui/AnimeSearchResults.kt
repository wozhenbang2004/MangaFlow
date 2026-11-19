package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.ui.motion.MotionTextButton

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState,
    indexOnly: Boolean,
    controller: AnimeController,
    showIssues: Boolean,
    onToggleIssues: () -> Unit,
    expandedGroup: String?,
    onToggleSources: (String) -> Unit,
    onOpenSubject: (AnimeSubject) -> Unit,
    onOpenSource: (AnimeSourceTitle) -> Unit,
) {
    // When matching a foreign catalogue entry, show only canonical index candidates.
    val groups = if (indexOnly) search.groups.filter { it.subject != null } else search.groups
    val sources = if (indexOnly) emptyList() else search.sources
    val index = search.index
    val failed = sources.filter { it.error != null }
    val failureCount = failed.size + if (index.error == null) 0 else 1
    val pending = sources.count { it.loading }
    val loadingIndex = index.loading && index.page == 0
    val loadingMore = (index.loading && index.page > 0) || sources.any { it.loadingMore }
    val hasMore = (index.hasMore && index.error == null) || sources.any { it.hasMore && it.error == null }
    val sourceNames = sources.associate { it.providerId to it.name }

    if (groups.isEmpty()) item(key = "search-status") {
        when {
            pending > 0 || loadingIndex -> AnimeMessage("正在搜索", search.query)
            failureCount > 0 -> AnimeMessage("搜索未完成")
            else -> AnimeMessage(if (indexOnly) "未找到对应的作品资料" else "没有找到番剧",
                if (indexOnly) "已保留所选来源。可以换用原名或别名。" else "试试原名、别名或更短的关键词。")
        }
    }

    if (failureCount > 0) item(key = "search-issues") {
        MotionTextButton(onClick = onToggleIssues, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (showIssues) "收起失败详情" else "$failureCount 个来源未返回")
        }
    }
    if (showIssues) {
        index.error?.let { message ->
            item(key = "search-issue:index") {
                AnimeDiscoveryInlineStatus("Bangumi · $message", retry = controller::retrySearchIndex)
            }
        }
        items(failed, key = { "search-issue:${it.providerId}" }) { row ->
            AnimeDiscoveryInlineStatus("${row.name} · ${row.error.orEmpty()}",
                retry = { controller.retrySearchProvider(row.providerId) })
        }
    }

    items(groups, key = { it.key }) { group ->
        val subject = group.subject
        val first = group.sources.firstOrNull()
        if (subject != null) {
            AnimeSubjectRow(subject, animeSubjectSummary(subject), onOpen = { onOpenSubject(subject) }, trailing = {
                if (!indexOnly && group.sources.isNotEmpty()) {
                    val count = group.sources.size
                    AnimeQuietLabel("$count 源", Modifier.semantics {
                        contentDescription = "搜索命中 $count 个来源，播放尚未确认"
                    })
                }
            })
        } else if (first != null) {
            Column {
                AnimeSubjectRow(remember(first) { controller.sourceSubject(first) },
                    listOfNotNull(sourceNames[first.sourceId], first.updateText.takeIf(String::isNotBlank)).joinToString(" · "),
                    onOpen = { onOpenSource(first) }, trailing = {
                        if (group.sources.size > 1) MotionTextButton(onClick = { onToggleSources(group.key) },
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(if (expandedGroup == group.key) "收起" else "${group.sources.size} 源")
                        }
                    })
                if (expandedGroup == group.key) group.sources.drop(1).forEach { title ->
                    MotionTextButton(onClick = { onOpenSource(title) }, Modifier.padding(start = 64.dp).heightIn(min = 44.dp)) {
                        Text(sourceNames[title.sourceId] ?: title.sourceId)
                    }
                }
            }
        }
    }

    if (groups.isNotEmpty() && (pending > 0 || loadingIndex)) item(key = "search-pending") {
        AnimeQuietLabel("正在补全搜索结果", Modifier.padding(12.dp))
    }
    if (hasMore || loadingMore) item(key = "search-more") {
        MotionTextButton(onClick = controller::loadMoreCatalog, enabled = !loadingMore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (loadingMore) "正在加载" else "更多结果")
        }
    }
}
