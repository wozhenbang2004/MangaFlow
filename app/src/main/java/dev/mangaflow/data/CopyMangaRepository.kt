package dev.mangaflow.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Host changes are explicit configuration, never a retry across mirrors after a refusal. */
data class CopyMangaConfig(
    val websiteOrigin: String = "https://www.copy3000.com",
    val apiOrigin: String = "https://api.copy202601.com",
    val trustedImageDomains: Set<String> = setOf("mangafunb.fun"),
)

class CopyMangaRepository(private val config: CopyMangaConfig = CopyMangaConfig()) : MangaRepository {
    private val webOrigin = checkedOrigin(config.websiteOrigin)
    private val apiOrigin = checkedOrigin(config.apiOrigin)
    private val api = SourceHttp()
    private val websiteClient = sourceClient()
    private val imageDomains = config.trustedImageDomains.map { it.lowercase().trim('.') }.toSet()
    private val catalogCache = LinkedHashMap<String, CachedCatalog>()
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageRequests = mutableMapOf<PageKey, SharedPages>()
    private val pageCache = LinkedHashMap<PageKey, CachedPages>()

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
        val keyword = query.trim()
        require(keyword.length in 1..120) { "请输入 1 至 120 个字符。" }
        require(page in 0..1000) { "漫画分页超出范围。" }
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/search/comic")
            .addQueryParameter("q", keyword)
            .addQueryParameter("free_type", "1")
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", (page * 20).toString())
            .addQueryParameter("platform", "3").build()
        catalogPage(apiJson(url, "漫画搜索"), page * 20, 20)
    }

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
        val url = comicUrl(mangaId).toHttpUrl()
        val http = websiteSession()
        val response = http.request(webRequest(url).build(), "漫画标识", url.toString())
        demandHtml(response, url, http, "漫画标识")
        comicUuidFrom(Jsoup.parse(response.text, url.toString()))
            ?: throw SourceException("官网详情缺少收藏所需的漫画标识，本地收藏已保留。", url.toString())
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

    private suspend fun readPages(mangaId: String, id: String): List<Page> {
        val url = webOrigin.newBuilder().addPathSegment("comic").addPathSegment(mangaId)
            .addPathSegment("chapter").addPathSegment(id).build()
        val http = websiteSession()
        val response = http.request(webRequest(url).header("Referer", comicUrl(mangaId)).build(), "章节内容", url.toString())
        demandHtml(response, url, http, "章节内容")
        val document = Jsoup.parse(response.text, url.toString())
        // The requested UUID stays in the fixed anonymous chapter URL (HTTP
        // redirects are disabled). The real reader HTML binds its directory
        // link to this comic, and every decrypted image must also belong to it.
        // This keeps ownership checks without making a full catalog a reading
        // prerequisite when resuming a saved chapter.
        val directoryLinks = document.select(".comicContent-prev.list a[href]")
            .mapNotNull { url.resolve(it.attr("href")) }.distinct()
        val expectedDirectory = comicUrl(mangaId).toHttpUrl()
        if (directoryLinks.isEmpty() || directoryLinks.any {
                it.scheme != expectedDirectory.scheme || it.host != expectedDirectory.host ||
                    it.port != expectedDirectory.port || it.encodedPath != expectedDirectory.encodedPath ||
                    it.username.isNotEmpty() || it.password.isNotEmpty()
            }) {
            throw SourceException("正文页面与漫画归属不一致，请重新打开该章节。", url.toString())
        }
        val payload = decodePayload(scriptVariable(document, "contentKey"), scriptVariable(document, "cct"), "章节内容")
        val contents = try { JSONArray(payload) } catch (error: JSONException) {
            throw SourceException("章节内容格式已变化，请查看原站提示。", url.toString(), cause = error)
        }
        if (contents.length() !in 1..2000) throw SourceException("来源没有返回有效的章节图片。", url.toString())
        return List(contents.length()) { index ->
            currentCoroutineContext().ensureActive()
            val item = contents.optJSONObject(index) ?: throw SourceException("章节图片清单不完整。", url.toString())
            val asset = imageUrl(item.requiredText("url"))
            if (!asset.encodedPath.contains("/$mangaId/")) {
                throw SourceException("来源返回了阅读提示图，未将它当作漫画正文。", url.toString())
            }
            Page(index, asset.toString())
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        segment(mangaId)
        val referer = if (chapterId.isBlank()) comicUrl(mangaId) else webOrigin.newBuilder()
            .addPathSegment("comic").addPathSegment(mangaId)
            .addPathSegment("chapter").addPathSegment(uuid(chapterId, "章节")).build().toString()
        return mapOf("User-Agent" to "Mozilla/5.0", "Referer" to referer)
    }

    private suspend fun apiJson(url: HttpUrl, label: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", "COPY/3.0.9")
            .header("Accept", "application/json")
            .header("source", "copyApp")
            .header("platform", "3")
            .header("version", "3.0.9")
            .header("webp", "1").build()
        val result = envelope(api.request(request, label, webOrigin.toString()), url, api, label)
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

    private suspend fun readCatalog(mangaId: String, onMetadata: ((Manga) -> Unit)? = null): CachedCatalog {
        val url = comicUrl(mangaId).toHttpUrl()
        val http = websiteSession()
        val response = http.request(webRequest(url).build(), "漫画详情", url.toString())
        demandHtml(response, url, http, "漫画详情")
        val document = Jsoup.parse(response.text, url.toString())
        val key = scriptVariable(document, "ccz")
        val version = document.select("#dnt").singleOrNull()?.attr("value").orEmpty()
        if (!version.matches(Regex("[1-9][0-9]{0,8}"))) throw SourceException("来源未提供有效的章节目录，请查看原站提示。", url.toString())
        val section = document.select(".comicParticulars-title").singleOrNull()
            ?: throw SourceException("漫画详情页面结构已变化。", url.toString())
        val title = section.select("h6").singleOrNull()?.text().orEmpty().trim()
        if (title.isBlank()) throw SourceException("来源没有返回漫画标题。", url.toString())
        // The collection control is optional website UI. Public catalog identity
        // comes from build.path_word; a present UUID is an additional cross-check.
        val comicUuid = comicUuidFrom(document)
        val coverImage = section.selectFirst(".comicParticulars-left-img img")
        val cover = coverImage?.attr("data-src")?.takeIf(String::isNotBlank)
            ?: coverImage?.attr("src")?.takeIf(String::isNotBlank)
        val status = section.select("li").firstOrNull { row ->
            row.children().firstOrNull()?.text()?.trim()?.let { it.startsWith("狀態") || it.startsWith("状态") } == true
        }?.selectFirst(".comicParticulars-right-txt")?.text().orEmpty()
        val popularityText = section.select("li").firstOrNull { row ->
            row.children().firstOrNull()?.text()?.trim()?.let { it.startsWith("熱度") || it.startsWith("热度") } == true
        }?.selectFirst(".comicParticulars-right-txt")?.text().orEmpty()
        val popularity = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([wW万萬亿億kK]?)$")
            .matchEntire(popularityText.replace(",", "").trim())?.let { match ->
                val unit = when (match.groupValues[2]) {
                    "w", "W", "万", "萬" -> 10_000.0
                    "亿", "億" -> 100_000_000.0
                    "k", "K" -> 1_000.0
                    else -> 1.0
                }
                match.groupValues[1].toDoubleOrNull()?.times(unit)?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE }
                    ?.toLong()
            }
        val manga = Manga(
            id = mangaId,
            title = title,
            coverUrl = optionalCover(cover.orEmpty()),
            authors = section.select("a[href^=/author/]").map { it.text() }.filter(String::isNotBlank).distinct(),
            description = document.selectFirst(".comicParticulars-synopsis .intro")?.text().orEmpty(),
            status = status,
            tags = section.select(".comicParticulars-tag a").map { it.text().removePrefix("#") }.filter(String::isNotBlank),
            sourceUrl = url.toString(),
            sourceUuid = comicUuid.orEmpty(),
            popularity = popularity,
            totalPopularity = popularity,
        )
        currentCoroutineContext().ensureActive()
        onMetadata?.invoke(manga)
        val catalogUrl = webOrigin.newBuilder().addPathSegment("comicdetail").addPathSegment(mangaId).addPathSegment("chapters").build()
        val catalogResponse = http.request(webRequest(catalogUrl)
            .header("Accept", "application/json")
            .header("Referer", url.toString())
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("dnts", version).build(), "章节目录", url.toString())
        val encrypted = envelope(catalogResponse, catalogUrl, http, "章节目录") as? String
            ?: throw SourceException("来源没有返回有效目录。", url.toString())
        val root = try { JSONObject(decodePayload(encrypted, key, "章节目录")) } catch (error: JSONException) {
            throw SourceException("章节目录格式已变化。", url.toString(), cause = error)
        }
        demandPublic(root)
        val build = root.optJSONObject("build") ?: throw SourceException("章节目录缺少漫画归属信息。", url.toString())
        demandPublic(build)
        if (build.requiredText("path_word") != mangaId) throw SourceException("来源章节目录与漫画不匹配。", url.toString())
        val groups = root.optJSONObject("groups") ?: throw SourceException("章节目录缺少分组。", url.toString())
        if (groups.length() !in 1..32) throw SourceException("章节目录分组异常。", url.toString())
        val chapters = mutableListOf<Chapter>()
        val seen = hashSetOf<String>()
        var latest = ""
        val keys = groups.keys().asSequence().toList().sortedBy { if (it == "default") 0 else 1 }
        for (groupId in keys) {
            currentCoroutineContext().ensureActive()
            val group = groups.optJSONObject(groupId) ?: throw SourceException("章节分组数据不完整。", url.toString())
            segment(groupId)
            val declaredGroup = group.text("path_word")
            if (declaredGroup.isNotEmpty() && declaredGroup != groupId) throw SourceException("章节分组标识不一致。", url.toString())
            val rows = group.optJSONArray("chapters") ?: throw SourceException("章节清单缺失。", url.toString())
            // The website consumes the complete chapters array. Count is optional
            // metadata, but an explicitly supplied total must still agree with it.
            val hasCount = group.has("count") && !group.isNull("count")
            if ((hasCount && rows.length() != group.optInt("count", -1)) || rows.length() + chapters.size > 20_000) {
                throw SourceException("来源章节目录尚不完整，请稍后刷新。", url.toString())
            }
            val groupName = group.text("name").ifBlank { if (groupId == "default") "正篇" else groupId }
            val groupIds = hashSetOf<String>()
            val groupStart = chapters.size
            for (index in 0 until rows.length()) {
                currentCoroutineContext().ensureActive()
                val row = rows.optJSONObject(index) ?: throw SourceException("章节数据缺失。", url.toString())
                // The website builds its directory from this complete array.
                // App-list metadata is not an availability contract for web
                // groups or rows. The anonymous chapter request enforces access.
                val chapterId = uuid(row.requiredText("id"), "章节")
                if (!seen.add(chapterId)) throw SourceException("来源章节标识重复。", url.toString())
                groupIds += chapterId
                chapters += Chapter(chapterId, row.requiredText("name"), chapters.size, groupName)
            }
            val last = group.optJSONObject("last_chapter")
            var publicLatestName = ""
            if (last != null) {
                val lastComic = last.text("comic_path_word")
                val lastGroup = last.text("group_path_word")
                val lastComicId = last.text("comic_id").takeIf(String::isNotBlank)?.let { uuid(it, "漫画") }
                val lastId = last.text("uuid").takeIf(String::isNotBlank)?.let { uuid(it, "章节") }
                if ((lastComic.isNotBlank() && lastComic != mangaId) || (lastGroup.isNotBlank() && lastGroup != groupId) ||
                    (comicUuid != null && lastComicId != null && lastComicId != comicUuid)) {
                    throw SourceException("章节目录归属不一致，请刷新后重试。", url.toString())
                }
                // A last_chapter can refer to a chapter not publicly available
                // in this directory; it does not invalidate the remaining rows.
                if (lastId != null && lastId in groupIds) publicLatestName = last.text("name")
            }
            if (chapters.size > groupStart && (latest.isBlank() || groupId == "default")) {
                latest = publicLatestName.ifBlank { chapters.last().title }
            }
        }
        if (chapters.isEmpty()) throw SourceException("官网目录返回了 ${groups.length()} 个分组，但章节清单为空。请稍后刷新或查看原站提示。", url.toString())
        val catalog = CachedCatalog(MangaDetail(manga.copy(latestChapter = latest), chapters), comicUuid, System.currentTimeMillis())
        synchronized(catalogCache) {
            catalogCache.remove(mangaId)
            catalogCache[mangaId] = catalog
            while (catalogCache.size > 24) catalogCache.remove(catalogCache.keys.first())
        }
        return catalog
    }

    private fun websiteSession(): SourceHttp {
        val jar = object : CookieJar {
            private val stored = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(stored) {
                if (url.host != webOrigin.host) return@synchronized
                for (cookie in cookies) {
                    // Keep cookies issued to this anonymous visit. This jar is
                    // short-lived and never reads or writes personal credentials.
                    stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    stored += cookie
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(stored) {
                if (url.host != webOrigin.host) emptyList() else {
                    stored.removeAll { it.expiresAt <= System.currentTimeMillis() }
                    stored.filter { it.matches(url) && it.name != "webp" } +
                        Cookie.Builder().name("webp").value("1").hostOnlyDomain(webOrigin.host).path("/").secure().build()
                }
            }
        }
        return SourceHttp(websiteClient.newBuilder().cookieJar(jar).build())
    }

    private fun webRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0").header("Accept", "text/html")

    private fun demandHtml(response: SourceResponse, url: HttpUrl, http: SourceHttp, label: String) {
        if (response.text.trimStart().startsWith('{')) {
            envelope(response, url, http, label)
            throw SourceException("$label 未返回正常网页，请查看原站提示。", url.toString())
        }
    }

    private fun envelope(response: SourceResponse, url: HttpUrl, http: SourceHttp, label: String): Any {
        val json = try { JSONObject(response.text) } catch (error: JSONException) {
            throw SourceException("$label 响应格式已变化，请查看原站提示。", webOrigin.toString(), cause = error)
        }
        val code = json.optInt("code", -1)
        if (code in setOf(209, 210, 429)) {
            http.rememberPause(url, response.retryAfterSeconds)
            throw SourceException("$label 暂时被来源限制（$code），请稍后重试或查看原站。", webOrigin.toString(), response.retryAfterSeconds)
        }
        if (code == 401 || code == 403) throw SourceException("$label 被来源拒绝，请查看原站提示。", webOrigin.toString())
        if (code != 200) throw SourceException("$label 暂时不可用（业务码 $code）。", webOrigin.toString())
        return json.opt("results")?.takeUnless { it == JSONObject.NULL }
            ?: throw SourceException("$label 未返回数据，请稍后重试或查看原站。", webOrigin.toString())
    }

    private fun scriptVariable(document: Document, variable: String): String {
        val pattern = Regex("(?m)^[ \\t]*(?:var|let|const)[ \\t]+${Regex.escape(variable)}[ \\t]*=[ \\t]*(['\"])([^'\"\\\\\\r\\n]+)\\1[ \\t]*;?[ \\t\\r]*(?://[^\\r\\n]*)?$")
        val matches = document.select("script:not([src])").flatMap { pattern.findAll(it.data()).map { match -> match.groupValues[2] }.toList() }
        return matches.singleOrNull() ?: throw SourceException("来源未提供可读取的目录或正文，请查看原站访问提示。", document.location())
    }

    private fun comicUuidFrom(document: Document): String? {
        val pattern = Regex("collect\\(\\s*['\"]([0-9a-fA-F-]{36})['\"]\\s*\\)")
        val identities = document.select(".comicParticulars-title .collect[onclick]").mapNotNull { element ->
            pattern.find(element.attr("onclick"))?.groupValues?.get(1)?.let { uuid(it, "漫画") }
        }.distinct()
        if (identities.size > 1) throw SourceException("官网详情中的漫画标识不一致，请重新打开。", document.location())
        return identities.singleOrNull()
    }

    private fun decodePayload(payload: String, key: String, label: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        try {
            if (keyBytes.size !in setOf(16, 24, 32) || payload.length !in 48..(8 * 1024 * 1024) ||
                (payload.length - 16) % 32 != 0 || payload.take(16).any { it.code !in 0x20..0x7e }) {
                throw SourceException("$label 编码格式已变化。", webOrigin.toString())
            }
            val hex = payload.drop(16)
            val encrypted = ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                    ?: throw SourceException("$label 编码无效。", webOrigin.toString())
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(payload.take(16).toByteArray(Charsets.UTF_8)))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (error: SourceException) { throw error }
          catch (error: Exception) { throw SourceException("$label 解码失败，请刷新详情后重试。", webOrigin.toString(), cause = error) }
        finally { keyBytes.fill(0) }
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
    private fun demandPublic(value: JSONObject) {
        if (restricted(value)) throw SourceException("该内容已下架、锁定或需要授权，请查看原站。", webOrigin.toString())
    }
    private fun restricted(value: JSONObject): Boolean {
        for (key in listOf("is_banned", "is_lock", "is_vip", "b_404", "b_hidden", "ban_ip", "vip", "locked", "is_paid")) {
            val flag = value.opt(key)
            if (flag == true || flag is Number && flag.toInt() != 0) return true
        }
        val ban = value.opt("ban")
        if (ban is Number && ban.toInt() != 0) return true
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
    private class SharedPages(val result: Deferred<List<Page>>, var readers: Int = 0)

    private companion object {
        const val PAGE_CACHE_NANOS = 3 * 60_000_000_000L
    }
}
