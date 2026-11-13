package dev.mangaflow.data

enum class SourceDiscoveryStatus { SEARCHING, LINKED, NO_MATCH, NEEDS_CONFIRMATION, FAILED, DISABLED }

data class SourceDiscoveryResult(
    val sourceId: String,
    val sourceName: String,
    val status: SourceDiscoveryStatus,
    val chapters: Int = 0,
    val message: String = "",
    val candidates: List<Manga> = emptyList(),
)

/** Candidate ordering is never evidence. Full identities and the two actual directories are checked. */
internal object SourceDiscoveryIdentity {
    private val unknownAuthors = setOf("未知", "佚名", "暂无", "不详", "作者", "unknown", "anonymous", "null")

    fun titleMatches(work: Manga, candidate: Manga, aliases: List<String>): Boolean {
        val title = normalizedBodySourceTitle(candidate.title)
        val primary = normalizedBodySourceTitle(work.title)
        // A broad metadata alias cannot erase an explicit sequel/edition marker on the work.
        fun markers(value: String): Set<String> = buildSet {
            value.filter { it in "≡∞×+" }.forEach { add("symbol:$it") }
            if (Regex("小说|novel").containsMatchIn(value)) add("novel")
            if (Regex("公式书|公式集|公式fanbook|fanbook|officialguide").containsMatchIn(value)) add("guide")
            if (Regex("续作|续篇|续集|后传|続編|sequel").containsMatchIn(value)) add("sequel")
            Regex("第([0-9]+)(卷|部|季)").findAll(value).forEach { add(it.value) }
        }
        if (markers(primary) != markers(title)) return false
        return title.isNotBlank() && (listOf(work.title) + aliases).any {
            normalizedBodySourceTitle(it).takeIf(String::isNotBlank) == title
        }
    }

    fun authorsMatch(work: Manga, candidate: Manga): Boolean {
        fun authors(manga: Manga): Set<String> = manga.authors.flatMap {
            it.split(Regex("[/、,，;；\\n]"))
        }.map(::normalizedBodySourceAuthor).filter { it.isNotBlank() && it !in unknownAuthors }.toSet()
        val known = authors(work)
        return known.isNotEmpty() && authors(candidate).any { it in known }
    }

    /** Three ordinary, unambiguous global chapter positions must agree, not just episode index. */
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
                    number.signum() <= 0 || number.scale() > 0) return@mapNotNull null
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
        return primary.intersect(target).size >= 3
    }

    fun signature(work: Manga, aliases: List<String>, base: MangaDetail?): String = buildString {
        append(normalizedBodySourceTitle(work.title)).append('\u0000')
        append(work.authors.map(::normalizedBodySourceAuthor).sorted().joinToString("|")).append('\u0000')
        append(aliases.map(::normalizedBodySourceTitle).distinct().sorted().joinToString("|")).append('\u0000')
        append(base?.chapters?.size ?: 0).append(':')
        base?.chapters?.let { chapters ->
            (chapters.take(4) + chapters.takeLast(4)).forEach { append(it.id).append(':').append(it.title).append('|') }
        }
    }
}
