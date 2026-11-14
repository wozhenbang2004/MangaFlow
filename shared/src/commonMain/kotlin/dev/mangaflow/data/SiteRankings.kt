package dev.mangaflow.data

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.*
import dev.mangaflow.storage.readManga
import dev.mangaflow.storage.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SiteRankingKind(val label: String) {
    SCORE("评分"), POPULARITY("人气"), TRENDING("趋势"), FAVOURITES("最爱")
}

enum class SiteRankingScope(val label: String) {
    ALL("全部漫画"), SERIES("系列"), VOLUME("单行本"),
    MANGA("漫画"), MANHWA("韩漫"), MANHUA("国漫"), ONE_SHOT("短篇")
}

enum class RankingSite(val id: String, val title: String, val home: String) {
    BANGUMI("bangumi", "Bangumi", "https://bangumi.tv"),
    ANILIST("anilist", "AniList", "https://anilist.co"),
    MYANIMELIST("myanimelist", "MyAnimeList", "https://myanimelist.net");

    val kinds: List<SiteRankingKind> get() = when (this) {
        BANGUMI -> listOf(SiteRankingKind.SCORE)
        ANILIST -> SiteRankingKind.entries
        MYANIMELIST -> listOf(SiteRankingKind.SCORE, SiteRankingKind.POPULARITY, SiteRankingKind.FAVOURITES)
    }
    val scopes: List<SiteRankingScope> get() = when (this) {
        BANGUMI -> listOf(SiteRankingScope.SERIES, SiteRankingScope.ALL, SiteRankingScope.VOLUME)
        ANILIST -> listOf(SiteRankingScope.ALL, SiteRankingScope.MANGA, SiteRankingScope.ONE_SHOT)
        MYANIMELIST -> listOf(SiteRankingScope.MANGA, SiteRankingScope.MANHWA,
            SiteRankingScope.MANHUA, SiteRankingScope.ONE_SHOT)
    }
}

data class SiteRankingSelection(
    val site: RankingSite = RankingSite.BANGUMI,
    val kind: SiteRankingKind = SiteRankingKind.SCORE,
    val scope: SiteRankingScope = SiteRankingScope.SERIES,
) {
    val key: String get() = site.id + ":" + kind.name + ":" + scope.name
    fun valid(): Boolean = kind in site.kinds && scope in site.scopes
    val explanation: String get() = when (kind) {
        SiteRankingKind.SCORE -> if (site == RankingSite.BANGUMI)
            "保留 Bangumi 原站评分排名顺序。序号属于当前漫画范围，原始评分排名在作品资料中显示。"
            else "保留来源的评分排序。列表序号属于当前范围，作品的全站评分排名单独记录。"
        SiteRankingKind.POPULARITY -> "按加入该站书单的人数排列，不是阅读次数或评分人数。"
        SiteRankingKind.TRENDING -> "按 AniList 过去一小时的相关活跃排列，不是累计人气。"
        SiteRankingKind.FAVOURITES -> "按该站标记为最爱的人数排列，与加入书单人数分别统计。"
    } + (if (kind == SiteRankingKind.SCORE) if (site == RankingSite.ANILIST) " 100分制。" else " 10分制。" else "") + if (site == RankingSite.MYANIMELIST)
        " 数据由现有 Tenrai 通道提供，可能滞后于原站；本地获取时间不代表原站更新时间。" else ""
}

/** Catalogue identity is deliberately separate from every readable source identity. */
data class SiteRankedWork(
    val site: RankingSite,
    val subjectId: String,
    val manga: Manga,
    val score: Double? = null,
    val scoreScale: Int = 10,
    val ratingCount: Int? = null,
    val popularity: Long? = null,
    val trending: Long? = null,
    val favourites: Long? = null,
    val ratingRank: Int? = null,
) {
    val key: String get() = site.id + ":" + subjectId
}

data class SiteRankingEntry(val position: Int, val work: SiteRankedWork)
data class SiteRankingSnapshot(
    val selection: SiteRankingSelection,
    val entries: List<SiteRankingEntry>,
    val page: Int,
    val hasMore: Boolean,
    val fetchedAt: Long,
)
data class SiteRankingPage(val entries: List<SiteRankingEntry>, val hasMore: Boolean)

/** Anonymous native rankings; the Copy ranking repository and its keys are not used here. */
class SiteRankingsRepository(context: PlatformContext) {
    private val http = ReviewProviderHttp(SourceHttp())
    private val directory = StorageFile(context.applicationContext.cacheDir, "site-rankings")
    private val links by lazy { preferences(context, "mangaflow_site_ranking_links") }
    private val diskLock = Mutex()
    private val linkLock = Mutex()
    private var cacheEpoch = 0L

    suspend fun page(selection: SiteRankingSelection, page: Int): SiteRankingPage = withContext(Dispatchers.IO) {
        require(selection.valid() && page in 0..199) { "榜单范围或页码无效。" }
        when (selection.site) {
            RankingSite.BANGUMI -> bangumi(selection, page)
            RankingSite.ANILIST -> aniList(selection, page)
            RankingSite.MYANIMELIST -> myAnimeList(selection, page)
        }
    }

    private suspend fun bangumi(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val url = "https://api.bgm.tv/v0/subjects".toHttpUrl().newBuilder()
            .addQueryParameter("type", "1").addQueryParameter("cat", "1001")
            .addQueryParameter("sort", "rank").addQueryParameter("limit", "20")
            .addQueryParameter("offset", (page * 20).toString()).apply {
                if (selection.scope != SiteRankingScope.ALL)
                    addQueryParameter("series", (selection.scope == SiteRankingScope.SERIES).toString())
            }.build().toString()
        val response = http.json(url, "Bangumi 榜单", selection.site.home)
        val rows = response.optJSONArray("data") ?: invalid(selection.site)
        val entries = rankObjects(rows, selection.site).mapIndexedNotNull { index, row ->
            if (row.optBoolean("nsfw", false)) return@mapIndexedNotNull null
            val work = bangumiWork(row)
            SiteRankingEntry(page * 20 + index + 1, work)
        }
        val total = response.opt("total")?.toString()?.toLongOrNull()
            ?: throw SourceException("Bangumi 未返回有效的榜单分页。", selection.site.home)
        return SiteRankingPage(entries, (page + 1L) * 20 < total)
    }

    private fun bangumiWork(row: JSONObject): SiteRankedWork {
        val id = decimal(row.reviewId("id"), RankingSite.BANGUMI)
        if (row.optInt("type") != 1) invalid(RankingSite.BANGUMI)
        val titles = listOf(row.reviewText("name_cn"), row.reviewText("name")).filter(String::isNotBlank).distinct()
        if (titles.isEmpty()) invalid(RankingSite.BANGUMI)
        val authors = mutableListOf<String>()
        row.optJSONArray("infobox")?.reviewObjects()?.forEach { box ->
            if (box.reviewText("key") in setOf("作者", "作画", "原作", "脚本")) {
                when (val value = box.opt("value")) {
                    is String -> authors.add(value)
                    is JSONArray -> value.reviewObjects().forEach { authors.add(it.reviewText("v")) }
                }
            }
        }
        val image = row.optJSONObject("images")
        val cover = listOf("large", "common", "medium").firstNotNullOfOrNull { key ->
            image?.reviewText(key)?.let { reviewAvatar(it, setOf("bgm.tv", "bangumi.tv")) }?.takeIf(String::isNotBlank)
        }.orEmpty()
        val rating = row.optJSONObject("rating")
        return work(RankingSite.BANGUMI, id, titles, authors, cover, row.reviewText("summary"),
            rating?.reviewScore("score", 10), 10, rating?.reviewCount("total"),
            ratingRank = rating?.reviewCount("rank")?.takeIf { it > 0 })
    }

    private suspend fun aniList(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val sort = when (selection.kind) {
            SiteRankingKind.SCORE -> "SCORE_DESC"
            SiteRankingKind.POPULARITY -> "POPULARITY_DESC"
            SiteRankingKind.TRENDING -> "TRENDING_DESC"
            SiteRankingKind.FAVOURITES -> "FAVOURITES_DESC"
        }
        val formats = when (selection.scope) {
            SiteRankingScope.MANGA -> listOf("MANGA")
            SiteRankingScope.ONE_SHOT -> listOf("ONE_SHOT")
            else -> listOf("MANGA", "ONE_SHOT")
        }
        val variables = JSONObject().put("page", page + 1).put("sort", sort).put("formats", JSONArray(formats))
        val body = JSONObject().put("query", ANILIST_QUERY).put("variables", variables)
        val response = http.json("https://graphql.anilist.co", "AniList 榜单", selection.site.home,
            body = body, minIntervalMillis = 2_100L)
        if ((response.optJSONArray("errors")?.length() ?: 0) > 0)
            throw SourceException("AniList 榜单暂时无法读取，请稍后重试。", selection.site.home)
        val result = response.optJSONObject("data")?.optJSONObject("Page") ?: invalid(selection.site)
        val rows = result.optJSONArray("media") ?: invalid(selection.site)
        val entries = rankObjects(rows, selection.site).mapIndexed { index, row ->
            if (row.reviewText("type") != "MANGA" || row.reviewText("format") !in formats ||
                row.optBoolean("isAdult", false)) invalid(selection.site)
            val id = decimal(row.reviewId("id"), selection.site)
            val title = row.optJSONObject("title") ?: invalid(selection.site)
            val titles = (listOf(title.reviewText("native"), title.reviewText("romaji"), title.reviewText("english")) +
                strings(row.optJSONArray("synonyms"))).filter(String::isNotBlank).distinct()
            val authors = row.optJSONObject("staff")?.optJSONArray("edges")?.reviewObjects().orEmpty()
                .filter { edge -> edge.reviewText("role").lowercase().let { "story" in it || "art" in it || "author" in it } }
                .flatMap { edge -> edge.optJSONObject("node")?.optJSONObject("name")?.let {
                    listOf(it.reviewText("native"), it.reviewText("full"))
                }.orEmpty() }.filter(String::isNotBlank).distinct()
            val votes = row.optJSONObject("stats")?.optJSONArray("scoreDistribution")?.reviewObjects()
                ?.mapNotNull { it.reviewCount("amount") }?.sum()?.takeIf { it > 0 }
            val mal = row.reviewId("idMal").takeIf { it.matches(Regex("[1-9][0-9]*")) }
            val cover = row.optJSONObject("coverImage")?.let {
                reviewAvatar(it.reviewText("large"), setOf("anilist.co", "anili.st"))
            }.orEmpty()
            val item = work(selection.site, id, titles, authors, cover, row.reviewText("description"),
                row.reviewScore("averageScore", 100), 100, votes,
                row.longCount("popularity"), row.longCount("trending"), row.longCount("favourites"),
                extraIds = mal?.let { mapOf("myanimelist" to it) }.orEmpty())
            SiteRankingEntry(page * 20 + index + 1, item)
        }
        val info = result.optJSONObject("pageInfo") ?: invalid(selection.site)
        if (!info.has("hasNextPage")) invalid(selection.site)
        return SiteRankingPage(entries, info.getBoolean("hasNextPage"))
    }

    private suspend fun myAnimeList(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val type = when (selection.scope) {
            SiteRankingScope.MANHWA -> "manhwa"
            SiteRankingScope.MANHUA -> "manhua"
            SiteRankingScope.ONE_SHOT -> "oneshot"
            else -> "manga"
        }
        val url = "https://api.tenrai.org/v1/top/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page + 1).toString()).addQueryParameter("limit", "20")
            .addQueryParameter("type", type).addQueryParameter("sfw", "true").apply {
                when (selection.kind) {
                    SiteRankingKind.POPULARITY -> addQueryParameter("filter", "bypopularity")
                    SiteRankingKind.FAVOURITES -> addQueryParameter("filter", "favorite")
                    else -> Unit
                }
            }.build().toString()
        val response = http.json(url, "MyAnimeList 榜单", selection.site.home, minIntervalMillis = 1_000L)
        val rows = response.optJSONArray("data") ?: invalid(selection.site)
        val expected = when (type) { "manhwa" -> "Manhwa"; "manhua" -> "Manhua"; "oneshot" -> "One-shot"; else -> "Manga" }
        val entries = rankObjects(rows, selection.site).mapIndexed { index, row ->
            if (!row.reviewText("type").equals(expected, true)) invalid(selection.site)
            val id = decimal(row.reviewId("mal_id"), selection.site)
            val titles = (listOf(row.reviewText("title"), row.reviewText("title_english"), row.reviewText("title_japanese")) +
                strings(row.optJSONArray("title_synonyms")) +
                row.optJSONArray("titles")?.reviewObjects().orEmpty().map { it.reviewText("title") })
                .filter(String::isNotBlank).distinct()
            val authors = row.optJSONArray("authors")?.reviewObjects().orEmpty().map { it.reviewText("name") }
            val images = row.optJSONObject("images")
            val cover = listOf("webp", "jpg").firstNotNullOfOrNull { format ->
                images?.optJSONObject(format)?.reviewText("large_image_url")?.let {
                    reviewAvatar(it, setOf("myanimelist.net"))
                }?.takeIf(String::isNotBlank)
            }.orEmpty()
            SiteRankingEntry(page * 20 + index + 1,
                work(selection.site, id, titles, authors, cover, row.reviewText("synopsis"),
                    row.reviewScore("score", 10), 10, row.reviewCount("scored_by"),
                    popularity = row.longCount("members"), favourites = row.longCount("favorites"),
                    ratingRank = row.reviewCount("rank")?.takeIf { it > 0 }))
        }
        val pagination = response.optJSONObject("pagination") ?: invalid(selection.site)
        if (!pagination.has("has_next_page")) invalid(selection.site)
        return SiteRankingPage(entries, pagination.getBoolean("has_next_page"))
    }

    suspend fun enrich(work: SiteRankedWork): SiteRankedWork = withContext(Dispatchers.IO) {
        if (work.site != RankingSite.BANGUMI || work.manga.authors.isNotEmpty()) return@withContext work
        val response = http.json("https://api.bgm.tv/v0/subjects/" + work.subjectId,
            "Bangumi 作品资料", work.manga.sourceUrl)
        val updated = bangumiWork(response)
        if (updated.subjectId != work.subjectId) invalid(work.site)
        updated
    }

    private fun work(site: RankingSite, id: String, titles: List<String>, authors: List<String>,
        cover: String, description: String, score: Double?, scale: Int, count: Int?,
        popularity: Long? = null, trending: Long? = null, favourites: Long? = null,
        ratingRank: Int? = null, extraIds: Map<String, String> = emptyMap()): SiteRankedWork {
        if (titles.isEmpty()) invalid(site)
        val url = when (site) {
            RankingSite.BANGUMI -> site.home + "/subject/" + id
            RankingSite.ANILIST -> site.home + "/manga/" + id
            RankingSite.MYANIMELIST -> site.home + "/manga/" + id
        }
        val manga = Manga(id = "catalog:" + site.id + ":" + id, title = titles.first().take(300),
            coverUrl = cover, authors = authors.filter(String::isNotBlank).distinct().take(12),
            authorAliases = authors.filter(String::isNotBlank).distinct().take(24),
            description = reviewPlainText(description).take(12_000), sourceUrl = url,
            sourceId = "catalog", sourceName = site.title, alternativeTitles = titles.take(24),
            externalIds = extraIds + (site.id to id))
        return SiteRankedWork(site, id, manga, score, scale, count, popularity, trending, favourites, ratingRank)
    }

    /** Exact user-confirmed/verified work mapping survives disposable list cache clearing. */
    suspend fun linked(work: SiteRankedWork): Manga? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = links.getString("link:" + work.key, null) ?: return@runCatching null
            val saved = JSONObject(raw)
            if (saved.getString("work") != work.key) return@runCatching null
            readManga(saved.getJSONObject("manga")).takeIf {
                it.id.isNotBlank() && it.title.isNotBlank() && it.sourceId != "catalog" &&
                    !conflicts(work, it)
            }
        }.getOrNull()
    }

    suspend fun rememberLink(work: SiteRankedWork, manga: Manga, onlyIfAbsent: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            linkLock.withLock {
                require(manga.sourceId != "catalog" && !conflicts(work, manga)) { "阅读来源的作品编号与榜单作品冲突。" }
                if (onlyIfAbsent && links.getString("link:" + work.key, null) != null) return@withLock false
                check(links.edit().putString("link:" + work.key,
                    JSONObject().put("work", work.key).put("manga", manga.toJson()).toString()).commit()) {
                    "阅读来源未能保存，请检查存储空间。"
                }
                true
            }
        }

    fun conflicts(work: SiteRankedWork, manga: Manga): Boolean =
        work.manga.externalIds.any { (provider, id) -> manga.externalIds[provider]?.let { it != id } == true }

    suspend fun readCache(selection: SiteRankingSelection): SiteRankingSnapshot? = withContext(Dispatchers.IO) {
        diskLock.withLock {
            runCatching {
                val file = cacheFile(selection)
                if (!file.isFile || file.length() !in 1..MAX_CACHE_BYTES) return@runCatching null
                val root = JSONObject(StorageAtomicFile(file).openRead().bufferedReader().use { it.readText() })
                if (root.optInt("version") != 1 || root.getString("key") != selection.key) return@runCatching null
                val fetchedAt = root.getLong("fetchedAt")
                if (wallTimeMillis() - fetchedAt !in -300_000L..604_800_000L) return@runCatching null
                val rows = root.getJSONArray("entries")
                if (rows.length() > 600) return@runCatching null
                val entries = (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    val item = row.getJSONObject("work")
                    val id = decimal(item.getString("subjectId"), selection.site)
                    val manga = readManga(item.getJSONObject("manga"))
                    require(manga.id == "catalog:" + selection.site.id + ":" + id && manga.sourceId == "catalog" &&
                        manga.externalIds[selection.site.id] == id)
                    SiteRankingEntry(row.getInt("position").also { require(it > 0) },
                        SiteRankedWork(selection.site, id, manga,
                            item.reviewScore("score", if (selection.site == RankingSite.ANILIST) 100 else 10),
                            if (selection.site == RankingSite.ANILIST) 100 else 10,
                            item.reviewCount("ratingCount"), item.longCount("popularity"),
                            item.longCount("trending"), item.longCount("favourites"), item.reviewCount("ratingRank")))
                }
                require(entries.distinctBy { it.work.key }.size == entries.size)
                SiteRankingSnapshot(selection, entries, root.getInt("page").also { require(it in 0..199) },
                    root.getBoolean("hasMore"), fetchedAt)
            }.getOrNull()
        }
    }

    suspend fun writeCache(snapshot: SiteRankingSnapshot, epoch: Long) = withContext(Dispatchers.IO) {
        if (snapshot.entries.size > 600) return@withContext
        diskLock.withLock {
            if (epoch != cacheEpoch) return@withLock
            val rows = JSONArray()
            snapshot.entries.forEach { entry ->
                val w = entry.work
                val value = JSONObject().put("subjectId", w.subjectId).put("manga", w.manga.toJson())
                w.score?.let { value.put("score", it) }; w.ratingCount?.let { value.put("ratingCount", it) }
                w.popularity?.let { value.put("popularity", it) }; w.trending?.let { value.put("trending", it) }
                w.favourites?.let { value.put("favourites", it) }; w.ratingRank?.let { value.put("ratingRank", it) }
                rows.put(JSONObject().put("position", entry.position).put("work", value))
            }
            val bytes = JSONObject().put("version", 1).put("key", snapshot.selection.key)
                .put("page", snapshot.page).put("hasMore", snapshot.hasMore).put("fetchedAt", snapshot.fetchedAt)
                .put("entries", rows).toString().encodeToByteArray()
            if (bytes.size > MAX_CACHE_BYTES) return@withLock
            directory.mkdirs()
            val file = StorageAtomicFile(cacheFile(snapshot.selection))
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            var total = 0L
            directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
                .forEachIndexed { index, item ->
                    total += item.length()
                    if (index >= 24 || total > 8L * 1024 * 1024) item.delete()
                }
        }
    }

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        diskLock.withLock { directory.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L } }
    }
    suspend fun cacheGeneration(): Long = diskLock.withLock { cacheEpoch }
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        diskLock.withLock {
            cacheEpoch++
            val failures = directory.listFiles().orEmpty().count { it.isFile && !it.delete() }
            check(failures == 0) { "部分榜单缓存未能清理，请重试。" }
        }
    }

    private fun cacheFile(selection: SiteRankingSelection): StorageFile =
        StorageFile(directory, sha256(selection.key.encodeToByteArray()).joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        } + ".json")

    private fun rankObjects(rows: JSONArray, site: RankingSite): List<JSONObject> {
        if (rows.length() > 20) invalid(site)
        return (0 until rows.length()).map { rows.optJSONObject(it) ?: invalid(site) }
    }
    private fun strings(values: JSONArray?): List<String> =
        if (values == null) emptyList() else (0 until values.length()).mapNotNull {
            (values.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank)
        }
    private fun JSONObject.longCount(key: String): Long? =
        opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
    private fun decimal(value: String, site: RankingSite): String =
        value.takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: invalid(site)
    private fun invalid(site: RankingSite): Nothing =
        throw SourceException(site.title + " 榜单数据格式已变化，请重试。", site.home)

    private companion object {
        const val MAX_CACHE_BYTES = 1024L * 1024
        const val ANILIST_QUERY = """
            query MangaFlowRankings(${'$'}page: Int!, ${'$'}sort: MediaSort!, ${'$'}formats: [MediaFormat]!) {
              Page(page: ${'$'}page, perPage: 20) {
                pageInfo { hasNextPage }
                media(type: MANGA, format_in: ${'$'}formats, isAdult: false, sort: [${'$'}sort, ID]) {
                  id idMal type format isAdult
                  title { native romaji english } synonyms
                  coverImage { large } description(asHtml: false)
                  averageScore popularity trending favourites
                  stats { scoreDistribution { amount } }
                  staff(perPage: 8) { edges { role node { name { native full } } } }
                }
              }
            }
        """
    }
}
