package dev.mangaflow.anime.player

/** One uninterrupted online rebuffer after this timeline has already played. */
internal class AnimeRebufferProgress {
    private var timelineId: Long? = null
    private var sinceMs = 0L
    private var startPositionMs = 0L
    private var startBufferEndMs: Long? = null
    private var grantedBufferTime = false

    fun reset() {
        timelineId = null
    }

    fun stalled(nowMs: Long, timeline: Long, positionMs: Long, bufferedEndMs: Long, waitMs: Long): Boolean {
        if (positionMs < 0L || waitMs <= 0L) {
            reset()
            return false
        }
        val knownEndMs = bufferedEndMs.takeIf { it >= 0L }
        if (timelineId != timeline || nowMs < sinceMs) {
            timelineId = timeline
            beginWindow(nowMs, positionMs, knownEndMs)
            return false
        }
        // A backend may publish its first buffer range halfway through the wait.
        // Its existing contents are a baseline, not evidence of newly playable media.
        if (startBufferEndMs == null) startBufferEndMs = knownEndMs?.coerceAtLeast(positionMs)
        val elapsed = nowMs - sinceMs
        if (elapsed < waitMs) return false
        if (positionMs - startPositionMs >= 1_000L) {
            beginWindow(nowMs, positionMs, knownEndMs)
            return false
        }
        if (elapsed - waitMs >= waitMs) return true
        val initialEndMs = startBufferEndMs
        if (knownEndMs != null && initialEndMs != null && knownEndMs - maxOf(initialEndMs, positionMs) >= 2_000L) {
            grantedBufferTime = true
        }
        // Give newly buffered media one more window to reach the screen.
        // Repeated reads, byte counts and a jittering reported endpoint cannot extend it.
        return !grantedBufferTime
    }

    private fun beginWindow(nowMs: Long, positionMs: Long, knownEndMs: Long?) {
        sinceMs = nowMs
        startPositionMs = positionMs
        startBufferEndMs = knownEndMs?.coerceAtLeast(positionMs)
        grantedBufferTime = false
    }
}
