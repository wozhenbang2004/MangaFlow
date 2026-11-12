package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

/** The app's ordinary MangaCover with existing flat press, swipe and drag ownership. */
@Composable
internal fun BookshelfBook(
    manga: Manga,
    repository: MangaRepository,
    progress: ReadingProgress?,
    opening: Boolean,
    onOpen: () -> Unit,
    dragging: Boolean,
    deleteArmed: Boolean,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    var rootOrigin by remember(manga.id) { mutableStateOf(Offset.Zero) }
    var dragOffset by remember(manga.id) { mutableStateOf(Offset.Zero) }
    var dragPoint by remember(manga.id) { mutableStateOf(Offset.Zero) }
    val scale = animateFloatAsState(
        targetValue = when {
            !motion -> 1f
            dragging && deleteArmed -> .9f
            dragging -> 1.02f
            pressed -> .97f
            else -> 1f
        },
        animationSpec = if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(),
        label = "collection-cover-press",
    )
    val colors = MaterialTheme.colorScheme
    val readingState = progress?.let {
        "读至 ${it.chapterTitle.ifBlank { "已保存章节" }}，第 ${it.pageIndex + 1} 页" +
            if (it.pageCount > 0) "，共 ${it.pageCount} 页" else ""
    } ?: "尚未阅读"

    Box(modifier.widthIn(min = 48.dp).heightIn(min = 64.dp)
        .onGloballyPositioned { rootOrigin = it.positionInRoot() }
        .pointerInput(manga.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    dragOffset = Offset.Zero
                    dragPoint = rootOrigin + local
                    onDragStart(dragPoint)
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset += amount
                    dragPoint += amount
                    onDragMove(dragPoint)
                },
                onDragEnd = {
                    onDragEnd(dragPoint)
                    dragOffset = Offset.Zero
                },
                onDragCancel = {
                    onDragCancel()
                    dragOffset = Offset.Zero
                },
            )
        }
        .graphicsLayer {
            translationX = dragOffset.x
            translationY = dragOffset.y
            scaleX = scale.value
            scaleY = scale.value
            alpha = if (dragging && deleteArmed) .82f else if (!motion && pressed) .84f else 1f
        }
        .zIndex(if (dragging) 8f else if (pressed) 1f else 0f)
        .semantics(mergeDescendants = true) {
            contentDescription = manga.title
            stateDescription = if (opening) "正在打开，$readingState" else readingState
        }
        .clickable(interactionSource = interaction, indication = null, enabled = !dragging,
            role = Role.Button, onClickLabel = if (progress == null) "开始阅读" else "继续阅读",
            onClick = onOpen)) {
        MangaCover(manga, repository, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            scale = ContentScale.Fit)
        if (opening) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(22.dp)
            .background(colors.surface.copy(alpha = .94f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center) {
            if (motion) CircularProgressIndicator(Modifier.size(13.dp), color = colors.onSurface, strokeWidth = 1.5.dp)
            else Canvas(Modifier.size(13.dp)) {
                val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.onSurface.copy(alpha = .18f),
                    radius = (size.minDimension - stroke.width) / 2f, style = stroke)
                drawArc(colors.onSurface, startAngle = -90f, sweepAngle = 240f, useCenter = false,
                    topLeft = Offset(stroke.width / 2f, stroke.width / 2f),
                    size = Size(size.width - stroke.width, size.height - stroke.width), style = stroke)
            }
        }
    }
}
