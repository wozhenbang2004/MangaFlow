package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.sources.lanerc.LanercProvider
import dev.mangaflow.anime.sources.lanerc.LanercSourceKind
import dev.mangaflow.anime.sources.lanerc.LanercSourceListing
import dev.mangaflow.anime.sources.lanerc.LanercSourceStore
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.decodeUrlComponent
import dev.mangaflow.platform.normalizeNfkc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

interface AnimeProvider {
    val id: String
    val name: String
    val homeUrl: String
    val revision: String
    val supportsSearchPagination: Boolean get() = true
    val supportsLatest: Boolean get() = false
    val supportsLatestPagination: Boolean get() = true
    suspend fun latest(page: Int = 1): List<AnimeSourceTitle> =
        throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "此来源尚未提供最近更新目录。")
    suspend fun search(query: String, page: Int = 1): List<AnimeSourceTitle>
    suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog
    suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset
}

class AnimeSourceRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    val extensions = LanercSourceStore.get(context)
    private val nativeProviders: List<AnimeProvider> = listOf(TvtFunProvider(), AgeProvider(), GiriGiriProvider(), LmmProvider())
    private val reads = AnimeSourceReadCache(context) { id -> provider(id)?.revision }
    internal val builtInVideoSources = nativeProviders.associate { it.id to it.name }
    val providers: List<AnimeProvider> get() = nativeProviders + extensions.state.value.mapNotNull(::extensionProvider)

    fun provider(id: String): AnimeProvider? = nativeProviders.firstOrNull { it.id == id }
        ?: extensions.state.value.firstOrNull { it.id == id }?.let(::extensionProvider)

    internal suspend fun search(provider: AnimeProvider, query: String, page: Int = 1,
        forceRefresh: Boolean = false): List<AnimeSourceTitle> = reads.search(provider, query, page, forceRefresh)

    internal suspend fun catalog(provider: AnimeProvider, title: AnimeSourceTitle,
        forceRefresh: Boolean = false): AnimeSourceCatalog = reads.catalog(provider, title, forceRefresh)

    private fun extensionProvider(listing: LanercSourceListing): AnimeProvider? {
        if (!listing.installed || !listing.enabled || listing.kind != LanercSourceKind.VIDEO) return null
        return when (listing.id) {
            "anime-native-jinpai" -> NativeJinpaiProvider(requireNotNull(listing.revision))
            "anime-native-yzx" -> NativeYzxProvider(requireNotNull(listing.revision))
            "anime-native-ani" -> AniChAnimeProvider(requireNotNull(listing.revision))
            NATIVE_LMM85_SOURCE -> NativeLmm85Provider(requireNotNull(listing.revision))
            in BUNDLED_HTML_ANIME_IDS -> BundledHtmlAnimeProvider(listing.id, requireNotNull(listing.revision))
            else -> LanercProvider(extensions, extensions.descriptor(listing.id))
        }
    }
}

/**
 * Source contracts read 2026-11-16:
 * Predidit/KazumiRules @64ea35976c383a3a760eb23d744fcada076317ee (MIT),
 * TvTFun 1.3, giriGiriLove 2.7, LMM 2.4; selectors and endpoint contracts adapted.
 * AGE API: miru-2-0/repo, repo/agedm.io.js @aea94f7e8a16689aeb9e510a86cd4a6cadab286f (MIT).
 * TvTFun API response fields cross-checked against AniBakaBaka/AniBaka assets/rules/tvtfun.json
 * and uerax/Animaku apps/server/src/lib/tvtfun.ts; protocol reference, no source copied.
 * A rule's presence is not evidence that its remote site is currently reachable or playable.
 */
private abstract class RoutedAnimeProvider : AnimeProvider {
    override val revision: String = "2026-11-16"
    protected fun titleUrl(title: AnimeSourceTitle): HttpUrl {
        require(title.sourceId == id) { "该条目不属于所选动漫来源。" }
        return animePublicUrl(title.url).also {
            require(it.host == animePublicUrl(homeUrl).host) { "手动链接必须属于所选动漫来源。" }
        }
    }
    protected fun selected(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode) {
        titleUrl(catalog.title)
        require(catalog.episodes.any { it == episode }) { "剧集已变化，请刷新来源后重新选择。" }
    }
    protected fun asset(
        http: AnimeHttp, episode: AnimeSourceEpisode, url: String,
        mimeType: String? = null, referer: String = homeUrl,
        subtitles: List<AnimeSubtitle> = emptyList(),
    ): AnimePlaybackAsset = AnimePlaybackAsset(
        id = "$id:${animeHash(episode.id + "\n" + episode.edition + "\n" + animeContentIdentity(url))}",
        sourceId = id, sourceEpisodeId = episode.id, url = animeMediaUrl(url).toString(),
        route = http.route, title = episode.title, edition = episode.edition, mimeType = mimeType ?: animeMediaType(url),
        headers = mapOf("User-Agent" to AnimeHttp.USER_AGENT, "Referer" to animePublicUrl(referer).toString()),
        subtitles = subtitles, sourceRevision = revision,
    )
    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        selected(catalog, episode)
        val http = AnimeHttp(id)
        return try {
            val stream = AnimeStreamResolver(http).resolve(episode.url, homeUrl)
            asset(http, episode, stream.url, stream.mimeType, stream.referer, stream.subtitles)
        } finally { http.close() }
    }
    protected fun requireEpisodes(title: AnimeSourceTitle, episodes: List<AnimeSourceEpisode>): AnimeSourceCatalog {
        if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
            "该来源没有返回可选剧集，可能尚未更新或页面规则已变化。")
        return AnimeSourceCatalog(title, episodes)
    }
    protected fun validateQuery(query: String, page: Int): String {
        require(page >= 1) { "页码必须从 1 开始。" }
        return query.trim().also { require(it.isNotEmpty()) { "请输入番剧名称。" } }
    }
}

internal enum class TvtFunPlaybackPhase(val label: String) {
    CATALOG("刷新作品目录"),
    LINES("读取本集线路"),
    ADDRESS("请求播放地址"),
    MEDIA("解析媒体页面"),
}

internal class TvtFunPlaybackException(
    val playbackPhase: TvtFunPlaybackPhase,
    sourceId: String,
    failure: Exception,
) : AnimeSourceException(
    sourceId, AnimeSourceStage.RESOLVE,
    (failure as? AnimeSourceException)?.message ?: "${playbackPhase.label}未完成。",
    (failure as? AnimeSourceException)?.httpStatus,
    (failure as? AnimeSourceException)?.retryAfterSeconds ?: 0,
    cause = failure,
)

private class TvtFunProvider : RoutedAnimeProvider() {
    override val revision = "2026-11-21-public-playback-list"
    override val id = "anime-tvtfun"
    override val name = "TvTFun"
    override val homeUrl = "https://www.tvtfun.net/"
    private val api = animePublicUrl(homeUrl).newBuilder().addPathSegments("api/client/anime").build()

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        val keyword = validateQuery(query, page)
        val http = AnimeHttp(id)
        return try {
            val json = checked(http.json(api.newBuilder().addQueryParameter("keyword", keyword)
                .addQueryParameter("page", page.toString()).addQueryParameter("pageSize", "24").build(),
                AnimeSourceStage.SEARCH, referer = homeUrl), AnimeSourceStage.SEARCH)
            json.requiredObject("data", id, AnimeSourceStage.SEARCH)
                .requiredArray("items", id, AnimeSourceStage.SEARCH).objects().mapNotNull { item ->
                    val slug = item.text("slug", "id")
                    val title = item.text("name", "originalName")
                    if (slug.isBlank() || title.isBlank()) return@mapNotNull null
                    AnimeSourceTitle(id, slug, title,
                        animePublicUrl(homeUrl).newBuilder().addPathSegments("anime").addPathSegment(slug).build().toString(),
                        sourceCover(homeUrl, item.text("coverUrl", "posterUrl")),
                        year = item.positiveInt("year"),
                        externalIds = item.text("bangumiId").takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true }
                            ?.let { mapOf("bangumi" to it) }.orEmpty())
                }.distinctBy { it.id }
        } finally { http.close() }
    }

    private fun identifier(title: AnimeSourceTitle): String {
        val path = titleUrl(title).pathSegments
        val at = path.indexOfFirst { it in setOf("anime", "watch", "video") }
        return animeRemoteId(if (at >= 0) decodeUrlComponent(path.getOrNull(at + 1).orEmpty()) else title.id)
    }

    private suspend fun details(http: AnimeHttp, title: AnimeSourceTitle, stage: AnimeSourceStage): JSONObject =
        checked(http.json(api.newBuilder().addPathSegment(identifier(title)).build(), stage, referer = title.url), stage)
            .requiredObject("data", id, stage)

    private suspend fun episodeLines(http: AnimeHttp, episode: JSONObject, episodeId: String,
        automatic: Boolean): List<JSONObject> {
        val embedded = episode.optJSONArray("sources")?.objects().orEmpty().filter {
            runCatching { animeRemoteId(it.text("id")) }.isSuccess
        }
        val stage = AnimeSourceStage.RESOLVE
        suspend fun playbackList(): List<JSONObject> {
            val data = checked(http.json(api.newBuilder().addPathSegment("episodes").addPathSegment(episodeId)
                .addPathSegment("playback").build(), stage, referer = homeUrl, sameOriginRedirects = true), stage)
                .requiredObject("data", id, stage)
            val reported = data.text("episodeId")
            if (reported.isNotEmpty() && reported != episodeId)
                throw AnimeSourceException(id, stage, "来源返回了另一集的线路，请刷新目录。")
            return data.requiredArray("items", id, stage).objects().filter {
                val lineEpisode = it.text("episodeId")
                (lineEpisode.isBlank() || lineEpisode == episodeId) &&
                    runCatching { animeRemoteId(it.text("id")) }.isSuccess
            }
        }
        if (embedded.isEmpty()) return playbackList()
        if (!automatic || embedded.any(::hasDirectPlayback)) return embedded
        // Embedded stations may need resolution even when the selected episode has
        // a direct stream in its ordinary playback list. Only add declared media.
        val published = try { withTimeoutOrNull(2_500L) { playbackList() }.orEmpty() }
        catch (cancel: CancellationException) { throw cancel }
        catch (route: DownloadRouteException) { throw route }
        catch (_: Exception) { emptyList() }
        val direct = published.groupBy { it.text("id") }.values.mapNotNull { rows ->
            rows.singleOrNull()?.takeIf(::hasDirectPlayback)
        }
        return (direct + embedded).distinctBy { it.text("id") }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val http = AnimeHttp(id)
        return try {
            val data = details(http, title, AnimeSourceStage.CATALOG)
            val slug = data.text("slug").ifBlank { identifier(title) }
            val episodes = data.requiredArray("seasons", id, AnimeSourceStage.CATALOG).objects().flatMapIndexed { seasonIndex, season ->
                val seasonLabel = season.text("name").ifBlank { "第 ${season.positiveInt("seasonNo") ?: seasonIndex + 1} 季" }
                val seasonKey = season.text("id").ifBlank { (seasonIndex + 1).toString() }
                season.requiredArray("episodes", id, AnimeSourceStage.CATALOG).objects().flatMap { ep ->
                    val epId = ep.text("id")
                    val number = ep.finiteNumber("number")
                    if (epId.isBlank() || number == null) return@flatMap emptyList()
                    val label = ep.text("title").ifBlank { "第 ${animeEpisodeLabel(number)} 集" }
                    // The API carries Bangumi episode type independently of its display title.
                    // OP/ED can both be numbered 1; treating their names as MAIN creates ambiguity.
                    val kind = if (ep.has("type") && !ep.isNull("type")) bangumiEpisodeKind(ep.getInt("type"))
                        else sourceEpisodeKind(label)
                    val embedded = ep.optJSONArray("sources")?.objects().orEmpty()
                    // Every real episode belongs to the automatic group, even when only some
                    // rows embed stations. Group choice must not hide the rest of the season.
                    val automaticUrl = animePublicUrl(homeUrl).newBuilder().addPathSegment("watch").addPathSegment(slug)
                        .addPathSegment(animeEpisodeLabel(number)).addQueryParameter("episodeId", epId).build()
                    val automaticEpisode = AnimeSourceEpisode(
                        id = "$seasonKey:$epId:auto", title = label, url = automaticUrl.toString(), number = number,
                        kind = kind, group = "$seasonLabel · 自动线路 [$seasonKey]",
                        edition = seasonLabel, playbackData = "automatic",
                    )
                    listOf(automaticEpisode) + embedded.mapIndexedNotNull { lineIndex, line ->
                        val lineId = line.text("id")
                        if (lineId.isBlank()) return@mapIndexedNotNull null
                        val lineLabel = line.text("lineName", "name").ifBlank { "线路 ${lineIndex + 1}" }
                        val lineKey = line.text("playbackLineId").ifBlank { lineLabel }
                        val url = animePublicUrl(homeUrl).newBuilder().addPathSegment("watch").addPathSegment(slug)
                            .addPathSegment(animeEpisodeLabel(number)).addQueryParameter("episodeId", epId)
                            .addQueryParameter("sourceId", lineId).build()
                        AnimeSourceEpisode(
                            id = "$seasonKey:$epId:$lineId", title = label, url = url.toString(), number = number,
                            kind = kind, group = "$seasonLabel · $lineLabel [$seasonKey/$lineKey]",
                            edition = seasonLabel,
                        )
                    }
                }
            }
            requireEpisodes(title.copy(title = data.text("name").ifBlank { title.title },
                coverUrl = sourceCover(homeUrl, data.text("coverUrl", "posterUrl")).ifBlank { title.coverUrl }), episodes)
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        selected(catalog, episode)
        val selection = animePublicUrl(episode.url)
        val episodeId = animeRemoteId(selection.queryParameter("episodeId").orEmpty())
        val automatic = episode.playbackData == "automatic"
        val requestedSourceId = selection.queryParameter("sourceId")?.let(::animeRemoteId)
        require(automatic || requestedSourceId != null) { "所选线路缺少身份，请刷新剧集。" }
        val http = AnimeHttp(id)
        return try {
            // Refresh exact IDs: a changed/missing line must never fall back to the first episode.
            val currentEpisode = duringPlaybackPhase(TvtFunPlaybackPhase.CATALOG) {
                val data = details(http, catalog.title, AnimeSourceStage.RESOLVE)
                data.requiredArray("seasons", id, AnimeSourceStage.RESOLVE).objects()
                    .flatMap { it.optJSONArray("episodes")?.objects().orEmpty() }
                    .singleOrNull { it.text("id") == episodeId }
                    ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                        "该剧集身份已变化，请刷新剧集并重新选择。")
            }
            val candidates = duringPlaybackPhase(TvtFunPlaybackPhase.LINES) {
                val available = episodeLines(http, currentEpisode, episodeId, automatic)
                if (automatic) {
                    // Keep upstream order within each class. Use declared public media before
                    // rows that still need a site resolver or media page request.
                    val (direct, remaining) = available.filter { runCatching { animeRemoteId(it.text("id")) }.isSuccess }
                        .partition(::hasDirectPlayback)
                    (direct + remaining).take(3)
                        .ifEmpty { throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                            "所选剧集暂未返回可用线路，请重试或选择其他来源。") }
                } else listOf(available.singleOrNull { it.text("id") == requestedSourceId }
                    ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "所选线路已变化，请刷新后重新选择。"))
            }
            if (!automatic) return resolveLine(http, episode, episodeId, candidates.single())
            var lastFailure: Exception? = null
            for (line in candidates) {
                try {
                    withTimeoutOrNull(5_000L) { resolveLine(http, episode, episodeId, line) }?.let { return it }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { lastFailure = failure }
            }
            throw lastFailure ?: AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                "本集线路未能在时限内解析，请重试或选择其他来源。")
        } finally { http.close() }
    }

    private suspend fun resolveLine(http: AnimeHttp, episode: AnimeSourceEpisode, episodeId: String,
        line: JSONObject): AnimePlaybackAsset {
        val sourceId = animeRemoteId(line.text("id"))
        var subtitles = emptyList<AnimeSubtitle>()
        val playback = declaredPlayback(line) ?: duringPlaybackPhase(TvtFunPlaybackPhase.ADDRESS) {
            val resolved = checked(http.json(
                animePublicUrl(homeUrl).newBuilder().addPathSegments("api/site-auth/playback-resolve").build(),
                AnimeSourceStage.RESOLVE, "POST", JSONObject().put("episodeId", episodeId).put("sourceId", sourceId),
                referer = episode.url), AnimeSourceStage.RESOLVE).requiredObject("data", id, AnimeSourceStage.RESOLVE)
            if (resolved.text("sourceId").isNotEmpty() && resolved.text("sourceId") != sourceId)
                throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "来源返回了另一条线路，已停止播放以避免串集。")
            if (resolved.text("episodeId").isNotEmpty() && resolved.text("episodeId") != episodeId)
                throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "来源返回了另一集的播放地址，请刷新目录。")
            subtitles = resolved.optJSONArray("subtitles")?.objects().orEmpty().mapIndexedNotNull { index, subtitle ->
                val url = runCatching { animeMediaAbsolute(homeUrl, subtitle.text("url")) }.getOrNull()
                    ?: return@mapIndexedNotNull null
                AnimeSubtitle(subtitle.text("id").ifBlank { "subtitle-$index" }, url,
                    subtitle.text("label", "language").ifBlank { "字幕 ${index + 1}" },
                    subtitle.text("language").takeIf { it.isNotBlank() },
                    when (subtitle.text("format").lowercase()) { "vtt" -> "text/vtt"; "srt" -> "application/x-subrip"; else -> null },
                    mapOf("User-Agent" to AnimeHttp.USER_AGENT, "Referer" to homeUrl))
            }
            resolved.optJSONObject("playback")?.takeIf { it.text("url").isNotBlank() }
                ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                    "该线路尚未提供播放地址，请选择另一条线路。")
        }
        return duringPlaybackPhase(TvtFunPlaybackPhase.MEDIA) {
            val url = animeMediaAbsolute(homeUrl, playback.text("url"))
            val mediaType = playbackMediaType(playback, url)
            if (mediaType == null) {
                val stream = AnimeStreamResolver(http).resolve(url, homeUrl)
                asset(http, episode, stream.url, stream.mimeType, stream.referer, subtitles + stream.subtitles)
            } else asset(http, episode, url, mediaType, subtitles = subtitles)
        }
    }

    private fun declaredPlayback(line: JSONObject): JSONObject? = line.optJSONObject("playback")?.takeIf {
        !it.optBoolean("requiresResolve", true) && it.text("url").isNotBlank()
    }

    private fun hasDirectPlayback(line: JSONObject): Boolean {
        val playback = declaredPlayback(line) ?: return false
        val url = runCatching { animeMediaAbsolute(homeUrl, playback.text("url")) }.getOrNull() ?: return false
        return playbackMediaType(playback, url) != null
    }

    private fun playbackMediaType(playback: JSONObject, url: String): String? =
        when (playback.text("mediaType").substringBefore(';').trim().lowercase()) {
            "hls", "m3u8", "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "application/vnd.apple.mpegurl"
            "mp4", "video/mp4" -> "video/mp4"
            "dash", "mpd", "application/dash+xml" -> "application/dash+xml"
            else -> animeMediaType(url)
        }

    private suspend fun <T> duringPlaybackPhase(phase: TvtFunPlaybackPhase, action: suspend () -> T): T =
        try { action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: DownloadRouteException) { throw failure }
        catch (failure: Exception) { throw TvtFunPlaybackException(phase, id, failure) }

    private fun checked(json: JSONObject, stage: AnimeSourceStage): JSONObject {
        if (json.text("error").isNotBlank() || (json.has("code") && json.optInt("code", -1) !in setOf(0, 200)))
            throw AnimeSourceException(id, stage, "TvTFun 暂时未返回有效结果，请稍后重试或切换来源。")
        return json
    }
}

private class AgeProvider : RoutedAnimeProvider() {
    override val id = "anime-age"
    override val name = "AGE 动漫"
    override val homeUrl = "https://www.agedm.io/"
    override val supportsLatest = true
    private val api = animePublicUrl("https://api.agedm.io/v2/")
    private val referer = "https://m.agedm.io/"

    // Miru's pinned MIT agedm.io v0.0.4 contract: catalogue order=time, 50 items/page.
    // uptodate is source display text; it does not establish a playable episode count.
    override suspend fun latest(page: Int): List<AnimeSourceTitle> {
        require(page >= 1) { "页码必须从 1 开始。" }
        val http = AnimeHttp(id)
        return try {
            val url = api.newBuilder().addPathSegment("catalog")
                .addQueryParameter("genre", "all").addQueryParameter("label", "all")
                .addQueryParameter("letter", "all").addQueryParameter("order", "time")
                .addQueryParameter("region", "all").addQueryParameter("resource", "all")
                .addQueryParameter("season", "all").addQueryParameter("status", "all")
                .addQueryParameter("year", "all").addQueryParameter("page", page.toString())
                .addQueryParameter("size", "50").build()
            val rows = http.json(url, AnimeSourceStage.CATALOG, referer = referer)
                .requiredArray("videos", id, AnimeSourceStage.CATALOG)
            val titles = (0 until rows.length()).map { index ->
                val row = rows.optJSONObject(index)
                    ?: throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "AGE 最近更新条目格式已变化。")
                val key = row.text("id")
                val title = row.text("name")
                if (key.isBlank() || title.isBlank())
                    throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "AGE 最近更新缺少条目标识或名称。")
                AnimeSourceTitle(id, key, title,
                    animePublicUrl(homeUrl).newBuilder().addPathSegment("detail").addPathSegment(key).build().toString(),
                    sourceCover(homeUrl, row.text("cover")), year = row.positiveInt("year"),
                    updateText = row.text("uptodate"), sourceRevision = revision)
            }.distinctBy { it.id }
            if (DownloadRoutingStore.activeSourceSnapshot(id, DownloadCategory.ANIME) != http.route)
                throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "来源网络路由已改变，请重新读取最近更新。")
            titles
        } finally { http.close() }
    }

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        val keyword = validateQuery(query, page)
        val http = AnimeHttp(id)
        return try {
            val json = http.json(api.newBuilder().addPathSegment("search").addQueryParameter("query", keyword)
                .addQueryParameter("page", page.toString()).build(), AnimeSourceStage.SEARCH, referer = referer)
            json.requiredObject("data", id, AnimeSourceStage.SEARCH)
                .requiredArray("videos", id, AnimeSourceStage.SEARCH).objects().mapNotNull { row ->
                    val key = row.text("id")
                    val title = row.text("name")
                    if (key.isBlank() || title.isBlank()) return@mapNotNull null
                    AnimeSourceTitle(id, key, title,
                        animePublicUrl(homeUrl).newBuilder().addPathSegment("detail").addPathSegment(key).build().toString(),
                        sourceCover(homeUrl, row.text("cover")), year = row.positiveInt("year"))
                }.distinctBy { it.id }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val page = titleUrl(title)
        val key = animeRemoteId(page.pathSegments.dropWhile { it != "detail" }.getOrNull(1) ?: title.id)
        val http = AnimeHttp(id)
        return try {
            val json = http.json(api.newBuilder().addPathSegment("detail").addPathSegment(key).build(),
                AnimeSourceStage.CATALOG, referer = referer)
            val video = json.requiredObject("video", id, AnimeSourceStage.CATALOG)
            // Public detail contract includes its work ID (JSON number or string).
            // Do not expose another work's playlists if an upstream redirect/cache misroutes it.
            if (video.text("id") != key) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                "AGE 返回的作品标识与所选作品不一致，未采用这些播放线路，请重新搜索。")
            val playlists = video.requiredObject("playlists", id, AnimeSourceStage.CATALOG)
            val labels = json.optJSONObject("player_label_arr")
            val parser = json.optJSONObject("player_jx")?.text("zj").orEmpty()
            val episodes = playlists.keys().asSequence().toList().flatMapIndexed { roadIndex, road ->
                val list = playlists.optJSONArray(road)
                    ?: throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "AGE 剧集线路格式已变化。")
                (0 until list.length()).mapNotNull { epIndex ->
                    val item = list.optJSONArray(epIndex) ?: return@mapNotNull null
                    val label = item.optString(0)
                    val value = item.optString(1)
                    if (label.isBlank() || value.isBlank()) return@mapNotNull null
                    val url = when {
                        animeMediaType(value) != null -> animeMediaUrl(value).toString()
                        runCatching { animePublicUrl(value) }.isSuccess -> value
                        road.endsWith("m3u8") && !road.contains("xigua", true) && parser.isNotBlank() ->
                            animeAbsolute(homeUrl, parser + value)
                        else -> animePublicUrl(homeUrl).newBuilder().addPathSegment("play").addPathSegment(key)
                            .addPathSegment((roadIndex + 1).toString()).addPathSegment((epIndex + 1).toString()).build().toString()
                    }
                    val line = labels?.text(road).orEmpty().ifBlank { road }
                    AnimeSourceEpisode("$key:$road:" + animeHash(label + "\n" + animeContentIdentity(value)), label, url, sourceEpisodeNumber(label),
                        sourceEpisodeKind(label), "$line [$road]")
                }
            }
            requireEpisodes(title.copy(title = video.text("name").ifBlank { title.title },
                coverUrl = sourceCover(homeUrl, video.text("cover")).ifBlank { title.coverUrl },
                year = video.positiveInt("year") ?: title.year,
                externalIds = title.externalIds + ("age" to key)),
                episodes.sortedBy { animePlaybackParserPriority(id, it) })
        } finally { http.close() }
    }
}

private class GiriGiriProvider : RoutedAnimeProvider() {
    override val id = "anime-girigiri"
    override val name = "GiriGiriLove"
    override val homeUrl = "https://ani.girigirilove.com/"
    private val api = animePublicUrl("https://m3u8.girigirilove.com/api.php/Vod/")

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        val keyword = validateQuery(query, page)
        val http = AnimeHttp(id)
        return try {
            val json = http.json(api.newBuilder().addPathSegment("get_list").addQueryParameter("vod_name", keyword)
                .addQueryParameter("offset", ((page.toLong() - 1) * 24).toString()).addQueryParameter("limit", "24").build(),
                AnimeSourceStage.SEARCH, referer = homeUrl)
            json.requiredObject("info", id, AnimeSourceStage.SEARCH)
                .requiredArray("rows", id, AnimeSourceStage.SEARCH).objects().mapNotNull { row ->
                    val key = row.text("vod_id")
                    val title = row.text("vod_name")
                    if (key.isBlank() || title.isBlank()) return@mapNotNull null
                    AnimeSourceTitle(id, key, title,
                        animePublicUrl(homeUrl + "GV$key/").toString(),
                        sourceCover(homeUrl, row.text("vod_pic")), year = row.positiveInt("vod_year"))
                }.distinctBy { it.id }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val page = titleUrl(title)
        val segment = page.pathSegments.firstOrNull { it.startsWith("GV") }
        val key = animeRemoteId(segment?.removePrefix("GV")?.removeSuffix(".html")
            ?: page.pathSegments.lastOrNull { it.isNotBlank() } ?: title.id)
        animeNumericId(key)
        val http = AnimeHttp(id)
        return try {
            val row = http.json(api.newBuilder().addPathSegment("get_detail").addQueryParameter("vod_id", key).build(),
                AnimeSourceStage.CATALOG, referer = homeUrl).requiredObject("info", id, AnimeSourceStage.CATALOG)
            val returnedId = row.text("vod_id")
            check(returnedId.isBlank() || returnedId == key) { "GiriGiriLove 返回的作品身份不一致。" }
            val roads = row.text("vod_play_from").split("$$$")
            val groups = row.text("vod_play_url").split("$$$")
            if (roads.size != groups.size) throw AnimeSourceException(id, AnimeSourceStage.CATALOG, "GiriGiriLove 线路数据不完整，请刷新后重试。")
            val episodes = groups.flatMapIndexed { roadIndex, group ->
                group.split('#').mapIndexedNotNull { epIndex, item ->
                    val separator = item.indexOf('$')
                    if (separator <= 0 || separator == item.lastIndex) return@mapIndexedNotNull null
                    val label = item.substring(0, separator).trim()
                    val value = item.substring(separator + 1).trim()
                    val direct = runCatching { animeMediaUrl(value) }.getOrNull()
                    val url = if (direct != null && animeMediaType(direct.toString()) != null) direct.toString()
                        else animePublicUrl("https://pl.girigirilove.com/addons/aplyer/atom.php").newBuilder()
                            .addQueryParameter("key", "0").addQueryParameter("url", value).build().toString()
                    AnimeSourceEpisode("$key:" + animeHash(roads[roadIndex] + "\n" + label + "\n" + animeContentIdentity(value)), label, url, sourceEpisodeNumber(label),
                        sourceEpisodeKind(label), "${roads[roadIndex].ifBlank { "线路 ${roadIndex + 1}" }} [${roadIndex + 1}]")
                }
            }
            requireEpisodes(title.copy(title = row.text("vod_name").ifBlank { title.title },
                coverUrl = sourceCover(homeUrl, row.text("vod_pic")).ifBlank { title.coverUrl },
                year = row.positiveInt("vod_year") ?: title.year,
                externalIds = (title.externalIds - "girigiri") + if (returnedId == key)
                    mapOf("girigiri" to key) else emptyMap()), episodes)
        } finally { http.close() }
    }
}

private class LmmProvider : RoutedAnimeProvider() {
    override val supportsSearchPagination = false
    override val id = "anime-lmm"
    override val revision = "2026-11-21-work-routes"
    override val name = "LMM 动漫"
    override val homeUrl = "https://www.lmm22.com/"

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        val keyword = validateQuery(query, page)
        // This upstream suggest API has no pagination. Subsequent pages are an honest end.
        if (page > 1) return emptyList()
        val http = AnimeHttp(id)
        return try {
            val response = http.json(animePublicUrl(homeUrl).newBuilder().addPathSegments("index.php/ajax/suggest")
                .addQueryParameter("mid", "1").addQueryParameter("wd", keyword).build(),
                AnimeSourceStage.SEARCH, referer = homeUrl)
            response.requiredArray("list", id, AnimeSourceStage.SEARCH).objects().mapNotNull { row ->
                val key = row.text("id")
                val title = row.text("name")
                if (key.isBlank() || title.isBlank()) return@mapNotNull null
                AnimeSourceTitle(id, key, title,
                    animePublicUrl(homeUrl).newBuilder().addPathSegments("index.php/vod/detail/id")
                        .addPathSegment("$key.html").build().toString(),
                    sourceCover(homeUrl, row.text("pic")))
            }.distinctBy { it.id }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val url = titleUrl(title)
        val http = AnimeHttp(id)
        return try {
            val response = http.request(url, AnimeSourceStage.CATALOG, referer = homeUrl)
            val document = withContext(Dispatchers.Default) { Ksoup.parse(response.text, response.url.toString()) }
            val tabs = document.select(".module-tab-item")
            val groups = document.select(".module-player-list")
            val episodes = groups.flatMapIndexed { groupIndex, group ->
                val label = tabs.getOrNull(groupIndex)?.text().orEmpty().ifBlank { "线路 ${groupIndex + 1}" }
                group.select("a[href*=/play/]").map { anchor ->
                    val episodeLabel = anchor.text().trim()
                    AnimeSourceEpisode("${title.id}:" + animeHash(label + "\n" + anchor.attr("href") + "\n" + episodeLabel), episodeLabel,
                        animeAbsolute(response.url.toString(), anchor.attr("href")), sourceEpisodeNumber(episodeLabel),
                        sourceEpisodeKind(episodeLabel), "$label [${groupIndex + 1}]")
                }
            }
            // Read only explicitly labelled dates inside this work's information block.
            // Native work/route ownership is independent of the date's display format.
            val identity = withContext(Dispatchers.Default) {
                val info = document.selectFirst(".video-info")
                val name = info?.selectFirst(".video-info-header h1.page-title")?.text().orEmpty().trim()
                val releaseDates = info?.select(".video-info-items").orEmpty().filter { row ->
                    normalizeNfkc(row.selectFirst(".video-info-itemtitle")?.text().orEmpty())
                        .trim().removeSuffix(":").trim() in setOf("上映", "首播", "年份")
                }.map { row -> row.selectFirst(".video-info-item")?.text().orEmpty().trim() }.distinct()
                val years = releaseDates.map(::lmmReleaseYear)
                val year = years.takeIf { it.isNotEmpty() && it.none { value -> value == null } }
                    ?.filterNotNull()?.distinct()?.singleOrNull()
                val home = animePublicUrl(homeUrl)
                val trustedOrigin = url.scheme == home.scheme && url.host == home.host && url.port == home.port
                val samePage = response.url.scheme == url.scheme && response.url.host == url.host &&
                    response.url.port == url.port && response.url.encodedPath == url.encodedPath
                val exactWork = url.encodedPath == "/index.php/vod/detail/id/${title.id}.html"
                val unverified = title.copy(year = null, externalIds = title.externalIds - "lmm22")
                if (!trustedOrigin || !samePage || !exactWork || name.isBlank()) return@withContext unverified
                val playPath = Regex("""/play/${Regex.escape(title.id)}_\d+_\d+\.html""")
                val sameWorkLines = episodes.isNotEmpty() && episodes.all { episode ->
                    val link = runCatching { animePublicUrl(episode.url) }.getOrNull()
                    link != null && link.scheme == home.scheme && link.host == home.host && link.port == home.port &&
                        playPath.matches(link.encodedPath)
                }
                val ids = if (sameWorkLines) unverified.externalIds + ("lmm22" to title.id) else unverified.externalIds
                unverified.copy(title = name, year = year, externalIds = ids)
            }
            requireEpisodes(identity, episodes)
        } finally { http.close() }
    }
}

/** A year, or an actual ISO release date optionally followed by its region. */
private fun lmmReleaseYear(value: String): Int? {
    val match = Regex("""^(\d{4})(-\d{2}-\d{2})?(?:\s*\([^()]*\))?$""")
        .matchEntire(normalizeNfkc(value).trim()) ?: return null
    val year = if (match.groupValues[2].isBlank()) match.groupValues[1].toIntOrNull()
        else runCatching { LocalDate.parse(match.groupValues[1] + match.groupValues[2]).year }.getOrNull()
    return year?.takeIf { it in 1900..2100 }
}

internal fun sourceCover(base: String, raw: String): String =
    if (raw.isBlank()) "" else runCatching { animeAbsolute(base, raw) }.getOrDefault("")

private val sourceTrailerLabel = Regex("""(?<![a-z0-9])(?:pv|trailer)(?![a-z])""")
private val sourceSpecialLabel = Regex("""(?<![a-z0-9])(?:sp|ova|oad)(?![a-z])|^special(?:\s*[0-9]+|\s*$)""")
private val numericSourceEpisode = Regex("""^(?:第\s*)?([0-9]+(?:\.[0-9]+)?)(?:\s*[集话話回])?(?=$|\s|[:_]|[.-](?![0-9]))""")
private val englishSourceEpisode = Regex("""^(?:EP|Episode)\s*([0-9]+(?:\.[0-9]+)?)(?=$|\s|[:_]|[.-](?![0-9]))""",
    RegexOption.IGNORE_CASE)

internal fun sourceEpisodeKind(label: String): AnimeEpisodeKind {
    val text = normalizeNfkc(label).trim().lowercase()
    return when {
        text.contains("预告") || text.contains("預告") || sourceTrailerLabel.containsMatchIn(text) -> AnimeEpisodeKind.TRAILER
        text.contains("片头") || text.contains("片頭") ||
            Regex("""^(?:op|opening)(?:\d|\s|$)""").containsMatchIn(text) -> AnimeEpisodeKind.OPENING
        text.contains("片尾") || Regex("""^(?:ed|ending)(?:\d|\s|$)""").containsMatchIn(text) -> AnimeEpisodeKind.ENDING
        text.contains("特别") || text.contains("特別") || text.contains("特典") ||
            text.contains("总集") || text.contains("總集") ||
            sourceSpecialLabel.containsMatchIn(text) -> AnimeEpisodeKind.SPECIAL
        else -> AnimeEpisodeKind.MAIN
    }
}

/** No ordinal fallback: an unparseable label stays unresolved for the mapping UI. */
internal fun sourceEpisodeNumber(label: String, exactLabel: Boolean = false): Double? {
    val text = normalizeNfkc(label).trim()
    val match = if (exactLabel) numericSourceEpisode.matchEntire(text) ?: englishSourceEpisode.matchEntire(text)
        else numericSourceEpisode.find(text) ?: englishSourceEpisode.find(text)
    return match?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

internal fun animeEpisodeLabel(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()


/** Exclude known expiring signatures from resource identity, retaining content/quality parameters. */
internal fun animeContentIdentity(raw: String): String {
    val url = runCatching { animeMediaUrl(raw) }.getOrNull() ?: return raw
    val nested = url.queryParameter("url")?.let { runCatching { animeMediaUrl(it) }.getOrNull() }
    val content = nested ?: url
    val builder = content.newBuilder()
    val signatures = setOf("token", "sign", "signature", "expires", "expire", "expiry", "auth_key",
        "wssecret", "wstime", "x-amz-signature", "x-amz-date", "x-amz-expires",
        "x-amz-credential", "x-amz-security-token", "x-amz-signedheaders", "policy", "key-pair-id")
    content.queryParameterNames.filter { it.lowercase() in signatures }.forEach { builder.removeAllQueryParameters(it) }
    return builder.build().toString()
}

/** This only orders known AGE parser capabilities; it never changes an explicit line choice. */
internal fun animePlaybackParserPriority(sourceId: String, episode: AnimeSourceEpisode): Int =
    if (sourceId == "anime-age" && episode.group.endsWith("[xigua]") &&
        animeMediaType(episode.url) == null) 1 else 0
