package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeBrowseMode
import dev.mangaflow.anime.AnimeDocumentCodec
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.animeCatalogueNames
import dev.mangaflow.anime.animeMetadataSubjectCandidate
import dev.mangaflow.anime.animeMetadataSubjectsMatch
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.anime.enrichAnimeCatalogueSubject
import dev.mangaflow.anime.projectAnimeMetadataEpisodes
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Official Bangumi v0 contract: bangumi/api/open-api/v0.yaml, read 2026-11-16. */
data class AnimeMetadataPage(val items: List<AnimeSubject>, val page: Int, val hasMore: Boolean,
    val error: String? = null)
data class AnimeMetadataDetailResult(
    val subject: AnimeSubject?,
    val episodes: List<AnimeEpisode>?,
    val subjectError: String? = null,
    val episodeError: String? = null,
    val metadataSubject: AnimeSubject? = null,
    /** Exact remote records for a controller merge after a concurrent local-source bootstrap. */
    val metadataEpisodes: List<AnimeEpisode>? = null,
)
internal class AnimeMetadataIdentityException(message: String) : IllegalStateException(message)

class AnimeMetadataRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)
    private val douban = DoubanAnimeRepository(context)

    suspend fun discover(): List<AnimeSubject> = browse().items
    suspend fun search(query: String): List<AnimeSubject> = browse(query = query).items

    /** A source-provided reference is a picker suggestion until the user confirms the exact entry. */
    internal suspend fun subjectByBangumiId(id: Int,
        route: dev.mangaflow.net.DownloadRouteSnapshot? = null): AnimeSubject? = withContext(Dispatchers.Default) {
        require(id > 0)
        val http = route?.let { AnimeHttp(BANGUMI_PROVIDER, it) } ?: AnimeHttp(BANGUMI_PROVIDER)
        try {
            bangumiSubject(http.json(BANGUMI_API.newBuilder().addPathSegment("subjects")
                .addPathSegment(id.toString()).build(), AnimeSourceStage.METADATA))
                ?.takeIf { it.canonicalBangumiId() == id }
        } finally { http.close() }
    }

    suspend fun cachedBrowse(mode: AnimeBrowseMode, query: String, page: Int, year: Int?, tag: String,
        weekday: Int?): AnimeMetadataPage? = cache.read(browseKey(mode, query, page, year, tag, weekday,
            DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME)))?.let {
            withContext(Dispatchers.Default) { runCatching { decodePage(it.text) }.getOrNull() }
        }

    suspend fun browse(mode: AnimeBrowseMode = AnimeBrowseMode.POPULAR, query: String = "", page: Int = 1,
        year: Int? = null, tag: String = "", weekday: Int? = null, forceRefresh: Boolean = false,
        route: DownloadRouteSnapshot = DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME),
    ): AnimeMetadataPage = withContext(Dispatchers.Default) {
        require(page > 0 && (page - 1L) * PAGE_SIZE <= Int.MAX_VALUE) { "分页位置无效。" }
        require(year == null || year in 1..9998) { "请输入有效年份。" }
        require(weekday == null || weekday in 1..7)
        fun checkRoute() { check(DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME) == route) {
            "Bangumi 网络线路已变化，请重新读取资料。"
        } }
        checkRoute()
        val key = browseKey(mode, query, page, year, tag, weekday, route)
        val kind = when {
            query.isNotBlank() -> ContentCacheKind.SEARCH
            mode == AnimeBrowseMode.POPULAR -> ContentCacheKind.RANKING
            else -> ContentCacheKind.DISCOVERY
        }
        cache.read(key)?.takeIf { !forceRefresh && cache.isFresh(it, kind) }?.let {
            runCatching { decodePage(it.text) }.getOrNull()?.let { cached -> checkRoute(); return@withContext cached }
        }
        val cacheGeneration = cache.generation
        val http = AnimeHttp(BANGUMI_PROVIDER, route)
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
            checkRoute()
            cache.write(key, encodePage(result), cacheGeneration)
            checkRoute()
            result
        } finally { http.close() }
    }

    suspend fun cachedDetail(subject: AnimeSubject): AnimeDetail? {
        val stored = cache.read("detail:${subject.id}")?.let {
            withContext(Dispatchers.Default) { runCatching { AnimeDocumentCodec.detail(JSONObject(it.text)) }.getOrNull() }
                ?.takeIf { detail -> detail.subject.id == subject.id }
        }
        if (stored != null || subject.canonicalDoubanId() == null) return stored
        return douban.cachedDetail(subject)?.let { AnimeDetail(it, emptyList()) }
    }

    /** Source bootstrap persists its real local catalogue without inventing a metadata association. */
    suspend fun cacheCatalogDetail(detail: AnimeDetail) {
        require(detail.subject.canonicalDoubanId() != null) { "请选择有效的豆瓣作品。" }
        val cacheGeneration = cache.generation
        AnimeDocumentCodec.validateDetail(detail)
        val document = AnimeDocumentCodec.detail(detail)
        cache.write("detail:${detail.subject.id}", document.toString(), cacheGeneration)
    }

    suspend fun detail(
        subject: AnimeSubject,
        previous: AnimeDetail? = null,
        reference: AnimeSubject? = null,
        allowAutoMatch: Boolean = true,
        onSubjectAvailable: suspend (AnimeSubject, String?) -> Unit = { _, _ -> },
        onEpisodesAvailable: suspend (List<AnimeEpisode>) -> Unit = {},
    ): AnimeMetadataDetailResult = withContext(Dispatchers.Default) {
        if (subject.canonicalDoubanId() != null)
            return@withContext doubanDetail(subject, previous, reference, allowAutoMatch, onSubjectAvailable, onEpisodesAvailable)
        require(subject.id.startsWith("bangumi:")) { "请选择有效的番剧条目。" }
        val id = animeNumericId(subject.id.removePrefix("bangumi:"))
        val key = "detail:${subject.id}"
        cache.read(key)?.takeIf { cache.isFresh(it, ContentCacheKind.DETAILS) }?.let {
            // Old cache entries can still render immediately through cachedDetail,
            // but refresh them once to recover Bangumi's subject-relative episode numbers.
            runCatching {
                val document = JSONObject(it.text)
                if (document.optBoolean("hasSubjectEpisodeNumbers") &&
                    document.optBoolean("hasRatingCounts")) AnimeDocumentCodec.detail(document) else null
            }.getOrNull()?.takeIf { detail -> detail.subject.id == subject.id }?.let { detail ->
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
                            numberInSubject = episode.finiteNumber("ep")?.takeIf { it > 0.0 },
                        )
                    }.sortedWith(compareBy<AnimeEpisode> { it.kind.ordinal }.thenBy { it.number }.thenBy { it.id })
                } }
                val ready = catalogue.await()
                ready.getOrNull()?.let { onEpisodesAvailable(it) }
                metadata.await() to ready
            }
            if (canonical.isSuccess && episodes.isSuccess) {
                val complete = AnimeDetail(canonical.getOrThrow(), episodes.getOrThrow())
                cache.write(key, AnimeDocumentCodec.detail(complete).put("hasSubjectEpisodeNumbers", true)
                    .put("hasRatingCounts", true).toString(), cacheGeneration)
            }
            // Partial responses never replace the complete persisted snapshot or its freshness.
            AnimeMetadataDetailResult(canonical.getOrNull(), episodes.getOrNull(),
                canonical.exceptionOrNull()?.let { (it as? AnimeSourceException)?.message ?: "作品资料未能读取。" },
                episodes.exceptionOrNull()?.let { (it as? AnimeSourceException)?.message ?: "集数目录未能读取。" })
        } finally { http.close() }
    }

    private suspend fun doubanDetail(
        subject: AnimeSubject,
        previous: AnimeDetail?,
        reference: AnimeSubject?,
        allowAutoMatch: Boolean,
        onSubjectAvailable: suspend (AnimeSubject, String?) -> Unit,
        onEpisodesAvailable: suspend (List<AnimeEpisode>) -> Unit,
    ): AnimeMetadataDetailResult {
        require(previous == null || previous.subject.id == subject.id) { "本地目录属于另一部作品。" }
        val cached = catalogueRead { cachedDetail(subject) }.getOrNull()
        val baseline = previous?.takeIf { it.episodes.isNotEmpty() } ?: cached ?: previous
        val mainRead = catalogueRead {
            douban.detail(subject).also { require(it.canonicalDoubanId() == subject.canonicalDoubanId()) {
                "豆瓣返回了其他作品的资料。"
            } }
        }
        (mainRead.exceptionOrNull() as? AnimeMetadataIdentityException)?.let { throw it }
        val readPrimary = mainRead.getOrNull() ?: previous?.subject ?: cached?.subject ?: subject
        val primary = if (reference == null && !allowAutoMatch)
            readPrimary.copy(externalIds = readPrimary.externalIds - "bangumi") else readPrimary
        val subjectError = mainRead.exceptionOrNull()?.message ?: if (mainRead.isFailure) "豆瓣作品资料未能读取。" else null
        onSubjectAvailable(primary, subjectError)

        if (reference == null && !allowAutoMatch) {
            val local = baseline?.episodes.orEmpty().map { it.copy(metadataEpisodeId = null, metadataSubjectId = null) }
            return AnimeMetadataDetailResult(primary, local, subjectError)
        }

        val resolved = catalogueRead {
            if (reference != null) {
                require(reference.canonicalBangumiId() != null) { "手工资料关联缺少有效的 Bangumi 身份。" }
                reference
            } else {
                val known = listOfNotNull(previous?.metadataSubject, cached?.metadataSubject)
                    .firstOrNull { animeMetadataSubjectsMatch(primary, it) }
                known ?: run {
                    val lookup = withTimeoutOrNull(15_000L) { Result.success(resolveBangumiReference(primary)) }
                        ?: error("Bangumi 资料匹配超时；豆瓣作品与播放来源保留，可稍后重试。")
                    lookup.getOrThrow() ?: error("尚未确认同一部番剧的 Bangumi 资料；播放来源仍可独立匹配。")
                }
            }
        }
        val selected = resolved.getOrNull() ?: return AnimeMetadataDetailResult(primary, null, subjectError,
            resolved.exceptionOrNull()?.message ?: "补充资料未能匹配。")
        val supplementary = catalogueRead { withTimeoutOrNull(15_000L) { detail(selected) } }
        // An already confirmed work can own comments when the optional episode read times out.
        // Identity/route failures are not a new confirmation, and no episode mapping is inferred.
        val loaded = supplementary.getOrElse { failure ->
            return AnimeMetadataDetailResult(primary, null, subjectError,
                failure.message ?: "Bangumi 剧集资料未能读取。")
        } ?: return AnimeMetadataDetailResult(primary, null, subjectError,
            "Bangumi 补充剧集读取超时；豆瓣作品与本地选集保留，可稍后重试。", metadataSubject = selected)
        val metadata = loaded.subject?.let { fresh ->
            // Legacy cache rows may predate format/alias fields; the freshly resolved record owns them.
            fresh.copy(format = fresh.format ?: selected.format,
                aliases = (fresh.aliases + selected.aliases).distinct().take(64))
        } ?: selected
        if (metadata.canonicalBangumiId() != selected.canonicalBangumiId() ||
            reference == null && !animeMetadataSubjectsMatch(primary, metadata)) {
            return AnimeMetadataDetailResult(primary, null, subjectError,
                "补充资料与当前作品的年份、格式或季度不一致，已保留豆瓣作品和本地选集。")
        }
        val shown = enrichAnimeCatalogueSubject(primary, metadata)
        val episodes = loaded.episodes?.let { remote ->
            projectAnimeMetadataEpisodes(shown, AnimeDetail(metadata, remote), baseline)
        }
        episodes?.let { onEpisodesAvailable(it) }
        val episodeError = loaded.episodeError ?: if (episodes?.any { it.metadataEpisodeId == null } == true)
            "已保留本地选集；部分剧集尚未确认对应的 Bangumi 集记录。" else null
        // The controller persists/caches its final merge under the current detail/restore/route fence.
        // A supplementary request may have outlived a newly bootstrapped local source catalogue.
        return AnimeMetadataDetailResult(shown, episodes, subjectError, episodeError, metadata, loaded.episodes)
    }

    /** Search completion, detailed evidence and uniqueness are all required before automatic linking. */
    internal suspend fun resolveBangumiReference(primary: AnimeSubject,
        route: DownloadRouteSnapshot = DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME),
        forceRefresh: Boolean = false,
    ): AnimeSubject? {
        fun checkRoute() { check(DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME) == route) {
            "Bangumi 网络线路已变化，请重新核对作品。"
        } }
        checkRoute()
        if (primary.year == null || primary.format == null) return null
        val names = (listOf(primary.originalTitle) + animeCatalogueNames(primary))
            .map(String::trim).filter { it.isNotBlank() && it.length <= 500 }.distinct().take(3)
        val candidates = linkedMapOf<String, AnimeSubject>()
        for (name in names) {
            for (page in 1..2) {
                val result = browse(query = name, page = page, year = primary.year, forceRefresh = forceRefresh, route = route)
                checkRoute()
                result.items.filter { animeMetadataSubjectCandidate(primary, it) }.forEach { candidates[it.id] = it }
                if (!result.hasMore) break
                if (page == 2) error("同名资料检索尚未完整，暂不自动确认 Bangumi 条目。")
            }
        }
        if (candidates.size > 6) error("同名资料候选不唯一，暂不自动关联其他季度。")
        val verified = mutableListOf<AnimeSubject>()
        for (candidate in candidates.values) {
            val full = subjectByBangumiId(requireNotNull(candidate.canonicalBangumiId()), route) ?: continue
            checkRoute()
            if (animeMetadataSubjectsMatch(primary, full)) verified += full
        }
        return verified.singleOrNull()
    }

    /** A supplementary service's identity/read failure never discards the primary catalogue. */
    private suspend fun <T> catalogueRead(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { Result.failure(failure) }

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

    private fun browseKey(mode: AnimeBrowseMode, query: String, page: Int, year: Int?, tag: String, weekday: Int?,
        route: DownloadRouteSnapshot) =
        JSONObject().put("mode", mode.name).put("query", query.trim()).put("page", page)
            .put("year", year).put("tag", tag.trim()).put("weekday", weekday)
            .put("route", animeHash(route.identity())).toString()
    private fun encodePage(page: AnimeMetadataPage) = JSONObject().put("page", page.page)
        .put("hasMore", page.hasMore).put("items", JSONArray(page.items.map { AnimeDocumentCodec.subject(it) }))
        .put("error", page.error).toString()
    private fun decodePage(text: String): AnimeMetadataPage {
        val json = JSONObject(text)
        return AnimeMetadataPage(json.getJSONArray("items").objects().map { AnimeDocumentCodec.subject(it) },
            json.getInt("page"), json.getBoolean("hasMore"), json.optString("error", null))
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
        ratingCount = json.optJSONObject("rating")?.optLong("total", -1L)
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        format = when (json.text("platform")) {
            "TV" -> AnimeDiscoveryFormat.SERIES
            "剧场版" -> AnimeDiscoveryFormat.MOVIE
            "OVA" -> AnimeDiscoveryFormat.SPECIAL
            "WEB" -> if ((json.positiveInt("eps") ?: json.positiveInt("eps_count") ?: 0) > 1)
                AnimeDiscoveryFormat.SERIES else null
            else -> null
        },
        aliases = bangumiAliases(json),
    )
}

private fun bangumiAliases(json: JSONObject): List<String> {
    fun values(value: Any?, depth: Int): List<String> {
        if (depth > 4) return emptyList()
        return when (value) {
            is String -> listOf(value.trim())
            is JSONArray -> (0 until minOf(value.length(), 64)).flatMap { values(value.opt(it), depth + 1) }
            is JSONObject -> values(value.opt("v"), depth + 1)
            else -> emptyList()
        }
    }
    return json.optJSONArray("infobox")?.objects().orEmpty()
        .filter { it.text("key") in setOf("别名", "中文名") }
        .flatMap { values(it.opt("value"), 0) }
        .filter { it.isNotBlank() && it.length <= 1000 }.distinct().take(64)
}

/** The legacy calendar API still advertises HTTP URLs for the HTTPS-capable official CDN.
 * Normalize only that known origin; never relax the shared public-source HTTPS boundary.
 */
internal fun bangumiCover(images: JSONObject?, thumbnail: Boolean = false): String {
    val sizes = if (thumbnail) listOf("common", "medium", "large", "small", "grid")
        else listOf("large", "common", "medium", "small", "grid")
    if (images == null) return ""
    return sizes.firstNotNullOfOrNull { size ->
        if (images.isNull(size)) return@firstNotNullOfOrNull null
        val raw = images.text(size).takeUnless { it.equals("null", ignoreCase = true) }
            ?: return@firstNotNullOfOrNull null
        val url = raw.toHttpUrlOrNull()
        val normalized = if (url != null && url.scheme == "http" && url.host == "lain.bgm.tv" &&
            url.port == 80 && url.username.isEmpty() && url.password.isEmpty())
            url.newBuilder().scheme("https").port(443).build().toString() else raw
        sourceCover("https://bgm.tv/", normalized).takeIf { it.isNotBlank() }
    }.orEmpty()
}

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
