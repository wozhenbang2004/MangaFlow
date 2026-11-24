package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.sources.AnimeEpisodeDiscussion
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.ui.DetailAction
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inline and expanded watching panes share the same exact episode request. */
@Composable
internal fun AnimeEpisodeDiscussionContent(visible: Boolean, episode: AnimeEpisode, catalogue: List<AnimeEpisode>,
    metadata: AnimeSubject?, backdrop: HazeState, controller: AnimeController, modifier: Modifier = Modifier) {
    val discussion by controller.discussion.state.collectAsStateWithLifecycle()
    val referenceId = metadata?.takeIf { it.canonicalBangumiId() != null }?.id
    val contextKey = discussion.takeIf { it.subjectId == episode.subjectId }?.episodeContextKey
    // Playback retains its opening snapshot. Display-only catalogue updates must not erase the same exact episode.
    val currentEpisode = catalogue.singleOrNull { it.id == episode.id && it.subjectId == episode.subjectId && it.kind == episode.kind }
    val exactReference = currentEpisode?.metadataEpisodeId?.let { id ->
        id.removePrefix("bangumi:").toIntOrNull()?.takeIf { it > 0 && id == "bangumi:$it" }
    } != null && currentEpisode?.metadataSubjectId == referenceId
    val target = currentEpisode?.takeIf { referenceId != null && contextKey != null &&
        (!it.subjectId.startsWith("douban:") || exactReference) }
    val canRead = target != null
    val requestKey = listOf(episode.subjectId, episode.id, referenceId, contextKey,
        target?.metadataEpisodeId, target?.metadataSubjectId)
    var result by remember(requestKey, catalogue) { mutableStateOf<AnimeEpisodeDiscussion?>(null) }
    var loading by remember(requestKey, catalogue) { mutableStateOf(canRead) }
    var error by remember(requestKey, catalogue) { mutableStateOf<String?>(null) }
    var revision by remember(requestKey) { mutableIntStateOf(0) }
    var filterOpen by rememberSaveable(requestKey) { mutableStateOf(false) }
    var filterChanged by rememberSaveable(requestKey) { mutableStateOf(false) }

    LaunchedEffect(visible, requestKey, catalogue, revision) {
        if (!visible || target == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val incoming = controller.discussion.readEpisode(target, catalogue, force = revision > 0)
            currentCoroutineContext().ensureActive()
            result = incoming
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "暂时无法读取本集讨论。"
        } finally { loading = false }
    }

    val hasComments = result?.source?.reviews?.isNotEmpty() == true
    LaunchedEffect(visible, hasComments) { if (!visible || !hasComments) filterOpen = false }
    Column(modifier.motionInputGate(visible)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                AnimeQuietLabel(animeEpisodeLabel(currentEpisode ?: episode) + result?.source?.reviews?.size?.takeIf { it > 0 }
                    ?.let { " · 已显示 $it 条" }.orEmpty())
            }
            if (canRead) {
                if (hasComments) DetailAction(
                    icon = Icons.Outlined.FilterList,
                    description = if (filterOpen) "收起评论筛选" else if (filterChanged) "筛选评论，筛选已生效" else "筛选评论",
                    selected = filterOpen || filterChanged, enabled = visible,
                    onClick = { filterOpen = !filterOpen })
                MotionIconButton(onClick = { revision++ }, enabled = visible && !loading,
                    modifier = Modifier.size(44.dp), tooltip = "刷新本集讨论") {
                    Icon(Icons.Outlined.Refresh, "刷新本集讨论", Modifier.size(19.dp))
                }
            }
        }
        val comments = result
        val failure = error
        if (!canRead || comments == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when {
                    failure != null -> AnimeDiscoveryInlineStatus(failure, retry = if (visible) ({ revision++ }) else null)
                    canRead && loading -> Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionBusyIndicator(Modifier.size(16.dp))
                        AnimeQuietLabel("正在读取本集评论")
                    }
                    referenceId == null -> AnimeMessage("本集评论暂无对应资料", "可在作品详情关联正确季度的 Bangumi 条目。")
                    contextKey == null -> AnimeMessage("本集评论暂不可用", "当前还没有确认可读取的评论来源。")
                    else -> AnimeMessage("本集还没有匹配到 Bangumi 记录", "不会使用其他集的评论。")
                }
            }
        } else {
            val source = comments.source.copy(loadingMore = loading, reviewsError = failure)
            CommunityPanel(mangaId = "anime-episode:${episode.subjectId}:${episode.id}:$contextKey",
                sources = listOf(source), loading = loading, onMore = {}, onRetry = { revision++ },
                onMatch = { _, _ -> }, onRelink = {},
                loadReplies = { requested, review, page ->
                    ReviewPage(if (page == 0 && requested.id == comments.source.id &&
                        requested.subjectId == comments.source.subjectId)
                        comments.replies[review.id].orEmpty() else emptyList())
                }, modifier = Modifier.weight(1f), backdrop = backdrop, captureBackdrop = false, active = visible,
                filterOpen = filterOpen, onFilterDismiss = { filterOpen = false },
                onFilterChanged = { filterChanged = it })
        }
    }
}
