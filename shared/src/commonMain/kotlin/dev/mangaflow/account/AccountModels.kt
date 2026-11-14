package dev.mangaflow.account

import dev.mangaflow.data.Manga

data class AccountProfile(val username: String, val nickname: String)

/** Account pages use a zero-based page index. */
data class CloudPage<T>(val items: List<T>, val hasMore: Boolean)

/** An empty chapter ID means the source did not provide usable chapter progress. */
data class CloudHistoryEntry(val manga: Manga, val chapterId: String, val chapterTitle: String)

/** Account-owned shelf data. Native ordering ranks are not timestamps or local reading positions. */
data class CopyShelfMetadata(
    val joinedAt: Long? = null,
    val chapterId: String = "",
    val chapterTitle: String = "",
    val readAt: Long? = null,
    /** Zero is newest in the complete native -datetime_modifier collection response. */
    val joinedOrder: Int? = null,
    /** Zero is newest in native -datetime_browse order; null means no known cloud reading record. */
    val readOrder: Int? = null,
)
