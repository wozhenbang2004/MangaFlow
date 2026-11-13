package dev.mangaflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress

/** One stateful header control, not a second row of permanent tab chrome. */
internal data class GlassTabChoice(val label: String, val icon: ImageVector)

@Composable
private fun glassTabLabelStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium)

/** Selection never changes the header's width or the morph's closing endpoint. */
@Composable
internal fun morphGlassTabWidth(choices: List<GlassTabChoice>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = glassTabLabelStyle()
    val labelWidth = remember(choices, measurer, style, density) {
        choices.maxOfOrNull { measurer.measure(it.label, style, maxLines = 1).size.width } ?: 0
    }
    return (with(density) { labelWidth.toDp() } + 70.dp).coerceIn(96.dp, 144.dp)
}

/** Identical icon, text, chevron and padding at both ends of the overlay handoff. */
@Composable
private fun GlassTabLabel(choice: GlassTabChoice, modifier: Modifier = Modifier, expansion: () -> Float = { 0f }) {
    Row(modifier.height(44.dp).padding(start = 11.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(choice.icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(choice.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = glassTabLabelStyle())
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp).graphicsLayer {
            rotationZ = expansion() * 180f
        }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MorphGlassTabTrigger(
    backdrop: HazeState,
    choices: List<GlassTabChoice>,
    selected: Int,
    collapsedWidth: Dp,
    expanded: Boolean,
    covered: Boolean = false,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    LiquidGlassSurface(backdrop, Modifier.graphicsLayer {
        alpha = if (covered) 0f else 1f
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
        trackPointer = !covered && enabled) {
        GlassTabLabel(choices[selected], Modifier.width(collapsedWidth).clip(RoundedCornerShape(22.dp))
            .motionInputGate(!covered)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled && !covered, onClickLabel = if (expanded) "收起分类" else "展开分类",
                onClick = onToggle)
            .motionPress(interaction, enabled && !covered, .985f)
            .semantics {
                contentDescription = "切换分类，当前" + choices[selected].label
                stateDescription = if (expanded) "已展开" else "已收起"
            })
    }
}

/**
 * The menu lives in the SAME window and Haze scene as the scrolling wallpaper.
 * Opening grows the capsule from its original top-row anchor without remeasuring the list.
 * A separate Popup window would lose the original backdrop capture on some devices.
 */
@Composable
internal fun BoxScope.MorphGlassTabOverlay(
    backdrop: HazeState,
    choices: List<GlassTabChoice>,
    selected: Int,
    collapsedWidth: Dp,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPresenceChange: (Boolean) -> Unit = {},
    onSelect: (Int) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val haptic = LocalHapticFeedback.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = expanded
    val transition = updateTransition(presence, label = "header-tab-morph")
    val animation = transition.animateFloat(transitionSpec = {
        if (motion) spring(dampingRatio = 1f, stiffness = MangaMotion.NavigationStiffness)
        else snap()
    }, label = "header-tab-expansion") { if (it) 1f else 0f }
    BackHandler(expanded) { onDismiss() }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    if (!present) return
    val progress = animation.value.coerceIn(0f, 1f)
    if (expanded) {
        Box(Modifier.fillMaxSize().zIndex(40f).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = "关闭分类", onClick = onDismiss))
    }
    val extendedWidth = (collapsedWidth + 12.dp).coerceIn(128.dp, 156.dp)
    val rowHeight = (18f * LocalDensity.current.fontScale + 12f).dp.coerceAtLeast(44.dp)
    val extendedHeight = rowHeight * choices.size + 8.dp
    val width = collapsedWidth + (extendedWidth - collapsedWidth) * progress
    val height = 44.dp + (extendedHeight - 44.dp) * progress
    LiquidGlassSurface(backdrop,
        modifier = Modifier.align(Alignment.TopStart).zIndex(41f)
            .padding(start = 62.dp, top = 2.dp).width(width).height(height).motionInputGate(expanded),
        cornerRadius = 22.dp - 4.dp * progress, role = GlassSurfaceRole.NAVIGATION,
        trackPointer = false) {
        GlassTabLabel(choices[selected], Modifier.width(collapsedWidth).graphicsLayer {
            alpha = (1f - progress * 2.4f).coerceIn(0f, 1f)
        }, expansion = { progress })
        // Lay out menu labels once at their final size; only the surface clips/reveals them.
        Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(width = extendedWidth, height = extendedHeight).graphicsLayer {
            alpha = ((progress - .25f) / .65f).coerceIn(0f, 1f)
        }.motionInputGate(expanded && progress >= .7f)
            .padding(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            choices.forEachIndexed { index, choice ->
                val interaction = remember(index) { MutableInteractionSource() }
                val active = selected == index
                val selectionTint = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)
                Row(Modifier.fillMaxWidth().height(rowHeight).clip(RoundedCornerShape(12.dp))
                    .background(if (active) selectionTint else Color.Transparent)
                    .clickable(enabled = expanded && progress >= .7f,
                        interactionSource = interaction, indication = null,
                        role = Role.Tab, onClickLabel = "显示" + choice.label,
                        onClick = {
                            if (index != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        })
                    .motionPress(interaction, expanded, .985f)
                    .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(choice.icon, null, Modifier.size(18.dp),
                        tint = if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(choice.label, Modifier.weight(1f), style = glassTabLabelStyle(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.size(14.dp)) {
                        if (active) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
