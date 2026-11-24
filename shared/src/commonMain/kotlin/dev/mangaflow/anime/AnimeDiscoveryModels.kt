package dev.mangaflow.anime

enum class AnimeDiscoveryTab { HOME, SEASON, CALENDAR, RANKING, GENRES, SOURCES }
enum class AnimeDiscoveryProvider { BANGUMI, ANILIST, DOUBAN }
enum class AnimeDiscoveryOrder { RANK, POPULARITY, SCORE, TRENDING, NEWEST }
enum class AnimeDiscoveryFormat { SERIES, MOVIE, SPECIAL, SHORT }
enum class AnimeSeason(val startMonth: Int) { WINTER(1), SPRING(4), SUMMER(7), FALL(10) }

internal fun animeDiscoveryOrders(provider: AnimeDiscoveryProvider): List<AnimeDiscoveryOrder> = when (provider) {
    AnimeDiscoveryProvider.DOUBAN -> listOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE, AnimeDiscoveryOrder.NEWEST)
    AnimeDiscoveryProvider.BANGUMI -> listOf(AnimeDiscoveryOrder.RANK, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
    AnimeDiscoveryProvider.ANILIST -> listOf(AnimeDiscoveryOrder.TRENDING, AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.SCORE)
}

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
    val region: String = "",
    val format: AnimeDiscoveryFormat? = null,
)

internal fun defaultAnimeRankingQuery(provider: AnimeDiscoveryProvider): AnimeDiscoveryQuery =
    AnimeDiscoveryQuery(provider = provider, order = animeDiscoveryOrders(provider).first())

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
    /** True only while a visible source has a live recovery timer. */
    val retryScheduled: Boolean = false,
    /** Current scheduled/running automatic attempt (1–3), otherwise zero. */
    val retryAttempt: Int = 0,
)

fun animeDiscoveryFeedKey(tab: AnimeDiscoveryTab): String = "browse:" + tab.name
