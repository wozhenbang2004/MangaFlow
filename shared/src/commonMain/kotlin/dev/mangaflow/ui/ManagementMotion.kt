package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionSwap

/** The caller keys the track to the measured operation. Corrections and new runs take effect immediately. */
@Composable
internal fun ManagementProgressTrack(
    fraction: Float,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    interpolateSteps: Boolean = true,
) {
    val target = fraction.coerceIn(0f, 1f)
    val fill = if (interpolateSteps && LocalMotionEnabled.current) {
        val animated = remember { Animatable(target) }
        var previousTarget by remember { mutableFloatStateOf(target) }
        LaunchedEffect(target) {
            val corrected = target < previousTarget
            previousTarget = target
            if (corrected || target <= animated.value) animated.snapTo(target)
            else animated.animateTo(target, tween(MangaMotion.Quick, easing = LinearEasing))
        }
        animated
    } else null
    val paper = MaterialTheme.colorScheme.onSurface.copy(alpha = .085f)
    Box(modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
    }.drawBehind {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(paper, cornerRadius = radius)
        val width = size.width * (fill?.let { minOf(it.value, target) } ?: target)
        if (width > 0f) {
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
            drawRoundRect(color, Offset(left, 0f), Size(width, size.height), radius)
        }
    })
}

internal enum class ManagementSignal { QUEUED, WORKING, COMPLETE, ATTENTION, CANCELLED, INFORMATION }
internal data class ManagementNotice(val message: String, val signal: ManagementSignal)

@Composable
internal fun ManagementStatusMark(signal: ManagementSignal, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    MotionSwap(signal, modifier.size(18.dp), scaleContent = false) { phase ->
        if (phase == ManagementSignal.WORKING)
            MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
        else Icon(when (phase) {
            ManagementSignal.QUEUED -> Icons.Outlined.Schedule
            ManagementSignal.COMPLETE -> Icons.Outlined.CheckCircle
            ManagementSignal.ATTENTION -> Icons.Outlined.ErrorOutline
            ManagementSignal.CANCELLED -> Icons.Outlined.Pause
            else -> Icons.Outlined.Info
        }, null, Modifier.size(17.dp), tint = when (phase) {
            ManagementSignal.COMPLETE -> mangaStatusColors().success
            ManagementSignal.ATTENTION -> colors.error
            else -> colors.onSurfaceVariant
        })
    }
}

/** The action stays on the live operation while its text and status settle in a single slot. */
@Composable
internal fun ManagementStatusRow(status: ManagementNotice?, modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {}) {
    var previous by remember { mutableStateOf(status) }
    SideEffect { if (status != null) previous = status }
    CopyShelfReveal(status != null, modifier, onHidden = { if (status == null) previous = null }) {
        val shown = status ?: previous
        if (shown != null) {
            val colors = MaterialTheme.colorScheme
            val warning = shown.signal == ManagementSignal.ATTENTION
            Row(Modifier.fillMaxWidth().background(if (warning) colors.errorContainer.copy(alpha = .6f)
                else colors.surfaceContainerLow, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagementStatusMark(shown.signal)
                val scroll = key(shown.message) { rememberScrollState() }
                Text(shown.message, Modifier.weight(1f).heightIn(max = 74.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                    .verticalScroll(scroll).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) colors.onErrorContainer else colors.onSurfaceVariant)
                if (status != null) action()
            }
        }
    }
}
