package dev.mangaflow.data.sources

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.javaProxy
import dev.mangaflow.net.checked
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.syncStorageFile
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.json.JSONObject
import dev.mangaflow.json.JSONArray
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.enums.Category
import io.github.jukomu.jmcomic.api.enums.OrderBy
import io.github.jukomu.jmcomic.api.enums.TimeOption
import io.github.jukomu.jmcomic.api.enums.SearchMainTag
import io.github.jukomu.jmcomic.api.enums.FavoriteFolderType
import io.github.jukomu.jmcomic.api.enums.ForumMode
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.SearchQuery
import io.github.jukomu.jmcomic.api.model.FavoriteQuery
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.JmSearchPage
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Built-in JM source, reusing the maintained MIT-licensed JVM client for authenticated
 * requests and image segment restoration. No JS engines and no second reader.
 *
 * The shared bridge runs on Android and desktop; iOS does not advertise this adapter.
 */
internal class JvmJmBodySource(private val context: PlatformContext) : BodySource {
    override val info = BodySourceInfo("jmcomic", "禁漫", "简中", "https://18comic.vip", "", adult = true)
    private val mutex = Mutex()
    private val root = StorageFile(cacheDirectory(context), "jm-decoded")
    private var client: JmApiClient? = null
    private var clientConfig: JmConfiguration? = null
    private var savedSession: JSONObject? = readSecret(context, SECRET_KEY)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    private var accountName: String = savedSession?.optString("owner").orEmpty()
    private val photoImages = LinkedHashMap<String, List<JmImage>>()

    private suspend fun <T> clientCall(operation: (JmApiClient) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val active = client ?: runInterruptible {
                val (created, config) = createClient()
                try {
                    savedSession?.let { session -> runCatching {
                        val host = session.optString("host")
                        val cookies = readCookies(session)
                        if (host in created.domainStates && cookies.isNotEmpty()) {
                            created.useDomain(host)
                            created.cookies = cookies
                        } else accountName = ""
                    }.onFailure { accountName = "" } }
                    clientConfig = config
                    created.also { client = it }
                } catch (failure: Exception) { created.close(); throw failure }
            }
            currentCoroutineContext().ensureActive()
            try { runInterruptible { operation(active) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (failure.message.orEmpty().contains("請先登入會員")) accountName = ""
                throw SourceException("禁漫请求失败：${failure.cause?.message ?: failure.message}", info.homeUrl, cause = failure)
            }
        }
    }

    /** A cookie-only reader never enables the upstream client's cached-password retry. */
    private fun createClient(pinDomain: Boolean = false): Pair<JmApiClient, JmConfiguration> {
        val config = JmConfiguration.Builder().clientType(ClientType.API).retryTimes(0)
            .cacheSize(24 * 1024 * 1024).closeTimeoutMs(1_000).build()
        val bundle = OkHttpBuilder.build(config)
        val http = bundle.client.newBuilder().retryOnConnectionFailure(false).build()
        val created = JmApiClient(config, http, bundle.cookieManager, bundle.domainManager)
        try {
            created.initializeAsync().get()
            if (pinDomain) created.useDomain(bundle.domainManager.bestDomain)
        }
        catch (failure: Exception) { created.close(); throw failure }
        return created to config
    }

    override suspend fun capabilities() = SourceCapabilities(setOf(SourceFeature.DISCOVERY, SourceFeature.SEARCH,
        SourceFeature.CATEGORIES, SourceFeature.RANKING, SourceFeature.ACCOUNT, SourceFeature.FAVORITES,
        SourceFeature.COMMENTS))

    override suspend fun feeds(): List<SourceFeed> = listOf(
        SourceFeed("latest", "最近更新", SourceFeedKind.LATEST),
        SourceFeed("ranking", "热榜", SourceFeedKind.RANKING,
            listOf(periodFilter.copy(defaultValue = "w"), sortFilter.copy(defaultValue = "mv"))),
    ) + Category.values().filter { it != Category.ALL }.map { category ->
        SourceFeed("category:${category.value}", category.description, SourceFeedKind.CATEGORY, listOf(sortFilter, periodFilter))
    }

    override suspend fun browse(request: SourceBrowseRequest): MangaPage {
        checkPage(request.page)
        if (request.feedId == "latest") {
            require(request.options.isEmpty()) { "最近更新不支持附加条件。" }
            return discover(request.page)
        }
        require(request.options.size <= 2) { "禁漫浏览条件无效。" }
        val rank = request.feedId == "ranking"
        val category = if (rank) Category.ALL else Category.values().firstOrNull {
            request.feedId == "category:${it.value}"
        } ?: throw SourceException("禁漫分类无效。")
        val order = order(request.options.getOrNull(if (rank) 1 else 0), if (rank) OrderBy.MOST_VIEWED else OrderBy.LATEST)
        val time = time(request.options.getOrNull(if (rank) 0 else 1), if (rank) TimeOption.WEEK else TimeOption.ALL)
        return clientCall { active -> toPage(active.getCategories(SearchQuery.Builder()
            .category(category).orderBy(order).time(time).page(request.page + 1).build()), request.page) }
    }

    override suspend fun searchFilters() = listOf(sortFilter, periodFilter,
        SourceFilter("target", "搜索", SearchMainTag.values().map { SourceChoice(it.value.toString(), it.description) }, "0"))

    override suspend fun searchFiltered(query: String, page: Int, options: List<String>): MangaPage {
        checkPage(page)
        require(query.trim().length in 1..120 && options.size <= 3) { "请输入有效的搜索词和条件。" }
        val target = options.getOrNull(2).orEmpty().ifBlank { "0" }
        val tag = SearchMainTag.values().firstOrNull { it.value.toString() == target }
            ?: throw SourceException("禁漫搜索类型无效。")
        return clientCall { active -> toPage(active.search(SearchQuery.Builder().text(query.trim()).mainTag(tag)
            .orderBy(order(options.getOrNull(0), OrderBy.LATEST)).time(time(options.getOrNull(1), TimeOption.ALL))
            .page(page + 1).build()), page) }
    }

    override suspend fun accountState(): SourceAccountState = mutex.withLock {
        SourceAccountState(accountName.isNotBlank(), accountName)
    }

    override suspend fun login(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        require(username.trim().length in 1..160 && password.length in 1..512) { "请输入禁漫账号和密码。" }
        val caller = currentCoroutineContext()
        mutex.withLock {
            runInterruptible {
                val (loginClient, _) = createClient(pinDomain = true)
                try {
                    val user = loginClient.login(username.trim(), password)
                    val owner = user.username()?.takeIf { it.isNotBlank() }
                        ?: throw SourceException("禁漫登录未返回有效账号。")
                    val host = loginClient.usedDomain?.takeIf { it.isNotBlank() }
                        ?: throw SourceException("禁漫登录未返回会话域名。")
                    val cookies = loginClient.cookies.orEmpty()
                    require(cookies.isNotEmpty()) { "禁漫登录未返回可恢复的会话。" }
                    val (replacement, config) = createClient()
                    try {
                        replacement.useDomain(host)
                        replacement.cookies = cookies
                        val secret = JSONObject().put("owner", owner).put("host", host).put("cookies", JSONArray(cookies.map {
                            JSONObject().put("domain", it.domain).put("value", it.toString())
                        }))
                        caller.ensureActive()
                        writeSecret(context, SECRET_KEY, secret.toString())
                        val previous = client
                        client = replacement; clientConfig = config
                        savedSession = secret; accountName = owner
                        previous?.close()
                    } catch (failure: Exception) { replacement.close(); throw failure }
                } finally {
                    // The upstream login client holds an encrypted in-memory password. Do not reuse it.
                    loginClient.cookies = emptyList()
                    loginClient.close()
                }
            }
        }
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            deleteSecret(context, SECRET_KEY)
            savedSession = null; accountName = ""
            val previous = client
            client = null; clientConfig = null
            previous?.let { active -> runInterruptible { active.cookies = emptyList(); active.close() } }
        }
    }

    override suspend fun favoriteFolders(): List<SourceFavoriteFolder> = clientCall { active ->
        requireAccount()
        val data = active.getFavorites(FavoriteQuery.Builder().page(1).build())
        (listOf(SourceFavoriteFolder("0", "全部")) + data.folderList().orEmpty().map { (id, title) ->
            SourceFavoriteFolder(id, title)
        }).distinctBy(SourceFavoriteFolder::id)
    }

    override suspend fun favorites(folderId: String, page: Int): MangaPage {
        checkPage(page)
        val folder = folderId.toIntOrNull()?.takeIf { it >= 0 } ?: throw SourceException("禁漫收藏夹编号无效。")
        return clientCall { active ->
            requireAccount()
            val result = active.getFavorites(FavoriteQuery.Builder().folderId(folder).page(page + 1).build())
            toPage(JmSearchPage(result.currentPage(), result.totalItems(), result.totalPages(), result.content()), page)
        }
    }

    override suspend fun setFavorite(mangaId: String, folderId: String, favorite: Boolean): Unit = clientCall { active ->
        requireAccount()
        val id = nativeMangaId(mangaId)
        val folder = folderId.toIntOrNull()?.takeIf { it >= 0 } ?: throw SourceException("禁漫收藏夹编号无效。")
        clientConfig?.cachePool?.clear()
        try {
            // The toggle ignores folderId. A custom-folder add requires the separate, real move API.
            if (active.getAlbum(id).isFavorite != favorite) active.toggleAlbumFavorite(id, "0")
            if (favorite && folder != 0) {
                val moved = active.manageFavoriteFolder(FavoriteFolderType.MOVE, folderId, "", id)
                check(moved.isOk) { "已收藏，但移动到指定收藏夹失败，请刷新站点收藏后重试。" }
            }
        } finally { clientConfig?.cachePool?.clear() }
    }

    override suspend fun comments(mangaId: String, page: Int): SourceCommentPage {
        checkPage(page)
        val id = nativeMangaId(mangaId)
        return clientCall { active ->
            val result = active.getComments(ForumQuery.album(id).mode(ForumMode.MANHUA).page(page + 1).build())
            val rows = result.list().orEmpty()
            val comments = rows.map { row ->
                require(row.aid().isNullOrBlank() || row.aid() == id) { "禁漫评论不属于当前作品。" }
                SourceComment(row.commentId().orEmpty(), row.nickname().orEmpty().ifBlank { row.username().orEmpty() },
                    row.content().orEmpty(), row.photo()?.takeIf { it.startsWith("https://") }.orEmpty(),
                    row.postDate().orEmpty(), maxOf(row.likes(), row.voteUp(), 0))
            }
            // Public jm.js and the native forum contract use six top-level comments per page.
            SourceCommentPage(comments, rows.isNotEmpty() && (page + 1) * 6 < result.total())
        }
    }

    private fun requireAccount() = check(accountName.isNotBlank()) { "请先登录禁漫账号。" }

    private fun readCookies(session: JSONObject): List<Cookie> {
        val rows = session.optJSONArray("cookies") ?: return emptyList()
        require(rows.length() <= 128) { "禁漫会话记录无效。" }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val domain = row.getString("domain")
            val cookie = Cookie.parse("https://$domain/".toHttpUrl(), row.getString("value"))
            cookie?.takeIf { it.expiresAt > System.currentTimeMillis() }
        }
    }

    private fun order(value: String?, fallback: OrderBy): OrderBy = if (value.isNullOrBlank()) fallback
        else OrderBy.values().firstOrNull { it.value == value } ?: throw SourceException("禁漫排序无效。")

    private fun time(value: String?, fallback: TimeOption): TimeOption = if (value.isNullOrBlank()) fallback
        else TimeOption.values().firstOrNull { it.value == value } ?: throw SourceException("禁漫时间范围无效。")

    override suspend fun discover(page: Int): MangaPage {
        checkPage(page)
        return clientCall { client -> toPage(client.getLatest(page + 1), page) }
    }

    override suspend fun search(query: String, page: Int): MangaPage = searchFiltered(query, page, emptyList())

    override suspend fun detail(mangaId: String): MangaDetail {
        val id = nativeMangaId(mangaId)
        return clientCall { client ->
            val album = client.getAlbum(id)
            require(album.id() == id) { "禁漫作品编号不一致，已停止加载。" }
            val title = album.title()?.trim().orEmpty().ifBlank { "JM$id" }
            val works = album.photoMetas().orEmpty()
            require(works.size <= 10_000) { "禁漫章节目录超过读取上限。" }
            val effective = if (works.isNotEmpty()) works.map { it.id() to it.title() } else listOf(id to "阅读")
            val chapters = effective.distinctBy { it.first }.mapIndexed { index, (photoId, title) ->
                val actual = validId(photoId)
                val label = title?.trim().orEmpty().ifBlank { "第${index + 1}话" }
                val variant = ChapterVariant(info.id, info.name, id, actual, label, info.language,
                    edition = if (effective.size == 1) "单行本" else "连载版")
                Chapter(variant.storageId, label, index,
                    if (effective.size == 1) "单行本" else "连载",
                    variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = actual)
            }
            val manga = Manga(SourceKeys.manga(info.id, id), title,
                coverUrl = album.image()?.takeIf { it.startsWith("https://") }
                    ?: "https://cdn-msp.jmapiproxy1.cc/media/albums/${id}_3x4.jpg",
                authors = album.authors().orEmpty(), description = album.description().orEmpty(),
                tags = album.tags().orEmpty(), sourceUrl = "${info.homeUrl}/album/$id",
                sourceId = info.id, sourceMangaId = id, sourceName = info.name)

            MangaDetail(manga, chapters)
        }
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "禁漫章节不属于当前来源。" }
        val id = validId(ref.chapterId)
        return clientCall { client ->
            if (forceRefresh) { clientConfig?.cachePool?.clear(); photoImages.remove(id) }
            val photo = client.getPhoto(id)
            if (!photo.albumId().isNullOrBlank() && photo.albumId() != ref.mangaId)
                throw SourceException("禁漫章节作品编号不一致，已停止加载。")
            val images = photo.images()?.takeIf { it.isNotEmpty() && it.size <= 10_000 }
                ?: throw SourceException("禁漫没有返回可读取图片。")
            photoImages[id] = images.toList()
            while (photoImages.size > 16) photoImages.remove(photoImages.keys.first())
            images.mapIndexed { index, image ->
                val url = image.downloadUrl
                require(url.startsWith("https://") && url.length <= 4096) { "禁漫返回的图片地址无效。" }
                Page(index, url)
            }
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> =
        mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36", "Accept" to "image/webp,image/jpeg,*/*")

    suspend fun decryptedPageFile(chapterId: String, page: Page): StorageFile = withContext(Dispatchers.IO) {
        val key = validId(chapterId)
        require(page.index in 0..9_999) { "图片序号无效。" }
        clientCall { client ->
            val images = photoImages[key] ?: client.getPhoto(key).images()?.also {
                photoImages[key] = it.toList()
                while (photoImages.size > 16) photoImages.remove(photoImages.keys.first())
            } ?: throw SourceException("禁漫章节没有图片。")
            val img = images.getOrNull(page.index) ?: throw SourceException("禁漫图片页码超出范围。")
            require(img.downloadUrl.startsWith("https://")) { "禁漫图片地址不是 HTTPS。" }
            require(img.downloadUrl == page.url) { "禁漫图片清单已变化，请刷新本章后重试。" }
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(("$key:${page.index}:" + img.downloadUrl).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val target = StorageFile(root, "$fingerprint.jpg")
            if (target.isFile && target.length() in 128L..MAX_IMAGE_BYTES) return@clientCall target
            if (!root.mkdirs()) throw SourceException("无法创建禁漫私有图片缓存。")
            val bytes = client.fetchImageBytes(img)
            if (bytes.size.toLong() !in 128L..MAX_IMAGE_BYTES)
                throw SourceException("图片解码结果无效或体积过大。")
            val pending = StorageFile(root, "$fingerprint.part")
            try {
                pending.writeBytes(bytes)
                syncStorageFile(pending)
                if (target.exists() && !target.delete()) throw SourceException("旧图片缓存无法替换。")
                if (!pending.renameTo(target)) throw SourceException("解码后的图片无法缓存。")
            } finally { pending.delete() }
            pruneCache()
            target
        }
    }

    private fun pruneCache() {
        val files = root.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()
        var bytes = files.sumOf { it.length() }
        if (bytes <= CACHE_CEILING) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= CACHE_TARGET) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }

    private fun toPage(page: io.github.jukomu.jmcomic.api.model.JmSearchPage, requestPage: Int): MangaPage {
        val current = page.currentPage()
        val total = page.totalPages()
        if (current != requestPage + 1 || current !in 1..500 || total !in 0..100_000 ||
            (total > 0 && current > total))
            throw SourceException("来源返回的分页信息无效，请刷新后重试。")
        val content = page.content()?.take(200)
        if (content == null && total > 0) throw SourceException("来源没有返回作品列表。")
        val rows = content.orEmpty().mapNotNull { item ->
            runCatching { manga(item) }.getOrNull()
        }.distinctBy(Manga::id)
        if ((!content.isNullOrEmpty() && rows.isEmpty()) || (total == 0 && rows.isNotEmpty()))
            throw SourceException("来源返回的作品列表无法识别，请更新来源后重试。")
        // Empty pages do not erase the source's validated remaining page count.
        val more = current < minOf(total, 500)
        return MangaPage(rows, more, if (more) requestPage + 1 else null)
    }

    private fun manga(item: JmAlbumMeta): Manga {
        val id = validId(item.id())
        return Manga(SourceKeys.manga(info.id, id), item.title()?.trim().orEmpty().ifBlank { "JM$id" },
            coverUrl = item.image()?.takeIf { it.startsWith("https://") }
                ?: "https://cdn-msp.jmapiproxy1.cc/media/albums/${id}_3x4.jpg",
            authors = item.authors().orEmpty(), description = item.description().orEmpty(),
            tags = item.tags().orEmpty(), sourceUrl = "${info.homeUrl}/album/$id",
            sourceId = info.id, sourceMangaId = id, sourceName = info.name)
    }

    private fun validId(value: String): String = value.takeIf { it.matches(Regex("[0-9]{1,14}")) }
        ?: throw SourceException("禁漫作品或章节编号无效。")
    private fun nativeMangaId(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "作品与禁漫来源不一致。" }
        return validId(ref.mangaId)
    }
    private fun checkPage(page: Int) = require(page in 0..499) { "禁漫分页超出范围。" }

    companion object {
        private const val SECRET_KEY = "adult_jm_session_v1"
        private val sortFilter = SourceFilter("sort", "排序", listOf(SourceChoice("mr", "最新"),
            SourceChoice("mv", "最多浏览"), SourceChoice("tf", "最多喜欢"), SourceChoice("mp", "图片最多")), "mr")
        private val periodFilter = SourceFilter("period", "时间", listOf(SourceChoice("t", "今日"),
            SourceChoice("w", "本周"), SourceChoice("m", "本月"), SourceChoice("a", "全部")), "a")
        private const val MAX_IMAGE_BYTES = 96L * 1024 * 1024
        private const val CACHE_CEILING = 384L * 1024 * 1024
        private const val CACHE_TARGET = 256L * 1024 * 1024
    }
}

private object JmNativeSourceProvider {
    @Volatile private var instance: JvmJmBodySource? = null
    @Synchronized fun get(context: PlatformContext): JvmJmBodySource =
        instance ?: JvmJmBodySource(context).also { instance = it }
}

actual fun createJmBodySource(context: PlatformContext): BodySource? = JmNativeSourceProvider.get(context)

actual suspend fun materializeJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): Page? {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    if (ref.sourceId != "jmcomic" || page.url.startsWith("file://")) return null
    val file = JmNativeSourceProvider.get(context).decryptedPageFile(ref.chapterId, page)
    return page.copy(url = file.toURI())
}

actual suspend fun decryptedJmPageBytes(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): ByteArray? {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    if (ref.sourceId != "jmcomic") return null
    return JmNativeSourceProvider.get(context).decryptedPageFile(ref.chapterId, page).readBytes()
}

/** The upstream's public client/context constructors preserve its cookies, domains and decoder. */
private class RoutedJmDownloadSession(
    private val context: PlatformContext,
    private val mangaId: String,
    private val chapterId: String,
    private val route: DownloadRouteSnapshot,
) : JmDownloadSession {
    private val control = Any()
    @Volatile private var closed = false
    private var client: JmApiClient? = null
    private val metadata = Mutex()
    private var images: List<JmImage>? = null

    override suspend fun pages(): List<Page> = withContext(Dispatchers.IO) {
        val active = runInterruptible { activeClient() }
        val loaded = chapterImages(active)
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("下载已停止")
        loaded.mapIndexed { index, image ->
            val url = image.downloadUrl
            require(url.startsWith("https://") && url.length <= 4096) { "来源返回的图片地址无效。" }
            Page(index, url)
        }
    }

    private suspend fun chapterImages(active: JmApiClient): List<JmImage> = metadata.withLock {
        images ?: runInterruptible { active.getPhoto(chapterId) }.let { photo ->
            if (!photo.albumId().isNullOrBlank() && photo.albumId() != mangaId)
                throw SourceException("章节作品编号不一致，已停止下载。")
            photo.images()?.takeIf { it.isNotEmpty() && it.size <= 10_000 }?.toList()
                ?: throw SourceException("章节没有可下载图片。")
        }.also { images = it }
    }

    override suspend fun read(page: Page): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("下载已停止")
        require(page.index in 0..9_999) { "图片序号无效。" }
        // Reuse the exact previously materialized image before opening a routed connection.
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest((chapterId + ":" + page.index + ":" + page.url).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cached = StorageFile(StorageFile(cacheDirectory(context), "jm-decoded"), fingerprint + ".jpg")
        if (cached.isFile && cached.length() in 128L..MAX_IMAGE_BYTES)
            return@withContext cached.readBytes()
        val active = runInterruptible { activeClient() }
        currentCoroutineContext().ensureActive()
        val pages = chapterImages(active)
        val image = pages.getOrNull(page.index) ?: throw SourceException("图片页码超出范围。")
        require(image.downloadUrl.startsWith("https://") && image.downloadUrl.length <= 4096) {
            "来源返回的图片地址无效。"
        }
        require(image.downloadUrl == page.url) { "图片清单已变化，请刷新下载任务后重试。" }
        if (closed) throw CancellationException("下载已停止")
        val bytes = runInterruptible { active.fetchImageBytes(image) }
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("下载已停止")
        require(bytes.size.toLong() in 128L..MAX_IMAGE_BYTES) { "图片解码结果无效或体积过大。" }
        bytes
    }

    private fun activeClient(): JmApiClient {
        val active = synchronized(control) {
            if (closed) throw CancellationException("下载已停止")
            client ?: run {
                val proxy = route.javaProxy()
                val config = JmConfiguration.Builder().clientType(ClientType.API)
                    .retryTimes(2).cacheSize(24 * 1024 * 1024).downloadThreadPoolSize(1)
                    .closeTimeoutMs(1_000).build()
                val bundle = OkHttpBuilder.build(config)
                val http = bundle.client.newBuilder().proxy(proxy).build()
                JmApiClient(config, http, bundle.cookieManager, bundle.domainManager).also { client = it }
            }
        }
        try {
            active.initializeAsync().get()
            if (closed) throw CancellationException("下载已停止")
            return active
        } catch (failure: Exception) {
            synchronized(control) { if (client === active) client = null }
            runCatching { active.close() }
            throw failure
        }
    }

    override fun close() {
        val owned = synchronized(control) {
            closed = true
            client.also { client = null }
        }
        owned?.close()
    }

    private companion object { const val MAX_IMAGE_BYTES = 96L * 1024L * 1024L }
}

internal actual fun createJmDownloadSession(
    context: PlatformContext, mangaId: String, chapterId: String, route: DownloadRouteSnapshot,
): JmDownloadSession? {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    if (ref.sourceId != "jmcomic") return null
    require(ref.mangaId.matches(Regex("[0-9]{1,14}")) && ref.chapterId.matches(Regex("[0-9]{1,14}"))) {
        "来源的作品或章节编号无效。"
    }
    return RoutedJmDownloadSession(context, ref.mangaId, ref.chapterId, route.checked())
}
