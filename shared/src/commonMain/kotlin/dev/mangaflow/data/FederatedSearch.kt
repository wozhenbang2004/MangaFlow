package dev.mangaflow.data

import dev.mangaflow.data.sources.SourceSearchCoverage

data class FederatedSearchSource(
    val sourceId: String,
    val sourceName: String,
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
    val failedPage: Int? = null,
    val coverage: SourceSearchCoverage? = null,
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

/** One serialized search load owns these bounded derivatives; no shared ICU instance is retained. */
internal class FederatedSearchGrouper(
    query: String,
    private val adult: Boolean = false,
    private val exactTitleFirst: Boolean = true,
) {
    private data class AuthorSignature(val names: Set<String>)
    private val titles = LinkedHashMap<String, String>()
    private val authors = LinkedHashMap<List<String>, AuthorSignature>()
    private val relevances = LinkedHashMap<String, Int>()
    private val normalizedQuery = normalizedBodySourceTitle(query)

    private fun normalizedTitle(title: String): String = derive(titles, title) { normalizedBodySourceTitle(title) }

    private fun authorSignature(manga: Manga): AuthorSignature = derive(authors, manga.authors.toList()) {
        val names = manga.authors.flatMap { it.split(AUTHOR_SEPARATOR) }.map(::normalizedBodySourceAuthor)
            .filter { it.isNotBlank() && it !in UNKNOWN_AUTHORS }.toSet()
        AuthorSignature(names)
    }

    private fun relevance(title: String): Int {
        if (normalizedQuery.isEmpty()) return 0
        val normalized = normalizedTitle(title)
        return derive(relevances, normalized) {
            when {
                normalized == normalizedQuery -> 3
                normalized.startsWith(normalizedQuery) -> 2
                normalized.contains(normalizedQuery) -> 1
                else -> 0 // An API can match an alternate title that is not displayed.
            }
        }
    }

    /** Visual grouping never persists a work association or merges chapter identities. */
    fun group(hits: List<Manga>, previous: Map<String, List<Manga>> = emptyMap()): List<FederatedSearchGroup> {
        val previousOrder = previous.keys.withIndex().associate { it.value to it.index }
        val sourceOrder = hits.withIndex().associate { federatedMangaIdentity(it.value) to it.index }
        val titleBuckets = hits.distinctBy(::federatedMangaIdentity).groupBy { manga ->
            // Keep sequel symbols, numbers and edition words in the complete title.
            normalizedTitle(manga.title).ifBlank { "identity:${federatedMangaIdentity(manga)}" }
        }.values
        val buckets = if (!adult) titleBuckets else titleBuckets.flatMap { matches ->
            // A verified conflict still gives that result its own card. Missing author
            // information can share a candidate group, with each native version selectable.
            val candidates = mutableListOf<MutableList<Manga>>()
            for (manga in matches.sortedBy { previousOrder[it.id] ?: Int.MAX_VALUE }) {
                val compatible = candidates.firstOrNull { group -> group.all { other ->
                    SourceDiscoveryIdentity.adultTitleMatches(other, manga) &&
                        SourceDiscoveryIdentity.authorEvidence(other, manga) !=
                            SourceDiscoveryIdentity.AuthorEvidence.CONFLICT
                } }
                if (compatible == null) candidates += mutableListOf(manga) else compatible += manga
            }
            candidates
        }
        return buckets.map { matches ->
            // An early non-Copy response must not permanently own the group when Copy arrives.
            val ordered = matches.sortedWith(compareByDescending<Manga> { isCopy(it) }
                .thenBy { previousOrder[it.id] ?: Int.MAX_VALUE })
            val retained = ordered.firstOrNull { it.id in previousOrder }
            val canonical = ordered.firstOrNull(::isCopy) ?: retained ?: ordered.first()
            val signatures = ordered.map(::authorSignature)
            // Equal titles are only a candidate group. Missing or different-script authors
            // do not prove a conflict, but also cannot establish that the entries are one work.
            val unverifiedAuthors = signatures.indices.any { left ->
                (left + 1 until signatures.size).any { right ->
                    signatures[left].names.intersect(signatures[right].names).isEmpty()
                }
            }
            val needsChoice = ordered.size > 1 && (
                adult || ordered.groupBy { federatedMangaIdentity(it).first }.any { it.value.size > 1 } || unverifiedAuthors
                )
            FederatedSearchGroup(canonical, ordered, needsChoice)
        }.sortedWith(compareByDescending<FederatedSearchGroup> { group ->
            !adult && exactTitleFirst && group.matches.any {
                relevance(it.title) == 3 || it.alternativeTitles.any { alias -> relevance(alias) == 3 }
            }
        }.thenByDescending { group -> group.matches.any(::isCopy) }
            // Keep Copy's popularity order within each exact-match tier.
            .thenByDescending { group -> group.matches.firstOrNull(::isCopy)
                ?.let { it.totalPopularity ?: it.popularity ?: -1L } ?: -1L }
            .thenByDescending { group -> if (group.matches.any(::isCopy)) 0
                else group.matches.maxOf { relevance(it.title) } }
            .thenBy { group -> group.matches.firstOrNull(::isCopy)?.let {
                sourceOrder[federatedMangaIdentity(it)]
            } ?: group.matches.mapNotNull { previousOrder[it.id] }.minOrNull() ?: Int.MAX_VALUE })
    }

    private fun isCopy(manga: Manga): Boolean = federatedMangaIdentity(manga).first == "copy"

    private fun <K, V : Any> derive(cache: LinkedHashMap<K, V>, key: K, calculate: () -> V): V {
        cache[key]?.let { return it }
        return calculate().also { value ->
            cache[key] = value
            while (cache.size > MAX_DERIVED_ENTRIES) cache.remove(cache.keys.first())
        }
    }

    private companion object {
        const val MAX_DERIVED_ENTRIES = 1_200
        val AUTHOR_SEPARATOR = Regex("[/、,，;；\\n]")
        val UNKNOWN_AUTHORS = setOf("未知", "佚名", "暂无", "不详", "作者", "unknown", "anonymous", "null")
    }
}

internal fun groupFederatedSearch(hits: List<Manga>, previous: Map<String, List<Manga>> = emptyMap(),
    query: String = ""): List<FederatedSearchGroup> = FederatedSearchGrouper(query).group(hits, previous)

internal fun groupAdultSearch(hits: List<Manga>, previous: Map<String, List<Manga>> = emptyMap()): List<FederatedSearchGroup> =
    FederatedSearchGrouper("", adult = true).group(hits, previous)
