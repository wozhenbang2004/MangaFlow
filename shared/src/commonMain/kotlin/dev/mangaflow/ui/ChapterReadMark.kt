package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.data.Manga
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionSwap
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** One local IO snapshot for the catalog, rather than parsing stored JSON in every tile. */
@Composable
internal fun rememberChapterPositions(
    manga: Manga,
    library: LibraryStore,
    active: Boolean = true,
): Map<String, ReadingProgress>? {
    val revision by library.chapterHistoryRevision.collectAsStateWithLifecycle()
    var positions by remember(manga.id, library) {
        mutableStateOf<Map<String, ReadingProgress>?>(null)
    }
    LaunchedEffect(manga.id, library, revision, active) {
        if (!active) return@LaunchedEffect
        try {
            positions = library.chapterHistoryFor(manga).associateBy(ReadingProgress::chapterId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Unknown is distinct from an empty, successfully loaded history.
            positions = null
        }
    }
    return positions
}

internal fun detailReadingFraction(progress: ReadingProgress?): Float? = progress?.takeIf { it.pageCount > 0 }?.let {
    ((it.pageIndex.coerceAtLeast(0) + 1).toFloat() / it.pageCount).coerceIn(0f, 1f)
}

internal fun detailReadingPercent(progress: ReadingProgress?): Int? = detailReadingFraction(progress)?.let {
    (it * 100).roundToInt().coerceIn(0, if (it == 1f) 100 else 99)
}

internal fun chapterReadingStatus(progress: ReadingProgress?, known: Boolean = true): String = when {
    progress != null && detailReadingFraction(progress) == 1f ->
        "已读完，共 ${progress.pageCount} 页"
    progress != null && progress.pageCount > 0 ->
        "阅读中，${detailReadingPercent(progress)}%，已读至第 ${(progress.pageIndex + 1).coerceIn(1, progress.pageCount)} 页，共 ${progress.pageCount} 页"
    progress != null -> "阅读中"
    !known -> "阅读记录尚未载入"
    else -> "未读"
}

/** Keep the actual saved-page ring; unread chapters do not gain a decorative empty ring. */
@Composable
internal fun ChapterReadMark(progress: ReadingProgress?, known: Boolean = true, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    if (progress == null) return
    val fraction = detailReadingFraction(progress)
    val color = MaterialTheme.colorScheme.primary
    val complete = fraction == 1f
    val ringStroke = (size.value * .1f).coerceAtLeast(1f).dp
    val dotRadius = (size.value * .13f).dp
    MotionSwap(complete, modifier.size(size).semantics {
        contentDescription = chapterReadingStatus(progress, known)
    }) { completed ->
        if (completed) Icon(Icons.Outlined.DoneAll, null, Modifier.size(size), tint = color.copy(alpha = .72f))
        else Canvas(Modifier.size(size)) {
            val stroke = ringStroke.toPx()
            val inset = stroke / 2f
            drawCircle(color.copy(alpha = .18f),
                radius = (this.size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (fraction == null) drawCircle(color.copy(alpha = .72f), radius = dotRadius.toPx())
            else if (fraction > 0f) drawArc(color.copy(alpha = .72f), -90f, 360f * fraction, false,
                topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Volume captions share the same glyph; the full saved-page status stays in semantics. */
@Composable
internal fun ChapterReadingStatus(progress: ReadingProgress?, known: Boolean = true) {
    ChapterReadMark(progress, known)
}
