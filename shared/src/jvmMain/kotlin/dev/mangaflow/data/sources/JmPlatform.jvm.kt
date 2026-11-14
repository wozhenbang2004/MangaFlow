package dev.mangaflow.data.sources

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.syncStorageFile
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.SearchQuery
import io.github.jukomu.jmcomic.core.JmComic
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Built-in JM source, reusing the maintained MIT-licensed JVM client for authenticated
 * requests and image segment restoration. No JS engines and no second reader.
 *
 * The shared bridge runs on Android and desktop; iOS does not advertise this adapter.
 */
internal class JvmJmBodySource(context: PlatformContext) : BodySource {
    override val info = BodySourceInfo("jmcomic", "禁漫", "简中", "https://18comic.vip", "", adult = true)
    private val mutex = Mutex()
    private val root = StorageFile(cacheDirectory(context), "jm-decoded")
    private var client: JmApiClient? = null
    private val photoImages = LinkedHashMap<String, List<JmImage>>()

    private suspend fun <T> clientCall(operation: (JmApiClient) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val active = client ?: try {
                val config = JmConfiguration.Builder().clientType(ClientType.API)
                    .retryTimes(2).cacheSize(24 * 1024 * 1024).build()
                JmComic.newApiClientAsync(config).join().also { client = it }
            } catch (failure: Exception) {
                throw SourceException("禁漫客户端初始化失败，可能是站点线路不可用：${failure.cause?.message ?: failure.message}", info.homeUrl, cause = failure)
            }
            currentCoroutineContext().ensureActive()
            try { operation(active) } catch (failure: Exception) {
                throw SourceException("禁漫请求失败：${failure.cause?.message ?: failure.message}", info.homeUrl, cause = failure)
            }
        }
    }

    override suspend fun discover(page: Int): MangaPage {
        checkPage(page)
        return clientCall { client -> toPage(client.getLatest(page + 1), page) }
    }

    override suspend fun search(query: String, page: Int): MangaPage {
        checkPage(page)
        require(query.trim().length in 1..120) { "请输入有效的搜索词。" }
        return clientCall { client ->
            val args = SearchQuery.Builder().text(query.trim()).page(page + 1).build()
            toPage(client.search(args), page)
        }
    }

    override suspend fun detail(mangaId: String): MangaDetail {
        val id = validId(SourceKeys.decodeManga(mangaId).mangaId)
        return clientCall { client ->
            val album = client.getAlbum(id)
            val title = album.title()?.trim().orEmpty().ifBlank { "JM$id" }
            val works = (album.photoMetas() ?: emptyList()).take(10_000)
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
        val rows = page.content()?.take(200).orEmpty().mapNotNull { item ->
            runCatching { manga(item) }.getOrNull()
        }.distinctBy(Manga::id)
        val more = page.currentPage() < page.totalPages() && page.totalPages() > 0 && rows.isNotEmpty()
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
    private fun checkPage(page: Int) = require(page in 0..499) { "禁漫分页超出范围。" }

    companion object {
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
