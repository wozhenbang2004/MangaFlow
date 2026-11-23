package dev.mangaflow.anime

import dev.mangaflow.anime.sources.isLmm85Source
import dev.mangaflow.anime.sources.sourceEpisodeNumber
import dev.mangaflow.data.bodySourceSearchTerms
import dev.mangaflow.platform.normalizeNfkc
import dev.mangaflow.platform.transliterate
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private val animeTitleTextLock = SynchronizedObject()
private val animeTitleTexts = LinkedHashMap<String, String>()

internal fun animeWorkTitleKey(value: String): String {
    synchronized(animeTitleTextLock) { animeTitleTexts[value] }?.let { return it }
    val normalized = normalizeNfkc(value)
    val text = if (normalized.any { it in '\u3400'..'\u9fff' })
        transliterate(normalized, "Traditional-Simplified").lowercase() else normalized.lowercase()
    val result = buildString(text.length) {
        text.forEachIndexed { index, char ->
            val numericSeparator = char in ".-" && text.getOrNull(index - 1)?.isDigit() == true &&
                text.getOrNull(index + 1)?.isDigit() == true
            if (char.isLetterOrDigit() || char in "≡∞×+!?★☆♪" || numericSeparator) append(char)
        }
    }
    synchronized(animeTitleTextLock) {
        animeTitleTexts[value] = result
        if (animeTitleTexts.size > 1200) animeTitleTexts.remove(animeTitleTexts.keys.first())
    }
    return result
}

private fun episodeLabelText(value: String) = animeWorkTitleKey(value).filter { it.isLetterOrDigit() }

/** Only the explicitly written season suffix and a separated sequel number may be compared.
 * The base title and year must still agree. A bare series title never equals season two. */
private fun autoSeasonSignature(value: String): Pair<String, Int>? {
    val title = normalizeNfkc(value).trim().lowercase()
    val named = Regex("""^(.+?)\s*第([一二三四五六七八九十]|[1-9])季$""").matchEntire(title)
    val numbered = Regex("""^(.+?)\s+([2-9])$""").matchEntire(title)
    val base = named?.groupValues?.get(1) ?: numbered?.groupValues?.get(1) ?: return null
    val label = named?.groupValues?.get(2) ?: numbered?.groupValues?.get(2) ?: return null
    val number = label.toIntOrNull() ?: "一二三四五六七八九十".indexOf(label).takeIf { it >= 0 }?.plus(1) ?: return null
    return animeWorkTitleKey(base).takeIf { it.length >= 2 }?.let { it to number }
}

/** Display aliases retain the entire work name, including an explicitly written season. */
internal fun animeWorkDisplayKeys(value: String): Set<String> = buildSet {
    animeWorkTitleKey(value).takeIf(String::isNotBlank)?.let(::add)
    autoSeasonSignature(value)?.let { (base, season) -> add("season:$base:$season") }
}

private val animeExplicitSeason = Regex(
    """第([一二三四五六七八九十0-9]{1,3})[季期部]|(?:^|[^a-z0-9])season\s*([0-9]{1,2})(?:$|[^0-9])|(?:^|[^a-z0-9])([0-9]{1,2})(?:st|nd|rd|th)\s+season(?:$|[^a-z0-9])""",
)
private val animeEditionMarkers = listOf(
    "fan-edit" to Regex("""(?:^|[^a-z0-9])(?:mad|amv)\s*[)\]】）]?\s*$|^[\[【(（]\s*(?:mad|amv)\s*[\]】)）]"""),
    "live-action" to Regex("""真人版|真人电影|真人電影|実写版|(?:^|[^a-z0-9])live[ -]?action(?:$|[^a-z0-9])"""),
    "movie" to Regex("""剧场版|劇場版|电影版|電影版|大电影|大電影|(?:^|[^a-z0-9])movie(?:$|[^a-z0-9])"""),
    "ova" to Regex("""(?:^|[^a-z0-9])ova(?:$|[^a-z0-9])"""),
    "oad" to Regex("""(?:^|[^a-z0-9])oad(?:$|[^a-z0-9])"""),
    "recap" to Regex("""总集篇|總集篇|総集編|(?:^|[^a-z0-9])recap(?:$|[^a-z0-9])"""),
)

private fun animeSeasonNumber(label: String): Int? {
    label.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
    val digits = "零一二三四五六七八九"
    if (label.length == 1) return if (label == "十") 10 else digits.indexOf(label).takeIf { it > 0 }
    val tens = label.indexOf('十')
    if (tens !in 0..1 || label.length > tens + 2) return null
    val first = if (tens == 0) 1 else digits.indexOf(label[0]).takeIf { it > 0 } ?: return null
    val last = label.getOrNull(tens + 1)?.let { digits.indexOf(it).takeIf { number -> number > 0 } ?: return null } ?: 0
    return first * 10 + last
}

/** A generic alias must not erase a different season, film, fan edit or live-action version.
 * Shared IDs may explain an omitted format label, but never contradictory explicit labels. */
internal fun animeWorkTitlesConflict(
    left: List<String>, right: List<String>, requireMatchingEdition: Boolean = false,
): Boolean {
    fun seasons(names: List<String>) = names.flatMap { raw ->
        val text = normalizeNfkc(raw).lowercase()
        listOfNotNull(autoSeasonSignature(raw)?.second) + animeExplicitSeason.findAll(text).mapNotNull { match ->
            animeSeasonNumber(match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty())
        }.toList()
    }.toSet()
    val leftSeasons = seasons(left)
    val rightSeasons = seasons(right)
    if (leftSeasons.isNotEmpty() && rightSeasons.isNotEmpty() && leftSeasons != rightSeasons) return true
    val completeNames = left.map(::animeWorkTitleKey).toSet().intersect(right.map(::animeWorkTitleKey).toSet())
        .filter(String::isNotBlank)
    val bareSeasonNames = (left + right).flatMap { raw ->
        val text = normalizeNfkc(raw).lowercase()
        listOfNotNull(autoSeasonSignature(raw)?.first) + animeExplicitSeason.findAll(text).map { match ->
            animeWorkTitleKey(text.removeRange(match.range))
        }.toList()
    }.toSet()
    // A complete supplied original title may express a sequel with a subtitle instead
    // of a season label. The bare series name obtained by removing a season is excluded.
    val exactAlias = completeNames.any { it !in bareSeasonNames }
    val requireLabels = requireMatchingEdition && !exactAlias
    if (requireLabels && leftSeasons.isEmpty() != rightSeasons.isEmpty()) return true
    fun editions(names: List<String>) = animeEditionMarkers.filter { (_, pattern) ->
        names.any { pattern.containsMatchIn(normalizeNfkc(it).lowercase()) }
    }.map { it.first }.toSet()
    val leftEditions = editions(left)
    val rightEditions = editions(right)
    if ((leftEditions - rightEditions + (rightEditions - leftEditions)).any {
        it == "fan-edit" || it == "live-action"
    }) return true
    return leftEditions != rightEditions &&
        (requireLabels || (leftEditions.isNotEmpty() && rightEditions.isNotEmpty()))
}

/** Presentation-only title hint. A conflicting year is a review candidate, not an automatic identity. */
internal fun autoPlayTitleCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    val names = animeCatalogueNames(subject)
    if (animeWorkTitlesConflict(names, listOf(title.title), requireMatchingEdition = true)) return false
    val titleKey = animeWorkTitleKey(title.title)
    val season = autoSeasonSignature(title.title)
    return names.any { (titleKey.isNotBlank() && animeWorkTitleKey(it) == titleKey) ||
        (season != null && autoSeasonSignature(it) == season) }
}

/** Search recall is wider than automatic binding. Search variants still must pass full identity checks. */
internal fun autoPlaySearchKeywords(subject: AnimeSubject): List<String> {
    val ordinal = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    val names = animeCatalogueNames(subject)
    val variants = names.flatMap { raw ->
        val signature = autoSeasonSignature(raw)
        if (signature == null) listOf(raw) else {
            val numeric = Regex("""^(.+?)\s+([2-9])$""").matchEntire(raw.trim())
            val named = Regex("""^(.+?)\s*第([一二三四五六七八九十]|[1-9])季$""").matchEntire(raw.trim())
            val base = numeric?.groupValues?.get(1) ?: named?.groupValues?.get(1)
            val season = signature.second
            if (base == null || season !in 1..10) listOf(raw)
            else listOf(raw, "$base 第${ordinal[season]}季", "$base 第${season}季", base)
        }
    }
    return (names + names.flatMap { bodySourceSearchTerms(it) } + variants)
        .map(String::trim).filter { it.length in 1..500 }.distinct().take(6)
}

/** AGE uses both 第02集 and 第2集 for the same regular episode across routes.
 * Only that complete numeric label is normalized; season/special/edition text survives. */
private fun ageEpisodeLabelKey(value: String): String {
    val literal = episodeLabelText(value)
    if (literal.isBlank()) return ""
    val number = Regex("第([0-9]+)集").matchEntire(literal)?.groupValues?.get(1)?.toLongOrNull()
    return if (number != null) "regular-number:$number" else "literal:$literal"
}

internal fun autoPlaySubjectMatches(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    autoPlaySubjectEvidence(subject, title, requireEvidence = true)

/** Candidate recall may lack a year; only detailed evidence can promote it into a binding. */
internal fun autoPlaySubjectCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    autoPlaySubjectEvidence(subject, title, requireEvidence = false)

internal fun autoPlaySubjectHasSharedId(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    autoPlaySubjectMatches(subject, title) && sharesAuthoritativeAnimeId(subject, title)

internal val authoritativeAnimeIdTypes = setOf("bangumi", "anilist", "myanimelist", "douban")

/** Source-native IDs are catalogue identities, not evidence that two works differ. */
internal fun animeAuthoritativeIds(ids: Map<String, String>): Set<Pair<String, String>> = ids.mapNotNull { (type, value) ->
    val namespace = if (type == "mal") "myanimelist" else type
    value.trim().takeIf { namespace in authoritativeAnimeIdTypes && it.isNotBlank() }?.let { namespace to it }
}.toSet()

internal fun conflictingAnimeIds(left: Set<Pair<String, String>>, right: Set<Pair<String, String>>): Boolean =
    (left + right).groupBy({ it.first }, { it.second }).values.any { it.distinct().size > 1 }

internal fun animeSubjectIds(subject: AnimeSubject): Map<String, String> =
    subject.externalIds + if (subject.id.startsWith("bangumi:"))
        mapOf("bangumi" to subject.id.removePrefix("bangumi:")) else emptyMap()

private fun sharesAuthoritativeAnimeId(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    animeAuthoritativeIds(animeSubjectIds(subject)).intersect(animeAuthoritativeIds(title.externalIds)).isNotEmpty()

private fun autoPlaySubjectEvidence(subject: AnimeSubject, title: AnimeSourceTitle, requireEvidence: Boolean): Boolean {
    val subjectIds = animeSubjectIds(subject)
    val sharedKeys = subjectIds.keys.intersect(title.externalIds.keys)
    if (sharedKeys.any { key -> subjectIds[key]?.isNotBlank() == true &&
        title.externalIds[key]?.isNotBlank() == true && subjectIds[key] != title.externalIds[key] }) return false
    if (conflictingAnimeIds(animeAuthoritativeIds(subjectIds), animeAuthoritativeIds(title.externalIds))) return false
    if (subject.year != null && title.year != null && subject.year != title.year) return false
    val sharedId = sharesAuthoritativeAnimeId(subject, title)
    val names = animeCatalogueNames(subject)
    if (animeWorkTitlesConflict(names, listOf(title.title), requireMatchingEdition = !sharedId)) return false
    val titleKey = animeWorkTitleKey(title.title)
    val exactName = titleKey.isNotBlank() && names.any {
        animeWorkTitleKey(it) == titleKey
    }
    val sourceSeason = autoSeasonSignature(title.title)
    val explicitSeasonAlias = sourceSeason != null && names.any { name ->
        // Different spellings of one explicit season are allowed; the bare base and another
        // season remain different works. Year evidence is mandatory before binding.
        autoSeasonSignature(name) == sourceSeason
    }
    // A shared authoritative ID permits aliases. Name-only binding still requires both years.
    return sharedId || ((exactName || explicitSeasonAlias) &&
        (!requireEvidence || (subject.year != null && title.year == subject.year)))
}


/**
 * Only an explicitly named season with a complete, unambiguous regular-episode
 * sequence can bridge global series numbers (25..47) and season-local numbers
 * (1..23). This is not a list-position guess and never applies to an unnamed
 * base title, a partial catalog, mixed editions, specials or a conflicting year.
 */
private fun autoSeasonCatalogOffset(detail: AnimeDetail, catalog: AnimeSourceCatalog): Double? {
    if (catalog.notice != null || catalog.episodes.isEmpty() ||
        !autoPlaySubjectCandidate(detail.subject, catalog.title)) return null
    val namedSeason = autoSeasonSignature(catalog.title.title) ?: return null
    if (animeCatalogueNames(detail.subject).none {
            it.isNotBlank() && autoSeasonSignature(it) == namedSeason
        }) return null
    val canonical = detail.episodes.filter {
        it.kind == AnimeEpisodeKind.MAIN && it.number.isFinite() && it.number > 0.0
    }.sortedBy { it.number }
    if (canonical.size !in 3..100 ||
        detail.subject.episodeCount?.let { it != canonical.size } == true ||
        catalog.title.episodeCount?.let { it != canonical.size } == true) return null
    val start = canonical.first().number
    if (canonical.map { it.number } != canonical.indices.map { start + it }) return null
    val groups = catalog.episodes.groupBy { it.group }.values
    if (groups.isEmpty()) return null
    val starts = mutableSetOf<Double>()
    val editions = mutableSetOf<String>()
    for (rows in groups) {
        if (rows.size != canonical.size || rows.any {
                it.kind != AnimeEpisodeKind.MAIN || it.number == null ||
                    !it.number.isFinite() || it.number <= 0.0
            }) return null
        val ordered = rows.map { requireNotNull(it.number) }.sorted()
        val first = ordered.first()
        if (first != 1.0 && first != start ||
            ordered != ordered.indices.map { first + it }) return null
        starts += first
        editions += rows.map { it.edition }
    }
    if (starts.size != 1 || editions.size != 1) return null
    return start - starts.single()
}

/** Exact year or shared-ID evidence remains authoritative; complete explicit
 * season structure is an additional, narrower path for yearless provider data. */
internal fun autoPlayCatalogSubjectMatches(detail: AnimeDetail, catalog: AnimeSourceCatalog): Boolean =
    autoPlaySubjectMatches(detail.subject, catalog.title) || autoSeasonCatalogOffset(detail, catalog) != null

/** Explain work identity separately from episode/route coverage, using the fetched catalogue. */
internal fun animeCatalogIdentityIssue(detail: AnimeDetail, catalog: AnimeSourceCatalog): String? {
    if (autoPlayCatalogSubjectMatches(detail, catalog)) return null
    val subject = detail.subject
    val title = catalog.title
    if (conflictingAnimeIds(animeAuthoritativeIds(animeSubjectIds(subject)), animeAuthoritativeIds(title.externalIds)))
        return "来源的作品编号与当前资料不同，请核对版本。"
    if (subject.year != null && title.year != null && subject.year != title.year)
        return "来源标注 ${title.year} 年，当前作品为 ${subject.year} 年，请核对是否为同名版本。"
    if (animeWorkTitlesConflict(animeCatalogueNames(subject), listOf(title.title),
            requireMatchingEdition = !sharesAuthoritativeAnimeId(subject, title)))
        return "来源的季度或版本与当前作品尚未对应。"
    if (!autoPlaySubjectCandidate(subject, title)) return "来源标题或作品身份与当前资料尚未对应。"
    if (title.year == null) return "未取得来源的明确年份，作品身份尚未确认。"
    if (subject.year == null) return "当前作品缺少年份资料，暂不能确认来源版本。"
    return "来源的作品资料尚未确认，可核对后手动关联。"
}

/**
 * Bangumi can give one episode both a series-wide sort number and a number in
 * this subject. A verified season catalogue can start at 1 while still airing.
 */
internal fun autoPlayEpisodeNumber(detail: AnimeDetail, requested: AnimeEpisode,
    catalog: AnimeSourceCatalog): Double = AnimeEpisodeMatcher(detail, catalog).episodeNumber(requested)

internal fun hasLmmCatalogWorkId(title: AnimeSourceTitle): Boolean = when {
    title.sourceId == "anime-lmm" -> title.externalIds["lmm22"] == title.id
    isLmm85Source(title.sourceId) -> title.externalIds["lmm85"] == title.id
    else -> false
}

/** A film's complete feature has no episode number on many providers. Keep that
 * source fact intact; the confirmed movie identity supplies the one-feature scope. */
internal fun autoPlayMovieEpisodes(subject: AnimeSubject, catalog: AnimeSourceCatalog,
    selectedGroup: String? = null): Map<String, AnimeSourceEpisode> {
    if (subject.format != AnimeDiscoveryFormat.MOVIE || subject.episodeCount?.let { it != 1 } == true ||
        catalog.title.episodeCount?.let { it != 1 } == true || catalog.notice != null ||
        !autoPlaySubjectMatches(subject, catalog.title)) return emptyMap()
    val groups = catalog.episodes.groupBy { it.group }
    if (selectedGroup != null && selectedGroup !in groups) return emptyMap()
    val sameWork = catalog.sameWorkRoutes || hasLmmCatalogWorkId(catalog.title) || when (catalog.title.sourceId) {
        "anime-age" -> catalog.title.externalIds["age"] == catalog.title.id && groups.keys.all { group ->
            group.substringAfterLast(" [", "").removeSuffix("]") in
                setOf("xigua", "ffm3u8", "bfzym3u8", "wjm3u8", "lzm3u8")
        }
        "anime-girigiri" -> catalog.title.externalIds["girigiri"] == catalog.title.id &&
            groups.keys.all { Regex("""^(chs|cht) \[\d+\]$""").matches(it) }
        "anime-native-yzx" -> true // Its adapter reads one requested work's playUrlList.
        else -> false
    }
    if (groups.size > 1 && !sameWork && selectedGroup == null) return emptyMap()
    val names = animeCatalogueNames(subject).map(::episodeLabelText).toSet()
    val completeFeature = Regex("""^(?:正片|全片|完整版|完整电影|完整電影|fullmovie|full|hd|bd|uhd|高清|超清|蓝光|藍光|4k|720p|1080p|2160p)(?:高清|超清|中字|国语|國語|粤语|粵語|英语|英語|中字版|版)*$""")
    val candidates = groups.mapNotNull { (group, rows) ->
        if (!sameWork && selectedGroup != null && group != selectedGroup) return@mapNotNull null
        val feature = rows.singleOrNull { it.kind == AnimeEpisodeKind.MAIN } ?: return@mapNotNull null
        if (feature.id.isBlank() || feature.url.isBlank() || rows.count { it.id == feature.id } != 1 ||
            feature.number != null && feature.number != 1.0) return@mapNotNull null
        val label = episodeLabelText(feature.title)
        if (feature.number == null && label !in names && !completeFeature.matches(label)) return@mapNotNull null
        group to feature
    }.toMap()
    val edition = if (selectedGroup != null) candidates[selectedGroup]?.edition
        else candidates.values.map { it.edition }.distinct().singleOrNull()
    return if (edition == null) emptyMap() else candidates.filterValues { it.edition == edition }
}

/** Use one correspondence rule for automatic bindings, status and playback options. */
internal fun autoPlayEpisodeMatches(detail: AnimeDetail, requested: AnimeEpisode,
    catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): Boolean =
    AnimeEpisodeMatcher(detail, catalog).matches(requested, episode)

/** Group selection still requires the source's audited line contracts. */
internal fun autoPlayGroup(detail: AnimeDetail, requested: AnimeEpisode, catalog: AnimeSourceCatalog): String? =
    autoPlayGroups(detail, requested, catalog).firstOrNull()

internal fun autoPlayGroups(detail: AnimeDetail, requested: AnimeEpisode, catalog: AnimeSourceCatalog,
    selectedGroup: String? = null): List<String> = AnimeEpisodeMatcher(detail, catalog).groups(requested, selectedGroup)

private val appV7PlaybackRoute = Regex("独家[A-Z0-9]+|(?:备用|专线|追剧|超速)[0-9]+|西瓜|小官")

private fun appV7WorkRoutes(detail: AnimeDetail, requested: AnimeEpisode,
    groups: Map<String, List<AnimeSourceEpisode>>, selectedGroup: String?, episodeNumber: Double,
    firstNumber: Double): List<String> {
    if (requested.kind != AnimeEpisodeKind.MAIN || !episodeNumber.isFinite() || episodeNumber < 1.0) return emptyList()
    val lastNumber = detail.subject.episodeCount?.takeIf { it > 0 }?.let { firstNumber + it - 1.0 }
        ?: detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.number.isFinite() }
            .maxOfOrNull { it.number - (requested.number - episodeNumber) } ?: return emptyList()
    val matches = groups.mapNotNull { (name, rows) ->
        if (!appV7PlaybackRoute.matches(normalizeNfkc(name).trim()))
            return@mapNotNull null
        val regular = rows.filter { it.kind == AnimeEpisodeKind.MAIN }
        if (regular.any { it.number == null || !it.number.isFinite() || it.number > lastNumber } ||
            regular.map { it.edition }.distinct().size != 1)
            return@mapNotNull null
        val numbers = regular.mapNotNull { it.number }.sorted()
        if (numbers != regular.indices.map { firstNumber + it }) return@mapNotNull null
        val episode = regular.singleOrNull { it.number == episodeNumber } ?: return@mapNotNull null
        // These same-work catalogues use both "2" and "第02集". A bare number
        // is equivalent only when it agrees with the already verified episode number.
        val label = if (sourceEpisodeNumber(episode.title, exactLabel = true) == episodeNumber)
            "regular-number:${episodeNumber.toLong()}"
            else ageEpisodeLabelKey(episode.title)
        if (label.isBlank()) return@mapNotNull null
        name to (label to episode.edition)
    }
    val anchor = if (selectedGroup != null) matches.firstOrNull { it.first == selectedGroup }?.second else {
        val labels = matches.map { it.second }.distinct()
        // A differently named episode can remain selectable without vetoing the
        // routes whose numbering and edition already agree with the current episode.
        labels.singleOrNull() ?: labels.singleOrNull { it.first == "regular-number:${episodeNumber.toLong()}" }
            ?.takeIf { labels.map { it.second }.distinct().size == 1 }
    }
    return if (anchor == null) emptyList() else matches.filter { it.second == anchor }.map { it.first }
}

/** Only a confirmed AGE line may anchor a partial set of equivalent routes. */
private fun ageEquivalentEpisodeGroups(
    requested: AnimeEpisode,
    groups: Map<String, List<AnimeSourceEpisode>>,
    selectedGroup: String,
    episodeNumber: Double,
): List<String>? {
    if (!episodeNumber.isFinite()) return null
    val knownRoutes = setOf("xigua", "ffm3u8", "bfzym3u8", "wjm3u8", "lzm3u8")
    fun route(group: String): String? {
        val start = group.lastIndexOf(" [")
        if (start <= 0 || !group.endsWith("]")) return null
        return group.substring(start + 2, group.length - 1).takeIf { it in knownRoutes }
    }
    val routeCounts = groups.keys.mapNotNull(::route).groupingBy { it }.eachCount()
    val selectedRoute = route(selectedGroup) ?: return null
    if (routeCounts[selectedRoute] != 1) return null
    val anchorRows = groups[selectedGroup] ?: return null
    val anchor = anchorRows.singleOrNull {
        it.kind == requested.kind && it.number == episodeNumber
    } ?: return null
    if (anchorRows.any { it.edition != anchor.edition }) return null
    val label = ageEpisodeLabelKey(anchor.title).takeIf { it.isNotBlank() } ?: return null
    return groups.entries.filter { (name, rows) ->
        val candidateRoute = route(name)
        if (candidateRoute == null || routeCounts[candidateRoute] != 1 ||
            rows.any { it.edition != anchor.edition }) return@filter false
        val candidate = rows.singleOrNull {
            it.kind == requested.kind && it.number == episodeNumber
        } ?: return@filter false
        ageEpisodeLabelKey(candidate.title) == label
    }.map { it.key }.sortedBy { if (route(it) == "xigua") 1 else 0 }
}

/** One immutable detail/catalogue projection owns these indexes. Never retain it across
 * a catalogue, identity, manual binding or source-revision change. */
internal class AnimeEpisodeMatcher(private val detail: AnimeDetail, private val catalog: AnimeSourceCatalog) {
    val groupedEpisodes: Map<String, List<AnimeSourceEpisode>> by lazy { catalog.episodes.groupBy { it.group } }
    private val subjectMatches by lazy { autoPlaySubjectMatches(detail.subject, catalog.title) }
    private val seasonOffset by lazy { autoSeasonCatalogOffset(detail, catalog) }
    private val singleMainEpisode by lazy { detail.episodes.singleOrNull { it.kind == AnimeEpisodeKind.MAIN } }
    private val detailEpisodes by lazy { detail.episodes.toSet() }
    private val metadataOwner by lazy { detail.metadataSubject?.takeIf { it.canonicalBangumiId() != null }?.id }
    private val knownEpisodes by lazy {
        detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.numberInSubject != null }
    }
    private val knownNumbers by lazy { knownEpisodes.mapNotNull { it.numberInSubject }.toSet() }
    private val knownMetadataMatches by lazy {
        knownEpisodes.none { it.metadataSubjectId != metadataOwner || animeBangumiReferenceId(it.metadataEpisodeId) == null }
    }
    private val mainGroups by lazy { catalog.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }.groupBy { it.group } }
    private val mainGroupNumbers by lazy { mainGroups.mapValues { (_, rows) -> rows.mapNotNull { it.number }.toSet() } }
    private val validSubjectOffsets = mutableMapOf<Double, Boolean>()
    private data class Numbering(val number: Double, val seasonOffset: Double?)
    private val numberings = mutableMapOf<AnimeEpisode, Numbering>()
    private val movieGroups = mutableMapOf<String?, Map<String, AnimeSourceEpisode>>()
    private val admittedGroups = mutableMapOf<Pair<AnimeEpisode, String?>, List<String>>()
    private val sameEdition by lazy { catalog.episodes.map { it.edition }.distinct().size == 1 }
    private val automaticGroups by lazy { groupedEpisodes.filterValues { rows -> rows.all { it.playbackData == "automatic" } } }
    private val completelyNumberedGroups by lazy { groupedEpisodes.filterValues { rows ->
        rows.all { it.number != null && it.number.isFinite() } &&
            rows.map { it.kind to it.number }.distinct().size == rows.size
    } }
    private val sameNumberCoverage by lazy { groupedEpisodes.values.map { rows ->
        rows.map { it.kind to it.number }.toSet()
    }.distinct().size == 1 }
    private val literalLabels by lazy { groupedEpisodes.mapValues { (_, rows) -> rows.associate {
        (it.kind to it.number) to episodeLabelText(it.title)
    } } }
    private val ageLabels by lazy { groupedEpisodes.mapValues { (_, rows) -> rows.associate {
        (it.kind to it.number) to ageEpisodeLabelKey(it.title)
    } } }
    // Double's map equality distinguishes signed zero; episode-number equality does not.
    private fun numberKey(number: Double): Double = if (number == 0.0) 0.0 else number
    private val numberedEpisodes by lazy { groupedEpisodes.mapValues { (_, rows) ->
        rows.filter { it.number?.isFinite() == true }
            .groupBy { it.kind to numberKey(requireNotNull(it.number)) }
    } }

    private fun subjectEpisodeNumber(requested: AnimeEpisode): Double? {
        val local = requested.numberInSubject ?: return null
        val offset = requested.number - local
        val projectedMetadata = detail.subject.canonicalDoubanId() != null && metadataOwner != null &&
            requested.metadataSubjectId == metadataOwner && animeBangumiReferenceId(requested.metadataEpisodeId) != null &&
            requested in detailEpisodes
        if (!offset.isFinite() || offset <= 0.0 || catalog.notice != null ||
            requested.subjectId != detail.subject.id || (!detail.subject.id.startsWith("bangumi:") && !projectedMetadata) ||
            !subjectMatches) return null
        if (projectedMetadata && !knownMetadataMatches || local !in knownNumbers) return null
        val valid = validSubjectOffsets.getOrPut(offset) {
            1.0 in knownNumbers && knownNumbers.size == knownEpisodes.size &&
                knownEpisodes.all { it.number == offset + requireNotNull(it.numberInSubject) } &&
                mainGroups.values.any { rows -> rows.any { it.number == 1.0 } } &&
                mainGroups.all { (group, rows) ->
                    val numbers = mainGroupNumbers.getValue(group)
                    numbers.size == rows.size && numbers.all { it in knownNumbers } && numbers.any { it < 1.0 + offset }
                }
        }
        return local.takeIf { valid }
    }

    private fun numbering(requested: AnimeEpisode): Numbering = numberings.getOrPut(requested) {
        if (requested.kind != AnimeEpisodeKind.MAIN) Numbering(requested.number, null)
        else {
            val local = subjectEpisodeNumber(requested)
            val offset = if (local == null) seasonOffset else null
            Numbering(local ?: (requested.number - (offset ?: 0.0)), offset)
        }
    }

    fun episodeNumber(requested: AnimeEpisode): Double = numbering(requested).number

    private fun movieEpisodes(selectedGroup: String?): Map<String, AnimeSourceEpisode> = movieGroups.getOrPut(selectedGroup) {
        autoPlayMovieEpisodes(detail.subject, catalog, selectedGroup)
    }

    fun matches(requested: AnimeEpisode, episode: AnimeSourceEpisode): Boolean {
        if (episode.kind != requested.kind) return false
        if (episode.number != null && episode.number == episodeNumber(requested)) return true
        if (requested.kind != AnimeEpisodeKind.MAIN || requested.subjectId != detail.subject.id ||
            singleMainEpisode != requested) return false
        return movieEpisodes(episode.group)[episode.group] == episode
    }

    /** Preserve source order and the film fallback; ordinary numbered series use one lookup. */
    fun matchingEpisodes(requested: AnimeEpisode, group: String): List<AnimeSourceEpisode> {
        val rows = groupedEpisodes[group].orEmpty()
        val number = episodeNumber(requested)
        if (!number.isFinite() || requested.kind == AnimeEpisodeKind.MAIN &&
            requested.subjectId == detail.subject.id && singleMainEpisode == requested &&
            movieEpisodes(group)[group] != null) return rows.filter { matches(requested, it) }
        return rowsAtNumber(group, requested.kind, number)
    }

    private fun rowsAtNumber(group: String, kind: AnimeEpisodeKind, number: Double): List<AnimeSourceEpisode> =
        if (number.isFinite()) numberedEpisodes[group]?.get(kind to numberKey(number)).orEmpty()
        else groupedEpisodes[group].orEmpty().filter { it.kind == kind && it.number == number }

    fun groups(requested: AnimeEpisode, selectedGroup: String? = null): List<String> =
        admittedGroups.getOrPut(requested to selectedGroup) { buildGroups(requested, selectedGroup) }

    private fun buildGroups(requested: AnimeEpisode, selectedGroup: String?): List<String> {
        if (catalog.notice != null || catalog.episodes.isEmpty()) return emptyList()
        if (requested.kind == AnimeEpisodeKind.MAIN && requested.subjectId == detail.subject.id &&
            singleMainEpisode == requested) {
            val movieLines = movieEpisodes(selectedGroup)
            if (movieLines.isNotEmpty()) return movieLines.keys.toList()
        }
        val numbering = numbering(requested)
        val seasonOffset = numbering.seasonOffset
        val episodeNumber = numbering.number
        val groups = groupedEpisodes
        // An existing confirmed AGE line anchors this episode. An unrelated line's
        // missing episodes must not reject another line's exact matching current episode.
        if (selectedGroup != null && catalog.title.sourceId == "anime-age" &&
            catalog.title.externalIds["age"] == catalog.title.id &&
            catalog.title.id.toLongOrNull()?.let { it > 0L } == true) {
            ageEquivalentEpisodeGroups(requested, groups, selectedGroup, episodeNumber)?.let { return it }
        }
        // TvTFun's typed automatic group owns a season's real episode feed. Explicit
        // stations may cover only aired episodes, so their unequal coverage must not
        // invalidate that feed. Multiple seasons/editions still require user choice.
        if (catalog.title.sourceId == "anime-tvtfun") {
            val automatic = automaticGroups
            if (automatic.isNotEmpty()) {
                if (automatic.size != 1 || !sameEdition)
                    return emptyList()
                val (name, rows) = automatic.entries.single()
                if (rows.first().edition.isBlank() || name !in completelyNumberedGroups) return emptyList()
                val anchor = rowsAtNumber(name, requested.kind, episodeNumber).singleOrNull() ?: return emptyList()
                // The typed adapter embeds stations in this exact remote episode.
                val stations = groups.filter { (group, lines) ->
                    group != name && lines.singleOrNull { it.kind == anchor.kind && it.number == anchor.number }?.let {
                        it.edition == anchor.edition && it.id.startsWith(anchor.id.removeSuffix("auto")) &&
                            it.url.substringBefore("&sourceId=") == anchor.url && it.url != anchor.url
                    } == true
                }
                return listOf(name) + stations.keys
            }
        }
        if (groups.size == 1) {
            val name = groups.keys.single()
            if (episodeNumber.isFinite() && rowsAtNumber(name, requested.kind, episodeNumber).size == 1) return listOf(name)
            return emptyList()
        }
        // Verified LMM work pages carry their native work ID in each route.
        // Check the episode label and edition before admitting another route.
        if (hasLmmCatalogWorkId(catalog.title)) {
            val matches = groups.keys.mapNotNull { name ->
                rowsAtNumber(name, requested.kind, episodeNumber).singleOrNull()?.let { name to it }
            }
            fun identity(row: AnimeSourceEpisode) = ageEpisodeLabelKey(row.title) to row.edition
            val target = if (selectedGroup == null) matches.map { identity(it.second) }.distinct().singleOrNull()
                else matches.firstOrNull { it.first == selectedGroup }?.let { identity(it.second) }
            return matches.filter { target != null && identity(it.second) == target }.map { it.first }
        }
        if (catalog.sameWorkRoutes && (seasonOffset != null || subjectMatches)) {
            val firstNumber = seasonOffset?.let { offset ->
                detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.number.isFinite() && it.number > 0.0 }
                    .minOf { it.number } - offset
            } ?: 1.0
            val matching = appV7WorkRoutes(detail, requested, groups, selectedGroup, episodeNumber, firstNumber)
            if (matching.isNotEmpty()) return matching
        }
        val eligible = completelyNumberedGroups.filter { (group, _) ->
            rowsAtNumber(group, requested.kind, episodeNumber).size == 1
        }
        if (eligible.isEmpty()) return emptyList()
        // More than one edition may mean multiple seasons all restarting at episode one.
        if (!sameEdition) return emptyList()
        if (eligible.size != groups.size || !sameNumberCoverage) return emptyList()
        if (eligible.size == 1) return listOf(eligible.keys.single())
        // Giri's exact chs/cht keys are subtitle tracks inside the same native get_detail record.
        // Require complete matching episode labels as well as number/kind; never generalize this
        // contract to arbitrary line names or other providers.
        if (catalog.title.sourceId == "anime-girigiri" &&
            catalog.title.externalIds["girigiri"] == catalog.title.id && eligible.size == 2 &&
            eligible.keys.map { it.substringBefore(" [") }.toSet() == setOf("chs", "cht") &&
            eligible.keys.all { Regex("""^(chs|cht) \[\d+\]$""").matches(it) }) {
            val labels = literalLabels.values.toList()
            if (labels.distinct().size == 1 && labels.first().values.all { it.isNotBlank() })
                return eligible.keys.sortedBy { if (it.startsWith("chs [")) 0 else 1 }
        }
        // AGE's adapter constructs these routes exclusively from one requested work's
        // video.playlists response. Unknown routes or unequal episode labels stay manual.
        if (catalog.title.sourceId == "anime-age" && catalog.title.externalIds["age"] == catalog.title.id) {
            val knownRoutes = setOf("xigua", "ffm3u8", "bfzym3u8", "wjm3u8", "lzm3u8")
            fun route(group: String) = group.substringAfterLast(" [", "").removeSuffix("]")
            val routes = eligible.keys.map(::route)
            val labels = ageLabels.values.toList()
            if (routes.all { it in knownRoutes } && routes.distinct().size == routes.size &&
                labels.distinct().size == 1 && labels.first().values.all { it.isNotBlank() }) {
                // Parsing order only, not measured speed or a promise of playable streams.
                return eligible.keys.sortedBy { if (route(it) == "xigua") 1 else 0 }
            }
        }
        // yzx builds its groups from the same native playUrlList work record. Only completely
        // equivalent number/kind and original episode labels can be rotated automatically.
        if (catalog.title.sourceId == "anime-native-yzx" && eligible.size == groups.size) {
            val labels = literalLabels.values.toList()
            if (labels.distinct().size == 1 && labels.first().values.all { it.isNotBlank() })
                return eligible.keys.sorted()
        }
        // Only adapters with a typed, audited automatic-line contract can select among groups.
        // Equal numbering with an empty edition is not proof that arbitrary groups are the same season.
        return listOfNotNull(eligible.entries.filter { (_, rows) -> when (catalog.title.sourceId) {
            "anime-tvtfun" -> rows.all { it.playbackData == "automatic" } && rows.first().edition.isNotBlank()
            "anime-native-ani" -> rows.all { it.group == "自动线路" }
            else -> false
        } }.singleOrNull()?.key)
    }
}
