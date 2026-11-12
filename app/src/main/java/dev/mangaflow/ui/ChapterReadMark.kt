package dev.mangaflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
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
internal fun ChapterReadMark(progress: ReadingProgress?, known: Boolean = true, modifier: Modifier = Modifier) {
    val fraction = detailReadingFraction(progress) ?: return
    val color = MaterialTheme.colorScheme.primary
    val complete = fraction == 1f
    MotionSwap(complete, modifier.size(14.dp).semantics {
        contentDescription = chapterReadingStatus(progress, known)
    }) { completed ->
        if (completed) Icon(Icons.Outlined.DoneAll, null, Modifier.size(14.dp), tint = color.copy(alpha = .72f))
        else Canvas(Modifier.size(14.dp)) {
            val stroke = 1.4.dp.toPx()
            val inset = stroke / 2f
            drawCircle(color.copy(alpha = if (known || progress != null) .18f else .07f),
                radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (fraction > 0f) drawArc(color.copy(alpha = .72f), -90f, 360f * fraction, false,
                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun ChapterReadingStatus(progress: ReadingProgress?, known: Boolean = true) {
    if (progress == null) return
    val completed = detailReadingFraction(progress) == 1f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChapterReadMark(progress, known, Modifier.size(12.dp))
        Text(if (completed) "已读完" else detailReadingPercent(progress)?.let { "$it% · 阅读中" } ?: "阅读中",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
