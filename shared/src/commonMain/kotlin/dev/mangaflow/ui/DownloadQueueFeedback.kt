package dev.mangaflow.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ChapterPreparationPhase
import dev.mangaflow.storage.ChapterPreparationState
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadCommandResult
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal enum class QueueCommand(val label: String) { PAUSE("暂停"), RESUME("继续"), RETRY("重试") }

private data class QueueAcknowledgement(val pending: Int, val accepted: Int = 0, val saved: Int = 0,
    val failed: Int = 0, val removed: Int = 0, val cancelled: Int = 0)

internal class DownloadQueueRequest(val command: QueueCommand, private val targets: List<DownloadEntry>,
    preparations: List<ChapterPreparationState>, val unavailable: Int,
    val commands: Map<String, Deferred<DownloadCommandResult>>) {
    val total = targets.size + unavailable
    val needsStoreObservation = targets.any { it.id !in commands }
    private val priorPreparations = preparations.filter { it.refreshingEntryId != null }.associateBy { it.refreshingEntryId }

    fun observe(entries: Map<String, DownloadEntry>, preparations: List<ChapterPreparationState>,
        completed: Map<String, DownloadCommandResult>): Map<String, DownloadCommandResult> {
        val latestPreparation = preparations.filter { it.refreshingEntryId != null }.associateBy { it.refreshingEntryId }
        return buildMap {
            for (original in targets) {
                if (original.id in commands) {
                    completed[original.id]?.let { put(original.id, it) }
                    continue
                }
                val now = entries[original.id]
                val preparation = latestPreparation[original.id]
                val outcome = when {
                    now == null || !sameDownloadTask(original, now) -> DownloadCommandResult.CHANGED
                    now.status == DownloadStatus.COMPLETED -> DownloadCommandResult.SAVED
                    command == QueueCommand.PAUSE && now.status == DownloadStatus.PAUSED -> DownloadCommandResult.ACCEPTED
                    command != QueueCommand.PAUSE && !now.needsSourceRefresh &&
                        (now.status == DownloadStatus.QUEUED || now.status == DownloadStatus.RUNNING ||
                            now.status == DownloadStatus.WAITING) -> DownloadCommandResult.ACCEPTED
                    now.status == DownloadStatus.FAILED && (original.status != DownloadStatus.FAILED ||
                        now.error != original.error || now.progress != original.progress) -> DownloadCommandResult.FAILED
                    command != QueueCommand.PAUSE && preparation?.phase == ChapterPreparationPhase.FAILED &&
                        preparation.manga.id == original.manga.id && preparation.chapter.id == original.chapter.id &&
                        preparation != priorPreparations[original.id] -> DownloadCommandResult.FAILED
                    else -> null
                }
                if (outcome != null) put(original.id, outcome)
            }
        }
    }
}

internal fun sameDownloadTask(a: DownloadEntry, b: DownloadEntry): Boolean = a.id == b.id && a.createdAt == b.createdAt &&
    a.manga.id == b.manga.id && a.chapter.id == b.chapter.id

/** A single receipt per explicit group action; grade the exact captured tasks against the store. */
@Composable
internal fun DownloadQueueFeedback(request: DownloadQueueRequest, entries: Map<String, DownloadEntry>,
    preparations: List<ChapterPreparationState>, onDismiss: () -> Unit) {
    val latestEntries by rememberUpdatedState(entries)
    val latestPreparations by rememberUpdatedState(preparations)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val access = LocalPlatformAccessibilityPreferences.current
    val pointer = remember { MutableInteractionSource() }
    val hovered by pointer.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var result by remember(request) { mutableStateOf(QueueAcknowledgement(request.total - request.unavailable, removed = request.unavailable)) }
    var waitingDetails by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, result.pending > 0) {
        if (result.pending > 0) {
            delay(6_500L)
            waitingDetails = true
        }
    }
    LaunchedEffect(request) {
        val completed = mutableStateMapOf<String, DownloadCommandResult>()
        request.commands.forEach { (id, command) ->
            launch {
                completed[id] = try { command.await() }
                catch (cancel: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    DownloadCommandResult.CANCELLED
                } catch (_: Exception) { DownloadCommandResult.FAILED }
            }
        }
        val settled = mutableMapOf<String, DownloadCommandResult>()
        // Explicit command receipts are independent of the byte counters in the download directory.
        snapshotFlow {
            if (request.needsStoreObservation) request.observe(latestEntries, latestPreparations, completed)
            else completed.toMap()
        }.first { observed ->
            observed.forEach { (id, outcome) -> if (id !in settled) settled[id] = outcome }
            val outcomes = settled.values
            result = QueueAcknowledgement(request.total - request.unavailable - settled.size,
                outcomes.count { it == DownloadCommandResult.ACCEPTED }, outcomes.count { it == DownloadCommandResult.SAVED },
                outcomes.count { it == DownloadCommandResult.FAILED }, request.unavailable + outcomes.count { it == DownloadCommandResult.CHANGED },
                outcomes.count { it == DownloadCommandResult.CANCELLED })
            result.pending == 0
        }
    }
    val settledCleanly = result.pending == 0 && result.failed == 0 && result.removed == 0 && result.cancelled == 0
    LaunchedEffect(request, settledCleanly, hovered, focused, access.touchExploration) {
        if (settledCleanly && !hovered && !focused && !access.touchExploration) {
            delay(4_500L)
            latestDismiss()
        }
    }
    val total = request.total
    val done = total - result.pending
    val colors = MaterialTheme.colorScheme
    val signal = when {
        result.failed > 0 -> ManagementSignal.ATTENTION
        result.pending > 0 && done > 0 -> ManagementSignal.WORKING
        result.pending > 0 -> ManagementSignal.QUEUED
        result.cancelled > 0 -> ManagementSignal.CANCELLED
        result.removed > 0 -> ManagementSignal.INFORMATION
        else -> ManagementSignal.COMPLETE
    }
    val accent = when (signal) {
        ManagementSignal.COMPLETE -> mangaStatusColors().success
        ManagementSignal.ATTENTION -> colors.error
        else -> colors.primary
    }
    val detail = buildList {
        if (result.accepted > 0) add(if (request.command == QueueCommand.PAUSE) "已暂停 ${result.accepted} 话" else "已恢复调度 ${result.accepted} 话")
        if (result.saved > 0) add("已下载完成 ${result.saved} 话")
        if (result.failed > 0) add("失败 ${result.failed} 话")
        if (result.cancelled > 0) add("已取消 ${result.cancelled} 话")
        if (result.removed > 0) add("任务已变更 ${result.removed} 话")
        if (result.pending > 0) add(when {
            request.command == QueueCommand.PAUSE && waitingDetails -> "正在等待 ${result.pending} 话停止写入"
            request.command == QueueCommand.PAUSE -> "正在暂停 ${result.pending} 话"
            waitingDetails -> "${result.pending} 话尚未回应，可查看任务详情"
            else -> "等待队列确认 ${result.pending} 话"
        })
    }.joinToString(" · ")
    val announcement = when {
        result.pending == 0 -> detail
        result.failed > 0 -> "部分操作失败，其余等待确认"
        waitingDetails -> "仍在等待队列确认"
        else -> "等待队列确认"
    }
    Surface(modifier = Modifier.widthIn(max = 480.dp).hoverable(pointer)
        .onFocusChanged { focused = it.hasFocus }.focusGroup(), shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerHigh, tonalElevation = 0.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManagementStatusMark(signal)
            Column(Modifier.weight(1f, fill = false).padding(vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${request.command.label} $total 话", Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = announcement
                }, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (total > 1) ManagementProgressTrack(done.toFloat() / total, "队列已回应",
                    accent, Modifier.fillMaxWidth().height(2.dp))
            }
            MotionIconButton(onClick = onDismiss, modifier = Modifier.size(38.dp), tooltip = "收起操作结果") {
                Icon(Icons.Outlined.Close, "收起操作结果", Modifier.size(15.dp))
            }
        }
    }
}
