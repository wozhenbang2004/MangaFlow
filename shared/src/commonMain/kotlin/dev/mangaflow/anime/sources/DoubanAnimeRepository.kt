package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeDiscoveryOrder
import dev.mangaflow.anime.AnimeDiscoveryPage
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeDiscoveryQuery
import dev.mangaflow.anime.AnimeDocumentCodec
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.RelayLinkController
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.NetworkTransferException
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DOUBAN_PROVIDER = "anime-douban"
internal const val DOUBAN_BROWSE_PAGE_SIZE = 20

/** A Douban subject owns one main view; playable sources are matched by the existing source layer. */
class DoubanAnimeRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)
    private val relay = RelayLinkController.get(context)
    private val cursorMutex = Mutex()
    // Pagination state remains available when the user disables the disposable metadata cache.
    private val cursors = LinkedHashMap<String, SearchCursor>()

    internal fun route(): DownloadRouteSnapshot =
        DownloadRoutingStore.activeSourceSnapshot(DOUBAN_PROVIDER, DownloadCategory.ANIME)

    internal fun browseRoute(): DownloadRouteSnapshot = relay.desktopCatalogueRoute() ?: route()

    suspend fun cachedSearch(query: String, page: Int): AnimeMetadataPage? = withContext(Dispatchers.Default) {
        val keyword = validateSearch(query, page)
        val requestedRoute = route()
        val generation = cache.generation
        val record = readSearch(searchKey(keyword, page, requestedRoute), page, freshOnly = false)
        checkScope(requestedRoute, generation)
        record?.value
    }

    suspend fun search(query: String, page: Int = 1, forceRefresh: Boolean = false): AnimeMetadataPage =
        withContext(Dispatchers.Default) {
            val startedAt = wallTimeMillis()
            val keyword = validateSearch(query, page)
            val requestedRoute = route()
            val generation = cache.generation
            val key = searchKey(keyword, page, requestedRoute)
            val requestUrl = searchPageUrl(keyword, page, requestedRoute, generation)
                ?: return@withContext AnimeMetadataPage(emptyList(), page, false)
            if (!forceRefresh) readSearch(key, page, freshOnly = true)
                ?.takeIf { it.requestUrl == requestUrl.toString() && it.value.error == null }?.let {
                    checkScope(requestedRoute, generation)
                    rememberCursor(key, it.nextUrl, generation)
                    return@withContext it.value
                }
            val response = read(requestUrl, AnimeSourceStage.SEARCH, requestedRoute,
                referer = "https://www.douban.com/", timeoutMillis = 8_000L)
            checkScope(requestedRoute, generation)
            val document = DoubanAnimeParser.search(response.text, response.url, keyword)
            val permits = Semaphore(2)
            val resultMutex = Mutex()
            val completed = MutableList<Result<DoubanSubjectDocument>?>(document.candidates.size) { null }
            // Return verified rows even if another detail stalls; unknown genres never become non-animation.
            withTimeoutOrNull((18_000L - (wallTimeMillis() - startedAt)).coerceAtLeast(1L)) {
                coroutineScope {
                    document.candidates.mapIndexed { index, candidate -> async {
                        permits.withPermit {
                            val result = try {
                                // Retrying an incomplete page must progress past already verified subjects.
                                // Explicit subject refresh remains available through detail(forceRefresh = true).
                                Result.success(readSubject(candidate.canonicalDoubanId()!!.toString(), requestedRoute,
                                    generation, forceRefresh = false))
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: DownloadRouteException) { throw failure }
                            catch (failure: Exception) { Result.failure(failure) }
                            resultMutex.withLock { completed[index] = result }
                        }
                    } }.awaitAll()
                }
            }
            checkScope(requestedRoute, generation)
            val details = completed.map { it ?: Result.failure(AnimeSourceException(DOUBAN_PROVIDER,
                AnimeSourceStage.METADATA, "确认动画资料超时，请重试本页。")) }
            val items = details.mapIndexedNotNull { index, result ->
                result.getOrNull()?.takeIf { row -> row.animation }?.subject
                    ?.retainObservedMetadata(document.candidates[index])
            }
                .distinctBy { it.id }
            val failures = details.mapNotNull { it.exceptionOrNull() }
            val errors = listOfNotNull(document.error, failures.firstOrNull()?.let {
                "${failures.size} 部作品的动画类型或资料读取失败：${it.message ?: "请稍后重试。"}"
            })
            if (items.isEmpty() && errors.isNotEmpty()) throw AnimeSourceException(DOUBAN_PROVIDER,
                AnimeSourceStage.SEARCH, errors.joinToString("\n"), cause = failures.firstOrNull())
            val value = AnimeMetadataPage(items, page, document.nextUrl != null,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
            val encoded = JSONObject().put("page", page).put("url", requestUrl.toString())
                .put("next", document.nextUrl).put("error", value.error)
                .put("items", JSONArray(items.map(AnimeDocumentCodec::subject))).toString()
            checkScope(requestedRoute, generation)
            cache.write(key, encoded, generation)
            rememberCursor(key, document.nextUrl, generation)
            checkScope(requestedRoute, generation)
            value
        }

    suspend fun cachedDetail(subject: AnimeSubject): AnimeSubject? = withContext(Dispatchers.Default) {
        val id = requireSubjectId(subject)
        val requestedRoute = route()
        val generation = cache.generation
        val result = cachedSubject(id, requestedRoute, freshOnly = false)
            ?.takeIf { it.animation }?.subject
        checkScope(requestedRoute, generation)
        result?.retainObservedMetadata(subject)
    }

    suspend fun detail(subject: AnimeSubject, forceRefresh: Boolean = false): AnimeSubject = withContext(Dispatchers.Default) {
        val id = requireSubjectId(subject)
        val requestedRoute = route()
        val generation = cache.generation
        val result = readSubject(id, requestedRoute, generation, forceRefresh)
        if (!result.animation) throw AnimeMetadataIdentityException("该豆瓣条目的真实类型不是动画，请重新选择番剧。")
        checkScope(requestedRoute, generation)
        result.subject.retainObservedMetadata(subject)
    }

    internal suspend fun cachedBrowse(query: AnimeDiscoveryQuery, page: Int, requestedRoute: DownloadRouteSnapshot?): AnimeDiscoveryPage? =
        withContext(Dispatchers.Default) {
            validateBrowse(query, page)
            val selectedRoute = requestedRoute ?: return@withContext null
            val generation = cache.generation
            val document = cache.read(browseKey(query, page, selectedRoute)) ?: return@withContext null
            val result = decodeBrowse(document.text, page)
            checkScope(selectedRoute, generation, catalogue = true)
            result
        }

    internal suspend fun browse(query: AnimeDiscoveryQuery, page: Int, forceRefresh: Boolean,
        requestedRoute: DownloadRouteSnapshot): AnimeDiscoveryPage = withContext(Dispatchers.Default) {
        validateBrowse(query, page)
        val generation = cache.generation
        val key = browseKey(query, page, requestedRoute)
        checkScope(requestedRoute, generation, catalogue = true)
        if (!forceRefresh) cache.read(key)?.takeIf { cache.isFresh(it, browseKind(query)) }
            ?.let { decodeBrowse(it.text, page) }?.let {
                checkScope(requestedRoute, generation, catalogue = true)
                return@withContext it
            }
        val kind = if (query.format == AnimeDiscoveryFormat.MOVIE) "movie" else "tv"
        val tags = listOf("动画", regionTag(query.region), query.tag.trim(), query.year?.toString().orEmpty())
            .filter(String::isNotBlank).distinct()
        val sort = when (query.order) {
            AnimeDiscoveryOrder.POPULARITY -> "U"
            AnimeDiscoveryOrder.NEWEST -> "R"
            AnimeDiscoveryOrder.SCORE -> "S"
            else -> error("豆瓣不支持所选排序。")
        }
        val url = animePublicUrl("https://m.douban.com/rexxar/api/v2/$kind/recommend").newBuilder()
            .addQueryParameter("refresh", "0").addQueryParameter("start", ((page - 1L) * DOUBAN_BROWSE_PAGE_SIZE).toString())
            .addQueryParameter("count", DOUBAN_BROWSE_PAGE_SIZE.toString()).addQueryParameter("tags", tags.joinToString(","))
            .addQueryParameter("sort", sort).build()
        val response = read(url, AnimeSourceStage.METADATA, requestedRoute, referer = "https://movie.douban.com/explore", catalogue = true)
        checkScope(requestedRoute, generation, catalogue = true)
        val payload = try { JSONObject(response.text) } catch (failure: Exception) {
            throw AnimeSourceException(DOUBAN_PROVIDER, AnimeSourceStage.METADATA,
                "豆瓣没有返回动画目录，可能需要原站验证，请稍后重试。", cause = failure)
        }
        val fetchedAt = wallTimeMillis()
        val result = DoubanAnimeParser.browse(payload, page, fetchedAt)
        cache.write(key, JSONObject().put("payload", payload).put("fetchedAt", fetchedAt).toString(), generation)
        checkScope(requestedRoute, generation, catalogue = true)
        result
    }

    internal fun validateBrowse(query: AnimeDiscoveryQuery, page: Int) {
        require(query.provider == AnimeDiscoveryProvider.DOUBAN)
        require(page > 0 && (page - 1L) * DOUBAN_BROWSE_PAGE_SIZE <= Int.MAX_VALUE) { "分页位置无效。" }
        require(!query.calendar && query.weekday == null && query.season == null) { "豆瓣动画目录支持年份筛选，不提供季度或每日番表。" }
        require(query.year == null || query.year in 1..9998) { "请输入有效年份。" }
        require(query.format == null || query.format in setOf(AnimeDiscoveryFormat.SERIES, AnimeDiscoveryFormat.MOVIE)) {
            "豆瓣动画目录暂不支持这个作品形式筛选。"
        }
        require(query.region.trim() in setOf("", "日本", "大陆", "中国大陆")) { "请选择豆瓣动画目录支持的地区。" }
        require(query.tag.isBlank() || query.tag.trim() in DOUBAN_GENRES) { "请选择豆瓣动画目录支持的题材。" }
        require(query.order in setOf(AnimeDiscoveryOrder.POPULARITY, AnimeDiscoveryOrder.NEWEST, AnimeDiscoveryOrder.SCORE)) {
            "豆瓣动画目录支持热度、最新和评分排序。"
        }
    }

    private suspend fun readSubject(id: String, requestedRoute: DownloadRouteSnapshot, generation: Long,
        forceRefresh: Boolean): DoubanSubjectDocument {
        checkScope(requestedRoute, generation)
        if (!forceRefresh) cachedSubject(id, requestedRoute, freshOnly = true)?.let {
            checkScope(requestedRoute, generation)
            return it
        }
        val response = read(animePublicUrl("https://movie.douban.com/subject/$id/"), AnimeSourceStage.METADATA,
            requestedRoute, referer = "https://movie.douban.com/")
        checkScope(requestedRoute, generation)
        val result = DoubanAnimeParser.detail(response.text, response.url, id)
        val encoded = JSONObject().put("subject", AnimeDocumentCodec.subject(result.subject))
            .put("animation", result.animation).toString()
        cache.write(detailKey(id, requestedRoute), encoded, generation)
        checkScope(requestedRoute, generation)
        return result
    }

    private suspend fun cachedSubject(id: String, requestedRoute: DownloadRouteSnapshot, freshOnly: Boolean): DoubanSubjectDocument? {
        val document = cache.read(detailKey(id, requestedRoute)) ?: return null
        if (freshOnly && !cache.isFresh(document, ContentCacheKind.DETAILS)) return null
        return runCatching {
            val json = JSONObject(document.text)
            val subject = AnimeDocumentCodec.subject(json.getJSONObject("subject"))
            require(subject.canonicalDoubanId()?.toString() == id && subject.tags.isNotEmpty())
            DoubanSubjectDocument(subject, json.getBoolean("animation"))
        }.getOrNull()
    }

    private suspend fun read(url: HttpUrl, stage: AnimeSourceStage, requestedRoute: DownloadRouteSnapshot,
        referer: String, timeoutMillis: Long = 12_000L, catalogue: Boolean = false): AnimeHttpResponse {
        if ((if (catalogue) browseRoute() else route()) != requestedRoute) throw DownloadRouteException("豆瓣资料的网络线路已更改，请按当前线路重新读取。")
        val http = AnimeHttp(DOUBAN_PROVIDER, requestedRoute)
        return try {
            withTimeoutOrNull(timeoutMillis) {
                http.request(url, stage, referer = referer, sameOriginRedirects = true)
            } ?: throw AnimeSourceException(DOUBAN_PROVIDER, stage, "等待读取豆瓣资料超时，请稍后重试。",
                cause = NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT, "等待读取豆瓣资料超时"))
        } finally { http.close() }
    }

    private suspend fun readSearch(key: String, page: Int, freshOnly: Boolean): SearchRecord? {
        val document = cache.read(key) ?: return null
        if (freshOnly && !cache.isFresh(document, ContentCacheKind.SEARCH)) return null
        return runCatching {
            val json = JSONObject(document.text)
            require(json.getInt("page") == page)
            val rows = json.getJSONArray("items")
            val subjects = (0 until rows.length()).map { AnimeDocumentCodec.subject(rows.getJSONObject(it)) }
            require(subjects.all { it.canonicalDoubanId() != null &&
                it.tags.any { tag -> tag == "动画" || tag.equals("Animation", ignoreCase = true) } })
            val next = json.optString("next").takeIf { it.isNotBlank() }
            SearchRecord(AnimeMetadataPage(subjects, page, next != null,
                error = json.optString("error").takeIf { it.isNotBlank() }), json.getString("url"), next)
        }.getOrNull()
    }

    private suspend fun searchPageUrl(query: String, page: Int, requestedRoute: DownloadRouteSnapshot, generation: Long): HttpUrl? {
        if (page == 1) return DoubanAnimeParser.searchUrl(query)
        val previousKey = searchKey(query, page - 1, requestedRoute)
        val previous = cursorMutex.withLock { cursors[previousKey]?.takeIf { it.generation == generation } }
            ?: readSearch(previousKey, page - 1, freshOnly = false)?.let { SearchCursor(it.nextUrl, generation) }
            ?: throw AnimeSourceException(DOUBAN_PROVIDER, AnimeSourceStage.SEARCH, "豆瓣搜索分页已过期，请从第一页重新搜索。")
        checkScope(requestedRoute, generation)
        return previous.nextUrl?.let { value ->
            val url = animePublicUrl(value)
            val start = url.queryParameter("start")?.toLongOrNull()
            if (url.host != "www.douban.com" || url.port != 443 || url.encodedPath.trimEnd('/') != "/search" ||
                url.queryParameter("q") != query || url.queryParameter("cat") != "1002" || start == null || start <= 0L)
                throw AnimeSourceException(DOUBAN_PROVIDER, AnimeSourceStage.SEARCH, "豆瓣搜索分页资料已失效，请重新搜索。")
            DoubanAnimeParser.searchUrl(query, start)
        }
    }

    private suspend fun rememberCursor(key: String, nextUrl: String?, generation: Long) = cursorMutex.withLock {
        cursors.entries.removeAll { it.value.generation != generation }
        cursors.remove(key)
        cursors[key] = SearchCursor(nextUrl, generation)
        while (cursors.size > 64) cursors.remove(cursors.keys.first())
    }

    private fun decodeBrowse(text: String, page: Int): AnimeDiscoveryPage? = runCatching {
        val document = JSONObject(text)
        val fetchedAt = document.getLong("fetchedAt")
        require(fetchedAt > 0L)
        DoubanAnimeParser.browse(document.getJSONObject("payload"), page, fetchedAt)
    }.getOrNull()

    private fun requireSubjectId(subject: AnimeSubject): String = subject.canonicalDoubanId()?.toString()
        ?: throw AnimeMetadataIdentityException("请选择具有真实豆瓣身份的番剧条目。")

    private fun AnimeSubject.retainObservedMetadata(previous: AnimeSubject): AnimeSubject =
        copy(externalIds = previous.externalIds + externalIds,
            year = year ?: previous.year, format = format ?: previous.format,
            coverUrl = coverUrl.ifBlank { previous.coverUrl })

    private fun validateSearch(query: String, page: Int): String {
        require(page > 0) { "分页位置无效。" }
        return query.trim().also { require(it.isNotBlank() && it.length <= 180) { "请输入 1 至 180 个字的番剧名称。" } }
    }

    private fun checkScope(requestedRoute: DownloadRouteSnapshot, generation: Long, catalogue: Boolean = false) {
        if ((if (catalogue) browseRoute() else route()) != requestedRoute) throw DownloadRouteException("豆瓣资料的网络线路已更改，请按当前线路重新读取。")
        if (cache.generation != generation) throw AnimeSourceException(DOUBAN_PROVIDER, AnimeSourceStage.METADATA,
            "番剧资料缓存已更新，请重新读取。")
    }

    private fun searchKey(query: String, page: Int, route: DownloadRouteSnapshot): String =
        "douban-search:v1:${animeHash(query)}:$page:${animeHash(route.identity())}"
    private fun detailKey(id: String, route: DownloadRouteSnapshot): String = "douban-detail:v1:$id:${animeHash(route.identity())}"
    private fun browseKey(query: AnimeDiscoveryQuery, page: Int, route: DownloadRouteSnapshot): String =
        "douban-browse:v1:${JSONObject().put("order", query.order.name).put("year", query.year)
            .put("region", regionTag(query.region)).put("format", query.format?.name).put("tag", query.tag.trim())}:$page:${animeHash(route.identity())}"
    private fun browseKind(query: AnimeDiscoveryQuery): ContentCacheKind =
        if (query.year != null) ContentCacheKind.DISCOVERY else ContentCacheKind.RANKING
    private fun regionTag(region: String): String = if (region.trim() == "中国大陆") "大陆" else region.trim()

    private data class SearchRecord(val value: AnimeMetadataPage, val requestUrl: String, val nextUrl: String?)
    private data class SearchCursor(val nextUrl: String?, val generation: Long)

    private companion object {
        val DOUBAN_GENRES = setOf("热血", "校园", "恋爱", "搞笑", "治愈", "科幻", "奇幻", "冒险", "悬疑", "推理", "日常", "运动", "机战", "催泪")
    }
}
