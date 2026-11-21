package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.data.plugins.YUZU_HTTP_PLAYBACK_ENDPOINTS
import dev.mangaflow.data.plugins.PublicPlaybackParserGrant
import dev.mangaflow.data.plugins.publicAppCleartextMethod
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull

/** Exact public protocol endpoints read from the pinned original scripts, not a TLS fallback. */
private val REVIEWED_HTTP_ENDPOINTS = mapOf(
    "anime-lanerc-sx" to ("53873698838e6ebde92978c52f87667b93e7f8938d8d17aeb8388a1470cd4e02" to "http://app2.dsx.ac/ndsx.php/v7/logs"),
    "anime-lanerc-gugu" to ("323079886b1ffb0b7023545471360cb2851c19af524a0f33bcb655f133376e1f" to "http://91.110.236.125:38569/nxyz.php/v7/logs"),
    "anime-lanerc-manguo" to ("177ecf39473b12aa042cdbbad203af47e3cfeacb54520d55da9a88b704dd546c" to "http://23.224.111.70:7788/nzdr.php/v7/logs"),
    "anime-lanerc-nmeng" to ("c00719bb8aca5d66a8aea123269bcdd2860fe6f1329e9b2f7cfc7ab5bd9e19df" to "http://198.16.32.138:12345/nxnm.php/v7/logs"),
    "anime-lanerc-07" to ("7505abdae0dcdab1fad683e8c028bb41e4c5986c2c2ebafff88640e83ce3ecd6" to "http://23.224.53.35:2345/nllq.php/v7/logs"),
    "anime-lanerc-23" to ("33140ab5c9ef1a79c3aa08f9ada6aaacdc158b8a9d99916af5acf479e6f9b29d" to "http://118.184.173.5:19987/app/bn/v2"),
    "anime-lanerc-ain" to ("5b34560f7ffb4b17be9877bf21579a10afef75139c13cc56324b91f835044e8a" to "http://frontend-static.kurte.cn/server_list_v2.json"),
)

internal fun lanercCleartextEndpoints(id: String, digest: String, method: String): Set<String> {
    val endpoint = REVIEWED_HTTP_ENDPOINTS[id]?.takeIf { it.first == digest }?.second ?: return emptySet()
    return if (id == "anime-lanerc-gugu" && method == "play") setOf(endpoint) + YUZU_HTTP_PLAYBACK_ENDPOINTS
    else setOf(endpoint)
}

internal fun lanercRequestUrl(value: String, cleartextEndpoints: Set<String>,
    playbackParsers: List<PublicPlaybackParserGrant> = emptyList()): HttpUrl {
    require(value.length <= 8192 && value.none { it.code < 32 || it.code == 127 }) { "来源请求地址无效。" }
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("来源请求地址无效。")
    if (url.isHttps) return animePublicUrl(value)
    if (url.scheme != "http" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.fragment != null || (publicAppCleartextMethod(url.toString(), cleartextEndpoints) == null &&
            playbackParsers.none { it.allows(url.toString()) })
    ) {
        throw LanercCapabilityException("此来源返回了当前版本不支持的 HTTP 接口。")
    }
    // Reuse the same public-host checks; transport still pins an actually public DNS/IP address.
    animePublicUrl(url.newBuilder().scheme("https").build().toString())
    return url
}
