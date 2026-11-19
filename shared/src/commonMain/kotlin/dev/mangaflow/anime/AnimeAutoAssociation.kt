package dev.mangaflow.anime

/** Ignore cosmetic spacing and punctuation, never drop episode, sequel or season identity. */
private fun autoTitleKey(value: String) = value.trim().lowercase().filter { it.isLetterOrDigit() }

/** Only the explicitly written season suffix and a separated sequel number may be compared.
 * The base title and year must still agree. A bare series title never equals season two. */
private fun autoSeasonSignature(value: String): Pair<String, Int>? {
    val title = value.trim().lowercase()
    val named = Regex("""^(.+?)\s*第([一二三四五六七八九十]|[1-9])季$""").matchEntire(title)
    val numbered = Regex("""^(.+?)\s+([2-9])$""").matchEntire(title)
    val base = named?.groupValues?.get(1) ?: numbered?.groupValues?.get(1) ?: return null
    val label = named?.groupValues?.get(2) ?: numbered?.groupValues?.get(2) ?: return null
    val number = label.toIntOrNull() ?: "一二三四五六七八九十".indexOf(label).takeIf { it >= 0 }?.plus(1) ?: return null
    return autoTitleKey(base).takeIf { it.length >= 6 }?.let { it to number }
}

/** Presentation-only title hint. A conflicting year is a review candidate, not an automatic identity. */
internal fun autoPlayTitleCandidate(subject: AnimeSubject, title: AnimeSourceTitle): Boolean {
    val names = listOf(subject.title, subject.originalTitle).filter(String::isNotBlank)
    return names.any { autoTitleKey(it) == autoTitleKey(title.title) ||
        (autoSeasonSignature(it) != null && autoSeasonSignature(it) == autoSeasonSignature(title.title)) }
}

/** Search recall is wider than automatic binding. Search variants still must pass full identity checks. */
internal fun autoPlaySearchKeywords(subject: AnimeSubject): List<String> {
    val ordinal = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    val variants = listOf(subject.title, subject.originalTitle).flatMap { raw ->
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
    return variants.map(String::trim).filter { it.length >= 2 }.distinct().take(6)
}

/** AGE uses both 第02集 and 第2集 for the same regular episode across routes.
 * Only that complete numeric label is normalized; season/special/edition text survives. */
private fun ageEpisodeLabelKey(value: String): String {
    val literal = autoTitleKey(value)
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

private val authoritativeAnimeIdTypes = setOf("bangumi", "anilist", "mal", "douban")

private fun animeSubjectIds(subject: AnimeSubject): Map<String, String> =
    subject.externalIds + if (subject.id.startsWith("bangumi:"))
        mapOf("bangumi" to subject.id.removePrefix("bangumi:")) else emptyMap()

private fun sharesAuthoritativeAnimeId(subject: AnimeSubject, title: AnimeSourceTitle): Boolean =
    animeSubjectIds(subject).any { (key, id) ->
        key in authoritativeAnimeIdTypes && id.isNotBlank() && id == title.externalIds[key]
    }

private fun autoPlaySubjectEvidence(subject: AnimeSubject, title: AnimeSourceTitle, requireEvidence: Boolean): Boolean {
    val subjectIds = animeSubjectIds(subject)
    val sharedKeys = subjectIds.keys.intersect(title.externalIds.keys)
    if (sharedKeys.any { key -> subjectIds[key]?.isNotBlank() == true &&
        title.externalIds[key]?.isNotBlank() == true && subjectIds[key] != title.externalIds[key] }) return false
    if (subject.year != null && title.year != null && subject.year != title.year) return false
    val exactName = listOf(subject.title, subject.originalTitle).any {
        it.isNotBlank() && autoTitleKey(it) == autoTitleKey(title.title)
    }
    val sourceSeason = autoSeasonSignature(title.title)
    val explicitSeasonAlias = sourceSeason != null && listOf(subject.title, subject.originalTitle).any { name ->
        // Different spellings of one explicit season are allowed; the bare base and another
        // season remain different works. Year evidence is mandatory before binding.
        autoSeasonSignature(name) == sourceSeason
    }
    val sharedId = sharesAuthoritativeAnimeId(subject, title)
    // A shared authoritative ID permits aliases. Name-only binding still requires both years.
    return sharedId || ((exactName || explicitSeasonAlias) &&
        (!requireEvidence || (subject.year != null && title.year == subject.year)))
}

/** Do not select between seasons/editions, or infer episode identity from list position. */
internal fun autoPlayGroup(detail: AnimeDetail, requested: AnimeEpisode, catalog: AnimeSourceCatalog): String? =
    autoPlayGroups(detail, requested, catalog).firstOrNull()

internal fun autoPlayGroups(detail: AnimeDetail, requested: AnimeEpisode, catalog: AnimeSourceCatalog,
    selectedGroup: String? = null): List<String> {
    if (catalog.notice != null || catalog.episodes.isEmpty()) return emptyList()
    val groups = catalog.episodes.groupBy { it.group }
    // An existing confirmed AGE line anchors this episode. An unrelated line's
    // missing episodes must not reject another line's exact matching current episode.
    if (selectedGroup != null && catalog.title.sourceId == "anime-age" &&
        catalog.title.externalIds["age"] == catalog.title.id &&
        catalog.title.id.toLongOrNull()?.let { it > 0L } == true) {
        ageEquivalentEpisodeGroups(requested, groups, selectedGroup)?.let { return it }
    }
    // TvTFun's typed automatic group owns a season's real episode feed. Explicit
    // stations may cover only aired episodes, so their unequal coverage must not
    // invalidate that feed. Multiple seasons/editions still require user choice.
    if (catalog.title.sourceId == "anime-tvtfun") {
        val automatic = groups.filterValues { rows -> rows.all { it.playbackData == "automatic" } }
        if (automatic.isNotEmpty()) {
            if (automatic.size != 1 || catalog.episodes.map { it.edition }.distinct().size != 1)
                return emptyList()
            val (name, rows) = automatic.entries.single()
            if (rows.first().edition.isBlank() || rows.any { it.number == null || !it.number.isFinite() } ||
                rows.map { it.kind to it.number }.distinct().size != rows.size ||
                rows.count { it.number == requested.number && it.kind == requested.kind } != 1)
                return emptyList()
            return listOf(name)
        }
    }
    if (groups.size == 1) {
        val (name, rows) = groups.entries.single()
        if (rows.count { it.number == requested.number && it.kind == requested.kind } == 1 &&
            requested.number.isFinite()) return listOf(name)
        return emptyList()
    }
    val eligible = groups.filterValues { rows ->
        rows.count { it.number == requested.number && it.kind == requested.kind } == 1 &&
            rows.all { it.number != null && it.number.isFinite() } &&
            rows.map { it.kind to it.number }.distinct().size == rows.size
    }
    if (eligible.isEmpty()) return emptyList()
    // More than one edition may mean multiple seasons all restarting at episode one.
    if (catalog.episodes.map { it.edition }.distinct().size != 1) return emptyList()
    val coverage = eligible.values.map { rows -> rows.map { it.kind to requireNotNull(it.number) }.toSet() }
    if (coverage.distinct().size != 1 || eligible.size != groups.size) return emptyList()
    if (eligible.size == 1) return listOf(eligible.keys.single())
    // Giri's exact chs/cht keys are subtitle tracks inside the same native get_detail record.
    // Require complete matching episode labels as well as number/kind; never generalize this
    // contract to arbitrary line names or other providers.
    if (catalog.title.sourceId == "anime-girigiri" &&
        catalog.title.externalIds["girigiri"] == catalog.title.id && eligible.size == 2 &&
        eligible.keys.map { it.substringBefore(" [") }.toSet() == setOf("chs", "cht") &&
        eligible.keys.all { Regex("""^(chs|cht) \[\d+\]$""").matches(it) }) {
        val labels = eligible.values.map { rows -> rows.associate {
            (it.kind to requireNotNull(it.number)) to autoTitleKey(it.title)
        } }
        if (labels.distinct().size == 1 && labels.first().values.all { it.isNotBlank() })
            return eligible.keys.sortedBy { if (it.startsWith("chs [")) 0 else 1 }
    }
    // AGE's adapter constructs these routes exclusively from one requested work's
    // video.playlists response. Unknown routes or unequal episode labels stay manual.
    if (catalog.title.sourceId == "anime-age" && catalog.title.externalIds["age"] == catalog.title.id) {
        val knownRoutes = setOf("xigua", "ffm3u8", "bfzym3u8", "wjm3u8", "lzm3u8")
        fun route(group: String) = group.substringAfterLast(" [", "").removeSuffix("]")
        val routes = eligible.keys.map(::route)
        val labels = eligible.values.map { rows -> rows.associate {
            (it.kind to requireNotNull(it.number)) to ageEpisodeLabelKey(it.title)
        } }
        if (routes.all { it in knownRoutes } && routes.distinct().size == routes.size &&
            labels.distinct().size == 1 && labels.first().values.all { it.isNotBlank() }) {
            // Parsing order only, not measured speed or a promise of playable streams.
            return eligible.keys.sortedBy { if (route(it) == "xigua") 1 else 0 }
        }
    }
    // yzx builds its groups from the same native playUrlList work record. Only completely
    // equivalent number/kind and original episode labels can be rotated automatically.
    if (catalog.title.sourceId == "anime-native-yzx" && eligible.size == groups.size) {
        val labels = eligible.values.map { rows -> rows.associate {
            (it.kind to requireNotNull(it.number)) to autoTitleKey(it.title)
        } }
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

/** Only a confirmed AGE line may anchor a partial set of equivalent routes. */
private fun ageEquivalentEpisodeGroups(
    requested: AnimeEpisode,
    groups: Map<String, List<AnimeSourceEpisode>>,
    selectedGroup: String,
): List<String>? {
    if (!requested.number.isFinite()) return null
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
        it.kind == requested.kind && it.number == requested.number
    } ?: return null
    if (anchorRows.any { it.edition != anchor.edition }) return null
    val label = ageEpisodeLabelKey(anchor.title).takeIf { it.isNotBlank() } ?: return null
    return groups.entries.filter { (name, rows) ->
        val candidateRoute = route(name)
        if (candidateRoute == null || routeCounts[candidateRoute] != 1 ||
            rows.any { it.edition != anchor.edition }) return@filter false
        val candidate = rows.singleOrNull {
            it.kind == requested.kind && it.number == requested.number
        } ?: return@filter false
        ageEpisodeLabelKey(candidate.title) == label
    }.map { it.key }.sortedBy { if (route(it) == "xigua") 1 else 0 }
}
