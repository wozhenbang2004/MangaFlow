package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookshelfBook(
    manga: Manga,
    repository: MangaRepository,
    progress: ReadingProgress?,
    opening: Boolean,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
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
            .zIndex(if (pressed || opening) 1f else 0f)
            .semantics(mergeDescendants = true) {
                contentDescription = manga.title
                stateDescription = if (opening) "正在打开，$readingState" else readingState
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (progress == null) "开始阅读" else "继续阅读",
                onLongClickLabel = "管理收藏",
                onLongClick = onManage,
                onClick = onOpen,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val bookWidth = (maxWidth - 8.dp).coerceAtLeast(1.dp)
        val bookHeight = (maxHeight - 6.dp).coerceAtLeast(1.dp)
        val topDepth = 2.dp
        val planeHeight = (bookHeight - topDepth).coerceAtLeast(1.dp)
        val pageEdge = 2.dp
        val spineWidth = 3.dp
        val coverWidth = (bookWidth - spineWidth - pageEdge).coerceAtLeast(1.dp)

        Box(
            Modifier.width(bookWidth).height(bookHeight)
                .clearAndSetSemantics { }
                .graphicsLayer {
                    val amount = lift.value
                    translationY = -6.dp.toPx() * amount
                    rotationX = -2f * amount
                    rotationY = -3f * amount
                    rotationZ = -1f * amount
                    transformOrigin = TransformOrigin(0f, 1f)
                    cameraDistance = size.height * 8f
                    alpha = if (!motion && pressed) .82f else 1f
                }
                .shadow(2.dp, RoundedCornerShape(3.dp), clip = false,
                    ambientColor = Color.Black.copy(alpha = .035f),
                    spotColor = Color.Black.copy(alpha = .08f)),
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
                            Color.Black.copy(alpha = .06f), Color.Transparent,
                            Color.Transparent, Color.White.copy(alpha = .025f),
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

/** Quiet shelf depth; the ledge is decoration and owns no touch or accessibility node. */
@Composable
internal fun BookshelfPlank(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.clearAndSetSemantics { }) {
        val topDepth = 3.dp.toPx().coerceAtMost(size.height * .3f)
        val frontHeight = 3.dp.toPx().coerceAtMost(size.height * .3f)
        val frontBottom = topDepth + frontHeight
        drawRect(Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .065f),
            colors.onSurface.copy(alpha = .018f), Color.Transparent),
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
        drawRect(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceVariant),
            startY = topDepth, endY = frontBottom),
            topLeft = Offset(0f, topDepth), size = Size(size.width, frontHeight))
        drawLine(colors.surface.copy(alpha = .85f), Offset(0f, topDepth),
            Offset(size.width, topDepth), .6.dp.toPx())
        drawLine(colors.onSurface.copy(alpha = .07f), Offset(0f, frontBottom),
            Offset(size.width, frontBottom), .5.dp.toPx())
    }
}
