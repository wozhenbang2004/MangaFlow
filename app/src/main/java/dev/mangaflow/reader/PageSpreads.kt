package dev.mangaflow.reader

import dev.mangaflow.data.Page

/** Indexes are always the original zero-based image indexes, never UI spread indexes. */
internal data class PageSpread(val first: Int, val second: Int? = null) {
    val last: Int get() = second ?: first
    fun contains(index: Int): Boolean = index == first || index == second
}

/**
 * Treat the cover, declared full-width artwork, and user-marked correction pages as single.
 * A single inserted page flips the pairing of everything that follows without reordering images.
 * The source order remains authoritative; RTL changes only which side is drawn first.
 */
internal fun pageSpreads(
    pages: List<Page>,
    coverAlone: Boolean,
    manuallySingle: Set<Int>,
): List<PageSpread> {
    fun isSingle(index: Int): Boolean {
        val page = pages[index]
        val wideArtwork = page.width > 0 && page.height > 0 &&
            page.width.toFloat() / page.height >= 1.25f
        return (index == 0 && coverAlone) || wideArtwork || index in manuallySingle
    }

    val result = ArrayList<PageSpread>(pages.size)
    var index = 0
    while (index < pages.size) {
        if (!isSingle(index) && index + 1 < pages.size && !isSingle(index + 1)) {
            result += PageSpread(index, index + 1)
            index += 2
        } else {
            result += PageSpread(index)
            index++
        }
    }
    return result
}
