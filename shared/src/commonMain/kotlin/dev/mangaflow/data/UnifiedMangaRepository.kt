package dev.mangaflow.data

import dev.mangaflow.platform.*
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

import dev.mangaflow.data.sources.BodySource
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.data.sources.MangaDexBodySource
import dev.mangaflow.data.plugins.ScriptBodySource
import dev.mangaflow.storage.UnifiedChapterStore
import dev.mangaflow.storage.UnifiedWorkSettings
import dev.mangaflow.storage.ReaderCatalogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    private val builtinAdapters = bodySources.associateBy { it.info.id }
    private val copyInfo = BodySourceInfo("copy", "拷贝", "简中", "https://www.copy3000.com", "")
    private data class SourceSnapshot(val adapters: Map<String, BodySource>, val infos: List<BodySourceInfo>,
        val importedDigests: Map<String, String>)
    @Volatile private var sourceSnapshot = SourceSnapshot(builtinAdapters, listOf(copyInfo) + bodySources.map { it.info }, emptyMap())
    private val adapters: Map<String, BodySource> get() = sourceSnapshot.adapters
    val sources: List<BodySourceInfo> get() = sourceSnapshot.infos
    private data class SourceEligibility(val ids: Set<String>, val generation: Long)
    @Volatile private var sourceEligibility = SourceEligibility(sources.map { it.id }.toSet(), 0L)
    private val rawCatalogs = LinkedHashMap<String, MangaDetail>()
    // Restored projections may be displayed, but only adapter-loaded directories can prove numbering.
    private val nativeCatalogIds = mutableSetOf<String>()
    private data class CatalogStamp(val generation: Long, val at: Long)
    private val catalogFetchedAt = LinkedHashMap<String, CatalogStamp>()
    private class CatalogGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val catalogGates = mutableMapOf<String, CatalogGate>()
    private data class SearchKey(val source: String, val query: String, val page: Int)
    private data class CachedSearch(val storedAt: Long, val generation: Long, val page: MangaPage)
    private val sourceSearchCache = LinkedHashMap<SearchKey, CachedSearch>()
    private val searchLocks = Array(16) { Mutex() }
    private data class CachedChapterPages(val storedAtNanos: Long, val pages: List<Page>)
    private val pageCache = LinkedHashMap<String, CachedChapterPages>()
    private class ChapterPageRequest(val generation: Long, val result: Deferred<List<Page>>, var readers: Int = 0)
    // Only an identical chapter shares work; unrelated chapter/cover requests never share a stripe.
    private val pageRequests = mutableMapOf<String, ChapterPageRequest>()
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
    private data class DiscoveryIdentity(val work: Manga, val aliases: List<String>, val conflictingIds: Set<String>)
    private val discoveryIdentities = LinkedHashMap<String, DiscoveryIdentity>()
    private data class IdentityCatalogAttempt(val workId: String, val mangaId: String,
        val eligibilityGeneration: Long, val sourceGeneration: Long)
    private val identityCatalogAttempts = linkedSetOf<IdentityCatalogAttempt>()
    private val discoveryGenerations = mutableMapOf<String, Long>()
    private val discoveryCompleted = mutableMapOf<String, Pair<String, Long>>()
    private var discoveryCacheEpoch = 0L
    private val pageCacheGenerations = mutableMapOf<String, Long>()
    private val searchTerms = LinkedHashMap<String, MutableMap<String, String>>()

    /** Installed sources remain readable; this eligibility controls search and automatic matching. */
    fun enabledSources(): Set<String> = sourceEligibility.ids.toSet()

    fun sourceConfigurationKey(): String {
        val snapshot = sourceSnapshot
        val value = sourceEligibility.ids.sorted().joinToString("|") { "$it:${snapshot.importedDigests[it].orEmpty()}" }
        return sha256(value.encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    fun replaceImportedSources(adapters: List<BodySource>) {
        require(adapters.size <= 64 && adapters.map { it.info.id }.distinct().size == adapters.size) { "导入源数量或身份无效。" }
        require(adapters.all { it is ScriptBodySource && it.info.id.matches(Regex("js_[a-f0-9]{36}")) &&
            it.info.id !in builtinAdapters && it.info.id != "copy" && it.info.id != "local" }) { "导入源不能覆盖内置来源。" }
        val imported = adapters.associateBy { it.info.id }
        val digests = adapters.associate { it.info.id to (it as ScriptBodySource).digest }
        val next = SourceSnapshot(builtinAdapters + imported, listOf(copyInfo) + builtinAdapters.values.map { it.info } +
            adapters.map { it.info }, digests)
        val changed = synchronized(unifiedMangaRepositoryStateLock) {
            val old = sourceSnapshot
            if (old.importedDigests == digests) return
            val changed = (old.importedDigests.keys + digests.keys).filter {
                old.importedDigests[it] != digests[it]
            }.toSet()
            // Pair replacement with the content-generation fence before new calls can cache it.
            synchronized(unifiedMangaRepositoryStateLock) {
                sourceSnapshot = next
                changed.forEach { id ->
                    pageCacheGenerations[id] = (pageCacheGenerations[id] ?: 0L) + 1L
                    pageCache.keys.removeAll { it.startsWith("@source/$id/") }
                    val obsolete = pageRequests.filterKeys { it.startsWith("@source/$id/") }
                    obsolete.keys.forEach(pageRequests::remove)
                    obsolete.values.forEach { it.result.cancel() }
                }
            }
            applyEnabledSources(sourceEligibility.ids, forceConfiguration = true)
            changed
        }
        synchronized(unifiedMangaRepositoryStateLock) { sourceSearchCache.keys.removeAll { it.source in changed } }
        synchronized(unifiedMangaRepositoryStateLock) {
            catalogFetchedAt.keys.removeAll { SourceKeys.decodeManga(it).sourceId in changed }
            nativeCatalogIds.removeAll { SourceKeys.decodeManga(it).sourceId in changed }
        }
        synchronized(unifiedMangaRepositoryStateLock) {
            searchTerms.values.forEach { terms -> changed.forEach(terms::remove) }
            searchTerms.entries.removeAll { it.value.isEmpty() }
        }
        _catalogRevision.update { it + 1L }
    }

    fun setEnabledSources(ids: Set<String>) = applyEnabledSources(ids, false)

    private fun applyEnabledSources(ids: Set<String>, forceConfiguration: Boolean) {
        val registered = sources.map { it.id }.toSet()
        val next = ids.intersect(registered)
        synchronized(unifiedMangaRepositoryStateLock) {
            if (!forceConfiguration && sourceEligibility.ids == next) return
            sourceEligibility = SourceEligibility(next, sourceEligibility.generation + 1L)
            val obsolete = discoveries.values.toList()
            discoveries.keys.forEach { workId ->
                discoveryGenerations[workId] = (discoveryGenerations[workId] ?: 0L) + 1L
            }
            discoveries.clear()
            discoveryCacheEpoch += 1L
            discoveryCompleted.clear()
            discoveryIdentities.clear()
            identityCatalogAttempts.clear()
            _sourceDiscovery.value = emptyMap()
            obsolete.forEach { it.job.cancel() }
        }
        synchronized(unifiedMangaRepositoryStateLock) {
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
        val requestedAt = repositoryNanoTime()
        return searchLocks[(key.hashCode() and Int.MAX_VALUE) % searchLocks.size].withLock {
            val generation = synchronized(unifiedMangaRepositoryStateLock) { pageCacheGenerations[sourceId] ?: 0L }
            synchronized(unifiedMangaRepositoryStateLock) {
                sourceSearchCache[key]?.takeIf { it.generation == generation &&
                    repositoryNanoTime() - it.storedAt in 0L until
                        (if (it.page.items.isEmpty()) EMPTY_SEARCH_CACHE_NANOS else DISCOVERY_TTL_NANOS) &&
                    (!forceRefresh || it.storedAt >= requestedAt) }?.page
            }?.let { return@withLock it }
            val result = if (sourceId == "copy") copy.search(key.query, page) else adapter(sourceId).search(key.query, page)
            if (sourceId.startsWith("js_") && synchronized(unifiedMangaRepositoryStateLock) { (pageCacheGenerations[sourceId] ?: 0L) != generation })
                throw CancellationException("源包已更新或移除，请重新搜索。")
            if (synchronized(unifiedMangaRepositoryStateLock) { (pageCacheGenerations[sourceId] ?: 0L) == generation }) synchronized(unifiedMangaRepositoryStateLock) {
                sourceSearchCache.remove(key)
                if (result.items.size <= MAX_SEARCH_CACHE_HITS)
                    sourceSearchCache[key] = CachedSearch(repositoryNanoTime(), generation, result)
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
        val retained = if (page > 0) synchronized(unifiedMangaRepositoryStateLock) { searchTerms[query]?.get(sourceId) } else null
        val terms = retained?.let(::listOf) ?: bodySourceSearchTerms(query)
        var result = MangaPage(emptyList(), false)
        for (term in terms) {
            currentCoroutineContext().ensureActive()
            result = searchSource(sourceId, term, page, forceRefresh = forceRefresh)
            if (result.items.isNotEmpty()) {
                synchronized(unifiedMangaRepositoryStateLock) {
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
    fun discoverSources(work: Manga, aliases: List<String> = emptyList(), force: Boolean = false,
        conflictingIds: Set<String> = emptySet()) =
        startDiscovery(work, aliases, force, conflictingIds, cachedOnly = false)

    /** A READY identity callback only reconsiders retained candidates, never searches every source again. */
    fun reconsiderSourceCandidates(work: Manga, aliases: List<String> = emptyList(),
        conflictingIds: Set<String> = emptySet()) =
        startDiscovery(work, aliases, false, conflictingIds, cachedOnly = true)

    private fun discoverySignature(identity: DiscoveryIdentity): String {
        val work = identity.work
        val baseline = loadedSourceDetail(work.id) ?: if (work.sourceId == "copy") copy.cachedDetail(work.id) else null
        return SourceDiscoveryIdentity.signature(work, identity.aliases, baseline) +
            "\u0000" + identity.conflictingIds.sorted().joinToString("|")
    }

    private fun startDiscovery(work: Manga, aliases: List<String>, force: Boolean,
        conflictingIds: Set<String>, cachedOnly: Boolean) {
        if (work.sourceUrl == dev.mangaflow.storage.DownloadStore.LOCAL_SOURCE_URL || work.title.isBlank()) return
        reconcileSourceDiscovery(work.id)
        val identity = DiscoveryIdentity(work, (work.alternativeTitles + aliases).distinct(), conflictingIds)
        val signature = discoverySignature(identity)
        synchronized(unifiedMangaRepositoryStateLock) {
            val eligibility = sourceEligibility
            if (eligibility.ids.isEmpty()) return
            // Late metadata joins this foreground generation; it must not restart source requests.
            discoveryIdentities[work.id] = identity
            if (force) identityCatalogAttempts.removeAll { it.workId == work.id }
            while (discoveryIdentities.size > 64) discoveryIdentities.remove(discoveryIdentities.keys.first())
            if (cachedOnly && discoveries[work.id] == null && discoveryCompleted[work.id]?.first == signature) return
            if (cachedOnly && _sourceDiscovery.value[work.id].isNullOrEmpty()) return
            discoveries.filterKeys { it != work.id }.toList().forEach { (otherWorkId, run) ->
                discoveries.remove(otherWorkId)
                discoveryGenerations[otherWorkId] = (discoveryGenerations[otherWorkId] ?: 0L) + 1L
                run.job.cancel()
            }
            val active = discoveries[work.id]
            if (active?.job?.isActive == true && !force) return
            val completed = discoveryCompleted[work.id]
            val elapsed = repositoryNanoTime() - (completed?.second ?: 0L)
            if (!cachedOnly && !force && active == null && completed?.first == signature &&
                elapsed in 0L until DISCOVERY_TTL_NANOS) return
            active?.job?.cancel()
            val generation = (discoveryGenerations[work.id] ?: 0L) + 1L
            discoveryGenerations[work.id] = generation
            val cacheEpoch = discoveryCacheEpoch
            var processedSignature = signature
            val job = scope.async(start = CoroutineStart.LAZY) {
                if (!cachedOnly) supervisorScope {
                    sources.filter { it.id != work.sourceId && it.id in eligibility.ids }.map { info ->
                        async {
                            ensureEligibility(eligibility, info.id)
                            publishDiscovery(work.id, generation,
                                SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.SEARCHING))
                            try {
                                slots.withPermit {
                                    withTimeout(25_000L) { discoverOneSource(work, identity.aliases, info, generation, force, eligibility) }
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
                val latest = synchronized(unifiedMangaRepositoryStateLock) { discoveryIdentities[work.id] } ?: identity
                reconsiderCachedCandidates(latest, generation, eligibility)
                processedSignature = discoverySignature(latest)
            }
            discoveries[work.id] = DiscoveryRun(signature, generation, eligibility.generation, job)
            job.invokeOnCompletion { failure ->
                val lateIdentity = synchronized(unifiedMangaRepositoryStateLock) {
                    if (discoveries[work.id]?.job !== job) return@synchronized null
                    discoveries.remove(work.id)
                    if (failure != null || cacheEpoch != discoveryCacheEpoch ||
                        eligibility.generation != sourceEligibility.generation) return@synchronized null
                    discoveryCompleted[work.id] = processedSignature to repositoryNanoTime()
                    while (discoveryCompleted.size > 64) discoveryCompleted.remove(discoveryCompleted.keys.first())
                    // Coalesce a real metadata revision that arrived during the bounded cache pass.
                    discoveryIdentities[work.id]?.takeIf { discoverySignature(it) != processedSignature }
                }
                lateIdentity?.let { reconsiderSourceCandidates(it.work, it.aliases, it.conflictingIds) }
            }
            job.start()
        }
    }

    private suspend fun reconsiderCachedCandidates(identity: DiscoveryIdentity, generation: Long,
        eligibility: SourceEligibility) {
        val work = identity.work
        val pending = _sourceDiscovery.value[work.id].orEmpty().values.filter {
            it.status == SourceDiscoveryStatus.NEEDS_CONFIRMATION && it.sourceId in eligibility.ids
        }
        pending.forEach { result ->
            currentCoroutineContext().ensureActive()
            ensureEligibility(eligibility, result.sourceId)
            if (!isCurrentDiscovery(work.id, generation)) return
            val settings = unified.settings(work.id)
            if (result.sourceId in settings.excludedSources || settings.links.any {
                it.manga.sourceId == result.sourceId && it.confirmedAt > 0L
            }) {
                reconcileSourceDiscovery(work.id)
                return@forEach
            }
            val info = sources.firstOrNull { it.id == result.sourceId } ?: return@forEach
            if (result.candidates.size !in 1..4 || identity.conflictingIds.isNotEmpty()) return@forEach
            val exact = result.candidates.filter { SourceDiscoveryIdentity.titleMatches(work, it, identity.aliases) }
            if (exact.isEmpty()) return@forEach
            if (exact.size == 1 && loadedSourceDetail(exact.single().id) == null &&
                SourceDiscoveryIdentity.authorEvidence(work, exact.single()) != SourceDiscoveryIdentity.AuthorEvidence.CONFLICT) {
                val candidate = exact.single()
                val attempt = synchronized(unifiedMangaRepositoryStateLock) {
                    if (!isCurrentDiscovery(work.id, generation)) return
                    val fresh = unified.settings(work.id)
                    if (info.id in fresh.excludedSources || fresh.links.any {
                        it.manga.sourceId == info.id && it.confirmedAt > 0L
                    }) return@forEach
                    val key = IdentityCatalogAttempt(work.id, candidate.id, eligibility.generation,
                        pageCacheGenerations[info.id] ?: 0L)
                    identityCatalogAttempts.add(key).also {
                        while (identityCatalogAttempts.size > 256)
                            identityCatalogAttempts.remove(identityCatalogAttempts.first())
                    }
                }
                if (attempt) try {
                    // Only the retained, newly identifiable candidate needs its native directory.
                    // Existing catalog stripes/TTL coalesce it; no source search is restarted.
                    slots.withPermit { withTimeout(25_000L) { detailSource(info.id, candidate.id) } }
                } catch (cancel: CancellationException) {
                    if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                } catch (_: Exception) {
                    // Keep the genuine unresolved candidate. Only explicit retry/configuration
                    // change permits another directory attempt; metadata callbacks never loop.
                }
            }
            currentCoroutineContext().ensureActive()
            ensureEligibility(eligibility, result.sourceId)
            val loaded = exact.mapNotNull { loadedSourceDetail(it.id) }
            if (loaded.size != exact.size) return@forEach
            associateCandidates(identity, loaded, info, generation, eligibility)
        }
    }

    /** Origin/settings changes must not reuse a chapter URL from the previous source session. */
    fun invalidatePageCache(sourceId: String) {
        if (sourceId == "copy") copy.invalidateCaches()
        synchronized(unifiedMangaRepositoryStateLock) {
            pageCacheGenerations[sourceId] = (pageCacheGenerations[sourceId] ?: 0L) + 1L
            pageCache.keys.removeAll { it.startsWith("@source/$sourceId/") }
            val obsolete = pageRequests.filterKeys { it.startsWith("@source/$sourceId/") }
            obsolete.keys.forEach(pageRequests::remove)
            obsolete.values.forEach { it.result.cancel() }
        }
        synchronized(unifiedMangaRepositoryStateLock) {
            catalogFetchedAt.keys.removeAll { SourceKeys.decodeManga(it).sourceId == sourceId }
            nativeCatalogIds.removeAll { SourceKeys.decodeManga(it).sourceId == sourceId }
        }
        synchronized(unifiedMangaRepositoryStateLock) { sourceSearchCache.keys.removeAll { it.source == sourceId } }
        synchronized(unifiedMangaRepositoryStateLock) {
            searchTerms.values.forEach { it.remove(sourceId) }
            searchTerms.entries.removeAll { it.value.isEmpty() }
        }
        synchronized(unifiedMangaRepositoryStateLock) {
            // Completion TTL is work-wide; an in-flight scan of the former origin
            // must not refill it after this explicit source configuration change.
            discoveryCacheEpoch += 1L
            discoveryCompleted.clear()
            identityCatalogAttempts.removeAll { SourceKeys.decodeManga(it.mangaId).sourceId == sourceId }
        }
    }

    private fun retainedSourceCount(workId: String, sourceId: String): Int =
        unified.linkedSources(workId).filter { it.manga.sourceId == sourceId }.sumOf {
            cachedSourceDetail(it.manga.id)?.chapters?.size ?: 0
        }

    private suspend fun discoverOneSource(work: Manga, aliases: List<String>,
        info: BodySourceInfo, generation: Long, force: Boolean, eligibility: SourceEligibility) {
        ensureEligibility(eligibility, info.id)
        fun report(status: SourceDiscoveryStatus, message: String = "", candidates: List<Manga> = emptyList(), chapters: Int = 0) =
            publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name, status, chapters, message, candidates))
        val settings = unified.settings(work.id)
        if (info.id in settings.excludedSources) {
            report(SourceDiscoveryStatus.DISABLED, "已由你移除此来源，不会自动重新关联。")
            return
        }
        val linked = settings.links.filter { it.manga.sourceId == info.id && it.confirmedAt > 0L }
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
        val loadedCandidates = mutableListOf<MangaDetail>()
        for (candidate in exact.sortedBy(Manga::id)) {
            ensureEligibility(eligibility, info.id)
            loadedCandidates += detailSource(info.id, candidate.id, forceRefresh = force)
            ensureEligibility(eligibility, info.id)
        }
        val identity = synchronized(unifiedMangaRepositoryStateLock) { discoveryIdentities[work.id] }
            ?: DiscoveryIdentity(work, aliases, emptySet())
        associateCandidates(identity, loadedCandidates, info, generation, eligibility)
    }

    private fun associateCandidates(identity: DiscoveryIdentity, candidates: List<MangaDetail>,
        info: BodySourceInfo, generation: Long, eligibility: SourceEligibility) {
        val work = identity.work
        ensureEligibility(eligibility, info.id)
        val baseline = loadedSourceDetail(work.id) ?: if (work.sourceId == "copy") copy.cachedDetail(work.id) else null
        val chapterMatches = candidates.associate { it.manga.id to SourceDiscoveryIdentity.chapterSystemMatches(baseline, it) }
        // Recheck the latest identity while committing. Manual exclusion/link changes and source
        // generations use these same guards; stale metadata cannot overwrite a user's choice.
        val outcome = synchronized(unifiedMangaRepositoryStateLock) {
            if (!isCurrentDiscovery(work.id, generation)) return
            ensureEligibility(eligibility, info.id)
            val current = discoveryIdentities[work.id] ?: identity
            val verified = candidates.filter {
                current.conflictingIds.isEmpty() && SourceDiscoveryIdentity.workMatches(
                    current.work, it.manga, current.aliases, chapterMatches[it.manga.id] == true)
            }
            if (verified.size != 1) {
                publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                    SourceDiscoveryStatus.NEEDS_CONFIRMATION,
                    message = if (verified.size > 1) "同一作品存在多个可读版本，请确认来源版本。"
                        else "已找到相近条目，作品身份仍需确认。",
                    candidates = candidates.map { it.manga }.distinctBy(Manga::id).take(6)))
                return
            }
            val selected = verified.single()
            val numbered = chapterMatches[selected.manga.id] == true
            val variant = selected.chapters.firstNotNullOfOrNull { it.variants.firstOrNull() }
            val added = unified.attachAutomatically(work.id, selected.manga,
                language = variant?.language ?: info.language, edition = "连载版", sameChapterSystem = numbered,
                evidence = if (numbered) "完整上游书名别名及独立身份/正常话号证据对应。"
                    else "完整上游书名与作者或已确认关系ID对应；章节体系独立保留。")
            Triple(selected, numbered, added)
        }
        val (selected, numbered, added) = outcome
        val fresh = unified.settings(work.id)
        val result = when {
            info.id in fresh.excludedSources -> SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.DISABLED)
            added || fresh.links.any { it.manga.sourceId == info.id && it.confirmedAt > 0L } ->
                SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.LINKED,
                    if (added) selected.chapters.size else retainedSourceCount(work.id, info.id),
                    if (numbered) "作品已关联，已核对章节体系。" else "作品已关联，保留独立章节体系。")
            else -> SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.NEEDS_CONFIRMATION,
                message = "来源关联已更改，请重新确认。", candidates = listOf(selected.manga))
        }
        publishDiscovery(work.id, generation, result)
        if (added) _catalogRevision.update { it + 1L }
    }

    private fun isCurrentDiscovery(workId: String, generation: Long): Boolean = synchronized(unifiedMangaRepositoryStateLock) {
        discoveryGenerations[workId] == generation &&
            discoveries[workId]?.eligibilityGeneration == sourceEligibility.generation &&
            discoveries[workId]?.job?.isActive == true
    }

    /** Saved work associations are authoritative; a cached or late search cannot reopen their confirmation. */
    fun reconcileSourceDiscovery(workId: String) {
        val settings = unified.settings(workId)
        synchronized(unifiedMangaRepositoryStateLock) {
            val current = _sourceDiscovery.value[workId] ?: return
            val updated = current.mapValues { (_, result) -> associatedDiscovery(workId, result, settings) }
            if (updated != current) _sourceDiscovery.update { it + (workId to updated) }
        }
    }

    private fun associatedDiscovery(workId: String, result: SourceDiscoveryResult,
        settings: UnifiedWorkSettings): SourceDiscoveryResult {
        if (result.sourceId in settings.excludedSources) return result.copy(
            status = SourceDiscoveryStatus.DISABLED, message = "已由你移除此来源，不会自动重新关联。",
            candidates = emptyList())
        val linked = settings.links.any { it.manga.sourceId == result.sourceId && it.confirmedAt > 0L }
        if (!linked) return if (result.status == SourceDiscoveryStatus.LINKED) result.copy(
            status = SourceDiscoveryStatus.NO_MATCH, chapters = 0, message = "尚未关联", candidates = emptyList())
            else result
        val retained = retainedSourceCount(workId, result.sourceId)
        return when (result.status) {
            SourceDiscoveryStatus.SEARCHING, SourceDiscoveryStatus.FAILED ->
                result.copy(chapters = retained, candidates = emptyList())
            else -> result.copy(status = SourceDiscoveryStatus.LINKED, chapters = retained,
                message = if (result.status == SourceDiscoveryStatus.LINKED) result.message else "已关联此来源。",
                candidates = emptyList())
        }
    }

    private fun publishDiscovery(workId: String, generation: Long, result: SourceDiscoveryResult) {
        synchronized(unifiedMangaRepositoryStateLock) {
            if (discoveryGenerations[workId] != generation ||
                discoveries[workId]?.eligibilityGeneration != sourceEligibility.generation ||
                result.sourceId !in sourceEligibility.ids) return
            val associated = associatedDiscovery(workId, result, unified.settings(workId))
            _sourceDiscovery.update { current ->
                LinkedHashMap(current).apply {
                    remove(workId)
                    put(workId, current[workId].orEmpty() + (associated.sourceId to associated))
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
        val requestedAt = repositoryNanoTime()
        val gate = synchronized(unifiedMangaRepositoryStateLock) {
            catalogGates.getOrPut(id) { CatalogGate() }.also { it.users++ }
        }
        try {
            return gate.mutex.withLock {
                val generation = synchronized(unifiedMangaRepositoryStateLock) { pageCacheGenerations[sourceId] ?: 0L }
                synchronized(unifiedMangaRepositoryStateLock) {
                    rawCatalogs[id]?.takeIf { catalogFetchedAt[id]?.let { stamp ->
                        stamp.generation == generation && repositoryNanoTime() - stamp.at in 0L until CATALOG_CACHE_NANOS &&
                            (!forceRefresh || stamp.at >= requestedAt)
                    } == true }
                }?.let { return@withLock it }
                val loaded = if (sourceId == "copy") copy.detail(id) else adapter(sourceId).detail(id)
                synchronized(unifiedMangaRepositoryStateLock) {
                    if ((pageCacheGenerations[sourceId] ?: 0L) != generation)
                        throw CancellationException("来源已更新或移除，请重新打开作品。")
                    rememberRaw(loaded, native = true)
                    catalogFetchedAt[id] = CatalogStamp(generation, repositoryNanoTime())
                }
                // Usable source data is published independently of disposable cache persistence.
                scope.launch {
                    catalogStore?.save(loaded) {
                        synchronized(unifiedMangaRepositoryStateLock) {
                            (pageCacheGenerations[sourceId] ?: 0L) == generation && rawCatalogs[id] === loaded
                        }
                    }
                }
                loaded
            }
        } finally {
            synchronized(unifiedMangaRepositoryStateLock) {
                gate.users--
                if (gate.users == 0 && catalogGates[id] === gate) catalogGates.remove(id)
            }
        }
    }

    override suspend fun detail(mangaId: String): MangaDetail = detail(mangaId) {}

    suspend fun detail(mangaId: String, onMetadata: (Manga) -> Unit): MangaDetail {
        // Linked catalogues refresh even when the primary source is unavailable.
        startRefresh(mangaId, force = false)
        val ref = SourceKeys.decodeManga(mangaId)
        val generation = synchronized(unifiedMangaRepositoryStateLock) { pageCacheGenerations[ref.sourceId] ?: 0L }
        val loaded = if (ref.sourceId == "copy") copy.detail(ref.mangaId, onMetadata)
        else detailSource(ref.sourceId, mangaId, forceRefresh = true).also { onMetadata(it.manga) }
        synchronized(unifiedMangaRepositoryStateLock) {
            if ((pageCacheGenerations[ref.sourceId] ?: 0L) != generation)
                throw CancellationException("来源已更新或移除，请重新打开作品。")
            if (ref.sourceId == "copy") {
                // A later shared refresh may already have completed before this caller resumes.
                val latest = copy.cachedDetail(ref.mangaId) ?: loaded
                if (rawCatalogs[mangaId] !== latest) rememberRaw(latest, native = true)
                catalogFetchedAt[mangaId] = CatalogStamp(generation, repositoryNanoTime())
            }
            // Foreign success was stamped by detailSource; do not replace a newer queued result.
        }
        val result = withContext(Dispatchers.Default) { project(loaded) }
        // A slow/refused additional source cannot block the available base catalog.
        startRefresh(mangaId, force = false)
        return result
    }

    fun cachedDetail(mangaId: String): MangaDetail? {
        val retained = synchronized(unifiedMangaRepositoryStateLock) { rawCatalogs[mangaId] }
        val ref = SourceKeys.decodeManga(mangaId)
        val raw = retained ?: if (ref.sourceId == "copy") copy.cachedDetail(ref.mangaId) else null
        return raw?.let(::project)
    }

    /** Stale catalogues remain readable; only proven fresh data may skip revalidation. */
    fun isCatalogFresh(mangaId: String): Boolean {
        val ref = SourceKeys.decodeManga(mangaId)
        // Copy owns its existing ten-minute cache; explicit invalidation clears it too.
        if (ref.sourceId == "copy") return copy.cachedDetail(ref.mangaId)?.chapters?.isNotEmpty() == true
        return synchronized(unifiedMangaRepositoryStateLock) {
            val generation = pageCacheGenerations[ref.sourceId] ?: 0L
            mangaId in nativeCatalogIds && rawCatalogs[mangaId]?.chapters?.isNotEmpty() == true &&
                catalogFetchedAt[mangaId]?.let { stamp ->
                    stamp.generation == generation && repositoryNanoTime() - stamp.at in 0L until CATALOG_CACHE_NANOS
                } == true
        }
    }

    fun cachedSourceDetail(mangaId: String): MangaDetail? = synchronized(unifiedMangaRepositoryStateLock) { rawCatalogs[mangaId] }

    fun loadedSourceDetail(mangaId: String): MangaDetail? = synchronized(unifiedMangaRepositoryStateLock) {
        rawCatalogs[mangaId]?.takeIf { mangaId in nativeCatalogIds }
    }

    /** Retain known work metadata so additional sources remain usable after a primary-source failure. */
    fun retainWork(detail: MangaDetail) {
        rememberRaw(detail)
    }

    /** Explicit retry after linking a source; failures are kept per linked entry. */
    suspend fun refreshLinked(workId: String) {
        startRefresh(workId, force = true)?.await()
    }

    fun rebuild(workId: String): MangaDetail? = cachedDetail(workId)

    override fun cachedPages(mangaId: String, chapterId: String): List<Page>? {
        val ref = runCatching { SourceKeys.decodeChapter(mangaId, chapterId) }.getOrNull() ?: return null
        if (ref.sourceId == "copy") return copy.cachedPages(ref.mangaId, ref.chapterId)
        val key = SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId)
        return synchronized(unifiedMangaRepositoryStateLock) {
            val now = repositoryNanoTime()
            pageCache[key]?.takeIf { now - it.storedAtNanos in 0L until PAGE_CACHE_NANOS }?.pages
        }
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        currentCoroutineContext().ensureActive()
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        if (ref.sourceId == "copy") return copy.pages(ref.mangaId, ref.chapterId, forceRefresh)
        val key = SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId)
        val request = synchronized(unifiedMangaRepositoryStateLock) {
            val generation = pageCacheGenerations[ref.sourceId] ?: 0L
            val now = repositoryNanoTime()
            pageCache.entries.removeAll { now - it.value.storedAtNanos !in 0L until PAGE_CACHE_NANOS }
            if (forceRefresh) pageCache.remove(key)
            else pageCache.remove(key)?.let { cached ->
                pageCache[key] = cached
                return cached.pages
            }
            // Match Copy's reader/download handoff: refresh may join a live fetch, never a completed one.
            val shared = pageRequests[key]?.takeIf { it.generation == generation && !it.result.isCompleted }
                ?: ChapterPageRequest(generation, scope.async(start = CoroutineStart.LAZY) {
                    val loaded = adapter(ref.sourceId).pages(
                        SourceKeys.manga(ref.sourceId, ref.mangaId), key, forceRefresh,
                    ).toList()
                    if (loaded.isEmpty()) throw SourceException("这个来源没有返回本章图片，请重试或选择其他来源。")
                    val requestContext = currentCoroutineContext()
                    requestContext.ensureActive()
                    synchronized(unifiedMangaRepositoryStateLock) {
                        requestContext.ensureActive()
                        if ((pageCacheGenerations[ref.sourceId] ?: 0L) != generation)
                            throw CancellationException("来源已更新或移除，请重新打开章节。")
                        if (pageRequests[key]?.result === requestContext[Job]) {
                            pageCache.remove(key)
                            pageCache[key] = CachedChapterPages(repositoryNanoTime(), loaded)
                            while (pageCache.size > 32) pageCache.remove(pageCache.keys.first())
                        }
                    }
                    loaded
                }).also { pageRequests[key] = it }
            shared.readers++
            shared
        }
        try {
            request.result.start()
            return request.result.await()
        } finally {
            synchronized(unifiedMangaRepositoryStateLock) {
                request.readers--
                if (request.readers == 0) {
                    if (pageRequests[key] === request) pageRequests.remove(key)
                    // A disappearing preview must not cancel a reader/download that adopted this fetch.
                    if (!request.result.isCompleted) request.result.cancel()
                }
            }
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        if (chapterId.isBlank()) {
            val cover = SourceKeys.decodeManga(mangaId)
            if (cover.sourceId == "copy") return copy.imageHeaders(cover.mangaId, "")
            val installed = adapters[cover.sourceId] ?: return emptyMap()
            (installed as? ScriptBodySource)?.let { return it.imageHeaders(mangaId, "") }
            return mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to installed.info.homeUrl.trimEnd('/') + "/",
                "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8",
            )
        }
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        return if (ref.sourceId == "copy") copy.imageHeaders(ref.mangaId, ref.chapterId)
        else adapters[ref.sourceId]?.imageHeaders(SourceKeys.manga(ref.sourceId, ref.mangaId), chapterId).orEmpty()
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
        ?: throw SourceException(if (id.startsWith("js_")) "此来源已移除，请重新导入该源。" else "这个正文来源尚未安装或当前版本不兼容。")

    private fun rememberRaw(detail: MangaDetail, native: Boolean = false) {
        synchronized(unifiedMangaRepositoryStateLock) {
            if (native) nativeCatalogIds.add(detail.manga.id) else nativeCatalogIds.remove(detail.manga.id)
            catalogFetchedAt.remove(detail.manga.id)
            rawCatalogs.remove(detail.manga.id)
            rawCatalogs[detail.manga.id] = detail
            while (rawCatalogs.size > 96) {
                val oldest = rawCatalogs.keys.first()
                rawCatalogs.remove(oldest)
                catalogFetchedAt.remove(oldest)
                nativeCatalogIds.remove(oldest)
            }
        }
    }

    private fun project(base: MangaDetail): MangaDetail {
        val links = unified.linkedSources(base.manga.id)
        val baseline = loadedSourceDetail(base.manga.id)
            ?: if (base.manga.sourceId == "copy") copy.cachedDetail(base.manga.id) else null
        if (baseline != null) links.filter { !it.sameChapterSystem && it.confirmedAt > 0L }.forEach { link ->
            val candidate = loadedSourceDetail(link.manga.id) ?: return@forEach
            if (SourceDiscoveryIdentity.chapterSystemMatches(baseline, candidate))
                unified.confirmChapterSystem(base.manga.id, link)
        }
        val linked = links.mapNotNull { link ->
            synchronized(unifiedMangaRepositoryStateLock) { rawCatalogs[link.manga.id] }
        }
        return base.copy(chapters = unified.build(base.manga, base.chapters, linked))
    }

    private fun startRefresh(workId: String, force: Boolean): Deferred<Unit>? = synchronized(unifiedMangaRepositoryStateLock) {
        val eligibility = sourceEligibility
        refreshes[workId]?.takeIf { it.eligibilityGeneration == eligibility.generation && it.job.isActive }
            ?.let { return@synchronized it.job }
        refreshes[workId]?.job?.cancel()
        if (!force && wallTimeMillis() - (refreshedAt[workId] ?: 0L) < 60_000L) return@synchronized null
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
            synchronized(unifiedMangaRepositoryStateLock) {
                ensureEligibility(eligibility)
                _sourceErrors.update { current -> current + (workId to errors) }
                refreshedAt[workId] = wallTimeMillis()
                _catalogRevision.update { it + 1 }
            }
        }
        refreshes[workId] = RefreshRun(eligibility.generation, job)
        job.invokeOnCompletion { synchronized(unifiedMangaRepositoryStateLock) { if (refreshes[workId]?.job === job) refreshes.remove(workId) } }
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

private val unifiedMangaRepositoryStateLock = SynchronizedObject()

// Nanosecond precision also orders overlapping forced refreshes within the same millisecond.
private val repositoryTimeOrigin = TimeSource.Monotonic.markNow()
private fun repositoryNanoTime(): Long = repositoryTimeOrigin.elapsedNow().inWholeNanoseconds
