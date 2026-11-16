package dev.mangaflow.data

import kotlin.math.roundToInt

/** Metadata readiness and a decoded image are separate facts, never page-equivalence evidence. */
enum class ReaderSourceStandbyPhase {
    PREPARING, MANIFEST_READY, QUEUED, RESOLVING, DOWNLOADING, CHECKING, READY, FAILED, DEFERRED,
}

enum class ReaderSourceStandbyAvailabilityPhase {
    IDLE, NO_ALTERNATIVES, NO_MATCHING_CHAPTER, UNCONFIRMED, DISABLED, ELIGIBLE,
}

/** Candidate eligibility for this exact chapter, independent of transfer or cache readiness. */
data class ReaderSourceStandbyAvailability(
    val workId: String = "",
    val chapterId: String = "",
    val phase: ReaderSourceStandbyAvailabilityPhase = ReaderSourceStandbyAvailabilityPhase.IDLE,
    val alternativeCount: Int = 0,
    val linkedCount: Int = 0,
    val confirmedCount: Int = 0,
    val enabledCount: Int = 0,
    val reason: String? = null,
)

/** A native alternate page in the current approximate window, never a cross-edition replacement. */
data class ReaderSourceStandbyPage(
    val index: Int,
    val phase: ReaderSourceStandbyPhase,
    val originalPresent: Boolean = false,
    val previewResident: Boolean = false,
    val cachedOriginalBytes: Long = 0L,
    /** Positive body bytes observed in this page's current attempt; cache hits contribute zero. */
    val receivedBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val cacheInspectedAtMillis: Long? = null,
    val reason: String? = null,
)

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
    /** Current-window pages actually admitted to resolve/transfer/decode, excluding queued work. */
    val activePages: Int = 0,
    val reason: String? = null,
    /** Native window revision. Outgoing children settle separately and never count as ready here. */
    val windowId: Long = 0L,
    val queuedPages: Int = 0,
    val resolvingPages: Int = 0,
    val downloadingPages: Int = 0,
    val checkingPages: Int = 0,
    val failedPages: Int = 0,
    val deferredPages: Int = 0,
    val settlingPages: Int = 0,
    /** Actual inspected original-cache bytes in this window, not received traffic or a budget. */
    val cachedOriginalBytes: Long = 0L,
    val previewResidentPages: Int = 0,
    val targetOriginalPresent: Boolean = false,
    val targetPreviewResident: Boolean = false,
    /** Sum of the latest owned attempts still in this window, never a session/network total. */
    val receivedBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val cacheInspectedAtMillis: Long? = null,
    val windowPages: List<ReaderSourceStandbyPage> = emptyList(),
    /** Directory intent is queued separately from image work and has no page-count denominator. */
    val manifestQueued: Boolean = false,
    /** True only while this source directory owns its existing optional-admission permit. */
    val manifestActive: Boolean = false,
)

/** Editions may split images differently; this is only an explicitly labelled resume estimate. */
internal fun approximateReaderPage(oldIndex: Int, oldCount: Int, targetCount: Int): Int {
    if (targetCount <= 1) return 0
    if (oldCount <= 1) return oldIndex.coerceIn(0, targetCount - 1)
    val fraction = oldIndex.coerceIn(0, oldCount - 1).toDouble() / (oldCount - 1)
    return (fraction * (targetCount - 1)).roundToInt().coerceIn(0, targetCount - 1)
}
