package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback

/** The existing beUI right-anchor reveal around the existing Ovio scope selector. */
@Composable
internal fun SearchScopeControl(
    target: SearchTarget,
    expanded: Boolean,
    enabled: Boolean,
    copyOnly: Boolean,
    backdrop: HazeState,
    maxWidth: Dp,
    onExpandedChange: (Boolean) -> Unit,
    onTarget: (SearchTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets = remember { listOf(SearchTarget.TITLE, SearchTarget.AUTHOR, SearchTarget.TAG) }
    val labels = remember { listOf("作品", "作者", "标签") }
    val selected = targets.indexOf(target).coerceAtLeast(0)
    val direction = LocalLayoutDirection.current
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    val interaction = remember { MutableInteractionSource() }
    val transition = updateTransition(expanded && enabled, label = "search-scope")
    val reveal = transition.animateFloat(transitionSpec = {
        if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
    }, label = "search-scope-reveal") { if (it) 1f else 0f }
    val progress = reveal.value.coerceIn(0f, 1f)
    val fullWidth = minOf(132.dp, maxWidth.coerceAtLeast(36.dp))
    val choicesReady = progress >= .55f
    val visibleWidth = 36.dp + (fullWidth - 36.dp) * progress
    var preview by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(expanded, enabled, target) { preview = null }
    val choose: (Int) -> Unit = { index ->
        preview = null
        onTarget(targets[index])
        onExpandedChange(false)
    }

    Box(modifier.width(visibleWidth).height(44.dp).clip(RoundedCornerShape(22.dp))
        .pointerInput(expanded, enabled, target, fullWidth, direction, choicesReady) {
            if (!expanded || !enabled || !choicesReady) return@pointerInput
            var lastPosition = Offset.Zero
            fun indexAt(position: Offset): Int {
                val fullX = if (direction == LayoutDirection.Rtl) position.x
                    else position.x + fullWidth.toPx() - size.width
                val physical = ((fullX / fullWidth.toPx().coerceAtLeast(1f)) * targets.size)
                    .toInt().coerceIn(targets.indices)
                return if (direction == LayoutDirection.Rtl) targets.lastIndex - physical else physical
            }
            detectHorizontalDragGestures(
                onDragStart = { position -> lastPosition = position; preview = indexAt(position) },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    lastPosition = change.position
                    preview = indexAt(lastPosition)
                },
                onDragCancel = { preview = null },
                onDragEnd = {
                    val index = preview
                    val slop = viewConfiguration.touchSlop
                    val inside = lastPosition.x in -slop..(size.width + slop) &&
                        lastPosition.y in -slop..(size.height + slop)
                    preview = null
                    if (inside && index != null) {
                        if (index != selected) feedback.perform(FeelEvent.Selection)
                        choose(index)
                    } else onExpandedChange(false)
                },
            )
        }) {
        LiquidGlassSurface(state = backdrop,
            modifier = Modifier.align(Alignment.Center).width(visibleWidth).height(36.dp),
            cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL,
            interactionSource = interaction, materialExpansion = { progress }) {}

        // Measure the three fixed cells once; the moving clip never changes input/results geometry.
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val width = fullWidth.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeRelative(constraints.maxWidth - width, 0)
            }
        }.motionInputGate(expanded && enabled && choicesReady).graphicsLayer { alpha = progress }) {
            MotionTabs(preview ?: selected, labels, choose,
                modifier = Modifier.align(Alignment.CenterEnd).width(fullWidth),
                controlHeight = 36.dp, indicatorHeight = 28.dp, indicatorCornerRadius = 18.dp,
                fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                fontSize = 11.sp, itemContent = { index, _, color ->
                    Box(Modifier.size(22.dp)) {
                        Icon(when (targets[index]) {
                            SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
                            SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
                            SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
                        }, labels[index] + if (copyOnly || targets[index] != SearchTarget.TITLE) "，拷贝来源" else "，全部来源",
                            Modifier.align(Alignment.Center).size(17.dp), tint = color)
                        if (copyOnly || targets[index] != SearchTarget.TITLE)
                            BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).size(8.dp))
                    }
                })
        }
        val trigger = if (!expanded || !choicesReady) Modifier.clickable(enabled = enabled, role = Role.Button,
            interactionSource = interaction, indication = null,
            onClickLabel = if (expanded) "收起搜索范围" else "展开搜索范围",
            onClick = { onExpandedChange(!expanded) })
            .motionPress(interaction, enabled, pressedScale = .94f)
            .semantics(mergeDescendants = true) {
                contentDescription = "搜索范围：" + labels[selected]
                stateDescription = if (copyOnly || target != SearchTarget.TITLE) "拷贝来源，已收起" else "全部来源，已收起"
            }
        else Modifier.clearAndSetSemantics { }
        Box(Modifier.align(Alignment.CenterEnd).size(36.dp).then(trigger)
            .graphicsLayer { alpha = (1f - progress * 2f).coerceIn(0f, 1f) },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp)) {
                Icon(when (target) {
                    SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
                    SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
                    SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
                }, null, Modifier.align(Alignment.Center).size(17.dp))
                if (copyOnly || target != SearchTarget.TITLE)
                    BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).size(8.dp))
            }
        }
    }
}
