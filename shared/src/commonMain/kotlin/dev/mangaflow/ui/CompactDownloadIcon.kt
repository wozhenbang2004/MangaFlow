package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionSwap

internal enum class CompactDownloadState { AVAILABLE, WAITING, DOWNLOADING, PAUSED, COMPLETE, ERROR }

/** Fixed icon slot adapted from the app's state-slot motion; the caller owns the real action. */
@Composable
internal fun CompactDownloadIcon(
    state: CompactDownloadState,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    activeIcon: ImageVector = Icons.Rounded.Pause,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
) {
    val motion = LocalMotionEnabled.current
    val actualProgress = if (state == CompactDownloadState.COMPLETE) 1f
        else progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(actualProgress ?: 0f,
        animationSpec = if (motion) tween(MangaMotion.Content) else snap(),
        label = "download-pages")
    val completeScale = remember { Animatable(1f) }
    var previousState by remember { mutableStateOf(state) }
    LaunchedEffect(state, motion) {
        val completedNow = previousState != CompactDownloadState.COMPLETE && state == CompactDownloadState.COMPLETE
        previousState = state
        completeScale.snapTo(1f)
        if (completedNow && motion) {
            completeScale.animateTo(1.14f, tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing))
            completeScale.animateTo(1f, tween(MangaMotion.Content, easing = MangaMotion.EnterEasing))
        }
    }
    Box(modifier.size(24.dp).semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
        if (state == CompactDownloadState.DOWNLOADING || state == CompactDownloadState.PAUSED ||
            state == CompactDownloadState.COMPLETE) {
            progressBarRangeInfo = actualProgress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                ?: ProgressBarRangeInfo.Indeterminate
        }
    }, contentAlignment = Alignment.Center) {
        // Progress changes redraw only this ring, rather than keying another icon transition.
        MotionSwap(state, Modifier.size(24.dp).clearAndSetSemantics { }.graphicsLayer {
            scaleX = completeScale.value
            scaleY = completeScale.value
        }) { visual ->
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when (visual) {
                    CompactDownloadState.DOWNLOADING, CompactDownloadState.PAUSED -> {
                        if (actualProgress != null) MotionBusyIndicator(progress = { animatedProgress },
                            modifier = Modifier.size(23.dp), color = tint, strokeWidth = 1.4.dp,
                            trackColor = tint.copy(alpha = .14f))
                        else if (visual == CompactDownloadState.DOWNLOADING)
                            MotionBusyIndicator(Modifier.size(23.dp), color = tint, strokeWidth = 1.4.dp)
                        Icon(if (visual == CompactDownloadState.DOWNLOADING) activeIcon else Icons.Rounded.PlayArrow,
                            null, Modifier.size(if (actualProgress != null || visual == CompactDownloadState.DOWNLOADING) 11.dp else 19.dp),
                            tint = tint)
                    }
                    CompactDownloadState.WAITING -> Icon(Icons.Rounded.Schedule, null, Modifier.size(19.dp), tint = tint)
                    CompactDownloadState.COMPLETE -> Icon(Icons.Rounded.Check, null, Modifier.size(21.dp), tint = tint)
                    CompactDownloadState.ERROR -> Icon(Icons.Rounded.Refresh, null, Modifier.size(21.dp), tint = tint)
                    CompactDownloadState.AVAILABLE -> Icon(Icons.Rounded.Download, null, Modifier.size(21.dp), tint = tint)
                }
            }
        }
    }
}
