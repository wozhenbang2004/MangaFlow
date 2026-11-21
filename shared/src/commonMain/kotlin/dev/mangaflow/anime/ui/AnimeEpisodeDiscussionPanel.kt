package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.ui.DetailAction
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun AnimeEpisodeDiscussionPanel(visible: Boolean, episode: AnimeEpisode, catalogue: List<AnimeEpisode>,
    metadata: AnimeSubject?, backdrop: HazeState, controller: AnimeController, onDismiss: () -> Unit, onClosed: () -> Unit) {
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 660.dp, onClosed = onClosed) {
        AnimePanelSurface(backdrop) {
            AnimeEpisodeDiscussionContent(visible, episode, catalogue, metadata, backdrop, controller,
                Modifier.fillMaxWidth().height(LocalMangaWindow.current.panelHeight), onDismiss = onDismiss)
        }
    }
}

/** Inline watching and fullscreen discussion use the same exact episode request and comment feed. */
@Composable
internal fun AnimeEpisodeDiscussionContent(visible: Boolean, episode: AnimeEpisode, catalogue: List<AnimeEpisode>,
    metadata: AnimeSubject?, backdrop: HazeState, controller: AnimeController, modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null) {
    val discussion by controller.discussion.state.collectAsStateWithLifecycle()
    val referenceId = metadata?.takeIf { it.canonicalBangumiId() != null }?.id
    val contextKey = discussion.takeIf { it.subjectId == episode.subjectId }?.episodeContextKey
    val exactReference = episode.metadataEpisodeId?.let { id ->
        id.removePrefix("bangumi:").toIntOrNull()?.takeIf { it > 0 && id == "bangumi:$it" }
    } != null && episode.metadataSubjectId == referenceId
    val canRead = referenceId != null && contextKey != null && episode in catalogue &&
        (!episode.subjectId.startsWith("douban:") || exactReference)
    val requestKey = listOf(episode.subjectId, episode.id, referenceId, contextKey,
        episode.metadataEpisodeId, episode.metadataSubjectId)
    var result by remember(requestKey, catalogue) { mutableStateOf<AnimeEpisodeDiscussion?>(null) }
    var loading by remember(requestKey, catalogue) { mutableStateOf(canRead) }
    var error by remember(requestKey, catalogue) { mutableStateOf<String?>(null) }
    var revision by remember(requestKey) { mutableIntStateOf(0) }
    var filterOpen by rememberSaveable(requestKey) { mutableStateOf(false) }
    var filterChanged by rememberSaveable(requestKey) { mutableStateOf(false) }

    LaunchedEffect(visible, requestKey, catalogue, revision) {
        if (!visible || !canRead) return@LaunchedEffect
        loading = true
        error = null
        try {
            val incoming = controller.discussion.readEpisode(episode, catalogue, force = revision > 0)
            currentCoroutineContext().ensureActive()
            result = incoming
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "暂时无法读取本集讨论。"
        } finally { loading = false }
    }

    Column(modifier.motionInputGate(visible)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                if (onDismiss != null) AnimeSectionTitle("本集评论")
                AnimeQuietLabel(animeEpisodeLabel(episode) + result?.source?.reviews?.size?.let { " · $it 条" }.orEmpty())
            }
            if (canRead) {
                if (result != null || loading) DetailAction(
                    icon = Icons.Outlined.FilterList,
                    description = if (filterOpen) "收起评论筛选" else if (filterChanged) "筛选评论，筛选已生效" else "筛选评论",
                    selected = filterOpen || filterChanged, enabled = visible,
                    onClick = { filterOpen = !filterOpen })
                MotionIconButton(onClick = { revision++ }, enabled = visible && !loading,
                    modifier = Modifier.size(44.dp), tooltip = "刷新本集讨论") {
                    Icon(Icons.Outlined.Refresh, "刷新本集讨论", Modifier.size(19.dp))
                }
            }
            if (onDismiss != null) MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭本集讨论") {
                Icon(Icons.Outlined.Close, "关闭本集讨论", Modifier.size(20.dp))
            }
        }
        val comments = result
        val failure = error
        if (!canRead || comments == null && !loading) {
            Column(Modifier.padding(16.dp)) {
                if (failure != null) AnimeDiscoveryInlineStatus(failure, retry = if (visible) ({ revision++ }) else null)
                else AnimeQuietLabel(if (referenceId == null || contextKey == null) "可在作品简介补充对应季度的 Bangumi 资料。"
                    else "这集尚未关联到准确的 Bangumi 集记录。")
            }
        } else {
            val source = comments?.source?.copy(loadingMore = loading, reviewsError = failure)
                ?: ReviewSource("bangumi", "Bangumi", ReviewStatus.NO_MATCH, "", loadingMore = loading)
            CommunityPanel(mangaId = "anime-episode:${episode.subjectId}:${episode.id}:$contextKey",
                sources = listOf(source), loading = loading, onMore = {}, onRetry = { revision++ },
                onMatch = { _, _ -> }, onRelink = {},
                loadReplies = { requested, review, page ->
                    ReviewPage(if (page == 0 && requested.id == comments?.source?.id &&
                        requested.subjectId == comments?.source?.subjectId)
                        comments?.replies?.get(review.id).orEmpty() else emptyList())
                }, modifier = Modifier.weight(1f), backdrop = backdrop, captureBackdrop = false, active = visible,
                filterOpen = filterOpen, onFilterDismiss = { filterOpen = false },
                onFilterChanged = { filterChanged = it })
        }
    }
}
