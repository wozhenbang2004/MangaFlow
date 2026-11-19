package dev.mangaflow.data

enum class SourceDiscoveryStatus { QUEUED, SEARCHING, RETRYING, LINKED, NO_MATCH, NEEDS_CONFIRMATION, FAILED, DISABLED }

data class SourceDiscoveryResult(
    val sourceId: String,
    val sourceName: String,
    val status: SourceDiscoveryStatus,
    val chapters: Int = 0,
    val message: String = "",
    val candidates: List<Manga> = emptyList(),
    /** Null until a projected catalogue can show this source's actual chapter coverage. */
    val alignedChapters: Int? = null,
    /** Completion and previous success are separate: a failed check never erases usable data. */
    val checkedAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val attempt: Int = 0,
    val elapsedMillis: Long = 0L,
    val nextRetryAt: Long = 0L,
)

/** Candidate ordering is never evidence. Full identities and the two actual directories are checked. */
internal object SourceDiscoveryIdentity {
    private val unknownAuthors = setOf("未知", "佚名", "暂无", "不详", "作者", "unknown", "anonymous", "null")
    private val adultVersionMarker = Regex("完整版|全彩|彩色版|无修|无删减|修正版|修订版|重制版|汉化版|简中版|繁中版|英文版|日文版|韩文版|uncensored|censored|fullcolor|remaster|(?:vol(?:ume)?|part)[0-9]+|第?[0-9一二三四五六七八九十]+[卷册部]")
    private val completeBookLabel = Regex("(?:全(?:一|1)[话章册卷]|全本|整本|完整[篇本]|单篇|oneshot|complete)")

    enum class AuthorEvidence { MATCH, UNKNOWN, CONFLICT }

    private fun titleMarkers(value: String): Set<String> = buildSet {
        value.filter { it in "≡∞×+" }.forEach { add("symbol:$it") }
        if (Regex("小说|novel").containsMatchIn(value)) add("novel")
        if (Regex("公式书|公式集|公式fanbook|fanbook|officialguide").containsMatchIn(value)) add("guide")
        if (Regex("续作|续篇|续集|后传|続編|sequel").containsMatchIn(value)) add("sequel")
        if (Regex("外传|外傳|番外|外伝|spinoff").containsMatchIn(value)) add("spinoff")
        if (Regex("试作|試作|prototype").containsMatchIn(value)) add("prototype")
        if (Regex("短篇|短編|oneshot").containsMatchIn(value)) add("one-shot")
        if (Regex("选集|選集|anthology").containsMatchIn(value)) add("anthology")
        Regex("第([0-9]+)(卷|部|季)").findAll(value).forEach { add(it.value) }
    }

    private fun titles(manga: Manga, aliases: List<String> = emptyList()): Set<String> =
        (listOf(manga.title) + manga.alternativeTitles + aliases)
            .map(::normalizedBodySourceTitle).filter(String::isNotBlank).toSet()

    private fun sharedTitles(work: Manga, candidate: Manga, aliases: List<String>): Set<String> {
        // An alias cannot erase an explicit edition/sequel marker on either displayed work.
        if (titleMarkers(normalizedBodySourceTitle(work.title)) !=
            titleMarkers(normalizedBodySourceTitle(candidate.title))) return emptySet()
        return titles(work, aliases).intersect(titles(candidate))
    }

    fun titleMatches(work: Manga, candidate: Manga, aliases: List<String>): Boolean =
        sharedTitles(work, candidate, aliases).isNotEmpty()

    /** Protect explicit translation, revised, colored and collected editions during alias matching. */
    fun adultTitleMatches(work: Manga, candidate: Manga, aliases: List<String> = emptyList()): Boolean {
        fun markers(manga: Manga) = adultVersionMarker.findAll(normalizedBodySourceTitle(manga.title))
            .map { it.value }.toSet()
        val primaryType = normalizedBodySourceTitle(work.contentType)
        val targetType = normalizedBodySourceTitle(candidate.contentType)
        return markers(work) == markers(candidate) &&
            (primaryType.isBlank() || targetType.isBlank() || primaryType == targetType) &&
            titleMatches(work, candidate, aliases)
    }

    fun adultWorkMatches(work: Manga, candidate: Manga, aliases: List<String>): Boolean =
        adultTitleMatches(work, candidate, aliases) &&
            authorsMatch(work, candidate) && workMatches(work, candidate, aliases, sameChapterSystem = false)

    private fun authors(manga: Manga): Set<String> =
        (manga.authors + manga.authorAliases).flatMap { name ->
            // Some upstream names explicitly contain another spelling in parentheses.
            val parenthesized = Regex("[（(]([^()（）]+)[）)]").findAll(name).map { it.groupValues[1] }.toList()
            (listOf(name.replace(Regex("[（(][^()（）]+[）)]"), " ")) + parenthesized)
                .flatMap { it.split(Regex("[/、，;；\\n]")) }
                .flatMap { value ->
                    val parts = value.split(',').map(String::trim).filter(String::isNotBlank)
                    // MAL writes one person as "Family, Given". Treating the comma as
                    // an author separator made either bare surname sufficient evidence.
                    if (parts.size == 2 && parts.all {
                            normalizedBodySourceAuthor(it).let { full -> full.isNotEmpty() && full.all { character -> character in 'a'..'z' } } })
                        listOf(value)
                    else parts
                }
        }.map(::normalizedBodySourceAuthor)
            .filter { it.isNotBlank() && it !in unknownAuthors }.toSet()

    private fun authorScript(name: String): String? = when {
        name.all { it in 'a'..'z' } -> "latin"
        name.all { it in '\u3400'..'\u9fff' || it in '\uf900'..'\ufaff' } -> "han"
        name.any { it in '\u3040'..'\u30ff' } -> "kana"
        name.any { it in '\uac00'..'\ud7af' } -> "hangul"
        else -> null
    }

    fun authorEvidence(work: Manga, candidate: Manga): AuthorEvidence {
        val primary = authors(work)
        val target = authors(candidate)
        if (primary.intersect(target).isNotEmpty()) return AuthorEvidence.MATCH
        if (primary.isEmpty() || target.isEmpty()) return AuthorEvidence.UNKNOWN
        val comparable = primary.mapNotNull(::authorScript).toSet()
            .intersect(target.mapNotNull(::authorScript).toSet())
        return if (comparable.isEmpty()) AuthorEvidence.UNKNOWN else AuthorEvidence.CONFLICT
    }

    fun authorsMatch(work: Manga, candidate: Manga): Boolean =
        authorEvidence(work, candidate) == AuthorEvidence.MATCH

    private fun identities(manga: Manga): Map<String, Set<String>> = manga.externalIds.entries
        .mapNotNull { (provider, raw) ->
            val key = when (provider.lowercase()) {
                "al", "anilist" -> "anilist"
                "mal", "myanimelist" -> "myanimelist"
                "kt", "kitsu" -> "kitsu"
                "mangadex", "bangumi", "mangabaka" -> provider.lowercase()
                else -> return@mapNotNull null
            }
            val value = raw.trim().lowercase()
            val valid = if (key == "mangadex")
                value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            else value.matches(Regex("[1-9][0-9]*"))
            if (!valid) null else key to value
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    /** Full-name evidence is mandatory; covers and candidate ordering are never identity proof. */
    fun workMatches(work: Manga, candidate: Manga, aliases: List<String>, sameChapterSystem: Boolean): Boolean {
        val names = sharedTitles(work, candidate, aliases)
        if (names.isEmpty()) return false
        val author = authorEvidence(work, candidate)
        if (author == AuthorEvidence.CONFLICT) return false
        val primaryIds = identities(work)
        val targetIds = identities(candidate)
        val common = primaryIds.keys.intersect(targetIds.keys)
        if (common.any { primaryIds[it] != targetIds[it] }) return false
        if (common.any { primaryIds[it]?.size == 1 }) return true
        if (author == AuthorEvidence.MATCH) return true
        // Different scripts are unknown, not a fabricated author mismatch. Independent
        // native numbering can complement a distinctive complete upstream title alias.
        return sameChapterSystem && names.any { it.length >= 6 && it.any(Char::isLetter) }
    }

    /** Exact serial positions must agree across three chapter ranges, not just episode index. */
    fun chapterSystemMatches(base: MangaDetail?, candidate: MangaDetail): Boolean {
        if (base == null) return false
        fun positions(detail: MangaDetail): Set<String> {
            val ref = SourceKeys.decodeManga(detail.manga.id)
            val rows = detail.chapters.mapNotNull { chapter ->
                val native = chapter.variants.firstOrNull { it.sourceId == ref.sourceId && it.mangaId == ref.mangaId }
                if (chapter.variants.isNotEmpty() && native == null) return@mapNotNull null
                val original = if (native == null) chapter else chapter.copy(title = native.title)
                val meaning = ChapterSemantics.parse(original, confirmedSerialSystem = true) ?: return@mapNotNull null
                val number = meaning.number ?: return@mapNotNull null
                if (meaning.system != "chapter" || meaning.branch.isNotBlank() || meaning.part.isNotBlank() ||
                    number.signum() <= 0) return@mapNotNull null
                val edition = native?.edition ?: "连载版"
                if (ChapterSemantics.editionFamily(edition, meaning, true) != "serial") return@mapNotNull null
                val volume = Regex("(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
                    .find(edition + " " + chapter.group)?.value.orEmpty()
                number.toPlainString() to volume
            }
            return rows.groupBy({ it.first }, { it.second }).filterValues { volumes ->
                volumes.filter(String::isNotBlank).distinct().size <= 1
            }.keys
        }
        val primary = positions(base)
        val target = positions(candidate)
        // Fractional labels (1.1, 1.2, ...) are real upstream chapter numbers too.
        // Compare them in full; count integer portions only to avoid proving a whole
        // numbering system from three fragments of the same chapter.
        return primary.intersect(target).map { it.substringBefore('.') }.distinct().size >= 3
    }

    /** Auto-alignment needs native language and edition evidence in addition to catalogue numbers. */
    fun adultChapterSystemMatches(base: MangaDetail?, candidate: MangaDetail): Boolean {
        if (base == null || !adultTitleMatches(base.manga, candidate.manga)) return false
        if (!adultCatalogLanguagesMatch(base, candidate)) return false
        return adultWholeWorkChaptersMatch(base, candidate) || chapterSystemMatches(base, candidate)
    }

    fun adultCatalogLanguagesMatch(base: MangaDetail, candidate: MangaDetail): Boolean {
        fun languages(detail: MangaDetail): Set<String> = detail.chapters.asSequence().flatMap { it.variants.asSequence() }
            .map(ChapterVariant::language).distinct()
            .map { variant ->
                when (val language = normalizedBodySourceTitle(variant)) {
                    "简中", "简体中文", "zhcn", "zhhans" -> "zh-hans"
                    "繁中", "繁体中文", "zhtw", "zhhk", "zhhant" -> "zh-hant"
                    "日文", "日语", "jajp", "ja" -> "ja"
                    "英文", "英语", "enus", "engb", "en" -> "en"
                    "韩文", "韩语", "kokr", "ko" -> "ko"
                    else -> language
                }
            }.toSet()
        val primary = languages(base)
        val unverified = setOf("", "未知", "unknown", "und", "多语言", "多语")
        return primary.size == 1 && primary.single() !in unverified && primary == languages(candidate)
    }

    /** A single returned entry can be a preview or part 1. Require explicit whole-work labels. */
    fun adultWholeWorkChaptersMatch(base: MangaDetail, candidate: MangaDetail): Boolean {
        if (!adultWorkMatches(base.manga, candidate.manga, emptyList())) return false
        fun completeBook(detail: MangaDetail): Boolean {
            val chapter = detail.chapters.singleOrNull() ?: return false
            val native = chapter.variants.singleOrNull() ?: return false
            return completeBookLabel.matches(normalizedBodySourceTitle(native.title)) &&
                native.sourceId == detail.manga.sourceId &&
                SourceKeys.manga(native.sourceId, native.mangaId) == detail.manga.id
        }
        return completeBook(base) && completeBook(candidate)
    }

    fun signature(work: Manga, aliases: List<String>, base: MangaDetail?): String = buildString {
        append(titles(work, aliases).sorted().joinToString("|")).append('\u0000')
        append(authors(work).sorted().joinToString("|")).append('\u0000')
        append(identities(work).entries.sortedBy { it.key }.joinToString("|") { (id, values) ->
            "$id:" + values.sorted().joinToString(",")
        }).append('\u0000')
        append(base?.chapters?.size ?: 0).append(':')
        base?.chapters?.let { chapters ->
            (chapters.take(4) + chapters.takeLast(4)).forEach { append(it.id).append(':').append(it.title).append('|') }
        }
    }
}
