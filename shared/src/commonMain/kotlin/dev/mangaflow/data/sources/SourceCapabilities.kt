package dev.mangaflow.data.sources

import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.SourceException

/** Only capabilities implemented by the installed adapter are exposed to the shared UI. */
enum class SourceFeature { DISCOVERY, SEARCH, CATEGORIES, RANKING, ACCOUNT, FAVORITES, COMMENTS }

data class SourceCapabilities(
    val features: Set<SourceFeature> = setOf(SourceFeature.DISCOVERY, SourceFeature.SEARCH),
    val loginRequired: Boolean = false,
) {
    fun supports(feature: SourceFeature): Boolean = feature in features
}

enum class SourceFeedKind { LATEST, EXPLORE, CATEGORY, RANKING }
data class SourceChoice(val id: String, val title: String)
data class SourceFilter(val id: String, val title: String, val choices: List<SourceChoice>, val defaultValue: String = "")
data class SourceSetting(val key: String, val title: String, val value: String,
    val type: String = "input", val choices: List<SourceChoice> = emptyList())
data class SourceFeed(val id: String, val title: String, val kind: SourceFeedKind = SourceFeedKind.EXPLORE,
    val filters: List<SourceFilter> = emptyList())
data class SourceBrowseRequest(val feedId: String = "latest", val page: Int = 0,
    val options: List<String> = emptyList())
data class SourceAccountState(val signedIn: Boolean = false, val displayName: String = "")
data class SourceFavoriteFolder(val id: String, val title: String)
data class SourceComment(val id: String, val author: String, val content: String,
    val avatar: String = "", val time: String = "", val likes: Int = 0)
data class SourceCommentPage(val items: List<SourceComment>, val hasMore: Boolean = false)

internal fun unsupportedSourceFeature(): Nothing = throw SourceException("此来源未提供这项能力。")
