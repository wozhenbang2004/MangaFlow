package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeDiscoveryOrder
import dev.mangaflow.anime.AnimeDiscoveryPage
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeDiscoveryQuery
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Public catalogue queries; playback availability and canonical identity remain separate. */
class AnimeDiscoveryRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)
    private val calendarMutex = Mutex()

    suspend fun cached(query: AnimeDiscoveryQuery, page: Int = 1): AnimeDiscoveryPage? {
        validate(query, page)
        return readCache(query, page, freshOnly = false)
    }

    suspend fun browse(query: AnimeDiscoveryQuery, page: Int = 1, forceRefresh: Boolean = false): AnimeDiscoveryPage {
        validate(query, page)
        return if (query.calendar) calendarMutex.withLock {
            // Recheck the shared weekly cache after an earlier weekday request finishes.
            // Explicit refresh still bypasses that fresh copy and performs its own request.
            browsePage(query, page, forceRefresh)
        } else browsePage(query, page, forceRefresh)
    }

    private suspend fun browsePage(query: AnimeDiscoveryQuery, page: Int, forceRefresh: Boolean): AnimeDiscoveryPage {
        if (!forceRefresh) readCache(query, page, freshOnly = true)?.let { return it }
        val generation = cache.generation
        val http = AnimeHttp(providerId(query))
        return try {
            val response = when {
                query.calendar -> {
                    val response = http.request(animePublicUrl("https://api.bgm.tv/calendar"), AnimeSourceStage.METADATA)
                    val observedAt = wallTimeMillis()
                    withContext(Dispatchers.Default) {
                        JSONObject().put("fetchedAt", observedAt).put("payload", JSONArray(response.text))
                    }
                }
                query.provider == AnimeDiscoveryProvider.BANGUMI ->
                    JSONObject().put("payload", bangumi(http, query, page)).put("fetchedAt", wallTimeMillis())
                else -> JSONObject().put("payload", anilist(http, query, page)).put("fetchedAt", wallTimeMillis())
            }
            val result = withContext(Dispatchers.Default) { decode(response, query, page) }
            val encoded = withContext(Dispatchers.Default) { response.toString() }
            cache.write(cacheKey(query, page), encoded, generation)
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: AnimeSourceException) {
            throw failure
        } catch (failure: Exception) {
            throw AnimeSourceException(providerId(query), AnimeSourceStage.METADATA,
                "番剧资料没有返回完整的预期数据，请稍后重试。", cause = failure)
        } finally { http.close() }
    }

    private suspend fun readCache(query: AnimeDiscoveryQuery, page: Int, freshOnly: Boolean): AnimeDiscoveryPage? {
        val document = cache.read(cacheKey(query, page)) ?: return null
        val kind = if (query.calendar || query.year != null) ContentCacheKind.DISCOVERY else ContentCacheKind.RANKING
        if (freshOnly && !cache.isFresh(document, kind)) return null
        return try {
            withContext(Dispatchers.Default) { decode(JSONObject(document.text), query, page) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    private suspend fun bangumi(http: AnimeHttp, query: AnimeDiscoveryQuery, page: Int): JSONObject {
        val filter = JSONObject().put("type", JSONArray(listOf(2))).put("nsfw", false)
        query.tag.trim().takeIf { it.isNotEmpty() }?.let { filter.put("tag", JSONArray(listOf(it))) }
        query.year?.let { year ->
            val startMonth = query.season?.startMonth ?: 1
            val endMonth = if (query.season == null || startMonth == 10) 1 else startMonth + 3
            val endYear = if (query.season == null || startMonth == 10) year + 1 else year
            filter.put("air_date", JSONArray(listOf(
                ">=${date(year, startMonth)}", "<${date(endYear, endMonth)}")))
        }
        val sort = when (query.order) {
            AnimeDiscoveryOrder.RANK -> "rank"
            AnimeDiscoveryOrder.POPULARITY -> "heat"
            AnimeDiscoveryOrder.SCORE -> "score"
            AnimeDiscoveryOrder.TRENDING -> throw IllegalArgumentException("Bangumi 暂不提供近期趋势排序。")
        }
        return http.json(BANGUMI_API.newBuilder().addPathSegments("search/subjects")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1L) * PAGE_SIZE).toString()).build(),
            AnimeSourceStage.METADATA, "POST", JSONObject().put("keyword", "").put("sort", sort).put("filter", filter))
    }

    private suspend fun anilist(http: AnimeHttp, query: AnimeDiscoveryQuery, page: Int): JSONObject {
        val sort = when (query.order) {
            AnimeDiscoveryOrder.TRENDING -> "TRENDING_DESC"
            AnimeDiscoveryOrder.POPULARITY -> "POPULARITY_DESC"
            AnimeDiscoveryOrder.SCORE -> "SCORE_DESC"
            AnimeDiscoveryOrder.RANK -> throw IllegalArgumentException("AniList 暂不提供 Bangumi 综合名次排序。")
        }
        val variables = JSONObject().put("page", page).put("perPage", PAGE_SIZE)
            .put("sort", JSONArray(listOf(sort))).put("year", query.year).put("season", query.season?.name)
        query.tag.trim().takeIf { it.isNotEmpty() }?.let { variables.put("genre", it) }
        return http.json(ANILIST_API, AnimeSourceStage.METADATA, "POST",
            JSONObject().put("query", ANILIST_QUERY).put("variables", variables))
    }

    private fun decode(document: JSONObject, query: AnimeDiscoveryQuery, page: Int): AnimeDiscoveryPage {
        val fetchedAt = document.getLong("fetchedAt")
        require(fetchedAt > 0) { "资料观测时间缺失。" }
        if (query.calendar) {
            // One observed weekly response backs all weekday views. This is a broadcast schedule.
            val items = document.getJSONArray("payload").objects().flatMap { day ->
                val weekday = day.requiredObject("weekday", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).getInt("id")
                require(weekday in 1..7) { "番表的放送日无效。" }
                if (query.weekday != null && query.weekday != weekday) emptyList() else
                    day.requiredArray("items", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
                        .map { bangumiItem(it, weekday) }
            }.distinctBy { it.key }
            return AnimeDiscoveryPage(items, 1, false, fetchedAt)
        }
        val payload = document.getJSONObject("payload")
        return when (query.provider) {
            AnimeDiscoveryProvider.BANGUMI -> {
                val total = payload.optLong("total", -1L)
                require(total >= 0) { "Bangumi 没有返回分页总数。" }
                val offset = (page - 1L) * PAGE_SIZE
                if (payload.has("offset")) require(payload.getLong("offset") == offset) { "Bangumi 返回了不同的分页位置。" }
                val rows = if (total == 0L && payload.isNull("data")) JSONArray() else
                    payload.requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA)
                require(rows.length() > 0 || offset >= total) { "Bangumi 返回了不完整的分页。" }
                AnimeDiscoveryPage(rows.objects().map { bangumiItem(it) }, page,
                    offset + rows.length() < total, fetchedAt)
            }
            AnimeDiscoveryProvider.ANILIST -> {
                if (payload.optJSONArray("errors")?.length()?.let { it > 0 } == true)
                    throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.METADATA,
                        "AniList 未完成资料请求，请稍后重试。")
                val result = payload.requiredObject("data", ANILIST_PROVIDER, AnimeSourceStage.METADATA)
                    .requiredObject("Page", ANILIST_PROVIDER, AnimeSourceStage.METADATA)
                val pageInfo = result.requiredObject("pageInfo", ANILIST_PROVIDER, AnimeSourceStage.METADATA)
                require(pageInfo.getInt("currentPage") == page) { "AniList 返回了不同的分页位置。" }
                // AniList documents total/lastPage as unreliable; only hasNextPage decides continuation.
                AnimeDiscoveryPage(result.requiredArray("media", ANILIST_PROVIDER, AnimeSourceStage.METADATA)
                    .objects().map(::anilistItem), page, pageInfo.getBoolean("hasNextPage"), fetchedAt)
            }
        }
    }

    private fun bangumiItem(row: JSONObject, weekday: Int? = null): AnimeDiscoveryItem {
        val subject = bangumiSubject(row)?.let { if (weekday == null) it else it.copy(airWeekday = weekday) }
            ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "Bangumi 返回了缺少动画身份的条目。")
        return AnimeDiscoveryItem(key = subject.id, title = subject.title, coverUrl = subject.coverUrl,
            year = subject.year, episodeCount = subject.episodeCount, rating = subject.rating, ratingScale = 10,
            rank = subject.rank, popularity = bangumiPopularity(row), airWeekday = subject.airWeekday, subject = subject)
    }

    private fun bangumiPopularity(row: JSONObject): Long? {
        val collection = row.optJSONObject("collection") ?: return null
        var total = 0L
        // The search server's heat() is the sum of all five collection states, not only watching.
        for (key in listOf("wish", "collect", "doing", "on_hold", "dropped")) {
            val count = nonNegativeLong(collection, key) ?: return null
            if (count > Long.MAX_VALUE - total) return null
            total += count
        }
        return total
    }

    private fun anilistItem(row: JSONObject): AnimeDiscoveryItem {
        val id = row.positiveInt("id")
            ?: throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.METADATA, "AniList 条目缺少身份。")
        require(row.text("type") == "ANIME") { "AniList 返回了非动画条目。" }
        val title = row.requiredObject("title", ANILIST_PROVIDER, AnimeSourceStage.METADATA).text("native", "romaji", "english")
        require(title.isNotBlank()) { "AniList 条目缺少名称。" }
        val cover = sourceCover("https://anilist.co/", row.optJSONObject("coverImage")?.text("extraLarge", "large").orEmpty())
        val year = (row.positiveInt("seasonYear") ?: row.optJSONObject("startDate")?.positiveInt("year"))?.takeIf { it <= 9999 }
        val episodes = row.positiveInt("episodes")
        val ids = mutableMapOf("anilist" to id.toString())
        row.positiveInt("idMal")?.let { ids["myanimelist"] = it.toString() }
        val airing = row.optJSONObject("nextAiringEpisode")
        val airingAt = airing?.let { nonNegativeLong(it, "airingAt") }?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
        return AnimeDiscoveryItem(key = "anilist:$id", title = title, coverUrl = cover, year = year,
            episodeCount = episodes, rating = row.finiteNumber("averageScore")?.takeIf { it in 0.0..100.0 }, ratingScale = 100,
            popularity = nonNegativeLong(row, "popularity"), trending = nonNegativeLong(row, "trending"),
            nextEpisode = airing?.positiveInt("episode"), nextAiringAt = airingAt?.times(1000),
            reference = AnimeSourceTitle(sourceId = ANILIST_PROVIDER, id = id.toString(), title = title,
                url = "https://anilist.co/anime/$id", coverUrl = cover, year = year, episodeCount = episodes, externalIds = ids))
    }

    private fun validate(query: AnimeDiscoveryQuery, page: Int) {
        require(page > 0) { "分页位置无效。" }
        require(query.year == null || query.year in 1..9998) { "请输入有效年份。" }
        require(query.season == null || query.year != null) { "季度筛选需要同时选择年份。" }
        require(query.weekday == null || query.weekday in 1..7) { "请选择有效的放送日。" }
        if (query.calendar) {
            require(query.provider == AnimeDiscoveryProvider.BANGUMI && page == 1) { "每日番表仅支持 Bangumi 的完整周表。" }
            require(query.order == AnimeDiscoveryOrder.POPULARITY && query.year == null && query.season == null && query.tag.isBlank()) {
                "每日番表只支持放送日筛选，排行、年份和分类请在对应页面选择。"
            }
        } else {
            require(query.weekday == null) { "放送日筛选只适用于每日番表。" }
            when (query.provider) {
                AnimeDiscoveryProvider.BANGUMI -> {
                    require((page - 1L) * PAGE_SIZE <= Int.MAX_VALUE) { "分页位置无效。" }
                    require(query.order != AnimeDiscoveryOrder.TRENDING) { "Bangumi 暂不提供近期趋势排序。" }
                }
                AnimeDiscoveryProvider.ANILIST -> {
                    require(query.order != AnimeDiscoveryOrder.RANK) { "AniList 暂不提供 Bangumi 综合名次排序。" }
                    require(query.tag.isBlank() || query.tag.trim() in ANILIST_GENRES) { "请选择 AniList 支持的分类。" }
                }
            }
        }
    }

    private fun cacheKey(query: AnimeDiscoveryQuery, page: Int): String = if (query.calendar) "discovery:v1:bangumi:calendar" else
        "discovery:v1:" + JSONObject().put("provider", query.provider.name).put("order", query.order.name)
            .put("year", query.year).put("season", query.season?.name).put("tag", query.tag.trim()).put("page", page)

    private fun providerId(query: AnimeDiscoveryQuery): String = when (query.provider) {
        AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
        AnimeDiscoveryProvider.ANILIST -> ANILIST_PROVIDER
    }

    private fun date(year: Int, month: Int): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-01"
    private fun nonNegativeLong(json: JSONObject, key: String): Long? = json.optLong(key, -1L).takeIf { it >= 0 }

    private companion object {
        const val PAGE_SIZE = 24
        val ANILIST_GENRES = setOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Hentai", "Horror",
            "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life",
            "Sports", "Supernatural", "Thriller")
        val ANILIST_QUERY = """
            query Discovery(${'$'}page: Int!, ${'$'}perPage: Int!, ${'$'}sort: [MediaSort], ${'$'}year: Int, ${'$'}season: MediaSeason, ${'$'}genre: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage }
                media(type: ANIME, isAdult: false, sort: ${'$'}sort, seasonYear: ${'$'}year, season: ${'$'}season, genre: ${'$'}genre) {
                  id idMal type title { native romaji english } coverImage { extraLarge large }
                  seasonYear startDate { year } episodes averageScore popularity trending
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
    }
}
