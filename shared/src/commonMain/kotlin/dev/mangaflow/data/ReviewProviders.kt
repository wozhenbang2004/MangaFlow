package dev.mangaflow.data

internal data class ReviewProviderInfo(
    val id: String, val name: String, val mark: String, val homeUrl: String,
    val scoreScale: Int? = null,
    val hasWorkReviews: Boolean = true,
)

/** Display order and native scales are shared by detail, cards and the persisted cache. */
internal object ReviewProviders {
    val all = listOf(
        ReviewProviderInfo("bangumi", "Bangumi", "bgm", "https://bgm.tv", 10),
        ReviewProviderInfo("anilist", "AniList", "AL", "https://anilist.co", 100),
        ReviewProviderInfo("mangaupdates", "MangaUpdates", "MU", "https://www.mangaupdates.com", 10),
        ReviewProviderInfo("myanimelist", "MyAnimeList", "MAL", "https://myanimelist.net", 10),
        ReviewProviderInfo("kitsu", "Kitsu", "Kitsu", "https://kitsu.app", 100),
        ReviewProviderInfo("mangadex", "MangaDex", "MD", "https://mangadex.org", 10, false),
        ReviewProviderInfo("shikimori", "Shikimori", "Shiki", "https://shikimori.io"),
        ReviewProviderInfo("tencent", "腾讯动漫", "腾讯", "https://ac.qq.com", 0, false),
        ReviewProviderInfo("bahamut", "巴哈姆特", "巴哈", "https://acg.gamer.com.tw", 5, false),
        ReviewProviderInfo("manhuagui", "漫画柜", "漫画柜", "https://www.manhuagui.com", 10, false),
        ReviewProviderInfo("mangabaka", "MangaBaka", "MB", "https://mangabaka.org", hasWorkReviews = false),
    )
    val ids = all.map(ReviewProviderInfo::id)
    val metricIds = all.filter { it.scoreScale != null }.map(ReviewProviderInfo::id)
    val displayOrder = listOf("copy") + ids
    fun find(id: String): ReviewProviderInfo? = all.firstOrNull { it.id == id }
    fun order(id: String): Int = displayOrder.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE
}
