package dev.mangaflow.anime

enum class AnimeDiscoveryTab { HOME, SEASON, CALENDAR, RANKING, GENRES, SOURCES }
enum class AnimeDiscoveryProvider { BANGUMI, ANILIST }
enum class AnimeDiscoveryOrder { RANK, POPULARITY, SCORE, TRENDING }
enum class AnimeDiscoveryFormat { SERIES, MOVIE, SPECIAL, SHORT }
enum class AnimeSeason(val startMonth: Int) { WINTER(1), SPRING(4), SUMMER(7), FALL(10) }

/** The same request describes both the visible controls and the actual remote query. */
data class AnimeDiscoveryQuery(
    val provider: AnimeDiscoveryProvider = AnimeDiscoveryProvider.BANGUMI,
    val order: AnimeDiscoveryOrder = AnimeDiscoveryOrder.POPULARITY,
    val year: Int? = null,
    val season: AnimeSeason? = null,
    val tag: String = "",
    val calendar: Boolean = false,
    val weekday: Int? = null,
    val combined: Boolean = false,
)

data class AnimeSourceScore(val provider: AnimeDiscoveryProvider, val rating: Double, val scale: Int,
    val ratingCount: Int? = null)

data class AnimeScoreCheck(val loading: Boolean = false, val error: String? = null, val message: String? = null)

/** A foreign discovery reference is never promoted to a canonical Bangumi identity by its title. */
data class AnimeDiscoveryItem(
    val key: String,
    val title: String,
    val coverUrl: String = "",
    val year: Int? = null,
    val episodeCount: Int? = null,
    val rating: Double? = null,
    val ratingScale: Int = 10,
    val rank: Int? = null,
    val popularity: Long? = null,
    val trending: Long? = null,
    val airWeekday: Int? = null,
    val nextEpisode: Int? = null,
    val nextAiringAt: Long? = null,
    val subject: AnimeSubject? = null,
    val reference: AnimeSourceTitle? = null,
    val aliases: List<String> = emptyList(),
    val format: AnimeDiscoveryFormat? = null,
    val sourceScores: List<AnimeSourceScore> = emptyList(),
    val coverProvider: AnimeDiscoveryProvider? = null,
)

data class AnimeDiscoveryPage(
    val items: List<AnimeDiscoveryItem>,
    val page: Int,
    val hasMore: Boolean,
    val fetchedAt: Long,
)

data class AnimeDiscoveryFeed(
    val query: AnimeDiscoveryQuery,
    val items: List<AnimeDiscoveryItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val fetchedAt: Long? = null,
    val sourceErrors: Map<AnimeDiscoveryProvider, String> = emptyMap(),
)

data class AnimeDiscoverySection(
    val key: String,
    val title: String,
    val query: AnimeDiscoveryQuery,
    val destination: AnimeDiscoveryTab,
)

fun animeDiscoveryFeedKey(tab: AnimeDiscoveryTab): String = "browse:" + tab.name
