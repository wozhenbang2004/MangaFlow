package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Original data-only adapters for public HTML contracts observed 2026-11-16.
 * No third-party JavaScript, extracted keys, account sessions or challenge bypass is bundled.
 * Direct/public player pages use the existing restricted resolver. Encrypted or challenged
 * lines remain unavailable; directory support does not promise playback or measured speed.
 */
internal class BundledHtmlAnimeProvider(
    override val id: String,
    override val revision: String,
) : AnimeProvider {
    private val aki = id == "anime-native-akianime"
    override val name = if (aki) "AkiAnime" else "动漫巴士"
    override val homeUrl = if (aki) "https://www.akianime.com/" else "https://dmbus.cc/"
    override val supportsSearchPagination = !aki
    private val detailPrefix = if (aki) "/bgmdetail/" else "/v/"
    private val playPrefix = if (aki) "/bgmplay/" else "/p/"

    init { require(id in BUNDLED_HTML_ANIME_IDS) }

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && page >= 1)
        if (aki && page > 1) return emptyList()
        val url = animePublicUrl(homeUrl).newBuilder().apply {
            if (aki) addPathSegment("bgmsearch").addPathSegment(query.trim() + "-".repeat(13) + ".html")
            else addPathSegment("s----------.html").addQueryParameter("wd", query.trim()).addQueryParameter("page", page.toString())
        }.build()
        val http = AnimeHttp(id)
        return try {
            val response = http.request(url, AnimeSourceStage.SEARCH, referer = homeUrl)
            withContext(Dispatchers.Default) {
                val document = Ksoup.parse(response.text, response.url.toString())
                val rows = document.select("a[href*=$detailPrefix]").mapNotNull { anchor ->
                    val target = runCatching { animePublicUrl(animeAbsolute(homeUrl, anchor.attr("href"))) }.getOrNull()
                        ?: return@mapNotNull null
                    if (target.host != animePublicUrl(homeUrl).host || !target.encodedPath.startsWith(detailPrefix)) return@mapNotNull null
                    val remoteId = target.pathSegments.last().removeSuffix(".html")
                    if (remoteId.isBlank() || remoteId.contains('/')) return@mapNotNull null
                    val label = anchor.attr("title").ifBlank { anchor.selectFirst("h3")?.text().orEmpty() }
                        .ifBlank { anchor.text() }.trim()
                    if (label.isEmpty()) return@mapNotNull null
                    // Only associate a cover from the same immediate card, never a neighbouring work.
                    val card = anchor.parent()
                    val image = anchor.selectFirst("img") ?: card?.selectFirst("img")
                    val cover = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
                        .ifBlank { card?.selectFirst("a.cover[data-bg]")?.attr("data-bg").orEmpty() }
                    AnimeSourceTitle(id, remoteId, label, target.toString(), sourceCover(homeUrl, cover))
                }.distinctBy { it.id }
                if (rows.isEmpty() && document.select("a[href*=$detailPrefix]").isNotEmpty())
                    throw AnimeSourceException(id, AnimeSourceStage.SEARCH, "来源页面结构已变化，无法识别搜索结果。")
                if (rows.isEmpty() && listOf("没有找到", "暂无相关", "搜索结果为空", "共0条", "共 0 条")
                        .none { response.text.contains(it) })
                    throw AnimeSourceException(id, AnimeSourceStage.SEARCH, "来源未返回可识别的搜索页面，可能需要原站验证。")
                rows
            }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        require(title.sourceId == id)
        val url = animePublicUrl(title.url)
        require(url.host == animePublicUrl(homeUrl).host && url.encodedPath == "$detailPrefix${title.id}.html")
        val http = AnimeHttp(id)
        return try {
            val response = http.request(url, AnimeSourceStage.CATALOG, referer = homeUrl)
            val (identity, episodes) = withContext(Dispatchers.Default) {
                val document = Ksoup.parse(response.text, response.url.toString())
                val pathPattern = Regex("^" + Regex.escape(playPrefix + title.id) + "-(\\d+)-(\\d+)\\.html$")
                val episodes = document.select("a[href*=$playPrefix]").mapNotNull { anchor ->
                    val target = runCatching { animePublicUrl(animeAbsolute(homeUrl, anchor.attr("href"))) }.getOrNull()
                        ?: return@mapNotNull null
                    if (target.host != url.host) return@mapNotNull null
                    val parts = pathPattern.matchEntire(target.encodedPath)?.groupValues ?: return@mapNotNull null
                    val label = anchor.text().trim()
                    if (label.isEmpty()) return@mapNotNull null
                    // Native line and episode IDs own identity; title similarity never merges lines.
                    AnimeSourceEpisode("${title.id}:${parts[1]}:${parts[2]}", label, target.toString(),
                        sourceEpisodeNumber(label), sourceEpisodeKind(label), "线路 ${parts[1]}")
                }.distinctBy { it.id }
                // Read only work-local metadata, not recommendation cards or global year filters.
                val info = document.selectFirst(".detail-info")
                val detailName = if (aki) info?.selectFirst("h3")?.text().orEmpty()
                    else document.selectFirst("h1.v_title a")?.text().orEmpty()
                val years = if (aki) info?.select("a[href]").orEmpty().mapNotNull { anchor ->
                    val target = runCatching { animePublicUrl(animeAbsolute(homeUrl, anchor.attr("href"))) }.getOrNull()
                    if (target == null || target.host != url.host) null else Regex("""^/bgmsearch/-+(\d{4})\.html$""")
                        .matchEntire(target.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
                }.distinct() else document.select("meta[property=og:video:release_date]").mapNotNull { meta ->
                    Regex("""^(\d{4})(?:-\d{2}-\d{2}(?:[T ].*)?)?$""").matchEntire(meta.attr("content").trim())
                        ?.groupValues?.get(1)?.toIntOrNull()
                }.distinct()
                val year = years.singleOrNull()?.takeIf { it in 1900..2100 }
                val identity = if (response.url.host == url.host && response.url.encodedPath == url.encodedPath &&
                    detailName.trim() == title.title.trim() && year != null && (title.year == null || title.year == year))
                    title.copy(year = year) else title
                identity to episodes
            }
            if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                "来源未返回可识别的剧集，可能需要原站验证或页面规则已变化。")
            AnimeSourceCatalog(identity, episodes)
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        require(catalog.title.sourceId == id && catalog.episodes.any { it == episode })
        require(animePublicUrl(episode.url).host == animePublicUrl(homeUrl).host)
        val http = AnimeHttp(id)
        return try {
            val stream = AnimeStreamResolver(http).resolve(episode.url, homeUrl)
            AnimePlaybackAsset(id = "$id:${animeHash(episode.id + "\n" + animeContentIdentity(stream.url))}",
                sourceId = id, sourceEpisodeId = episode.id, url = stream.url, route = http.route,
                title = episode.title, edition = episode.edition, mimeType = stream.mimeType,
                headers = mapOf("User-Agent" to AnimeHttp.USER_AGENT, "Referer" to stream.referer),
                subtitles = stream.subtitles, sourceRevision = revision)
        } finally { http.close() }
    }
}

internal val BUNDLED_HTML_ANIME_IDS = setOf("anime-native-akianime", "anime-native-dmbus", "anime-native-ani")
internal const val BUNDLED_HTML_ANIME_VERSION = "native-2026-11-17"
