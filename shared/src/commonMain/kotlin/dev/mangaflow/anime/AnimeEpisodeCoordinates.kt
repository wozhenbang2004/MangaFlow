package dev.mangaflow.anime

import dev.mangaflow.anime.sources.sourceEpisodeMarker

private val genericEpisodeCaption = Regex(
    """^(?:第?[0-9一二三四五六七八九十百零〇]+(?:[.\-]\d+)?[集话話回]?|(?:ep|episode)\d+|(?:1080|720|2160|480)p|(?:正片|全集|上集|下集|高清|超清|完整版|国语版|粤语版|日语版|中字|字幕|国配|蓝光|预告|待定|未命名|unknown|tba|tbd|hd|bd|4k))$""",
)

internal fun animeEpisodeCaption(title: String): String? {
    val payload = sourceEpisodeMarker(title)?.caption ?: title
    val key = animeWorkTitleKey(payload)
    return key.takeIf { it.length in 2..240 && it.any(Char::isLetter) && !genericEpisodeCaption.matches(it) }
}

private fun positiveEpisodeNumber(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it > 0.0 && it % 1.0 == 0.0 }

internal fun <T> uniqueAnimeEpisodeCaptions(rows: Collection<T>, title: (T) -> String): Map<String, T> {
    val result = mutableMapOf<String, T>()
    val ambiguous = mutableSetOf<String>()
    for (row in rows) {
        val caption = animeEpisodeCaption(title(row)) ?: continue
        if (caption !in ambiguous && result.put(caption, row) != null) {
            result.remove(caption)
            ambiguous.add(caption)
        }
    }
    return result
}

/** Each real playlist chooses its own numbering. Do not let a sibling route veto it or supply its offset. */
internal class AnimeEpisodeCoordinates(
    private val detail: AnimeDetail,
    private val groups: Map<String, List<AnimeSourceEpisode>>,
    private val verifiedWork: Boolean,
    private val completeSeasonOffset: Double?,
) {
    private val main = detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.subjectId == detail.subject.id }
    private val details = main.toSet()
    private val absoluteNumbers = main.mapNotNullTo(mutableSetOf()) { positiveEpisodeNumber(it.number) }
    private val firstAbsolute = absoluteNumbers.minOrNull()
    private val namedSeason by lazy { animeCatalogueNames(detail.subject).mapNotNull(::animeWrittenSeasonNumber).distinct().singleOrNull() }
    private val workNames by lazy { animeCatalogueNames(detail.subject).map(::animeWorkTitleKey).toSet() }
    private val captions by lazy { uniqueAnimeEpisodeCaptions(main, AnimeEpisode::title) }
    private val byGroup = mutableMapOf<String, Group>()
    private val metadataPairs by lazy {
        val reference = detail.metadataSubject?.id?.takeIf { animeBangumiReferenceId(it) != null }
        val pairs = main.filter { row ->
            positiveEpisodeNumber(row.number) != null && positiveEpisodeNumber(row.numberInSubject) != null &&
                (detail.subject.canonicalBangumiId() != null && animeBangumiReferenceId(row.id) != null ||
                    reference != null && row.metadataSubjectId == reference && animeBangumiReferenceId(row.metadataEpisodeId) != null)
        }
        // A reused episode number or conflicting offset does not define a numbering system.
        if (pairs.map { it.numberInSubject }.distinct().size != pairs.size ||
            pairs.map { it.number - requireNotNull(it.numberInSubject) }.distinct().size != 1) emptySet()
        else pairs.toSet()
    }
    private val offset by lazy { metadataPairs.firstOrNull()?.let { it.number - requireNotNull(it.numberInSubject) }
        ?.takeIf { it > 0.0 } }
    private val lastLocal by lazy {
        val observed = metadataPairs.maxOfOrNull { requireNotNull(it.numberInSubject) } ?: 0.0
        // Some services put the series-wide end in the count. Only extend the local range with a plausible season count.
        val declared = detail.subject.episodeCount?.toDouble()?.takeIf { count ->
            val absoluteEnd = metadataPairs.maxOfOrNull { it.number } ?: return@takeIf false
            count < absoluteEnd
        } ?: 0.0
        maxOf(observed, declared)
    }

    private inner class Group(rows: List<AnimeSourceEpisode>) {
        val regular = rows.filter { it.kind == AnimeEpisodeKind.MAIN }
        val numbers = regular.mapNotNullTo(mutableSetOf()) { positiveEpisodeNumber(it.number) }
        val sourceCaptions by lazy { uniqueAnimeEpisodeCaptions(regular, AnimeSourceEpisode::title) }
        val metadataShift: Double? by lazy {
            val displacement = offset ?: return@lazy null
            val localEnd = lastLocal
            when {
                numbers.isEmpty() -> null
                // A partial range can have gaps or start after 1. Require at least one coordinate specific to this scale.
                numbers.all { it <= localEnd } && numbers.any { it < displacement + 1.0 } -> displacement
                namedSeason != null && regular.all { it.catalogueSeason == namedSeason } && numbers.all { it < displacement + 1.0 } -> displacement
                numbers.none { it < displacement + 1.0 } && numbers.any { it > localEnd } -> 0.0
                // An omnibus may contain both 1 and 171. Its uninterrupted run across the boundary proves absolute numbering.
                displacement - 1.0 in numbers && displacement in numbers && displacement + 1.0 in numbers -> 0.0
                else -> null
            }
        }
        val captionRange by lazy {
            if (regular.map { it.edition }.distinct().size != 1) return@lazy null
            val anchors = sourceCaptions.mapNotNull { (caption, row) ->
                val target = captions[caption] ?: return@mapNotNull null
                val absolute = positiveEpisodeNumber(target.number) ?: return@mapNotNull null
                val local = positiveEpisodeNumber(row.number) ?: return@mapNotNull null
                absolute to (absolute - local)
            }
            if (anchors.size < 2 || anchors.map { it.first }.distinct().size != anchors.size) return@lazy null
            val shift = anchors.map { it.second }.distinct().singleOrNull() ?: return@lazy null
            (anchors.minOf { it.first }..anchors.maxOf { it.first }) to shift
        }
    }

    private fun group(name: String) = byGroup.getOrPut(name) { Group(groups[name].orEmpty()) }

    fun numbered(requested: AnimeEpisode, name: String): Double? {
        if (requested.kind != AnimeEpisodeKind.MAIN || !verifiedWork || requested !in details)
            return requested.number - (if (requested.kind == AnimeEpisodeKind.MAIN) completeSeasonOffset ?: 0.0 else 0.0)
        val group = group(name)
        if (offset != null && requested in metadataPairs)
            return group.metadataShift?.let { requested.number - it }
        if (completeSeasonOffset != null) return requested.number - completeSeasonOffset
        val range = group.captionRange
        return if (range != null && requested.number in range.first) requested.number - range.second else requested.number
    }

    /** A unique substantive subtitle can identify one row, without assigning nearby episodes an invented number. */
    fun byCaption(requested: AnimeEpisode, name: String): AnimeSourceEpisode? {
        if (!verifiedWork || requested !in details) return null
        val caption = animeEpisodeCaption(requested.title) ?: return null
        if (captions[caption] != requested || caption in workNames) return null
        val group = group(name)
        val row = group.sourceCaptions[caption] ?: return null
        if (row.id.isBlank() || row.url.isBlank()) return null
        val sourceNumber = row.number ?: return row.takeIf { caption.length >= 3 }
        if (requested in metadataPairs && offset != null) {
            val shift = group.metadataShift
            return row.takeIf { if (shift != null) sourceNumber == requested.number - shift
                else sourceNumber == requested.number || sourceNumber == requested.numberInSubject }
        }
        if (sourceNumber == requested.number) return row
        val range = group.captionRange
        if (range != null && requested.number in range.first)
            return row.takeIf { sourceNumber == requested.number - range.second }
        // A unique caption identifies just this episode. Never extend a displacement from a single title.
        if (sourceNumber in absoluteNumbers || requested.number in group.numbers) return null
        val seasonOpening = namedSeason != null && requested.number == firstAbsolute && sourceNumber == 1.0
        return row.takeIf { caption.length >= 4 || seasonOpening }
    }
}
