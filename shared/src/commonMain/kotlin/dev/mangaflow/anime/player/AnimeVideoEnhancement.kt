package dev.mangaflow.anime.player

/** These are MangaFlow's small/medium CNN combinations, not upstream Mode A/B/C. */
enum class AnimeVideoEnhancementMode { OFF, FAST, QUALITY, MODEL }

/** Configuration acceptance alone cannot claim that a GPU has processed a frame. */
enum class AnimeVideoEnhancementPhase { OFF, WAITING, CONFIGURED, ACTIVE, UNAVAILABLE, FAILED }

/** An active readout describes a recent frame submission, not a configured target. */
internal const val ANIME_ENHANCED_FRAME_FRESH_MS = 1_500L

data class AnimeVideoEnhancementState(
    val mode: AnimeVideoEnhancementMode = AnimeVideoEnhancementMode.OFF,
    val phase: AnimeVideoEnhancementPhase = AnimeVideoEnhancementPhase.OFF,
    val detail: String? = null,
    val inputWidth: Int? = null,
    val inputHeight: Int? = null,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val observation: AnimeVideoEnhancementObservation? = null,
    val modelName: String? = null,
    /** Estimated post-filter frame rate, independent of actual display presentation. */
    val filteredFps: Double? = null,
    /** The native-only recovery backend actually rendered its first frame. */
    val renderedOriginalFallback: Boolean = false,
)

/** Evidence from the current enhancement pass. A submission does not prove display presentation. */
data class AnimeVideoEnhancementObservation(
    val submittedFrames: Long,
    val sampleFrames: Long? = null,
    val sampleDurationMillis: Long? = null,
    /** CPU time for one sampled submission on the rendering thread, excluding GPU execution. */
    val submitThreadCpuNanos: Long? = null,
    /** Allocated intermediate texture payload estimate, excluding the output and decoder buffers. */
    val intermediateTextureBytes: Long,
    val recentlySubmitting: Boolean = false,
    val gpuSampleSequence: Long? = null,
    val gpuExecutionNanos: Long? = null,
    val gpuSubmittedAtNanos: Long? = null,
    /** CPU observation of a completed GPU query, not an exact GPU completion timestamp. */
    val gpuCompletionObservedAtNanos: Long? = null,
    /** Populated only by a real presentation acknowledgement, never a scheduled release timestamp. */
    val presentedAtNanos: Long? = null,
    /** Monotonic completion of this successful frame submission, not display presentation. */
    val lastSubmittedAtMillis: Long? = null,
)

internal data class Anime4KShader(val name: String, val source: String)
