package dev.mangaflow.anime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.downloads.AnimeDownloadStatus
import dev.mangaflow.anime.downloads.AnimeDownloadsState
import dev.mangaflow.ui.DetailActionDock
import dev.mangaflow.ui.DetailDockContent
import dev.mangaflow.ui.DetailDockOption
import dev.mangaflow.ui.motion.MotionSwap
import kotlin.math.roundToInt

internal fun animeWatchFraction(progress: AnimeWatchProgress?): Float? = progress?.let {
    if (it.completed) 1f else if (it.durationMs > 0)
        (it.positionMs.toDouble() / it.durationMs).coerceIn(0.0, 1.0).toFloat() else null
}

internal fun animeWatchProgressDescription(progress: AnimeWatchProgress?, completed: Boolean = false): String = when {
    completed || progress?.completed == true -> "已看完"
    progress != null && progress.positionMs > 0 -> "看至 ${animeTime(progress.positionMs)}" +
        if (progress.durationMs > 0) "，共 ${animeTime(progress.durationMs)}" else ""
    else -> "未看"
}

/** Adapted from the actual manga ChapterReadMark; unknown duration never creates a percentage. */
@Composable
internal fun AnimeWatchProgressMark(progress: AnimeWatchProgress?, completed: Boolean,
    modifier: Modifier = Modifier, size: Dp = 14.dp) {
    if (progress == null && !completed) return
    val fraction = animeWatchFraction(progress)
    val color = MaterialTheme.colorScheme.primary
    val done = completed || progress?.completed == true
    val ringStroke = (size.value * .1f).coerceAtLeast(1f).dp
    val dotRadius = (size.value * .13f).dp
    MotionSwap(done, modifier.size(size).semantics {
        contentDescription = animeWatchProgressDescription(progress, completed)
    }) { complete ->
        if (complete) Icon(Icons.Outlined.DoneAll, null, Modifier.size(size), tint = color.copy(alpha = .72f))
        else Canvas(Modifier.size(size)) {
            val stroke = ringStroke.toPx()
            val inset = stroke / 2f
            drawCircle(color.copy(alpha = .18f), radius = (this.size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (fraction == null) drawCircle(color.copy(alpha = .72f), radius = dotRadius.toPx())
            else if (fraction > 0f) drawArc(color.copy(alpha = .72f), -90f, 360f * fraction, false,
                topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

internal enum class AnimeDetailOffline { CHECKING, READY, MISSING }

/** This read never opens a file. The existing download owner validates bytes again at playback. */
internal fun animeDetailOffline(snapshot: AnimeDownloadsState, subjectId: String, progress: AnimeWatchProgress): AnimeDetailOffline = when {
    snapshot.loading -> AnimeDetailOffline.CHECKING
    snapshot.entries.any { row -> row.id == progress.localDownloadId && row.status == AnimeDownloadStatus.COMPLETED &&
        row.subject.id == subjectId && row.episode.subjectId == subjectId && row.episode.id == progress.episodeId &&
        row.assetId == progress.assetId && row.sourceId == progress.sourceId } -> AnimeDetailOffline.READY
    else -> AnimeDetailOffline.MISSING
}

internal enum class AnimeDetailPlayMode { PLAY, FIND, SOURCES, UPCOMING, WAIT }

internal data class AnimeDetailPlayControl(
    val title: String,
    val compactTitle: String,
    val caption: String,
    val mode: AnimeDetailPlayMode,
    val fraction: Float?,
)

internal class AnimeCataloguePlayAction(val control: AnimeDetailPlayControl, val activate: () -> Unit)

@Composable
internal fun rememberCataloguePlayAction(controller: AnimeController, subjectId: String,
    active: Boolean, awaitingCatalogue: Boolean): AnimeCataloguePlayAction? {
    val visit = controller.relatedSourceVisit
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible by rememberUpdatedState(active)
    var requested by remember(controller, subjectId, visit) { mutableStateOf(false) }
    if (requested) {
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) requested = false
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(active, visit) {
            try {
                if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    controller.playFirstWhenCatalogueArrives(subjectId, visit) {
                        requested && visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
            } finally { requested = false }
        }
    }
    if (!active || !requested && !awaitingCatalogue) return null
    val label = if (requested) "取消等待" else "播放正片"
    return AnimeCataloguePlayAction(AnimeDetailPlayControl(label, label,
        if (requested) "正在确认本作正片" else "目录就绪后开播", AnimeDetailPlayMode.PLAY, null)) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requested = !requested
    }
}

internal fun animeDetailPlayControl(episode: AnimeEpisode?, previous: AnimeWatchProgress?,
    availability: AnimeEpisodeAvailability?, checking: Boolean, canRetry: Boolean,
    offline: AnimeDetailOffline?, resumeCheckpoint: AnimeWatchProgress?, savedLineOffered: Boolean): AnimeDetailPlayControl? {
    // The catalogue already owns the empty/loading view. A saved record, however, stays discoverable.
    if (episode == null && previous == null) return null
    val next = previous != null && episode != null && episode.id != previous.episodeId
    val replay = !next && previous?.completed == true
    val progress = previous.takeUnless { next }
    val episodeLabel = episode?.let(::animeEpisodeLabel)
        ?: progress?.episodeTitle?.takeIf(String::isNotBlank) ?: "上次观看集"
    val resuming = progress != null && !replay && resumeCheckpoint != null
    val time = progress?.positionMs?.takeIf { resuming && it > 0L }?.let(::animeTime)
    val caption = listOfNotNull(episodeLabel, time?.let { "看至 $it" },
        "上次位置未确认".takeIf { progress != null && !replay && !resuming && episode != null }).joinToString(" · ")
    val playback = when {
        next -> "播放下一集"
        replay -> "重看本集"
        resuming -> "继续观看"
        progress != null -> "播放本集"
        else -> "播放"
    }
    val compactPlayback = when {
        next -> "下一集"
        replay -> "重看"
        resuming -> "继续"
        else -> "播放"
    }
    fun control(title: String, mode: AnimeDetailPlayMode, note: String? = null) = AnimeDetailPlayControl(
        title, when (mode) {
            AnimeDetailPlayMode.PLAY -> compactPlayback
            AnimeDetailPlayMode.FIND -> "找片源"
            AnimeDetailPlayMode.SOURCES -> "线路"
            AnimeDetailPlayMode.UPCOMING -> "未放送"
            AnimeDetailPlayMode.WAIT -> "核对中"
        }, listOfNotNull(caption, note).joinToString(" · "), mode,
        animeWatchFraction(resumeCheckpoint.takeIf {
            resuming && (mode == AnimeDetailPlayMode.PLAY || mode == AnimeDetailPlayMode.FIND)
        }),
    )
    return when {
        offline == AnimeDetailOffline.CHECKING -> control("正在核对离线记录", AnimeDetailPlayMode.WAIT)
        offline == AnimeDetailOffline.READY -> control(playback, AnimeDetailPlayMode.PLAY, "离线")
        offline == AnimeDetailOffline.MISSING -> control(if (episode == null) "查看作品线路" else "查看本集线路",
            AnimeDetailPlayMode.SOURCES, "原离线记录暂不可用")
        savedLineOffered && availability != AnimeEpisodeAvailability.UPCOMING -> control(playback, AnimeDetailPlayMode.PLAY)
        episode == null && checking -> control("正在确认上次观看集", AnimeDetailPlayMode.WAIT)
        episode == null -> control("查看作品线路", AnimeDetailPlayMode.SOURCES, "上次观看集未确认")
        availability == AnimeEpisodeAvailability.UPCOMING -> control("尚未放送", AnimeDetailPlayMode.UPCOMING)
        availability == AnimeEpisodeAvailability.MATCHED -> control(playback, AnimeDetailPlayMode.PLAY)
        availability == AnimeEpisodeAvailability.RECENTLY_FAILED -> if (canRetry)
            control(when {
                next -> "重试播放下一集"
                resuming -> "重试续看"
                else -> "重试播放"
            }, AnimeDetailPlayMode.PLAY)
        else control("查看本集线路", AnimeDetailPlayMode.SOURCES, "线路暂不可用")
        else -> control(when {
            next -> "找片源后看下一集"
            replay -> "找片源后重看"
            resuming -> "找片源后续看"
            else -> "找片源并播放"
        }, AnimeDetailPlayMode.FIND)
    }
}

/** Anime retains the exact episode and availability model; the manga dock owns the presentation. */
@Composable
internal fun AnimeDetailPlayAction(
    subjectId: String,
    control: AnimeDetailPlayControl,
    active: Boolean,
    persistent: Boolean,
    collapseProgress: () -> Float,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit,
    onActivate: () -> Unit,
    onEpisodes: () -> Unit,
    onLines: (() -> Unit)?,
) {
    val percent = control.fraction?.let { (it * 100).roundToInt().coerceIn(0, if (it == 1f) 100 else 99) }
    DetailActionDock(subjectId, backdrop, DetailDockContent(
        title = control.title,
        compactTitle = control.compactTitle,
        caption = control.caption,
        description = listOfNotNull(control.title, control.caption, percent?.let { "上次看到 $it%" }).joinToString("，"),
        fraction = control.fraction,
        percent = percent,
        icon = when (control.mode) {
            AnimeDetailPlayMode.FIND -> Icons.Outlined.Search
            AnimeDetailPlayMode.SOURCES -> Icons.Outlined.Link
            AnimeDetailPlayMode.UPCOMING -> Icons.Outlined.Schedule
            else -> Icons.Filled.PlayArrow
        },
        busy = control.mode == AnimeDetailPlayMode.WAIT,
        clickLabel = if (control.mode == AnimeDetailPlayMode.UPCOMING) "查看放送与本集线路" else control.title,
    ), canActivate = control.mode != AnimeDetailPlayMode.WAIT, active = active, persistent = persistent,
        collapseProgress = collapseProgress, modifier = modifier, onHeightChanged = onHeightChanged,
        optionsLabel = "播放选项", options = listOfNotNull(
            onLines?.let { DetailDockOption("本集线路", onClick = it) },
            DetailDockOption("集数列表", onClick = onEpisodes),
        ), onActivate = onActivate)
}
