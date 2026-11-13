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
    private val _sourceDiscovery = MutableStateFlow<Map<String, Map<String, SourceDiscoveryResult>>>(emptyMap())
    val sourceDiscovery = _sourceDiscovery.asStateFlow()
    private data class DiscoveryRun(val signature: String, val generation: Long, val job: Deferred<Unit>)
    private val discoveries = mutableMapOf<String, DiscoveryRun>()
    private val discoveryGenerations = mutableMapOf<String, Long>()
    private val discoveryCompleted = mutableMapOf<String, Pair<String, Long>>()
    private val pageCacheGenerations = mutableMapOf<String, Long>()

    override suspend fun discover(page: Int): MangaPage = copy.discover(page)
    override suspend fun search(query: String, page: Int): MangaPage = copy.search(query, page)
    suspend fun search(query: String, target: SearchTarget, selectedTags: List<MangaTag>, page: Int): MangaPage =
        copy.search(query, target, selectedTags, page)

    suspend fun searchSource(sourceId: String, query: String, page: Int = 0): MangaPage =
        if (sourceId == "copy") copy.search(query, page) else adapter(sourceId).search(query, page)

    /** Start independent source discovery; the existing work and readable directory stay visible. */
    fun discoverSources(work: Manga, aliases: List<String> = emptyList(), force: Boolean = false) {
        if (work.sourceUrl == dev.mangaflow.storage.DownloadStore.LOCAL_SOURCE_URL || work.title.isBlank()) return
        val baseline = cachedSourceDetail(work.id) ?: if (work.sourceId == "copy") copy.cachedDetail(work.id) else null
        val identity = SourceDiscoveryIdentity.signature(work, aliases, baseline)
        synchronized(discoveries) {
            // Only the foreground work owns automatic search slots. Already loaded
            // catalogues and explicit refresh/link jobs have independent lifetimes.
            discoveries.filterKeys { it != work.id }.toList().forEach { (otherWorkId, run) ->
                discoveries.remove(otherWorkId)
                discoveryGenerations[otherWorkId] = (discoveryGenerations[otherWorkId] ?: 0L) + 1L
                run.job.cancel()
            }
            val active = discoveries[work.id]
            if (active?.job?.isActive == true && active.signature == identity && !force) return
            val completed = discoveryCompleted[work.id]
            val elapsed = System.nanoTime() - (completed?.second ?: 0L)
            if (!force && active == null && completed?.first == identity && elapsed in 0L until DISCOVERY_TTL_NANOS) return
            active?.job?.cancel()
            val generation = (discoveryGenerations[work.id] ?: 0L) + 1L
            discoveryGenerations[work.id] = generation
            val job = scope.async(start = CoroutineStart.LAZY) {
                supervisorScope {
                    sources.filter { it.id != work.sourceId }.map { info ->
                        async {
                            publishDiscovery(work.id, generation,
                                SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.SEARCHING))
                            try {
                                slots.withPermit {
                                    withTimeout(25_000L) { discoverOneSource(work, aliases, baseline, info, generation) }
                                }
                            } catch (cancel: CancellationException) {
                                if (cancel is kotlinx.coroutines.TimeoutCancellationException) {
                                    publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                                        SourceDiscoveryStatus.FAILED, retainedSourceCount(work.id, info.id),
                                        "该来源响应超时，已保留上次可用章节。"))
                                } else throw cancel
                            } catch (failure: Exception) {
                                publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                                    SourceDiscoveryStatus.FAILED, retainedSourceCount(work.id, info.id),
                                    failure.message ?: "该来源暂时不可用，已保留上次可用章节。"))
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            discoveries[work.id] = DiscoveryRun(identity, generation, job)
            job.invokeOnCompletion { failure -> synchronized(discoveries) {
                if (discoveries[work.id]?.job === job) {
                    discoveries.remove(work.id)
                    if (failure == null) discoveryCompleted[work.id] = identity to System.nanoTime()
                    while (discoveryCompleted.size > 64) discoveryCompleted.remove(discoveryCompleted.keys.first())
                }
            } }
            job.start()
        }
    }

    /** Origin/settings changes must not reuse a chapter URL from the previous source session. */
    fun invalidatePageCache(sourceId: String) = synchronized(pageCache) {
        pageCacheGenerations[sourceId] = (pageCacheGenerations[sourceId] ?: 0L) + 1L
        pageCache.keys.removeAll { it.startsWith("@source/$sourceId/") }
        Unit
    }

    private fun retainedSourceCount(workId: String, sourceId: String): Int =
        unified.linkedSources(workId).filter { it.manga.sourceId == sourceId }.sumOf {
            cachedSourceDetail(it.manga.id)?.chapters?.size ?: 0
        }

    private suspend fun discoverOneSource(work: Manga, aliases: List<String>, baseline: MangaDetail?,
        info: BodySourceInfo, generation: Long) {
        fun report(status: SourceDiscoveryStatus, message: String = "", candidates: List<Manga> = emptyList(), chapters: Int = 0) =
            publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name, status, chapters, message, candidates))
        val settings = unified.settings(work.id)
        if (info.id in settings.excludedSources) {
            report(SourceDiscoveryStatus.DISABLED, "已由你移除此来源，不会自动重新关联。")
            return
        }
        val linked = settings.links.filter { it.manga.sourceId == info.id }
        if (linked.isNotEmpty()) {
            var count = 0
            linked.forEach { link ->
                if (cachedSourceDetail(link.manga.id) == null) catalogStore?.read(link.manga.id)?.let {
                    rememberRaw(it)
                    _catalogRevision.update { revision -> revision + 1L }
                }
                val loaded = detailSource(info.id, link.manga.id)
                count += loaded.chapters.size
                _sourceErrors.update { errors ->
                    errors + (work.id to (errors[work.id].orEmpty() - link.manga.id))
                }
                _catalogRevision.update { revision -> revision + 1L }
            }
            if (info.id in unified.settings(work.id).excludedSources) report(SourceDiscoveryStatus.DISABLED)
            else report(SourceDiscoveryStatus.LINKED, chapters = count)
            return
        }

        var results: List<Manga> = emptyList()
        for (query in bodySourceSearchTerms(work.title, aliases)) {
            currentCoroutineContext().ensureActive()
            results = searchSource(info.id, query).items.distinctBy(Manga::id)
            if (results.isNotEmpty()) break
        }
        if (results.isEmpty()) {
            report(SourceDiscoveryStatus.NO_MATCH, "该来源暂未找到这部作品。")
            return
        }
        val exact = results.filter { SourceDiscoveryIdentity.titleMatches(work, it, aliases) }
        if (exact.isEmpty()) {
            report(SourceDiscoveryStatus.NEEDS_CONFIRMATION, "搜索到相近作品，完整标题尚未对应。", results.take(6))
            return
        }
        if (exact.size > 4) {
            report(SourceDiscoveryStatus.NEEDS_CONFIRMATION, "存在多个同名版本，请确认具体作品。", exact.take(6))
            return
        }
        val verified = mutableListOf<MangaDetail>()
        val candidates = mutableListOf<Manga>()
        for (candidate in exact.sortedBy(Manga::id)) {
            val loaded = detailSource(info.id, candidate.id)
            candidates += loaded.manga
            if (SourceDiscoveryIdentity.titleMatches(work, loaded.manga, aliases) &&
                SourceDiscoveryIdentity.authorsMatch(work, loaded.manga) &&
                SourceDiscoveryIdentity.chapterSystemMatches(baseline, loaded)) verified += loaded
        }
        if (verified.size != 1) {
            report(SourceDiscoveryStatus.NEEDS_CONFIRMATION,
                if (verified.size > 1) "同名作品存在多个可读版本，请确认来源版本。"
                else "已找到同名条目，作者或章节体系仍需确认。", candidates.distinctBy(Manga::id).take(6))
            return
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentDiscovery(work.id, generation)) return
        val selected = verified.single()
        val variant = selected.chapters.firstNotNullOfOrNull { it.variants.firstOrNull() }
        val added = unified.attachAutomatically(work.id, selected.manga,
            language = variant?.language ?: info.language, edition = "连载版", sameChapterSystem = true,
            evidence = "完整标题与作者一致，至少三个正常话号及串行版别对应。")
        val fresh = unified.settings(work.id)
        when {
            info.id in fresh.excludedSources -> report(SourceDiscoveryStatus.DISABLED)
            added || fresh.links.any { it.manga.sourceId == info.id } -> {
                report(SourceDiscoveryStatus.LINKED, "已核对作品与章节并合并。",
                    chapters = if (added) selected.chapters.size else retainedSourceCount(work.id, info.id))
                _catalogRevision.update { it + 1L }
            }
            else -> report(SourceDiscoveryStatus.NEEDS_CONFIRMATION, "来源关联已更改，请重新确认。", listOf(selected.manga))
        }
    }

    private fun isCurrentDiscovery(workId: String, generation: Long): Boolean = synchronized(discoveries) {
        discoveryGenerations[workId] == generation
    }

    private fun publishDiscovery(workId: String, generation: Long, result: SourceDiscoveryResult) {
        synchronized(discoveries) {
            if (discoveryGenerations[workId] != generation) return
            _sourceDiscovery.update { current ->
                LinkedHashMap(current).apply {
                    remove(workId)
                    put(workId, current[workId].orEmpty() + (result.sourceId to result))
                    while (size > 64) remove(keys.first())
                }
            }
        }
    }

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
            val generation = synchronized(pageCache) { pageCacheGenerations[ref.sourceId] ?: 0L }
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
                if ((pageCacheGenerations[ref.sourceId] ?: 0L) == generation)
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
        const val DISCOVERY_TTL_NANOS = 300_000_000_000L
    }
}
