package dev.mangaflow.data

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

data class SourceMangaRef(val sourceId: String, val mangaId: String)
data class SourceChapterRef(val sourceId: String, val mangaId: String, val chapterId: String)

/** Encoded identities are durable routing information, never an in-memory lookup token. */
object SourceKeys {
    private const val PREFIX = "@source/"
    private val sourcePattern = Regex("[a-z][a-z0-9_-]{0,39}")
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        .also { require(it.isNotBlank() && it.length <= 4096) { "来源编号不完整" } }
    private fun checkedSource(sourceId: String): String = sourceId.also {
        require(sourcePattern.matches(it)) { "来源编号不受支持" }
    }

    fun manga(sourceId: String, mangaId: String): String {
        checkedSource(sourceId)
        require(mangaId.isNotBlank() && mangaId.length <= 4096)
        return if (sourceId == "copy" || sourceId == "local") mangaId
        else "$PREFIX$sourceId/${encode(mangaId)}"
    }

    fun chapter(sourceId: String, mangaId: String, chapterId: String): String {
        checkedSource(sourceId)
        require(mangaId.isNotBlank() && mangaId.length <= 4096 && chapterId.isNotBlank() && chapterId.length <= 4096)
        return if (sourceId == "copy" || sourceId == "local") chapterId
        else "$PREFIX$sourceId/${encode(mangaId)}/${encode(chapterId)}"
    }

    fun isEncoded(id: String): Boolean = id.startsWith(PREFIX)

    fun decodeManga(id: String): SourceMangaRef {
        if (!isEncoded(id)) return SourceMangaRef("copy", id)
        val parts = id.removePrefix(PREFIX).split('/')
        require(parts.size == 2) { "作品来源编号损坏" }
        return SourceMangaRef(checkedSource(parts[0]), decode(parts[1]))
    }

    fun decodeChapter(workId: String, chapterId: String): SourceChapterRef {
        if (!isEncoded(chapterId)) {
            val manga = decodeManga(workId)
            return SourceChapterRef(manga.sourceId, manga.mangaId, chapterId)
        }
        val parts = chapterId.removePrefix(PREFIX).split('/')
        require(parts.size == 3) { "章节来源编号损坏" }
        return SourceChapterRef(checkedSource(parts[0]), decode(parts[1]), decode(parts[2]))
    }
}

data class ChapterVariant(
    val sourceId: String,
    val sourceName: String,
    val mangaId: String,
    val chapterId: String,
    val title: String,
    val language: String = "简中",
    val edition: String = "连载版",
    val scanlator: String = "",
    val url: String = "",
    val pageCount: Int? = null,
) {
    val storageId: String get() = SourceKeys.chapter(sourceId, mangaId, chapterId)
}

fun resolveChapter(chapter: Chapter, variant: ChapterVariant): Chapter = chapter.copy(
    id = variant.storageId,
    logicalId = chapter.logicalId.ifBlank { chapter.id },
    sourceId = variant.sourceId,
    sourceMangaId = variant.mangaId,
    sourceChapterId = variant.chapterId,
)

fun sourceDisplayName(sourceId: String): String = when (sourceId) {
    "copy" -> "拷贝"
    "baozi" -> "包子"
    "komiic" -> "Komiic"
    "mangadex" -> "MangaDex"
    "manhuagui" -> "漫画柜"
    "mangabz" -> "MangaBZ"
    "local" -> "本地"
    else -> sourceId
}

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

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun number(value: String): BigDecimal? = value.toBigDecimalOrNull()?.stripTrailingZeros()

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

    fun independentId(storageId: String): String = "logical:" + MessageDigest.getInstance("SHA-256")
        .digest(storageId.toByteArray(StandardCharsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
}
