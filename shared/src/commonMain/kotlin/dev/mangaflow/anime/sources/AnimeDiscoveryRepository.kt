package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeDiscoveryOrder
import dev.mangaflow.anime.AnimeDiscoveryPage
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeDiscoveryQuery
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.combineAnimeDiscoveryItems
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.NetworkTransferException
import dev.mangaflow.net.identity
import dev.mangaflow.net.networkFailureKind
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AnimeAniListMatch(val id: Int, val malId: Int?, val rating: Double?, val ratingCount: Int? = null)

/** Public catalogue queries; playback availability and canonical identity remain separate. */
class AnimeDiscoveryRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)
    private val douban = DoubanAnimeRepository(context)
    private val calendarMutex = Mutex()

    internal fun route(query: AnimeDiscoveryQuery): DownloadRouteSnapshot =
        DownloadRoutingStore.activeSourceSnapshot(providerId(query), DownloadCategory.ANIME)

    internal fun isFresh(query: AnimeDiscoveryQuery, fetchedAt: Long): Boolean =
        cache.isFresh(fetchedAt, cacheKind(query))

    internal suspend fun lookupAniListScore(subject: AnimeSubject): AnimeSourceScore? =
        lookupAniListMatch(subject)?.let { match -> match.rating?.let {
            AnimeSourceScore(AnimeDiscoveryProvider.ANILIST, it, 100, match.ratingCount)
        } }

    /** Resolve the same exact or unique season identity used by the detail's AniList rating. */
    internal suspend fun lookupAniListMatch(subject: AnimeSubject, forceRefresh: Boolean = false,
        reportFailure: Boolean = false): AnimeAniListMatch? {
        if (!subject.id.startsWith("bangumi:")) return null
        val ids = subject.externalIds
        val aniListId = ids["anilist"]?.toIntOrNull()?.takeIf { it > 0 }
        val malIds = listOfNotNull(ids["myanimelist"], ids["mal"]).mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }
            .distinct()
        if (malIds.size > 1) return null
        val malId = malIds.singleOrNull()
        val year = subject.year?.takeIf { it in 1..9998 }
        val title = subject.originalTitle.ifBlank { subject.title }.trim()
        val byId = aniListId != null || malId != null
        if (!byId && (year == null || title.length !in 1..180)) return null
        val query = AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.ANILIST)
        return try {
            val route = route(query)
            val identity = JSONObject().put("subject", subject.id).put("anilist", aniListId).put("mal", malId)
                .put("title", subject.title).put("originalTitle", subject.originalTitle).put("year", year)
                .put("episodes", subject.episodeCount)
            val key = "anilist-match:v2:${identity}:${animeHash(route.identity())}"
            val cached = cache.read(key)?.takeIf { !forceRefresh && cache.isFresh(it, ContentCacheKind.DETAILS) }
                ?.let { runCatching { JSONObject(it.text) }.getOrNull() }
            cached?.positiveInt("id")?.let { id ->
                checkRoute(query, route)
                return AnimeAniListMatch(id, cached.positiveInt("idMal"), cached.finiteNumber("score"),
                    nonNegativeLong(cached, "ratingCount")?.takeIf { it <= Int.MAX_VALUE }?.toInt())
            }
            val generation = cache.generation
            val variables = JSONObject().put("id", aniListId).put("idMal", malId)
            if (!byId && year != null) variables.put("search", title).putAniListReleaseYear(year)
            checkRoute(query, route)
            val response = withTimeoutOrNull(ANILIST_SCORE_TIMEOUT_MILLIS) {
                val http = AnimeHttp(ANILIST_PROVIDER, route)
                try {
                    val payload = http.json(ANILIST_API, AnimeSourceStage.METADATA, "POST",
                        JSONObject().put("query", ANILIST_SCORE_QUERY).put("variables", variables))
                    check(payload.optJSONArray("errors")?.length()?.let { it > 0 } != true) {
                        "AniList 暂时无法返回作品资料，请重试。"
                    }
                    val result = withContext(Dispatchers.Default) {
                        val candidates = aniListFirstPage(payload).orEmpty().filter { item ->
                            val externalIds = item.reference?.externalIds.orEmpty()
                            (aniListId == null || externalIds["anilist"] == aniListId.toString()) &&
                                (malId == null || externalIds["myanimelist"] == malId.toString())
                        }
                        val item = if (byId) candidates.singleOrNull() else {
                            val work = AnimeDiscoveryItem(key = subject.id, title = subject.title, year = year,
                                episodeCount = subject.episodeCount, subject = subject)
                            combineAnimeDiscoveryItems(listOf(work), candidates).firstOrNull { it.subject?.id == subject.id }
                                ?.reference?.let { reference -> candidates.singleOrNull { it.reference == reference } }
                        }
                        val ids = item?.reference?.externalIds.orEmpty()
                        ids["anilist"]?.toIntOrNull()?.let { id ->
                            val row = payload.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
                                .objects().singleOrNull { it.positiveInt("id") == id }
                            AnimeAniListMatch(id, ids["myanimelist"]?.toIntOrNull(),
                                item?.rating?.takeIf { it in 1.0..100.0 }, row?.let(::aniListRatingCount))
                        }
                    }
                    // A successful response without a unique identity differs from a timeout.
                    Result.success(result)
                } finally { http.close() }
            } ?: error("等待读取 AniList 作品资料超时。")
            checkRoute(query, route)
            val match = response.getOrThrow() ?: return null
            cache.write(key, JSONObject().put("id", match.id).put("idMal", match.malId)
                .put("score", match.rating).put("ratingCount", match.ratingCount).toString(), generation)
            checkRoute(query, route)
            match
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (reportFailure) throw failure
            null
        }
    }

    private fun aniListFirstPage(payload: JSONObject): List<AnimeDiscoveryItem>? {
        if (payload.optJSONArray("errors")?.length()?.let { it > 0 } == true) return null
        val page = payload.getJSONObject("data").getJSONObject("Page")
        val info = page.getJSONObject("pageInfo")
        check(info.getInt("currentPage") == 1) { "AniList 返回了不一致的作品分页。" }
        if (info.getBoolean("hasNextPage")) return null
        val rows = page.getJSONArray("media")
        check(rows.length() <= ANILIST_SCORE_LIMIT) { "AniList 返回的作品数量超出请求范围。" }
        return (0 until rows.length()).map { anilistItem(rows.getJSONObject(it)) }
    }

    suspend fun cached(
        query: AnimeDiscoveryQuery, page: Int = 1,
        route: DownloadRouteSnapshot? = runCatching { route(query) }.getOrNull(),
    ): AnimeDiscoveryPage? {
        validate(query, page)
        if (query.provider == AnimeDiscoveryProvider.DOUBAN) return douban.cachedBrowse(query, page, route)
        // Earlier versions did not record the route. Keep their displayable snapshots,
        // but revalidate them before treating a query as fresh on the current route.
        return route?.let { readCache(query, page, cacheKey(query, page, it), freshOnly = false) }
            ?: readCache(query, page, cacheKey(query, page, null), freshOnly = false)
    }

    suspend fun browse(
        query: AnimeDiscoveryQuery, page: Int = 1, forceRefresh: Boolean = false,
        route: DownloadRouteSnapshot = route(query),
    ): AnimeDiscoveryPage {
        validate(query, page)
        if (query.provider == AnimeDiscoveryProvider.DOUBAN) return douban.browse(query, page, forceRefresh, route)
        return if (query.calendar) calendarMutex.withLock {
            // Recheck the shared weekly cache after an earlier weekday request finishes.
            // Explicit refresh still bypasses that fresh copy and performs its own request.
            browsePage(query, page, forceRefresh, route)
        } else browsePage(query, page, forceRefresh, route)
    }

    private suspend fun browsePage(
        query: AnimeDiscoveryQuery, page: Int, forceRefresh: Boolean, route: DownloadRouteSnapshot,
    ): AnimeDiscoveryPage {
        checkRoute(query, route)
        val key = cacheKey(query, page, route)
        if (!forceRefresh) {
            readCache(query, page, key, freshOnly = true)?.let {
                checkRoute(query, route)
                return it
            }
        }
        val generation = cache.generation
        val http = AnimeHttp(providerId(query), route)
        return try {
            suspend fun read(): JSONObject {
                checkRoute(query, route)
                return withTimeoutOrNull(READ_TIMEOUT_MILLIS) {
                    when {
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
                } ?: throw AnimeSourceException(providerId(query), AnimeSourceStage.METADATA,
                    "等待番剧资料超时，请稍后重试。",
                    cause = NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT, "等待番剧资料超时"))
            }
            val response = try { read() } catch (failure: AnimeSourceException) {
                // Discovery POSTs are read-only API queries. Never replay writes, schema
                // failures, access verification, or a server cooldown too long for this fetch.
                if (networkFailureKind(failure) == NetworkFailureKind.NO_NETWORK) throw failure
                val wait = discoveryRetryDelay(failure, transientDelay = 450L)
                    ?.takeIf { it <= INLINE_RETRY_BUDGET_MILLIS } ?: throw failure
                delay(wait)
                read()
            }
            val result = withContext(Dispatchers.Default) { decode(response, query, page) }
            val encoded = withContext(Dispatchers.Default) { response.toString() }
            checkRoute(query, route)
            cache.write(key, encoded, generation)
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: DownloadRouteException) {
            throw failure
        } catch (failure: AnimeSourceException) {
            throw failure
        } catch (failure: Exception) {
            throw AnimeSourceException(providerId(query), AnimeSourceStage.METADATA,
                "番剧资料没有返回完整的预期数据，请稍后重试。", cause = failure)
        } finally { http.close() }
    }

    private suspend fun readCache(
        query: AnimeDiscoveryQuery, page: Int, key: String, freshOnly: Boolean,
    ): AnimeDiscoveryPage? {
        val document = cache.read(key) ?: return null
        if (freshOnly && !cache.isFresh(document, cacheKind(query))) return null
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
            AnimeDiscoveryOrder.NEWEST -> throw IllegalArgumentException("Bangumi 此目录暂不提供最新排序。")
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
            AnimeDiscoveryOrder.NEWEST -> throw IllegalArgumentException("AniList 此目录暂不提供最新排序。")
        }
        val variables = JSONObject().put("page", page).put("perPage", PAGE_SIZE)
            .put("sort", JSONArray(listOf(sort)))
        if (query.season != null) variables.put("year", query.year).put("season", query.season.name)
        else query.year?.let { variables.putAniListReleaseYear(it) }
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
            AnimeDiscoveryProvider.DOUBAN -> error("豆瓣目录由独立的真实身份和缓存合同读取。")
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
            rank = subject.rank, popularity = bangumiPopularity(row), airWeekday = subject.airWeekday, subject = subject,
            format = when (row.text("platform")) {
                "TV" -> AnimeDiscoveryFormat.SERIES
                "剧场版" -> AnimeDiscoveryFormat.MOVIE
                "OVA" -> AnimeDiscoveryFormat.SPECIAL
                else -> null
            })
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
        val names = row.requiredObject("title", ANILIST_PROVIDER, AnimeSourceStage.METADATA)
        val titles = listOf("native", "romaji", "english").map { names.text(it) }.filter(String::isNotBlank).distinct()
        require(titles.isNotEmpty()) { "AniList 条目缺少名称。" }
        val title = titles.first()
        val cover = sourceCover("https://anilist.co/", row.optJSONObject("coverImage")?.text("extraLarge", "large").orEmpty())
        val year = (row.optJSONObject("startDate")?.positiveInt("year") ?: row.positiveInt("seasonYear"))?.takeIf { it <= 9999 }
        val episodes = row.positiveInt("episodes")
        val ids = mutableMapOf("anilist" to id.toString())
        row.positiveInt("idMal")?.let { ids["myanimelist"] = it.toString() }
        val airing = row.optJSONObject("nextAiringEpisode")
        val airingAt = airing?.let { nonNegativeLong(it, "airingAt") }?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
        return AnimeDiscoveryItem(key = "anilist:$id", title = title, coverUrl = cover, year = year,
            episodeCount = episodes, rating = row.finiteNumber("averageScore")?.takeIf { it in 0.0..100.0 }, ratingScale = 100,
            popularity = nonNegativeLong(row, "popularity"), trending = nonNegativeLong(row, "trending"),
            nextEpisode = airing?.positiveInt("episode"), nextAiringAt = airingAt?.times(1000),
            aliases = titles.drop(1),
            format = when (row.text("format")) {
                "TV" -> AnimeDiscoveryFormat.SERIES
                "MOVIE" -> AnimeDiscoveryFormat.MOVIE
                "SPECIAL", "OVA" -> AnimeDiscoveryFormat.SPECIAL
                "TV_SHORT" -> AnimeDiscoveryFormat.SHORT
                else -> null
            },
            reference = AnimeSourceTitle(sourceId = ANILIST_PROVIDER, id = id.toString(), title = title,
                url = "https://anilist.co/anime/$id", coverUrl = cover, year = year, episodeCount = episodes, externalIds = ids))
    }

    private fun validate(query: AnimeDiscoveryQuery, page: Int) {
        if (query.provider == AnimeDiscoveryProvider.DOUBAN) {
            douban.validateBrowse(query, page)
            return
        }
        require(page > 0) { "分页位置无效。" }
        require(query.region.isBlank() && query.format == null) { "地区和作品形式筛选目前适用于豆瓣动画目录。" }
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
                AnimeDiscoveryProvider.DOUBAN -> error("豆瓣目录已经完成筛选校验。")
                AnimeDiscoveryProvider.BANGUMI -> {
                    require((page - 1L) * PAGE_SIZE <= Int.MAX_VALUE) { "分页位置无效。" }
                    require(query.order != AnimeDiscoveryOrder.TRENDING) { "Bangumi 暂不提供近期趋势排序。" }
                    require(query.order != AnimeDiscoveryOrder.NEWEST) { "Bangumi 此目录暂不提供最新排序。" }
                }
                AnimeDiscoveryProvider.ANILIST -> {
                    require(query.order != AnimeDiscoveryOrder.RANK) { "AniList 暂不提供 Bangumi 综合名次排序。" }
                    require(query.order != AnimeDiscoveryOrder.NEWEST) { "AniList 此目录暂不提供最新排序。" }
                    require(query.tag.isBlank() || query.tag.trim() in ANILIST_GENRES) { "请选择 AniList 支持的分类。" }
                }
            }
        }
    }

    private fun cacheKind(query: AnimeDiscoveryQuery): ContentCacheKind =
        if (query.calendar || query.year != null) ContentCacheKind.DISCOVERY else ContentCacheKind.RANKING

    private fun cacheKey(query: AnimeDiscoveryQuery, page: Int, route: DownloadRouteSnapshot?): String {
        val identity = if (query.calendar) "bangumi:calendar" else
            JSONObject().put("provider", query.provider.name).put("order", query.order.name)
                .put("year", query.year).put("season", query.season?.name).put("tag", query.tag.trim()).put("page", page)
                .put("releaseYear", query.year.takeIf { query.provider == AnimeDiscoveryProvider.ANILIST && query.season == null })
                .toString()
        return if (route == null) "discovery:v1:$identity" else "discovery:v2:$identity:${animeHash(route.identity())}"
    }

    private fun checkRoute(query: AnimeDiscoveryQuery, requested: DownloadRouteSnapshot) {
        if (route(query) != requested) throw DownloadRouteException("番剧资料的网络线路已更改，请按当前线路重新读取。")
    }

    private fun providerId(query: AnimeDiscoveryQuery): String = when (query.provider) {
        AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
        AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
        AnimeDiscoveryProvider.ANILIST -> ANILIST_PROVIDER
    }

    private fun date(year: Int, month: Int): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-01"
    private fun nonNegativeLong(json: JSONObject, key: String): Long? = json.optLong(key, -1L).takeIf { it >= 0 }

    /** AniList popularity counts collections; only its score distribution counts ratings. */
    private fun aniListRatingCount(row: JSONObject): Int? {
        val amounts = row.optJSONObject("stats")?.optJSONArray("scoreDistribution")?.objects() ?: return null
        var count = 0L
        for (amount in amounts) {
            val value = nonNegativeLong(amount, "amount") ?: return null
            if (value > Int.MAX_VALUE.toLong() - count) return null
            count += value
        }
        return count.toInt()
    }

    private fun JSONObject.putAniListReleaseYear(year: Int): JSONObject =
        put("from", (year - 1) * 10_000 + 1_231).put("until", (year + 1) * 10_000)

    private companion object {
        const val PAGE_SIZE = 24
        const val READ_TIMEOUT_MILLIS = 8_000L
        const val INLINE_RETRY_BUDGET_MILLIS = 4_000L
        const val ANILIST_SCORE_LIMIT = 20
        const val ANILIST_SCORE_TIMEOUT_MILLIS = 4_000L
        val ANILIST_GENRES = setOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Hentai", "Horror",
            "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life",
            "Sports", "Supernatural", "Thriller")
        val ANILIST_QUERY = """
            query Discovery(${'$'}page: Int!, ${'$'}perPage: Int!, ${'$'}sort: [MediaSort], ${'$'}year: Int,
              ${'$'}season: MediaSeason, ${'$'}genre: String, ${'$'}from: FuzzyDateInt, ${'$'}until: FuzzyDateInt) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage }
                media(type: ANIME, isAdult: false, sort: ${'$'}sort, seasonYear: ${'$'}year, season: ${'$'}season,
                  genre: ${'$'}genre, startDate_greater: ${'$'}from, startDate_lesser: ${'$'}until) {
                  id idMal type format title { native romaji english } coverImage { extraLarge large }
                  seasonYear startDate { year } episodes averageScore popularity trending
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
        val ANILIST_SCORE_QUERY = """
            query AnimeScore(${'$'}id: Int, ${'$'}idMal: Int, ${'$'}search: String, ${'$'}from: FuzzyDateInt, ${'$'}until: FuzzyDateInt) {
              Page(page: 1, perPage: $ANILIST_SCORE_LIMIT) {
                pageInfo { currentPage hasNextPage }
                media(type: ANIME, isAdult: false, id: ${'$'}id, idMal: ${'$'}idMal, search: ${'$'}search,
                  startDate_greater: ${'$'}from, startDate_lesser: ${'$'}until) {
                  id idMal type format title { native romaji english }
                  seasonYear startDate { year } episodes averageScore
                  stats { scoreDistribution { amount } }
                }
              }
            }
        """.trimIndent()
    }
}

/** Backoff for the original read, never authorization, route setup, or invalid response data. */
internal fun discoveryRetryDelay(failure: Exception, transientDelay: Long = 30_000L): Long? {
    val error = failure as? AnimeSourceException ?: return null
    if (error.retryAfterSeconds > 0) return error.retryAfterSeconds.coerceAtMost(86_400L) * 1_000L
    when (error.httpStatus) {
        408, 429, 500, 502, 503, 504 -> return transientDelay
        null -> Unit
        else -> return null
    }
    return when (networkFailureKind(error)) {
        NetworkFailureKind.NO_NETWORK, NetworkFailureKind.DNS, NetworkFailureKind.CONNECTION, NetworkFailureKind.CONNECT_TIMEOUT,
        NetworkFailureKind.RESPONSE_TIMEOUT, NetworkFailureKind.TRANSFER_TIMEOUT, NetworkFailureKind.REQUEST_TIMEOUT -> transientDelay
        else -> null
    }
}
