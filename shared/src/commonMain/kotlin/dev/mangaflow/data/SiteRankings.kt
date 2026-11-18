package dev.mangaflow.data

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.*
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.ContentCachePreferences
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.storage.AuxiliaryCacheEntry
import dev.mangaflow.storage.AuxiliaryCacheFiles
import dev.mangaflow.storage.AuxiliaryDiskCacheOwner
import dev.mangaflow.storage.StorageCacheStore
import dev.mangaflow.storage.readManga
import dev.mangaflow.storage.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SiteRankingKind(val label: String) {
    SCORE("评分"), POPULARITY("人气"), TRENDING("趋势"), FAVOURITES("最爱"), NEWEST("新作")
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
        BANGUMI -> listOf(SiteRankingKind.SCORE, SiteRankingKind.POPULARITY, SiteRankingKind.NEWEST)
        ANILIST -> SiteRankingKind.entries
        MYANIMELIST -> listOf(SiteRankingKind.SCORE, SiteRankingKind.POPULARITY, SiteRankingKind.FAVOURITES,
            SiteRankingKind.NEWEST)
    }
    val scopes: List<SiteRankingScope> get() = when (this) {
        BANGUMI -> listOf(SiteRankingScope.SERIES, SiteRankingScope.ALL, SiteRankingScope.VOLUME)
        ANILIST -> listOf(SiteRankingScope.ALL, SiteRankingScope.MANGA, SiteRankingScope.ONE_SHOT)
        MYANIMELIST -> listOf(SiteRankingScope.MANGA, SiteRankingScope.MANHWA,
            SiteRankingScope.MANHUA, SiteRankingScope.ONE_SHOT)
    }
    val supportsMonth: Boolean get() = this == BANGUMI
    val supportsQuery: Boolean get() = true
    val supportsGenres: Boolean get() = true
    val supportsStatus: Boolean get() = this != BANGUMI
    val supportsCountry: Boolean get() = this == ANILIST
    val supportsMinScore: Boolean get() = true
    val supportsMinVotes: Boolean get() = this != ANILIST
}

enum class SiteRankingGenre(val id: String, val label: String, val bangumiTag: String, val malId: Int) {
    ACTION("Action", "动作", "战斗", 1),
    ADVENTURE("Adventure", "冒险", "冒险", 2),
    COMEDY("Comedy", "搞笑", "搞笑", 4),
    DRAMA("Drama", "剧情", "剧情", 8),
    FANTASY("Fantasy", "奇幻", "奇幻", 10),
    HORROR("Horror", "恐怖", "恐怖", 14),
    MYSTERY("Mystery", "悬疑", "悬疑", 7),
    ROMANCE("Romance", "恋爱", "恋爱", 22),
    SCI_FI("Sci-Fi", "科幻", "科幻", 24),
    SLICE_OF_LIFE("Slice of Life", "日常", "日常", 36),
    SPORTS("Sports", "运动", "运动", 30),
    SUPERNATURAL("Supernatural", "超自然", "超自然", 37),
}

enum class SiteRankingStatus(val label: String, val malValue: String) {
    RELEASING("连载中", "publishing"), FINISHED("已完结", "complete"),
    NOT_YET_RELEASED("未开始", "upcoming"), HIATUS("休刊", "hiatus"), CANCELLED("已终止", "discontinued");
    val id: String get() = name
}

data class SiteRankingFilters(
    val year: Int? = null,
    val month: Int? = null,
    val query: String = "",
    val genres: Set<String> = emptySet(),
    val status: String? = null,
    val country: String? = null,
    val minScore: Int? = null,
    val minVotes: Int? = null,
) {
    internal val hasSearchConditions: Boolean get() = query.isNotBlank() || genres.isNotEmpty() ||
        minScore != null || minVotes != null

    internal fun toJson(): JSONObject = JSONObject()
        .put("year", year).put("month", month).put("query", query)
        .put("genres", JSONArray(genres.sorted())).put("status", status).put("country", country)
        .put("minScore", minScore).put("minVotes", minVotes)
}

data class SiteRankingSelection(
    val site: RankingSite = RankingSite.BANGUMI,
    val kind: SiteRankingKind = SiteRankingKind.SCORE,
    val scope: SiteRankingScope = SiteRankingScope.SERIES,
    val filters: SiteRankingFilters = SiteRankingFilters(),
) {
    /** JSON escaping and ordered genre IDs preserve distinct queries without delimiter collisions. */
    val key: String get() = normalized().let { value ->
        "v2:" + JSONObject().put("site", value.site.id).put("kind", value.kind.name)
            .put("scope", value.scope.name).put("filters", value.filters.toJson()).toString()
    }
    val supportsAdvancedFilters: Boolean get() = site != RankingSite.BANGUMI || kind != SiteRankingKind.NEWEST
    val supportsMonth: Boolean get() = site.supportsMonth && filters.year != null
    val supportsStatus: Boolean get() = site.supportsStatus
    val usesBangumiSearch: Boolean get() = site == RankingSite.BANGUMI && kind != SiteRankingKind.NEWEST &&
        (kind == SiteRankingKind.POPULARITY || filters.hasSearchConditions)
    val availableKinds: List<SiteRankingKind> get() = site.kinds
    val availableScopes: List<SiteRankingScope> get() =
        if (usesBangumiSearch) listOf(SiteRankingScope.ALL) else site.scopes
    val scopeLabel: String get() = if (usesBangumiSearch) "漫画标签" else scope.label

    fun normalized(): SiteRankingSelection {
        val actualKind = kind.takeIf { it in site.kinds } ?: site.kinds.first()
        val advanced = site != RankingSite.BANGUMI || actualKind != SiteRankingKind.NEWEST
        val year = filters.year?.takeIf { it in 1900..3000 }
        val clean = filters.copy(
            year = year,
            month = filters.month?.takeIf { site.supportsMonth && year != null && it in 1..12 },
            query = if (advanced && site.supportsQuery) filters.query.trim().take(300) else "",
            genres = if (advanced && site.supportsGenres) filters.genres.intersect(
                SiteRankingGenre.entries.map { it.id }.toSet()) else emptySet(),
            status = filters.status?.takeIf { site.supportsStatus && SiteRankingStatus.entries.any { s -> s.id == it } },
            country = filters.country?.trim()?.uppercase()?.takeIf {
                site.supportsCountry && it.matches(Regex("[A-Z]{2}")) },
            minScore = filters.minScore?.takeIf { advanced && site.supportsMinScore && it in 1..10 },
            minVotes = filters.minVotes?.takeIf { advanced && site.supportsMinVotes && it > 0 },
        )
        val actualScope = if (site == RankingSite.BANGUMI && actualKind != SiteRankingKind.NEWEST &&
            (actualKind == SiteRankingKind.POPULARITY || clean.hasSearchConditions)) SiteRankingScope.ALL
            else scope.takeIf { it in site.scopes } ?: site.scopes.first()
        return copy(kind = actualKind, scope = actualScope, filters = clean)
    }

    fun valid(): Boolean = this == normalized()
    val activeFilterCount: Int get() = filters.let { f -> listOf(f.year != null, f.month != null,
        f.query.isNotBlank(), f.genres.isNotEmpty(), f.status != null, f.country != null,
        f.minScore != null, f.minVotes != null).count { it } }
    val filterSummary: String get() = buildList {
        filters.year?.let { add("${it}年") }
        filters.month?.let { add("${it}月") }
        if (filters.query.isNotBlank()) add("「${filters.query}」")
        val genres = SiteRankingGenre.entries.filter { it.id in filters.genres }.map { it.label }
        if (genres.isNotEmpty()) add(genres.joinToString(" / "))
        SiteRankingStatus.entries.firstOrNull { it.id == filters.status }?.let { add(it.label) }
        filters.country?.let { add(when (it) { "JP" -> "日本"; "KR" -> "韩国"; "CN" -> "中国"; "TW" -> "台湾"; else -> it }) }
        filters.minScore?.let { add("≥${it}分") }
        filters.minVotes?.let { add("≥${it}人评分") }
    }.joinToString(" · ")
    val explanation: String get() = (if (site == RankingSite.BANGUMI && filters.query.isNotBlank())
        "关键词按 Bangumi 的匹配程度排列，保留尚未进入评分榜的作品。清空关键词后恢复所选榜单排序。"
        else when (kind) {
        SiteRankingKind.SCORE -> if (site == RankingSite.BANGUMI)
            "保留 Bangumi 原站评分排名顺序。序号属于当前漫画范围，原始评分排名在作品资料中显示。"
            else "保留来源的评分排序。列表序号属于当前范围，作品的全站评分排名单独记录。"
        SiteRankingKind.POPULARITY -> "按加入该站书单的人数排列，不是阅读次数或评分人数。"
        SiteRankingKind.TRENDING -> "按 AniList 过去一小时的相关活跃排列，不是累计人气。"
        SiteRankingKind.FAVOURITES -> "按该站标记为最爱的人数排列，与加入书单人数分别统计。"
        SiteRankingKind.NEWEST -> "按作品首发日期由近到远排列，不是最新章节更新。"
    }) + (if (kind == SiteRankingKind.SCORE) if (site == RankingSite.ANILIST) " 100分制。" else " 10分制。" else "") +
        (if (usesBangumiSearch) " 按 Bangumi 的漫画标签检索书籍，不使用原站漫画分类或系列筛选；题材为用户标签。" else "") +
        (if (filters.year != null) " 年份指作品首发年，不表示当年仍在连载。" else "") +
        (if (site == RankingSite.MYANIMELIST)
            " 数据由现有 Tenrai 通道提供，可能滞后于原站；本地获取时间不代表原站更新时间。" else "")
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
    val publishedDate: String? = null,
) {
    val key: String get() = site.id + ":" + subjectId
}

data class SiteRankingViewState(
    val selected: SiteRankingSelection,
    val perSite: Map<RankingSite, SiteRankingSelection>,
)

data class SiteRankingEntry(val position: Int, val work: SiteRankedWork)
data class SiteRankingSnapshot(
    val selection: SiteRankingSelection,
    val entries: List<SiteRankingEntry>,
    val page: Int,
    val hasMore: Boolean,
    val fetchedAt: Long,
)
data class SiteRankingPage(val entries: List<SiteRankingEntry>, val hasMore: Boolean)

/** Only prioritize visible search relevance. This never grants canonical identity or writes an association. */
internal fun rankedCandidateRelevant(work: SiteRankedWork, query: String, manga: Manga): Boolean {
    if (SourceDiscoveryIdentity.titleMatches(work.manga, manga, work.manga.alternativeTitles)) return true
    val names = (listOf(work.manga.title, query) + work.manga.alternativeTitles).filter(String::isNotBlank)
    val candidate = (listOf(manga.title) + manga.alternativeTitles).map(::normalizedBodySourceTitle)
    if (names.map(::normalizedBodySourceTitle).any { full ->
            full.length >= 4 && candidate.any { target -> target.length >= 4 && (target.contains(full) || full.contains(target)) }
        }) return true
    val ignored = setOf("manga", "comic", "part", "volume", "book", "the", "with")
    val words = names.flatMap { Regex("[A-Za-z0-9]+").findAll(it).map { match -> match.value.lowercase() }.toList() }
        .filter { it.length >= 4 && it !in ignored }.toSet()
    val found = (listOf(manga.title) + manga.alternativeTitles).flatMap {
        Regex("[A-Za-z0-9]+").findAll(it).map { match -> match.value.lowercase() }.toList()
    }.toSet()
    return words.intersect(found).isNotEmpty()
}

/** Anonymous native rankings; the Copy ranking repository and its keys are not used here. */
class SiteRankingsRepository(context: PlatformContext) {
    private val cacheContext = context.applicationContext
    internal fun contentCachePolicy(): ContentCachePreferences = contentCachePreferences(cacheContext)
    private val http = ReviewProviderHttp(SourceHttp())
    private val directory = StorageFile(context.applicationContext.cacheDir, "site-rankings")
    private val links by lazy { preferences(context, "mangaflow_site_ranking_links") }
    private val diskLock = Mutex()
    private val linkLock = Mutex()
    private val identityLock = Mutex()
    private val identityCache = linkedMapOf<String, RankedIdentity>()
    private var cacheEpoch = 0L
    private val ownedFiles = AuxiliaryCacheFiles(directory) { CACHE_FILE.matches(it) }
    private val automaticCleanup get() = ExperiencePolicyStore.get(cacheContext).state.value.runtime.automaticCleanup

    internal val cacheOwner: AuxiliaryDiskCacheOwner = object : AuxiliaryDiskCacheOwner {
        override val ownerId = "rankings"
        override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) {
            diskLock.withLock { ownedFiles.snapshot() }
        }
        override suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? = withContext(Dispatchers.IO) {
            var changed = false
            try {
                diskLock.withLock {
                    if (ownedFiles.entry(expected.key, 0L, verifyBytes = true) != expected) return@withLock null
                    if (!canRemove()) return@withLock null
                    cacheEpoch++
                    changed = true
                    // User-selected source associations and view-state preferences are not cache files.
                    ownedFiles.removeReviewed(expected)
                }
            } finally {
                if (changed) StorageCacheStore.get(cacheContext).cacheWorkFinished()
            }
        }
    }

    private data class RankedIdentity(
        val titles: List<String>, val authors: List<String>, val ids: Map<String, String>, val fetchedAt: Long,
    )

    suspend fun readViewState(): SiteRankingViewState? = withContext(Dispatchers.IO) {
        runCatching {
            val value = links.getString("view-state-v1", null) ?: return@runCatching null
            val root = JSONObject(value)
            fun selection(record: JSONObject?): SiteRankingSelection? {
                if (record == null) return null
                val site = RankingSite.entries.firstOrNull { it.id == record.reviewText("site") } ?: return null
                val kind = SiteRankingKind.entries.firstOrNull { it.name == record.reviewText("kind") } ?: return null
                val scope = SiteRankingScope.entries.firstOrNull { it.name == record.reviewText("scope") } ?: return null
                val f = record.optJSONObject("filters")
                val filters = if (f == null) SiteRankingFilters() else SiteRankingFilters(
                    year = f.opt("year")?.toString()?.toIntOrNull(),
                    month = f.opt("month")?.toString()?.toIntOrNull(),
                    query = f.reviewText("query"), genres = strings(f.optJSONArray("genres")).toSet(),
                    status = f.reviewText("status").takeIf(String::isNotBlank),
                    country = f.reviewText("country").takeIf(String::isNotBlank),
                    minScore = f.opt("minScore")?.toString()?.toIntOrNull(),
                    minVotes = f.opt("minVotes")?.toString()?.toIntOrNull(),
                )
                return SiteRankingSelection(site, kind, scope, filters).normalized()
            }
            val selected = selection(root.optJSONObject("selected")) ?: return@runCatching null
            val perSite = root.optJSONArray("perSite")?.reviewObjects().orEmpty()
                .mapNotNull(::selection).associateBy { it.site }
            SiteRankingViewState(selected, perSite + (selected.site to selected))
        }.getOrNull()
    }

    suspend fun writeViewState(state: SiteRankingViewState) = withContext(Dispatchers.IO) {
        linkLock.withLock {
            fun encode(value: SiteRankingSelection): JSONObject =
                JSONObject().put("site", value.site.id).put("kind", value.kind.name).put("scope", value.scope.name)
                    .put("filters", value.filters.toJson())
            require(state.selected.valid())
            val perSite = JSONArray()
            state.perSite.values.filter { it.valid() }.distinctBy { it.site }.forEach { perSite.put(encode(it)) }
            check(links.edit().putString("view-state-v1", JSONObject().put("selected", encode(state.selected))
                .put("perSite", perSite).toString()).commit()) { "当前榜单选择未能保存。" }
        }
    }

    suspend fun page(selection: SiteRankingSelection, page: Int): SiteRankingPage = withContext(Dispatchers.IO) {
        require(selection.valid() && page in 0..199) { "榜单范围或页码无效。" }
        when (selection.site) {
            RankingSite.BANGUMI -> bangumi(selection, page)
            RankingSite.ANILIST -> aniList(selection, page)
            RankingSite.MYANIMELIST -> myAnimeList(selection, page)
        }
    }

    private suspend fun bangumi(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        if (selection.usesBangumiSearch) return bangumiSearch(selection, page)
        val url = "https://api.bgm.tv/v0/subjects".toHttpUrl().newBuilder()
            .addQueryParameter("type", "1").addQueryParameter("cat", "1001")
            .addQueryParameter("sort", if (selection.kind == SiteRankingKind.NEWEST) "date" else "rank")
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", (page * 20).toString()).apply {
                if (selection.scope != SiteRankingScope.ALL)
                    addQueryParameter("series", (selection.scope == SiteRankingScope.SERIES).toString())
                selection.filters.year?.let { addQueryParameter("year", it.toString()) }
                selection.filters.month?.let { addQueryParameter("month", it.toString()) }
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

    private suspend fun bangumiSearch(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val f = selection.filters
        val tags = listOf("漫画") + SiteRankingGenre.entries.filter { it.id in f.genres }.map { it.bangumiTag }
        val filter = JSONObject().put("type", JSONArray(listOf(1))).put("tag", JSONArray(tags.distinct()))
            .put("nsfw", false)
        f.year?.let { year ->
            val month = f.month ?: 1
            val start = "$year-${month.toString().padStart(2, '0')}-01"
            val end = if (f.month == null || month == 12) "${year + 1}-01-01"
                else "$year-${(month + 1).toString().padStart(2, '0')}-01"
            filter.put("air_date", JSONArray(listOf(">=$start", "<$end")))
        }
        f.minScore?.let { filter.put("rating", JSONArray(listOf(">=$it"))) }
        f.minVotes?.let { filter.put("rating_count", JSONArray(listOf(">=$it"))) }
        if (selection.kind == SiteRankingKind.SCORE && f.query.isBlank())
            filter.put("rank", JSONArray(listOf(">0")))
        val body = JSONObject().put("keyword", f.query)
            .put("sort", when {
                f.query.isNotBlank() -> "match"
                selection.kind == SiteRankingKind.POPULARITY -> "heat"
                else -> "rank"
            })
            .put("filter", filter)
        // Search currently caps pages at 20; its total is an estimate, not a precise matching-work count.
        val response = http.json("https://api.bgm.tv/v0/search/subjects?limit=20&offset=${page * 20}",
            "Bangumi 漫画检索", selection.site.home, body = body)
        val rows = response.optJSONArray("data") ?: invalid(selection.site)
        val entries = rankObjects(rows, selection.site).mapIndexedNotNull { index, row ->
            if (row.optBoolean("nsfw", false)) return@mapIndexedNotNull null
            SiteRankingEntry(page * 20 + index + 1, bangumiWork(row))
        }
        val total = response.opt("total")?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw SourceException("Bangumi 未返回有效的检索分页。", selection.site.home)
        val limit = response.optInt("limit", 20)
        if (limit != 20) throw SourceException("Bangumi 检索分页规则已变化，请重试。", selection.site.home)
        return SiteRankingPage(entries, rows.length() > 0 && (page + 1L) * 20 < total)
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
        val collection = row.optJSONObject("collection")
        val counts = listOf("wish", "collect", "doing", "on_hold", "dropped").map { collection?.longCount(it) }
        return work(RankingSite.BANGUMI, id, titles, authors, cover, row.reviewText("summary"),
            rating?.reviewScore("score", 10), 10, rating?.reviewCount("total"),
            popularity = counts.takeIf { values -> values.all { it != null } }?.sumOf { it ?: 0L },
            ratingRank = rating?.reviewCount("rank")?.takeIf { it > 0 },
            publishedDate = row.reviewText("date").takeIf(String::isNotBlank))
    }

    private suspend fun aniList(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val sort = when (selection.kind) {
            SiteRankingKind.SCORE -> "SCORE_DESC"
            SiteRankingKind.POPULARITY -> "POPULARITY_DESC"
            SiteRankingKind.TRENDING -> "TRENDING_DESC"
            SiteRankingKind.FAVOURITES -> "FAVOURITES_DESC"
            SiteRankingKind.NEWEST -> "START_DATE_DESC"
        }
        val formats = when (selection.scope) {
            SiteRankingScope.MANGA -> listOf("MANGA")
            SiteRankingScope.ONE_SHOT -> listOf("ONE_SHOT")
            else -> listOf("MANGA", "ONE_SHOT")
        }
        val variables = JSONObject().put("page", page + 1).put("sort", sort).put("formats", JSONArray(formats))
        val f = selection.filters
        if (f.query.isNotBlank()) variables.put("search", f.query)
        if (f.genres.isNotEmpty()) variables.put("genres", JSONArray(f.genres.sorted()))
        f.status?.let { variables.put("status", it) }
        f.country?.let { variables.put("country", it) }
        f.year?.let {
            // Fuzzy dates may contain a known year with unknown month/day (YYYY0000).
            variables.put("after", it * 10_000 - 1).put("before", (it + 1) * 10_000)
        }
        // AniList's predicate is strictly greater; the user-facing threshold is inclusive on a 10-point scale.
        f.minScore?.let { variables.put("minScore", it * 10 - 1) }
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
                extraIds = mal?.let { mapOf("myanimelist" to it) }.orEmpty(),
                publishedDate = aniListPublishedDate(row.optJSONObject("startDate")))
            SiteRankingEntry(page * 20 + index + 1, item)
        }
        val info = result.optJSONObject("pageInfo") ?: invalid(selection.site)
        if (!info.has("hasNextPage")) invalid(selection.site)
        return SiteRankingPage(entries, info.getBoolean("hasNextPage"))
    }

    private fun aniListPublishedDate(value: JSONObject?): String? {
        val year = value?.reviewCount("year")?.takeIf { it in 1..9999 } ?: return null
        val month = value.reviewCount("month")?.takeIf { it in 1..12 } ?: return year.toString()
        val prefix = "$year-${month.toString().padStart(2, '0')}"
        val day = value.reviewCount("day")?.takeIf { it in 1..31 } ?: return prefix
        return "$prefix-${day.toString().padStart(2, '0')}"
    }

    private suspend fun myAnimeList(selection: SiteRankingSelection, page: Int): SiteRankingPage {
        val type = when (selection.scope) {
            SiteRankingScope.MANHWA -> "manhwa"
            SiteRankingScope.MANHUA -> "manhua"
            SiteRankingScope.ONE_SHOT -> "oneshot"
            else -> "manga"
        }
        val f = selection.filters
        val search = f.query.isNotBlank() || selection.kind == SiteRankingKind.NEWEST
        val endpoint = if (search) "https://api.tenrai.org/v1/manga" else "https://api.tenrai.org/v1/top/manga"
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("page", (page + 1).toString()).addQueryParameter("limit", "20")
            .addQueryParameter("type", type).addQueryParameter("sfw", "true").apply {
                if (search) {
                    if (f.query.isNotBlank()) addQueryParameter("q", f.query)
                    addQueryParameter("order_by", when (selection.kind) {
                        SiteRankingKind.POPULARITY -> "members"
                        SiteRankingKind.FAVOURITES -> "favorites"
                        SiteRankingKind.NEWEST -> "start_date"
                        else -> "score"
                    })
                    addQueryParameter("sort", "desc")
                    addQueryParameter("nulls", if (selection.kind == SiteRankingKind.NEWEST) "exclude" else "last")
                } else {
                    // /top uses ascending native rank even for bypopularity/favorite.
                    addQueryParameter("sort", "asc")
                    when (selection.kind) {
                        SiteRankingKind.POPULARITY -> addQueryParameter("filter", "bypopularity")
                        SiteRankingKind.FAVOURITES -> addQueryParameter("filter", "favorite")
                        else -> Unit
                    }
                }
                f.year?.let { addQueryParameter("year", it.toString()) }
                SiteRankingStatus.entries.firstOrNull { it.id == f.status }?.let { addQueryParameter("status", it.malValue) }
                if (f.genres.isNotEmpty()) addQueryParameter("genres", SiteRankingGenre.entries
                    .filter { it.id in f.genres }.joinToString(",") { it.malId.toString() })
                f.minScore?.let { addQueryParameter("min_score", it.toString()) }
                f.minVotes?.let { addQueryParameter("min_scored_by", it.toString()) }
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
                    ratingRank = row.reviewCount("rank")?.takeIf { it > 0 },
                    publishedDate = row.optJSONObject("published")?.reviewText("from")?.take(10)
                        ?.takeIf(String::isNotBlank)))
        }
        val pagination = response.optJSONObject("pagination") ?: invalid(selection.site)
        if (!pagination.has("has_next_page")) invalid(selection.site)
        return SiteRankingPage(entries, pagination.getBoolean("has_next_page"))
    }

    suspend fun enrich(work: SiteRankedWork): SiteRankedWork = withContext(Dispatchers.IO) {
        if (work.site == RankingSite.BANGUMI) {
            if (work.manga.authors.isNotEmpty()) return@withContext work
            val response = http.json("https://api.bgm.tv/v0/subjects/" + work.subjectId,
                "Bangumi 作品资料", work.manga.sourceUrl)
            val updated = bangumiWork(response)
            if (updated.subjectId != work.subjectId) invalid(work.site)
            return@withContext updated
        }
        val cached = identityLock.withLock {
            identityCache[work.key]?.takeIf { wallTimeMillis() - it.fetchedAt in 0L until
                contentCachePolicy().freshMillis(ContentCacheKind.DETAILS) }
        }
        if (cached != null) return@withContext work.withIdentity(cached)
        // Rankings supply display names, often romanized only. Resolve the same native
        // IDs through existing metadata providers; never use a search spelling as proof.
        val evidence = supervisorScope {
            listOf(
                async { optionalIdentity { mappedIdentity(work) } },
                async { optionalIdentity { aniListIdentity(work) } },
            ).mapNotNull { it.await() }
        }
        if (evidence.isEmpty()) return@withContext work
        val compatible = evidence.filter { item ->
            work.manga.externalIds.none { (provider, id) -> item.ids[provider]?.let { it != id } == true }
        }
        val ids = work.manga.externalIds.toMutableMap()
        for (item in compatible) {
            if (item.ids.any { (provider, id) -> ids[provider]?.let { it != id } == true }) continue
            ids.putAll(item.ids)
        }
        val accepted = compatible.filter { item -> item.ids.all { (provider, id) -> ids[provider] == id } }
        if (accepted.isEmpty()) return@withContext work
        val combined = RankedIdentity(accepted.flatMap { it.titles }.distinct(),
            accepted.flatMap { it.authors }.distinct(), ids, wallTimeMillis())
        identityLock.withLock {
            if (identityCache.size >= 64 && work.key !in identityCache) identityCache.remove(identityCache.keys.first())
            identityCache[work.key] = combined
        }
        work.withIdentity(combined)
    }

    private suspend fun optionalIdentity(load: suspend () -> RankedIdentity?): RankedIdentity? = try {
        withTimeoutOrNull(5_000L) { load() }
    } catch (cancel: CancellationException) { throw cancel
    } catch (_: Exception) { null }

    private suspend fun mappedIdentity(work: SiteRankedWork): RankedIdentity? {
        val source = MangaBakaReviewProvider(http).fromKnownIds(work.manga.externalIds) ?: return null
        if (source.status != ReviewStatus.READY || source.aliases.isEmpty() ||
            work.manga.externalIds.none { (provider, id) -> source.externalIds[provider] == id }) return null
        return RankedIdentity(source.aliases, emptyList(), source.externalIds, wallTimeMillis())
    }

    private suspend fun aniListIdentity(work: SiteRankedWork): RankedIdentity? {
        if (work.site == RankingSite.ANILIST && work.manga.authors.isNotEmpty()) return null
        val mal = work.manga.externalIds["myanimelist"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val response = http.json("https://graphql.anilist.co", "漫画作品身份", "https://anilist.co",
            body = JSONObject().put("query", ANILIST_IDENTITY_QUERY)
                .put("variables", JSONObject().put("mal", mal)), minIntervalMillis = 2_100L)
        if ((response.optJSONArray("errors")?.length() ?: 0) > 0) return null
        val item = response.optJSONObject("data")?.optJSONObject("Media") ?: return null
        val id = item.reviewId("id").takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return null
        if (item.reviewId("idMal") != mal.toString() || item.reviewText("type") != "MANGA" ||
            item.reviewText("format") !in setOf("MANGA", "ONE_SHOT") || item.optBoolean("isAdult", false)) return null
        val title = item.optJSONObject("title") ?: return null
        val titles = (listOf(title.reviewText("native"), title.reviewText("romaji"), title.reviewText("english")) +
            strings(item.optJSONArray("synonyms"))).filter(String::isNotBlank).distinct()
        val authors = item.optJSONObject("staff")?.optJSONArray("edges")?.reviewObjects().orEmpty()
            .filter { edge -> edge.reviewText("role").lowercase().let { "story" in it || "art" in it || "author" in it } }
            .flatMap { edge -> edge.optJSONObject("node")?.optJSONObject("name")?.let {
                listOf(it.reviewText("native"), it.reviewText("full"))
            }.orEmpty() }.filter(String::isNotBlank).distinct()
        return RankedIdentity(titles, authors, mapOf("anilist" to id, "myanimelist" to mal.toString()), wallTimeMillis())
    }

    private fun SiteRankedWork.withIdentity(identity: RankedIdentity): SiteRankedWork {
        // Put real Han aliases inside the bounded reading-source search window. The
        // visible native ranking title, score and author row stay owned by that site.
        val names = (identity.titles + manga.alternativeTitles).filter(String::isNotBlank).distinct()
            .sortedByDescending { title -> title.any { it in '\u3400'..'\u9fff' } }
        return copy(manga = manga.copy(alternativeTitles = names.take(48),
            authorAliases = (manga.authorAliases + identity.authors).distinct().take(48),
            externalIds = manga.externalIds + identity.ids))
    }

    private fun work(site: RankingSite, id: String, titles: List<String>, authors: List<String>,
        cover: String, description: String, score: Double?, scale: Int, count: Int?,
        popularity: Long? = null, trending: Long? = null, favourites: Long? = null,
        ratingRank: Int? = null, extraIds: Map<String, String> = emptyMap(),
        publishedDate: String? = null): SiteRankedWork {
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
        return SiteRankedWork(site, id, manga, score, scale, count, popularity, trending, favourites, ratingRank,
            publishedDate?.takeIf { it.matches(Regex("[0-9]{4}(?:-[0-9]{2}){0,2}")) })
    }

    /** A native catalogue ID is never usable as a body route, even in a saved association. */
    fun acceptsSource(work: SiteRankedWork, manga: Manga): Boolean =
        manga.id.isNotBlank() && !manga.id.startsWith("catalog:") && manga.title.isNotBlank() &&
            manga.sourceId !in setOf("catalog", "local") &&
            runCatching { SourceKeys.decodeManga(manga.id).sourceId == manga.sourceId }.getOrDefault(false) &&
            !conflicts(work, manga)

    fun sameSourceResult(work: SiteRankedWork, selected: Manga, canonical: Manga): Boolean =
        acceptsSource(work, canonical) && selected.id == canonical.id && selected.sourceId == canonical.sourceId &&
            SourceDiscoveryIdentity.titleMatches(selected, canonical, selected.alternativeTitles) &&
            SourceDiscoveryIdentity.authorEvidence(selected, canonical) != SourceDiscoveryIdentity.AuthorEvidence.CONFLICT

    /** Keep old bytes, but an unverified fuzzy legacy link cannot silently become a direct reading entry. */
    suspend fun linked(work: SiteRankedWork): Manga? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = links.getString("link:" + work.key, null) ?: return@runCatching null
            val saved = JSONObject(raw)
            if (saved.getString("work") != work.key) return@runCatching null
            val manga = readManga(saved.getJSONObject("manga"))
            manga.takeIf {
                acceptsSource(work, it) && (saved.optInt("version", 1) == 2 && saved.optBoolean("identityChecked", false) ||
                    SourceDiscoveryIdentity.workMatches(work.manga, it, work.manga.alternativeTitles, sameChapterSystem = false))
            }
        }.getOrNull()
    }

    /** Only the actual canonical source result, checked against the selected row, can create a new link. */
    suspend fun rememberLink(work: SiteRankedWork, selected: Manga, canonical: Manga,
        stillSelected: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            linkLock.withLock {
                require(acceptsSource(work, selected) && sameSourceResult(work, selected, canonical)) {
                    "来源返回的作品与所选结果不一致，请重新选择。"
                }
                currentCoroutineContext().ensureActive()
                if (!stillSelected()) return@withLock false
                check(links.edit().putString("link:" + work.key,
                    JSONObject().put("version", 2).put("identityChecked", true).put("work", work.key)
                        .put("manga", canonical.toJson()).toString()).commit()) {
                    "阅读来源未能保存，请检查存储空间。"
                }
                true
            }
        }

    /** Remove this work association only; favorites, catalogues, downloads and progress are independent. */
    suspend fun forgetLink(work: SiteRankedWork, expectedMangaId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            linkLock.withLock {
                val key = "link:" + work.key
                val raw = links.getString(key, null) ?: return@withLock false
                if (expectedMangaId != null) {
                    val storedId = runCatching { readManga(JSONObject(raw).getJSONObject("manga")).id }.getOrNull()
                    if (storedId != expectedMangaId) return@withLock false
                }
                check(links.edit().remove(key).commit()) { "阅读关联未能移除，请重试。" }
                true
            }
        }

    fun conflicts(work: SiteRankedWork, manga: Manga): Boolean =
        work.manga.externalIds.any { (provider, id) -> manga.externalIds[provider]?.let { it != id } == true }

    suspend fun readCache(selection: SiteRankingSelection): SiteRankingSnapshot? = withContext(Dispatchers.IO) {
        diskLock.withLock {
            val policy = contentCachePolicy()
            if (!policy.enabled || !policy.storeOnDisk) return@withLock null
            runCatching {
                val file = cacheFile(selection)
                if (!file.isFile || file.length() !in 1..MAX_CACHE_BYTES) return@runCatching null
                if (StorageFile(file.path + ".bak").isFile) ownedFiles.changed(file.name)
                val root = JSONObject(StorageAtomicFile(file).openRead().bufferedReader().use { it.readText() })
                if (root.optInt("version") != 1 || root.getString("key") != selection.key) return@runCatching null
                val fetchedAt = root.getLong("fetchedAt")
                if (wallTimeMillis() - fetchedAt !in -300_000L..policy.retentionMillis) return@runCatching null
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
                            item.longCount("trending"), item.longCount("favourites"), item.reviewCount("ratingRank"),
                            item.reviewText("publishedDate").takeIf { it.matches(Regex("[0-9]{4}(?:-[0-9]{2}){0,2}")) }))
                }
                require(entries.distinctBy { it.work.key }.size == entries.size)
                SiteRankingSnapshot(selection, entries, root.getInt("page").also { require(it in 0..199) },
                    root.getBoolean("hasMore"), fetchedAt).takeIf {
                        contentCachePolicy().let { current -> current.enabled && current.storeOnDisk } }
            }.getOrNull()
        }
    }

    suspend fun writeCache(snapshot: SiteRankingSnapshot, epoch: Long) = withContext(Dispatchers.IO) {
        if (snapshot.entries.size > 600) return@withContext
        diskLock.withLock {
            if (epoch != cacheEpoch || !contentCachePolicy().let { it.enabled && it.storeOnDisk }) return@withLock
            val rows = JSONArray()
            snapshot.entries.forEach { entry ->
                val w = entry.work
                val value = JSONObject().put("subjectId", w.subjectId).put("manga", w.manga.toJson())
                w.score?.let { value.put("score", it) }; w.ratingCount?.let { value.put("ratingCount", it) }
                w.popularity?.let { value.put("popularity", it) }; w.trending?.let { value.put("trending", it) }
                w.favourites?.let { value.put("favourites", it) }; w.ratingRank?.let { value.put("ratingRank", it) }
                w.publishedDate?.let { value.put("publishedDate", it) }
                rows.put(JSONObject().put("position", entry.position).put("work", value))
            }
            val bytes = JSONObject().put("version", 1).put("key", snapshot.selection.key)
                .put("page", snapshot.page).put("hasMore", snapshot.hasMore).put("fetchedAt", snapshot.fetchedAt)
                .put("entries", rows).toString().encodeToByteArray()
            if (bytes.size > MAX_CACHE_BYTES) return@withLock
            directory.mkdirs()
            val file = StorageAtomicFile(cacheFile(snapshot.selection))
            ownedFiles.changed(file.baseFile.name)
            val output = file.startWrite()
            try {
                output.write(bytes)
                if (!contentCachePolicy().let { it.enabled && it.storeOnDisk }) {
                    file.failWrite(output)
                    return@withLock
                }
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            try {
                run {
                    var total = 0L
                    ownedFiles.snapshot().sortedByDescending { it.modifiedAt }.forEach { item ->
                        val policy = contentCachePolicy()
                        total += item.bytes
                        if (total > policy.snapshotDiskMiB * 1_048_576L ||
                            automaticCleanup && wallTimeMillis() - item.modifiedAt > policy.retentionMillis) ownedFiles.removeReviewed(item)
                    }
                }
            } finally { StorageCacheStore.get(cacheContext).cacheWorkFinished() }
        }
    }

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        diskLock.withLock { directory.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L } }
    }
    suspend fun cacheGeneration(): Long = diskLock.withLock { cacheEpoch }
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        identityLock.withLock { identityCache.clear() }
        try {
            diskLock.withLock {
                cacheEpoch++
                val failures = directory.listFiles().orEmpty().count {
                    if (!it.isFile) false else {
                        ownedFiles.keyForFile(it.name)?.let(ownedFiles::changed)
                        !it.delete()
                    }
                }
                check(failures == 0) { "部分榜单缓存未能清理，请重试。" }
            }
        } finally { StorageCacheStore.get(cacheContext).cacheWorkFinished() }
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
        val CACHE_FILE = Regex("[a-f0-9]{64}\\.json")
        const val MAX_CACHE_BYTES = 1024L * 1024
        const val ANILIST_IDENTITY_QUERY = """
            query MangaFlowRankingIdentity(${'$'}mal: Int!) {
              Media(idMal: ${'$'}mal, type: MANGA) {
                id idMal type format isAdult title { native romaji english } synonyms
                staff(perPage: 12) { edges { role node { name { native full } } } }
              }
            }
        """
        const val ANILIST_QUERY = """
            query MangaFlowRankings(${'$'}page: Int!, ${'$'}sort: MediaSort!, ${'$'}formats: [MediaFormat]!,
              ${'$'}search: String, ${'$'}genres: [String], ${'$'}status: MediaStatus, ${'$'}country: CountryCode,
              ${'$'}after: FuzzyDateInt, ${'$'}before: FuzzyDateInt, ${'$'}minScore: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                pageInfo { hasNextPage }
                media(type: MANGA, format_in: ${'$'}formats, isAdult: false, sort: [${'$'}sort, ID],
                  search: ${'$'}search, genre_in: ${'$'}genres, status: ${'$'}status, countryOfOrigin: ${'$'}country,
                  startDate_greater: ${'$'}after, startDate_lesser: ${'$'}before, averageScore_greater: ${'$'}minScore) {
                  id idMal type format isAdult
                  startDate { year month day }
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
