package dev.mangaflow.data

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.platform.ConcurrentMap
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class ReviewNetworkProbeResult(val valid: Boolean, val observed: Boolean, val responseMillis: Long?)

/** Anonymous live service reads only: no associations, metrics cache, view-state writes or account clients. */
internal suspend fun probeReviewNetwork(context: PlatformContext, id: String, route: DownloadRouteSnapshot): ReviewNetworkProbeResult {
    require(ReviewProviders.find(id) != null)
    val transfers = MutableStateFlow<List<Long>>(emptyList())
    val observed = MutableStateFlow(false)
    val client = NetworkClient(route = route, observeRelayTraffic = false, onTransfer = { result ->
        if (!result.failed && result.code?.let { it in 200..299 } == true) transfers.update { it + result.elapsedMillis }
    })
    try {
        val valid = withContext(Dispatchers.IO + ChapterDownloadTransport(route, client, connectionCheck = true,
            onProbeResponse = { observed.value = true })) {
            if (id in setOf("bangumi", "anilist", "myanimelist")) {
                val site = RankingSite.entries.single { it.id == id }
                val scope = site.scopes.first()
                SiteRankingsRepository(context).page(SiteRankingSelection(site, SiteRankingKind.SCORE, scope), 0).entries.isNotEmpty()
            } else {
                val http = ReviewProviderHttp(SourceHttp(pauses = ConcurrentMap()))
                val provider: ExternalReviewProvider = when (id) {
                    "mangaupdates" -> MangaUpdatesReviewProvider(http)
                    "kitsu" -> KitsuReviewProvider(http)
                    "mangadex" -> MangaDexReviewProvider(http)
                    "shikimori" -> ShikimoriReviewProvider(http)
                    "tencent" -> TencentReviewProvider(http)
                    "bahamut" -> BahamutReviewProvider(http)
                    "manhuagui" -> ManhuaguiReviewProvider(http)
                    "mangabaka" -> MangaBakaReviewProvider(http)
                    else -> error("Unknown review provider")
                }
                // This is a real public catalogue query, not a fabricated rating or saved user work.
                val query = if (id in setOf("tencent", "bahamut", "manhuagui")) "海贼王" else "One Piece"
                provider.search(query).any { it.match.id.isNotBlank() && it.titles.any(String::isNotBlank) }
            }
        }
        return ReviewNetworkProbeResult(valid, observed.value && transfers.value.isNotEmpty(), transfers.value.takeIf { it.isNotEmpty() }?.sum())
    } finally { client.close() }
}
