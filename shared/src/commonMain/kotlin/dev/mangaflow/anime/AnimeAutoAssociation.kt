package dev.mangaflow.anime

/** Cosmetic title normalization only: season, part, punctuation and edition suffixes survive. */
private fun autoTitleKey(value: String) = value.trim().lowercase().filterNot { it.isWhitespace() }

internal fun autoPlaySubjectMatches(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    val subjectIds = subject.externalIds + if (subject.id.startsWith("bangumi:"))
        mapOf("bangumi" to subject.id.removePrefix("bangumi:")) else emptyMap()
    val sharedKeys = subjectIds.keys.intersect(title.externalIds.keys)
    if (sharedKeys.any { key -> subjectIds[key]?.isNotBlank() == true &&
        title.externalIds[key]?.isNotBlank() == true && subjectIds[key] != title.externalIds[key] }) return false
    if (subject.year != null && title.year != null && subject.year != title.year) return false
    val exactName = listOf(subject.title, subject.originalTitle).any {
        it.isNotBlank() && autoTitleKey(it) == autoTitleKey(title.title)
    }
    if (!exactName) return false
    val sharedId = sharedKeys.any { key -> key in setOf("bangumi", "anilist", "mal", "douban") &&
        subjectIds[key]?.isNotBlank() == true && subjectIds[key] == title.externalIds[key] }
    return sharedId || (subject.year != null && title.year == subject.year)
}

/** Do not select between seasons/editions, or infer episode identity from list position. */
internal fun autoPlayGroup(detail: AnimeDetail, requested: AnimeEpisode, catalog: AnimeSourceCatalog): String? {
    if (catalog.notice != null || catalog.episodes.isEmpty()) return null
    val groups = catalog.episodes.groupBy { it.group }
    val eligible = groups.filterValues { rows ->
        rows.count { it.number == requested.number && it.kind == requested.kind } == 1 &&
            rows.all { it.number != null && it.number.isFinite() } &&
            rows.map { it.kind to it.number }.distinct().size == rows.size
    }
    if (eligible.isEmpty()) return null
    // More than one edition may mean multiple seasons all restarting at episode one.
    if (catalog.episodes.map { it.edition }.distinct().size != 1) return null
    val coverage = eligible.values.map { rows -> rows.map { it.kind to requireNotNull(it.number) }.toSet() }
    if (coverage.distinct().size != 1 || eligible.size != groups.size) return null
    val canonicalKeys = detail.episodes.map { it.kind to it.number }.toSet()
    if (!coverage.first().all { it in canonicalKeys }) return null
    if (eligible.size == 1) return eligible.keys.single()
    // Only adapters with a typed, audited automatic-line contract can select among groups.
    // Equal numbering with an empty edition is not proof that arbitrary groups are the same season.
    return eligible.entries.filter { (_, rows) -> when (catalog.title.sourceId) {
        "anime-tvtfun" -> rows.all { it.playbackData == "automatic" } && rows.first().edition.isNotBlank()
        "anime-native-ani" -> rows.all { it.group == "自动线路" }
        else -> false
    } }.singleOrNull()?.key
}
