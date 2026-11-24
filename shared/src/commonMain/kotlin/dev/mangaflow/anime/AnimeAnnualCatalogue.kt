package dev.mangaflow.anime

import dev.mangaflow.anime.sources.sourceEpisodeNumber

private const val overallTitle = "斗破苍穹年番"
private const val annualSeasonStart = 158
private const val annualSeasonEnd = 219
private const val annualSeasonEpisodeCount = annualSeasonEnd - annualSeasonStart + 1
private val annualSeasonEpisodes = annualSeasonStart..annualSeasonEnd

/**
 * The fourth annual run is a slice of the continuing catalogue. The publisher lists
 * episode 158 under the 2022 serial and explicitly ends this run at 219.
 * See the Bangumi episode list (subject 562145) and Tencent Video Animation's
 * episode-count adjustment (weibo.com/3889146127/R8cP97H1o).
 */
internal fun animeAnnualCatalogueSearchTitle(subject: AnimeSubject): String? {
    if (subject.year != 2025 || animeWorkTitleKey(subject.title) != "${overallTitle}4" ||
        subject.format?.let { it != AnimeDiscoveryFormat.SERIES } == true ||
        subject.episodeCount?.let { it != 52 && it != annualSeasonEpisodeCount } == true) return null
    if (subject.canonicalDoubanId() == null && subject.canonicalBangumiId() != 562145) return null
    return overallTitle
}

/** A search suggestion only. The fetched catalogue must prove its own numbering. */
internal fun animeAnnualCatalogueCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    animeAnnualCatalogueSearchTitle(subject) != null && animeWorkTitleKey(title.title) == overallTitle &&
        (title.year == null || title.year == 2022) &&
        !conflictingAnimeIds(animeAuthoritativeIds(animeSubjectIds(subject)), animeAuthoritativeIds(title.externalIds))

internal fun animeAnnualSeasonNumber(number: Double): Int? =
    number.toInt().takeIf { it in annualSeasonEpisodes && it.toDouble() == number }

internal fun animeAnnualCatalogueGroups(
    subject: AnimeSubject,
    catalog: AnimeSourceCatalog,
): Map<String, List<AnimeSourceEpisode>> {
    if (catalog.notice != null || catalog.title.year != 2022 ||
        !animeAnnualCatalogueCandidate(subject, catalog.title)) return emptyMap()
    return catalog.episodes.groupBy { it.group }.mapNotNull groupLoop@ { (group, rows) ->
        val numbered = rows.filter { it.kind == AnimeEpisodeKind.MAIN }.mapNotNull episodeLoop@ { row ->
            val number = row.number ?: return@episodeLoop null
            val integer = number.toInt()
            if (integer !in 1..annualSeasonEnd || integer.toDouble() != number) return@episodeLoop null
            integer to row
        }.groupBy({ it.first }, { it.second })
        fun regular(number: Int): AnimeSourceEpisode? = numbered[number]?.singleOrNull()?.takeIf { row ->
            row.id.isNotBlank() && row.url.isNotBlank() &&
                sourceEpisodeNumber(row.title)?.let { it == number.toDouble() } != false
        }
        // Require actual numbered rows around the verified boundary. Earlier
        // episodes may be missing from an otherwise valid continuing catalogue.
        val continuesAcrossBoundary = (annualSeasonStart - 3..annualSeasonStart).all { regular(it) != null }
        val startsAtSeason = numbered.keys.minOrNull() == annualSeasonStart &&
            (annualSeasonStart..(annualSeasonStart + 2)).all { regular(it) != null }
        if (!continuesAcrossBoundary && !startsAtSeason) return@groupLoop null
        val slice = annualSeasonEpisodes.mapNotNull(::regular)
        if (slice.map { it.edition }.distinct().size != 1 || slice.map { it.id }.distinct().size != slice.size)
            return@groupLoop null
        group to slice
    }.toMap()
}
