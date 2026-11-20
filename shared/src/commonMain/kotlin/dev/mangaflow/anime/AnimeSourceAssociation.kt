package dev.mangaflow.anime

enum class AnimeAssociationPhase {
    QUEUED, SEARCHING, LINKED, NO_MATCH, NEEDS_SELECTION, EPISODE_UNMATCHED, UNAVAILABLE, EXCLUDED
}

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
