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
import kotlinx.coroutines.withContext

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
    private data class SourceEligibility(val ids: Set<String>, val generation: Long)
    @Volatile private var sourceEligibility = SourceEligibility(sources.map { it.id }.toSet(), 0L)
    private val rawCatalogs = LinkedHashMap<String, MangaDetail>()
    private data class CatalogStamp(val generation: Long, val at: Long)
    private val catalogFetchedAt = LinkedHashMap<String, CatalogStamp>()
    private val catalogLocks = Array(16) { Mutex() }
    private data class SearchKey(val source: String, val query: String, val page: Int)
    private data class CachedSearch(val storedAt: Long, val generation: Long, val page: MangaPage)
    private val sourceSearchCache = LinkedHashMap<SearchKey, CachedSearch>()
    private val searchLocks = Array(16) { Mutex() }
    private data class CachedChapterPages(val storedAtNanos: Long, val pages: List<Page>)
    private val pageCache = LinkedHashMap<String, CachedChapterPages>()
    // Fixed stripes keep the lock set bounded while coalescing prepare/reader requests.
    private val pageLocks = Array(16) { Mutex() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class RefreshRun(val eligibilityGeneration: Long, val job: Deferred<Unit>)
    private val refreshes = mutableMapOf<String, RefreshRun>()
    private val refreshedAt = mutableMapOf<String, Long>()
    private val slots = Semaphore(2)
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()
    private val _sourceErrors = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val sourceErrors = _sourceErrors.asStateFlow()
    private val _sourceDiscovery = MutableStateFlow<Map<String, Map<String, SourceDiscoveryResult>>>(emptyMap())
    val sourceDiscovery = _sourceDiscovery.asStateFlow()
    private data class DiscoveryRun(val signature: String, val generation: Long,
        val eligibilityGeneration: Long, val job: Deferred<Unit>)
    private val discoveries = mutableMapOf<String, DiscoveryRun>()
    private val discoveryGenerations = mutableMapOf<String, Long>()
    private val discoveryCompleted = mutableMapOf<String, Pair<String, Long>>()
    private var discoveryCacheEpoch = 0L
    private val pageCacheGenerations = mutableMapOf<String, Long>()
    private val searchTerms = LinkedHashMap<String, MutableMap<String, String>>()

    /** Installed sources remain readable; this eligibility controls search and automatic matching. */
    fun enabledSources(): Set<String> = sourceEligibility.ids.toSet()

    fun setEnabledSources(ids: Set<String>) {
        val registered = sources.map { it.id }.toSet()
        val next = ids.intersect(registered)
        synchronized(discoveries) {
            if (sourceEligibility.ids == next) return
            sourceEligibility = SourceEligibility(next, sourceEligibility.generation + 1L)
            val obsolete = discoveries.values.toList()
            discoveries.keys.forEach { workId ->
                discoveryGenerations[workId] = (discoveryGenerations[workId] ?: 0L) + 1L
            }
            discoveries.clear()
            discoveryCacheEpoch += 1L
            discoveryCompleted.clear()
            _sourceDiscovery.value = emptyMap()
            obsolete.forEach { it.job.cancel() }
        }
        synchronized(refreshes) {
            val obsolete = refreshes.filterValues { it.eligibilityGeneration != sourceEligibility.generation }.toList()
            obsolete.forEach { (workId, run) ->
                refreshes.remove(workId)
                run.job.cancel()
            }
            refreshedAt.clear()
            _sourceErrors.value = emptyMap()
        }
    }

    private fun ensureEligibility(expected: SourceEligibility, sourceId: String? = null) {
        if (sourceEligibility.generation != expected.generation || sourceId != null && sourceId !in expected.ids)
            throw CancellationException("来源搜索配置已更改。")
    }

    override suspend fun discover(page: Int): MangaPage = copy.discover(page)
    override suspend fun search(query: String, page: Int): MangaPage = copy.search(query, page)
    suspend fun search(query: String, target: SearchTarget, selectedTags: List<MangaTag>, page: Int): MangaPage =
        copy.search(query, target, selectedTags, page)

    suspend fun searchSource(sourceId: String, query: String, page: Int = 0, forceRefresh: Boolean = false): MangaPage {
        val key = SearchKey(sourceId, query.trim(), page)
        val requestedAt = System.nanoTime()
        return searchLocks[(key.hashCode() and Int.MAX_VALUE) % searchLocks.size].withLock {
            val generation = synchronized(pageCache) { pageCacheGenerations[sourceId] ?: 0L }
            synchronized(sourceSearchCache) {
                sourceSearchCache[key]?.takeIf { it.generation == generation &&
                    System.nanoTime() - it.storedAt in 0L until
                        (if (it.page.items.isEmpty()) EMPTY_SEARCH_CACHE_NANOS else DISCOVERY_TTL_NANOS) &&
                    (!forceRefresh || it.storedAt >= requestedAt) }?.page
            }?.let { return@withLock it }
            val result = if (sourceId == "copy") copy.search(key.query, page) else adapter(sourceId).search(key.query, page)
            if (synchronized(pageCache) { (pageCacheGenerations[sourceId] ?: 0L) == generation }) synchronized(sourceSearchCache) {
                sourceSearchCache.remove(key)
                if (result.items.size <= MAX_SEARCH_CACHE_HITS)
                    sourceSearchCache[key] = CachedSearch(System.nanoTime(), generation, result)
                while (sourceSearchCache.size > MAX_SEARCH_CACHE_PAGES ||
                    sourceSearchCache.values.sumOf { it.page.items.size } > MAX_SEARCH_CACHE_HITS)
                    sourceSearchCache.remove(sourceSearchCache.keys.first())
            }
            result
        }
    }

    /** Each callback is an immutable cumulative snapshot of this requested batch only. */
    suspend fun searchAll(query: String, pages: Map<String, Int>? = null, forceRefresh: Boolean = false,
        onUpdate: (FederatedSearchPage) -> Unit = {}): FederatedSearchPage = withContext(Dispatchers.IO) {
        val text = query.trim()
        require(text.length in 1..120) { "请输入 1–120 个字的漫画标题。" }
        val eligibility = sourceEligibility
        if (eligibility.ids.isEmpty()) throw SourceException("请先在设置中启用漫画源")
        val requested = (pages?.toMap() ?: sources.associate { it.id to 0 }).filterKeys { it in eligibility.ids }
        val infos = sources.associateBy(BodySourceInfo::id)
        val states = LinkedHashMap<String, FederatedSearchSource>()
        requested.forEach { (id, _) ->
            states[id] = FederatedSearchSource(id, infos[id]?.name ?: sourceDisplayName(id), loading = true)
        }
        val hits = LinkedHashMap<Pair<String, String>, Manga>()
        val updateLock = Mutex()
        fun snapshot() = FederatedSearchPage(hits.values.toList(), states.toMap())
        ensureEligibility(eligibility)
        onUpdate(snapshot())
        // Different sources start independently; each adapter retains its own request limits.
        supervisorScope {
            requested.map { (sourceId, page) -> async {
                var found: List<Manga> = emptyList()
                val state = try {
                    require(sourceId in infos) { "这个正文来源尚未安装。" }
                    require(page >= 0) { "搜索分页无效。" }
                    ensureEligibility(eligibility, sourceId)
                    val result = withTimeout(15_000L) { searchSourceTerms(sourceId, text, page, forceRefresh) }
                    found = result.items.distinctBy(::federatedMangaIdentity)
                    val next = if (result.hasMore) result.nextPage ?: (page + 1) else null
                    require(next == null || next > page) { "该来源没有返回有效的下一页。" }
                    FederatedSearchSource(sourceId, infos.getValue(sourceId).name,
                        hasMore = result.hasMore, nextPage = next)
                } catch (cancel: CancellationException) {
                    if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                    FederatedSearchSource(sourceId, infos[sourceId]?.name ?: sourceDisplayName(sourceId),
                        error = "该来源搜索超时，请重试。", failedPage = page)
                } catch (failure: Exception) {
                    FederatedSearchSource(sourceId, infos[sourceId]?.name ?: sourceDisplayName(sourceId),
                        error = failure.message?.take(300) ?: "该来源暂时无法搜索，请重试。", failedPage = page)
                }
                currentCoroutineContext().ensureActive()
                updateLock.withLock {
                    ensureEligibility(eligibility, sourceId)
                    found.forEach { hits[federatedMangaIdentity(it)] = it }
                    states[sourceId] = state
                    onUpdate(snapshot())
                }
            } }.forEach { it.await() }
        }
        ensureEligibility(eligibility)
        snapshot()
    }

    private suspend fun searchSourceTerms(sourceId: String, query: String, page: Int, forceRefresh: Boolean): MangaPage {
        val retained = if (page > 0) synchronized(searchTerms) { searchTerms[query]?.get(sourceId) } else null
        val terms = retained?.let(::listOf) ?: bodySourceSearchTerms(query)
        var result = MangaPage(emptyList(), false)
        for (term in terms) {
            currentCoroutineContext().ensureActive()
            result = searchSource(sourceId, term, page, forceRefresh = forceRefresh)
            if (result.items.isNotEmpty()) {
                synchronized(searchTerms) {
                    val selected = searchTerms.remove(query) ?: mutableMapOf()
                    selected[sourceId] = term
                    searchTerms[query] = selected
                    while (searchTerms.size > 32) searchTerms.remove(searchTerms.keys.first())
                }
                break
            }
        }
        return result
    }

    /** Start independent source discovery; the existing work and readable directory stay visible. */
    fun discoverSources(work: Manga, aliases: List<String> = emptyList(), force: Boolean = false) {
        if (work.sourceUrl == dev.mangaflow.storage.DownloadStore.LOCAL_SOURCE_URL || work.title.isBlank()) return
        val baseline = cachedSourceDetail(work.id) ?: if (work.sourceId == "copy") copy.cachedDetail(work.id) else null
        val identity = SourceDiscoveryIdentity.signature(work, aliases, baseline)
        synchronized(discoveries) {
            val eligibility = sourceEligibility
            if (eligibility.ids.isEmpty()) return
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
            val cacheEpoch = discoveryCacheEpoch
            val job = scope.async(start = CoroutineStart.LAZY) {
                supervisorScope {
                    sources.filter { it.id != work.sourceId && it.id in eligibility.ids }.map { info ->
                        async {
                            ensureEligibility(eligibility, info.id)
                            publishDiscovery(work.id, generation,
                                SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.SEARCHING))
                            try {
                                slots.withPermit {
                                    withTimeout(25_000L) { discoverOneSource(work, aliases, baseline, info, generation, force, eligibility) }
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
            discoveries[work.id] = DiscoveryRun(identity, generation, eligibility.generation, job)
            job.invokeOnCompletion { failure -> synchronized(discoveries) {
                if (discoveries[work.id]?.job === job) {
                    discoveries.remove(work.id)
                    if (failure == null && cacheEpoch == discoveryCacheEpoch &&
                        eligibility.generation == sourceEligibility.generation)
                        discoveryCompleted[work.id] = identity to System.nanoTime()
                    while (discoveryCompleted.size > 64) discoveryCompleted.remove(discoveryCompleted.keys.first())
                }
            } }
            job.start()
        }
    }

    /** Origin/settings changes must not reuse a chapter URL from the previous source session. */
    fun invalidatePageCache(sourceId: String) {
        synchronized(pageCache) {
            pageCacheGenerations[sourceId] = (pageCacheGenerations[sourceId] ?: 0L) + 1L
            pageCache.keys.removeAll { it.startsWith("@source/$sourceId/") }
        }
        synchronized(rawCatalogs) { catalogFetchedAt.keys.removeAll { SourceKeys.decodeManga(it).sourceId == sourceId } }
        synchronized(sourceSearchCache) { sourceSearchCache.keys.removeAll { it.source == sourceId } }
        synchronized(searchTerms) {
            searchTerms.values.forEach { it.remove(sourceId) }
            searchTerms.entries.removeAll { it.value.isEmpty() }
        }
        synchronized(discoveries) {
            // Completion TTL is work-wide; an in-flight scan of the former origin
            // must not refill it after this explicit source configuration change.
            discoveryCacheEpoch += 1L
            discoveryCompleted.clear()
        }
    }

    private fun retainedSourceCount(workId: String, sourceId: String): Int =
        unified.linkedSources(workId).filter { it.manga.sourceId == sourceId }.sumOf {
            cachedSourceDetail(it.manga.id)?.chapters?.size ?: 0
        }

    private suspend fun discoverOneSource(work: Manga, aliases: List<String>, baseline: MangaDetail?,
        info: BodySourceInfo, generation: Long, force: Boolean, eligibility: SourceEligibility) {
        ensureEligibility(eligibility, info.id)
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
                ensureEligibility(eligibility, info.id)
                if (cachedSourceDetail(link.manga.id) == null) catalogStore?.read(link.manga.id)?.let {
                    ensureEligibility(eligibility, info.id)
                    rememberRaw(it)
                    _catalogRevision.update { revision -> revision + 1L }
                }
                val loaded = detailSource(info.id, link.manga.id, forceRefresh = force)
                ensureEligibility(eligibility, info.id)
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
            ensureEligibility(eligibility, info.id)
            results = searchSource(info.id, query, forceRefresh = force).items.distinctBy(Manga::id)
            ensureEligibility(eligibility, info.id)
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
            ensureEligibility(eligibility, info.id)
            val loaded = detailSource(info.id, candidate.id, forceRefresh = force)
            ensureEligibility(eligibility, info.id)
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
        val selected = verified.single()
        val variant = selected.chapters.firstNotNullOfOrNull { it.variants.firstOrNull() }
        val context = currentCoroutineContext()
        val added = synchronized(discoveries) {
            context.ensureActive()
            ensureEligibility(eligibility, info.id)
            if (!isCurrentDiscovery(work.id, generation)) return
            // Eligibility mutation uses this same lock, so a disabled source cannot
            // slip between the final check and the synchronous association commit.
            unified.attachAutomatically(work.id, selected.manga,
                language = variant?.language ?: info.language, edition = "连载版", sameChapterSystem = true,
                evidence = "完整标题与作者一致，至少三个正常话号及串行版别对应。")
        }
        ensureEligibility(eligibility, info.id)
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
        discoveryGenerations[workId] == generation &&
            discoveries[workId]?.eligibilityGeneration == sourceEligibility.generation
    }

    private fun publishDiscovery(workId: String, generation: Long, result: SourceDiscoveryResult) {
        synchronized(discoveries) {
            if (discoveryGenerations[workId] != generation ||
                discoveries[workId]?.eligibilityGeneration != sourceEligibility.generation ||
                result.sourceId !in sourceEligibility.ids) return
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
    suspend fun detailSource(sourceId: String, mangaId: String, forceRefresh: Boolean = false): MangaDetail {
        val ref = SourceKeys.decodeManga(mangaId)
        require(!SourceKeys.isEncoded(mangaId) || ref.sourceId == sourceId) { "作品和所选来源不一致。" }
        val id = SourceKeys.manga(sourceId, ref.mangaId)
        return catalogLocks[(id.hashCode() and Int.MAX_VALUE) % catalogLocks.size].withLock {
            val generation = synchronized(pageCache) { pageCacheGenerations[sourceId] ?: 0L }
            if (!forceRefresh) synchronized(rawCatalogs) {
                rawCatalogs[id]?.takeIf { catalogFetchedAt[id]?.let { stamp ->
                    stamp.generation == generation && System.nanoTime() - stamp.at in 0L until CATALOG_CACHE_NANOS
                } == true }
            }?.let { return@withLock it }
            val loaded = if (sourceId == "copy") copy.detail(id) else adapter(sourceId).detail(id)
            rememberRaw(loaded)
            if (synchronized(pageCache) { (pageCacheGenerations[sourceId] ?: 0L) == generation })
                synchronized(rawCatalogs) { catalogFetchedAt[id] = CatalogStamp(generation, System.nanoTime()) }
            catalogStore?.save(loaded)
            loaded
        }
    }

    override suspend fun detail(mangaId: String): MangaDetail = detail(mangaId) {}

    suspend fun detail(mangaId: String, onMetadata: (Manga) -> Unit): MangaDetail {
        // Linked catalogues refresh even when the primary source is unavailable.
        startRefresh(mangaId, force = false)
        val ref = SourceKeys.decodeManga(mangaId)
        val loaded = if (ref.sourceId == "copy") copy.detail(ref.mangaId, onMetadata)
        else adapter(ref.sourceId).detail(mangaId).also { onMetadata(it.manga) }
        rememberRaw(loaded)
        val result = withContext(Dispatchers.Default) { project(loaded) }
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
        if (chapterId.isBlank()) {
            val cover = SourceKeys.decodeManga(mangaId)
            if (cover.sourceId == "copy") return copy.imageHeaders(cover.mangaId, "")
            return mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to adapter(cover.sourceId).info.homeUrl.trimEnd('/') + "/",
                "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8",
            )
        }
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
            catalogFetchedAt.remove(detail.manga.id)
            rawCatalogs.remove(detail.manga.id)
            rawCatalogs[detail.manga.id] = detail
            while (rawCatalogs.size > 96) {
                val oldest = rawCatalogs.keys.first()
                rawCatalogs.remove(oldest)
                catalogFetchedAt.remove(oldest)
            }
        }
    }

    private fun project(base: MangaDetail): MangaDetail {
        val linked = unified.linkedSources(base.manga.id).mapNotNull { link ->
            synchronized(rawCatalogs) { rawCatalogs[link.manga.id] }
        }
        return base.copy(chapters = unified.build(base.manga, base.chapters, linked))
    }

    private fun startRefresh(workId: String, force: Boolean): Deferred<Unit>? = synchronized(refreshes) {
        val eligibility = sourceEligibility
        refreshes[workId]?.takeIf { it.eligibilityGeneration == eligibility.generation && it.job.isActive }
            ?.let { return@synchronized it.job }
        refreshes[workId]?.job?.cancel()
        if (!force && System.currentTimeMillis() - (refreshedAt[workId] ?: 0L) < 60_000L) return@synchronized null
        val links = unified.linkedSources(workId).filter { it.manga.sourceId in eligibility.ids }
        if (links.isEmpty()) {
            if (eligibility.generation == sourceEligibility.generation) _sourceErrors.update { it - workId }
            return@synchronized null
        }
        val job = scope.async(start = CoroutineStart.LAZY) {
            ensureEligibility(eligibility)
            val errors = supervisorScope {
                links.map { link ->
                    async {
                        try {
                            slots.withPermit {
                                ensureEligibility(eligibility, link.manga.sourceId)
                                if (cachedSourceDetail(link.manga.id) == null) {
                                    catalogStore?.read(link.manga.id)?.let {
                                        ensureEligibility(eligibility, link.manga.sourceId)
                                        rememberRaw(it)
                                        _catalogRevision.update { value -> value + 1 }
                                    }
                                }
                                withTimeout(25_000L) { detailSource(link.manga.sourceId, link.manga.id, forceRefresh = force) }
                                ensureEligibility(eligibility, link.manga.sourceId)
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
            synchronized(refreshes) {
                ensureEligibility(eligibility)
                _sourceErrors.update { current -> current + (workId to errors) }
                refreshedAt[workId] = System.currentTimeMillis()
                _catalogRevision.update { it + 1 }
            }
        }
        refreshes[workId] = RefreshRun(eligibility.generation, job)
        job.invokeOnCompletion { synchronized(refreshes) { if (refreshes[workId]?.job === job) refreshes.remove(workId) } }
        job.start()
        job
    }

    private companion object {
        const val PAGE_CACHE_NANOS = 60_000_000_000L
        const val DISCOVERY_TTL_NANOS = 300_000_000_000L
        const val EMPTY_SEARCH_CACHE_NANOS = 60_000_000_000L
        const val MAX_SEARCH_CACHE_PAGES = 64
        const val MAX_SEARCH_CACHE_HITS = 1_200
        const val CATALOG_CACHE_NANOS = 60_000_000_000L
    }
}
