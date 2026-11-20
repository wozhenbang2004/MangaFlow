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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.sources.AnimeEpisodeDiscussion
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException

@Composable
internal fun AnimeEpisodeDiscussionPanel(visible: Boolean, episode: AnimeEpisode, catalogue: List<AnimeEpisode>,
    referenceId: String?, backdrop: HazeState, controller: AnimeController, onDismiss: () -> Unit, onClosed: () -> Unit) {
    var result by remember(episode.id, referenceId) { mutableStateOf<AnimeEpisodeDiscussion?>(null) }
    var loading by remember(episode.id, referenceId) { mutableStateOf(referenceId != null) }
    var error by remember(episode.id, referenceId) { mutableStateOf<String?>(null) }
    var revision by remember(episode.id, referenceId) { mutableIntStateOf(0) }

    LaunchedEffect(visible, episode.id, referenceId, revision) {
        if (!visible || referenceId == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            result = controller.discussion.readEpisode(episode, catalogue, force = revision > 0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "暂时无法读取本集讨论。"
        } finally { loading = false }
    }

    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 660.dp, onClosed = onClosed) {
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().height(LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        AnimeSectionTitle("本集讨论")
                        AnimeQuietLabel(animeEpisodeLabel(episode))
                    }
                    if (referenceId != null) MotionIconButton(onClick = { revision++ }, enabled = visible && !loading,
                        modifier = Modifier.size(44.dp), tooltip = "刷新本集讨论") {
                        Icon(Icons.Outlined.Refresh, "刷新本集讨论", Modifier.size(19.dp))
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭本集讨论") {
                        Icon(Icons.Outlined.Close, "关闭本集讨论", Modifier.size(20.dp))
                    }
                }
                val comments = result
                val failure = error
                if (referenceId == null || comments == null && !loading) {
                    Column(Modifier.padding(16.dp)) {
                        if (failure != null) AnimeDiscoveryInlineStatus(failure, retry = { revision++ })
                        else AnimeQuietLabel(if (referenceId == null) "在作品简介关联对应的 Bangumi 季度后可查看讨论。"
                            else "Bangumi 目录中暂未确认对应的这一集。")
                    }
                } else {
                    val source = comments?.source?.copy(loadingMore = loading, reviewsError = failure)
                    CommunityPanel(mangaId = "anime-episode:${episode.subjectId}:${episode.id}",
                        sources = listOfNotNull(source), loading = loading, onMore = {}, onRetry = { revision++ },
                        onMatch = { _, _ -> }, onRelink = {},
                        loadReplies = { requested, review, page ->
                            ReviewPage(if (page == 0 && requested.subjectId == comments?.source?.subjectId)
                                comments?.replies?.get(review.id).orEmpty() else emptyList())
                        }, modifier = Modifier.weight(1f), backdrop = backdrop, captureBackdrop = false, active = visible)
                }
            }
        }
    }
}
