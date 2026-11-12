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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import java.text.BreakIterator
import java.util.Locale

/**
 * Compose adaptation of petargyurov/virtual-bookshelf (Unlicense), commit
 * 3c1073227df161d87eb48e7286bb7748cd22cd4d, bookshelf.css and bookshelf.js:
 * https://github.com/petargyurov/virtual-bookshelf/tree/3c1073227df161d87eb48e7286bb7748cd22cd4d
 *
 * The upstream upright spine, vertical title and shallow paper top remain separate
 * faces. Stable opaque bindings replace its randomized patterns and dimensions;
 * the real cover is a small identity endcap. Press/opening lift keeps the original
 * bottom anchor inside a fixed touch slot.
 */
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
    val dragScale = animateFloatAsState(
        targetValue = if (!motion || !dragging) 1f else if (deleteArmed) .84f else 1.04f,
        animationSpec = if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(),
        label = "bookshelf-drag-scale",
    )
    val lift = animateFloatAsState(
        targetValue = if (!motion) 0f else if (opening) 1f else if (pressed) .45f else 0f,
        animationSpec = if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(),
        label = "bookshelf-pick-up",
    )
    val context = LocalContext.current
    val request = remember(context, repository, manga.id, manga.coverUrl, motion) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val paper = colors.surface
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val shade = .07f + (manga.id.hashCode().ushr(1) % 4) * .025f
    val binding = lerp(colors.surface, colors.onSurface, shade).copy(alpha = 1f)
    val readingState = progress?.let {
        "读至 ${it.chapterTitle.ifBlank { "已保存章节" }}，第 ${it.pageIndex + 1} 页" +
            if (it.pageCount > 0) "，共 ${it.pageCount} 页" else ""
    } ?: "尚未阅读"

    BoxWithConstraints(
        modifier.widthIn(min = 48.dp).heightIn(min = 64.dp)
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
                scaleX = dragScale.value
                scaleY = dragScale.value
                alpha = if (dragging && deleteArmed) .82f else 1f
            }
            .zIndex(if (dragging) 8f else if (pressed || opening) 1f else 0f)
            .semantics(mergeDescendants = true) {
                contentDescription = manga.title
                stateDescription = if (opening) "正在打开，$readingState" else readingState
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !dragging,
                role = Role.Button,
                onClickLabel = if (progress == null) "开始阅读" else "继续阅读",
                onClick = onOpen,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val bookWidth = (maxWidth - 2.dp).coerceAtLeast(1.dp)
        val bookHeight = (maxHeight - 3.dp).coerceAtLeast(1.dp)
        val topDepth = 3.dp
        val planeHeight = (bookHeight - topDepth).coerceAtLeast(1.dp)
        val sideDepth = 2.dp
        val spineWidth = (bookWidth - sideDepth).coerceAtLeast(1.dp)
        val endcapWidth = minOf(32.dp * fontScale.coerceAtMost(1.4f), spineWidth - 12.dp).coerceAtLeast(1.dp)
        val endcapHeight = endcapWidth / .7f
        val titleTop = topDepth + endcapHeight + 17.dp
        val titleHeight = (bookHeight - titleTop - 17.dp).coerceAtLeast(1.dp)

        Box(
            Modifier.width(bookWidth).height(bookHeight)
                .clearAndSetSemantics { }
                .graphicsLayer {
                    val amount = lift.value
                    translationY = -5.dp.toPx() * amount
                    rotationX = -2f * amount
                    rotationY = -3f * amount
                    rotationZ = -1f * amount
                    transformOrigin = TransformOrigin(0f, 1f)
                    cameraDistance = size.height * 8f
                    alpha = if (!motion && pressed) .82f else 1f
                }
                .shadow(2.dp, RoundedCornerShape(2.dp), clip = false,
                    ambientColor = Color.Black.copy(alpha = .025f),
                    spotColor = Color.Black.copy(alpha = .07f)),
        ) {
            // .spine is the visible book face, not a cropped strip of cover artwork.
            Box(Modifier.align(Alignment.BottomStart).width(spineWidth).height(planeHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(binding)
                .background(Brush.horizontalGradient(listOf(
                    ink.copy(alpha = .12f), Color.Transparent,
                    Color.Transparent, paper.copy(alpha = .16f), ink.copy(alpha = .055f),
                ))))

            // Retain each actual cover as a small, complete identity mark at the spine's head.
            Box(Modifier.align(Alignment.TopCenter).padding(top = topDepth + 7.dp)
                .width(endcapWidth).height(endcapHeight)
                .clip(RoundedCornerShape(1.dp)).background(paper), contentAlignment = Alignment.Center) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            }
            BookshelfSpineTitle(manga.title, titleHeight,
                Modifier.align(Alignment.TopStart).padding(top = titleTop, start = 7.dp, end = 7.dp)
                    .width((spineWidth - 14.dp).coerceAtLeast(1.dp)).height(titleHeight))

            // .top and the closed cover edge give the spine a little physical depth.
            Canvas(Modifier.fillMaxSize()) {
                val depth = topDepth.toPx()
                val side = sideDepth.toPx()
                val top = Path().apply {
                    moveTo(0f, depth)
                    lineTo(size.width - side, depth)
                    lineTo(size.width, 0f)
                    lineTo(side, 0f)
                    close()
                }
                drawPath(top, Brush.verticalGradient(listOf(paper, colors.surfaceVariant), endY = depth))
                drawLine(ink.copy(alpha = .12f), Offset(side, depth * .5f),
                    Offset(size.width - side, depth * .5f), .5.dp.toPx())
                drawRect(lerp(binding, ink, .16f), topLeft = Offset(size.width - side, depth),
                    size = Size(side, size.height - depth))
                drawLine(ink.copy(alpha = .10f), Offset(3.dp.toPx(), depth),
                    Offset(3.dp.toPx(), size.height), .6.dp.toPx())
                drawLine(ink.copy(alpha = .10f), Offset(5.dp.toPx(), size.height - 9.dp.toPx()),
                    Offset(size.width - side - 5.dp.toPx(), size.height - 9.dp.toPx()), .6.dp.toPx())
            }
            if (opening) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp)
                    .size(22.dp).background(paper.copy(alpha = .94f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center) {
                    if (motion) CircularProgressIndicator(Modifier.size(13.dp), color = ink, strokeWidth = 1.5.dp)
                    else Canvas(Modifier.size(13.dp)) {
                        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(ink.copy(alpha = .18f), radius = (size.minDimension - stroke.width) / 2f,
                            style = stroke)
                        drawArc(ink, startAngle = -90f, sweepAngle = 240f, useCenter = false,
                            topLeft = Offset(stroke.width / 2f, stroke.width / 2f),
                            size = Size(size.width - stroke.width, size.height - stroke.width), style = stroke)
                    }
                }
            }
        }
    }
}

/** Upright grapheme columns follow the source's right-to-left vertical title placement. */
@Composable
private fun BookshelfSpineTitle(title: String, availableHeight: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val lineHeight = 17.sp
    val rows = with(density) { (availableHeight.toPx() / lineHeight.toPx()).toInt().coerceAtLeast(1) }
    val columns = remember(title, rows) {
        val value = title.trim().replace(Regex("\\s+"), " ")
        val iterator = BreakIterator.getCharacterInstance(Locale.getDefault()).apply { setText(value) }
        val characters = buildList {
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                add(value.substring(start, end))
                start = end
                end = iterator.next()
            }
        }
        val visible = characters.take(rows * 2).toMutableList()
        if (characters.size > visible.size && visible.isNotEmpty()) visible[visible.lastIndex] = "…"
        visible.chunked(rows).map { it.joinToString("\n") }.reversed()
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
        columns.forEach { column ->
            Text(column, color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp, lineHeight = lineHeight, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = rows, overflow = TextOverflow.Clip)
        }
    }
}

/** Quiet shelf depth; the ledge is decoration and owns no touch or accessibility node. */
@Composable
internal fun BookshelfPlank(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.clearAndSetSemantics { }) {
        val topDepth = 3.dp.toPx().coerceAtMost(size.height * .3f)
        val frontHeight = 3.dp.toPx().coerceAtMost(size.height * .3f)
        val frontBottom = topDepth + frontHeight
        drawRect(Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .07f),
            colors.onSurface.copy(alpha = .02f), Color.Transparent),
            startY = frontBottom, endY = size.height),
            topLeft = Offset(0f, frontBottom), size = Size(size.width, size.height - frontBottom))
        val top = Path().apply {
            moveTo(3.dp.toPx(), 0f)
            lineTo(size.width - 3.dp.toPx(), 0f)
            lineTo(size.width, topDepth)
            lineTo(0f, topDepth)
            close()
        }
        drawPath(top, Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surface), endY = topDepth))
        drawLine(colors.onSurface.copy(alpha = .08f), Offset(3.dp.toPx(), 0f),
            Offset(size.width - 3.dp.toPx(), 0f), 1.dp.toPx())
        drawRect(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceVariant),
            startY = topDepth, endY = frontBottom),
            topLeft = Offset(0f, topDepth), size = Size(size.width, frontHeight))
        drawLine(colors.surface.copy(alpha = .85f), Offset(0f, topDepth),
            Offset(size.width, topDepth), .6.dp.toPx())
        drawLine(colors.onSurface.copy(alpha = .07f), Offset(0f, frontBottom),
            Offset(size.width, frontBottom), .5.dp.toPx())
    }
}
