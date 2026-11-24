package dev.mangaflow.reader

/** Chapter/session-local long-window intent. Counters remain actual current-window evidence.
 * A one-unit return glance does not exchange the long lead and short return windows.
 * Two reversed units (or one longer jump) do. Units are images or whole page spreads,
 * not viewport pixels or panels within a single image.
 */
internal class ReaderPrefetchDirection {
    private var frontier: Long? = null
    private var previous: Long? = null
    private var baseline: Long? = null
    var direction: Int = 1
        private set

    fun update(anchor: Long, scrollDirection: Int?, baseline: Long = 0L, baselineAnchor: Long = anchor): Int {
        if (this.baseline != baseline) {
            this.baseline = baseline
            previous = baselineAnchor
            frontier = baselineAnchor
        }
        val old = previous
        previous = anchor
        val edge = frontier
        if (edge == null || old == null) {
            frontier = anchor
            return direction
        }
        // Vertical readers provide authoritative gesture/navigation direction; do not
        // infer a reversal from a layout correction that disagrees with that signal.
        val movement = anchor.compareTo(old)
        if (movement == 0 || scrollDirection != null && movement != scrollDirection) return direction
        if ((anchor - edge) * direction >= 0L) {
            frontier = anchor
        } else if ((edge - anchor) * direction >= 2L) {
            direction = -direction
            frontier = anchor
        }
        return direction
    }
}

/** Nearby previews share one image-count budget across both sides of the viewport.
 * Prefer the reading direction only to break equal-distance ties. Keep whole spreads
 * together; an odd remainder never splits a spread or expands the requested budget.
 */
internal fun readerNearbyPreviewPages(
    first: Int, last: Int, pageCount: Int, count: Int, direction: Int, groups: List<PageSpread>?,
): List<Int> {
    if (count <= 0 || first !in 0 until pageCount || last !in first until pageCount) return emptyList()
    fun side(step: Int): Sequence<List<Int>> = sequence {
        val edge = if (step > 0) last else first
        if (groups == null) {
            var index = edge + step
            while (index in 0 until pageCount) {
                yield(listOf(index))
                index += step
            }
        } else {
            val owner = groups.indexOfReaderPage(edge)
            if (owner >= 0) {
                var index = owner + step
                while (index in groups.indices) {
                    val spread = groups[index]
                    yield(listOfNotNull(spread.first, spread.second))
                    index += step
                }
            }
        }
    }
    val preferredStep = if (direction < 0) -1 else 1
    val preferred = side(preferredStep).iterator()
    val reverse = side(-preferredStep).iterator()
    val result = mutableListOf<Int>()
    var preferredOpen = true
    var reverseOpen = true
    fun appendNext(iterator: Iterator<List<Int>>): Boolean {
        if (!iterator.hasNext()) return false
        val unit = iterator.next()
        if (unit.size > count - result.size) return false
        result += unit
        return true
    }
    while (result.size < count && (preferredOpen || reverseOpen)) {
        if (preferredOpen) preferredOpen = appendNext(preferred)
        if (reverseOpen && result.size < count) reverseOpen = appendNext(reverse)
    }
    return result
}
