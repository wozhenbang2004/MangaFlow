package dev.mangaflow.anime.danmaku

/** A deterministic timeline: seeks are binary searches, not replayed queues or wall-clock timers. */
internal class AnimeDanmakuTimeline(comments: List<AnimeDanmakuComment>, settings: AnimeDanmakuSettings, availableRows: Int = 1) {
    private data class Scheduled(val value: AnimeDanmakuComment, val lane: Int, val duration: Long)
    private val scheduled: List<Scheduled>

    init {
        val blocked = settings.blockedWords.map(String::trim).filter(String::isNotBlank)
        val lastText = mutableMapOf<String, Long>()
        val lanes = LanePool(availableRows.coerceAtLeast(1), minOf(settings.density, comments.size))
        val output = ArrayList<Scheduled>(comments.size.coerceAtMost(30_000))
        for (comment in comments) {
            if (!settings.showColored && comment.color != 0xFFFFFF) continue
            if (blocked.any { comment.text.contains(it, ignoreCase = true) }) continue
            if (settings.deduplicate) {
                val key = comment.text.lowercase().filterNot(Char::isWhitespace)
                val previous = lastText[key]
                if (previous != null && comment.timeMs - previous < 10_000L) continue
                lastText[key] = comment.timeMs
            }
            val duration = if (comment.mode == AnimeDanmakuMode.SCROLL) 8_000L else 4_000L
            val lane = lanes.take(comment.timeMs, duration, comment.mode == AnimeDanmakuMode.BOTTOM)
            if (lane >= 0) output += Scheduled(comment, lane, duration)
        }
        scheduled = output
    }

    fun frame(positionMs: Long, settings: AnimeDanmakuSettings): List<AnimeDanmakuFrame> {
        if (!settings.enabled || scheduled.isEmpty()) return emptyList()
        val time = if (settings.offsetMs < 0 && positionMs > Long.MAX_VALUE + settings.offsetMs) Long.MAX_VALUE
            else positionMs - settings.offsetMs
        if (time < 0L) return emptyList()
        var low = 0
        var high = scheduled.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (scheduled[mid].value.timeMs < time - 8_000L) low = mid + 1 else high = mid
        }
        val output = ArrayList<AnimeDanmakuFrame>()
        var index = low
        while (index < scheduled.size) {
            val entry = scheduled[index++]
            if (entry.value.timeMs > time) break
            val age = time - entry.value.timeMs
            if (age >= entry.duration) continue
            output += AnimeDanmakuFrame(entry.value.id, entry.value.text, entry.value.color, entry.value.mode,
                entry.lane, age.toFloat() / entry.duration)
        }
        return output
    }

    /** Shared physical rows prevent TOP/BOTTOM and scrolling comments from occupying the same line.
     * A free-row tree and release-time heap bound scheduling by O(comments * log active density).
     * Only the necessary top/bottom rows are represented, even for a very large manual density.
     */
    private class LanePool(private val physicalRows: Int, requested: Int) {
        private val capacity = minOf(physicalRows, requested)
        private val count = minOf(physicalRows.toLong(), capacity.toLong() * 2).toInt()
        private val firstRows = if (count == physicalRows) count else capacity
        private val base = run { var value = 1; while (value < count) value *= 2; value }
        private val free = IntArray(base * 2).also { tree ->
            for (i in 0 until count) tree[base + i] = 1
            for (i in base - 1 downTo 1) tree[i] = tree[i * 2] + tree[i * 2 + 1]
        }
        private val release = LongArray(count)
        private val heap = IntArray(capacity)
        private var size = 0

        fun take(time: Long, duration: Long, bottom: Boolean): Int {
            while (size > 0 && release[heap[0]] <= time) {
                mark(heap[0], true)
                size--
                if (size > 0) {
                    heap[0] = heap[size]
                    var parent = 0
                    while (true) {
                        val left = parent * 2 + 1
                        if (left >= size) break
                        val right = left + 1
                        val child = if (right < size && earlier(heap[right], heap[left])) right else left
                        if (!earlier(heap[child], heap[parent])) break
                        swap(parent, child); parent = child
                    }
                }
            }
            if (size >= capacity || free[1] == 0) return -1
            var node = 1
            while (node < base) {
                val preferred = node * 2 + if (bottom) 1 else 0
                node = if (free[preferred] > 0) preferred else node * 2 + if (bottom) 0 else 1
            }
            val slot = node - base
            mark(slot, false)
            release[slot] = if (time > Long.MAX_VALUE - duration) Long.MAX_VALUE else time + duration
            var at = size++
            heap[at] = slot
            while (at > 0) {
                val parent = (at - 1) / 2
                if (!earlier(heap[at], heap[parent])) break
                swap(at, parent); at = parent
            }
            return if (slot < firstRows) slot else physicalRows - (count - slot)
        }

        private fun mark(slot: Int, available: Boolean) {
            var node = base + slot
            free[node] = if (available) 1 else 0
            node /= 2
            while (node >= 1) { free[node] = free[node * 2] + free[node * 2 + 1]; node /= 2 }
        }
        private fun earlier(left: Int, right: Int): Boolean = release[left] < release[right] || release[left] == release[right] && left < right
        private fun swap(left: Int, right: Int) { val value = heap[left]; heap[left] = heap[right]; heap[right] = value }
    }
}
