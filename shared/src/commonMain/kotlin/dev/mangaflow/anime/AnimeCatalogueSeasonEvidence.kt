package dev.mangaflow.anime

import dev.mangaflow.anime.sources.sourceEpisodeMarker

/** The playback option always keeps the real source row, even if its number is local to a season. */
private fun regularCatalogueRows(rows: List<AnimeSourceEpisode>): Map<Double, AnimeSourceEpisode> {
    val regular = rows.filter { it.kind == AnimeEpisodeKind.MAIN }
    if (regular.isEmpty() || regular.any { it.id.isBlank() || it.url.isBlank() || !wholeEpisodeNumber(it.number) } ||
        regular.map { it.edition }.distinct().size != 1 || regular.map { it.id }.distinct().size != regular.size) return emptyMap()
    return regular.associateBy { requireNotNull(it.number) }.takeIf { it.size == regular.size }.orEmpty()
}

private fun wholeEpisodeNumber(number: Double?): Boolean =
    number != null && number.isFinite() && number > 0.0 && number % 1.0 == 0.0

private fun regularWorkEpisodes(detail: AnimeDetail): List<AnimeEpisode> = detail.episodes.filter {
    it.kind == AnimeEpisodeKind.MAIN && it.subjectId == detail.subject.id && it.id.isNotBlank() && wholeEpisodeNumber(it.number)
}

/** Only a caption unique in each independent catalogue can establish a number correspondence.
 * Keep all observed offsets; conflicting anchors rule out that directory's proposed numbering. */
private fun captionOffsets(
    expected: Map<String, AnimeEpisode>, rows: Map<Double, AnimeSourceEpisode>,
): Map<Double, List<String>> {
    if (expected.isEmpty()) return emptyMap()
    return uniqueAnimeEpisodeCaptions(rows.values, AnimeSourceEpisode::title).mapNotNull { (caption, row) ->
        expected[caption]?.let { episode -> episode.number - requireNotNull(row.number) to caption }
    }.groupBy({ it.first }, { it.second })
}

/** A partial season with a missing publication year can still prove the named work from two independent captions. */
internal fun animeNamedCatalogueEpisodeEvidence(detail: AnimeDetail, catalog: AnimeSourceCatalog): Boolean {
    if (catalog.notice != null || detail.subject.year != null && catalog.title.year != null ||
        detail.episodes.size < 2 || !autoPlayNamedSubjectCandidate(detail.subject, catalog.title)) return false
    val episodes = regularWorkEpisodes(detail)
    if (episodes.size < 2 || episodes.map { it.number }.distinct().size != episodes.size) return false
    val captions = uniqueAnimeEpisodeCaptions(episodes, AnimeEpisode::title)
    if (captions.size < 2) return false
    val season = animeCatalogueNames(detail.subject).mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull()
    return catalog.episodes.groupBy { it.group }.values.any { group ->
        val rows = regularCatalogueRows(group)
        val matching = if (season == null) rows else rows.filterValues { row ->
            (row.catalogueSeason == null || row.catalogueSeason == season) &&
                sourceEpisodeMarker(row.title)?.season?.let { it == season } != false
        }
        val agreement = captionOffsets(captions, matching).entries.singleOrNull()
        agreement != null && agreement.key >= 0.0 && agreement.value.size >= 2
    }
}

/** An unscoped search hit alone cannot identify a later season. Admit only rows owned by the
 * confirmed season metadata and an explicit provider season, or a continuous directory that
 * actually crosses its boundary and agrees on distinctive episode captions at their absolute numbers. */
internal fun animeContinuingCatalogueRows(
    detail: AnimeDetail, catalog: AnimeSourceCatalog,
): Map<String, Map<Double, AnimeSourceEpisode>> {
    val subject = detail.subject
    if (catalog.notice != null || !animeContinuingCatalogueCandidate(subject, catalog.title)) return emptyMap()
    val season = animeContinuingSeason(subject) ?: return emptyMap()
    val direct = subject.canonicalBangumiId() != null
    val reference = detail.metadataSubject?.takeIf { it.canonicalBangumiId() != null }?.id
    val episodes = regularWorkEpisodes(detail).filter { episode ->
        if (direct) animeBangumiReferenceId(episode.id) != null
        else reference != null && episode.metadataSubjectId == reference &&
            animeBangumiReferenceId(episode.metadataEpisodeId) != null && animeCatalogEpisodeOwnedBy(subject, episode)
    }
    val byNumber = episodes.associateBy { it.number }
    if (episodes.isEmpty() || byNumber.size != episodes.size || episodes.map { it.id }.distinct().size != episodes.size) return emptyMap()
    val withLocal = episodes.filter { wholeEpisodeNumber(it.numberInSubject) }
    val metadataOffsets = withLocal.map { it.number - requireNotNull(it.numberInSubject) }.distinct()
    if (metadataOffsets.size > 1 || metadataOffsets.any { it < 0.0 }) return emptyMap()
    val declaredOffset = metadataOffsets.singleOrNull()?.takeIf { withLocal.any { episode -> episode.numberInSubject == 1.0 } }
    val first = declaredOffset?.plus(1.0) ?: episodes.minOf { it.number }
    val captions = uniqueAnimeEpisodeCaptions(episodes, AnimeEpisode::title)
    val result = mutableMapOf<String, Map<Double, AnimeSourceEpisode>>()
    for ((group, raw) in catalog.episodes.groupBy { it.group }) {
        val rows = regularCatalogueRows(raw)
        if (rows.isEmpty()) continue
        val reportedSeasons = rows.values.map { it.catalogueSeason }.distinct()
        if (reportedSeasons.size != 1) continue
        val markers = rows.mapValues { (_, row) -> sourceEpisodeMarker(row.title) }
        val reportedSeason = reportedSeasons.single() ?: markers.values.map { it?.season }.distinct().singleOrNull()
        if (reportedSeason != null && reportedSeason != season) continue
        val matching = rows.filterKeys { markers[it]?.season?.let { actual -> actual == season } != false }
        val anchors = captionOffsets(captions, matching)
        if (anchors.size > 1) continue
        val agreement = anchors.entries.singleOrNull()
        val offset = if (reportedSeason == season) {
            // Typed season feeds can carry global or local numbers. Where both interpretations
            // are possible, require confirming captions instead of arbitrarily preferring one.
            val knownOffsets = setOfNotNull(0.0, declaredOffset)
            val observedOffset = agreement?.key
            if (observedOffset != null) {
                if (observedOffset < 0.0 || declaredOffset != null && observedOffset !in knownOffsets ||
                    declaredOffset == null && (first > 1.0 || observedOffset != 0.0) && agreement.value.size < 2) continue
                observedOffset
            } else {
                // A typed season identifies the work but cannot tell 13 (overall) from 13 (local).
                if (declaredOffset == null && first > 1.0) continue
                val fits = knownOffsets.filter { shift -> rows.keys.any { it + shift in byNumber } }
                fits.singleOrNull() ?: fits.singleOrNull { shift -> rows.keys.all { it + shift in byNumber } } ?: continue
            }
        } else {
            if (first <= 1.0 || first !in rows || first - 1.0 !in rows || agreement?.key != 0.0) continue
            // Two captions can establish the slice when an older metadata record lacks local
            // season numbers. One longer distinct title also suffices alongside the verified boundary.
            if (agreement.value.size < 2 && (declaredOffset == null || agreement.value.single().length < 4 ||
                    first - 2.0 !in rows)) continue
            0.0
        }
        val slice = matching.mapNotNull { (number, source) ->
            val episode = byNumber[number + offset] ?: return@mapNotNull null
            if (offset != 0.0 && episode.numberInSubject != null && episode.numberInSubject != number) return@mapNotNull null
            val marker = markers[number]
            if (marker != null && marker.number != number && marker.number != episode.number) return@mapNotNull null
            episode.number to source
        }.toMap()
        if (slice.isNotEmpty()) result[group] = slice
    }
    return result
}
