package dev.mangaflow.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val READER_SLOW_LOAD_MILLIS = 8_000L
internal const val READER_PAGE_WAIT_MILLIS = 45_000L

/** One foreground wait owns its cancellation; shared downloads retain their own owner. */
internal class ReaderLoadAttempt {
    val startedAt: TimeMark = TimeSource.Monotonic.markNow()
    var stopped by mutableStateOf(false)
    var active = true
    val acceptsResult: Boolean get() = active && !stopped
}

/** Do not present transport URLs, request keys or raw exception chains as reading UI. */
internal fun readerPageErrorLabel(raw: String): String = when {
    raw == "已停止等待" -> raw
    raw.contains("timeout", true) || raw.contains("timed out", true) || raw.contains("超时") -> "连接超时"
    raw.contains("UnknownHost", true) || raw.contains("resolve host", true) || raw.contains("DNS", true) -> "暂时无法连接来源"
    raw.contains("429") || raw.contains("限流") -> "来源暂时限制访问"
    raw.contains("401") || raw.contains("403") || raw.contains("拒绝") || raw.contains("forbidden", true) -> "来源拒绝了请求"
    raw.contains("404") || raw.contains("not found", true) -> "图片地址可能已失效"
    raw.contains("SSL", true) || raw.contains("certificate", true) || raw.contains("TLS", true) -> "安全连接未能建立"
    raw.contains("本地") -> "本机文件暂时无法读取"
    raw.contains("没有可") || raw.contains("还没有可") -> "这一章暂时没有可读图片"
    raw.contains("连接") || raw.contains("socket", true) -> "连接已中断"
    else -> "暂时无法加载"
}

/** Only this caption ticks; elapsed time never masquerades as byte progress. */
@Composable
internal fun ReaderWaitCaption(startedAt: TimeMark, waiting: Boolean, color: Color) {
    var seconds by remember(startedAt, waiting) {
        mutableIntStateOf(startedAt.elapsedNow().inWholeSeconds.toInt().coerceAtLeast(0))
    }
    LaunchedEffect(startedAt, waiting) {
        while (waiting) {
            seconds = startedAt.elapsedNow().inWholeSeconds.toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }
    if (seconds > 0) Text("${seconds}秒", fontSize = 11.sp, color = color, maxLines = 1)
}

/** Adapt existing page-local recovery; disclose alternatives only for slow/failed waits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderImageRecovery(
    modifier: Modifier = Modifier,
    index: Int?,
    failure: String,
    alternatives: List<ChapterVariant>,
    onRetry: () -> Unit,
    onSwitch: (ChapterVariant) -> Unit,
    onMore: (Rect?) -> Unit,
    onControls: () -> Unit,
    attempt: ReaderLoadAttempt,
    pending: Boolean = false,
    slow: Boolean = false,
    stage: String = "请求图片",
    pageCount: Int = 0,
    sourceName: String = "",
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    onStop: () -> Unit = {},
) {
    // Multiple editions from one provider must be selected explicitly, not guessed.
    val sourceGroups = remember(alternatives) { alternatives.groupBy { it.sourceId }.values }
    val direct = remember(sourceGroups) { sourceGroups.mapNotNull { it.singleOrNull() }.take(2) }
    val hasMore = alternatives.size > direct.size
    val expanded = !pending || slow
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    Column(modifier.widthIn(max = 300.dp)
        .animateContentSize(tween(if (LocalMotionEnabled.current) MangaMotion.Quick else 0))
        .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (pending) MotionBusyIndicator(Modifier.size(15.dp), color = foreground.copy(alpha = .65f), strokeWidth = 1.5.dp)
            Text(if (index == null) "打开章节" else "第 ${index + 1}" +
                (if (pageCount > 0) " / $pageCount" else "") + " 页",
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = foreground, maxLines = 1)
        }
        Text(if (pending) stage + if (slow) " · 等待较久" else "" else readerPageErrorLabel(failure),
            fontSize = 11.sp, color = foreground.copy(alpha = .66f), maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sourceName.isNotBlank()) Text(sourceName, fontSize = 11.sp, color = foreground.copy(alpha = .55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            ReaderWaitCaption(attempt.startedAt, pending, foreground.copy(alpha = .55f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (expanded) MotionTextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp), tint = foreground)
                Spacer(Modifier.width(5.dp))
                Text(if (index == null) "重新读取" else "重试本页", fontSize = 12.sp, color = foreground)
            }
            MotionTextButton(onClick = onControls) {
                Icon(Icons.Outlined.Tune, "打开阅读控制台", Modifier.size(16.dp), tint = foreground)
            }
            if (pending) MotionTextButton(onClick = onStop) {
                Icon(Icons.Outlined.Close, "停止等待", Modifier.size(16.dp), tint = foreground.copy(alpha = .65f))
            }
        }
        if (expanded && alternatives.isNotEmpty()) {
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for (variant in direct) {
                    MotionTextButton(onClick = { onSwitch(variant) }) {
                        ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(variant.sourceName, fontSize = 11.sp, maxLines = 1, color = foreground,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (hasMore) MotionTextButton(onClick = { onMore(moreAnchor) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val point = coordinates.localToScreen(Offset.Zero)
                        if (point.isSpecified) moreAnchor = Rect(point,
                            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                    }) {
                    Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(17.dp), tint = foreground)
                    Text("更多源", fontSize = 11.sp, color = foreground)
                }
            }
            Text("换源按相近进度续读，分图可能不同", fontSize = 10.sp,
                color = foreground.copy(alpha = .55f), maxLines = 2)
        }
    }
}
