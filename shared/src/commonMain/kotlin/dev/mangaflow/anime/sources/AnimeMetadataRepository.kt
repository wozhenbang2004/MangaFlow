package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeBrowseMode
import dev.mangaflow.anime.AnimeDocumentCodec
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Official Bangumi v0 contract: bangumi/api/open-api/v0.yaml, read 2026-11-16. */
data class AnimeMetadataPage(val items: List<AnimeSubject>, val page: Int, val hasMore: Boolean)
data class AnimeMetadataDetailResult(
    val subject: AnimeSubject?,
    val episodes: List<AnimeEpisode>?,
    val subjectError: String? = null,
    val episodeError: String? = null,
)
private class AnimeMetadataIdentityException(message: String) : IllegalStateException(message)

class AnimeMetadataRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)

    suspend fun discover(): List<AnimeSubject> = browse().items
    suspend fun search(query: String): List<AnimeSubject> = browse(query = query).items

    suspend fun cachedBrowse(mode: AnimeBrowseMode, query: String, page: Int, year: Int?, tag: String,
        weekday: Int?): AnimeMetadataPage? = cache.read(browseKey(mode, query, page, year, tag, weekday))?.let {
            withContext(Dispatchers.Default) { runCatching { decodePage(it.text) }.getOrNull() }
        }

    suspend fun browse(mode: AnimeBrowseMode = AnimeBrowseMode.POPULAR, query: String = "", page: Int = 1,
        year: Int? = null, tag: String = "", weekday: Int? = null, forceRefresh: Boolean = false): AnimeMetadataPage = withContext(Dispatchers.Default) {
        require(page > 0 && (page - 1L) * PAGE_SIZE <= Int.MAX_VALUE) { "分页位置无效。" }
        require(year == null || year in 1..9998) { "请输入有效年份。" }
        require(weekday == null || weekday in 1..7)
        val key = browseKey(mode, query, page, year, tag, weekday)
        val kind = when {
            query.isNotBlank() -> ContentCacheKind.SEARCH
            mode == AnimeBrowseMode.POPULAR -> ContentCacheKind.RANKING
            else -> ContentCacheKind.DISCOVERY
        }
        cache.read(key)?.takeIf { !forceRefresh && cache.isFresh(it, kind) }?.let {
            runCatching { decodePage(it.text) }.getOrNull()?.let { cached -> return@withContext cached }
        }
        val cacheGeneration = cache.generation
        val http = AnimeHttp(BANGUMI_PROVIDER)
        try {
            val result = if (mode == AnimeBrowseMode.CALENDAR && query.isBlank()) {
                require(tag.isBlank()) { "每日番表暂不支持标签筛选，请切换排行或新番。" }
                val rows = JSONArray(http.request(animePublicUrl("https://api.bgm.tv/calendar"), AnimeSourceStage.METADATA).text)
                val items = rows.objects().flatMap { day ->
                    val dayId = day.optJSONObject("weekday")?.optInt("id")
                    if (weekday != null && weekday != dayId) emptyList() else
                        day.requiredArray("items", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
                            .mapNotNull(::bangumiSubject).map { it.copy(airWeekday = dayId?.takeIf { value -> value in 1..7 }) }
                }.filter { year == null || it.year == year }.distinctBy { it.id }
                AnimeMetadataPage(items, 1, false)
            } else {
                val offset = (page - 1L) * PAGE_SIZE
                val document = if (query.isNotBlank() || tag.isNotBlank()) {
                    val filter = JSONObject().put("type", JSONArray(listOf(2)))
                    if (tag.isNotBlank()) filter.put("tag", JSONArray(listOf(tag.trim())))
                    if (year != null) filter.put("air_date", JSONArray(listOf(">=${year.toString().padStart(4, '0')}-01-01",
                        "<${(year + 1).toString().padStart(4, '0')}-01-01")))
                    http.json(BANGUMI_API.newBuilder().addPathSegments("search/subjects")
                        .addQueryParameter("limit", PAGE_SIZE.toString()).addQueryParameter("offset", offset.toString()).build(),
                        AnimeSourceStage.METADATA, "POST", JSONObject().put("keyword", query.trim())
                            .put("sort", if (query.isNotBlank()) "match" else "rank").put("filter", filter))
                } else {
                    val url = BANGUMI_API.newBuilder().addPathSegment("subjects")
                        .addQueryParameter("type", "2").addQueryParameter("sort", if (mode == AnimeBrowseMode.NEWEST) "date" else "rank")
                        .addQueryParameter("limit", PAGE_SIZE.toString()).addQueryParameter("offset", offset.toString())
                    year?.let { url.addQueryParameter("year", it.toString()) }
                    http.json(url.build(), AnimeSourceStage.METADATA)
                }
                val rows = document.requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
                val total = document.optLong("total", -1L)
                AnimeMetadataPage(rows.mapNotNull(::bangumiSubject), page,
                    rows.isNotEmpty() && if (total >= 0) offset + rows.size < total else rows.size >= PAGE_SIZE)
            }
            cache.write(key, encodePage(result), cacheGeneration)
            result
        } finally { http.close() }
    }

    suspend fun cachedDetail(subject: AnimeSubject): AnimeDetail? = cache.read("detail:${subject.id}")?.let {
        withContext(Dispatchers.Default) { runCatching { AnimeDocumentCodec.detail(JSONObject(it.text)) }.getOrNull() }?.takeIf { detail -> detail.subject.id == subject.id }
    }

    suspend fun detail(subject: AnimeSubject): AnimeMetadataDetailResult = withContext(Dispatchers.Default) {
        require(subject.id.startsWith("bangumi:")) { "请选择有效的番剧条目。" }
        val id = animeNumericId(subject.id.removePrefix("bangumi:"))
        val key = "detail:${subject.id}"
        cache.read(key)?.takeIf { cache.isFresh(it, ContentCacheKind.DETAILS) }?.let {
            runCatching { AnimeDocumentCodec.detail(JSONObject(it.text)) }.getOrNull()
                ?.takeIf { detail -> detail.subject.id == subject.id }?.let { detail ->
                    return@withContext AnimeMetadataDetailResult(detail.subject, detail.episodes)
                }
        }
        val cacheGeneration = cache.generation
        val http = AnimeHttp(BANGUMI_PROVIDER)
        try {
            // Both reads have exact subject ownership, but separate failure boundaries. A
            // metadata failure must not cancel/discard a successful episode catalogue (or vice versa).
            val (canonical, episodes) = coroutineScope {
                val metadata = async { metadataRead {
                    val result = bangumiSubject(http.json(BANGUMI_API.newBuilder().addPathSegment("subjects")
                        .addPathSegment(id.toString()).build(), AnimeSourceStage.METADATA))
                        ?: throw AnimeMetadataIdentityException("该条目不是动画，请重新选择正确的番剧或季度。")
                    if (result.id != subject.id)
                        throw AnimeMetadataIdentityException("返回的番剧身份与请求不一致，请重新选择条目。")
                    result
                } }
                val catalogue = async { metadataRead {
                    bangumiEpisodes(http, id).map { episode ->
                        val number = episode.finiteNumber("sort")
                            ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "番剧目录缺少剧集编号，暂时无法建立准确映射。")
                        val episodeId = episode.positiveInt("id")
                            ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "番剧目录缺少剧集身份，请刷新后重试。")
                        AnimeEpisode(
                            id = "bangumi:$episodeId", subjectId = subject.id,
                            title = episode.text("name_cn", "name").ifBlank { "第 ${animeEpisodeLabel(number)} 集" },
                            number = number, kind = bangumiEpisodeKind(episode.optInt("type", -1)),
                            durationMs = bangumiDuration(episode.text("duration")),
                            airDate = episode.text("airdate").takeIf { it.isNotBlank() && it != "0000-00-00" },
                        )
                    }.sortedWith(compareBy<AnimeEpisode> { it.kind.ordinal }.thenBy { it.number }.thenBy { it.id })
                } }
                metadata.await() to catalogue.await()
            }
            if (canonical.isSuccess && episodes.isSuccess) {
                val complete = AnimeDetail(canonical.getOrThrow(), episodes.getOrThrow())
                cache.write(key, AnimeDocumentCodec.detail(complete).toString(), cacheGeneration)
            }
            // Partial responses never replace the complete persisted snapshot or its freshness.
            AnimeMetadataDetailResult(canonical.getOrNull(), episodes.getOrNull(),
                canonical.exceptionOrNull()?.let { (it as? AnimeSourceException)?.message ?: "作品资料未能读取。" },
                episodes.exceptionOrNull()?.let { (it as? AnimeSourceException)?.message ?: "集数目录未能读取。" })
        } finally { http.close() }
    }

    private suspend fun <T> metadataRead(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: AnimeMetadataIdentityException) { throw failure }
    catch (failure: Exception) { Result.failure(failure) }

    suspend fun related(subject: AnimeSubject): List<AnimeSubject> = withContext(Dispatchers.Default) {
        require(subject.id.startsWith("bangumi:")) { "请选择有效的番剧条目。" }
        val id = animeNumericId(subject.id.removePrefix("bangumi:"))
        val http = AnimeHttp(BANGUMI_PROVIDER)
        try {
            JSONArray(http.request(BANGUMI_API.newBuilder().addPathSegment("subjects").addPathSegment(id.toString())
                .addPathSegment("subjects").build(), AnimeSourceStage.METADATA).text).objects()
                .mapNotNull(::bangumiSubject).filterNot { it.id == subject.id }.distinctBy { it.id }
        } finally { http.close() }
    }

    private fun browseKey(mode: AnimeBrowseMode, query: String, page: Int, year: Int?, tag: String, weekday: Int?) =
        JSONObject().put("mode", mode.name).put("query", query.trim()).put("page", page)
            .put("year", year).put("tag", tag.trim()).put("weekday", weekday).toString()
    private fun encodePage(page: AnimeMetadataPage) = JSONObject().put("page", page.page)
        .put("hasMore", page.hasMore).put("items", JSONArray(page.items.map { AnimeDocumentCodec.subject(it) })).toString()
    private fun decodePage(text: String): AnimeMetadataPage {
        val json = JSONObject(text)
        return AnimeMetadataPage(json.getJSONArray("items").objects().map { AnimeDocumentCodec.subject(it) },
            json.getInt("page"), json.getBoolean("hasMore"))
    }
    private companion object { const val PAGE_SIZE = 24 }
}

internal const val BANGUMI_PROVIDER = "anime-bangumi"
internal const val ANILIST_PROVIDER = "anime-anilist"
internal val BANGUMI_API = animePublicUrl("https://api.bgm.tv/v0/")
internal val ANILIST_API = animePublicUrl("https://graphql.anilist.co/")

internal fun bangumiSubject(json: JSONObject): AnimeSubject? {
    if (json.optInt("type") != 2) return null
    val id = json.positiveInt("id") ?: return null
    val name = json.text("name_cn", "name")
    if (name.isBlank()) return null
    val images = json.optJSONObject("images")
    return AnimeSubject(
        id = "bangumi:$id", title = name, originalTitle = json.text("name"),
        coverUrl = bangumiCover(images),
        description = Ksoup.parse(json.text("summary")).text(),
        episodeCount = json.positiveInt("eps") ?: json.positiveInt("eps_count"),
        year = json.text("date", "air_date").take(4).toIntOrNull()?.takeIf { it in 1..9999 },
        tags = json.optJSONArray("tags")?.objects().orEmpty().map { it.text("name") }.filter { it.isNotBlank() }.distinct(),
        externalIds = mapOf("bangumi" to id.toString()),
        airWeekday = json.optInt("air_weekday").takeIf { it in 1..7 },
        rating = json.optJSONObject("rating")?.finiteNumber("score"),
        rank = json.optJSONObject("rating")?.positiveInt("rank") ?: json.positiveInt("rank"),
    )
}

/** The legacy calendar API still advertises HTTP URLs for the HTTPS-capable official CDN.
 * Normalize only that known origin; never relax the shared public-source HTTPS boundary.
 */
private fun bangumiCover(images: JSONObject?): String =
    listOf("large", "common", "medium", "small", "grid").firstNotNullOfOrNull { size ->
        val raw = images?.text(size).orEmpty()
        val url = raw.toHttpUrlOrNull()
        val normalized = if (url != null && url.scheme == "http" && url.host == "lain.bgm.tv" &&
            url.port == 80 && url.username.isEmpty() && url.password.isEmpty())
            url.newBuilder().scheme("https").port(443).build().toString() else raw
        sourceCover("https://bgm.tv/", normalized).takeIf { it.isNotBlank() }
    }.orEmpty()

internal suspend fun bangumiEpisodes(http: AnimeHttp, subjectId: Int, token: String? = null): List<JSONObject> {
    val output = mutableListOf<JSONObject>()
    val identities = mutableSetOf<Int>()
    var offset = 0
    while (true) {
        val data = http.json(BANGUMI_API.newBuilder().addPathSegment("episodes")
            .addQueryParameter("subject_id", subjectId.toString()).addQueryParameter("limit", "200")
            .addQueryParameter("offset", offset.toString()).build(), AnimeSourceStage.METADATA, token = token)
        val rows = if (data.optInt("total", -1) == 0 && data.isNull("data")) emptyList()
            else data.requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
        val total = data.optInt("total", -1)
        if (rows.isEmpty()) {
            if (total > offset) throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录分页不完整，请刷新后重试。")
            break
        }
        for (row in rows) {
            val id = row.positiveInt("id")
                ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录缺少剧集身份。")
            if (row.optInt("subject_id", subjectId) != subjectId || !identities.add(id))
                throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录在读取过程中发生变化，请刷新后重试。")
            output += row
        }
        offset += rows.size
        if (total >= 0 && offset >= total) break
        if (total < 0 && rows.size < 200) break
    }
    return output
}

internal fun bangumiEpisodeKind(type: Int): AnimeEpisodeKind = when (type) {
    0 -> AnimeEpisodeKind.MAIN
    1 -> AnimeEpisodeKind.SPECIAL
    2 -> AnimeEpisodeKind.OPENING
    3 -> AnimeEpisodeKind.ENDING
    4 -> AnimeEpisodeKind.TRAILER
    else -> AnimeEpisodeKind.OTHER
}

private fun bangumiDuration(text: String): Long? {
    val parts = text.split(':')
    if (parts.size !in 2..3) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.any { it < 0 } || values.drop(1).any { it >= 60 }) return null
    return (if (values.size == 3) values[0] * 3600 + values[1] * 60 + values[2]
        else values[0] * 60 + values[1]).takeIf { it in 1..86_400 }?.times(1_000L)
}
