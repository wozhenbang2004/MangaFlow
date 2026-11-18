package dev.mangaflow.data

import dev.mangaflow.platform.*
import dev.mangaflow.experience.ContentCacheKind
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

import dev.mangaflow.data.sources.*
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.data.sources.MangaDexBodySource
import dev.mangaflow.data.sources.createJmDownloadSession
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.checked
import dev.mangaflow.data.plugins.sourceImageRevisions
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import dev.mangaflow.data.plugins.scriptImageEvents
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val storageCache: dev.mangaflow.storage.StorageCacheStore? = null,
) : MangaRepository {
    private fun freshNanos(kind: ContentCacheKind, failure: Boolean = false): Long =
        copy.contentCachePolicy().freshMillis(kind, failure).coerceIn(0L, Long.MAX_VALUE / 1_000_000L) * 1_000_000L
    private fun useDiskCache(): Boolean = copy.contentCachePolicy().let { it.enabled && it.storeOnDisk }
    private val diagnostics = SourceDiagnostics()
    val sourceHealth = diagnostics.state
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
    private data class SearchKey(val source: String, val query: String, val page: Int,
        val network: String = DownloadRoutingStore.sourceRevision(source))
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
    @Volatile private var sourceCheckPreferences = SourceCheckPreferences()
    private var admittedSourceChecks = 0
    private val sourceCheckPulse = MutableStateFlow(0L)
    private val mutableSourceCheckActive = MutableStateFlow(0)
    val sourceCheckActive = mutableSourceCheckActive.asStateFlow()
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()
    private val _sourceErrors = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val sourceErrors = _sourceErrors.asStateFlow()
    private val _sourceDiscovery = MutableStateFlow<Map<String, Map<String, SourceDiscoveryResult>>>(emptyMap())
    val sourceDiscovery = _sourceDiscovery.asStateFlow()
    private data class DiscoveryRun(val signature: String, val generation: Long,
        val eligibilityGeneration: Long, val configuration: String, val cacheEpoch: Long,
        val sourceIds: Set<String>, val job: Deferred<Unit>)
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

    init {
        scope.launch {
            scriptImageEvents.collect { event ->
                if (sourceSnapshot.adapters.containsKey(event.sourceId)) diagnostics.imageResult(event.sourceId,
                    when (event.stage) { "decode" -> SourceStage.IMAGE_DECODE; "image" -> SourceStage.IMAGE_READ; else -> SourceStage.IMAGE_RESOLVE },
                    event.success, event.at)
            }
        }
    }

    /** Installed sources remain readable; this eligibility controls search and automatic matching. */
    fun enabledSources(): Set<String> = sourceEligibility.ids.toSet()

    fun sourceConfigurationKey(): String {
        val snapshot = sourceSnapshot
        val value = sourceEligibility.ids.sorted().joinToString("|") { "$it:${snapshot.importedDigests[it].orEmpty()}:${DownloadRoutingStore.sourceRevision(it)}" }
        return sha256(value.encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    fun replaceImportedSources(adapters: List<BodySource>) {
        require(adapters.size <= 64 && adapters.map { it.info.id }.distinct().size == adapters.size) { "导入源数量或身份无效。" }
        require(adapters.all { it is ScriptBodySource && it.info.id.matches(Regex("js_[a-f0-9]{36}")) &&
            it.info.id !in builtinAdapters && it.info.id != "copy" && it.info.id != "local" }) { "导入源不能覆盖内置来源。" }
        // Keep unchanged adapters: their opaque pagination belongs to the still-visible feed.
        val previous = sourceSnapshot.adapters
        val imported = adapters.associate { source ->
            val retained = (previous[source.info.id] as? ScriptBodySource)?.takeIf { it.digest == (source as ScriptBodySource).digest }
            source.info.id to (retained ?: source)
        }
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
                    (it.storedAt >= requestedAt || !forceRefresh &&
                        repositoryNanoTime() - it.storedAt in 0L until
                            freshNanos(ContentCacheKind.SEARCH, it.page.items.isEmpty())) }?.page
            }?.let { return@withLock it }
            val result = diagnostics.track(sourceId, SourceStage.SEARCH) {
                if (sourceId == "copy") copy.search(key.query, page) else adapter(sourceId).search(key.query, page)
            }
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

    /** Explicit private-source browsing does not add the adapter to global search/matching. */
    suspend fun browseSource(sourceId: String, page: Int = 0, request: SourceBrowseRequest? = null): MangaPage {
        require(page in 0..100_000) { "页码无效。" }
        return diagnostics.track(sourceId, SourceStage.CATALOGUE) {
            if (sourceId == "copy") copy.discover(page)
            else if (request == null) adapter(sourceId).discover(page) else adapter(sourceId).browse(request.copy(page = page))
        }
    }

    suspend fun sourceCapabilities(id: String): SourceCapabilities = if (id == "copy") SourceCapabilities() else adapter(id).capabilities()
    suspend fun sourceFeeds(id: String): List<SourceFeed> = if (id == "copy") emptyList() else adapter(id).feeds()
    suspend fun sourceFeedFilters(id: String, feedId: String): List<SourceFilter> = if (id == "copy") emptyList() else adapter(id).feedFilters(feedId)
    suspend fun sourceSearchFilters(id: String): List<SourceFilter> = if (id == "copy") emptyList() else adapter(id).searchFilters()
    suspend fun sourceFilteredSearch(id: String, query: String, page: Int, options: List<String>, forceRefresh: Boolean = false): MangaPage =
        if (options.isEmpty()) searchSource(id, query, page, forceRefresh) else diagnostics.track(id, SourceStage.SEARCH) {
            adapter(id).searchFiltered(query, page, options)
        }
    suspend fun sourceSettings(id: String): List<SourceSetting> = if (id == "copy") emptyList() else adapter(id).settings()
    suspend fun saveSourceSetting(id: String, key: String, value: String) { adapter(id).saveSetting(key, value); invalidatePageCache(id) }
    suspend fun sourceAccountState(id: String): SourceAccountState = adapter(id).accountState()
    suspend fun sourceLogin(id: String, user: String, password: String) = diagnostics.track(id, SourceStage.ACCOUNT) {
        commitSourceAccountChange(id) { adapter(id).login(user, password) }
    }
    /** Account creation does not sign in, change stored credentials or clear anonymous caches. */
    suspend fun sourceRegister(id: String, request: dev.mangaflow.data.sources.SourceRegistrationRequest) =
        diagnostics.track(id, SourceStage.ACCOUNT) { adapter(id).register(request) }
    suspend fun sourceLoginCookies(id: String, values: List<String>) = diagnostics.track(id, SourceStage.ACCOUNT) {
        commitSourceAccountChange(id) { adapter(id).loginWithCookies(values) }
    }
    suspend fun sourceLogout(id: String) = diagnostics.track(id, SourceStage.ACCOUNT) {
        commitSourceAccountChange(id) { adapter(id).logout() }
    }
    private suspend fun commitSourceAccountChange(id: String, action: suspend () -> Unit) {
        try { action() }
        catch (committed: SourceAccountCleanupException) {
            try { invalidatePageCache(id) }
            catch (cancel: CancellationException) {
                cancel.addSuppressed(committed)
                throw cancel
            }
            catch (cleanup: Exception) { if (cleanup !== committed) committed.addSuppressed(cleanup) }
            throw committed
        }
        invalidatePageCache(id)
    }
    suspend fun sourceFavoriteFolders(id: String): List<SourceFavoriteFolder> = diagnostics.track(id, SourceStage.FAVORITES) {
        adapter(id).favoriteFolders()
    }
    suspend fun sourceFavorites(id: String, folder: String, page: Int): MangaPage = diagnostics.track(id, SourceStage.FAVORITES) {
        adapter(id).favorites(folder, page)
    }
    suspend fun sourceSetFavorite(id: String, mangaId: String, folder: String, favorite: Boolean) = diagnostics.track(id, SourceStage.FAVORITES) {
        val ref = SourceKeys.decodeManga(mangaId)
        require(ref.sourceId == id) { "作品和站点收藏来源不一致。" }
        adapter(id).setFavorite(ref.mangaId, folder, favorite)
    }
    suspend fun sourceComments(id: String, mangaId: String, page: Int): SourceCommentPage = diagnostics.track(id, SourceStage.COMMENTS) {
        val ref = SourceKeys.decodeManga(mangaId)
        require(ref.sourceId == id) { "作品和评论来源不一致。" }
        adapter(id).comments(ref.mangaId, page)
    }
    /** Explicit user action; no scheduler, automatic availability probe or account retry. */
    internal fun sourceProbeIdentity(id: String): String = if (id == "copy") "anonymous-catalogue"
        else (adapter(id) as? dev.mangaflow.data.sources.BodySourceProbeIdentity)?.connectionProbeIdentity().orEmpty()

    /** Script preferences and authenticated native clients share mutable sessions; keep their lanes serialized. */
    internal fun sourceProbeParallelRoutes(id: String): Boolean = !id.startsWith("js_") && id !in setOf("picacg", "jmcomic")

    suspend fun sourceProbe(id: String, route: DownloadRouteSnapshot? = null) {
        check(sourceProbeObserved(id, route)) { "来源仅返回了缓存目录，尚未验证真实响应。" }
    }

    internal suspend fun sourceProbeObserved(id: String, route: DownloadRouteSnapshot? = null): Boolean {
        require(sources.any { it.id == id }) { "这个漫画源尚未安装。" }
        val selected = (route ?: DownloadRoutingStore.activeSourceSnapshot(id)).checked()
        val client = NetworkClient(route = selected, observeRelayTraffic = false)
        val observed = kotlinx.coroutines.flow.MutableStateFlow(false)
        try {
            withContext(Dispatchers.IO + ChapterDownloadTransport(selected, client, connectionCheck = true,
                onProbeResponse = { observed.value = true })) {
                diagnostics.track(id, SourceStage.CATALOGUE) {
                    if (id == "copy") copy.discover(0)
                    else {
                        val installed = adapter(id)
                        val isolated = if (installed is ScriptBodySource) installed.forDownload() else installed
                        isolated.discover(0)
                    }
                }
            }
            return observed.value
        } finally { client.close() }
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

    fun configureSourceChecks(preferences: SourceCheckPreferences) {
        require(preferences.concurrency > 0 && preferences.timeoutSeconds > 0 &&
            preferences.intervalMinutes > 0 && preferences.retries >= 0)
        synchronized(unifiedMangaRepositoryStateLock) {
            sourceCheckPreferences = preferences
            sourceCheckPulse.value += 1L
        }
    }

    /** A restored library cannot inherit automatic writes from a pre-restore source batch. */
    fun invalidateSourceChecks() {
        synchronized(unifiedMangaRepositoryStateLock) {
            discoveryCacheEpoch++
            discoveries.values.toList().forEach { it.job.cancel() }
            discoveries.clear()
            discoveryCompleted.clear()
            discoveryIdentities.clear()
            identityCatalogAttempts.clear()
            refreshes.values.toList().forEach { it.job.cancel() }
            refreshes.clear()
            refreshedAt.clear()
            _sourceDiscovery.value = emptyMap()
            _sourceErrors.value = emptyMap()
        }
    }

    /** Resizing changes admission for subsequent work, without replacing a live semaphore. */
    private suspend fun <T> withSourceCheckSlot(block: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            val pulse = sourceCheckPulse.value
            val entered = synchronized(unifiedMangaRepositoryStateLock) {
                if (admittedSourceChecks < sourceCheckPreferences.concurrency) {
                    admittedSourceChecks++
                    mutableSourceCheckActive.value = admittedSourceChecks
                    true
                } else false
            }
            if (entered) break
            sourceCheckPulse.first { it != pulse }
        }
        try { return block() }
        finally {
            synchronized(unifiedMangaRepositoryStateLock) {
                admittedSourceChecks--
                mutableSourceCheckActive.value = admittedSourceChecks
                sourceCheckPulse.value += 1L
            }
        }
    }

    /** Start independent source discovery; the existing work and readable directory stay visible. */
    fun discoverSources(work: Manga, aliases: List<String> = emptyList(), force: Boolean = false,
        conflictingIds: Set<String> = emptySet()): Unit {
        startDiscovery(work, aliases, force, conflictingIds, cachedOnly = false)
    }

    /** The OS/app scheduler awaits this same owner; it never launches an unobserved second scan. */
    suspend fun checkSources(work: Manga, aliases: List<String> = emptyList(), force: Boolean = true,
        failedOnly: Boolean = false, includePrimary: Boolean = true) {
        startDiscovery(work, aliases, force, emptySet(), cachedOnly = false,
            failedOnly = failedOnly, includePrimary = includePrimary, preserveIdentity = true)?.await()
    }

    fun retryFailedSources(work: Manga, aliases: List<String> = emptyList(),
        conflictingIds: Set<String> = emptySet(), sourceId: String? = null,
        failedSourceIds: Set<String> = emptySet()) {
        startDiscovery(work, aliases, true, conflictingIds, cachedOnly = false,
            failedOnly = true, includePrimary = true, onlySourceId = sourceId, failedSourceIds = failedSourceIds)
    }

    /** A READY identity callback only reconsiders retained candidates, never searches every source again. */
    fun reconsiderSourceCandidates(work: Manga, aliases: List<String> = emptyList(),
        conflictingIds: Set<String> = emptySet()) =
        startDiscovery(work, aliases, false, conflictingIds, cachedOnly = true)

    private fun discoverySignature(identity: DiscoveryIdentity): String {
        val work = identity.work
        val baseline = loadedSourceDetail(work.id) ?: if (work.sourceId == "copy") copy.cachedDetail(work.id) else null
        return SourceDiscoveryIdentity.signature(work, identity.aliases, baseline) +
            "\u0000" + identity.conflictingIds.sorted().joinToString("|") + "\u0000" + sourceConfigurationKey()
    }

    private fun startDiscovery(work: Manga, aliases: List<String>, force: Boolean,
        conflictingIds: Set<String>, cachedOnly: Boolean, failedOnly: Boolean = false,
        includePrimary: Boolean = false, onlySourceId: String? = null,
        preserveIdentity: Boolean = false, failedSourceIds: Set<String> = emptySet(),
        onlySourceIds: Set<String>? = null): Deferred<Unit>? {
        if (work.sourceUrl == dev.mangaflow.storage.DownloadStore.LOCAL_SOURCE_URL || work.title.isBlank()) return null
        reconcileSourceDiscovery(work.id)
        var identity = DiscoveryIdentity(work, (work.alternativeTitles + aliases).distinct(), conflictingIds)
        var signature = discoverySignature(identity)
        synchronized(unifiedMangaRepositoryStateLock) {
            val eligibility = sourceEligibility
            if (eligibility.ids.isEmpty()) return null
            if (preserveIdentity) discoveryIdentities[work.id]?.let { known ->
                identity = known
                signature = discoverySignature(known)
            }
            // Late metadata joins this foreground generation; it must not restart source requests.
            discoveryIdentities[work.id] = identity
            if (force) identityCatalogAttempts.removeAll { it.workId == work.id }
            while (discoveryIdentities.size > 64) discoveryIdentities.remove(discoveryIdentities.keys.first())
            if (cachedOnly && discoveries[work.id] == null && discoveryCompleted[work.id]?.first == signature) return null
            if (cachedOnly && _sourceDiscovery.value[work.id].isNullOrEmpty()) return null
            val active = discoveries[work.id]
            val live = active?.takeIf { it.job.isActive && it.configuration == sourceConfigurationKey() &&
                it.cacheEpoch == discoveryCacheEpoch }
            if (cachedOnly && live != null) return live.job
            val completed = discoveryCompleted[work.id]
            val previous = _sourceDiscovery.value[work.id].orEmpty()
            val now = wallTimeMillis()
            val targets = sources.filter { info ->
                info.id in eligibility.ids && (!info.adult || info.id == work.sourceId) &&
                    (previous[info.id]?.nextRetryAt ?: 0L) <= now &&
                    (includePrimary || info.id != work.sourceId) &&
                    (onlySourceId == null || info.id == onlySourceId) &&
                    (onlySourceIds == null || info.id in onlySourceIds) &&
                    (!failedOnly || onlySourceId != null || info.id in failedSourceIds ||
                        previous[info.id]?.status == SourceDiscoveryStatus.FAILED) &&
                    (force || completed?.first != signature || previous[info.id]?.let { row ->
                        row.checkedAt <= 0L || now - row.checkedAt !in 0L until
                            copy.contentCachePolicy().freshMillis(ContentCacheKind.DISCOVERY,
                                row.status == SourceDiscoveryStatus.FAILED)
                    } != false)
            }
            if (live != null) {
                val missing = targets.mapTo(mutableSetOf()) { it.id } - live.sourceIds
                if (missing.isEmpty()) return live.job
                // Join the current owner first, then acquire only genuinely uncovered targets.
                // In particular, a catalogue-only foreground scan cannot certify the primary.
                return scope.async {
                    live.job.await()
                    val followup = synchronized(unifiedMangaRepositoryStateLock) {
                        if (live.cacheEpoch != discoveryCacheEpoch || live.configuration != sourceConfigurationKey() ||
                            live.eligibilityGeneration != sourceEligibility.generation)
                            throw CancellationException("来源检查上下文已更新。")
                        startDiscovery(work, aliases, true, conflictingIds, cachedOnly = false,
                            failedOnly = failedOnly, includePrimary = includePrimary, onlySourceId = onlySourceId,
                            preserveIdentity = true, failedSourceIds = failedSourceIds, onlySourceIds = missing)
                    }
                    followup?.await()
                    Unit
                }
            }
            if (!cachedOnly && targets.isEmpty()) return null
            active?.job?.cancel()
            val generation = (discoveryGenerations[work.id] ?: 0L) + 1L
            discoveryGenerations[work.id] = generation
            val cacheEpoch = discoveryCacheEpoch
            val configuration = sourceConfigurationKey()
            var processedSignature = signature
            val job = scope.async(start = CoroutineStart.LAZY) {
                if (!cachedOnly) supervisorScope {
                    targets.map { info ->
                        async {
                            ensureEligibility(eligibility, info.id)
                            publishDiscovery(work.id, generation,
                                SourceDiscoveryResult(info.id, info.name, SourceDiscoveryStatus.QUEUED))
                            checkOneSource(work, identity.aliases, info, generation, force, eligibility)
                        }
                    }.forEach { it.await() }
                }
                val latest = synchronized(unifiedMangaRepositoryStateLock) { discoveryIdentities[work.id] } ?: identity
                reconsiderCachedCandidates(latest, generation, eligibility)
                processedSignature = discoverySignature(latest)
            }
            discoveries[work.id] = DiscoveryRun(signature, generation, eligibility.generation,
                configuration, cacheEpoch, targets.mapTo(mutableSetOf()) { it.id }, job)
            job.invokeOnCompletion { failure ->
                val lateIdentity = synchronized(unifiedMangaRepositoryStateLock) {
                    if (discoveries[work.id]?.job !== job) return@synchronized null
                    discoveries.remove(work.id)
                    if (failure != null || cacheEpoch != discoveryCacheEpoch || configuration != sourceConfigurationKey() ||
                        eligibility.generation != sourceEligibility.generation) {
                        _sourceDiscovery.update { current ->
                            val rows = current[work.id] ?: return@update current
                            current + (work.id to rows.mapValues { (_, row) ->
                                if (row.status in setOf(SourceDiscoveryStatus.QUEUED, SourceDiscoveryStatus.SEARCHING,
                                        SourceDiscoveryStatus.RETRYING)) row.copy(status = SourceDiscoveryStatus.FAILED,
                                    message = "检查已中断，已保留上次目录；可单独重试。") else row
                            })
                        }
                        return@synchronized null
                    }
                    discoveryCompleted[work.id] = processedSignature to repositoryNanoTime()
                    while (discoveryCompleted.size > 64) discoveryCompleted.remove(discoveryCompleted.keys.first())
                    // Coalesce a real metadata revision that arrived during the bounded cache pass.
                    discoveryIdentities[work.id]?.takeIf { discoverySignature(it) != processedSignature }
                }
                lateIdentity?.let { reconsiderSourceCandidates(it.work, it.aliases, it.conflictingIds) }
            }
            job.start()
            return job
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
                    withSourceCheckSlot { withTimeout(sourceCheckPreferences.timeoutSeconds.toLong() * 1_000L) {
                        detailSource(info.id, candidate.id)
                    } }
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
        if (SourceKeys.decodeManga(workId).sourceId == sourceId)
            (cachedSourceDetail(workId) ?: if (sourceId == "copy") copy.cachedDetail(workId) else null)?.chapters?.size ?: 0
        else unified.linkedSources(workId).filter { it.manga.sourceId == sourceId }.sumOf {
            cachedSourceDetail(it.manga.id)?.chapters?.size ?: 0
        }

    private suspend fun checkOneSource(work: Manga, aliases: List<String>, info: BodySourceInfo,
        generation: Long, force: Boolean, eligibility: SourceEligibility) {
        val preferences = sourceCheckPreferences
        val started = repositoryNanoTime()
        var attempt = 1L
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureEligibility(eligibility, info.id)
            if (!isCurrentDiscovery(work.id, generation)) throw CancellationException("来源配置已改变。")
            try {
                withSourceCheckSlot {
                    if (!isCurrentDiscovery(work.id, generation)) throw CancellationException("来源配置已改变。")
                    publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                        SourceDiscoveryStatus.SEARCHING, attempt = attempt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    withTimeout(preferences.timeoutSeconds.toLong() * 1_000L) {
                        discoverOneSource(work, aliases, info, generation, force || attempt > 1L, eligibility)
                    }
                }
                _sourceDiscovery.value[work.id]?.get(info.id)?.let { result ->
                    publishDiscovery(work.id, generation, result.copy(
                        attempt = attempt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        elapsedMillis = (repositoryNanoTime() - started) / 1_000_000L))
                }
                return
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                currentCoroutineContext().ensureActive()
                val response = failure as? SourceException
                val retryable = failure !is DownloadRouteException &&
                    response?.httpStatus !in setOf(209, 210, 400, 401, 403, 404, 410, 422, 429) &&
                    (response?.retryAfterSeconds ?: 0L) == 0L &&
                    listOf("验证", "登录", "登陆", "付费", "不兼容", "尚未安装", "已移除").none {
                        failure.message?.contains(it) == true
                    }
                val message = if (failure is kotlinx.coroutines.TimeoutCancellationException)
                    "该来源响应超时，已保留上次可用章节。"
                    else failure.message ?: "该来源暂时不可用，已保留上次可用章节。"
                val elapsed = (repositoryNanoTime() - started) / 1_000_000L
                if (!retryable || attempt > preferences.retries.toLong()) {
                    val cooldown = (response?.retryAfterSeconds ?: 0L).coerceIn(0L, (Long.MAX_VALUE - wallTimeMillis()) / 1_000L)
                    publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                        SourceDiscoveryStatus.FAILED, retainedSourceCount(work.id, info.id), message,
                        attempt = attempt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), elapsedMillis = elapsed,
                        nextRetryAt = if (cooldown > 0L) wallTimeMillis() + cooldown * 1_000L else 0L))
                    return
                }
                // Backoff owns no admission slot, so another source can make progress immediately.
                publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name,
                    SourceDiscoveryStatus.RETRYING, retainedSourceCount(work.id, info.id), message,
                    attempt = attempt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), elapsedMillis = elapsed))
                delay(1_000L)
                attempt++
            }
        }
    }

    private suspend fun discoverOneSource(work: Manga, aliases: List<String>,
        info: BodySourceInfo, generation: Long, force: Boolean, eligibility: SourceEligibility) {
        ensureEligibility(eligibility, info.id)
        fun report(status: SourceDiscoveryStatus, message: String = "", candidates: List<Manga> = emptyList(), chapters: Int = 0) =
            publishDiscovery(work.id, generation, SourceDiscoveryResult(info.id, info.name, status, chapters, message, candidates))
        val settings = unified.settings(work.id)
        if (info.id == work.sourceId) {
            val loaded = detailSource(info.id, work.id, forceRefresh = force)
            ensureEligibility(eligibility, info.id)
            report(SourceDiscoveryStatus.LINKED, chapters = loaded.chapters.size)
            _catalogRevision.update { it + 1L }
            return
        }
        if (info.id in settings.excludedSources) {
            report(SourceDiscoveryStatus.DISABLED, "已由你移除此来源，不会自动重新关联。")
            return
        }
        val linked = settings.links.filter { it.manga.sourceId == info.id && it.confirmedAt > 0L }
        if (linked.isNotEmpty()) {
            var count = 0
            linked.forEach { link ->
                ensureEligibility(eligibility, info.id)
                if (useDiskCache() && cachedSourceDetail(link.manga.id) == null) catalogStore?.read(link.manga.id)?.let {
                    ensureEligibility(eligibility, info.id)
                    rememberRaw(it)
                    _catalogRevision.update { revision -> revision + 1L }
                }
                val loaded = detailSource(info.id, link.manga.id, forceRefresh = force)
                ensureEligibility(eligibility, info.id)
                // Only upgrade automatically created links; a user's independent numbering stays theirs.
                if (isCurrentDiscovery(work.id, generation) && link.association == "automatic" && !link.sameChapterSystem &&
                    SourceDiscoveryIdentity.chapterSystemMatches(loadedSourceDetail(work.id), loaded))
                    unified.confirmChapterSystem(work.id, link)
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
            val found = searchSource(info.id, query, forceRefresh = force).items
            results = (results + found).distinctBy(Manga::id)
            ensureEligibility(eligibility, info.id)
            // A broad upstream search may return unrelated hits for the first spelling.
            // Continue the existing bounded alias list until a full-title match is present.
            if (results.any { SourceDiscoveryIdentity.titleMatches(work, it, aliases) }) break
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
            discoveries[workId]?.configuration == sourceConfigurationKey() &&
            discoveries[workId]?.cacheEpoch == discoveryCacheEpoch &&
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
        if (result.sourceId == SourceKeys.decodeManga(workId).sourceId) return result
        if (result.sourceId in settings.excludedSources) return result.copy(
            status = SourceDiscoveryStatus.DISABLED, message = "已由你移除此来源，不会自动重新关联。",
            candidates = emptyList())
        val linked = settings.links.any { it.manga.sourceId == result.sourceId && it.confirmedAt > 0L }
        if (!linked) return if (result.status == SourceDiscoveryStatus.LINKED) result.copy(
            status = SourceDiscoveryStatus.NO_MATCH, chapters = 0, message = "尚未关联", candidates = emptyList())
            else result
        val retained = retainedSourceCount(workId, result.sourceId)
        return when (result.status) {
            SourceDiscoveryStatus.QUEUED, SourceDiscoveryStatus.SEARCHING, SourceDiscoveryStatus.RETRYING, SourceDiscoveryStatus.FAILED ->
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
                discoveries[workId]?.configuration != sourceConfigurationKey() ||
                discoveries[workId]?.cacheEpoch != discoveryCacheEpoch ||
                result.sourceId !in sourceEligibility.ids) return
            val old = _sourceDiscovery.value[workId]?.get(result.sourceId)
            val pending = result.status in setOf(SourceDiscoveryStatus.QUEUED, SourceDiscoveryStatus.SEARCHING,
                SourceDiscoveryStatus.RETRYING)
            val success = result.status in setOf(SourceDiscoveryStatus.LINKED, SourceDiscoveryStatus.NO_MATCH,
                SourceDiscoveryStatus.NEEDS_CONFIRMATION)
            val stamped = result.copy(checkedAt = if (pending) old?.checkedAt ?: 0L else wallTimeMillis(),
                lastSuccessAt = if (success) wallTimeMillis() else old?.lastSuccessAt ?: 0L,
                attempt = result.attempt.takeIf { it > 0 } ?: old?.attempt ?: 0,
                chapters = if (pending) maxOf(result.chapters, old?.chapters ?: 0) else result.chapters)
            val associated = if (result.sourceId == SourceKeys.decodeManga(workId).sourceId) stamped
                else associatedDiscovery(workId, stamped, unified.settings(workId))
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
                        stamp.generation == generation && (stamp.at >= requestedAt || !forceRefresh &&
                            repositoryNanoTime() - stamp.at in 0L until freshNanos(ContentCacheKind.DETAILS))
                    } == true }
                }?.let { return@withLock it }
                val loaded = diagnostics.track(sourceId, SourceStage.DETAIL) {
                    if (sourceId == "copy") copy.detail(id) else adapter(sourceId).detail(id)
                }
                synchronized(unifiedMangaRepositoryStateLock) {
                    if ((pageCacheGenerations[sourceId] ?: 0L) != generation)
                        throw CancellationException("来源已更新或移除，请重新打开作品。")
                    rememberRaw(loaded, native = true)
                    catalogFetchedAt[id] = CatalogStamp(generation, repositoryNanoTime())
                    _catalogRevision.update { it + 1L }
                }
                // Usable source data is published independently of disposable cache persistence.
                scope.launch {
                    if (useDiskCache()) catalogStore?.save(loaded) {
                        useDiskCache() && synchronized(unifiedMangaRepositoryStateLock) {
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

    /**
     * A bounded browse intent may prepare only this exact owning catalogue.
     * The caller owns optional admission/cancellation; linked-source discovery,
     * projections and source choices still belong to an actual detail opening.
     */
    suspend fun prewarmDetail(mangaId: String): Boolean {
        val ref = SourceKeys.decodeManga(mangaId)
        return detailSource(ref.sourceId, mangaId).chapters.isNotEmpty()
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
                _catalogRevision.update { it + 1L }
            }
            // Foreign success was stamped by detailSource; do not replace a newer queued result.
        }
        val result = withContext(Dispatchers.Default) { project(loaded) }
        // A slow/refused additional source cannot block the available base catalog.
        startRefresh(mangaId, force = false)
        return result
    }

    internal data class CatalogSnapshot(val detail: MangaDetail, val fresh: Boolean)

    fun cachedDetail(mangaId: String): MangaDetail? = cachedCatalog(mangaId)?.detail

    /** Freshness belongs to the raw directory that was projected, not a later cache entry. */
    internal fun cachedCatalog(mangaId: String): CatalogSnapshot? {
        val policy = copy.contentCachePolicy()
        if (!policy.enabled) return null
        val ref = SourceKeys.decodeManga(mangaId)
        // Copy publishes its complete native catalog before its awaiting callers resume.
        val nativeCopy = if (ref.sourceId == "copy") copy.cachedDetail(ref.mangaId) else null
        val (raw, fresh) = if (nativeCopy != null) nativeCopy to true else synchronized(unifiedMangaRepositoryStateLock) {
            val retained = rawCatalogs[mangaId] ?: return@synchronized null
            val stamp = catalogFetchedAt[mangaId]
            val age = stamp?.let { repositoryNanoTime() - it.at }
            if (storageCache?.protectedCatalog(mangaId) != true &&
                (age == null || age !in 0L..policy.retentionMillis * 1_000_000L)) return@synchronized null
            val fresh = ref.sourceId != "copy" && mangaId in nativeCatalogIds && retained.chapters.isNotEmpty() &&
                stamp?.generation == (pageCacheGenerations[ref.sourceId] ?: 0L) &&
                age != null && age in 0L until
                    policy.freshMillis(ContentCacheKind.DETAILS).coerceIn(0L, Long.MAX_VALUE / 1_000_000L) * 1_000_000L
            retained to fresh
        } ?: return null
        return CatalogSnapshot(project(raw), fresh)
    }

    /** Stale catalogues remain readable; only proven fresh data may skip revalidation. */
    fun isCatalogFresh(mangaId: String): Boolean {
        val ref = SourceKeys.decodeManga(mangaId)
        // Copy owns its source catalogue; explicit invalidation clears it too.
        if (ref.sourceId == "copy") return copy.cachedDetail(ref.mangaId)?.chapters?.isNotEmpty() == true
        return synchronized(unifiedMangaRepositoryStateLock) {
            val generation = pageCacheGenerations[ref.sourceId] ?: 0L
            mangaId in nativeCatalogIds && rawCatalogs[mangaId]?.chapters?.isNotEmpty() == true &&
                catalogFetchedAt[mangaId]?.let { stamp ->
                    stamp.generation == generation && repositoryNanoTime() - stamp.at in 0L until
                        freshNanos(ContentCacheKind.DETAILS)
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
        val imageRevision = sourceImageRevisions.value[ref.sourceId] ?: 0L
        val key = SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId) + "@network/" + DownloadRoutingStore.sourceRevision(ref.sourceId) + "@image/" + imageRevision
        return synchronized(unifiedMangaRepositoryStateLock) {
            val now = repositoryNanoTime()
            pageCache[key]?.takeIf { (sourceImageRevisions.value[ref.sourceId] ?: 0L) == imageRevision && now - it.storedAtNanos in 0L until freshNanos(ContentCacheKind.PAGE_MANIFEST) }?.pages
        }
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        val loaded = loadPages(mangaId, chapterId, forceRefresh)
        storageCache?.registerPages(mangaId, chapterId, SourceKeys.decodeChapter(mangaId, chapterId).sourceId,
            loaded, imageHeaders(mangaId, chapterId))
        return loaded
    }

    private suspend fun loadPages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> {
        currentCoroutineContext().ensureActive()
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        if (ref.sourceId == "copy") return copy.pages(ref.mangaId, ref.chapterId, forceRefresh)
        val imageRevision = sourceImageRevisions.value[ref.sourceId] ?: 0L
        val key = SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId) + "@network/" + DownloadRoutingStore.sourceRevision(ref.sourceId) + "@image/" + imageRevision
        val request = synchronized(unifiedMangaRepositoryStateLock) {
            if ((sourceImageRevisions.value[ref.sourceId] ?: 0L) != imageRevision)
                throw CancellationException("来源图片会话已变化，请刷新本章。")
            val generation = pageCacheGenerations[ref.sourceId] ?: 0L
            val now = repositoryNanoTime()
            val ttl = freshNanos(ContentCacheKind.PAGE_MANIFEST)
            pageCache.entries.removeAll { now - it.value.storedAtNanos !in 0L until ttl }
            if (forceRefresh) pageCache.remove(key)
            else pageCache.remove(key)?.let { cached ->
                pageCache[key] = cached
                if ((sourceImageRevisions.value[ref.sourceId] ?: 0L) != imageRevision)
                    throw CancellationException("来源图片会话已变化，请刷新本章。")
                return cached.pages
            }
            // Match Copy's reader/download handoff: refresh may join a live fetch, never a completed one.
            val shared = pageRequests[key]?.takeIf { it.generation == generation && !it.result.isCompleted }
                ?: ChapterPageRequest(generation, scope.async(start = CoroutineStart.LAZY) {
                    val loaded = diagnostics.track(ref.sourceId, SourceStage.PAGES) { adapter(ref.sourceId).pages(
                        SourceKeys.manga(ref.sourceId, ref.mangaId), SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId), forceRefresh,
                    ).toList() }
                    if (loaded.isEmpty()) throw SourceException("这个来源没有返回本章图片，请重试或选择其他来源。")
                    val requestContext = currentCoroutineContext()
                    requestContext.ensureActive()
                    synchronized(unifiedMangaRepositoryStateLock) {
                        requestContext.ensureActive()
                        if ((pageCacheGenerations[ref.sourceId] ?: 0L) != generation ||
                            (sourceImageRevisions.value[ref.sourceId] ?: 0L) != imageRevision)
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
            val loaded = request.result.await()
            if ((sourceImageRevisions.value[ref.sourceId] ?: 0L) != imageRevision)
                throw CancellationException("来源图片会话已变化，请刷新本章。")
            return loaded
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

    /** One task owns metadata, redirects and page bytes on the same frozen route. */
    override suspend fun prepareDownload(
        context: PlatformContext,
        mangaId: String,
        chapterId: String,
        route: DownloadRouteSnapshot,
        forceRefresh: Boolean,
    ): PreparedChapterDownload {
        val selected = route.checked()
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        val snapshot = sourceSnapshot
        val installed = if (ref.sourceId == "copy") null else (snapshot.adapters[ref.sourceId]
            ?: throw SourceException("这个正文来源尚未安装或已移除。"))
        // Script header caches belong to this request, even while the reader uses the same script.
        val source = if (installed is ScriptBodySource) installed.forDownload() else installed
        val client = NetworkClient(route = selected)
        try {
            return withContext(Dispatchers.IO + ChapterDownloadTransport(selected, client)) {
                currentCoroutineContext().ensureActive()
                val owner = SourceKeys.manga(ref.sourceId, ref.mangaId)
                val pages = when (ref.sourceId) {
                    "copy" -> copy.downloadPages(ref.mangaId, ref.chapterId)
                    "jmcomic" -> {
                        val session = createJmDownloadSession(context, mangaId, chapterId, selected)
                            ?: throw DownloadRouteException("当前平台不支持这个来源的下载。")
                        try { session.pages() } finally { session.close() }
                    }
                    else -> requireNotNull(source).pages(owner, chapterId, forceRefresh)
                }.toList()
                currentCoroutineContext().ensureActive()
                if (ref.sourceId != "copy" && sourceSnapshot.adapters[ref.sourceId] !== installed)
                    throw CancellationException("来源已更新或移除，请重新开始下载。")
                if (pages.isEmpty()) throw SourceException("这个来源没有返回本章图片，请重试或选择其他来源。")
                val headers = if (ref.sourceId == "copy") copy.imageHeaders(ref.mangaId, ref.chapterId)
                    else requireNotNull(source).imageHeaders(owner, chapterId)
                PreparedChapterDownload(pages, headers.toMap())
            }
        } finally {
            client.close()
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
        if (baseline != null) links.filter { it.association == "automatic" && !it.sameChapterSystem && it.confirmedAt > 0L }.forEach { link ->
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
        discoveries[workId]?.takeIf { it.eligibilityGeneration == eligibility.generation &&
            it.configuration == sourceConfigurationKey() && it.cacheEpoch == discoveryCacheEpoch && it.job.isActive }
            ?.let { return@synchronized it.job }
        refreshes[workId]?.takeIf { it.eligibilityGeneration == eligibility.generation && it.job.isActive }
            ?.let { return@synchronized it.job }
        refreshes[workId]?.job?.cancel()
        if (!force && wallTimeMillis() - (refreshedAt[workId] ?: 0L) in 0L until
                copy.contentCachePolicy().freshMillis(ContentCacheKind.DETAILS)) return@synchronized null
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
                            withSourceCheckSlot {
                                ensureEligibility(eligibility, link.manga.sourceId)
                                if (useDiskCache() && cachedSourceDetail(link.manga.id) == null) {
                                    catalogStore?.read(link.manga.id)?.let {
                                        ensureEligibility(eligibility, link.manga.sourceId)
                                        rememberRaw(it)
                                        _catalogRevision.update { value -> value + 1 }
                                    }
                                }
                                withTimeout(sourceCheckPreferences.timeoutSeconds.toLong() * 1_000L) {
                                    detailSource(link.manga.sourceId, link.manga.id, forceRefresh = force)
                                }
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
        const val MAX_SEARCH_CACHE_PAGES = 64
        const val MAX_SEARCH_CACHE_HITS = 1_200
    }
}

private val unifiedMangaRepositoryStateLock = SynchronizedObject()

// Nanosecond precision also orders overlapping forced refreshes within the same millisecond.
private val repositoryTimeOrigin = TimeSource.Monotonic.markNow()
private fun repositoryNanoTime(): Long = repositoryTimeOrigin.elapsedNow().inWholeNanoseconds
