package dev.mangaflow.experience

import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.IOException

/** A stalled real source request is distinct from queueing or local decoding pressure. */
internal class PrefetchImageInactivityException(val sourceFailure: Boolean) : IOException(
    if (sourceFailure) "等待图片新数据超时" else "准备资源等待超时，稍后继续")

/** Attempt-owned watchdog: positive body bytes extend it; callbacks and headers do not. */
internal suspend fun <T> withPrefetchImageInactivity(
    work: PrefetchWork, idleMillis: Long, sourceFailureAllowed: Boolean = true, block: suspend () -> T,
): T = coroutineScope {
    val started = monotonicTimeMillis()
    val task = async { block() }
    val watchdog = launch {
        var phase: PrefetchStage? = null
        var phaseStarted = started
        var admittedOnce = false
        while (!task.isCompleted) {
            val now = monotonicTimeMillis()
            val snapshot = work.snapshot()
            if (snapshot.stage != phase) {
                phase = snapshot.stage
                phaseStarted = now
                admittedOnce = false
            }
            // Grant one execution interval after the queue; repeated admission callbacks cannot reset it.
            if (snapshot.admitted && snapshot.wait == null && !admittedOnce) {
                admittedOnce = true
                phaseStarted = now
            }
            val lastProgress = maxOf(phaseStarted, snapshot.lastProgressAt ?: started)
            if (now - lastProgress >= idleMillis.coerceAtLeast(1L)) {
                throw PrefetchImageInactivityException(sourceFailureAllowed && snapshot.admitted && snapshot.wait == null &&
                    !snapshot.settled && snapshot.stage in setOf(PrefetchStage.RESOLVING, PrefetchStage.DOWNLOADING))
            }
            delay(minOf(250L, idleMillis.coerceAtLeast(1L)))
        }
    }
    try { task.await() } finally { watchdog.cancel() }
}
