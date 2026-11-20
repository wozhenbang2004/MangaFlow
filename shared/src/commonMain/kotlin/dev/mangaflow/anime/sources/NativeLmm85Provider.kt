package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.net.HttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val NATIVE_LMM85_SOURCE = "anime-native-lmm85"
internal const val LMM85_HOME = "https://www.lmm85.com/"

internal fun isLmm85Source(sourceId: String): Boolean =
    sourceId == NATIVE_LMM85_SOURCE || sourceId == "anime-lanerc-lmm85"

/** Public LMM85 HTML contract read from the published adapter. Media is resolved independently. */
internal class NativeLmm85Provider(override val revision: String) : AnimeProvider {
    override val id = NATIVE_LMM85_SOURCE
    override val name = "LMM85 · 路漫漫"
    override val homeUrl = LMM85_HOME
    override val supportsLatest = true

    override suspend fun latest(page: Int): List<AnimeSourceTitle> {
        require(page >= 1)
        val path = if (page == 1) "label/new.html" else "label/new/page/$page.html"
        return readCards(animePublicUrl(homeUrl + path), AnimeSourceStage.CATALOG)
    }

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && query.length <= 500 && page >= 1)
        val url = animePublicUrl(homeUrl).newBuilder().addPathSegments("vod/search/page/$page/wd")
            .addPathSegment(query.trim() + ".html").build()
        return readCards(url, AnimeSourceStage.SEARCH)
    }

    private suspend fun readCards(url: HttpUrl, stage: AnimeSourceStage): List<AnimeSourceTitle> {
        val http = AnimeHttp(id)
        try {
            val document = readPage(http, url, stage)
            return withContext(Dispatchers.Default) {
                val titles = document.select(".img-box.cover-md").mapNotNull { artwork ->
                    val card = artwork.parent() ?: return@mapNotNull null
                    val artworkLink = artwork.selectFirst("a[href*=/detail/]")?.attr("href")
                    val anchor = card.selectFirst("h6.title a[href]") ?: artworkLink?.let {
                        card.parent()?.selectFirst("h6.title a[href]")
                    } ?: return@mapNotNull null
                    val detail = localPath(anchor.attr("href"))?.let(DETAIL::matchEntire) ?: return@mapNotNull null
                    if (artworkLink != null && localPath(artworkLink) != detail.value) return@mapNotNull null
                    val title = anchor.text().ifBlank { return@mapNotNull null }
                    val image = artwork.selectFirst("img")
                    val cover = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
                    val work = detail.groupValues[1]
                    AnimeSourceTitle(id, work, title, homeUrl + detail.value.removePrefix("/"), sourceCover(homeUrl, cover),
                        updateText = artwork.selectFirst("span.label")?.text().orEmpty(), sourceRevision = revision)
                }.distinctBy { it.id }
                if (titles.isEmpty() && document.select("a[href*=/detail/]").isNotEmpty() && !document.text().contains("没有找到"))
                    throw AnimeSourceException(id, stage, "路漫漫的栏目布局发生变化，无法读取结果。")
                titles
            }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val work = requireWork(title)
        val http = AnimeHttp(id)
        try {
            val document = readPage(http, animePublicUrl(title.url), AnimeSourceStage.CATALOG, exactPath = true)
            return withContext(Dispatchers.Default) {
                val name = document.selectFirst("h1.page-title")?.text().orEmpty()
                val cover = document.selectFirst("img.url_img")?.attr("src").orEmpty()
                val year = document.select(".video-info a[href*=/year/], .video-info-item a[href*=/year/]").asSequence().mapNotNull { item ->
                    localPath(item.attr("href"))?.let(YEAR::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
                }.firstOrNull()
                val description = document.selectFirst(".video-info-item.video-info-content")?.text().orEmpty()
                val groups = document.select("[data-dropdown-value]").map { it.attr("data-dropdown-value").trim() }
                val episodes = document.select("a[href*=/play/]").mapNotNull { anchor ->
                    val match = localPath(anchor.attr("href"))?.let(PLAY::matchEntire) ?: return@mapNotNull null
                    val (owner, line, _) = match.destructured
                    if (owner != work) return@mapNotNull null
                    val label = anchor.selectFirst("span")?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val group = groups.getOrNull((line.toIntOrNull() ?: 0) - 1)?.ifBlank { "线路 $line" } ?: "线路 $line"
                    AnimeSourceEpisode(match.value, label, homeUrl + match.value.removePrefix("/"),
                        sourceEpisodeNumber(label), sourceEpisodeKind(label), group)
                }.distinctBy { it.id }
                if (episodes.isEmpty() && name.isBlank() && description.isBlank())
                    throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "路漫漫没有返回可识别的作品资料。")
                AnimeSourceCatalog(title.copy(title = name.ifBlank { title.title },
                    coverUrl = sourceCover(homeUrl, cover).ifBlank { title.coverUrl }, year = year ?: title.year,
                    externalIds = title.externalIds + ("lmm85" to work), sourceRevision = revision), episodes,
                    notice = if (episodes.isEmpty()) "路漫漫暂未返回剧集。" else null, description = description)
            }
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        val work = requireWork(catalog.title)
        require(catalog.title.sourceRevision == revision && episode in catalog.episodes)
        require(PLAY.matchEntire(episode.id)?.groupValues?.get(1) == work &&
            episode.url == homeUrl + episode.id.removePrefix("/"))
        val http = AnimeHttp(id)
        try {
            val media = AnimeStreamResolver(http, sameOriginPages = true).resolve(episode.url, homeUrl)
            return AnimePlaybackAsset("$id:${animeHash(episode.id + "\n" + animeContentIdentity(media.url))}",
                id, episode.id, media.url, http.route, title = episode.title,
                headers = mapOf("Referer" to media.referer, "User-Agent" to AnimeHttp.USER_AGENT),
                mimeType = media.mimeType, subtitles = media.subtitles, sourceRevision = revision)
        } finally { http.close() }
    }

    private suspend fun readPage(http: AnimeHttp, url: HttpUrl, stage: AnimeSourceStage, exactPath: Boolean = false): Document {
        val page = http.request(url, stage, referer = homeUrl, sameOriginRedirects = true)
        return withContext(Dispatchers.Default) {
            val document = Ksoup.parse(page.text, page.url.toString())
            if (document.title().contains("Just a moment", true) || document.title().contains("身份验证") ||
                page.text.contains("/cdn-cgi/challenge-platform/"))
                throw AnimeSourceException(id, stage, "路漫漫对此页要求访问验证。", 403)
            if (exactPath && page.url.encodedPath != url.encodedPath)
                throw AnimeSourceException(id, stage, "路漫漫跳转到其他页面，请刷新作品。")
            document
        }
    }

    private fun requireWork(title: AnimeSourceTitle): String {
        val expected = "/detail/${title.id}.html"
        require(title.sourceId == id && DETAIL.matches(expected) && title.url == homeUrl + expected.removePrefix("/"))
        return title.id
    }

    private fun localPath(link: String): String? = runCatching {
        val url = animePublicUrl(animeAbsolute(homeUrl, link))
        url.encodedPath.takeIf { url.host == "www.lmm85.com" && url.port == 443 }
    }.getOrNull()

    private companion object {
        val DETAIL = Regex("/detail/([0-9]{1,24})\\.html")
        val PLAY = Regex("/play/([0-9]{1,24})_([0-9]{1,8})_([0-9]{1,8})\\.html")
        val YEAR = Regex("/year/((?:19|20)[0-9]{2})\\.html")
    }
}
