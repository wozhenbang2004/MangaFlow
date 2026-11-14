package dev.mangaflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadPhase
import dev.mangaflow.storage.DownloadStatus
import kotlinx.coroutines.delay

/** A visible row owns its clock; byte updates never restart a tab or a layout animation. */
@Composable
internal fun downloadObservationTime(entries: List<DownloadEntry>, active: Boolean): Long {
    val ticking = active && entries.any {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING ||
            it.status == DownloadStatus.QUEUED
    }
    val now by produceState(wallTimeMillis(), ticking) {
        value = wallTimeMillis()
        while (ticking) { delay(1_000L); value = wallTimeMillis() }
    }
    return now
}

internal fun downloadObservedEntry(entries: List<DownloadEntry>): DownloadEntry? =
    entries.firstOrNull { it.status == DownloadStatus.RUNNING && it.progress.activePages > 0 }
        ?: entries.firstOrNull { it.status == DownloadStatus.RUNNING }
        ?: entries.firstOrNull { it.status == DownloadStatus.WAITING }
        ?: entries.firstOrNull { it.status == DownloadStatus.FAILED }
        ?: entries.firstOrNull { it.status == DownloadStatus.QUEUED }
        ?: entries.firstOrNull { it.status == DownloadStatus.PAUSED }
        ?: entries.firstOrNull()

internal fun downloadTransferPhaseLabel(entry: DownloadEntry, now: Long): String {
    val observed = entry.progress
    val base = when (entry.status) {
        DownloadStatus.COMPLETED -> "已保存"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.FAILED -> observed.failureReason?.label ?: "下载未完成"
        DownloadStatus.QUEUED, DownloadStatus.WAITING, DownloadStatus.RUNNING ->
            observed.waitingReason?.label ?: when {
                observed.activePages > 1 && observed.phase == DownloadPhase.TRANSFERRING ->
                    "正在接收 ${observed.activePages} 页"
                observed.pageIndex != null && observed.phase in setOf(DownloadPhase.TRANSFERRING,
                    DownloadPhase.VERIFYING, DownloadPhase.COMMITTING) ->
                    "第 ${observed.pageIndex!! + 1} 页 · ${observed.phase.label}"
                else -> observed.phase.label
            }
    }
    if (entry.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.PAUSED, DownloadStatus.FAILED)) return base
    observed.retryAt?.let { retry ->
        val remaining = ((retry - now + 999L) / 1_000L).coerceAtLeast(0L)
        return base + if (remaining > 0L) " · ${remaining}秒后重试" else " · 等待调度"
    }
    val since = observed.lastProgressAt ?: observed.phaseStartedAt.takeIf { it > 0L }
    val elapsed = since?.let { ((now - it).coerceAtLeast(0L) / 1_000L) } ?: 0L
    return if (elapsed >= 8L && observed.phase !in setOf(DownloadPhase.VERIFYING, DownloadPhase.COMMITTING))
        "$base · 已等待 ${elapsed}秒" else base
}

internal fun downloadTransferByteLabel(entry: DownloadEntry, now: Long): String {
    val observed = entry.progress
    val bytes = observed.receivedBytes.coerceAtLeast(0L)
    if (bytes == 0L) return ""
    val expected = observed.expectedBytes?.takeIf { it > 0L && observed.activePages == 1 }
    val received = if (expected != null) downloadBytes(bytes) + " / " + downloadBytes(expected)
        else "已接收 " + downloadBytes(bytes)
    val speed = observed.bytesPerSecond?.takeIf { it > 0L && entry.status == DownloadStatus.RUNNING &&
        observed.phase == DownloadPhase.TRANSFERRING &&
        observed.lastProgressAt?.let { at -> now - at in 0L..4_000L } == true }
    return received + (speed?.let { " · " + downloadBytes(it) + "/s" } ?: "")
}

/** Only the exact current page length gives a byte percentage. Other pages remain saved counts. */
internal fun downloadCurrentPageFraction(entries: List<DownloadEntry>): Float? {
    val active = entries.filter { it.progress.activePages > 0 && it.status == DownloadStatus.RUNNING }
    if (active.size != 1) return null
    val observation = active.single().progress
    val total = observation.expectedBytes?.takeIf { it > 0L && observation.activePages == 1 } ?: return null
    return (observation.receivedBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
}
