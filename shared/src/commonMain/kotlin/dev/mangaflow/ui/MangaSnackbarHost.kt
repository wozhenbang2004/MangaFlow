package dev.mangaflow.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate

/** Let a desktop notice finish while keeping the newest pending passive event from this producer. */
internal suspend fun Flow<String>.presentMangaNotices(host: SnackbarHostState) {
    if (platformKind == PlatformKind.DESKTOP) conflate().collect { host.showSnackbar(it) }
    else collectLatest { host.showSnackbar(it) }
}
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** SnackbarHostState still serializes requests and delivers the actual undo/dismiss result. */
@Composable
internal fun MangaSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    if (platformKind != PlatformKind.DESKTOP) {
        SnackbarHost(state, modifier)
        return
    }
    val current = state.currentSnackbarData
    val motion = LocalMotionEnabled.current
    val travel = with(LocalDensity.current) { 7.dp.roundToPx() }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(targetState = current, contentAlignment = Alignment.BottomCenter, transitionSpec = {
            if (!motion) (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
            else ((fadeIn(tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing)) +
                slideInVertically(tween(MangaMotion.Content, easing = MangaMotion.EnterEasing)) { travel }) togetherWith
                (fadeOut(tween(MangaMotion.Quick)) +
                    slideOutVertically(tween(MangaMotion.Quick)) { travel / 2 })).using(null)
        }, label = "desktop-notice") { data ->
            if (data != null) DesktopNotice(data, active = data === state.currentSnackbarData)
        }
    }
}

@Composable
private fun DesktopNotice(data: SnackbarData, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val hasAction = data.visuals.actionLabel != null || data.visuals.withDismissAction
    val interaction = remember(data) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hasFocus by remember(data) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    if (active) NoticeTimeout(data, hovered || hasFocus || !windowFocused)
    BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(max = 560.dp),
        contentAlignment = Alignment.Center) {
        val stacked = hasAction && maxWidth < 310.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Surface(Modifier.motionInputGate(active).hoverable(interaction, enabled = active)
            .onFocusChanged { hasFocus = it.hasFocus }.focusGroup().semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = "操作提示"
                dismiss {
                    if (active) data.dismiss()
                    active
                }
            }, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface, tonalElevation = 0.dp, shadowElevation = 6.dp) {
            if (stacked) {
                Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 2.dp)) {
                    Text(data.visuals.message, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.align(Alignment.End)) { NoticeActions(data, active) }
                }
            } else Row(Modifier.padding(start = 15.dp, end = if (hasAction) 4.dp else 15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(vertical = 11.dp),
                    style = MaterialTheme.typography.bodyMedium)
                if (hasAction) NoticeActions(data, active)
            }
        }
    }
}

/** Count only time the notice is available to read. Hover/focus preserves the remaining
 * interval instead of granting a new full timeout after each pointer crossing. */
@Composable
private fun NoticeTimeout(data: SnackbarData, paused: Boolean) {
    val visuals = data.visuals
    val base = when (visuals.duration) {
        SnackbarDuration.Short -> 4_000L
        SnackbarDuration.Long -> 10_000L
        SnackbarDuration.Indefinite -> return
    }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(base,
        containsIcons = visuals.withDismissAction, containsText = true,
        containsControls = visuals.actionLabel != null || visuals.withDismissAction) ?: base
    val held by rememberUpdatedState(paused)
    LaunchedEffect(data, timeout) {
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        var remaining = timeout
        snapshotFlow { held }.collectLatest { hold ->
            if (!hold) {
                val start = monotonicTimeMillis()
                try {
                    delay(remaining)
                    data.dismiss()
                } finally {
                    remaining = (remaining - (monotonicTimeMillis() - start).coerceAtLeast(0L)).coerceAtLeast(0L)
                }
            }
        }
    }
}

@Composable
private fun NoticeActions(data: SnackbarData, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        data.visuals.actionLabel?.let { label ->
            MotionTextButton(onClick = data::performAction, enabled = active,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
        }
        if (data.visuals.withDismissAction) MotionIconButton(onClick = data::dismiss, enabled = active,
            modifier = Modifier.size(40.dp), tooltip = "关闭提示") {
            Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}
