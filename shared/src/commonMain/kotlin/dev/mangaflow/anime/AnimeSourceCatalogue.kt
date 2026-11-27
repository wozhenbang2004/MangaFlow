package dev.mangaflow.anime

import dev.mangaflow.anime.sources.sourceEpisodeIsRange
import dev.mangaflow.anime.sources.sourceEpisodeMarker

internal data class AnimeSourceCatalogueProjection(
    val episodes: List<AnimeEpisode>,
    val mappings: Map<String, String>,
    val group: String,
)

/** Use observed source episode identities when a supplementary catalogue is unavailable. */
internal fun projectAnimeSourceCatalogue(
    detail: AnimeDetail,
    catalog: AnimeSourceCatalog,
    binding: AnimeSourceBinding? = null,
): AnimeSourceCatalogueProjection? {
    val subject = detail.subject
    if (subject.canonicalDoubanId() == null || catalog.notice != null || catalog.episodes.isEmpty()) return null
    val explicit = binding?.automatic == false
    val annualGroups = if (explicit) emptyMap() else animeAnnualCatalogueGroups(subject, catalog)
    if (!explicit && annualGroups.isEmpty() && !autoPlaySubjectMatches(subject, catalog.title) &&
        !animeNamedCatalogueEpisodeEvidence(detail, catalog)) return null
    val groups = annualGroups.ifEmpty { catalog.episodes.groupBy { it.group } }
    val expectedSeason = animeCatalogueNames(subject).mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull()
    val scopedTitle = expectedSeason != null && animeWrittenSeasonNumber(catalog.title.title) == expectedSeason
    val movieLines = if (explicit || annualGroups.isNotEmpty()) emptyMap()
        else autoPlayMovieEpisodes(subject, catalog, binding?.selectedGroup)
    fun valid(rows: List<AnimeSourceEpisode>): Boolean {
        if (rows.isEmpty() || rows.map { it.id }.distinct().size != rows.size || rows.any { it.id.isBlank() }) return false
        if (explicit) return rows.all { it.number == null || it.number.isFinite() }
        if (annualGroups.isNotEmpty()) return true
        if (rows.size == 1 && movieLines[rows.single().group] == rows.single()) return true
        if (rows.any { row ->
                val number = row.number
                val marker = sourceEpisodeMarker(row.title)
                val season = row.catalogueSeason ?: marker?.season
                row.kind != AnimeEpisodeKind.MAIN || sourceEpisodeIsRange(row.title) ||
                    number == null || !number.isFinite() || number <= 0.0 ||
                    number % 1.0 != 0.0 || row.url.isBlank() || marker != null && marker.number != number ||
                    season != null && season != (expectedSeason ?: 1)
            }) return false
        val numbers = rows.map { requireNotNull(it.number) }
        if (numbers.distinct().size != numbers.size || rows.map { it.edition }.distinct().size != 1) return false
        if (subject.format == AnimeDiscoveryFormat.MOVIE && rows.size != 1) return false
        if (subject.episodeCount?.let { rows.size > it } == true || catalog.title.episodeCount?.let { rows.size > it } == true) return false
        // A source may have a genuine gap, start late, or publish overall numbering for an explicitly named season.
        // Keep exactly the observed rows and their own coordinates; never fill the missing numbers.
        val maximum = subject.episodeCount ?: catalog.title.episodeCount
        return maximum == null || numbers.all { it <= maximum } || scopedTitle
    }
    val regularGroups = if (explicit || annualGroups.isNotEmpty() || movieLines.isNotEmpty()) groups else groups.mapValues { (_, rows) ->
        rows.filter { it.kind == AnimeEpisodeKind.MAIN }
    }
    fun bestSameWorkGroup(): String? {
        val candidates = animeAuditedRouteNames(catalog, regularGroups.keys).mapNotNull { name ->
            regularGroups.getValue(name).takeIf(::valid)?.let { name to it }
        }.toMap()
        if (candidates.isEmpty()) return null
        val anchor = candidates.entries.maxBy { it.value.size }
        val numbers = anchor.value.associate { requireNotNull(it.number) to it }
        // Compare overlapping releases, not the entire length. A mirror can be a week behind or miss a middle episode.
        val sameScope = candidates.values.all { rows ->
            val overlap = rows.filter { it.number?.let(numbers::containsKey) == true }
            overlap.isNotEmpty() && overlap.all { row ->
                val first = numbers.getValue(requireNotNull(row.number))
                val left = animeEpisodeCaption(first.title)
                val right = animeEpisodeCaption(row.title)
                row.edition == first.edition && (left == null || right == null || left == right)
            }
        }
        return anchor.key.takeIf { sameScope }
    }
    val group = binding?.selectedGroup?.takeIf { it in groups } ?: when {
        binding?.selectedGroup != null -> return null
        movieLines.isNotEmpty() -> movieLines.keys.sorted().first()
        groups.size == 1 -> groups.keys.single()
        annualGroups.isNotEmpty() -> {
            if (annualGroups.values.map { it.first().edition }.distinct().size != 1) return null
            annualGroups.keys.sorted().maxByOrNull { annualGroups.getValue(it).size } ?: return null
        }
        !explicit && catalog.title.sourceId == "anime-tvtfun" -> {
            val automatic = regularGroups.filterValues { rows ->
                rows.isNotEmpty() && rows.all { it.playbackData == "automatic" } && valid(rows)
            }
            val allAutomatic = groups.values.count { rows -> rows.all { it.playbackData == "automatic" } }
            if (allAutomatic == 1) automatic.keys.singleOrNull() ?: return null
            else automatic.entries.singleOrNull { (_, rows) ->
                expectedSeason != null && rows.all { it.catalogueSeason == expectedSeason }
            }?.key ?: return null
        }
        !explicit -> bestSameWorkGroup() ?: return null
        else -> return null
    }
    val movie = movieLines[group]
    val rows = movie?.let(::listOf) ?: regularGroups.getValue(group)
    if (!valid(rows)) return null
    val origin = listOf("source", catalog.title.sourceId, catalog.title.id, catalog.title.url, group).joinToString("\n")
    val observed = rows.map { episode ->
        AnimeEpisode(animeCatalogEpisodeId(subject, origin, episode.id), subject.id,
            if (movie != null) "正片" else episode.title,
            if (movie != null) 1.0 else episode.number ?: 0.0,
            if (movie != null) AnimeEpisodeKind.MAIN else if (episode.number == null) AnimeEpisodeKind.OTHER else episode.kind)
    }
    val mappings = observed.zip(rows).associate { (local, source) -> local.id to source.id }
    val previous = detail.episodes
    if (previous.isNotEmpty()) {
        // Only the original anchor can extend a source-owned catalogue. Other sources remain alternatives.
        if (binding == null || previous.any { episode ->
                val sourceId = binding.episodeMappings[episode.id] ?: return@any true
                episode.id != animeCatalogEpisodeId(subject, origin, sourceId)
            }) return null
        val updated = observed.associateBy { it.id }
        val retained = previous.map { old -> updated[old.id]?.let { new ->
            // A revised source number is a discrepancy, never permission to relabel saved progress.
            if (new.kind == old.kind && new.number == old.number) old.copy(title = new.title) else old
        } ?: old }
        val knownIds = previous.mapTo(hashSetOf()) { it.id }
        val knownNumbers = previous.filter { it.number > 0.0 }.mapTo(hashSetOf()) { it.kind to it.number }
        val added = observed.filter { it.id !in knownIds && (it.number <= 0.0 || (it.kind to it.number) !in knownNumbers) }
        val merged = retained + added
        val mergedIds = merged.mapTo(hashSetOf()) { it.id }
        return AnimeSourceCatalogueProjection(merged, binding.episodeMappings + mappings.filterKeys(mergedIds::contains), group)
    }
    return AnimeSourceCatalogueProjection(observed, mappings, group)
}
