package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
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

/** Share the reading dock's surface and collapse geometry; account checks never gate reading. */
@Composable
internal fun CopyProgressHint(
    state: CopyProgressHintState,
    hasLocalProgress: Boolean,
    active: Boolean,
    revealFraction: Float,
    onAdopt: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    if (!state.loading && state.error == null && state.cloudChapterId.isBlank()) return
    val reveal = revealFraction.coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Layout(modifier = Modifier.fillMaxWidth().clipToBounds()
        .graphicsLayer { alpha = reveal }.motionInputGate(active && reveal > .95f), content = {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp)) {
            val label = when {
                state.loading -> "正在检查拷贝进度"
                state.error != null -> "未能检查拷贝进度"
                else -> (if (hasLocalProgress) "拷贝：" else "拷贝读到 ") +
                    state.cloudChapterTitle.ifBlank { "已保存章节" }
            }
            val caption: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.loading) MotionBusyIndicator(Modifier.size(11.dp), strokeWidth = 1.3.dp)
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val action: (@Composable () -> Unit)? = when {
                state.loading -> null
                state.error != null && onRetry != null -> ({
                    MotionTextButton(onClick = onRetry, enabled = active,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                })
                state.adopted -> ({
                    Text("已采用", Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                })
                state.canAdopt && onAdopt != null -> ({
                    MotionTextButton(onClick = onAdopt, enabled = active,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("比较拷贝进度", style = MaterialTheme.typography.labelSmall)
                    }
                })
                else -> null
            }
            if (action == null) caption(Modifier.fillMaxWidth().padding(vertical = 7.dp))
            else if (maxWidth < 220.dp * fontScale) {
                Column(Modifier.fillMaxWidth()) {
                    caption(Modifier.fillMaxWidth().padding(top = 4.dp))
                    androidx.compose.foundation.layout.Box(Modifier.align(Alignment.End)) { action() }
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                caption(Modifier.weight(1f))
                action()
            }
        }
    }) { measurables, constraints ->
        val content = measurables.single().measure(constraints.copy(minHeight = 0))
        layout(content.width, (content.height * reveal).roundToInt()) { content.placeRelative(0, 0) }
    }
}
