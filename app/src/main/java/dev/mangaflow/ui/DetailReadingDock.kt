package dev.mangaflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailReadingDock(
    mangaId: String,
    backdrop: HazeState,
    resume: ReadingProgress?,
    firstChapter: Chapter?,
    loading: Boolean,
    hidden: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onContinue: (ReadingProgress) -> Unit,
    onRead: (Chapter) -> Unit,
    onRestart: (Chapter) -> Unit,
    onContents: () -> Unit,
) {
    val canRead = resume != null || firstChapter != null
    val interaction = remember { MutableInteractionSource() }
    var actions by remember(mangaId) { mutableStateOf(false) }
    val visibility by animateFloatAsState(if (hidden && !actions) 0f else 1f,
        tween(if (LocalMotionEnabled.current) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing),
        label = "reading-dock")
    val fraction = resume?.takeIf { it.pageCount > 0 }?.let {
        ((it.pageIndex + 1).toFloat() / it.pageCount).coerceIn(0f, 1f)
    }
    val percent = fraction?.let { (it * 100).roundToInt() }
    val subtitle = when {
        resume != null -> resume.chapterTitle + (percent?.let { " · 上次读至 $it%" } ?: " · 继续上次位置")
        firstChapter != null -> firstChapter.title
        loading -> "正在读取章节"
        else -> "暂无可读章节"
    }
    if (visibility > .01f) LiquidGlassSurface(backdrop,
        modifier.graphicsLayer {
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
            }.motionPress(interaction, enabled = canRead, pressedScale = .985f),
        cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL,
        interactionSource = interaction, trackPointer = true) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${if (resume != null) "继续阅读" else "开始阅读"}，$subtitle" },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, tint = DetailForeground, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (resume != null) "继续阅读" else "开始阅读", color = DetailForeground,
                    fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = DetailSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (fraction != null) {
                val color = DetailForeground
                Canvas(Modifier.padding(start = 12.dp).size(25.dp)) {
                    val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(color.copy(alpha = .12f), -90f, 360f, false, style = stroke)
                    drawArc(color.copy(alpha = .8f), -90f, 360f * fraction, false, style = stroke)
                }
            }
        }
    }
    BackHandler(enabled = active && actions) { actions = false }
    if (actions && active) MotionBottomSheet(onDismissRequest = { actions = false },
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
