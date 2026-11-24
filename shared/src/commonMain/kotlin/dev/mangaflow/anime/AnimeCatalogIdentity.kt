package dev.mangaflow.anime

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.platform.normalizeNfkc

/** A catalogue's own names remain intact; stripped series names are only search hints elsewhere. */
internal fun animeCatalogueNames(subject: AnimeSubject): List<String> =
    (listOf(subject.title, subject.originalTitle) + subject.aliases)
        .map(String::trim).filter { it.isNotBlank() && it.length <= 1000 }.distinct().take(66)

internal fun animeBangumiReferenceId(value: String?): Int? = value?.removePrefix("bangumi:")
    ?.toIntOrNull()?.takeIf { it > 0 && value == "bangumi:$it" }

/** Never reuse a global Bangumi episode key under a different local work owner. */
internal fun animeCatalogEpisodeId(subject: AnimeSubject, origin: String, episodeKey: String): String {
    require(subject.canonicalDoubanId() != null && origin.isNotBlank() && episodeKey.isNotBlank())
    return "${subject.id}:episode:${animeHash(origin + "\n" + episodeKey)}"
}

internal fun animeCatalogEpisodeOwnedBy(subject: AnimeSubject, episode: AnimeEpisode): Boolean =
    subject.canonicalDoubanId() != null && episode.subjectId == subject.id &&
        Regex("${Regex.escape(subject.id)}:episode:[a-f0-9]{64}").matches(episode.id)

/** Incomplete search rows may be inspected, but are not sufficient for an automatic metadata link. */
internal fun animeMetadataSubjectCandidate(primary: AnimeSubject, candidate: AnimeSubject): Boolean {
    if (primary.canonicalDoubanId() == null || candidate.canonicalBangumiId() == null) return false
    if (primary.year != null && candidate.year != null && primary.year != candidate.year) return false
    if (primary.format != null && candidate.format != null && primary.format != candidate.format) return false
    if (conflictingAnimeIds(animeAuthoritativeIds(primary.externalIds), animeAuthoritativeIds(candidate.externalIds))) return false
    val left = animeCatalogueNames(primary)
    val right = animeCatalogueNames(candidate)
    if (animeWorkTitlesConflict(left, right, requireMatchingEdition = true)) return false
    return left.flatMap(::animeWorkDisplayKeys).toSet().intersect(right.flatMap(::animeWorkDisplayKeys).toSet()).isNotEmpty()
}

private val catalogueScope = Regex(
    """第[一二三四五六七八九十百0-9]+(?:[季期部章]|クール)|(?:^|[^a-z0-9])(?:season|part|cour)\s*[0-9]+|(?:^|[^a-z0-9])[0-9]+(?:st|nd|rd|th)\s+season|(?:final|last)\s+season|年番\s*[一二三四五六七八九十0-9]+|(?:篇|編|前半|後半|后半|終章|终章|完結|完结)|\s+[2-9]$|(?:\s|[\u3400-\u9fff])[ivx]{1,4}$""",
    RegexOption.IGNORE_CASE,
)

/** Both catalogues must describe one complete work, not merely share an abbreviated series alias. */
internal fun animeMetadataSubjectsMatch(primary: AnimeSubject, candidate: AnimeSubject): Boolean {
    if (!animeMetadataSubjectCandidate(primary, candidate) || primary.year == null || candidate.year != primary.year ||
        primary.format == null || candidate.format != primary.format) return false
    if (primary.episodeCount != null && candidate.episodeCount != null && primary.episodeCount != candidate.episodeCount) return false
    val left = animeCatalogueNames(primary)
    val right = animeCatalogueNames(candidate)
    val leftKeys = left.flatMap(::animeWorkDisplayKeys).toSet()
    val rightKeys = right.flatMap(::animeWorkDisplayKeys).toSet()
    val leftPrimary = listOf(primary.title, primary.originalTitle).filter(String::isNotBlank).flatMap(::animeWorkDisplayKeys).toSet()
    val rightPrimary = listOf(candidate.title, candidate.originalTitle).filter(String::isNotBlank).flatMap(::animeWorkDisplayKeys).toSet()
    if (leftPrimary.intersect(rightKeys).isEmpty() || rightPrimary.intersect(leftKeys).isEmpty()) return false
    // A shared generic alias must not hide a named cour/chapter/season on either side.
    val leftScoped = left.filter { catalogueScope.containsMatchIn(normalizeNfkc(it)) }
    val rightScoped = right.filter { catalogueScope.containsMatchIn(normalizeNfkc(it)) }
    if (leftScoped.isNotEmpty() || rightScoped.isNotEmpty()) {
        if (leftScoped.flatMap(::animeWorkDisplayKeys).toSet()
                .intersect(rightScoped.flatMap(::animeWorkDisplayKeys).toSet()).isEmpty()) return false
    }
    return true
}

/** Only a confirmed supplementary record can contribute aliases or IDs, never cover/title/score. */
internal fun enrichAnimeCatalogueSubject(primary: AnimeSubject, metadata: AnimeSubject): AnimeSubject {
    require(primary.canonicalDoubanId() != null && metadata.canonicalBangumiId() != null)
    val additions = metadata.externalIds.filter { (key, value) ->
        value.isNotBlank() && (primary.externalIds[key].isNullOrBlank() || primary.externalIds[key] == value)
    }
    return primary.copy(
        originalTitle = primary.originalTitle.ifBlank { metadata.originalTitle },
        episodeCount = primary.episodeCount ?: metadata.episodeCount,
        aliases = (primary.aliases + animeCatalogueNames(metadata)).map(String::trim)
            .filter { it.isNotBlank() && it != primary.title && it != primary.originalTitle }.distinct().take(64),
        externalIds = primary.externalIds + additions,
    )
}

private fun meaningfulEpisodeTitle(title: String): String? {
    val key = animeWorkTitleKey(title)
    if (key.isBlank() || key in setOf("正片", "高清", "hd", "full", "fullmovie")) return null
    if (Regex("""^(?:第)?[0-9]+(?:\.[0-9]+)?(?:集|话|話|回)?$|^(?:episode|ep)[0-9]+(?:\.[0-9]+)?$""").matches(key)) return null
    return key
}

/**
 * Supplement an existing local catalogue without replacing any local key or assuming list position.
 * Source-owned catalogues keep their exact rows. Only a catalogue originally built from these exact
 * Bangumi records may append newly returned episodes during an airing season.
 */
internal fun projectAnimeMetadataEpisodes(
    subject: AnimeSubject,
    metadata: AnimeDetail,
    previous: AnimeDetail? = null,
): List<AnimeEpisode> {
    require(subject.canonicalDoubanId() != null && metadata.subject.canonicalBangumiId() != null)
    require(previous == null || previous.subject.id == subject.id)
    val reference = metadata.subject.id
    val remote = metadata.episodes
    require(remote.all { it.subjectId == reference && animeBangumiReferenceId(it.id) != null && it.number.isFinite() })
    require(remote.map { it.id }.distinct().size == remote.size)
    val local = previous?.episodes.orEmpty()
    require(local.all { animeCatalogEpisodeOwnedBy(subject, it) && it.number.isFinite() })
    require(local.map { it.id }.distinct().size == local.size)
    fun newEpisode(episode: AnimeEpisode) = episode.copy(
        id = animeCatalogEpisodeId(subject, "bangumi", episode.id), subjectId = subject.id,
        metadataEpisodeId = episode.id, metadataSubjectId = reference,
    )
    if (local.isEmpty()) return remote.map(::newEpisode)

    val localMain = local.filter { it.kind == AnimeEpisodeKind.MAIN }
    val remoteMain = remote.filter { it.kind == AnimeEpisodeKind.MAIN }
    val localNumbers = localMain.map { it.number }.sorted()
    val remoteNumbers = remoteMain.map { it.number }.sorted()
    val seasonNumbers = remoteMain.mapNotNull { it.numberInSubject?.takeIf { value -> value.isFinite() && value > 0.0 } }
        .takeIf { it.size == remoteMain.size && it.distinct().size == it.size }?.sorted()
    val sameNumbering = localNumbers.size > 1 && localNumbers.distinct().size == localNumbers.size &&
        (localNumbers == remoteNumbers || localNumbers == seasonNumbers)
    val singleMovie = subject.format == AnimeDiscoveryFormat.MOVIE && metadata.subject.format == AnimeDiscoveryFormat.MOVIE &&
        localMain.size == 1 && remoteMain.size == 1
    val remoteByLocalId = remote.associateBy { animeCatalogEpisodeId(subject, "bangumi", it.id) }
    val localNumberCounts = local.groupingBy { it.kind to it.number }.eachCount()
    val remoteByNumber = mutableMapOf<Pair<AnimeEpisodeKind, Double>, MutableList<AnimeEpisode>>()
    remote.forEach { episode ->
        remoteByNumber.getOrPut(episode.kind to episode.number) { mutableListOf() }.add(episode)
        episode.numberInSubject?.takeIf { it.isFinite() && it > 0.0 && it != episode.number }?.let { number ->
            remoteByNumber.getOrPut(episode.kind to number) { mutableListOf() }.add(episode)
        }
    }
    val projected = local.map { episode ->
        val matches = remoteByNumber[episode.kind to episode.number].orEmpty()
        val previousReference = matches.singleOrNull {
            it.id == episode.metadataEpisodeId && episode.metadataSubjectId == reference ||
                remoteByLocalId[episode.id]?.id == it.id
        }
        val candidate = previousReference ?: matches.singleOrNull()
        val sameTitle = candidate?.let { meaningfulEpisodeTitle(episode.title)?.let { title -> title == meaningfulEpisodeTitle(it.title) } } == true
        val uniqueLocalNumber = localNumberCounts[episode.kind to episode.number] == 1
        val proven = candidate != null && uniqueLocalNumber && (previousReference != null || sameTitle ||
            episode.kind == AnimeEpisodeKind.MAIN && (sameNumbering || singleMovie))
        if (proven && candidate != null) episode.copy(metadataEpisodeId = candidate.id, metadataSubjectId = reference,
            numberInSubject = candidate.numberInSubject ?: episode.numberInSubject)
        else episode.copy(metadataEpisodeId = null, metadataSubjectId = null)
    }
    val duplicates = projected.mapNotNull { it.metadataEpisodeId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val unique = projected.map { if (it.metadataEpisodeId in duplicates)
        it.copy(metadataEpisodeId = null, metadataSubjectId = null) else it }
    val originallyMetadataOwned = local.all { episode ->
        val retainedReference = episode.metadataSubjectId == reference && episode.metadataEpisodeId?.let { id ->
            animeBangumiReferenceId(id) != null && episode.id == animeCatalogEpisodeId(subject, "bangumi", id)
        } == true
        // Unlinking clears remote references, not the stable local key's original provenance.
        retainedReference || remoteByLocalId[episode.id]?.let { observed ->
            observed.kind == episode.kind && (observed.number == episode.number || observed.numberInSubject == episode.number)
        } == true
    }
    if (!originallyMetadataOwned) return unique
    val known = local.map { it.id }.toSet()
    return unique + remote.filterNot { animeCatalogEpisodeId(subject, "bangumi", it.id) in known }.map(::newEpisode)
}
