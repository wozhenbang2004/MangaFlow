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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * Compose adaptation of petargyurov/virtual-bookshelf (Unlicense), commit
 * 3c1073227df161d87eb48e7286bb7748cd22cd4d, bookshelf.css and bookshelf.js:
 * https://github.com/petargyurov/virtual-bookshelf/tree/3c1073227df161d87eb48e7286bb7748cd22cd4d
 *
 * The shallow spine, full cover and top paper edge remain separate faces. Every
 * book now faces forward at the same proportion; the upstream hover lift becomes
 * press/opening feedback inside a fixed touch slot. No cropped spine title or tint
 * obscures the artwork.
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
        val bookWidth = (maxWidth - 4.dp).coerceAtLeast(1.dp)
        val bookHeight = (maxHeight - 6.dp).coerceAtLeast(1.dp)
        val topDepth = 4.dp
        val planeHeight = (bookHeight - topDepth).coerceAtLeast(1.dp)
        val pageEdge = 5.dp
        val spineWidth = 4.dp
        val coverWidth = (bookWidth - spineWidth - pageEdge).coerceAtLeast(1.dp)

        Box(
            Modifier.width(bookWidth).height(bookHeight)
                .clearAndSetSemantics { }
                .graphicsLayer {
                    val amount = lift.value
                    translationY = -6.dp.toPx() * amount
                    rotationX = -2f * amount
                    rotationY = -7f - 3f * amount
                    rotationZ = -1f * amount
                    transformOrigin = TransformOrigin(0f, 1f)
                    cameraDistance = size.height * 8f
                    alpha = if (!motion && pressed) .82f else 1f
                }
                .shadow(4.dp, RoundedCornerShape(3.dp), clip = false,
                    ambientColor = Color.Black.copy(alpha = .035f),
                    spotColor = Color.Black.copy(alpha = .10f)),
        ) {
            // A complete front cover with a fine binding and paper edge.
            Box(
                Modifier.align(Alignment.BottomStart).offset(x = spineWidth)
                    .width(coverWidth).height(planeHeight)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(paper)
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(listOf(
                            Color.Black.copy(alpha = .16f), Color.Transparent,
                            Color.Transparent, Color.White.copy(alpha = .07f),
                        )))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(manga.title, modifier = Modifier.padding(6.dp), color = ink.copy(alpha = .65f),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.BottomStart).width(spineWidth).height(planeHeight)
                    .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                    .background(colors.surfaceVariant),
            ) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                    ink.copy(alpha = .16f), Color.Transparent, paper.copy(alpha = .12f),
                ))))
            }

            // A shallow paper block and fore-edge, consistently aligned to the plank.
            Canvas(Modifier.fillMaxSize()) {
                val depth = topDepth.toPx()
                val edge = pageEdge.toPx()
                val spine = spineWidth.toPx()
                val top = Path().apply {
                    moveTo(0f, depth)
                    lineTo(size.width - edge, depth)
                    lineTo(size.width, 0f)
                    lineTo(2.dp.toPx(), 0f)
                    close()
                }
                drawPath(top, Brush.verticalGradient(listOf(colors.surfaceVariant, paper), endY = depth))
                drawLine(ink.copy(alpha = .08f), Offset(2.dp.toPx(), depth * .45f),
                    Offset(size.width - edge, depth * .45f), .5.dp.toPx())
                drawRect(Brush.horizontalGradient(listOf(paper, colors.surfaceVariant),
                    startX = size.width - edge, endX = size.width),
                    topLeft = Offset(size.width - edge, depth),
                    size = Size(edge, size.height - depth))
                repeat(3) { line ->
                    val x = size.width - edge + edge * (line + 1) / 4f
                    drawLine(ink.copy(alpha = .09f), Offset(x, depth + 1.dp.toPx()),
                        Offset(x, size.height - 1.dp.toPx()), .4.dp.toPx())
                }
                drawLine(ink.copy(alpha = .10f), Offset(spine, depth),
                    Offset(spine, size.height), .5.dp.toPx())
            }

            if (progress != null) {
                Canvas(Modifier.align(Alignment.TopEnd).offset(x = (-9).dp, y = topDepth)
                    .width(6.dp).height(17.dp)) {
                    val ribbon = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width / 2f, size.height - 3.dp.toPx())
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(ribbon, ink.copy(alpha = .7f))
                }
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

/** Shared case back and continuous side boards, kept outside book hit/drag bounds. */
@Composable
internal fun BookshelfCabinetBay(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    Box(modifier.drawBehind {
        val wall = 7.dp.toPx()
        val recess = 12.dp.toPx()
        drawRect(colors.surfaceContainerLow)
        drawRect(Brush.verticalGradient(listOf(ink.copy(alpha = .065f), Color.Transparent),
            endY = 24.dp.toPx()))
        drawRect(Brush.horizontalGradient(listOf(ink.copy(alpha = .10f), Color.Transparent),
            startX = wall, endX = wall + recess), topLeft = Offset(wall, 0f),
            size = Size(recess, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = .09f)),
            startX = size.width - wall - recess, endX = size.width - wall),
            topLeft = Offset(size.width - wall - recess, 0f), size = Size(recess, size.height))
        drawRect(Brush.horizontalGradient(listOf(colors.surfaceContainerHigh, colors.surface)),
            size = Size(wall, size.height))
        drawRect(Brush.horizontalGradient(listOf(colors.surface, colors.surfaceContainerHigh)),
            topLeft = Offset(size.width - wall, 0f), size = Size(wall, size.height))
        drawLine(colors.surface.copy(alpha = .9f), Offset(wall, 0f), Offset(wall, size.height), .7.dp.toPx())
        drawLine(ink.copy(alpha = .08f), Offset(size.width - wall, 0f),
            Offset(size.width - wall, size.height), .7.dp.toPx())
    }, content = content)
}

/** Quiet shelf depth; the ledge is decoration and owns no touch or accessibility node. */
@Composable
internal fun BookshelfPlank(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.clearAndSetSemantics { }) {
        val topDepth = 8.dp.toPx().coerceAtMost(size.height * .4f)
        val frontHeight = 6.dp.toPx().coerceAtMost(size.height * .3f)
        val frontBottom = topDepth + frontHeight
        drawRect(Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .14f),
            colors.onSurface.copy(alpha = .04f), Color.Transparent),
            startY = frontBottom, endY = size.height),
            topLeft = Offset(0f, frontBottom), size = Size(size.width, size.height - frontBottom))
        val top = Path().apply {
            moveTo(8.dp.toPx(), 0f)
            lineTo(size.width - 8.dp.toPx(), 0f)
            lineTo(size.width, topDepth)
            lineTo(0f, topDepth)
            close()
        }
        drawPath(top, Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surface), endY = topDepth))
        drawLine(colors.onSurface.copy(alpha = .08f), Offset(8.dp.toPx(), 0f),
            Offset(size.width - 8.dp.toPx(), 0f), 1.dp.toPx())
        drawRect(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceVariant),
            startY = topDepth, endY = frontBottom),
            topLeft = Offset(0f, topDepth), size = Size(size.width, frontHeight))
        drawLine(colors.surface.copy(alpha = .85f), Offset(0f, topDepth),
            Offset(size.width, topDepth), .6.dp.toPx())
        drawLine(colors.onSurface.copy(alpha = .07f), Offset(0f, frontBottom),
            Offset(size.width, frontBottom), .5.dp.toPx())
    }
}
