package dev.mangaflow.ui

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ChapterPreparationPhase
import dev.mangaflow.storage.ChapterPreparationState
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal enum class QueueCommand(val label: String) { PAUSE("暂停"), RESUME("继续"), RETRY("重试") }

private data class QueueAcknowledgement(val pending: Int, val accepted: Int = 0, val saved: Int = 0,
    val failed: Int = 0, val removed: Int = 0)

internal enum class QueueResolution { ACCEPTED, SAVED, FAILED, REMOVED }

internal class DownloadQueueRequest(val command: QueueCommand, private val targets: List<DownloadEntry>,
    preparations: List<ChapterPreparationState>, val unavailable: Int) {
    val total = targets.size + unavailable
    private val priorPreparations = preparations.filter { it.refreshingEntryId != null }.associateBy { it.refreshingEntryId }

    fun observe(entries: Map<String, DownloadEntry>, preparations: List<ChapterPreparationState>): Map<String, QueueResolution> {
        val latestPreparation = preparations.filter { it.refreshingEntryId != null }.associateBy { it.refreshingEntryId }
        return buildMap {
            for (original in targets) {
                val now = entries[original.id]
                val preparation = latestPreparation[original.id]
                val outcome = when {
                    now == null || !sameDownloadTask(original, now) -> QueueResolution.REMOVED
                    now.status == DownloadStatus.COMPLETED -> QueueResolution.SAVED
                    command == QueueCommand.PAUSE && now.status == DownloadStatus.PAUSED -> QueueResolution.ACCEPTED
                    command != QueueCommand.PAUSE && !now.needsSourceRefresh &&
                        (now.status == DownloadStatus.QUEUED || now.status == DownloadStatus.RUNNING ||
                            now.status == DownloadStatus.WAITING) -> QueueResolution.ACCEPTED
                    now.status == DownloadStatus.FAILED && (original.status != DownloadStatus.FAILED ||
                        now.error != original.error || now.progress != original.progress) -> QueueResolution.FAILED
                    command != QueueCommand.PAUSE && preparation?.phase == ChapterPreparationPhase.FAILED &&
                        preparation.manga.id == original.manga.id && preparation.chapter.id == original.chapter.id &&
                        preparation != priorPreparations[original.id] -> QueueResolution.FAILED
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
    val access by rememberUpdatedState(LocalPlatformAccessibilityPreferences.current)
    var result by remember(request) { mutableStateOf(QueueAcknowledgement(request.total - request.unavailable, removed = request.unavailable)) }
    var waitingDetails by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, result.pending > 0) {
        if (result.pending > 0) {
            delay(6_500L)
            waitingDetails = true
        }
    }
    LaunchedEffect(request) {
        val settled = mutableMapOf<String, QueueResolution>()
        snapshotFlow { request.observe(latestEntries, latestPreparations) }.first { observed ->
            observed.forEach { (id, outcome) -> if (id !in settled) settled[id] = outcome }
            val outcomes = settled.values
            result = QueueAcknowledgement(request.total - request.unavailable - settled.size,
                outcomes.count { it == QueueResolution.ACCEPTED }, outcomes.count { it == QueueResolution.SAVED },
                outcomes.count { it == QueueResolution.FAILED }, request.unavailable + outcomes.count { it == QueueResolution.REMOVED })
            result.pending == 0
        }
        if (result.failed == 0 && result.removed == 0) {
            delay(4_500L)
            if (!access.touchExploration) latestDismiss()
        }
    }
    val total = request.total
    val done = total - result.pending
    val colors = MaterialTheme.colorScheme
    val signal = when {
        result.pending > 0 -> ManagementSignal.QUEUED
        result.failed > 0 -> ManagementSignal.ATTENTION
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
        if (result.removed > 0) add("任务已变更 ${result.removed} 话")
        if (result.pending > 0) add(if (waitingDetails) "${result.pending} 话尚未回应，可查看任务详情" else "等待队列确认 ${result.pending} 话")
    }.joinToString(" · ")
    Surface(modifier = Modifier.widthIn(max = 480.dp), shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerHigh, tonalElevation = 0.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManagementStatusMark(signal)
            Column(Modifier.weight(1f, fill = false).padding(vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${request.command.label} $total 话", style = MaterialTheme.typography.labelLarge)
                Text(detail, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (total > 1) ManagementProgressTrack(done.toFloat() / total, "队列已回应",
                    accent, Modifier.fillMaxWidth().height(2.dp))
            }
            MotionIconButton(onClick = onDismiss, modifier = Modifier.size(38.dp), tooltip = "收起操作结果") {
                Icon(Icons.Outlined.Close, "收起操作结果", Modifier.size(15.dp))
            }
        }
    }
}
