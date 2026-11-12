package dev.mangaflow.data

internal data class ReviewProviderInfo(
    val id: String, val name: String, val mark: String, val homeUrl: String,
    val scoreScale: Int? = null,
)

/** Display order and native scales are shared by detail, cards and the persisted cache. */
internal object ReviewProviders {
    val all = listOf(
        ReviewProviderInfo("bangumi", "Bangumi", "bgm", "https://bgm.tv", 10),
        ReviewProviderInfo("anilist", "AniList", "AL", "https://anilist.co", 100),
        ReviewProviderInfo("mangaupdates", "MangaUpdates", "MU", "https://www.mangaupdates.com", 10),
        ReviewProviderInfo("myanimelist", "MyAnimeList", "MAL", "https://myanimelist.net", 10),
        ReviewProviderInfo("kitsu", "Kitsu", "Kitsu", "https://kitsu.app", 100),
        ReviewProviderInfo("mangadex", "MangaDex", "MD", "https://mangadex.org", 10),
        ReviewProviderInfo("shikimori", "Shikimori", "Shiki", "https://shikimori.io"),
    )
    val ids = all.map(ReviewProviderInfo::id)
    val metricIds = all.filter { it.scoreScale != null }.map(ReviewProviderInfo::id)
    val displayOrder = listOf("copy") + ids
    fun find(id: String): ReviewProviderInfo? = all.firstOrNull { it.id == id }
    fun order(id: String): Int = displayOrder.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE
}
