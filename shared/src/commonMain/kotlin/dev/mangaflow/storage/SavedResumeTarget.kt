package dev.mangaflow.storage

/** An explicit continuation choice, not an observed reading event or a page position. */
data class SavedResumeTarget(
    val mangaId: String,
    val chapterId: String,
    val chapterTitle: String,
    val accountOwner: String,
)
