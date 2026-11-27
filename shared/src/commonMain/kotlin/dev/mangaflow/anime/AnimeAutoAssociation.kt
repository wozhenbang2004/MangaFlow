package dev.mangaflow.anime

import dev.mangaflow.anime.sources.SourceEpisodeMarker
import dev.mangaflow.anime.sources.isLmm85Source
import dev.mangaflow.anime.sources.sourceEpisodeIsRange
import dev.mangaflow.anime.sources.sourceEpisodeMarker
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

private val namedSeasonSuffix = Regex(
    """(?:第\s*([零〇一二三四五六七八九十0-9]{1,3})\s*(?:[季期]|シーズン)|(?:season\s*|シーズン\s*|s)([0-9]{1,2})|([0-9]{1,2})(?:st|nd|rd|th)\s+season)$""",
    RegexOption.IGNORE_CASE,
)
private val separatedSequelNumber = Regex("""\s+([2-9])$""")
private val sourceTechnicalSuffix = Regex(
    """(?:\s*[\[【(]\s*(?:2160p|1080p|720p|480p|4k|uhd|fhd|hevc|avc|av1|[hx]\.?(?:264|265))(?:[ /+_.-]+(?:2160p|1080p|720p|480p|4k|uhd|fhd|hevc|avc|av1|[hx]\.?(?:264|265)))*\s*[\]】)])+\s*$""",
    RegexOption.IGNORE_CASE,
)

private fun sourceWorkName(title: String) = normalizeNfkc(title).trim().replace(sourceTechnicalSuffix, "").trim()

private fun seasonTitleText(title: String): String {
    val text = sourceWorkName(title)
    val open = when (text.lastOrNull()) { ')' -> '('; ']' -> '['; '】' -> '【'; else -> return text }
    val bracket = text.lastIndexOf(open)
    if (bracket < 0) return text
    val inside = text.substring(bracket + 1, text.lastIndex).trim()
    return if (namedSeasonSuffix.matches(inside)) text.substring(0, bracket).trimEnd() + " " + inside else text
}

/** Only a terminal season label varies. Preserve the rest of the title, parts, cours and film editions. */
private fun writtenSeasonSuffix(value: String): MatchResult? {
    val match = namedSeasonSuffix.find(value) ?: return null
    // "Class2" ends in "s2" but is a different complete word; Japanese/Chinese need no space before S2.
    val previous = value.getOrNull(match.range.first - 1)
    if ((match.value.first().lowercaseChar() in 'a'..'z' || match.value.first().isDigit()) && previous != null &&
        (previous.lowercaseChar() in 'a'..'z' || previous.isDigit())) return null
    return match
}

private data class SourceSeason(val base: String, val number: Int, val written: Boolean)
private val sourceSeasons = LinkedHashMap<String, SourceSeason?>()

private fun sourceSeason(value: String): SourceSeason? {
    synchronized(animeTitleTextLock) { if (value in sourceSeasons) return sourceSeasons[value] }
    val text = seasonTitleText(value)
    val written = writtenSeasonSuffix(text)
    val suffix = written ?: separatedSequelNumber.find(text)
    val number = suffix?.groupValues?.drop(1)?.firstNotNullOfOrNull(::animeSeasonNumber)
    val result = if (number == null || suffix == null) null else {
        val base = text.substring(0, suffix.range.first).trimEnd { it.isWhitespace() || it in "-_:：" }
        SourceSeason(base, number, written != null)
    }
    synchronized(animeTitleTextLock) {
        sourceSeasons[value] = result
        if (sourceSeasons.size > 512) sourceSeasons.remove(sourceSeasons.keys.first())
    }
    return result
}

internal fun animeWrittenSeasonNumber(value: String): Int? = sourceSeason(value)?.takeIf { it.written }?.number

private fun sourceSeasonName(value: String): Pair<String, Int>? =
    sourceSeason(value)?.takeIf { it.base.length >= 2 }?.let { it.base to it.number }

private fun autoSeasonSignature(value: String): Pair<String, Int>? = sourceSeasonName(value)?.let { (base, season) ->
    animeWorkTitleKey(base).takeIf { it.length >= 2 }?.let { it to season }
}

/** Display aliases retain the entire work name, including an explicitly written season. */
internal fun animeWorkDisplayKeys(value: String): Set<String> = buildSet {
    animeWorkTitleKey(value).takeIf(String::isNotBlank)?.let(::add)
    autoSeasonSignature(value)?.let { (base, season) -> add("season:$base:$season") }
}

private val animeExplicitSeason = Regex(
    """第\s*([一二三四五六七八九十0-9]{1,3})\s*(?:[季期部]|シーズン)|(?:^|[^a-z0-9])(?:season\s*|シーズン\s*|s)([0-9]{1,2})(?=$|[^a-z0-9])|(?:^|[^a-z0-9])([0-9]{1,2})(?:st|nd|rd|th)\s+season(?=$|[^a-z0-9])""",
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
    if (label.isEmpty()) return null
    label.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
    val digits = "零一二三四五六七八九"
    if (label.length == 1) return if (label == "十") 10 else digits.indexOf(label).takeIf { it > 0 }
    val tens = label.indexOf('十')
    if (tens !in 0..1 || label.length > tens + 2) return null
    val first = if (tens == 0) 1 else digits.indexOf(label[0]).takeIf { it > 0 } ?: return null
    val last = label.getOrNull(tens + 1)?.let { digits.indexOf(it).takeIf { number -> number > 0 } ?: return null } ?: 0
    return first * 10 + last
}

/** User-reviewed range matching compares the two observed titles, with at least one named season. */
internal fun sameObservedAnimeSeason(left: String, right: String): Boolean {
    if (animeWrittenSeasonNumber(left) == null && animeWrittenSeasonNumber(right) == null) return false
    fun signature(title: String): Pair<String, Int>? {
        val text = sourceWorkName(title).lowercase()
        if (animeEditionMarkers.any { (_, pattern) -> pattern.containsMatchIn(text) }) return null
        val (base, season) = sourceSeasonName(text) ?: return null
        if (animeExplicitSeason.containsMatchIn(base)) return null
        return animeWorkTitleKey(base).takeIf { it.length >= 2 }?.let { it to season }
    }
    val expected = signature(left) ?: return false
    return expected == signature(right)
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

private val numberedPartOrCour = Regex(
    """第\s*[一二三四五六七八九十0-9]+\s*(?:部分|部|クール)|(?:^|[^a-z0-9])(?:part|cour)\s*[0-9]+|(?:前半|後半|后半)""",
    RegexOption.IGNORE_CASE,
)

private fun seasonAliasMatches(names: List<String>, provided: String): Boolean {
    val season = autoSeasonSignature(provided) ?: return false
    if (names.any { autoSeasonSignature(it) == season }) return true
    // A complete supplied foreign alias may omit a season that the primary name explicitly states.
    // Keep numbered parts separate from seasons and never manufacture a translation of a base title.
    if (animeWrittenSeasonNumber(provided) != season.second || names.any { numberedPartOrCour.containsMatchIn(it) }) return false
    val observed = names.mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull()
    return observed == season.second && names.any { animeWorkTitleKey(it) == season.first }
}

/** Presentation-only title hint. A conflicting year is a review candidate, not an automatic identity. */
internal fun autoPlayTitleCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    if (animeAnnualCatalogueCandidate(subject, title) || animeContinuingCatalogueCandidate(subject, title)) return true
    val names = animeCatalogueNames(subject)
    val provided = sourceWorkName(title.title)
    if (animeWorkTitlesConflict(names, listOf(provided), requireMatchingEdition = true)) return false
    val titleKey = animeWorkTitleKey(provided)
    return titleKey.isNotBlank() && names.any { animeWorkTitleKey(it) == titleKey } || seasonAliasMatches(names, provided)
}

/** Spend the first requests on the complete primary/original names and the observed series root.
 * Search for a series without a suffix only to retrieve candidates for catalogue inspection. */
internal fun autoPlaySearchKeywords(subject: AnimeSubject): List<String> {
    val names = animeCatalogueNames(subject)
    val season = names.firstNotNullOfOrNull(::sourceSeasonName)
    return buildList {
        animeAnnualCatalogueSearchTitle(subject)?.let(::add)
        add(subject.title)
        add(subject.originalTitle)
        season?.let { (base, _) -> add(base) }
        subject.aliases.firstOrNull { it.isNotBlank() && it !in this }?.let(::add)
        if (season != null) {
            val (base, number) = season
            add(if (base.any { it in '\u3400'..'\u9fff' }) "${base}第${number}季" else "$base Season $number")
        }
        names.take(2).forEach { addAll(bodySourceSearchTerms(it)) }
        addAll(names)
    }.map(String::trim).filter { it.length in 1..500 }.distinct().take(6)
}

internal fun autoPlaySubjectMatches(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    autoPlaySubjectEvidence(subject, title, requireEvidence = true)

/** Candidate recall may lack a year; only detailed evidence can promote it into a binding. */
internal fun autoPlaySubjectCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    autoPlayNamedSubjectCandidate(subject, title) || animeAnnualCatalogueCandidate(subject, title) ||
        animeContinuingCatalogueCandidate(subject, title)

internal fun autoPlayNamedSubjectCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
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

private fun conflictingSourceWorkIds(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    val subjectIds = animeSubjectIds(subject)
    return subjectIds.any { (key, expected) ->
        val observed = title.externalIds[key]
        !observed.isNullOrBlank() && expected.isNotBlank() && expected != observed
    } || conflictingAnimeIds(animeAuthoritativeIds(subjectIds), animeAuthoritativeIds(title.externalIds))
}

private val collectionSuffix = Regex("""(?:[（(【\[]\s*(?:全集|合集|全季)\s*[)）】\]]|(?:全集|合集))$""")

/** Family-only hit for further inspection. An earlier publication year may describe the whole run. */
internal fun animeContinuingCatalogueCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    if (subject.format?.let { it != AnimeDiscoveryFormat.SERIES } == true ||
        subject.canonicalBangumiId() == null && subject.canonicalDoubanId() == null ||
        subject.year != null && title.year != null && title.year > subject.year || conflictingSourceWorkIds(subject, title)) return false
    val sourceName = sourceWorkName(title.title).replace(collectionSuffix, "").trim()
    val key = animeWorkTitleKey(sourceName)
    if (key.length < 2 || animeEditionMarkers.any { it.second.containsMatchIn(sourceName.lowercase()) }) return false
    val names = animeCatalogueNames(subject)
    val season = animeContinuingSeason(subject) ?: return false
    return names.any { name ->
        val scope = sourceSeasonName(name)
        if (scope != null) scope.second == season && animeWorkTitleKey(scope.first) == key
        else !animeExplicitSeason.containsMatchIn(name.lowercase()) && animeWorkTitleKey(name) == key
    }
}

internal fun animeContinuingSeason(subject: AnimeSubject): Int? {
    val names = animeCatalogueNames(subject)
    if (names.any { numberedPartOrCour.containsMatchIn(it) }) return null
    return names.mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull()?.takeIf { it > 1 }
}

private fun autoPlaySubjectEvidence(subject: AnimeSubject, title: AnimeSourceTitle, requireEvidence: Boolean): Boolean {
    if (conflictingSourceWorkIds(subject, title)) return false
    if (subject.year != null && title.year != null && subject.year != title.year) return false
    val sharedId = sharesAuthoritativeAnimeId(subject, title)
    val names = animeCatalogueNames(subject)
    val provided = sourceWorkName(title.title)
    if (animeWorkTitlesConflict(names, listOf(provided), requireMatchingEdition = !sharedId)) return false
    val titleKey = animeWorkTitleKey(provided)
    val exactName = titleKey.isNotBlank() && names.any { animeWorkTitleKey(it) == titleKey }
    if (!sharedId && exactName && animeContinuingCatalogueCandidate(subject, title)) {
        // A translated franchise alias can appear in the metadata of every season. Only a
        // complete primary/original subtitle may confirm this season without a scoped catalogue.
        val complete = listOf(subject.title, subject.originalTitle).any { animeWorkTitleKey(it) == titleKey }
        if (!complete || names.any { autoSeasonSignature(it)?.first == titleKey }) return false
    }
    val explicitSeasonAlias = !exactName && seasonAliasMatches(names, provided)
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

/** Fetched catalogue structure can establish an independently verified season scope. */
internal fun autoPlayCatalogSubjectMatches(detail: AnimeDetail, catalog: AnimeSourceCatalog): Boolean =
    autoPlaySubjectMatches(detail.subject, catalog.title) || autoSeasonCatalogOffset(detail, catalog) != null ||
        animeAnnualCatalogueGroups(detail.subject, catalog).isNotEmpty() || animeNamedCatalogueEpisodeEvidence(detail, catalog) ||
        animeContinuingCatalogueRows(detail, catalog).isNotEmpty()

/** Explain work identity separately from episode/route coverage, using the fetched catalogue. */
internal fun animeCatalogIdentityIssue(detail: AnimeDetail, catalog: AnimeSourceCatalog): String? {
    if (autoPlayCatalogSubjectMatches(detail, catalog)) return null
    val subject = detail.subject
    val title = catalog.title
    if (animeContinuingCatalogueCandidate(subject, title))
        return "找到同系列目录，尚未能用季度标记或连续集号和分集标题核对本季。"
    if (animeAnnualCatalogueCandidate(subject, title))
        return "全集目录尚未确认年番这一季的正片范围。"
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

private val appV7PlaybackRoute = Regex("独家[A-Z0-9]+|(?:备用|专线|追剧|超速)[0-9]+|(?:西瓜|小官)[0-9]*")
private val ageRoutes = setOf("xigua", "ffm3u8", "bfzym3u8", "wjm3u8", "lzm3u8")
private val giriRoute = Regex("""^(chs|cht) \[\d+\]$""")
private val bundledWorkRoute = Regex("""线路 [0-9]+""")
private val qualityOnlyCaption = Regex("""(?:高清|超清|蓝光|hd|bd|4k|[0-9]{3,4}p)+""")

/** These route namespaces come from the provider's own audited, same-work catalogue contract. */
internal fun animeAuditedRouteNames(catalog: AnimeSourceCatalog, names: Set<String>): List<String> {
    fun uniqueRoutes(route: (String) -> String?): List<String> =
        names.mapNotNull { name -> route(name)?.let { it to name } }.groupBy({ it.first }, { it.second })
            .values.mapNotNull { it.singleOrNull() }
    return when {
        catalog.title.sourceId == "anime-age" && catalog.title.externalIds["age"] == catalog.title.id &&
            catalog.title.id.toLongOrNull()?.let { it > 0L } == true ->
            uniqueRoutes { it.substringAfterLast(" [", "").removeSuffix("]").takeIf(ageRoutes::contains) }
                .sortedBy { if (it.endsWith("[xigua]")) 1 else 0 }
        catalog.title.sourceId == "anime-girigiri" && catalog.title.externalIds["girigiri"] == catalog.title.id ->
            uniqueRoutes { giriRoute.matchEntire(it)?.groupValues?.get(1) }.sortedBy { if (it.startsWith("chs [")) 0 else 1 }
        hasLmmCatalogWorkId(catalog.title) -> names.toList()
        catalog.sameWorkRoutes && catalog.title.sourceId == "anime-native-akianime" -> names.filter(bundledWorkRoute::matches)
        catalog.sameWorkRoutes -> names.filter { appV7PlaybackRoute.matches(normalizeNfkc(it).trim()) }
        else -> emptyList()
    }
}

/** One immutable detail/catalogue projection owns these indexes. Never retain it across
 * a catalogue, identity, manual binding or source-revision change. */
internal class AnimeEpisodeMatcher(private val detail: AnimeDetail, private val catalog: AnimeSourceCatalog) {
    val groupedEpisodes: Map<String, List<AnimeSourceEpisode>> by lazy { catalog.episodes.groupBy { it.group } }
    private val subjectMatches by lazy { autoPlaySubjectMatches(detail.subject, catalog.title) ||
        animeNamedCatalogueEpisodeEvidence(detail, catalog) }
    private val seasonOffset by lazy { autoSeasonCatalogOffset(detail, catalog) }
    private val annualCatalogue by lazy { animeAnnualCatalogueCandidate(detail.subject, catalog.title) }
    private val continuingCatalogue by lazy { !annualCatalogue && !subjectMatches &&
        animeContinuingCatalogueCandidate(detail.subject, catalog.title) }
    private val scopedGroups: Map<String, Map<Double, AnimeSourceEpisode>> by lazy {
        if (annualCatalogue) animeAnnualCatalogueGroups(detail.subject, catalog).mapValues { (_, rows) ->
            rows.associateBy { requireNotNull(it.number) }
        } else animeContinuingCatalogueRows(detail, catalog)
    }
    private val singleMainEpisode by lazy { detail.episodes.singleOrNull { it.kind == AnimeEpisodeKind.MAIN } }
    private val detailEpisodes by lazy { detail.episodes.toSet() }
    private val coordinates by lazy { AnimeEpisodeCoordinates(detail, groupedEpisodes, subjectMatches, seasonOffset) }
    private val movieGroups = mutableMapOf<String?, Map<String, AnimeSourceEpisode>>()
    private val admittedGroups = mutableMapOf<Pair<AnimeEpisode, String?>, List<String>>()
    private val automaticGroups by lazy { groupedEpisodes.filterValues { rows -> rows.all { it.playbackData == "automatic" } } }
    private val subjectSeason by lazy { animeCatalogueNames(detail.subject).mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull() }
    private val typedSeasons by lazy { automaticGroups.mapValues { (_, rows) -> rows.map { it.catalogueSeason }.distinct().singleOrNull() } }
    // TvTFun creates each station under an actual native episode. Preserve that relationship even
    // when the station has only one recent release and its isolated number would be ambiguous.
    private val tvtOwners by lazy {
        val anchors = mutableMapOf<Pair<String, String>, String?>()
        for ((name, rows) in automaticGroups) for (row in rows) {
            val key = row.id.removeSuffix(":auto") to row.url
            anchors[key] = if (key in anchors) null else name
        }
        groupedEpisodes.mapNotNull { (name, rows) ->
            if (name in automaticGroups) name to name else {
                val owners = rows.map { anchors[it.id.substringBeforeLast(':') to it.url.substringBefore("&sourceId=")] }.distinct()
                owners.singleOrNull()?.let { name to it }
            }
        }.toMap()
    }
    // Double map equality distinguishes signed zero, but two episode coordinates compare it equal.
    private fun numberKey(number: Double): Double = if (number == 0.0) 0.0 else number
    private val numberedEpisodes by lazy { groupedEpisodes.mapValues { (_, rows) ->
        rows.filter { it.number?.isFinite() == true }.groupBy { it.kind to numberKey(requireNotNull(it.number)) }
    } }
    private val uniqueWorkGroups by lazy {
        if (catalog.sameWorkRoutes && !subjectMatches && seasonOffset == null) emptyList()
        else animeAuditedRouteNames(catalog, groupedEpisodes.keys)
    }

    fun canExpandEquivalentRoutes(): Boolean = annualCatalogue || continuingCatalogue ||
        catalog.title.sourceId == "anime-tvtfun" || uniqueWorkGroups.isNotEmpty()

    fun episodeNumber(requested: AnimeEpisode): Double {
        if (annualCatalogue || continuingCatalogue) return requested.number
        // A catalogue may contain both number systems. Its callers without a group retain the canonical coordinate.
        return groupedEpisodes.keys.mapNotNull { coordinates.numbered(requested, it) }.distinct().singleOrNull() ?: requested.number
    }

    private fun movieEpisodes(selectedGroup: String?): Map<String, AnimeSourceEpisode> = movieGroups.getOrPut(selectedGroup) {
        autoPlayMovieEpisodes(detail.subject, catalog, selectedGroup)
    }

    private fun scopeEpisode(requested: AnimeEpisode, group: String): AnimeSourceEpisode? {
        if (requested.kind != AnimeEpisodeKind.MAIN || requested.subjectId != detail.subject.id || requested !in detailEpisodes) return null
        return scopedGroups[group]?.get(requested.number)
    }

    fun matches(requested: AnimeEpisode, episode: AnimeSourceEpisode): Boolean =
        episode in matchingEpisodes(requested, episode.group)

    private val labelEvidence = mutableMapOf<String, Pair<Boolean, SourceEpisodeMarker?>>()

    private fun fitsEpisodeScope(row: AnimeSourceEpisode, requested: AnimeEpisode): Boolean {
        if (row.id.isBlank() || row.url.isBlank()) return false
        if (row.kind != AnimeEpisodeKind.MAIN) return true
        val (range, marker) = labelEvidence.getOrPut(row.title) { sourceEpisodeIsRange(row.title) to sourceEpisodeMarker(row.title) }
        if (range) return false
        val statedSeason = marker?.season
        if (row.catalogueSeason != null && statedSeason != null && row.catalogueSeason != statedSeason) return false
        val season = row.catalogueSeason ?: statedSeason
        if (season != null && subjectSeason != null && season != subjectSeason) return false
        if (season != null && subjectSeason == null && issuedSeasons.size > 1 && catalog.title.sourceId != "anime-tvtfun") return false
        return marker == null || row.number == null || marker.number == row.number ||
            marker.number == requested.number && row.number == requested.numberInSubject
    }

    private val issuedSeasons by lazy { catalog.episodes.mapNotNullTo(mutableSetOf()) {
        it.catalogueSeason ?: sourceEpisodeMarker(it.title)?.season
    } }

    fun matchingEpisodes(requested: AnimeEpisode, group: String): List<AnimeSourceEpisode> {
        if (annualCatalogue || continuingCatalogue) return listOfNotNull(scopeEpisode(requested, group))
        if (detail.subject.format == AnimeDiscoveryFormat.MOVIE && requested == singleMainEpisode)
            movieEpisodes(group)[group]?.let { return listOf(it) }
        if (catalog.title.sourceId == "anime-tvtfun" && group !in automaticGroups) {
            val owner = tvtOwners[group] ?: return emptyList()
            val anchor = matchingEpisodes(requested, owner).singleOrNull() ?: return emptyList()
            return listOfNotNull(tvtStation(anchor, group))
        }
        val number = coordinates.numbered(requested, group)
        val numbered = if (number?.isFinite() == true)
            numberedEpisodes[group]?.get(requested.kind to numberKey(number)).orEmpty().filter { fitsEpisodeScope(it, requested) }
            else emptyList()
        val titled = coordinates.byCaption(requested, group)?.takeIf { fitsEpisodeScope(it, requested) } ?: return numbered
        return if (numbered.isEmpty() || titled in numbered) listOf(titled) else emptyList()
    }

    fun groups(requested: AnimeEpisode, selectedGroup: String? = null): List<String> =
        admittedGroups.getOrPut(requested to selectedGroup) { buildGroups(requested, selectedGroup) }

    private fun tvtStation(anchor: AnimeSourceEpisode, group: String): AnimeSourceEpisode? {
        val number = anchor.number ?: return null
        return numberedEpisodes[group]?.get(anchor.kind to numberKey(number))?.singleOrNull { row ->
            row.edition == anchor.edition && row.id.substringBeforeLast(':') == anchor.id.removeSuffix(":auto") &&
                row.url.substringBefore("&sourceId=") == anchor.url && row.url != anchor.url
        }
    }

    private fun tvtGroups(requested: AnimeEpisode, selectedGroup: String?): List<String> {
        val name = when {
            selectedGroup != null -> tvtOwners[selectedGroup]
            automaticGroups.size == 1 -> automaticGroups.keys.single()
            subjectSeason != null -> automaticGroups.keys.singleOrNull { typedSeasons[it] == subjectSeason }
            else -> null
        } ?: return emptyList()
        if (automaticGroups.getValue(name).first().edition.isBlank() || selectedGroup == null &&
            subjectSeason != null && typedSeasons[name]?.let { it != subjectSeason } == true) return emptyList()
        val anchor = matchingEpisodes(requested, name).singleOrNull() ?: return emptyList()
        return listOf(name) + tvtOwners.keys.filter { it != name && tvtOwners[it] == name && tvtStation(anchor, it) != null }
    }

    /** The adapter has proved these routes belong to one work. Only the current episode has to agree.
     * A missing release or an extra special on some other date cannot invalidate a confirmed line today. */
    private fun equivalentGroups(requested: AnimeEpisode, selectedGroup: String?): List<String> {
        val candidates = uniqueWorkGroups.mapNotNull { name ->
            matchingEpisodes(requested, name).singleOrNull()?.let { name to it }
        }
        val anchor = if (selectedGroup == null) null else
            candidates.firstOrNull { it.first == selectedGroup }?.second ?: return emptyList()
        val edition = anchor?.edition ?: candidates.map { it.second.edition }.distinct().singleOrNull() ?: return emptyList()
        val rows = candidates.filter { it.second.edition == edition }
        fun caption(row: AnimeSourceEpisode) = animeEpisodeCaption(row.title)
        fun bareNumber(row: AnimeSourceEpisode): Boolean {
            val marker = sourceEpisodeMarker(row.title) ?: return false
            return row.number == marker.number && (marker.exact || qualityOnlyCaption.matches(animeWorkTitleKey(marker.caption)))
        }
        val expected = animeEpisodeCaption(requested.title)
        val observed = rows.mapNotNull { caption(it.second) }.distinct()
        val acceptedCaption = anchor?.let(::caption) ?: expected?.takeIf { it in observed } ?: observed.singleOrNull()
        return rows.filter { (_, row) ->
            bareNumber(row) || (acceptedCaption != null && caption(row) == acceptedCaption) ||
                anchor == row
        }.map { it.first }
    }

    private fun buildGroups(requested: AnimeEpisode, selectedGroup: String?): List<String> {
        val groups = groupedEpisodes
        if (catalog.notice != null || groups.isEmpty()) return emptyList()
        if (annualCatalogue || continuingCatalogue) {
            val matches = scopedGroups.keys.mapNotNull { group -> scopeEpisode(requested, group) }
            val edition = if (selectedGroup == null) matches.map { it.edition }.distinct().singleOrNull()
                else scopedGroups[selectedGroup]?.values?.firstOrNull()?.edition
            return matches.filter { edition != null && it.edition == edition }.map { it.group }
        }
        if (detail.subject.format == AnimeDiscoveryFormat.MOVIE && requested == singleMainEpisode)
            movieEpisodes(selectedGroup).takeIf { it.isNotEmpty() }?.let { return it.keys.toList() }
        if (catalog.title.sourceId == "anime-tvtfun" && automaticGroups.isNotEmpty()) return tvtGroups(requested, selectedGroup)
        if (groups.size == 1) {
            val name = groups.keys.single()
            return if (matchingEpisodes(requested, name).size == 1) listOf(name) else emptyList()
        }
        if (uniqueWorkGroups.isNotEmpty()) return equivalentGroups(requested, selectedGroup)
        if (catalog.title.sourceId == "anime-native-ani" && matchingEpisodes(requested, "自动线路").size == 1)
            return listOf("自动线路")
        return emptyList()
    }
}
