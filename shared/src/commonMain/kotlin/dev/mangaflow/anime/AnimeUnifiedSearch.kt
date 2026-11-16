package dev.mangaflow.anime

/** Presentation grouping only: never grants a canonical binding or an episode mapping. */
data class AnimeSearchGroup(
    val key: String,
    val subject: AnimeSubject? = null,
    val sources: List<AnimeSourceTitle> = emptyList(),
)

internal fun groupAnimeSearch(subjects: List<AnimeSubject>, results: List<AnimeSourceResult>): List<AnimeSearchGroup> {
    val groups = subjects.distinctBy { it.id }.map { AnimeSearchGroup("subject:${it.id}", it) }.toMutableList()
    // Only whitespace/case are cosmetic. Keep numbers, punctuation, season/film/special suffixes.
    fun normalized(title: String) = title.trim().lowercase().filterNot { it.isWhitespace() }
    fun matches(title: AnimeSourceTitle, group: AnimeSearchGroup): Boolean {
        val subject = group.subject
        val reference = group.sources.firstOrNull()
        val identities = listOfNotNull(subject?.externalIds) + group.sources.map { it.externalIds }
        if (identities.any { ids -> title.externalIds.any { (key, value) ->
            value.isNotBlank() && ids[key]?.takeIf { it.isNotBlank() }?.let { it != value } == true
        } }) return false
        val years = listOfNotNull(subject?.year) + group.sources.mapNotNull { it.year }
        if (title.year != null && years.any { it != title.year }) return false
        val year = subject?.year ?: reference?.year
        // A source must not collapse two distinct native records into one result card.
        if (group.sources.any { it.sourceId == title.sourceId }) return false
        val names = if (subject != null) listOf(subject.title, subject.originalTitle) else listOf(reference?.title.orEmpty())
        val sameName = names.any { it.isNotBlank() && normalized(it) == normalized(title.title) }
        val sharedId = identities.any { ids -> title.externalIds.any { (key, value) ->
            value.isNotBlank() && ids[key] == value
        } }
        // Unknown year/title-only similarity is not sufficient evidence, especially for remakes.
        return sameName && (sharedId || (year != null && title.year == year))
    }
    results.flatMap { it.items }.distinctBy { Triple(it.sourceId, it.id, it.url) }.forEach { title ->
        val candidates = groups.indices.filter { matches(title, groups[it]) }
        val index = candidates.singleOrNull()
        if (index == null) groups += AnimeSearchGroup("source:${title.sourceId}:${title.id}:${title.url}", sources = listOf(title))
        else groups[index] = groups[index].copy(sources = groups[index].sources + title)
    }
    return groups
}
