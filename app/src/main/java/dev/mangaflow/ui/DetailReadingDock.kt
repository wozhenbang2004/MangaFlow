package dev.mangaflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailReadingDock(
    mangaId: String,
    backdrop: HazeState,
    resume: ReadingProgress?,
    firstChapter: Chapter?,
    loading: Boolean,
    hidden: Boolean,
    collapseProgress: Float = 0f,
    active: Boolean,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
    onContinue: (ReadingProgress) -> Unit,
    onRead: (Chapter) -> Unit,
    onRestart: (Chapter) -> Unit,
    onContents: () -> Unit,
) {
    val canRead = resume != null || firstChapter != null
    val interaction = remember { MutableInteractionSource() }
    var actions by remember(mangaId) { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    // The same reversible scroll progress owns size, position, content and material shape.
    val collapse = if (motion) collapseProgress.coerceIn(0f, 1f)
        else if (collapseProgress < .5f) 0f else 1f
    val minimumHeight = lerp(56.dp, 36.dp, collapse)
    val visibility by animateFloatAsState(if (hidden && !actions) 0f else 1f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing),
        label = "reading-dock")
    val fraction = detailReadingFraction(resume)
    val displayedProgress by animateFloatAsState(fraction ?: 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "dock-saved-progress")
    val percent = detailReadingPercent(resume)
    val chapterTitle = if (resume != null) resume.chapterTitle.ifBlank { "上次章节" } else firstChapter?.title.orEmpty()
    val actionTitle = if (resume != null) "继续阅读" else "开始阅读"
    val subtitle = when {
        resume != null -> percent?.let { "上次读至 $it%" } ?: "继续上次位置"
        firstChapter != null -> ""
        loading -> "正在读取章节"
        else -> "暂无可读章节"
    }
    if (visibility > .01f) BoxWithConstraints(modifier.fillMaxWidth()) {
        val fullWidth = minOf(maxWidth, 280.dp + 40.dp * (fontScale - 1f).coerceIn(0f, 1f))
        val compactWidth = minOf(fullWidth, 104.dp * fontScale.coerceAtMost(1.6f))
        val width = lerp(fullWidth, compactWidth, collapse)
        Box(Modifier.align(BiasAlignment(collapse, 1f)).width(width).heightIn(min = 48.dp)
            .onSizeChanged { onHeightChanged(it.height) }.graphicsLayer {
                alpha = visibility
                translationY = (1f - visibility) * 86.dp.toPx()
            }.motionInputGate(active && visibility > .9f)
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = active && canRead,
                onLongClickLabel = "阅读选项", onLongClick = { actions = true },
                onClick = { if (resume != null) onContinue(resume) else firstChapter?.let(onRead) })
            .pointerInput(mangaId, canRead, active) {
                if (canRead && active) {
                    var travel = 0f
                    detectVerticalDragGestures(onDragStart = { travel = 0f }, onVerticalDrag = { change, dy ->
                        travel += dy
                        if (travel < -24.dp.toPx()) { change.consume(); actions = true }
                    })
                }
            }.motionPress(interaction, enabled = canRead, pressedScale = .985f)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().align(Alignment.Center),
                cornerRadius = lerp(28.dp, 18.dp, collapse), role = GlassSurfaceRole.READING_DOCK,
                interactionSource = interaction, trackPointer = true,
                softCard = true) {
                Row(Modifier.fillMaxWidth().heightIn(min = minimumHeight)
                    .padding(horizontal = lerp(20.dp, 10.dp, collapse), vertical = 4.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOf(actionTitle, chapterTitle, subtitle)
                            .filter(String::isNotBlank).joinToString("，")
                    }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start) {
                    val progressColor = MaterialTheme.colorScheme.primary
                    Box(Modifier.size(lerp(32.dp, 20.dp, collapse)), contentAlignment = Alignment.Center) {
                        if (fraction != null) Canvas(Modifier.matchParentSize().padding(1.dp)) {
                            val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                            drawArc(progressColor.copy(alpha = .14f), -90f, 360f, false, style = stroke)
                            drawArc(progressColor.copy(alpha = .85f), -90f, 360f * displayedProgress, false, style = stroke)
                        }
                        Icon(Icons.Filled.PlayArrow, null, tint = progressColor,
                            modifier = Modifier.size(lerp(22.dp, 16.dp, collapse)))
                    }
                    Spacer(Modifier.width(lerp(14.dp, 6.dp, collapse)))
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp * (1f - collapse))) {
                        Text(chapterTitle.ifBlank { if (loading) "正在读取章节" else "暂无可读章节" },
                            color = DetailForeground, fontSize = (15f - 3f * collapse).sp,
                            lineHeight = (22f - 4f * collapse).sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (canRead && collapse < 1f) {
                            val lineHeight = with(density) { 16.sp.toDp() }
                            Box(Modifier.height(lineHeight * (1f - collapse)).clipToBounds()
                                .graphicsLayer { alpha = (1f - collapse * 2f).coerceIn(0f, 1f) }) {
                                Text(actionTitle,
                                    color = DetailSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (percent != null && collapse < 1f) {
                        Spacer(Modifier.width(12.dp * (1f - collapse)))
                        Box(Modifier.width(42.dp * fontScale * (1f - collapse)).clipToBounds()
                            .graphicsLayer { alpha = (1f - collapse * 2f).coerceIn(0f, 1f) },
                            contentAlignment = Alignment.CenterEnd) {
                            Text("$percent%", color = DetailSecondary, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = active && actions) { actions = false }
    if (actions && active) MotionBottomSheet(onDismissRequest = { actions = false },
        backdrop = backdrop,
        containerColor = DetailBackground, contentColor = DetailForeground) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            if (firstChapter != null) DockOption("从第一话开始") { actions = false; dismiss(); onRestart(firstChapter) }
            DockOption("章节列表") { actions = false; dismiss(); onContents() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockOption(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).combinedClickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DetailForeground, fontSize = 15.sp)
    }
}
