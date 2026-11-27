package dev.mangaflow.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay

internal enum class ReaderCommandKind { POSITION, EDGE, SOURCE, COLLECTION, DOWNLOAD, REPAIR, ISSUE }

/** Each completed user action owns its expiry, even when a repeated action reports the same text. */
internal class ReaderCommandReceipt(val kind: ReaderCommandKind, val text: String)

@Composable
internal fun ReaderCommandNotice(receipt: ReaderCommandReceipt?, active: Boolean, desktop: Boolean,
    backdrop: HazeState, modifier: Modifier, onExpire: (ReaderCommandReceipt) -> Unit, onReturn: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val manager = LocalAccessibilityManager.current
    val latestExpire by rememberUpdatedState(onExpire)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(receipt) }
    val shown = receipt ?: last
    SideEffect { if (receipt != null) last = receipt }
    val canReturn = desktop && receipt?.kind == ReaderCommandKind.POSITION && onReturn != null
    LaunchedEffect(receipt, active, hovered, focused, canReturn, manager) {
        val current = receipt ?: run { focused = false; return@LaunchedEffect }
        if (!active) { latestExpire(current); return@LaunchedEffect }
        if (hovered || focused) return@LaunchedEffect
        val timeout = when {
            !desktop || current.kind == ReaderCommandKind.ISSUE -> 4500L
            canReturn -> 4200L
            current.kind == ReaderCommandKind.EDGE -> 1700L
            else -> 2500L
        }
        delay(manager?.calculateRecommendedTimeoutMillis(timeout, containsIcons = desktop,
            containsText = true, containsControls = canReturn) ?: timeout)
        latestExpire(current)
    }
    val present = receipt != null && active
    val enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0))
    val exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0))
    AnimatedVisibility(present, modifier = modifier,
        enter = if (desktop && motion) enter + slideInVertically(tween(MangaMotion.Content, easing = MangaMotion.EnterEasing)) {
            -it / 6
        } else enter,
        exit = if (desktop && motion) exit + slideOutVertically(tween(MangaMotion.Quick)) { -it / 9 } else exit) {
        val displayed = shown ?: return@AnimatedVisibility
        val kind = displayed.kind
        val showReturn = desktop && kind == ReaderCommandKind.POSITION && onReturn != null
        val input = if (desktop) Modifier.hoverable(interaction).onFocusChanged { focused = it.hasFocus }
            .focusGroup().motionInputGate(present) else Modifier
        LiquidGlassSurface(backdrop, Modifier.widthIn(max = 386.dp).then(input),
            role = GlassSurfaceRole.POPOVER, cornerRadius = 16.dp, softCard = true) {
            Row(Modifier.padding(start = 13.dp, end = if (showReturn) 3.dp else 13.dp,
                top = if (showReturn) 2.dp else 10.dp, bottom = if (showReturn) 2.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (desktop) Icon(when (kind) {
                    ReaderCommandKind.POSITION -> Icons.Outlined.MyLocation
                    ReaderCommandKind.EDGE -> Icons.Outlined.Info
                    ReaderCommandKind.SOURCE -> Icons.Outlined.Source
                    ReaderCommandKind.COLLECTION -> Icons.Rounded.Favorite
                    ReaderCommandKind.DOWNLOAD -> Icons.Rounded.Download
                    ReaderCommandKind.REPAIR -> Icons.Rounded.Tune
                    ReaderCommandKind.ISSUE -> Icons.Outlined.ErrorOutline
                }, null, Modifier.size(16.dp), tint = if (kind == ReaderCommandKind.ISSUE) colors.error else colors.onSurfaceVariant)
                Text(displayed.text, Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.onSurface, style = MaterialTheme.typography.bodySmall,
                    maxLines = if (desktop) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (showReturn && onReturn != null) MotionIconButton(onClick = onReturn, enabled = present,
                    modifier = Modifier.size(38.dp), tooltip = "返回刚才的位置") {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "返回刚才的位置", Modifier.size(17.dp))
                }
            }
        }
    }
}
