package dev.mangaflow.data

import kotlin.math.roundToInt

/** Metadata readiness and a decoded image are separate facts, never page-equivalence evidence. */
enum class ReaderSourceStandbyPhase { PREPARING, MANIFEST_READY, READY, FAILED, DEFERRED }

/**
 * A speculative alternative belongs to the current native chapter and reader session.
 * It never changes the selected source, saved progress, or a current page's ownership.
 */
data class ReaderSourceStandby(
    val workId: String,
    val chapterId: String,
    val variantId: String,
    val sourceId: String,
    val sourceName: String,
    val phase: ReaderSourceStandbyPhase,
    val targetPageIndex: Int? = null,
    val totalPages: Int? = null,
    val approximate: Boolean = true,
    /** Current nearby window only; these are observed originals, never whole-chapter claims. */
    val requestedPages: Int = 0,
    val preparedPages: Int = 0,
    /** Successfully inspected previews; resident memory is reported independently below. */
    val decodedPages: Int = 0,
    val memoryWarmed: Boolean = false,
    val targetPrepared: Boolean = false,
    val activePages: Int = 0,
    val reason: String? = null,
)

/** Editions may split images differently; this is only an explicitly labelled resume estimate. */
internal fun approximateReaderPage(oldIndex: Int, oldCount: Int, targetCount: Int): Int {
    if (targetCount <= 1) return 0
    if (oldCount <= 1) return oldIndex.coerceIn(0, targetCount - 1)
    val fraction = oldIndex.coerceIn(0, oldCount - 1).toDouble() / (oldCount - 1)
    return (fraction * (targetCount - 1)).roundToInt().coerceIn(0, targetCount - 1)
}
