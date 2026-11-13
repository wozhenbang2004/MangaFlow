package dev.mangaflow.data

internal const val CHAPTER_DIMENSION_SAMPLE_LIMIT = 32

/** Existing page metadata only. These dimensions are samples, never a visual-quality score. */
internal data class ChapterQualityInfo(
    val totalPages: Int?,
    val inspectedPages: Int,
    val dimensionPages: Int,
    val minWidth: Int?,
    val maxWidth: Int?,
    val minHeight: Int?,
    val maxHeight: Int?,
)

/** Bound CPU work even for long chapters; do not read files, decode images or fetch missing data. */
internal fun chapterQualityInfo(pages: List<Page>, totalPages: Int?): ChapterQualityInfo {
    val inspected = pages.asSequence().take(CHAPTER_DIMENSION_SAMPLE_LIMIT)
        .filter { it.index >= 0 }.distinctBy(Page::index).toList()
    val known = inspected.filter { it.width > 0 && it.height > 0 }
    return ChapterQualityInfo(
        totalPages = totalPages?.takeIf { it > 0 && it >= inspected.size },
        inspectedPages = inspected.size,
        dimensionPages = known.size,
        minWidth = known.minOfOrNull(Page::width), maxWidth = known.maxOfOrNull(Page::width),
        minHeight = known.minOfOrNull(Page::height), maxHeight = known.maxOfOrNull(Page::height),
    )
}
