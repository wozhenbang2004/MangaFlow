package dev.mangaflow.anime

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
    if (!explicit && !autoPlaySubjectMatches(subject, catalog.title)) return null
    val groups = catalog.episodes.groupBy { it.group }
    val movieLines = if (explicit) emptyMap() else autoPlayMovieEpisodes(subject, catalog, binding?.selectedGroup)
    fun valid(rows: List<AnimeSourceEpisode>): Boolean {
        if (rows.isEmpty() || rows.map { it.id }.distinct().size != rows.size || rows.any { it.id.isBlank() }) return false
        if (explicit) return rows.all { it.number == null || it.number.isFinite() }
        if (rows.size == 1 && movieLines[rows.single().group] == rows.single()) return true
        if (rows.any { it.kind != AnimeEpisodeKind.MAIN || it.number == null || !it.number.isFinite() || it.number <= 0.0 }) return false
        val numbers = rows.map { requireNotNull(it.number) }.sorted()
        if (numbers != numbers.indices.map { it + 1.0 } || rows.map { it.edition }.distinct().size != 1) return false
        if (subject.format == AnimeDiscoveryFormat.MOVIE && rows.size != 1) return false
        if (subject.episodeCount?.let { rows.size > it } == true || catalog.title.episodeCount?.let { rows.size > it } == true) return false
        return true
    }
    val group = binding?.selectedGroup?.takeIf { it in groups } ?: when {
        binding?.selectedGroup != null -> return null
        movieLines.isNotEmpty() -> movieLines.keys.sorted().first()
        groups.size == 1 -> groups.keys.single()
        !explicit && (catalog.sameWorkRoutes || hasLmmCatalogWorkId(catalog.title)) &&
            groups.values.all(::valid) && groups.values.map { rows ->
            rows.map { Triple(it.kind, it.number, it.edition) }.sortedBy { it.second }
        }.distinct().size == 1 -> groups.keys.sorted().first()
        else -> return null
    }
    val movie = movieLines[group]
    val rows = movie?.let(::listOf) ?: groups.getValue(group)
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
        return AnimeSourceCatalogueProjection(retained + added, binding.episodeMappings + mappings.filterKeys {
            id -> (retained + added).any { it.id == id }
        }, group)
    }
    return AnimeSourceCatalogueProjection(observed, mappings, group)
}
