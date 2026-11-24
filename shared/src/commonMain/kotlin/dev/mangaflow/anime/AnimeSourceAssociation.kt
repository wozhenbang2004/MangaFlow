package dev.mangaflow.anime

enum class AnimeAssociationPhase {
    QUEUED, SEARCHING, PAUSED, LINKED, NO_MATCH, NEEDS_SELECTION, EPISODE_UNMATCHED, UNAVAILABLE, EXCLUDED
}

internal val AnimeAssociationPhase.checking: Boolean
    get() = this == AnimeAssociationPhase.QUEUED || this == AnimeAssociationPhase.SEARCHING

/** Structural work/episode discovery only; LINKED does not claim stream playback health. */
data class AnimeAssociationProgress(
    val phase: AnimeAssociationPhase,
    val message: String? = null,
    val episodeId: String? = null,
    val candidateChecks: List<AnimeAssociationCandidateCheck> = emptyList(),
)

data class AnimeAssociationCandidateCheck(
    val title: AnimeSourceTitle,
    val phase: AnimeAssociationPhase,
    val message: String,
)

/** Not persisted. One visible visit to one exact episode's line chooser. */
internal class EpisodeLineLookupVisit(
    val subjectId: String, val episodeId: String, val detailGeneration: Long, val restoreEpoch: Long,
)
