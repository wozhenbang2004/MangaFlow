package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.localDateAt
import dev.mangaflow.ui.platform.localToday

internal fun AnimeWatchProgress.belongsToWatchHistory(subjectId: String): Boolean =
    episodeId.isNotBlank() && (this.subjectId.isBlank() || this.subjectId == subjectId)

internal enum class AnimeHistoryPlayback(val label: String) {
    RESUME("续看本集"),
    REPLAY("重看本集"),
    UNAVAILABLE("本集暂不可直接播放"),
}

/** Only the controller's exact progress can supply a resume offset; an old catalogue can prove a replay target. */
internal fun animeHistoryPlayback(
    entry: AnimeLibraryEntry,
    record: AnimeWatchProgress,
    progress: Map<String, AnimeWatchProgress>,
    currentDetail: AnimeDetail?,
): AnimeHistoryPlayback {
    val subjectId = entry.subject.id
    if (!record.belongsToWatchHistory(subjectId)) return AnimeHistoryPlayback.UNAVAILABLE
    val checkpoint = progress[record.episodeId]?.takeIf { it.subjectId == subjectId }
    if (checkpoint != null) return if (checkpoint.completed) AnimeHistoryPlayback.REPLAY else AnimeHistoryPlayback.RESUME
    if (!record.completed) return AnimeHistoryPlayback.UNAVAILABLE
    val catalogue = currentDetail?.takeIf { it.subject.id == subjectId } ?: entry.catalogDetail
    return if (catalogue?.episodes?.any { it.id == record.episodeId && it.subjectId == subjectId } == true)
        AnimeHistoryPlayback.REPLAY else AnimeHistoryPlayback.UNAVAILABLE
}

private fun animeWatchRecordTime(timestamp: Long): String {
    if (timestamp <= 0L) return "早期记录"
    return formatInstantMillis(timestamp, if (localDateAt(timestamp).year == localToday().year) "MM-dd HH:mm" else "yyyy-MM-dd")
}

@Composable
internal fun AnimeHistoryActionLine(playback: AnimeHistoryPlayback, updatedAt: Long) {
    val colors = MaterialTheme.colorScheme
    val time = remember(updatedAt) { animeWatchRecordTime(updatedAt) }
    val playable = playback != AnimeHistoryPlayback.UNAVAILABLE
    val ink = if (playable) colors.primary else colors.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (playable) Icon(if (playback == AnimeHistoryPlayback.REPLAY) Icons.Outlined.Replay else Icons.Outlined.PlayArrow,
            null, Modifier.size(14.dp), tint = ink)
        Text(playback.label + " · " + time, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Saved native fields and exact catalogue IDs only; a display name never establishes identity. */
internal fun animeWatchedEpisodeName(
    entry: AnimeLibraryEntry,
    progress: AnimeWatchProgress,
    catalogued: AnimeEpisode? = entry.catalogDetail?.takeIf { it.subject.id == entry.subject.id }
        ?.episodes?.firstOrNull { it.id == progress.episodeId && it.subjectId == entry.subject.id },
): String {
    if (progress.subjectId.isNotBlank() && progress.subjectId != entry.subject.id) return "观看记录"
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
    return animeWatchedEpisodeName(entry, progress) + " · " + animeWatchPosition(progress)
}

internal fun animeWatchPosition(progress: AnimeWatchProgress): String =
    if (progress.completed) "已看完" else animeTime(progress.positionMs) +
        progress.durationMs.takeIf { it > 0L }?.let { " / " + animeTime(it) }.orEmpty()

internal fun animeWatchElapsed(milliseconds: Long): String {
    val seconds = milliseconds / 1_000L
    return when {
        seconds >= 3_600L -> "${seconds / 3_600L} 小时 ${seconds / 60L % 60L} 分钟"
        seconds >= 60L -> "${seconds / 60L} 分钟 ${seconds % 60L} 秒"
        seconds > 0L -> "$seconds 秒"
        else -> "不足 1 秒"
    }
}

internal fun animeContinuationLabel(entry: AnimeLibraryEntry, target: AnimeEpisode?): String = when {
    entry.resume == null -> "查看详情与选集"
    target != null && target.id != entry.resume.episodeId ->
        "下一集 · " + animeEpisodeLabel(target)
    target != null && entry.resume.completed -> "重看本集"
    else -> "继续观看"
}

/** Uses the ordinary manga chapter-history sheet's local records, row geometry and exit handoff. */
@Composable
internal fun AnimeEpisodeHistorySheet(
    entry: AnimeLibraryEntry,
    progress: Map<String, AnimeWatchProgress>,
    currentDetail: AnimeDetail?,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onContinue: (AnimeWatchProgress) -> Unit,
) {
    var selected by remember(entry.subject.id) { mutableStateOf<AnimeWatchProgress?>(null) }
    val rows = remember(entry.subject.id, entry.resume, progress) {
        (progress.values.filter { it.subjectId == entry.subject.id } + listOfNotNull(entry.resume?.takeIf {
            it.belongsToWatchHistory(entry.subject.id)
        })).sortedWith(compareByDescending<AnimeWatchProgress> { it.updatedAt }.thenBy { it.episodeId })
            .distinctBy { it.episodeId }
    }
    val catalogue = remember(entry.subject.id, entry.catalogDetail) {
        buildMap<String, AnimeEpisode> {
            entry.catalogDetail?.takeIf { it.subject.id == entry.subject.id }?.episodes?.forEach { episode ->
                if (episode.subjectId == entry.subject.id && episode.id !in this) put(episode.id, episode)
            }
        }
    }
    val scroll = rememberLazyListState()
    MotionBottomSheet(onDismissRequest = {
        val chosen = selected
        selected = null
        onDismiss()
        chosen?.let(onContinue)
    }, backdrop = backdrop) { dismiss ->
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimePoster(entry.subject, Modifier.width(38.dp).height(54.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("逐集观看记录", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(entry.subject.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.visibleWatchMillis > 0L)
                        AnimeQuietLabel("本作品已记录观看 ${animeWatchElapsed(entry.visibleWatchMillis)}")
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭观看记录") {
                    Icon(Icons.Outlined.Close, "关闭观看记录", Modifier.size(20.dp))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = scroll,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rows.isEmpty()) item(key = "empty") { AnimeMessage("还没有逐集观看记录") }
                items(rows, key = { it.episodeId }, contentType = { "episode-history" }) { record ->
                    val playback = animeHistoryPlayback(entry, record, progress, currentDetail)
                    AnimeEpisodeHistoryRow(entry, record, catalogue[record.episodeId],
                        playback = playback,
                        onOpen = { selected = record; dismiss() })
                }
            }
        }
    }
}


@Composable
private fun AnimeEpisodeHistoryRow(
    entry: AnimeLibraryEntry,
    record: AnimeWatchProgress,
    catalogued: AnimeEpisode?,
    playback: AnimeHistoryPlayback,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(record.episodeId) { MutableInteractionSource() }
    val current = entry.resume?.episodeId == record.episodeId && !record.completed
    val name = remember(entry.subject.id, record, catalogued) { animeWatchedEpisodeName(entry, record, catalogued) }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape)
        .then(if (current) Modifier.background(colors.primaryContainer) else Modifier)
        .then(if (playback != AnimeHistoryPlayback.UNAVAILABLE) Modifier.motionPress(interaction, pressedScale = .985f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = playback.label, onClick = onOpen) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium,
                color = if (current) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(
                "上次看到".takeIf { current },
                animeWatchPosition(record),
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!record.completed && record.durationMs > 0L) AnimeWatchProgressLine(
                (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f), Modifier.widthIn(max = 192.dp))
            AnimeHistoryActionLine(playback, record.updatedAt)
        }
    }
}
