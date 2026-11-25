package dev.mangaflow.anime

internal val animeDiscoveryGenreLabels = linkedMapOf(
    "Action" to "动作", "Adventure" to "冒险", "Comedy" to "喜剧",
    "Drama" to "剧情", "Fantasy" to "奇幻", "Horror" to "恐怖", "Mahou Shoujo" to "魔法少女",
    "Mecha" to "机战", "Music" to "音乐", "Mystery" to "悬疑", "Psychological" to "心理",
    "Romance" to "恋爱", "Sci-Fi" to "科幻", "Slice of Life" to "日常", "Sports" to "运动",
    "Supernatural" to "超自然", "Thriller" to "惊悚",
)

internal fun animeDiscoveryPeerQuery(query: AnimeDiscoveryQuery): AnimeDiscoveryQuery? {
    if (!query.combined || query.calendar ||
        query.order !in setOf(AnimeDiscoveryOrder.SCORE, AnimeDiscoveryOrder.POPULARITY)) return null
    val provider = when (query.provider) {
        AnimeDiscoveryProvider.BANGUMI -> AnimeDiscoveryProvider.ANILIST
        AnimeDiscoveryProvider.ANILIST -> AnimeDiscoveryProvider.BANGUMI
        AnimeDiscoveryProvider.DOUBAN -> return null
    }
    val peerTags = query.selectedTags.map { tag ->
        when (provider) {
            AnimeDiscoveryProvider.BANGUMI -> animeDiscoveryGenreLabels[tag]
            AnimeDiscoveryProvider.ANILIST -> animeDiscoveryGenreLabels.entries.singleOrNull { it.value == tag }?.key
            AnimeDiscoveryProvider.DOUBAN -> return null
        } ?: return null
    }
    return query.copy(provider = provider, combined = false).withTags(peerTags)
}

internal val AnimeDiscoveryItem.discoveryProvider: AnimeDiscoveryProvider?
    get() = when {
        subject?.canonicalDoubanId() != null -> AnimeDiscoveryProvider.DOUBAN
        subject?.id?.startsWith("bangumi:") == true -> AnimeDiscoveryProvider.BANGUMI
        reference?.sourceId == "anime-anilist" -> AnimeDiscoveryProvider.ANILIST
        key.startsWith("bangumi:") -> AnimeDiscoveryProvider.BANGUMI
        key.startsWith("anilist:") -> AnimeDiscoveryProvider.ANILIST
        else -> null
    }

/**
 * Only a mutual unique match shares a display card. The original canonical subject
 * and foreign reference survive independently for navigation and playback checks.
 */
internal fun combineAnimeDiscoveryItems(
    primary: List<AnimeDiscoveryItem>,
    secondary: List<AnimeDiscoveryItem>,
    previous: List<AnimeDiscoveryItem> = emptyList(),
): List<AnimeDiscoveryItem> {
    val first = primary.distinctBy { it.key }.map(::DiscoveryWork)
    val second = secondary.distinctBy { it.key }.map(::DiscoveryWork)
    val firstCandidates = DiscoveryMatches(first)
    val secondCandidates = DiscoveryMatches(second)
    val firstChoices = first.associate { it.item.key to secondCandidates.uniquePeer(it) }
    val secondChoices = second.associate { it.item.key to firstCandidates.uniquePeer(it) }
    val admitted = mutableSetOf<String>()
    val interleaved = buildList<AnimeDiscoveryItem> {
        fun addWork(work: DiscoveryWork, peer: DiscoveryWork?, reciprocal: Map<String, DiscoveryWork?>) {
            if (!admitted.add(work.item.key)) return
            val counterpart = peer?.takeIf { reciprocal[it.item.key]?.item?.key == work.item.key }
            if (counterpart == null) add(work.item.withDiscoveryScore())
            else if (admitted.add(counterpart.item.key)) add(mergeDiscoveryWorks(work.item, counterpart.item))
        }
        repeat(maxOf(first.size, second.size)) { index ->
            first.getOrNull(index)?.let { addWork(it, firstChoices[it.item.key], secondChoices) }
            second.getOrNull(index)?.let { addWork(it, secondChoices[it.item.key], firstChoices) }
        }
    }
    if (previous.isEmpty()) return interleaved
    val previousPositions = previous.mapIndexed { index, item -> item.key to index }.toMap()
    val previousKeys = buildMap<String, String> {
        for (item in previous) for (identity in item.discoveryKeys()) {
            if (identity !in this) put(identity, item.key)
        }
    }
    val reservedKeys = interleaved.mapTo(hashSetOf()) { it.key }
    val claimed = hashSetOf<String>()
    return interleaved.map { item ->
        val retained = item.discoveryKeys().mapNotNull(previousKeys::get)
            .filter { it == item.key || it !in reservedKeys }.filterNot(claimed::contains)
            .minByOrNull { previousPositions.getValue(it) }
        val key = retained ?: item.key
        claimed += key
        item.copy(key = key)
    }
}

private class DiscoveryWork(val item: AnimeDiscoveryItem) {
    val provider = item.discoveryProvider
    val titles = (listOfNotNull(item.title, item.subject?.title, item.subject?.originalTitle, item.reference?.title) +
        item.aliases).filter(String::isNotBlank).distinct()
    val names = titles.flatMap(::animeWorkDisplayKeys).toSet()
    val ids = animeAuthoritativeIds(item.reference?.externalIds.orEmpty()) +
        animeAuthoritativeIds(item.subject?.let(::animeSubjectIds).orEmpty())

    fun compatible(other: DiscoveryWork): Boolean =
        provider != null && other.provider != null && provider != other.provider &&
            provider != AnimeDiscoveryProvider.DOUBAN && other.provider != AnimeDiscoveryProvider.DOUBAN &&
            (item.year == null || other.item.year == null || item.year == other.item.year) &&
            (item.format == null || other.item.format == null || item.format == other.item.format) &&
            !conflictingAnimeIds(ids, other.ids) && !animeWorkTitlesConflict(titles, other.titles,
                requireMatchingEdition = ids.intersect(other.ids).isEmpty())

    fun compatibleWithoutId(other: DiscoveryWork): Boolean {
        if (!compatible(other)) return false
        val knownFormat = item.format ?: other.item.format
        if (knownFormat != null && knownFormat != AnimeDiscoveryFormat.SERIES && item.format != other.item.format)
            return false
        val episodes = item.episodeCount
        val otherEpisodes = other.item.episodeCount
        return episodes == null || otherEpisodes == null || (episodes == 1) == (otherEpisodes == 1)
    }
}

private class DiscoveryMatches(works: List<DiscoveryWork>) {
    private val byId = works.flatMap { work -> work.ids.map { (namespace, id) -> (namespace to id) to work } }
        .groupBy({ it.first }, { it.second })
    private val byTitleAndYear = works.flatMap { work ->
        val year = work.item.year ?: return@flatMap emptyList()
        work.names.map { (it to year) to work }
    }.groupBy({ it.first }, { it.second })

    fun uniquePeer(work: DiscoveryWork): DiscoveryWork? {
        val identified = work.ids.flatMap { (namespace, id) -> byId[namespace to id].orEmpty() }.distinct()
        if (identified.isNotEmpty()) return identified.filter(work::compatible).singleOrNull()
        val year = work.item.year ?: return null
        return work.names.flatMap { byTitleAndYear[it to year].orEmpty() }.distinct()
            .filter(work::compatibleWithoutId).singleOrNull()
    }
}

private fun AnimeDiscoveryItem.discoveryKeys(): List<String> = listOfNotNull(
    key, subject?.id?.takeIf { it.startsWith("bangumi:") },
    reference?.takeIf { it.sourceId == "anime-anilist" }?.let { "anilist:${it.id}" },
)

private fun AnimeDiscoveryItem.withDiscoveryScore(): AnimeDiscoveryItem {
    val provider = discoveryProvider ?: return this
    if (sourceScores.any { it.provider == provider }) return this
    val value = rating?.takeIf { ratingScale > 0 && it in 0.0..ratingScale.toDouble() } ?: return this
    return copy(sourceScores = sourceScores + AnimeSourceScore(provider, value, ratingScale, subject?.ratingCount))
}

private fun mergeDiscoveryWorks(left: AnimeDiscoveryItem, right: AnimeDiscoveryItem): AnimeDiscoveryItem {
    val (bangumi, anilist) = if (left.discoveryProvider == AnimeDiscoveryProvider.BANGUMI) left to right else right to left
    return bangumi.copy(
        coverUrl = bangumi.coverUrl.ifBlank { anilist.coverUrl },
        coverProvider = if (bangumi.coverUrl.isBlank() && anilist.coverUrl.isNotBlank())
            AnimeDiscoveryProvider.ANILIST else AnimeDiscoveryProvider.BANGUMI,
        reference = anilist.reference,
        sourceScores = bangumi.withDiscoveryScore().sourceScores + anilist.withDiscoveryScore().sourceScores,
    )
}
