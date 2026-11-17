package dev.mangaflow.data

import dev.mangaflow.platform.*

import dev.mangaflow.data.DecimalNumber as BigDecimal
import dev.mangaflow.data.toPortableDecimalOrNull

/** A displayable chapter key, not a claim that two sources contain the same images. */
internal data class ChapterMeaning(
    val system: String,
    val branch: String,
    val number: BigDecimal?,
    val part: String,
    val normalized: String,
) {
    val key: String get() = "$system\u0000$branch\u0000$normalized\u0000$part"
}

internal object ChapterSemantics {
    private val volume = Regex("^(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:巻目|卷|巻|册|冊)", RegexOption.IGNORE_CASE)
    private val englishVolume = Regex("^(?:vol(?:ume)?\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    private val chapterNumber = Regex("^(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:话|話|章|回|集)(?:\\s*[（(]?([上下中前后後ab])(?:篇|半)?[)）]?)?(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val englishChapter = Regex("^(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)([ab]?)(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val bare = Regex("^([0-9]+(?:\\.[0-9]+)?)$")
    private val season = Regex("^(?:第\\s*)?([0-9]+)\\s*(?:季|部|season)\\s*[:：·-]?\\s*", RegexOption.IGNORE_CASE)
    private val englishSeason = Regex("^season\\s*([0-9]+)\\s*[:：·-]?\\s*", RegexOption.IGNORE_CASE)
    private val special = Regex("番外|特别|特別|外传|外傳|后记|後記|附录|附錄|特典|附赠|附贈|赠品|贈品|序章|序话|序話|前传|前傳|prologue|epilogue|special|extra|omake|appendix|bonus|afterword", RegexOption.IGNORE_CASE)
    private val normalGroups = setOf("", "默认", "默認", "default", "正篇", "本篇", "连载", "連載", "連載版", "连载版")

    private val volumeGroups = setOf("单行本", "單行本", "单行本版", "單行本版", "卷", "册", "冊",
        "volume", "volumes", "collected", "collected edition", "collected volumes", "tankobon", "tankōbon")
    private val genericVolumeEditions = volumeGroups + setOf("", "连载版", "連載版", "serial", "serial edition")

    private fun normalize(value: String): String = normalizeNfkc(value)
        .trim().lowercase().replace(Regex("\\s+"), " ")
    private fun number(value: String): BigDecimal? = value.toPortableDecimalOrNull()

    fun parse(chapter: Chapter, confirmedSerialSystem: Boolean = false): ChapterMeaning? {
        var title = normalize(chapter.title)
        val group = normalize(chapter.group)
        val groupParts = group.split(Regex("\\s*[·|]\\s*"))
        val genericSerial = groupParts.firstOrNull().orEmpty() in normalGroups
        // A confirmed serial catalogue uses global chapter numbers. Language, scanlator and
        // display-volume labels are variant attributes, not separate chapter systems.
        var branch = if (group in normalGroups) "" else if (confirmedSerialSystem && genericSerial) {
            groupParts.drop(1).filter { token ->
                Regex("季|分部|第\\s*[0-9]+\\s*部|part|season|重制|重製|修订|修訂|彩色|全彩", RegexOption.IGNORE_CASE).containsMatchIn(token)
            }.joinToString("/")
        } else group
        (season.find(title) ?: englishSeason.find(title))?.let { match ->
            branch += "/season:${match.groupValues[1].toIntOrNull() ?: match.groupValues[1]}"
            title = title.removeRange(match.range).trim()
        }
        if (special.containsMatchIn(title)) return ChapterMeaning("special", branch, null, "", title)
        val volumeMatch = volume.find(title) ?: englishVolume.find(title)
        if (volumeMatch != null) {
            val vol = number(volumeMatch.groupValues[1]) ?: return null
            val remainder = title.removeRange(volumeMatch.range).trim().trimStart(':', '：', '·', '-', ' ')
            // Generic catalogue headings do not define a separate collected-volume edition.
            // Keep explicit seasons and unknown edition branches intact.
            val volumeBranch = if (group in normalGroups || group in volumeGroups ||
                group == "章节" || group == "章節") branch.removePrefix(group) else branch
            val inner = chapterNumber.find(remainder) ?: englishChapter.find(remainder)
            return when {
                remainder.isBlank() -> ChapterMeaning("volume", volumeBranch, vol, "", vol.toPlainString())
                inner == null -> ChapterMeaning("volume-part", volumeBranch, vol, remainder, vol.toPlainString())
                else -> {
                    val ch = number(inner.groupValues[1]) ?: return null
                    ChapterMeaning("volume-chapter", "$volumeBranch/volume:${vol.toPlainString()}", ch,
                        inner.groupValues.getOrElse(2) { "" }, ch.toPlainString())
                }
            }
        }
        val match = chapterNumber.find(title) ?: englishChapter.find(title) ?: bare.find(title) ?: return null
        val num = number(match.groupValues[1]) ?: return null
        return ChapterMeaning("chapter", branch, num, match.groupValues.getOrElse(2) { "" }, num.toPlainString())
    }

    fun editionFamily(edition: String, meaning: ChapterMeaning?, confirmedSerialSystem: Boolean): String {
        val normalized = normalize(edition)
        val differentEdition = Regex("重制|重製|修订|修訂|彩色|全彩|remaster|revised|colou?r", RegexOption.IGNORE_CASE)
        if (meaning?.system == "volume" && normalized in genericVolumeEditions) return "volume"
        return if (confirmedSerialSystem && meaning?.system == "chapter" &&
            !differentEdition.containsMatchIn(normalized) &&
            (normalized.startsWith("连载") || normalized.startsWith("連載") || normalized.startsWith("serial")))
            "serial" else normalized
    }

    /** Compare numbering only inside an already confirmed work association, independently of serial chapters. */
    fun volumeSystemMatches(base: List<Chapter>, candidate: List<Chapter>): Boolean {
        fun unambiguousVolumes(chapters: List<Chapter>): Set<String> = chapters.mapNotNull { chapter ->
            val meaning = parse(chapter, confirmedSerialSystem = true) ?: return@mapNotNull null
            val value = meaning.number ?: return@mapNotNull null
            if (meaning.system != "volume" || value.signum() <= 0 || value.scale() > 0)
                return@mapNotNull null
            val edition = chapter.variants.firstOrNull()?.edition ?: "单行本"
            editionFamily(edition, meaning, false) + "\u0000" + meaning.key
        }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
        return unambiguousVolumes(base).intersect(unambiguousVolumes(candidate)).size >= 3
    }

    private data class ReadingVolume(val number: BigDecimal, val language: String, val edition: String)

    private fun readingVolume(group: String): ReadingVolume? {
        // This is MangaDexBodySource's native grouping, not a guess about arbitrary source groups.
        val parts = group.split(" · ")
        if (parts.size != 3 || parts[0] != "连载" ||
            !Regex(".+ \\[(zh|zh-hk|zh-ro|en)\\]").matches(parts[2])) return null
        val match = volume.matchEntire(parts[1]) ?: return null
        val value = number(match.groupValues[1])?.takeIf { it.signum() > 0 } ?: return null
        return ReadingVolume(value, parts[2], "连载版 · ${parts[1]}")
    }

    /** Keep ordinary groups intact; join only complete, unambiguous native MangaDex volumes. */
    fun readingSequence(chapters: List<Chapter>, current: Chapter, catalogComplete: Boolean): List<Chapter> {
        val sameGroup = chapters.filter { it.group == current.group }.sortedBy(Chapter::index)
        if (!catalogComplete || current.sourceId != "mangadex" || sameGroup.none { it.id == current.id })
            return sameGroup
        val currentVolume = readingVolume(current.group) ?: return sameGroup
        fun selectedVariant(chapter: Chapter): ChapterVariant? = chapter.variants.singleOrNull {
            it.sourceId == "mangadex" && chapter.sourceId == it.sourceId &&
                it.mangaId.isNotBlank() && chapter.sourceMangaId == it.mangaId &&
                it.chapterId.isNotBlank() && chapter.sourceChapterId == it.chapterId
        }
        val selected = selectedVariant(current)?.takeIf { it.scanlator.isNotBlank() } ?: return sameGroup
        val volumes = mutableListOf<Pair<ReadingVolume, List<Chapter>>>()
        val seenVolumes = mutableSetOf<String>()
        for ((group, entries) in chapters.groupBy(Chapter::group)) {
            val label = readingVolume(group) ?: continue
            if (label.language != currentVolume.language) continue
            // Do not skip an ambiguous middle volume to reach a later one. DecimalNumber
            // comparison is exact, but equality is identity-based, so key by canonical text.
            if (!seenVolumes.add(label.number.toPlainString()) || entries.any { chapter ->
                    val variant = selectedVariant(chapter)
                    variant == null || variant.mangaId != selected.mangaId ||
                        variant.language != label.language || variant.edition != label.edition ||
                        variant.scanlator != selected.scanlator
                }) return sameGroup
            val numbers = entries.mapNotNull { chapter ->
                parse(chapter)?.takeIf { it.system == "chapter" }?.key
            }
            if (numbers.distinct().size != numbers.size ||
                entries.map(Chapter::id).distinct().size != entries.size ||
                entries.map(Chapter::index).distinct().size != entries.size) return sameGroup
            volumes += label to entries
        }
        return volumes.sortedWith { left, right -> left.first.number.compareTo(right.first.number) }
            .flatMap { (_, entries) -> entries.sortedBy(Chapter::index) }
    }

    fun independentId(storageId: String): String = "logical:" + sha256(storageId.encodeToByteArray()).take(16).toByteArray().hexString()
}
