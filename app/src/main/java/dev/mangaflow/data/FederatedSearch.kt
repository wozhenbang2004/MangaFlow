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
        manga.sourceMangaId.ifBlank { encoded?.mangaId ?: manga.id }
}

/** A visual title group never persists a work association or merges chapter identities. */
internal fun groupFederatedSearch(
    hits: List<Manga>,
    previous: Map<String, List<Manga>> = emptyMap(),
): List<FederatedSearchGroup> {
    val unknownAuthors = setOf("未知", "佚名", "暂无", "不详", "作者", "unknown", "anonymous", "null")
    fun authors(manga: Manga): Set<String> = manga.authors.flatMap {
        it.split(Regex("[/、,，;；\\n]"))
    }.map(::normalizedBodySourceAuthor).filter { it.isNotBlank() && it !in unknownAuthors }.toSet()
    return hits.distinctBy(::federatedMangaIdentity).groupBy { manga ->
        // The shared normalizer retains sequel symbols, numbers and edition words.
        normalizedBodySourceTitle(manga.title).ifBlank { "identity:${federatedMangaIdentity(manga)}" }
    }.values.map { matches ->
        val byId = matches.associateBy(Manga::id)
        val retained = previous.keys.firstNotNullOfOrNull { byId[it] }
        val canonical = retained ?: matches.firstOrNull { federatedMangaIdentity(it).first == "copy" } ?: matches.first()
        val signatures = matches.map(::authors)
        val needsChoice = matches.size > 1 && (
            matches.groupBy { federatedMangaIdentity(it).first }.any { it.value.size > 1 } ||
                signatures.any(Set<String>::isEmpty) || signatures.distinct().size > 1
            )
        FederatedSearchGroup(canonical, matches, needsChoice)
    }
}
