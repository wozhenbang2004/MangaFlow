package dev.mangaflow.storage

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot

enum class ChapterPreparationPhase { WAITING, PREPARING, COMMITTING, FAILED }

/** ViewModel-owned metadata work, before a real downloadable manifest exists. */
data class ChapterPreparationState(
    val id: String,
    val generation: Long,
    val manga: Manga,
    val chapter: Chapter,
    val routeMode: DownloadRouteMode?,
    val route: DownloadRouteSnapshot?,
    val phase: ChapterPreparationPhase,
    val error: String = "",
    val refreshingEntryId: String? = null,
) {
    companion object {
        fun key(mangaId: String, chapterId: String): String = "$mangaId\u0000$chapterId"
    }
}
