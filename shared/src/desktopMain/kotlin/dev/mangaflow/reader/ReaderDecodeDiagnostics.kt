package dev.mangaflow.reader

import kotlin.math.ceil

/** Native tile decode/convert wall time only; excludes admission, GPU upload and Present. */
data class ReaderDecodeSnapshot(
    val active: Int,
    val completed: Long,
    val cancelled: Long,
    val failures: Long,
    val openedReaders: Long,
    val reusedReaders: Long,
    val retainedEncodedBytes: Long,
    val samples: Int,
    val p50Millis: Double?,
    val p95Millis: Double?,
    val p99Millis: Double?,
)

/** Constant-size recording with no per-tile StateFlow emission or sorting on the decode path. */
object ReaderDecodeDiagnostics {
    private val lock = Any()
    private val durations = LongArray(256)
    private var next = 0
    private var count = 0
    private var active = 0
    private var completed = 0L
    private var cancelled = 0L
    private var failures = 0L
    private var openedReaders = 0L
    private var reusedReaders = 0L
    private var retainedEncodedBytes = 0L
    @Volatile private var lastSnapshot: Pair<Long, ReaderDecodeSnapshot>? = null

    internal fun begin(): Long {
        synchronized(lock) { active++ }
        return System.nanoTime()
    }

    internal fun readerAcquired(reused: Boolean) = synchronized(lock) {
        if (reused) reusedReaders++ else openedReaders++
    }

    internal fun retainedInputChanged(bytes: Long) = synchronized(lock) { retainedEncodedBytes += bytes }

    internal fun finish(started: Long, succeeded: Boolean, wasCancelled: Boolean) = synchronized(lock) {
        active--
        when {
            wasCancelled -> cancelled++
            succeeded -> {
                completed++
                durations[next] = (System.nanoTime() - started).coerceAtLeast(0L)
                next = (next + 1) % durations.size
                count = minOf(count + 1, durations.size)
            }
            else -> failures++
        }
    }

    /** Polled by the disclosed diagnostics surface; aggregate work is limited to once a second. */
    fun snapshot(): ReaderDecodeSnapshot {
        val now = System.nanoTime()
        lastSnapshot?.let { if (now - it.first < 1_000_000_000L) return it.second }
        val (raw, counters) = synchronized(lock) {
            durations.copyOf(count) to ReaderDecodeSnapshot(active, completed, cancelled, failures,
                openedReaders, reusedReaders, retainedEncodedBytes, count, null, null, null)
        }
        raw.sort()
        fun percentile(value: Double): Double? = if (raw.isEmpty()) null
            else raw[(ceil(raw.size * value).toInt() - 1).coerceIn(0, raw.lastIndex)] / 1_000_000.0
        val snapshot = counters.copy(p50Millis = percentile(.50), p95Millis = percentile(.95), p99Millis = percentile(.99))
        lastSnapshot = now to snapshot
        return snapshot
    }
}
