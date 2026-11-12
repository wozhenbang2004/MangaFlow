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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionPress

/** One stateful header control, not a second row of permanent tab chrome. */
internal data class GlassTabChoice(val label: String, val icon: ImageVector)

@Composable
internal fun MorphGlassTabTrigger(
    backdrop: HazeState,
    choices: List<GlassTabChoice>,
    selected: Int,
    collapsedWidth: Dp,
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
        trackPointer = true) {
        Row(Modifier.width(collapsedWidth).height(44.dp).clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClickLabel = if (expanded) "收起分类" else "展开分类",
                onClick = onToggle)
            .motionPress(interaction, enabled, .965f)
            .semantics {
                contentDescription = "切换分类，当前" + choices[selected].label
                stateDescription = if (expanded) "已展开" else "已收起"
            }
            .padding(start = 11.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MotionSwap(selected, Modifier.size(19.dp)) { index ->
                Icon(choices[index].icon, null, Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(choices[selected].label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp).graphicsLayer {
                rotationZ = if (expanded) 180f else 0f
            }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    onSelect: (Int) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val haptic = LocalHapticFeedback.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = expanded
    val transition = updateTransition(presence, label = "header-tab-morph")
    val animation = transition.animateFloat(transitionSpec = {
        if (motion) spring(dampingRatio = .86f, stiffness = MangaMotion.GestureStiffness)
        else snap()
    }, label = "header-tab-expansion") { if (it) 1f else 0f }
    BackHandler(expanded) { onDismiss() }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    if (!present) return
    val progress = animation.value.coerceIn(0f, 1.04f)
    if (expanded) {
        Box(Modifier.fillMaxSize().zIndex(40f).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = "关闭分类", onClick = onDismiss))
    }
    val extendedWidth = 194.dp
    val extendedHeight = (choices.size * 46 + 12).dp
    val width = collapsedWidth + (extendedWidth - collapsedWidth) * progress
    val height = 44.dp + (extendedHeight - 44.dp) * progress
    LiquidGlassSurface(backdrop,
        modifier = Modifier.align(Alignment.TopStart).zIndex(41f)
            .padding(start = 62.dp, top = 2.dp).width(width).height(height),
        cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
        trackPointer = false) {
        Row(Modifier.fillMaxWidth().height(44.dp).graphicsLayer {
            alpha = (1f - progress * 2.4f).coerceIn(0f, 1f)
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(choices[selected].icon, null, Modifier.size(19.dp))
            Text(choices[selected].label, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium)
        }
        Column(Modifier.fillMaxSize().graphicsLayer {
            alpha = ((progress - .25f) / .65f).coerceIn(0f, 1f)
        }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            choices.forEachIndexed { index, choice ->
                val interaction = remember(index) { MutableInteractionSource() }
                val active = selected == index
                val selectionTint = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)
                Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (active) selectionTint else Color.Transparent)
                    .clickable(enabled = expanded && progress >= .7f,
                        interactionSource = interaction, indication = null,
                        role = Role.Tab, onClickLabel = "显示" + choice.label,
                        onClick = {
                            if (index != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        })
                    .motionPress(interaction, expanded, .965f)
                    .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Icon(choice.icon, null, Modifier.size(20.dp),
                        tint = if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(choice.label, Modifier.weight(1f), fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (active) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
