package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeSearchGroup
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeUnifiedSearchState
import dev.mangaflow.anime.canLoadMoreSearch
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate

internal fun LazyListScope.animeSearchResults(
    search: AnimeUnifiedSearchState,
    indexOnly: Boolean,
    controller: AnimeController,
    showIssues: Boolean,
    onToggleIssues: () -> Unit,
    expandedGroup: String?,
    onToggleSources: (String) -> Unit,
    onOpenSubject: (AnimeSubject) -> Unit,
    onOpenSource: (AnimeSearchGroup, AnimeSourceTitle) -> Unit,
    onOpenSourceGroup: (AnimeSearchGroup) -> Unit,
) {
    // When matching a foreign catalogue entry, show only canonical index candidates.
    val groups = if (indexOnly) search.groups.filter { it.subject != null } else search.groups
    val sources = if (indexOnly) emptyList() else search.sources
    val index = search.index
    val failed = sources.filter { it.error != null }
    val failureCount = failed.size + if (index.error == null) 0 else 1
    val pending = sources.count { it.page == 0 && it.error == null }
    val loadingIndex = index.page == 0 && index.error == null
    val paging = listOfNotNull("Bangumi".takeIf { index.loading && index.page > 0 }) +
        sources.filter { it.loadingMore }.map { it.name }
    val canLoadMore = index.canLoadMore || sources.any { it.canLoadMoreSearch }
    val sourceNames = sources.associate { it.providerId to it.name }

    if (groups.isEmpty()) item(key = "search-status") {
        when {
            pending > 0 || loadingIndex -> AnimeMessage("正在搜索", search.query)
            failureCount > 0 -> AnimeMessage("搜索未完成")
            else -> AnimeMessage(if (indexOnly) "未找到对应的作品资料" else "没有找到番剧",
                if (indexOnly) "已保留所选来源。可以换用原名或别名。" else "试试原名、别名或更短的关键词。")
        }
    }

    fun LazyListScope.issues() {
        if (failureCount == 0) return
        item(key = "search-issues") {
            MotionTextButton(onClick = onToggleIssues, modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(if (showIssues) "收起搜索问题" else "$failureCount 个来源可重试")
            }
        }
        if (showIssues) {
            index.error?.let { message ->
                item(key = "search-issue:index") {
                    AnimeDiscoveryInlineStatus("Bangumi · $message", retry = controller::retrySearchIndex,
                        retryLabel = if (index.page > 0) "重试下一页" else "重试搜索")
                }
            }
            items(failed, key = { "search-issue:${it.providerId}" }) { row ->
                AnimeDiscoveryInlineStatus("${row.name} · ${row.error.orEmpty()}",
                    retry = { controller.retrySearchProvider(row.providerId) },
                    retryLabel = if (row.page > 0) "重试下一页" else "重试搜索")
            }
        }
    }
    if (groups.isNotEmpty()) item(key = "search-summary") {
        AnimeSectionTitle("${groups.size} 部番剧", if (pending > 0 || loadingIndex) "来源结果仍在补全" else null)
    }
    issues()

    items(groups, key = { it.key }) { group ->
        val subject = group.subject
        val first = group.sources.firstOrNull()
        val display = subject ?: first?.let { remember(it) { controller.sourceSubject(it) } }
        if (display != null) {
            val candidates = if (indexOnly) emptyList() else group.sources
            val marks = candidates.map { it.sourceId to (sourceNames[it.sourceId] ?: animeSourceName(it.sourceId)) }
            val expanded = candidates.isNotEmpty() && expandedGroup == group.key
            val subtitle = if (subject != null) animeSubjectSummary(subject) else listOfNotNull(
                first?.let { sourceNames[it.sourceId] ?: animeSourceName(it.sourceId) },
                first?.updateText?.takeIf(String::isNotBlank),
            ).joinToString(" · ")
            Column {
                AnimeSubjectRow(display, subtitle, onOpen = {
                    if (subject != null) onOpenSubject(subject) else onOpenSourceGroup(group)
                }, trailing = {
                    if (marks.isNotEmpty()) AnimeSourceBadge(marks, compact = true,
                        modifier = Modifier.widthIn(max = 88.dp).semantics {
                            stateDescription = if (expanded) "已展开来源候选" else "搜索命中的播放候选"
                        }, onClick = { onToggleSources(group.key) })
                })
                val motion = LocalMotionEnabled.current
                AnimatedVisibility(expanded,
                    enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                        fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                    exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                        fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                    Column(Modifier.padding(start = 68.dp).motionInputGate(expanded)) {
                        candidates.forEach { candidate ->
                            val name = sourceNames[candidate.sourceId] ?: animeSourceName(candidate.sourceId)
                            MotionTextButton(onClick = { onOpenSource(group, candidate) },
                                modifier = Modifier.heightIn(min = 44.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    AnimeSourceMark(candidate.sourceId, name, size = 17.dp)
                                    Text(listOfNotNull(name, candidate.title.takeIf { it != display.title })
                                        .joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (canLoadMore) item(key = "search-more") {
        MotionTextButton(onClick = controller::loadMoreCatalog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("更多结果")
        }
    }
    if (paging.isNotEmpty()) item(key = "search-more-pending") {
        val names = paging.take(2).joinToString("、")
        val remaining = (paging.size - 2).takeIf { it > 0 }?.let { "等 ${paging.size} 个来源" }.orEmpty()
        AnimeQuietLabel("正在读取下一页 · $names$remaining", Modifier.padding(12.dp))
    }
}
