package dev.mangaflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.data.Manga
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionSwap
import kotlinx.coroutines.CancellationException

/** One local IO snapshot for the catalog, rather than parsing stored JSON in every tile. */
@Composable
internal fun rememberChapterPositions(
    manga: Manga,
    library: LibraryStore,
    active: Boolean = true,
): Map<String, ReadingProgress>? {
    val revision by library.chapterHistoryRevision.collectAsStateWithLifecycle()
    var positions by remember(manga.id, library, revision) {
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

internal fun chapterReadingStatus(progress: ReadingProgress?, known: Boolean = true): String = when {
    progress != null && progress.pageCount > 0 && progress.pageIndex >= progress.pageCount - 1 ->
        "已读完，共 ${progress.pageCount} 页"
    progress != null && progress.pageCount > 0 ->
        "已读至第 ${(progress.pageIndex + 1).coerceIn(1, progress.pageCount)} 页，共 ${progress.pageCount} 页"
    !known -> "阅读记录尚未载入"
    else -> "未读"
}

/** Adapt the existing reading dock's progress ring; completion has its own circled check. */
@Composable
internal fun ChapterReadMark(progress: ReadingProgress?, known: Boolean = true, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val count = progress?.pageCount ?: 0
    val fraction = if (progress != null && count > 0) ((progress.pageIndex + 1).toFloat() / count).coerceIn(0f, 1f) else 0f
    val complete = count > 0 && progress != null && progress.pageIndex >= count - 1
    MotionSwap(complete, modifier.size(14.dp).semantics {
        contentDescription = chapterReadingStatus(progress, known)
    }) { completed ->
        if (completed) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = color.copy(alpha = .72f))
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
