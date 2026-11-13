package dev.mangaflow.reader.panels

enum class PanelEngine { OFF, CLASSIC, NANO, HIGH_QUALITY }

enum class PanelDirection { RIGHT_TO_LEFT, LEFT_TO_RIGHT }

/** All positions refer to the unchanged page, independently of the detection bitmap size. */
data class PanelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object { val FULL = PanelBox(0f, 0f, 1f, 1f) }
}

data class PanelDetection(
    val boxes: List<PanelBox>,
    val engine: String,
    val notice: String? = null,
)

internal fun PanelBox.sanitized(): PanelBox? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    val box = copy(left = left.coerceIn(0f, 1f), top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f), bottom = bottom.coerceIn(0f, 1f),
        confidence = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
    return box.takeIf { it.width >= .012f && it.height >= .012f }
}

/**
 * Adapted from Komai's Apache-2.0 PanelOrdering.kt at f567e2be8f30b59f04d14dd7fc589e186fb72ff8.
 * Changes: normalized MangaFlow geometry and direction, bounded input, no upstream reader dependency.
 * Horizontal cuts retain rows; a tall panel beside a vertical stack requires a column cut first.
 */
internal object PanelOrdering {
    fun order(boxes: List<PanelBox>, direction: PanelDirection, isSpread: Boolean): List<PanelBox> {
        if (boxes.size <= 1) return boxes
        val rightToLeft = direction == PanelDirection.RIGHT_TO_LEFT
        if (isSpread) {
            spreadSeam(boxes)?.let { seam ->
                val left = boxes.filter { it.centerX < seam }
                val right = boxes.filter { it.centerX >= seam }
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    return if (rightToLeft) cut(right, true) + cut(left, true)
                    else cut(left, false) + cut(right, false)
                }
            }
        }
        return cut(boxes, rightToLeft)
    }

    private fun spreadSeam(boxes: List<PanelBox>): Float? {
        val spans = boxes.map { it.left to it.right }.sortedBy { it.first }
        var end = spans.first().second
        var seam: Float? = null
        var best = .01f
        for ((start, nextEnd) in spans.drop(1)) {
            if (start - end > best) { best = start - end; seam = (start + end) / 2f }
            end = maxOf(end, nextEnd)
        }
        return seam
    }

    private fun cut(boxes: List<PanelBox>, rightToLeft: Boolean): List<PanelBox> {
        if (boxes.size <= 1) return boxes
        findCut(boxes, vertical = false)?.let { (top, bottom) ->
            return cut(top, rightToLeft) + cut(bottom, rightToLeft)
        }
        findCut(boxes, vertical = true)?.let { (left, right) ->
            return if (rightToLeft) cut(right, true) + cut(left, true)
            else cut(left, false) + cut(right, false)
        }
        val rows = ArrayList<MutableList<PanelBox>>()
        for (box in boxes.sortedBy { it.top }) {
            val row = rows.lastOrNull()
            if (row != null && box.top - row.first().top <= .12f) row.add(box)
            else rows.add(mutableListOf(box))
        }
        return rows.flatMap { row ->
            row.sortedWith(if (rightToLeft) compareByDescending { it.left } else compareBy { it.left })
        }
    }

    private fun findCut(boxes: List<PanelBox>, vertical: Boolean): Pair<List<PanelBox>, List<PanelBox>>? {
        val start: (PanelBox) -> Float = { if (vertical) it.left else it.top }
        val end: (PanelBox) -> Float = { if (vertical) it.right else it.bottom }
        val maximum = boxes.maxOf(end)
        for (line in (boxes.map(start) + boxes.map(end)).distinct().sorted()) {
            if (line >= maximum) continue
            val first = mutableListOf<PanelBox>()
            val second = mutableListOf<PanelBox>()
            var valid = true
            for (box in boxes) {
                val a = start(box)
                val b = end(box)
                when {
                    b <= line -> first.add(box)
                    a >= line -> second.add(box)
                    else -> {
                        if (minOf(line - a, b - line) / (b - a).coerceAtLeast(.0001f) > .12f) {
                            valid = false
                            break
                        }
                        if (line - a >= b - line) first.add(box) else second.add(box)
                    }
                }
            }
            if (valid && first.isNotEmpty() && second.isNotEmpty()) return first to second
        }
        return null
    }
}
