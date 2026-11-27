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

/** A series-wide directory must not obscure an exact season returned by the same provider. */
internal fun animeCatalogueCandidateTiers(subject: AnimeSubject, candidates: List<AnimeSourceTitle>): List<List<AnimeSourceTitle>> {
    val identified = candidates.filter { autoPlaySubjectHasSharedId(subject, it) }
    if (identified.isNotEmpty()) return listOf(identified)
    val (specific, wholeSeries) = candidates.partition { autoPlayNamedSubjectCandidate(subject, it) }
    return listOf(specific, wholeSeries).filter { it.isNotEmpty() }
}

internal data class AnimeCatalogueDecision(
    val catalog: AnimeSourceCatalog? = null,
    val phase: AnimeAssociationPhase = AnimeAssociationPhase.NEEDS_SELECTION,
    val checks: List<AnimeAssociationCandidateCheck> = emptyList(),
)

/** Evaluate same-strength candidates together. Only a definite rejection allows a lower tier to win. */
internal suspend fun chooseAnimeCatalogue(
    tiers: List<List<AnimeSourceTitle>>,
    inspect: suspend (AnimeSourceTitle) -> Pair<AnimeSourceCatalog?, AnimeAssociationCandidateCheck?>,
): AnimeCatalogueDecision {
    val checks = mutableListOf<AnimeAssociationCandidateCheck>()
    var remaining = 2
    for (tier in tiers) {
        if (tier.size > remaining) {
            return AnimeCatalogueDecision(checks = checks + tier.map {
                AnimeAssociationCandidateCheck(it, AnimeAssociationPhase.NEEDS_SELECTION, "此目录仍待核对，可手动选择版本。")
            })
        }
        remaining -= tier.size
        val verified = mutableListOf<AnimeSourceCatalog>()
        var unfinished = false
        for (title in tier) {
            val (catalog, check) = inspect(title)
            if (catalog != null) verified += catalog
            if (check != null) {
                checks += check
                if (check.phase == AnimeAssociationPhase.UNAVAILABLE) unfinished = true
            }
        }
        if (verified.size == 1 && !unfinished) return AnimeCatalogueDecision(catalog = verified.single())
        if (unfinished || verified.isNotEmpty()) {
            val phase = if (unfinished) AnimeAssociationPhase.UNAVAILABLE else AnimeAssociationPhase.NEEDS_SELECTION
            return AnimeCatalogueDecision(phase = phase, checks = checks + verified.map {
                AnimeAssociationCandidateCheck(it.title, phase, if (unfinished) "另一个同版本候选还未核对完成。"
                    else "多个来源目录都符合当前作品，请核对版本。")
            })
        }
    }
    return AnimeCatalogueDecision(checks = checks)
}
