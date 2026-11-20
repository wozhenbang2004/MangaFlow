package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone

internal const val CYCITY_SOURCE_ID = "anime-native-cycity"
internal const val CYCITY_HOME = "https://www.cycani.org/"
internal const val CYCITY_SIGN_IN_REQUIRED = "播放需要登录次元城账号。"

/** Public catalogue and the account-scoped player API used by the official Cycity web client. */
internal class CycityAnimeProvider(
    override val revision: String,
    private val account: CycityAccount,
) : AnimeProvider {
    override val id = CYCITY_SOURCE_ID
    override val name = "次元城"
    override val homeUrl = CYCITY_HOME
    override val supportsLatest = true

    override suspend fun latest(page: Int): List<AnimeSourceTitle> = list(
        "videos", page, "order_by" to "update_time",
    )

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && query.length <= 512)
        return list("videos/search", page, "q" to query.trim())
    }

    private suspend fun list(path: String, page: Int, vararg filters: Pair<String, String>): List<AnimeSourceTitle> {
        require(page >= 1)
        val http = AnimeHttp(id)
        try {
            val data = cycityData(http, path, AnimeSourceStage.SEARCH,
                listOf("page" to page.toString(), "page_size" to "24") + filters)
            val rows = cycityRows(data, AnimeSourceStage.SEARCH, "videos")
            val pager = (data as? JSONObject)?.optJSONObject("pager") ?: data as? JSONObject
            val returnedPage = pager?.optInt("page", page) ?: page
            if (page > 1 && returnedPage < page) return emptyList()
            cycityCheckRoute(http, AnimeSourceStage.SEARCH)
            return rows.objects().mapNotNull { row ->
                val work = row.optLong("video_id").takeIf { it > 0 } ?: row.optLong("id").takeIf { it > 0 }
                    ?: return@mapNotNull null
                val title = row.text("title").ifBlank { return@mapNotNull null }
                card(row, work, title)
            }.distinctBy { it.id }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        val work = requireWork(title)
        val stage = AnimeSourceStage.CATALOG
        val http = AnimeHttp(id)
        try {
            val detail = cycityData(http, "videos/$work", stage).cycityObject(stage)
            if (!detail.isNull("id") && detail.optLong("id") != work)
                throw cycityError(stage, "次元城返回了其他作品的详情。")
            val sources = detail.optJSONArray("play_from")?.objects().orEmpty().mapNotNull { row ->
                val code = row.text("code").takeIf(String::isNotBlank) ?: return@mapNotNull null
                Line(code, row.text("title").ifBlank { code })
            }.distinctBy { it.code }
            val edition = detail.text("version")
            val initialSections = detail.optJSONArray("sections")?.objects().orEmpty()
            val sections = if (sources.isEmpty()) {
                listOf(LineResult(initialSections.mapNotNull { row ->
                    val line = row.text("player_id").ifBlank { "default" }
                    episode(work, row, Line(line, row.text("player_id").ifBlank { "默认线路" }), edition)
                }))
            } else coroutineScope {
                val permits = Semaphore(3)
                sources.map { line ->
                    async {
                        permits.withPermit { sections(http, work, line, edition) }
                    }
                }.awaitAll()
            }
            cycityCheckRoute(http, stage)
            val episodes = sections.flatMap { it.episodes }.distinctBy { it.id }
            if (episodes.isEmpty()) sections.firstNotNullOfOrNull { it.error }?.let { throw it }
            val canonical = card(detail, work, detail.text("title").ifBlank { title.title }).let {
                it.copy(coverUrl = it.coverUrl.ifBlank { title.coverUrl }, year = it.year ?: title.year,
                    externalIds = title.externalIds + it.externalIds)
            }
            return AnimeSourceCatalog(canonical, episodes, notice = when {
                sections.any { it.error != null } -> "部分次元城线路或剧集暂未返回，已保留可选集数。"
                episodes.isEmpty() -> "次元城暂未返回可选剧集。"
                else -> null
            }, description = detail.text("description"))
        } finally { http.close() }
    }

    private suspend fun sections(http: AnimeHttp, work: Long, line: Line, edition: String): LineResult {
        val stage = AnimeSourceStage.CATALOG
        val result = mutableListOf<AnimeSourceEpisode>()
        var page = 1
        try {
            while (true) {
                val data = cycityData(http, "videos/$work/sections", stage,
                    listOf("player_code" to line.code, "page" to page.toString(), "page_size" to "100"))
                val rows = cycityRows(data, stage, "sections")
                result += rows.objects().mapNotNull { episode(work, it, line, edition) }
                val pager = (data as? JSONObject)?.optJSONObject("pager") ?: break
                val returnedPage = pager.optInt("page", page)
                val pageSize = pager.optInt("page_size", 100)
                val total = pager.optLong("total", rows.length().toLong())
                if (rows.length() == 0 || returnedPage.toLong() * pageSize >= total) break
                if (returnedPage != page || pageSize <= 0 || page == 100)
                    throw cycityError(stage, "次元城返回的剧集分页无法继续。")
                page++
            }
        } catch (failure: AnimeSourceException) {
            return LineResult(result, failure)
        }
        return LineResult(result)
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        val work = requireWork(catalog.title)
        require(catalog.title.sourceRevision == revision && episode in catalog.episodes)
        val selected = JSONObject(requireNotNull(episode.playbackData))
        val section = selected.getLong("section")
        val line = selected.getString("line")
        require(selected.getLong("work") == work && section > 0 && line.isNotBlank() &&
            episode.id == episodeId(work, line, section) && episode.url == episodeUrl(work, section))
        val stage = AnimeSourceStage.RESOLVE
        val http = AnimeHttp(id)
        try {
            val result = account.authorized(http) { token ->
                cycityData(http, "v2/sections/$section/play-url", stage, token = token)
            }.cycityObject(stage)
            val url = runCatching { animePublicUrl(result.text("url")).toString() }.getOrNull()
                ?: throw cycityError(stage, "次元城没有为这一集返回可读取的 HTTPS 视频地址。")
            cycityCheckRoute(http, stage)
            return AnimePlaybackAsset(
                id = "$id:${animeHash(episode.id + "\n" + animeContentIdentity(url))}",
                sourceId = id, sourceEpisodeId = episode.id, url = url, route = http.route,
                title = episode.title, headers = mapOf("Referer" to homeUrl), mimeType = animeMediaType(url),
                edition = episode.edition, sourceRevision = revision,
            )
        } finally { http.close() }
    }

    private fun card(row: JSONObject, work: Long, title: String): AnimeSourceTitle {
        val bangumi = row.optLong("bangumi_id").takeIf { it > 0 }
        return AnimeSourceTitle(id, work.toString(), title, workUrl(work),
            coverUrl = sourceCover(homeUrl, row.text("cover_url")), year = row.positiveInt("year"),
            episodeCount = row.positiveInt("total"), externalIds = bangumi?.let { mapOf("bangumi" to it.toString()) }.orEmpty(),
            updateText = row.text("remarks"), ratingText = row.text("score"), sourceRevision = revision)
    }

    private fun episode(work: Long, row: JSONObject, line: Line, edition: String): AnimeSourceEpisode? {
        val section = row.optLong("id").takeIf { it > 0 } ?: return null
        val order = row.positiveInt("order")
        val rawTitle = row.text("title")
        val label = rawTitle.ifBlank { order?.let { "第 $it 话" } ?: "剧集 $section" }
        val kind = sourceEpisodeKind(label)
        return AnimeSourceEpisode(episodeId(work, line.code, section), label, episodeUrl(work, section),
            number = sourceEpisodeNumber(rawTitle)
                ?: order?.toDouble()?.takeIf { kind == AnimeEpisodeKind.MAIN },
            kind = kind, group = "${line.title} [${line.code}]", edition = edition,
            playbackData = JSONObject().put("work", work).put("section", section).put("line", line.code).toString())
    }

    private fun requireWork(title: AnimeSourceTitle): Long {
        val work = title.id.toLongOrNull()?.takeIf { it > 0 } ?: error("次元城作品标识无效。")
        require(title.sourceId == id && title.url == workUrl(work))
        return work
    }

    private fun workUrl(work: Long) = "${homeUrl}anime/$work"
    private fun episodeUrl(work: Long, section: Long) = "${workUrl(work)}/play/$section"
    private fun episodeId(work: Long, line: String, section: Long) = "$work:${animeHash(line)}:$section"

    private data class Line(val code: String, val title: String)
    private data class LineResult(val episodes: List<AnimeSourceEpisode>, val error: AnimeSourceException? = null)
}

internal suspend fun cycityData(http: AnimeHttp, path: String, stage: AnimeSourceStage,
    query: List<Pair<String, String>> = emptyList(), method: String = "GET", payload: JSONObject? = null,
    token: String? = null): Any? {
    val url: HttpUrl = animePublicUrl(CYCITY_HOME).newBuilder().addPathSegments("api/$path").apply {
        query.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()
    val body = http.json(url, stage, method, payload, token, referer = CYCITY_HOME, sameOriginRedirects = true,
        headers = mapOf("X-App-Name" to "cyc_web", "X-App-Version" to "cycweb",
            "X-Time-Zone" to TimeZone.currentSystemDefault().id))
    val code = body.optInt("code", -1)
    if (code != 0) throw when (code) {
        401 -> cycityError(stage, if (stage == AnimeSourceStage.AUTH) "次元城账号授权失败，请核对登录信息。"
            else CYCITY_SIGN_IN_REQUIRED, code)
        1001, 1002 -> cycityError(stage, "次元城登录状态已过期，请重新登录。", 401)
        403 -> cycityError(stage, "次元城拒绝了访问，请检查账号权限和当前线路。", code)
        404 -> cycityError(stage, "次元城没有找到对应条目，请刷新目录。", code)
        429 -> cycityError(stage, "次元城请求过于频繁，请稍后重试。", code)
        else -> cycityError(stage, if (stage == AnimeSourceStage.AUTH) "次元城账号操作未完成，请核对登录信息后重试。"
            else "次元城未能完成请求，请稍后重试。")
    }
    return body.opt("data")
}

internal fun cycityCheckRoute(http: AnimeHttp, stage: AnimeSourceStage) {
    if (http.route != DownloadRoutingStore.activeSourceSnapshot(CYCITY_SOURCE_ID, DownloadCategory.ANIME))
        throw cycityError(stage, "次元城网络线路已变化，请重试。")
}

internal fun cycityError(stage: AnimeSourceStage, message: String, status: Int? = null) =
    AnimeSourceException(CYCITY_SOURCE_ID, stage, message, status)

internal fun Any?.cycityObject(stage: AnimeSourceStage): JSONObject = this as? JSONObject
    ?: throw cycityError(stage, "次元城返回的数据格式已变化，请稍后重试。")

private fun cycityRows(data: Any?, stage: AnimeSourceStage, name: String): JSONArray = when (data) {
    is JSONArray -> data
    is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("items") ?: data.optJSONArray(name)
    else -> null
} ?: throw cycityError(stage, "次元城返回的数据格式已变化，请稍后重试。")
