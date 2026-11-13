package dev.mangaflow.data

import dev.mangaflow.data.sources.BodySource
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.data.sources.MangaDexBodySource
import dev.mangaflow.storage.UnifiedChapterStore
import dev.mangaflow.storage.ReaderCatalogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * A work keeps its existing storage identity. Source-qualified chapter IDs route
 * actual bytes, and the store projects retained raw catalogs into logical chapters.
 */
class UnifiedMangaRepository(
    val copy: CopyMangaRepository,
    val unified: UnifiedChapterStore,
    bodySources: List<BodySource> = listOf(KomiicBodySource(), MangaDexBodySource()),
    private val catalogStore: ReaderCatalogStore? = null,
) : MangaRepository {
    private val adapters = bodySources.associateBy { it.info.id }
    val sources: List<BodySourceInfo> = listOf(
        BodySourceInfo("copy", "拷贝", "简中", "https://www.copy3000.com", ""),
    ) + bodySources.map { it.info }
    private val rawCatalogs = LinkedHashMap<String, MangaDetail>()
    private data class CachedChapterPages(val storedAtNanos: Long, val pages: List<Page>)
    private val pageCache = LinkedHashMap<String, CachedChapterPages>()
    // Fixed stripes keep the lock set bounded while coalescing prepare/reader requests.
    private val pageLocks = Array(16) { Mutex() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = mutableMapOf<String, Deferred<Unit>>()
    private val refreshedAt = mutableMapOf<String, Long>()
    private val slots = Semaphore(2)
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()
    private val _sourceErrors = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val sourceErrors = _sourceErrors.asStateFlow()

    override suspend fun discover(page: Int): MangaPage = copy.discover(page)
    override suspend fun search(query: String, page: Int): MangaPage = copy.search(query, page)
    suspend fun search(query: String, target: SearchTarget, selectedTags: List<MangaTag>, page: Int): MangaPage =
        copy.search(query, target, selectedTags, page)

    suspend fun searchSource(sourceId: String, query: String, page: Int = 0): MangaPage =
        if (sourceId == "copy") copy.search(query, page) else adapter(sourceId).search(query, page)

    /** Native or qualified source IDs are accepted; no inferred title association occurs here. */
    suspend fun detailSource(sourceId: String, mangaId: String): MangaDetail {
        val ref = SourceKeys.decodeManga(mangaId)
        require(!SourceKeys.isEncoded(mangaId) || ref.sourceId == sourceId) { "作品和所选来源不一致。" }
        val id = SourceKeys.manga(sourceId, ref.mangaId)
        val loaded = if (sourceId == "copy") copy.detail(id) else adapter(sourceId).detail(id)
        rememberRaw(loaded)
        catalogStore?.save(loaded)
        return loaded
    }

    override suspend fun detail(mangaId: String): MangaDetail = detail(mangaId) {}

    suspend fun detail(mangaId: String, onMetadata: (Manga) -> Unit): MangaDetail {
        // Linked catalogues refresh even when the primary source is unavailable.
        startRefresh(mangaId, force = false)
        val ref = SourceKeys.decodeManga(mangaId)
        val loaded = if (ref.sourceId == "copy") copy.detail(ref.mangaId, onMetadata)
        else adapter(ref.sourceId).detail(mangaId).also { onMetadata(it.manga) }
        rememberRaw(loaded)
        val result = project(loaded)
        // A slow/refused additional source cannot block the available base catalog.
        startRefresh(mangaId, force = false)
        return result
    }

    fun cachedDetail(mangaId: String): MangaDetail? {
        val retained = synchronized(rawCatalogs) { rawCatalogs[mangaId] }
        val ref = SourceKeys.decodeManga(mangaId)
        val raw = retained ?: if (ref.sourceId == "copy") copy.cachedDetail(ref.mangaId) else null
        return raw?.let(::project)
    }

    fun cachedSourceDetail(mangaId: String): MangaDetail? = synchronized(rawCatalogs) { rawCatalogs[mangaId] }

    /** Retain known work metadata so additional sources remain usable after a primary-source failure. */
    fun retainWork(detail: MangaDetail) {
        rememberRaw(detail)
    }

    /** Explicit retry after linking a source; failures are kept per linked entry. */
    suspend fun refreshLinked(workId: String) {
        startRefresh(workId, force = true)?.await()
    }

    fun rebuild(workId: String): MangaDetail? = cachedDetail(workId)

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        if (ref.sourceId == "copy") return copy.pages(ref.mangaId, ref.chapterId, forceRefresh)
        val key = SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId)
        if (forceRefresh) synchronized(pageCache) { pageCache.remove(key) }
        val lock = pageLocks[(key.hashCode() and Int.MAX_VALUE) % pageLocks.size]
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!forceRefresh) {
                val now = System.nanoTime()
                synchronized(pageCache) {
                    pageCache[key]?.takeIf { now - it.storedAtNanos in 0L until PAGE_CACHE_NANOS }
                        ?.pages
                }?.let { return@withLock it }
            }
            val loaded = adapter(ref.sourceId).pages(
                SourceKeys.manga(ref.sourceId, ref.mangaId), key, forceRefresh,
            ).toList()
            if (loaded.isEmpty()) throw SourceException("这个来源没有返回本章图片，请重试或选择其他来源。")
            currentCoroutineContext().ensureActive()
            synchronized(pageCache) {
                val now = System.nanoTime()
                pageCache.entries.removeAll { now - it.value.storedAtNanos !in 0L until PAGE_CACHE_NANOS }
                pageCache.remove(key)
                pageCache[key] = CachedChapterPages(now, loaded)
                while (pageCache.size > 32) pageCache.remove(pageCache.keys.first())
            }
            loaded
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        return if (ref.sourceId == "copy") copy.imageHeaders(ref.mangaId, ref.chapterId)
        else adapter(ref.sourceId).imageHeaders(SourceKeys.manga(ref.sourceId, ref.mangaId), chapterId)
    }

    suspend fun coverPage(mangaId: String, chapterId: String, forceRefresh: Boolean = false): Page {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        return if (ref.sourceId == "copy") copy.coverPage(ref.mangaId, ref.chapterId, forceRefresh)
        else pages(mangaId, chapterId, forceRefresh).firstOrNull()
            ?: throw SourceException("该来源暂未返回本章封面页。")
    }

    suspend fun searchTagsFor(manga: Manga): List<MangaTag> =
        if (SourceKeys.decodeManga(manga.id).sourceId == "copy") copy.searchTagsFor(manga)
        else emptyList()

    suspend fun byAuthor(manga: Manga, author: String, page: Int = 0): MangaPage =
        if (SourceKeys.decodeManga(manga.id).sourceId == "copy") copy.byAuthor(manga, author, page)
        else throw SourceException("这个来源暂不支持按作者目录查找。", manga.sourceUrl)

    suspend fun comicUuid(mangaId: String): String {
        val ref = SourceKeys.decodeManga(mangaId)
        require(ref.sourceId == "copy") { "只有拷贝作品可以同步到拷贝账号。" }
        return copy.comicUuid(ref.mangaId)
    }

    fun close() = scope.cancel()

    private fun adapter(id: String): BodySource = adapters[id]
        ?: throw SourceException("这个正文来源尚未安装或当前版本不兼容。")

    private fun rememberRaw(detail: MangaDetail) {
        synchronized(rawCatalogs) {
            rawCatalogs.remove(detail.manga.id)
            rawCatalogs[detail.manga.id] = detail
            while (rawCatalogs.size > 96) rawCatalogs.remove(rawCatalogs.keys.first())
        }
    }

    private fun project(base: MangaDetail): MangaDetail {
        val linked = unified.linkedSources(base.manga.id).mapNotNull { link ->
            synchronized(rawCatalogs) { rawCatalogs[link.manga.id] }
        }
        return base.copy(chapters = unified.build(base.manga, base.chapters, linked))
    }

    private fun startRefresh(workId: String, force: Boolean): Deferred<Unit>? = synchronized(refreshes) {
        refreshes[workId]?.takeIf { it.isActive }?.let { return@synchronized it }
        if (!force && System.currentTimeMillis() - (refreshedAt[workId] ?: 0L) < 60_000L) return@synchronized null
        val links = unified.linkedSources(workId)
        if (links.isEmpty()) {
            _sourceErrors.update { it - workId }
            return@synchronized null
        }
        val job = scope.async(start = CoroutineStart.LAZY) {
            val errors = supervisorScope {
                links.map { link ->
                    async {
                        try {
                            slots.withPermit {
                                if (cachedSourceDetail(link.manga.id) == null) {
                                    catalogStore?.read(link.manga.id)?.let {
                                        rememberRaw(it)
                                        _catalogRevision.update { value -> value + 1 }
                                    }
                                }
                                withTimeout(25_000L) { detailSource(link.manga.sourceId, link.manga.id) }
                            }
                            null
                        } catch (cancel: CancellationException) {
                            // A source timeout is a reported failure; external cancellation propagates.
                            if (cancel is kotlinx.coroutines.TimeoutCancellationException)
                                link.manga.id to "这个来源响应超时，保留上次可用目录。"
                            else throw cancel
                        } catch (failure: Exception) {
                            link.manga.id to (failure.message ?: "这个来源暂时不可用，保留上次目录。")
                        }
                    }
                }.mapNotNull { it.await() }.toMap()
            }
            _sourceErrors.update { current -> current + (workId to errors) }
            synchronized(refreshes) { refreshedAt[workId] = System.currentTimeMillis() }
            _catalogRevision.update { it + 1 }
        }
        refreshes[workId] = job
        job.invokeOnCompletion { synchronized(refreshes) { if (refreshes[workId] === job) refreshes.remove(workId) } }
        job.start()
        job
    }

    private companion object {
        const val PAGE_CACHE_NANOS = 60_000_000_000L
    }
}
