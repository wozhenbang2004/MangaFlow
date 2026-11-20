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
    private const val volumeLabel = "(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:巻目|卷|巻|册|冊)"
    private const val englishVolumeLabel = "vol(?:ume)?\\.?\\s*([0-9]+(?:\\.[0-9]+)?)"
    private val volume = Regex("^$volumeLabel")
    private val englishVolume = Regex("^$englishVolumeLabel", RegexOption.IGNORE_CASE)
    private val volumeInLabel = Regex("(?<![0-9.])$volumeLabel|\\b$englishVolumeLabel(?=$|[\\s:：·、|,-])",
        RegexOption.IGNORE_CASE)
    private val chapterNumber = Regex("^(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:话|話|章|回|集)(?:\\s*[（(]?([上下中前后後ab])(?:篇|半)?[)）]?)?(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val englishChapter = Regex("^(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)([ab]?)(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val bare = Regex("^([0-9]+(?:\\.[0-9]+)?)$")
    private val zeroPadding = Regex("^0+(?=[0-9])")
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

    fun canonicalVolumeLabels(label: String): String = volumeInLabel.replace(label) { match ->
        val ordinal = match.groups[1] ?: match.groups[2] ?: return@replace match.value
        val value = number(ordinal.value)?.toPlainString() ?: return@replace match.value
        val offset = ordinal.range.first - match.range.first
        match.value.replaceRange(offset, offset + ordinal.value.length, value)
    }

    fun volumeNumberIn(label: String): String? = volumeInLabel.find(normalize(label))?.let { match ->
        number(match.groupValues[1].ifEmpty { match.groupValues[2] })?.toPlainString()
    }

    /** Duplicate native rows can be variants when their titles differ only in ordinal padding. */
    fun paddedTitleAliases(titles: List<String>): Boolean {
        val labels = titles.map(::normalize)
        if (labels.distinct().size != labels.size) return false
        val canonical = labels.map { label ->
            val prefix = (season.find(label) ?: englishSeason.find(label))?.value.orEmpty()
            val title = label.removePrefix(prefix)
            val ordinal = (volume.find(title) ?: englishVolume.find(title) ?: chapterNumber.find(title)
                ?: englishChapter.find(title) ?: bare.matchEntire(title))?.groups?.get(1) ?: return false
            prefix + title.replaceRange(ordinal.range, ordinal.value.replace(zeroPadding, ""))
        }
        return canonical.distinct().size == 1
    }

    fun parse(chapter: Chapter, confirmedSerialSystem: Boolean = false): ChapterMeaning? {
        var title = normalize(chapter.title)
        val group = canonicalVolumeLabels(normalize(chapter.group))
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
        val bareNumber = bare.matchEntire(title)
        val match = chapterNumber.find(title) ?: englishChapter.find(title) ?: bareNumber ?: return null
        val num = number(match.groupValues[1]) ?: return null
        if (bareNumber != null && (groupParts.firstOrNull().orEmpty() in volumeGroups ||
                normalize(chapter.variants.firstOrNull()?.edition.orEmpty()) in volumeGroups)) {
            val volumeBranch = if (group in volumeGroups || group in normalGroups) branch.removePrefix(group) else branch
            return ChapterMeaning("volume", volumeBranch, num, "", num.toPlainString())
        }
        return ChapterMeaning("chapter", branch, num, match.groupValues.getOrElse(2) { "" }, num.toPlainString())
    }

    fun editionFamily(edition: String, meaning: ChapterMeaning?, confirmedSerialSystem: Boolean): String {
        val normalized = canonicalVolumeLabels(normalize(edition))
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
            (editionFamily(edition, meaning, false) + "\u0000" + meaning.key) to chapter.title
        }.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 || paddedTitleAliases(it) }.keys
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
        return ReadingVolume(value, parts[2], "连载版 · 第${value.toPlainString()}卷")
    }

    /** Only the contiguous, proven MangaDex edition around the current group can extend navigation. */
    fun readingSequence(chapters: List<Chapter>, current: Chapter, catalogComplete: Boolean): List<Chapter> {
        val currentGroup = canonicalVolumeLabels(current.group)
        val sameGroup = chapters.filter { canonicalVolumeLabels(it.group) == currentGroup }.sortedBy(Chapter::index)
        if (!catalogComplete || current.sourceId != "mangadex" || sameGroup.none { it.id == current.id })
            return sameGroup
        val currentVolume = readingVolume(current.group) ?: return sameGroup
        val currentOrdinal = currentVolume.number.toPlainString().toLongOrNull() ?: return sameGroup
        fun selectedVariant(chapter: Chapter): ChapterVariant? = chapter.variants.singleOrNull {
            it.sourceId == "mangadex" && chapter.sourceId == it.sourceId &&
                it.mangaId.isNotBlank() && chapter.sourceMangaId == it.mangaId &&
                it.chapterId.isNotBlank() && chapter.sourceChapterId == it.chapterId
        }
        val selected = selectedVariant(current)?.takeIf { it.scanlator.isNotBlank() } ?: return sameGroup
        val volumes = mutableMapOf<Long, List<Chapter>>()
        val ambiguousVolumes = mutableSetOf<Long>()
        val fractionalAfter = mutableSetOf<Long>()
        for ((group, entries) in chapters.groupBy { canonicalVolumeLabels(it.group) }) {
            val label = readingVolume(group) ?: continue
            if (label.language != currentVolume.language) continue
            val number = label.number.toPlainString()
            val ordinal = number.toLongOrNull()
            if (ordinal == null) {
                number.substringBefore('.').toLongOrNull()?.let { fractionalAfter += it }
                continue
            }
            val lane = entries.filter { chapter ->
                selectedVariant(chapter)?.let { variant ->
                    variant.mangaId == selected.mangaId && variant.language == label.language &&
                        canonicalVolumeLabels(variant.edition) == label.edition && variant.scanlator == selected.scanlator
                } == true
            }.sortedBy(Chapter::index)
            val numbers = lane.mapNotNull { chapter ->
                parse(chapter)?.takeIf { it.system == "chapter" }?.key
            }
            val known = numbers.toSet()
            val laneIds = lane.mapTo(mutableSetOf(), Chapter::id)
            // Other confirmed scanlations may duplicate a numbered chapter. They may not
            // supply a missing chapter or ambiguous extra in the currently selected edition.
            val valid = lane.isNotEmpty() && known.size == numbers.size && laneIds.size == lane.size &&
                lane.map(Chapter::index).distinct().size == lane.size && entries.all { chapter ->
                    chapter.id in laneIds || parse(chapter)?.let { it.system == "chapter" && it.key in known } == true
                }
            if (!valid || ordinal in volumes) ambiguousVolumes += ordinal
            else volumes[ordinal] = lane
        }
        fun lane(number: Long) = volumes[number]?.takeUnless { number in ambiguousVolumes }
        val start = lane(currentOrdinal)?.takeIf { entries -> entries.any { it.id == current.id } }
            ?: return sameGroup
        fun earlierThan(left: Chapter, right: Chapter): Boolean {
            val before = parse(left)?.takeIf { it.system == "chapter" }?.number ?: return false
            val after = parse(right)?.takeIf { it.system == "chapter" }?.number ?: return false
            return before < after
        }
        val previous = mutableListOf<List<Chapter>>()
        var first = start
        var firstVolume = currentOrdinal
        while (firstVolume > 1L && firstVolume - 1L !in fractionalAfter) {
            val candidate = lane(firstVolume - 1L) ?: break
            if (!earlierThan(candidate.last(), first.first())) break
            previous += candidate
            first = candidate
            firstVolume--
        }
        val following = mutableListOf<List<Chapter>>()
        var last = start
        var lastVolume = currentOrdinal
        while (lastVolume < Long.MAX_VALUE && lastVolume !in fractionalAfter) {
            val candidate = lane(lastVolume + 1L) ?: break
            if (!earlierThan(last.last(), candidate.first())) break
            following += candidate
            last = candidate
            lastVolume++
        }
        return (previous.asReversed() + listOf(start) + following).flatten()
    }

    fun independentId(storageId: String): String = "logical:" + sha256(storageId.encodeToByteArray()).take(16).toByteArray().hexString()
}
