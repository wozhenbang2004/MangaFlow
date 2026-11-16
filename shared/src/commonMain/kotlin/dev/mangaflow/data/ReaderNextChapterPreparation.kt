package dev.mangaflow.data

enum class ReaderNextChapterPreparationPhase { QUEUED, MANIFEST, PREPARING, READY, DEFERRED, FAILED }

/** A bounded look-ahead owned by the visible chapter; never part of its current page window. */
data class ReaderNextChapterPreparation(
    val sessionEpoch: Long,
    val workId: String,
    val currentChapterId: String,
    val chapterId: String,
    val chapterTitle: String,
    val sourceId: String,
    val sourceMangaId: String,
    val phase: ReaderNextChapterPreparationPhase,
    val totalPages: Int? = null,
    val requestedPages: Int = 0,
    /** Currently inspected original artifacts, not launches or decoded previews. */
    val originalReadyPages: Int = 0,
    val queuedPages: Int = 0,
    /** Work admitted to resolution, transfer or cache inspection, not coroutine count. */
    val activePages: Int = 0,
    val resolvingPages: Int = 0,
    val downloadingPages: Int = 0,
    val checkingPages: Int = 0,
    val failedPages: Int = 0,
    val deferredPages: Int = 0,
    /** Image body bytes actually observed in this attempt; no cache-size or budget substitution. */
    val receivedBytes: Long? = null,
    val reason: String? = null,
)
