package dev.mangaflow.data

data class FederatedSearchSource(
    val sourceId: String,
    val sourceName: String,
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
    val failedPage: Int? = null,
)

data class FederatedSearchPage(
    val hits: List<Manga>,
    val sources: Map<String, FederatedSearchSource>,
)

data class FederatedSearchGroup(
    val manga: Manga,
    val matches: List<Manga>,
    val needsChoice: Boolean,
)

/** Remote native identity, rather than title, is the only search-result deduplication key. */
internal fun federatedMangaIdentity(manga: Manga): Pair<String, String> {
    val encoded = if (SourceKeys.isEncoded(manga.id)) SourceKeys.decodeManga(manga.id) else null
    return (encoded?.sourceId ?: manga.sourceId) to
        (encoded?.mangaId ?: manga.sourceMangaId.ifBlank { manga.id })
}

/** A visual title group never persists a work association or merges chapter identities. */
internal fun groupFederatedSearch(
    hits: List<Manga>,
    previous: Map<String, List<Manga>> = emptyMap(),
    query: String = "",
): List<FederatedSearchGroup> {
    val unknownAuthors = setOf("未知", "佚名", "暂无", "不详", "作者", "unknown", "anonymous", "null")
    fun authors(manga: Manga): Set<String> = manga.authors.flatMap {
        it.split(Regex("[/、,，;；\\n]"))
    }.map(::normalizedBodySourceAuthor).filter { it.isNotBlank() && it !in unknownAuthors }.toSet()
    fun scripts(names: Set<String>): Set<Character.UnicodeScript> = names.flatMap { name ->
        name.map { Character.UnicodeScript.of(it.code) }
    }.filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED &&
        it != Character.UnicodeScript.UNKNOWN }.toSet()
    val normalizedQuery = normalizedBodySourceTitle(query)
    val titleRelevance = mutableMapOf<String, Int>()
    fun relevance(title: String): Int {
        if (normalizedQuery.isEmpty()) return 0
        return titleRelevance.getOrPut(title) {
            val normalized = normalizedBodySourceTitle(title)
            when {
                normalized == normalizedQuery -> 3
                normalized.startsWith(normalizedQuery) -> 2
                normalized.contains(normalizedQuery) -> 1
                else -> 0 // APIs can match an alternate title that is not the displayed title.
            }
        }
    }
    return hits.distinctBy(::federatedMangaIdentity).groupBy { manga ->
        // The shared normalizer retains sequel symbols, numbers and edition words.
        normalizedBodySourceTitle(manga.title).ifBlank { "identity:${federatedMangaIdentity(manga)}" }
    }.values.map { matches ->
        val byId = matches.associateBy(Manga::id)
        val retained = previous.keys.firstNotNullOfOrNull { byId[it] }
        val canonical = retained ?: matches.firstOrNull { federatedMangaIdentity(it).first == "copy" } ?: matches.first()
        val signatures = matches.map(::authors)
        val authorScripts = signatures.map(::scripts)
        // Missing metadata and a Han/Latin transliteration are unknown, not contradictory.
        // This display grouping never relaxes verified work/chapter association rules.
        val conflictingAuthors = signatures.indices.any { left ->
            (left + 1 until signatures.size).any { right ->
                signatures[left].intersect(signatures[right]).isEmpty() &&
                    authorScripts[left].intersect(authorScripts[right]).isNotEmpty()
            }
        }
        val needsChoice = matches.size > 1 && (
            matches.groupBy { federatedMangaIdentity(it).first }.any { it.value.size > 1 } ||
                conflictingAuthors
            )
        FederatedSearchGroup(canonical, matches, needsChoice)
    }.sortedWith(compareByDescending<FederatedSearchGroup> { group -> group.matches.maxOf { relevance(it.title) } }
        .thenByDescending { group -> group.matches.maxOf { it.totalPopularity ?: it.popularity ?: -1L } })
}
