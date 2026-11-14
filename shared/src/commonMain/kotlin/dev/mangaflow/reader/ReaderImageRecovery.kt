package dev.mangaflow.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.motion.MotionTextButton

/** Do not present transport URLs, request keys or raw exception chains as reading UI. */
internal fun readerPageErrorLabel(raw: String): String = when {
    raw.contains("timeout", ignoreCase = true) ||
        raw.contains("timed out", ignoreCase = true) ||
        raw.contains("超时") -> "当前图片连接超时"
    raw.contains("429") || raw.contains("限流") -> "来源暂时限制访问"
    raw.contains("401") || raw.contains("403") ||
        raw.contains("拒绝") || raw.contains("forbidden", true) -> "来源拒绝了本页请求"
    raw.contains("404") || raw.contains("not found", true) -> "本页图片地址可能已失效"
    raw.contains("连接") || raw.contains("socket", true) -> "本页连接中断"
    else -> "本页暂时无法加载"
}

/** The same compact recovery actions serve a failed page or a slow, still-running request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderImageRecovery(
    index: Int,
    failure: String,
    alternatives: List<ChapterVariant>,
    onRetry: () -> Unit,
    onSwitch: (ChapterVariant) -> Unit,
    onMore: (Rect?) -> Unit,
    pending: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    // Multiple editions from one provider must be selected explicitly, not guessed.
    val sourceGroups = remember(alternatives) { alternatives.groupBy { it.sourceId }.values }
    val direct = remember(sourceGroups) { sourceGroups.mapNotNull { it.singleOrNull() }.take(2) }
    val hasMore = alternatives.size > direct.size
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("第 ${index + 1} 页" + if (pending) "仍在加载" else "未加载", fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = colors.onSurface, maxLines = 1)
        Text(if (pending) "等待时间较长，可重试或换源" else readerPageErrorLabel(failure), fontSize = 11.sp,
            color = colors.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            MotionTextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("重试本页", fontSize = 12.sp)
            }
        }
        if (alternatives.isNotEmpty()) {
            FlowRow(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for (variant in direct) {
                    MotionTextButton(onClick = { onSwitch(variant) }) {
                        ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(variant.sourceName, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (hasMore) MotionTextButton(onClick = { onMore(moreAnchor) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val point = coordinates.localToScreen(Offset.Zero)
                        if (point.isSpecified) moreAnchor = Rect(point,
                            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                    }) {
                    Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(17.dp))
                    Text("更多源", fontSize = 11.sp)
                }
            }
            Text("换源会从相近进度继续，版本分图可能不同", fontSize = 10.sp,
                color = colors.onSurfaceVariant, maxLines = 2)
        }
    }
}
