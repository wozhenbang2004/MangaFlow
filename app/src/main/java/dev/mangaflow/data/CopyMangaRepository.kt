package dev.mangaflow.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Host changes are explicit configuration, never a retry across mirrors after a refusal. */
data class CopyMangaConfig(
    val websiteOrigin: String = "https://www.copy3000.com",
    val apiOrigin: String = "https://api.copy202601.com",
    val trustedImageDomains: Set<String> = setOf("mangafunb.fun"),
)

class CopyMangaRepository(
    private val config: CopyMangaConfig = CopyMangaConfig(),
    private val identityProvider: () -> CopyAnonymousIdentity = { CopyAnonymousIdentity.create() },
) : MangaRepository {
    private val webOrigin = checkedOrigin(config.websiteOrigin)
    private val apiOrigin = checkedOrigin(config.apiOrigin)
    private val api = SourceHttp()
    private val anonymousIdentity by lazy(identityProvider)
    private val imageDomains = config.trustedImageDomains.map { it.lowercase().trim('.') }.toSet()
    private val catalogCache = LinkedHashMap<String, CachedCatalog>()
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageRequests = mutableMapOf<PageKey, SharedPages>()
    private val pageCache = LinkedHashMap<PageKey, CachedPages>()
    private val coverCache = LinkedHashMap<PageKey, CachedCover>()

    init {
        require(imageDomains.isNotEmpty() && imageDomains.all { it.matches(Regex("[a-z0-9.-]+")) && '.' in it })
    }

    /** Discover uses 21 rows and search uses 20; the caller passes a zero-based page index. */
    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..950) { "漫画分页超出范围。" }
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comics")
            .addQueryParameter("free_type", "1")
            .addQueryParameter("limit", "21")
            .addQueryParameter("offset", (page * 21).toString())
            .addQueryParameter("ordering", "-popular")
            .addQueryParameter("platform", "3").build()
        catalogPage(apiJson(url, "漫画发现"), page * 21, 21)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        keywordSearch(query, SearchTarget.TITLE, page)
    }

    private suspend fun keywordSearch(query: String, target: SearchTarget, page: Int): MangaPage {
        val keyword = query.trim()
        require(keyword.length in 1..120) { "请输入 1 至 120 个字符。" }
        require(page in 0..1000) { "漫画分页超出范围。" }
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/search/comic")
            .addQueryParameter("q", keyword)
            .addQueryParameter("q_type", if (target == SearchTarget.AUTHOR) "author" else "name")
            .addQueryParameter("ordering", "-popular")
            .addQueryParameter("free_type", "1")
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", (page * 20).toString())
            .addQueryParameter("platform", "3").build()
        return catalogPage(apiJson(url, "漫画搜索"), page * 20, 20)
    }

    /**
     * The source exposes one theme filter, not an evidenced multi-theme intersection.
     * Continue its real cursor and intersect full source metadata, never only UI rows.
     * A sparse intersection scans at most two source pages per explicit load.
     */
    suspend fun search(query: String, target: SearchTarget, selectedTags: List<MangaTag>, page: Int): MangaPage =
        withContext(Dispatchers.IO) {
            val tags = selectedTags.distinctBy(MangaTag::path).sortedBy(MangaTag::path)
            tags.forEach { segment(it.path) }
            val keyword = query.trim().takeUnless { target == SearchTarget.TAG }.orEmpty()
            if (keyword.isBlank() && tags.isEmpty()) return@withContext MangaPage(emptyList(), false)
            val keywordSource = keyword.isNotBlank()
            val needsIntersection = tags.isNotEmpty() && (keywordSource || tags.size > 1)
            val permits = Semaphore(4)
            val matched = mutableListOf<Manga>()
            var cursor = page
            var more = true
            repeat(if (needsIntersection) 2 else 1) {
                if (!more || matched.size >= 20) return@repeat
                currentCoroutineContext().ensureActive()
                val result = if (keywordSource) keywordSearch(keyword, target, cursor)
                    else themePage(tags.first().path, cursor)
                val candidates = if (!needsIntersection) result.items else coroutineScope {
                    result.items.map { manga -> async {
                        val tagged = if (manga.tagPaths.isNotEmpty()) manga else permits.withPermit {
                            val comic = comicMetadata(segment(manga.id)).getJSONObject("comic")
                            manga.copy(tags = themePaths(comic).keys.toList(), tagPaths = themePaths(comic))
                        }
                        tagged.takeIf { candidate -> tags.all { it.path in candidate.tagPaths.values } }
                    } }.mapNotNull { it.await() }
                }
                matched += candidates
                cursor++
                more = result.hasMore
            }
            MangaPage(matched.distinctBy(Manga::id), more, nextPage = cursor)
        }

    private suspend fun themePage(path: String, page: Int): MangaPage {
        require(page in 0..950) { "漫画分页超出范围。" }
        val offset = page * 21
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comics")
            .addQueryParameter("theme", segment(path)).addQueryParameter("ordering", "-popular")
            .addQueryParameter("free_type", "1").addQueryParameter("limit", "21")
            .addQueryParameter("offset", "$offset").addQueryParameter("platform", "3").build()
        return catalogPage(apiJson(url, "标签漫画"), offset, 21)
    }

    private fun themePaths(comic: JSONObject): Map<String, String> = comic.optJSONArray("theme")?.objects().orEmpty()
        .mapNotNull { row ->
            val name = row.text("name")
            val path = runCatching { segment(row.text("path_word")) }.getOrNull()
            if (name.isBlank() || path == null) null else name to path
        }.groupBy({ it.first }, { it.second }).mapNotNull { (name, paths) ->
            paths.distinct().singleOrNull()?.let { name to it }
        }.toMap()

    suspend fun searchTagsFor(manga: Manga): List<MangaTag> = withContext(Dispatchers.IO) {
        val paths = manga.tagPaths.ifEmpty {
            themePaths(comicMetadata(segment(manga.id)).getJSONObject("comic"))
        }
        paths.map { (name, path) -> MangaTag(name, path) }
    }

    /** Venera a04e055: /api/v3/comics?author=<author.path_word>, not a title search. */
    suspend fun byAuthor(manga: Manga, author: String, page: Int = 0): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..950)
        val key = manga.authorPaths[author] ?: run {
            val comic = comicMetadata(segment(manga.id)).optJSONObject("comic")
                ?: throw SourceException("暂时无法确认作者资料，请稍后重试。")
            authorPaths(comic)[author] ?: throw SourceException("来源没有返回这位作者的唯一索引，请稍后重试。")
        }
        val offset = page * 21
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comics")
            .addQueryParameter("author", segment(key)).addQueryParameter("ordering", "-popular")
            .addQueryParameter("free_type", "1").addQueryParameter("limit", "21")
            .addQueryParameter("offset", "$offset").addQueryParameter("platform", "3").build()
        catalogPage(apiJson(url, "作者作品"), offset, 21)
    }

    private fun authorPaths(comic: JSONObject): Map<String, String> = comic.optJSONArray("author")?.objects().orEmpty()
        .mapNotNull { row ->
            val name = row.text("name")
            val path = runCatching { segment(row.text("path_word")) }.getOrNull()
            if (name.isBlank() || path == null) null else name to path
        }.groupBy({ it.first }, { it.second }).mapNotNull { (name, paths) ->
            paths.distinct().singleOrNull()?.let { name to it }
        }.toMap()

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        // An explicit refresh always obtains a new public directory.
        readCatalog(segment(mangaId)).detail
    }

    suspend fun detail(mangaId: String, onMetadata: (Manga) -> Unit): MangaDetail = withContext(Dispatchers.IO) {
        readCatalog(segment(mangaId), onMetadata).detail
    }

    /** A reading session may reuse a recent directory without refreshing it. */
    fun cachedDetail(mangaId: String): MangaDetail? {
        segment(mangaId)
        return synchronized(catalogCache) {
            catalogCache[mangaId]?.takeIf { System.currentTimeMillis() - it.fetchedAt in 0L until 10 * 60_000L }
                ?.detail
        }
    }

    /** Public metadata supplies the identifier needed by the separate account API. */
    suspend fun comicUuid(mangaId: String): String = withContext(Dispatchers.IO) {
        segment(mangaId)
        val cached = synchronized(catalogCache) { catalogCache[mangaId] }
        if (cached?.comicUuid != null && System.currentTimeMillis() - cached.fetchedAt < 10 * 60_000) {
            return@withContext cached.comicUuid
        }
        // Account operations need only this public identity. A broken/empty
        // chapter directory must not prevent removing a cloud favorite.
        val data = comicMetadata(mangaId)
        uuid(data.getJSONObject("comic").requiredText("uuid"), "漫画")
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        currentCoroutineContext().ensureActive()
        segment(mangaId)
        val id = uuid(chapterId, "章节")
        val key = PageKey(mangaId, id)
        val request = synchronized(pageRequests) {
            val now = System.nanoTime()
            pageCache.entries.removeAll { now - it.value.fetchedAt >= PAGE_CACHE_NANOS }
            if (forceRefresh) pageCache.remove(key)
            else pageCache.remove(key)?.let { cached ->
                pageCache[key] = cached
                return cached.pages
            }
            // Reader, download preparation and the one next-chapter prefetch
            // share the same live request, including its failure. A refresh may
            // join an unfinished network fetch, but never a completed result.
            val shared = pageRequests[key]?.takeUnless { it.result.isCompleted } ?: SharedPages(
                pageScope.async(start = CoroutineStart.LAZY) {
                    val loaded = readPages(mangaId, id)
                    val requestContext = currentCoroutineContext()
                    synchronized(pageRequests) {
                        requestContext.ensureActive()
                        if (pageRequests[key]?.result === requestContext[Job]) {
                            pageCache.remove(key)
                            pageCache[key] = CachedPages(loaded, System.nanoTime())
                            while (pageCache.size > 4) pageCache.remove(pageCache.keys.first())
                        }
                    }
                    loaded
                },
            ).also { pageRequests[key] = it }
            shared.readers++
            shared
        }
        try {
            request.result.start()
            return request.result.await()
        } finally {
            synchronized(pageRequests) {
                request.readers--
                if (request.readers == 0) {
                    if (pageRequests[key] === request) pageRequests.remove(key)
                    // Leaving a screen cancels unused work. It must not cancel
                    // a fetch that a reader or download is still awaiting.
                    if (!request.result.isCompleted) request.result.cancel()
                }
            }
        }
    }

    /** A volume preview retains one original page, without retaining every volume's manifest. */
    suspend fun coverPage(mangaId: String, chapterId: String, forceRefresh: Boolean = false): Page {
        currentCoroutineContext().ensureActive()
        segment(mangaId)
        val key = PageKey(mangaId, uuid(chapterId, "章节"))
        synchronized(pageRequests) {
            val now = System.nanoTime()
            coverCache.entries.removeAll { now - it.value.fetchedAt >= PAGE_CACHE_NANOS }
            if (forceRefresh) coverCache.remove(key)
            else coverCache.remove(key)?.let { cached ->
                coverCache[key] = cached
                return cached.page
            }
        }
        val page = pages(mangaId, chapterId, forceRefresh).firstOrNull()
            ?: throw SourceException("这一卷暂未提供封面页。", comicUrl(mangaId))
        currentCoroutineContext().ensureActive()
        synchronized(pageRequests) {
            coverCache[key] = CachedCover(page, System.nanoTime())
            while (coverCache.size > 64) coverCache.remove(coverCache.keys.first())
        }
        return page
    }

    private suspend fun readPages(mangaId: String, id: String): List<Page> {
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comic").addPathSegment(mangaId)
            .addPathSegment("chapter2").addPathSegment(id).build()
        val data = apiJson(url, "章节内容")
        demandReadingAccess(data)
        val comic = data.optJSONObject("comic") ?: throw SourceException("章节缺少漫画归属信息。", comicUrl(mangaId))
        val chapter = data.optJSONObject("chapter") ?: throw SourceException("来源未返回章节正文。", comicUrl(mangaId))
        demandPublic(comic)
        demandPublic(chapter)
        if (comic.requiredText("path_word") != mangaId) {
            throw SourceException("正文返回的漫画代号与当前漫画不一致，请刷新详情后重试。", comicUrl(mangaId))
        }
        if (uuid(chapter.requiredText("uuid"), "章节") != id) {
            throw SourceException("正文返回的章节编号与所选章节不一致，请刷新目录后重试。", comicUrl(mangaId))
        }
        val comicUuid = uuid(comic.requiredText("uuid"), "漫画")
        if (uuid(chapter.requiredText("comic_id"), "漫画") != comicUuid) {
            throw SourceException("正文的章节归属与返回的漫画编号不一致，请稍后重试。", comicUrl(mangaId))
        }
        val catalogUuid = cachedDetail(mangaId)?.manga?.sourceUuid?.takeIf(String::isNotBlank)
        if (catalogUuid != null && catalogUuid != comicUuid) {
            throw SourceException("正文的漫画编号与已加载目录不一致，请刷新详情后重试。", comicUrl(mangaId))
        }
        // The returned comic path and both UUID relationships establish ownership.
        // chapter.comic_path_word repeats a mutable path; it is not another identity.
        // Do not reject an otherwise verified chapter solely for that duplicate text.
        val contents = chapter.optJSONArray("contents") ?: throw SourceException("来源未返回章节图片。", comicUrl(mangaId))
        val words = chapter.optJSONArray("words") ?: throw SourceException("来源未返回图片页序。", comicUrl(mangaId))
        if (contents.length() !in 1..2000 || words.length() != contents.length() ||
            chapter.optInt("size", -1) != contents.length()) {
            throw SourceException("来源返回的章节图片为空或不完整，请稍后重试。", comicUrl(mangaId))
        }
        // App contents are shuffled. Each words entry belongs to the image at
        // the same array position; sort the pair before assigning reader pages.
        val seen = hashSetOf<Int>()
        return List(contents.length()) { index ->
            currentCoroutineContext().ensureActive()
            val order = words.optInt(index, -1)
            if (order < 0 || !seen.add(order)) throw SourceException("章节图片页序无效。", comicUrl(mangaId))
            val item = contents.optJSONObject(index) ?: throw SourceException("章节图片清单不完整。", comicUrl(mangaId))
            val asset = imageUrl(item.requiredText("url"))
            if (!asset.encodedPath.contains("/$mangaId/")) {
                throw SourceException("来源返回了阅读提示图，未将它当作漫画正文。", comicUrl(mangaId))
            }
            order to asset.toString()
        }.sortedBy { it.first }.mapIndexed { index, (_, asset) -> Page(index, asset) }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        segment(mangaId)
        val referer = if (chapterId.isBlank()) comicUrl(mangaId) else webOrigin.newBuilder()
            .addPathSegment("comic").addPathSegment(mangaId)
            .addPathSegment("chapter").addPathSegment(uuid(chapterId, "章节")).build().toString()
        return mapOf("User-Agent" to "Mozilla/5.0", "Referer" to referer)
    }

    private suspend fun apiJson(url: HttpUrl, label: String): JSONObject {
        val target = url.newBuilder().setQueryParameter("platform", "3")
            .setQueryParameter("in_mainland", "false").setQueryParameter("request_id", "").build()
        val request = Request.Builder().url(target).apply {
            anonymousIdentity.headers().forEach { (name, value) -> header(name, value) }
        }.build()
        val result = envelope(api.request(request, label, webOrigin.toString()), target, label)
        return result as? JSONObject ?: throw SourceException("$label 返回了空数据或不完整的数据结构。", webOrigin.toString())
    }

    private suspend fun catalogPage(data: JSONObject, offset: Int, size: Int): MangaPage {
        val list = data.optJSONArray("list") ?: throw SourceException("漫画列表格式已变化。", webOrigin.toString())
        if (list.length() > size) throw SourceException("漫画列表分页异常。", webOrigin.toString())
        val items = mutableListOf<Manga>()
        var malformed = 0
        for (index in 0 until list.length()) {
            currentCoroutineContext().ensureActive()
            val row = list.optJSONObject(index)
            if (row == null) { malformed++; continue }
            val comic = row.optJSONObject("comic") ?: row
            if (restricted(row) || restricted(comic)) continue
            try {
                val id = segment(comic.requiredText("path_word"))
                val title = comic.requiredText("name")
                val authors = comic.optJSONArray("author")?.objects()?.mapNotNull { it.text("name").takeIf(String::isNotBlank) }.orEmpty()
                val cover = optionalCover(comic.text("cover"))
                items += Manga(
                    id = id,
                    title = title,
                    coverUrl = cover,
                    authors = authors,
                    authorPaths = authorPaths(comic),
                    tags = comic.optJSONArray("theme")?.objects()?.mapNotNull { it.text("name").takeIf(String::isNotBlank) }.orEmpty(),
                    tagPaths = themePaths(comic),
                    latestChapter = comic.optJSONObject("last_chapter")?.text("name").orEmpty(),
                    sourceUrl = comicUrl(id),
                    totalPopularity = comic.opt("popular")?.toString()?.toLongOrNull()?.takeIf { it >= 0 },
                )
            } catch (_: IllegalArgumentException) { malformed++ }
              catch (_: SourceException) { malformed++ }
        }
        if (list.length() > 0 && malformed == list.length()) throw SourceException("来源返回了无法识别的漫画列表。", webOrigin.toString())
        val next = offset + list.length()
        val total = data.optInt("total", -1)
        return MangaPage(items.distinctBy(Manga::id), list.length() > 0 && if (total >= 0) next < total else list.length() == size)
    }

    private suspend fun comicMetadata(mangaId: String): JSONObject {
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comic2").addPathSegment(mangaId).build()
        val data = apiJson(url, "漫画详情")
        val comic = data.optJSONObject("comic") ?: throw SourceException("来源未返回漫画资料。", comicUrl(mangaId))
        if (comic.requiredText("path_word") != mangaId) {
            throw SourceException("来源返回的漫画与请求不匹配。", comicUrl(mangaId))
        }
        uuid(comic.requiredText("uuid"), "漫画")
        return data
    }

    private suspend fun readCatalog(mangaId: String, onMetadata: ((Manga) -> Unit)? = null): CachedCatalog {
        val data = comicMetadata(mangaId)
        val comic = data.getJSONObject("comic")
        demandReadingAccess(data)
        demandPublic(comic)
        val comicUuid = uuid(comic.requiredText("uuid"), "漫画")
        val manga = Manga(
            id = mangaId,
            title = comic.requiredText("name"),
            coverUrl = optionalCover(comic.text("cover")),
            authors = comic.optJSONArray("author")?.objects()?.mapNotNull { it.text("name").takeIf(String::isNotBlank) }.orEmpty(),
            authorPaths = authorPaths(comic),
            tagPaths = themePaths(comic),
            description = comic.text("brief"),
            status = comic.optJSONObject("status")?.text("display").orEmpty(),
            tags = comic.optJSONArray("theme")?.objects()?.mapNotNull { it.text("name").takeIf(String::isNotBlank) }.orEmpty(),
            latestChapter = comic.optJSONObject("last_chapter")?.text("name").orEmpty(),
            sourceUrl = comicUrl(mangaId),
            sourceUuid = comicUuid,
            popularity = comic.optLong("popular", -1).takeIf { it >= 0 },
            totalPopularity = comic.optLong("popular", -1).takeIf { it >= 0 },
        )
        currentCoroutineContext().ensureActive()
        onMetadata?.invoke(manga)
        val groups = data.optJSONObject("groups") ?: throw SourceException("来源目录缺少章节分组。", manga.sourceUrl)
        if (groups.length() !in 1..32) throw SourceException("来源目录未返回有效分组。", manga.sourceUrl)
        val chapters = mutableListOf<Chapter>()
        val seen = hashSetOf<String>()
        val keys = groups.keys().asSequence().toList().sortedBy { if (it == "default") 0 else 1 }
        for (groupId in keys) {
            currentCoroutineContext().ensureActive()
            segment(groupId)
            val group = groups.optJSONObject(groupId) ?: throw SourceException("章节分组数据不完整。", manga.sourceUrl)
            if (group.requiredText("path_word") != groupId) throw SourceException("章节分组标识不一致。", manga.sourceUrl)
            val groupName = group.text("name").ifBlank { if (groupId == "default") "正篇" else groupId }
            var total = group.optInt("count", -1)
            if (total !in 0..20_000) throw SourceException("章节数量无效。", manga.sourceUrl)
            var offset = 0
            while (offset < total) {
                currentCoroutineContext().ensureActive()
                val url = apiOrigin.newBuilder().addPathSegments("api/v3/comic").addPathSegment(mangaId)
                    .addPathSegment("group").addPathSegment(groupId).addPathSegment("chapters")
                    .addQueryParameter("limit", "100").addQueryParameter("offset", offset.toString()).build()
                val page = apiJson(url, "章节目录")
                demandPublic(page)
                val rows = page.optJSONArray("list") ?: throw SourceException("来源未返回章节清单。", manga.sourceUrl)
                val pageTotal = page.optInt("total", -1)
                if (page.optInt("offset", -1) != offset || pageTotal !in 1..20_000 ||
                    rows.length() !in 1..100 || offset + rows.length() > pageTotal ||
                    chapters.size + rows.length() > 20_000) {
                    throw SourceException("来源章节目录为空或不完整，请稍后刷新。", manga.sourceUrl)
                }
                total = maxOf(total, pageTotal)
                for (index in 0 until rows.length()) {
                    currentCoroutineContext().ensureActive()
                    val row = rows.optJSONObject(index) ?: throw SourceException("章节数据缺失。", manga.sourceUrl)
                    val id = uuid(row.requiredText("uuid"), "章节")
                    if (row.requiredText("comic_path_word") != mangaId ||
                        row.requiredText("group_path_word") != groupId ||
                        uuid(row.requiredText("comic_id"), "漫画") != comicUuid || !seen.add(id)) {
                        throw SourceException("来源章节目录归属不一致或出现重复章节。", manga.sourceUrl)
                    }
                    chapters += Chapter(id, row.requiredText("name"), chapters.size, groupName)
                }
                offset += rows.length()
            }
        }
        if (chapters.isEmpty()) throw SourceException("来源暂未返回可读章节，请稍后刷新或查看原站提示。", manga.sourceUrl)
        currentCoroutineContext().ensureActive()
        val catalog = CachedCatalog(MangaDetail(manga, chapters), comicUuid, System.currentTimeMillis())
        synchronized(catalogCache) {
            catalogCache.remove(mangaId)
            catalogCache[mangaId] = catalog
            while (catalogCache.size > 24) catalogCache.remove(catalogCache.keys.first())
        }
        return catalog
    }

    private fun envelope(response: SourceResponse, url: HttpUrl, label: String): Any {
        val json = try { JSONObject(response.text) } catch (error: JSONException) {
            throw SourceException("$label 响应格式已变化，请查看原站提示。", webOrigin.toString(), cause = error)
        }
        val code = json.optInt("code", -1)
        if (code in setOf(209, 210, 429)) {
            api.rememberPause(url, response.retryAfterSeconds)
            throw SourceException("$label 暂时被来源限制（$code），请稍后重试或查看原站。", webOrigin.toString(), response.retryAfterSeconds)
        }
        if (code == 401 || code == 403) throw SourceException("$label 被来源拒绝，请查看原站提示。", webOrigin.toString())
        if (code != 200) throw SourceException("$label 暂时不可用（业务码 $code）。", webOrigin.toString())
        return json.opt("results")?.takeUnless { it == JSONObject.NULL }
            ?: throw SourceException("$label 未返回数据，请稍后重试或查看原站。", webOrigin.toString())
    }

    private fun imageUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw SourceException("来源图片地址无效。")
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            imageDomains.none { url.host == it || url.host.endsWith(".$it") }) {
            throw SourceException("图片来自尚未配置的 CDN，请更新来源配置。", webOrigin.toString())
        }
        return url
    }

    private fun optionalCover(value: String): String = if (value.isBlank()) "" else {
        // A broken decorative image must not remove a valid search result or
        // block a valid chapter directory. Reader assets keep strict validation.
        try { imageUrl(value).toString() } catch (_: SourceException) { "" }
    }

    private fun comicUrl(id: String) = webOrigin.newBuilder().addPathSegment("comic").addPathSegment(segment(id)).build().toString()
    private fun demandReadingAccess(result: JSONObject) {
        val banned = result.opt("is_banned") as? Boolean
        val locked = result.opt("is_lock") as? Boolean
        if (banned == null || locked == null) {
            throw SourceException("来源未返回可确认的阅读状态，请稍后重试。", webOrigin.toString())
        }
        if (banned || locked) throw SourceException("该内容已下架、锁定或需要授权，请查看原站。", webOrigin.toString())
    }
    private fun demandPublic(value: JSONObject) {
        if (restricted(value)) throw SourceException("该内容已下架、锁定或需要授权，请查看原站。", webOrigin.toString())
    }
    private fun restricted(value: JSONObject): Boolean {
        for (key in listOf("is_banned", "is_lock", "is_vip", "b_404", "b_hidden", "ban_ip", "vip", "locked", "is_paid")) {
            val flag = value.opt(key)
            if (flag == true || flag is Number && flag.toInt() != 0) return true
        }
        // comic.ban is numeric metadata, not the App result's is_banned flag.
        // A real free, unlocked comic can have ban=2; the actual catalog and
        // chapter response must explicitly permit reading above.
        return value.optJSONObject("free_type")?.let { it.optInt("value", 1) != 1 } ?: false
    }
    private fun JSONObject.text(key: String) = (opt(key) as? String).orEmpty().trim()
    private fun JSONObject.requiredText(key: String) = text(key).takeIf(String::isNotBlank)
        ?: throw SourceException("来源数据缺少必要字段（$key）。", webOrigin.toString())
    private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "漫画标识无效。" }
        return value
    }
    private fun uuid(value: String, label: String): String {
        if (!value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            throw SourceException("来源 $label 标识无效。", webOrigin.toString())
        }
        val id = UUID.fromString(value)
        if (id.mostSignificantBits == 0L && id.leastSignificantBits == 0L) throw SourceException("来源 $label 标识为空。")
        return id.toString()
    }
    private fun checkedOrigin(value: String): HttpUrl {
        val url = value.toHttpUrl()
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) { "来源需要完整的 HTTPS 域名。" }
        return url
    }
    private data class CachedCatalog(val detail: MangaDetail, val comicUuid: String?, val fetchedAt: Long)
    private data class PageKey(val mangaId: String, val chapterId: String)
    private data class CachedPages(val pages: List<Page>, val fetchedAt: Long)
    private data class CachedCover(val page: Page, val fetchedAt: Long)
    private class SharedPages(val result: Deferred<List<Page>>, var readers: Int = 0)

    private companion object {
        const val PAGE_CACHE_NANOS = 3 * 60_000_000_000L
    }
}
