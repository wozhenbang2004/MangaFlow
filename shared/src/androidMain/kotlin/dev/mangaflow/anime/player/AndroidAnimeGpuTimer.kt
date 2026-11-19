package dev.mangaflow.anime.player

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock

/** EXT_disjoint_timer_query samples asynchronously. Missing/invalid queries remain unknown. */
internal class AndroidAnimeGpuTimer {
    data class Sample(val durationNanos: Long, val submittedAtNanos: Long, val completionObservedAtNanos: Long, val sequence: Long)
    private data class Pending(val id: Int, val submitted: Long)
    private var available = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')
        ?.contains("GL_EXT_disjoint_timer_query") == true
    private val free = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pending>()
    private var active: Pending? = null
    private var counterChecked = false
    private var sequence = 0L
    var latest: Sample? = null
        private set

    fun begin() {
        if (!available || active != null) return
        runCatching {
            poll()
            if (!available) return
            if (!counterChecked) {
                val bits = IntArray(1)
                GLES30.glGetQueryiv(TIME_ELAPSED, QUERY_COUNTER_BITS, bits, 0)
                // A zero-bit query is allowed by EXT. Keep the accepted observation
                // interval below 2^30 ns so neither the GPU counter nor this 32-bit
                // Java result can wrap during a recorded sample.
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR || bits[0] < 30) { disable(); return }
                counterChecked = true
            }
            val current = IntArray(1)
            GLES30.glGetQueryiv(TIME_ELAPSED, CURRENT_QUERY, current, 0)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { disable(); return }
            if (current[0] != 0) return // Never disturb another renderer's timing scope.
            if (free.isEmpty()) {
                if (pending.size >= 4) return
                val id = IntArray(1)
                GLES30.glGenQueries(1, id, 0)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR || id[0] == 0) { disable(); return }
                free.addLast(id[0])
            }
            val next = Pending(free.removeFirst(), SystemClock.elapsedRealtimeNanos())
            GLES30.glBeginQuery(TIME_ELAPSED, next.id)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) active = next
            else { free.addLast(next.id); disable() }
        }.onFailure { disable() }
    }

    fun end() {
        val current = active ?: return
        active = null
        runCatching {
            GLES30.glEndQuery(TIME_ELAPSED)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) pending.addLast(current)
            else { free.addLast(current.id); disable() }
        }.onFailure { free.addLast(current.id); disable() }
    }

    fun poll() {
        if (!available || pending.isEmpty()) return
        runCatching {
            val disjoint = IntArray(1)
            GLES20.glGetIntegerv(GPU_DISJOINT, disjoint, 0)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { available = false; latest = null; return }
            if (disjoint[0] != 0) {
                latest = null
                while (pending.isNotEmpty()) free.addLast(pending.removeFirst().id)
                return
            }
            while (pending.isNotEmpty()) {
                val query = pending.first()
                val value = IntArray(1)
                GLES30.glGetQueryObjectuiv(query.id, GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { available = false; latest = null; return }
                if (value[0] == 0) return // Do not stall the GPU or call glFinish.
                GLES30.glGetQueryObjectuiv(query.id, GLES30.GL_QUERY_RESULT, value, 0)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { available = false; latest = null; return }
                pending.removeFirst(); free.addLast(query.id)
                val elapsed = value[0].toLong() and 0xffff_ffffL
                val observed = SystemClock.elapsedRealtimeNanos()
                // Overflow is undefined by EXT, rather than guaranteed saturation.
                // Reject long pending samples instead of reporting a wrapped short duration.
                latest = if (observed - query.submitted in 0L..MAX_SAMPLE_WINDOW &&
                    elapsed in 0L..MAX_SAMPLE_WINDOW) Sample(elapsed, query.submitted, observed, ++sequence) else null
            }
        }.onFailure { available = false; latest = null }
    }

    private fun disable() { available = false; latest = null }

    fun reset() {
        end()
        latest = null
        sequence = 0
        // Pending old-timeline GPU work may finish, but its observations must never be published.
        val ids = (free.toList() + pending.map { it.id }).toIntArray()
        free.clear(); pending.clear()
        if (ids.isNotEmpty()) runCatching { GLES30.glDeleteQueries(ids.size, ids, 0); GLES20.glGetError() }
    }

    private companion object {
        const val TIME_ELAPSED = 0x88BF
        const val CURRENT_QUERY = 0x8865
        const val QUERY_COUNTER_BITS = 0x8864
        const val GPU_DISJOINT = 0x8FBB
        const val MAX_SAMPLE_WINDOW = 1_000_000_000L
    }
}
