package dev.mangaflow.account

import dev.mangaflow.data.Manga

data class AccountProfile(val username: String, val nickname: String)

/** Account pages use a zero-based page index. */
data class CloudPage<T>(val items: List<T>, val hasMore: Boolean)

/** An empty chapter ID means the source did not provide usable chapter progress. */
data class CloudHistoryEntry(val manga: Manga, val chapterId: String, val chapterTitle: String)
