package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionSwap

/** Reflow the same live editors when a desktop window resizes; retain edits and focus targets. */
@Composable
internal fun PreferenceWorkspace(
    modifier: Modifier = Modifier,
    splitWidth: Dp = 584.dp,
    preview: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val currentPreview by rememberUpdatedState(preview)
    val currentControls by rememberUpdatedState(controls)
    val showPreview = remember { movableContentOf { currentPreview() } }
    val showControls = remember { movableContentOf { currentControls() } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val desktop = platformKind == PlatformKind.DESKTOP
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val split = desktop && maxWidth >= splitWidth * fontScale
        if (split) {
            val previewWidth = (maxWidth * .43f).coerceAtMost(388.dp * fontScale)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.width(previewWidth)) { showPreview() }
                Box(Modifier.weight(1f)) { showControls() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (desktop) 16.dp else 8.dp)) {
                showPreview()
                if (desktop) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .38f))
                showControls()
            }
        }
    }
}

@Composable
internal fun PreferenceSectionLabel(title: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant.copy(alpha = .38f))
    }
}

internal enum class PreferenceNoticeKind { WORKING, DONE, INFO, ERROR }
internal data class PreferenceNotice(val message: String, val kind: PreferenceNoticeKind)

/** The status keeps its place while work resolves. Only the symbol animates between phases. */
@Composable
internal fun PreferenceOperationFeedback(notice: PreferenceNotice?, action: (@Composable () -> Unit)? = null) {
    var retained by remember { mutableStateOf(notice) }
    SideEffect { if (notice != null) retained = notice }
    val shown = notice ?: retained
    val tones = mangaStatusColors()
    CopyShelfReveal(notice != null, onHidden = { if (notice == null) retained = null }) {
        if (shown != null) Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MotionSwap(shown.kind, Modifier.size(20.dp)) { kind ->
                when (kind) {
                    PreferenceNoticeKind.WORKING -> MotionBusyIndicator(Modifier.size(16.dp), color = tones.activity, strokeWidth = 1.5.dp)
                    PreferenceNoticeKind.DONE -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp), tint = tones.success)
                    PreferenceNoticeKind.INFO -> Icon(Icons.Outlined.Info, null, Modifier.size(17.dp), tint = tones.neutral)
                    PreferenceNoticeKind.ERROR -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(17.dp), tint = tones.error)
                }
            }
            Text(shown.message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (shown.kind == PreferenceNoticeKind.ERROR) tones.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (notice != null) action?.invoke()
        }
    }
}
