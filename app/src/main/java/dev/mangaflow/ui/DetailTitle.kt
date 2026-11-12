package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class DetailTitleAnchor(val position: Offset, val width: Float)

/** One title travels between two layout anchors; its position follows the finger directly. */
@Composable
internal fun CollapsingDetailTitle(
    title: String,
    expanded: DetailTitleAnchor?,
    collapsed: DetailTitleAnchor?,
    origin: Offset,
    fraction: Float,
    compact: Boolean,
    comments: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val end = collapsed ?: return
    val start = expanded ?: end
    val t = if (compact || expanded == null) 1f else fraction.coerceIn(0f, 1f)
    val point = start.position + (end.position - start.position) * t - origin
    val width = (start.width + (end.width - start.width) * t).coerceAtLeast(1f)
    val targetSize = if (comments) 11f else 15f
    val size = 22f + (targetSize - 22f) * t
    val colorProgress = ((t - .6f) / .4f).coerceIn(0f, 1f)
    val targetColor = if (comments) MaterialTheme.colorScheme.onSurfaceVariant else DetailForeground
    Text(title, modifier = modifier.offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
        .width(with(LocalDensity.current) { width.toDp() })
        .clickable(role = Role.Button, onClickLabel = "查看作品信息", onClick = onClick),
        color = lerp(Color.White, targetColor, colorProgress),
        fontSize = size.sp, lineHeight = (size * 1.3f).sp,
        fontWeight = FontWeight((600f - 100f * t).roundToInt()),
        maxLines = if (t < .72f) 2 else 1, overflow = TextOverflow.Ellipsis)
}
