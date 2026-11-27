package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun DetailReadingDock(
    mangaId: String,
    backdrop: HazeState,
    resume: ReadingProgress?,
    firstChapter: Chapter?,
    loading: Boolean,
    hidden: Boolean,
    collapseProgress: () -> Float = { 0f },
    active: Boolean,
    persistent: Boolean = false,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
    onContinue: (ReadingProgress) -> Unit,
    onRead: (Chapter) -> Unit,
    onRestart: (Chapter) -> Unit,
    onContents: () -> Unit,
    preferredResumeChapter: Chapter? = null,
    onContinuePreferred: (() -> Unit)? = null,
    cloudProgress: CopyProgressHintState? = null,
    onAdoptCloudProgress: (() -> Unit)? = null,
    onRetryCloudProgress: (() -> Unit)? = null,
) {
    val preferred = preferredResumeChapter?.takeIf { onContinuePreferred != null }
    val displayedResume = resume?.takeIf { preferred == null || it.chapterId == preferred.id }
    val canRead = preferred != null || resume != null || firstChapter != null
    // The catalogue already shows loading and retry while there is no actual reading target.
    if (platformKind == PlatformKind.ANDROID && !canRead) return
    val chapterTitle = preferred?.title?.ifBlank { "拷贝章节" }
        ?: if (resume != null) resume.chapterTitle.ifBlank { "上次章节" } else firstChapter?.title.orEmpty()
    val resuming = preferred != null || resume != null
    val actionTitle = if (resuming) "继续阅读" else "开始阅读"
    val percent = detailReadingPercent(displayedResume)
    val subtitle = when {
        preferred != null -> "已采用拷贝章节"
        resume != null -> percent?.let { "上次读至 $it%" } ?: "继续上次位置"
        firstChapter != null -> ""
        loading -> "正在读取章节"
        else -> "暂无可读章节"
    }
    val options = buildList {
        if (firstChapter != null) add(DetailDockOption("从第一话开始", "从头阅读") { onRestart(firstChapter) })
        add(DetailDockOption("章节列表", onClick = onContents))
    }
    DetailActionDock(mangaId, backdrop, DetailDockContent(
        title = if (canRead) actionTitle else subtitle,
        compactTitle = if (!canRead) subtitle else if (resuming) "继续" else "阅读",
        caption = chapterTitle,
        description = listOf(actionTitle.takeIf { canRead }.orEmpty(), chapterTitle, subtitle)
            .filter(String::isNotBlank).joinToString("，"),
        fraction = detailReadingFraction(displayedResume), percent = percent,
    ), canActivate = canRead, active = active, hidden = hidden, collapseProgress = collapseProgress,
        persistent = persistent, modifier = modifier, onHeightChanged = onHeightChanged,
        optionsLabel = "阅读选项", options = options, onActivate = {
            if (preferred != null) onContinuePreferred?.invoke()
            else if (resume != null) onContinue(resume)
            else firstChapter?.let(onRead)
        }, trailing = { collapse ->
            cloudProgress?.let { hint ->
                CopyProgressHint(hint, active = active,
                    revealFraction = (1f - collapse * 2f).coerceIn(0f, 1f),
                    onAdopt = onAdoptCloudProgress, onRetry = onRetryCloudProgress)
            }
        })
}

internal data class DetailDockContent(
    val title: String,
    val compactTitle: String,
    val caption: String,
    val description: String,
    val fraction: Float? = null,
    val percent: Int? = null,
    val icon: ImageVector = Icons.Filled.PlayArrow,
    val busy: Boolean = false,
    val clickLabel: String? = null,
)

internal data class DetailDockOption(val label: String, val desktopLabel: String = label, val onClick: () -> Unit)

/** The original manga dock's layout, optical surface, progress and input; media owners supply only real actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailActionDock(
    ownerKey: String,
    backdrop: HazeState,
    content: DetailDockContent,
    canActivate: Boolean,
    active: Boolean,
    hidden: Boolean = false,
    collapseProgress: () -> Float = { 0f },
    persistent: Boolean = false,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
    optionsLabel: String,
    options: List<DetailDockOption>,
    onActivate: () -> Unit,
    trailing: @Composable (Float) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    var actions by remember(ownerKey) { mutableStateOf(false) }
    LaunchedEffect(ownerKey, active) { if (!active) actions = false }
    var pull by remember(ownerKey) { mutableFloatStateOf(0f) }
    var pullReturn by remember(ownerKey) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    val desktop = platformKind == PlatformKind.DESKTOP
    val desktopActions = if (desktop) options.map { option ->
        DesktopMenuAction(option.desktopLabel, enabled = active, onClick = option.onClick)
    } else emptyList()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    // Read scroll progress only in the dock, not the whole detail scene.
    val collapse = if (desktop || persistent) 0f else if (motion) collapseProgress().coerceIn(0f, 1f)
        else if (collapseProgress() < .5f) 0f else 1f
    val minimumHeight = lerp(56.dp, 36.dp, collapse)
    val shown = desktop || persistent || !hidden || actions
    val visibility by animateFloatAsState(if (shown) 1f else 0f,
        if (!motion) snap() else if (shown) spring(MangaMotion.PressDamping, MangaMotion.GestureStiffness)
        else tween(MangaMotion.Quick, easing = MangaMotion.ExitEasing), label = "detail-dock")
    val displayedProgress by animateFloatAsState(content.fraction ?: 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "dock-saved-progress")
    val percent = content.percent
    if (visibility > .01f) BoxWithConstraints(modifier.fillMaxWidth()) {
        val fullWidth = if (persistent) maxWidth else minOf(maxWidth, 280.dp + 40.dp * (fontScale - 1f).coerceIn(0f, 1f))
        val compactWidth = minOf(fullWidth, 104.dp * fontScale.coerceAtMost(1.6f))
        val width = lerp(fullWidth, compactWidth, collapse)
        val available = active && shown && !actions && visibility > .4f
        val enabled = available && canActivate
        val hasTouchOptions = !desktop && options.isNotEmpty()
        val readTarget = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClickLabel = content.clickLabel, onLongClickLabel = optionsLabel,
                onLongClick = if (hasTouchOptions) ({ actions = true }) else null,
                onClick = onActivate)
            .then(if (!hasTouchOptions || !enabled) Modifier else Modifier.pointerInput(ownerKey, motion) {
                var travel = 0f
                var armed = false
                var tracking = false
                val threshold = 28.dp.toPx()
                fun release() {
                    if (!tracking) return
                    tracking = false
                    armed = false
                    if (motion && pull > 0f) {
                        val start = pull
                        pullReturn = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            animate(start, 0f, animationSpec = spring(
                                MangaMotion.PressDamping, MangaMotion.PressStiffness)) { value, _ ->
                                pull = value.coerceIn(0f, 1f)
                            }
                        }
                    } else pull = 0f
                }
                try {
                    detectVerticalDragGestures(
                        onDragStart = {
                            pullReturn?.cancel()
                            travel = pull * threshold
                            armed = false
                            tracking = true
                        },
                        onVerticalDrag = { change, delta ->
                            travel = (travel - delta).coerceIn(0f, threshold * 1.2f)
                            pull = (travel / threshold).coerceAtMost(1f)
                            if (!armed && travel >= threshold) {
                                armed = true
                                feedback.perform(FeelEvent.Threshold)
                            } else if (travel < threshold * .78f) armed = false
                            if (travel > 0f) change.consume()
                        },
                        onDragEnd = {
                            val open = armed
                            release()
                            if (open) actions = true
                        },
                        onDragCancel = { release() },
                    )
                } finally { release() }
            })
            .motionPress(interaction, enabled = enabled, pressedScale = .985f)
            .semantics(mergeDescendants = true) { contentDescription = content.description }
        Box(Modifier.align(BiasAlignment(collapse, 1f)).width(width)
            .onSizeChanged { onHeightChanged(it.height) }.graphicsLayer {
                alpha = visibility.coerceIn(0f, 1f)
                translationY = (1f - visibility) * minOf(size.height * .55f, 28.dp.toPx())
            }.motionInputGate(active && shown && visibility > .4f)) {
            DesktopContextMenuArea(desktopActions) {
                Box(readTarget, contentAlignment = Alignment.Center) {
                    LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().graphicsLayer {
                        val reach = if (motion) pull else 0f
                        transformOrigin = TransformOrigin(.5f, 1f)
                        translationY = -5.dp.toPx() * reach
                        scaleX = 1f - .015f * reach
                        scaleY = 1f + .045f * reach
                    },
                        cornerRadius = lerp(28.dp, 18.dp, collapse), role = GlassSurfaceRole.READING_DOCK,
                        interactionSource = interaction.takeIf { enabled }, preferSource = true,
                        softCard = true) {
                        Row(Modifier.fillMaxWidth().heightIn(min = minimumHeight)
                            .padding(horizontal = lerp(16.dp, 10.dp, collapse), vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start) {
                            val progressColor = MaterialTheme.colorScheme.primary
                            Box(Modifier.size(lerp(35.dp, 20.dp, collapse))
                                .background(progressColor.copy(alpha = .105f * (1f - collapse)), CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (content.fraction != null) Canvas(Modifier.matchParentSize().padding(1.dp)) {
                                    val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
                                    drawArc(progressColor.copy(alpha = .12f), -90f, 360f, false, style = stroke)
                                    drawArc(progressColor, -90f, 360f * displayedProgress, false, style = stroke)
                                }
                                if (content.busy) MotionBusyIndicator(Modifier.size(lerp(18.dp, 13.dp, collapse)),
                                    color = DetailSecondary, strokeWidth = 1.5.dp)
                                else {
                                    val markSize = lerp(22.dp, 16.dp, collapse)
                                    Icon(content.icon, null, tint = if (enabled) progressColor else DetailMuted,
                                        modifier = Modifier.size(markSize).graphicsLayer { alpha = 1f - pull })
                                    if (hasTouchOptions) Icon(Icons.Outlined.ExpandLess, null, tint = progressColor,
                                        modifier = Modifier.size(markSize).graphicsLayer { alpha = pull })
                                }
                            }
                            Spacer(Modifier.width(lerp(12.dp, 6.dp, collapse)))
                            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                                val compact = if (content.title == content.compactTitle) 0f
                                    else ((collapse - .32f) / .36f).coerceIn(0f, 1f)
                                val titleStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = (16f - 4f * collapse).sp, lineHeight = (22f - 5f * collapse).sp,
                                    fontWeight = FontWeight.SemiBold)
                                Box {
                                    if (compact < 1f) Text(content.title, color = DetailForeground, style = titleStyle,
                                        modifier = Modifier.graphicsLayer { alpha = 1f - compact },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (compact > 0f) Text(content.compactTitle, color = DetailForeground, style = titleStyle,
                                        modifier = Modifier.graphicsLayer { alpha = compact },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val chapterReveal = (1f - collapse / .6f).coerceIn(0f, 1f)
                                if (content.caption.isNotBlank() && chapterReveal > 0f) {
                                    Box(Modifier.height(18.dp * fontScale * chapterReveal).clipToBounds()
                                        .graphicsLayer { alpha = chapterReveal }) {
                                        Text(content.caption, color = DetailSecondary, fontSize = 12.sp,
                                            lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (percent != null && collapse < 1f) {
                                Spacer(Modifier.width(12.dp * (1f - collapse)))
                                Box(Modifier.width(42.dp * fontScale * (1f - collapse)).clipToBounds()
                                    .graphicsLayer { alpha = (1f - collapse * 2f).coerceIn(0f, 1f) },
                                    contentAlignment = Alignment.CenterEnd) {
                                    Row(Modifier.clearAndSetSemantics {}) {
                                        Text(percent.toString(), Modifier.alignByBaseline(), color = DetailForeground,
                                            style = mangaMetricValueStyle(15.sp, 20.sp), maxLines = 1, softWrap = false)
                                        Text("%", Modifier.alignByBaseline(), color = DetailSecondary,
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                            trailing(collapse)
                            if (desktopActions.isNotEmpty()) Box(Modifier.size(32.dp)) {
                                DesktopMoreButton(desktopActions, label = optionsLabel)
                            }
                        }
                    }
                }
            }
        }
    }
    if (actions && active) MotionBottomSheet(onDismissRequest = { actions = false },
        backdrop = backdrop, desktopCloseButton = true,
        containerColor = DetailBackground, contentColor = DetailForeground) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            options.forEach { option ->
                DockOption(option.label) { dismiss(); option.onClick() }
            }
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
