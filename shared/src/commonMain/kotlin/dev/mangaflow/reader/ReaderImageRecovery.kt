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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import dev.mangaflow.data.Page
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.data.approximateReaderPage
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.experience.PrefetchPromoted
import dev.mangaflow.experience.ExperienceAdmissionDeferred
import dev.mangaflow.experience.PrefetchWorkSnapshot
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.PrefetchImageInactivityException
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.data.plugins.ScriptImageRefreshRequired
import dev.mangaflow.data.sources.JmImageRefreshRequired
import dev.mangaflow.storage.DownloadStorageException
import dev.mangaflow.storage.DownloadPhase
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.storage.DownloadProgress
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal typealias ReaderPageResolver = suspend (Page, ((Long) -> Unit)?) -> Page

/** A decoder queue deadline does not invalidate already downloaded original bytes. */
internal fun isReaderAdmissionDeferred(failure: Throwable): Boolean {
    var cause: Throwable? = failure
    repeat(8) {
        val current = cause ?: return false
        if (current is ExperienceAdmissionDeferred) return true
        cause = current.cause
    }
    return false
}

/** An optional budget, cancellation or foreground promotion says nothing about source health. */
internal fun isReaderPrefetchFailure(failure: Throwable): Boolean {
    var cause: Throwable? = failure
    repeat(8) {
        val current = cause ?: return true
        if (current is CancellationException || current is PrefetchBudgetExceeded || current is PrefetchPromoted ||
            current is ExperienceAdmissionDeferred || current is OutOfMemoryError || current is DownloadRouteException ||
            current is ScriptImageRefreshRequired || current is JmImageRefreshRequired || current is DownloadStorageException) return false
        if (current is PrefetchImageInactivityException && !current.sourceFailure) return false
        if (Regex("(?i)ENOSPC|no space left|disk full|磁盘空间不足|存储空间不足").containsMatchIn(current.message.orEmpty())) return false
        cause = current.cause
    }
    return true
}

/** Local lookup, decode and an existing download's queue are not evidence of a failing source. */
internal fun readerTimeoutIsSource(work: PrefetchWorkSnapshot?, shared: DownloadProgress?): Boolean {
    if (work?.stage == PrefetchStage.CHECKING) return false
    if (shared != null) return shared.phase in setOf(DownloadPhase.CONNECTING,
        DownloadPhase.FIRST_BYTE_WAIT, DownloadPhase.TRANSFERRING) && shared.waitingReason == null
    return work?.admitted == true && work.stage == PrefetchStage.DOWNLOADING
}

/** Keep local optional-work stops distinct from a failed image or unreachable source. */
internal fun readerPrefetchErrorLabel(failure: Throwable): String {
    var cause: Throwable? = failure
    val chain = mutableListOf<Throwable>()
    repeat(8) {
        val current = cause ?: return@repeat
        chain += current
        cause = current.cause
    }
    return when {
        chain.any { it is PrefetchBudgetExceeded } -> "预读容量暂受限"
        chain.any { it is ExperienceAdmissionDeferred } -> "资源保护暂缓"
        chain.any { it is PrefetchPromoted } -> "交由当前页继续"
        chain.any { it is OutOfMemoryError } -> "内存保护暂缓"
        chain.any { it is DownloadRouteException } -> "线路配置暂不可用"
        chain.any { it is ScriptImageRefreshRequired || it is JmImageRefreshRequired } -> "来源版本已变化，请重新读取"
        chain.any { it is DownloadStorageException } -> "本机存储暂不可用"
        chain.filterIsInstance<PrefetchImageInactivityException>().firstOrNull()?.sourceFailure == false -> "准备资源等待超时"
        chain.any { it is PrefetchImageInactivityException } -> "等待图片新数据超时"
        chain.any { it is CancellationException } -> "预读已取消"
        else -> chain.asReversed().map { readerPageErrorLabel(it.message.orEmpty()) }
            .firstOrNull { it != "暂时无法加载" } ?: "图片预读未完成"
    }
}

/** One foreground wait owns its cancellation; shared downloads retain their own owner. */
internal class ReaderLoadAttempt {
    val startedAt: TimeMark = TimeSource.Monotonic.markNow()
    private val createdAt = wallTimeMillis()
    private var observedTransfer: String? = null
    private var observedBytes = 0L
    private var byteProgress: TimeMark? = null
    private val byteLock = StorageLock()
    var stopped by mutableStateOf(false)
    var active = true
    val acceptsResult: Boolean get() = active && !stopped

    /** A new download request never revives the old reader attempt which had awaited another one. */
    fun observeTransfer(progress: DownloadProgress): Boolean = synchronized(byteLock) {
        if (!acceptsResult || progress.transferId.isEmpty()) return@synchronized false
        val first = observedTransfer == null
        if (first) observedTransfer = progress.transferId
        if (observedTransfer != progress.transferId) return@synchronized false
        val bytes = progress.transferredBytes
        if (bytes > observedBytes && progress.lastProgressAt != null &&
            (!first || progress.lastProgressAt >= createdAt)) byteProgress = TimeSource.Monotonic.markNow()
        observedBytes = maxOf(observedBytes, bytes)
        true
    }

    /** Visibility starts a fresh bounded wait; only new bytes in this request move its idle clock. */
    fun idleMillis(visibleSince: TimeMark, transportProgressAt: Long? = null): Long = synchronized(byteLock) {
        minOf(visibleSince.elapsedNow().inWholeMilliseconds,
            byteProgress?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE,
            transportProgressAt?.let { monotonicTimeMillis() - it } ?: Long.MAX_VALUE).coerceAtLeast(0L)
    }
}

@Composable
internal fun rememberReaderPageTransfer(attempt: ReaderLoadAttempt, page: Page,
    observer: (Page) -> Flow<DownloadProgress?>, enabled: Boolean): State<DownloadProgress?> {
    val progress = remember(attempt) { mutableStateOf<DownloadProgress?>(null) }
    LaunchedEffect(attempt, page, observer, enabled) {
        progress.value = null
        if (!enabled || !attempt.acceptsResult) return@LaunchedEffect
        observer(page).collect { current ->
            progress.value = current?.takeIf { attempt.observeTransfer(it) }
        }
    }
    return progress
}

/** Do not present transport URLs, request keys or raw exception chains as reading UI. */
internal fun readerPageErrorLabel(raw: String): String = when {
    raw == "已停止等待" -> raw
    raw.contains("准备等待超时") || raw.contains("准备资源等待超时") -> "准备资源等待超时"
    raw.contains("等待下载资源") || raw.contains("资源保护暂缓") || raw.contains("内存保护暂缓") -> "资源暂缓"
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
    startedAt: TimeMark = attempt.startedAt,
    pending: Boolean = false,
    slow: Boolean = false,
    stage: String = "请求图片",
    pageCount: Int = 0,
    sourceName: String = "",
    transferProgress: DownloadProgress? = null,
    readySourceVariants: Map<String, ReaderSourceStandby> = emptyMap(),
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    onStop: () -> Unit = {},
    onRepairPage: (() -> Unit)? = null,
) {
    // Multiple editions from one provider must be selected explicitly, not guessed.
    val sourceGroups = remember(alternatives) { alternatives.groupBy { it.sourceId }.values }
    val direct = remember(sourceGroups, index) {
        if (index == null) sourceGroups.mapNotNull { it.singleOrNull() }.take(2)
        else alternatives.take(2) // Opens explicit artwork confirmation; it does not choose a whole-source edition.
    }
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
        val liveTransfer = transferProgress?.takeIf { pending && attempt.acceptsResult }
        val transferStage = liveTransfer?.let { transfer ->
            transfer.phase.label + if (transfer.receivedBytes > 0L) " · " + readerTransferBytes(transfer.receivedBytes) else ""
        } ?: stage
        Text(if (pending) transferStage + if (slow) " · 等待较久" else "" else readerPageErrorLabel(failure),
            fontSize = 11.sp, color = foreground.copy(alpha = .66f), maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sourceName.isNotBlank()) Text(sourceName, fontSize = 11.sp, color = foreground.copy(alpha = .55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            ReaderWaitCaption(startedAt, pending, foreground.copy(alpha = .55f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (expanded) MotionTextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp), tint = foreground)
                Spacer(Modifier.width(5.dp))
                Text(if (index == null) "重新读取" else "重试本页", fontSize = 12.sp, color = foreground)
            }
            if (index != null && expanded && alternatives.isNotEmpty() && onRepairPage != null)
                MotionTextButton(onClick = onRepairPage) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = foreground)
                    Spacer(Modifier.width(5.dp))
                    Text("补此页", fontSize = 12.sp, color = foreground)
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
                        val standby = readySourceVariants[variant.storageId]?.takeIf { prepared ->
                            index != null && pageCount > 0 && prepared.totalPages != null && prepared.totalPages > 0 &&
                                prepared.targetPageIndex == approximateReaderPage(index, pageCount, prepared.totalPages)
                        }
                        if (standby != null) {
                            val target = standby.targetPageIndex
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Outlined.Check, "备用已预热" +
                                (target?.let { "，约第 ${it + 1} 页" } ?: "") + "；确认相同画面后补当前页",
                                Modifier.size(12.dp), tint = foreground.copy(alpha = .65f))
                        }
                    }
                }
                if (hasMore || index != null) MotionTextButton(onClick = { onMore(moreAnchor) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val point = coordinates.localToScreen(Offset.Zero)
                        if (point.isSpecified) moreAnchor = Rect(point,
                            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                    }) {
                    Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(17.dp), tint = foreground)
                    Text(if (index == null) "更多源" else "整章来源", fontSize = 11.sp, color = foreground)
                }
            }
            Text(if (index == null) "换源按相近进度续读，分图可能不同" else "点来源对齐单页 · 附近位置仅为估算", fontSize = 10.sp,
                color = foreground.copy(alpha = .55f), maxLines = 2)
        }
    }
}

private fun readerTransferBytes(bytes: Long): String = if (bytes >= 1_048_576L) {
    val tenths = bytes / 1_048_576L * 10L + bytes % 1_048_576L * 10L / 1_048_576L
    "${tenths / 10}.${tenths % 10} MB"
} else "${(bytes / 1024L).coerceAtLeast(1L)} KB"
