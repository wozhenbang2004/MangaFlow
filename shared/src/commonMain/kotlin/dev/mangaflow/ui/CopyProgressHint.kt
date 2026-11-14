package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionInputGate
import kotlin.math.roundToInt

/** A read-only account comparison; adopting it never represents a local reading event. */
data class CopyProgressHintState(
    val owner: String?,
    val mangaId: String,
    val loading: Boolean = false,
    val error: String? = null,
    val cloudChapterId: String = "",
    val cloudChapterTitle: String = "",
    val adopted: Boolean = false,
    val canAdopt: Boolean = false,
)

/** One inline status/action; account checks never add a second dock row or gate reading. */
@Composable
internal fun CopyProgressHint(
    state: CopyProgressHintState,
    active: Boolean,
    revealFraction: Float,
    onAdopt: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    if (!state.loading && state.error == null && state.cloudChapterId.isBlank()) return
    val reveal = revealFraction.coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    val status = when {
        state.loading -> "正在检查拷贝进度"
        state.error != null -> "未能检查拷贝进度"
        state.adopted -> "已采用拷贝章节：" + state.cloudChapterTitle.ifBlank { "已保存章节" }
        else -> "拷贝读至：" + state.cloudChapterTitle.ifBlank { "已保存章节" }
    }
    val action = when {
        state.loading -> null
        state.error != null -> onRetry
        !state.adopted && state.canAdopt -> onAdopt
        else -> null
    }
    val actionLabel = if (state.error != null) "重试检查拷贝进度" else "比较拷贝进度"
    val symbol = when {
        state.loading -> 0
        state.error != null -> 1
        state.adopted -> 2
        state.canAdopt && onAdopt != null -> 3
        else -> 4
    }
    val glyph: @Composable () -> Unit = {
        MotionSwap(symbol) { shown ->
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BodySourceMark("copy", "拷贝", Modifier.size(13.dp), muted = true)
                when (shown) {
                    0 -> MotionBusyIndicator(Modifier.size(11.dp), strokeWidth = 1.3.dp)
                    1 -> Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    2 -> Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                    3 -> Icon(Icons.Outlined.CompareArrows, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    else -> Unit
                }
            }
        }
    }
    Layout(modifier = Modifier.clipToBounds()
        .graphicsLayer { alpha = reveal }.motionInputGate(active && reveal > .95f), content = {
        val description = if (action == null) status else "$status，$actionLabel"
        if (action != null) MotionIconButton(onClick = action, enabled = active,
            modifier = Modifier.size(44.dp).semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = status
            }, tooltip = description, content = glyph)
        else Box(Modifier.size(44.dp).clearAndSetSemantics { contentDescription = status },
            contentAlignment = Alignment.Center) { glyph() }
    }) { measurables, constraints ->
        val content = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout((content.width * reveal).roundToInt().coerceAtLeast(constraints.minWidth),
            (content.height * reveal).roundToInt().coerceAtLeast(constraints.minHeight)) {
            content.placeRelative(0, 0)
        }
    }
}
