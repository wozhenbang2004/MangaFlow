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
