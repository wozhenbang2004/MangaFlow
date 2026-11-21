package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress

/** Saved native fields and exact catalogue IDs only; a display name never establishes identity. */
internal fun animeWatchedEpisodeName(entry: AnimeLibraryEntry, progress: AnimeWatchProgress): String {
    if (progress.subjectId.isNotBlank() && progress.subjectId != entry.subject.id) return "观看记录"
    val catalogued = entry.catalogDetail?.takeIf { it.subject.id == entry.subject.id }
        ?.episodes?.firstOrNull { it.id == progress.episodeId && it.subjectId == entry.subject.id }
    val episode = catalogued ?: progress.episodeNumber?.takeIf { it.isFinite() }?.let { number ->
        progress.episodeKind?.let { kind ->
            AnimeEpisode(progress.episodeId, entry.subject.id, progress.episodeTitle, number, kind)
        }
    }
    if (episode == null) return progress.episodeTitle.trim().ifBlank { "上次观看的剧集" }
    return listOfNotNull(animeEpisodeLabel(episode), animeEpisodeSubtitle(episode)).joinToString(" · ")
}

internal fun animeWatchLocation(entry: AnimeLibraryEntry, progress: AnimeWatchProgress? = entry.resume): String {
    if (progress == null) return "尚未观看"
    if (progress.subjectId.isNotBlank() && progress.subjectId != entry.subject.id) return "观看位置暂不可用"
    val position = if (progress.completed) "已看完" else animeTime(progress.positionMs) +
        progress.durationMs.takeIf { it > 0L }?.let { " / " + animeTime(it) }.orEmpty()
    return animeWatchedEpisodeName(entry, progress) + " · " + position
}

internal fun animeContinuationLabel(entry: AnimeLibraryEntry, target: AnimeEpisode?): String = when {
    entry.resume == null -> "查看详情与选集"
    target != null && target.id != entry.resume.episodeId ->
        "观看下一集 · " + animeEpisodeLabel(target)
    target != null && entry.resume.completed -> "重看本集"
    else -> "继续观看"
}

/** Uses the ordinary manga chapter-history sheet's local records, row geometry and exit handoff. */
@Composable
internal fun AnimeEpisodeHistorySheet(
    entry: AnimeLibraryEntry,
    progress: Map<String, AnimeWatchProgress>,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onContinue: (AnimeWatchProgress) -> Unit,
) {
    var selected by remember(entry.subject.id) { mutableStateOf<AnimeWatchProgress?>(null) }
    val rows = remember(entry, progress) {
        (progress.values.filter { it.subjectId == entry.subject.id } + listOfNotNull(entry.resume?.takeIf {
            it.subjectId.isBlank() || it.subjectId == entry.subject.id
        })).sortedByDescending { it.updatedAt }.distinctBy { it.episodeId }
    }
    val scroll = rememberLazyListState()
    MotionBottomSheet(onDismissRequest = {
        val chosen = selected
        selected = null
        onDismiss()
        chosen?.let(onContinue)
    }, containerColor = MaterialTheme.colorScheme.background, backdrop = backdrop, desktopCloseButton = true) { dismiss ->
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("观看记录", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(entry.subject.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭观看记录") {
                    Icon(Icons.Outlined.Close, "关闭观看记录", Modifier.size(20.dp))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = scroll,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (rows.isEmpty()) item(key = "empty") { AnimeMessage("还没有逐集观看记录") }
                items(rows, key = { it.episodeId }) { record ->
                    val interaction = remember(record.episodeId) { MutableInteractionSource() }
                    val label = if (record.completed) "重看这一集" else "从已保存位置继续"
                    Row(Modifier.fillMaxWidth()
                        .motionPress(interaction, pressedScale = .985f)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = label, onClick = { selected = record; dismiss() })
                        .heightIn(min = 52.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(animeWatchedEpisodeName(entry, record), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(if (record.completed) "已看完" else animeTime(record.positionMs) +
                                record.durationMs.takeIf { it > 0L }?.let { " / " + animeTime(it) }.orEmpty(),
                                animeSourceName(record.sourceId)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (!record.completed && record.durationMs > 0L) AnimeWatchProgressLine(
                                (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f))
                        }
                        Icon(Icons.Outlined.PlayArrow, label, Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
