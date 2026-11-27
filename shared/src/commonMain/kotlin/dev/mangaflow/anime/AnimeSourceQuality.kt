package dev.mangaflow.anime

import dev.mangaflow.anime.downloads.hlsAttributes
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.normalizeNfkc
import dev.mangaflow.platform.platformKind

private val labelHeight = Regex("""(?i)(?<![a-z0-9])(?:(?:hd|bd)[ -]?)?([1-8]\d{2,3})\s*p(?:\d{2,3}(?:\.\d+)?)?(?![a-z0-9])""")
private val labelDimensions = Regex("""(?i)(?<!\d)\d{3,5}\s*[x×]\s*(\d{3,4})(?!\d)""")
private val labelSizeName = Regex("""(?i)(?<![a-z0-9])(8k|4k|uhd|qhd|fhd)(?![a-z0-9])""")
private val bareHeights = setOf(144, 240, 288, 360, 432, 480, 540, 576, 720, 900, 1080, 1440, 2160, 4320)

/** Only unambiguous, explicitly stated source dimensions qualify for automatic quality selection. */
internal fun animeDeclaredHeight(label: String): Int? {
    val text = normalizeNfkc(label).trim()
    text.toIntOrNull()?.let { return it.takeIf(bareHeights::contains) }
    val heights = buildSet {
        for (pattern in listOf(labelHeight, labelDimensions)) {
            for (match in pattern.findAll(text)) match.groupValues[1].toIntOrNull()?.takeIf { it in 144..8640 }?.let(::add)
        }
        for (match in labelSizeName.findAll(text)) add(when (match.value.lowercase()) {
            "8k" -> 4320
            "4k", "uhd" -> 2160
            "qhd" -> 1440
            else -> 1080
        })
    }
    return heights.singleOrNull()
}

/** Prefer the highest stated height within the preference, then the lowest available above it. */
internal fun animeRenditionOrder(height: Int?, preferredHeight: Int): Int = when {
    height == null || height <= 0 -> Int.MAX_VALUE
    preferredHeight == 0 || height <= preferredHeight -> -height
    else -> height
}

internal fun animePlaybackFormatSupported(asset: AnimePlaybackAsset): Boolean =
    animePlaybackFormatSupported(asset.url, asset.mimeType)

internal fun animePlaybackFormatSupported(url: String, mimeType: String?): Boolean =
    platformKind == PlatformKind.ANDROID || !(mimeType?.substringBefore(';')?.trim().equals("application/dash+xml", true) ||
        url.substringBefore('?').substringBefore('#').endsWith(".mpd", true))

private val streamResolution = Regex("""[1-9]\d*[xX](\d+)""")
internal data class AnimeHlsRendition(val tagIndex: Int, val uriIndex: Int, val height: Int?)

internal fun animeHlsRenditions(lines: List<String>): List<AnimeHlsRendition> = buildList {
    var tag = -1
    for ((index, original) in lines.withIndex()) {
        val line = original.trim()
        when {
            line.startsWith("#EXT-X-STREAM-INF:") -> tag = index
            tag >= 0 && line.isNotEmpty() && !line.startsWith('#') -> {
                val dimensions = runCatching { hlsAttributes(lines[tag].trim().substringAfter(':'))["RESOLUTION"] }.getOrNull()
                val height = dimensions?.let(streamResolution::matchEntire)?.groupValues?.get(1)
                    ?.toIntOrNull()?.takeIf { it in 144..8640 }
                add(AnimeHlsRendition(tag, index, height))
                tag = -1
            }
            line.startsWith("#EXT") -> tag = -1
        }
    }
}

/** Keep native adaptation, rendition groups and subtitles within an explicitly requested video height. */
internal fun limitAnimeHlsMaster(text: String, preferredHeight: Int): String {
    if (preferredHeight <= 0 || !text.contains("#EXT-X-STREAM-INF:")) return text
    val lines = text.lines()
    val renditions = animeHlsRenditions(lines)
    if (renditions.size != lines.count { it.trimStart().startsWith("#EXT-X-STREAM-INF:") }) return text
    val heights = renditions.mapNotNull { it.height }
    val fallback = heights.minOrNull() ?: return text
    val ceiling = maxOf(preferredHeight, fallback)
    val omitted = renditions.filter { (it.height ?: 0) > ceiling }
    if (omitted.isEmpty()) return text
    val skip = buildSet { for (rendition in omitted) { add(rendition.tagIndex); add(rendition.uriIndex) } }
    return lines.filterIndexed { index, _ -> index !in skip }.joinToString("\n")
}
