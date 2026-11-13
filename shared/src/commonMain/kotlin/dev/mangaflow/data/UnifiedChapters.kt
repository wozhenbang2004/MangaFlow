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
    private val volume = Regex("^(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:卷|巻|册|冊|巻目)", RegexOption.IGNORE_CASE)
    private val englishVolume = Regex("^(?:vol(?:ume)?\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    private val chapterNumber = Regex("^(?:第\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:话|話|章|回|集)(?:\\s*[（(]?([上下中前后後ab])(?:篇|半)?[)）]?)?(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val englishChapter = Regex("^(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)([ab]?)(?=$|[\\s:：·、-])", RegexOption.IGNORE_CASE)
    private val bare = Regex("^([0-9]+(?:\\.[0-9]+)?)$")
    private val season = Regex("^(?:第\\s*)?([0-9]+)\\s*(?:季|部|season)\\s*[:：·-]?\\s*", RegexOption.IGNORE_CASE)
    private val englishSeason = Regex("^season\\s*([0-9]+)\\s*[:：·-]?\\s*", RegexOption.IGNORE_CASE)
    private val special = Regex("番外|特别|特別|外传|外傳|后记|後記|序章|序话|序話|前传|前傳|prologue|epilogue|special|extra|omake", RegexOption.IGNORE_CASE)
    private val normalGroups = setOf("", "默认", "默認", "default", "正篇", "本篇", "连载", "連載", "連載版", "连载版")

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
            val inner = chapterNumber.find(remainder) ?: englishChapter.find(remainder)
            return if (inner == null) ChapterMeaning("volume", branch, vol, "", vol.toPlainString())
            else {
                val ch = number(inner.groupValues[1]) ?: return null
                ChapterMeaning("volume-chapter", "$branch/volume:${vol.toPlainString()}", ch,
                    inner.groupValues.getOrElse(2) { "" }, ch.toPlainString())
            }
        }
        val match = chapterNumber.find(title) ?: englishChapter.find(title) ?: bare.find(title) ?: return null
        val num = number(match.groupValues[1]) ?: return null
        return ChapterMeaning("chapter", branch, num, match.groupValues.getOrElse(2) { "" }, num.toPlainString())
    }

    fun editionFamily(edition: String, meaning: ChapterMeaning?, confirmedSerialSystem: Boolean): String {
        val normalized = normalize(edition)
        val differentEdition = Regex("重制|重製|修订|修訂|彩色|全彩|remaster|revised|colou?r", RegexOption.IGNORE_CASE)
        return if (confirmedSerialSystem && meaning?.system == "chapter" &&
            !differentEdition.containsMatchIn(normalized) &&
            (normalized.startsWith("连载") || normalized.startsWith("連載") || normalized.startsWith("serial")))
            "serial" else normalized
    }

    fun independentId(storageId: String): String = "logical:" + sha256(storageId.encodeToByteArray()).take(16).toByteArray().hexString()
}
