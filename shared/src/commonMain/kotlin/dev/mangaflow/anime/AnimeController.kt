package dev.mangaflow.anime

import dev.mangaflow.anime.player.AnimePlayer
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerRequest
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimePlayerOptions
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.anime.player.createAnimePlayer
import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.AnimeTrackingRepository
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.SourceNetworkSettings
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned data and playback. Screens keep only disclosure, drafts and scroll state. */
class AnimeController(private val context: PlatformContext, private val scope: CoroutineScope) {
    private val metadata = AnimeMetadataRepository(context)
    private val sources = AnimeSourceRepository(context)
    private val tracking = AnimeTrackingRepository(context)
    private val library = AnimeLibraryStore.get(context)
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeScreenState())
    val state = mutableState.asStateFlow()
    val extensions get() = sources.extensions
    val danmaku by lazy { dev.mangaflow.anime.danmaku.AnimeDanmakuController(context, scope, sources.extensions) }
    private val downloadsOwner = lazy { dev.mangaflow.anime.downloads.AnimeDownloadStore(context, scope).also {
        it.setForeground(foreground)
    } }
    val downloads get() = downloadsOwner.value
    val sourceHomes: Map<String, String> get() = sources.providers.associate { it.id to it.homeUrl } +
        dev.mangaflow.anime.sources.lanerc.LANERC_SOURCE_DIRECTORY.filter { descriptor ->
            sources.extensions.state.value.any { it.id == descriptor.id && it.installed && it.enabled }
        }.associate { it.id to it.homeUrl } + mapOf(
        "anime-bangumi" to "https://api.bgm.tv/", "anime-anilist" to "https://graphql.anilist.co/",
        "anime-danmaku-dandanplay" to "https://api.dandanplay.net/")
    private var playerInstance: AnimePlayer? = null
    val player: AnimePlayer
        get() = playerInstance ?: createAnimePlayer(context, scope).also {
            playerInstance = it
            it.configure(playerOptions(mutableState.value.settings))
            it.setForeground(foreground)
            observePlayer(it)
        }

    private var browseGeneration = 0L
    private var detailGeneration = 0L
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private var resolveGeneration = 0L
    private var catalogGeneration = 0L
    private val catalogRequests = mutableMapOf<String, Long>()
    private var browseJob: Job? = null
    private var detailJob: Job? = null
    private var sourceJob: Job? = null
    private var previewJob: Job? = null
    private var previewSourceId: String? = null
    private var previewSourceRevision: String? = null
    private var resolveJob: Job? = null
    private var boundCatalogJob: Job? = null
    private var relatedJob: Job? = null
    private var prepareJob: Job? = null
    private var sleepJob: Job? = null
    private val sourceSearchJobs = mutableMapOf<String, Job>()
    private val sourceSearchRequests = mutableMapOf<String, Long>()
    private var sourceSearchSequence = 0L
    private var sourceQuery = ""
    private var foreground = true
    private var resolvingSourceId: String? = null
    private var prepared: PendingPlayback? = null
    private var rollback: PlaybackReceipt? = null
    private var resumeRequest: Pair<String, String>? = null
    private var restoreEpoch = 0L
    private val recoverySources = mutableSetOf<String>()
    private var sourceRecoveryAttempts = 0
    private val playbackEpochs = mutableMapOf<String, Long>()
    private var active: ActivePlayback? = null
    private var pending: PendingPlayback? = null
    private val reviewSnapshots = mutableMapOf<String, ReviewContext>()
    private var lastCheckpointAt = 0L
    private var lastSavedPosition = -1L
    private var lastSavedPhase: AnimePlayerPhase? = null

    private data class ActivePlayback(
        val detail: AnimeDetail,
        val episode: AnimeEpisode,
        val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset,
        val epoch: Long,
        val playerGeneration: Long,
        val libraryEpoch: Long,
        val sourceRevision: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        var endedSaved: Boolean = false,
        var endActionHandled: Boolean = false,
        var openingSkipped: Boolean = false,
        var endingSkipped: Boolean = false,
        var preparationAttempted: Boolean = false,
        var recoveryHandled: Boolean = false,
    )
    private data class PendingPlayback(
        val detail: AnimeDetail, val episode: AnimeEpisode, val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset, val positionMs: Long, val generation: Long,
        val sourceRevision: String? = null, val playWhenReady: Boolean = true,
        val resolutionId: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        val useSourceStart: Boolean = true,
    )
    private data class PlaybackReceipt(val playback: ActivePlayback, val positionMs: Long, val playWhenReady: Boolean)
    private data class ReviewContext(val detail: AnimeDetail, val remote: AnimeTrackingSnapshot)

    init {
        scope.launch {
            try {
                try { library.awaitReady() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取，原文件已保留；可通过备份恢复。") }
                // Keep observing so an explicit recovery can replace a previously unreadable document.
                library.state.collect { stored ->
                    if (stored.restoreEpoch != restoreEpoch) {
                        restoreEpoch = stored.restoreEpoch
                        stopPlayback(discardCheckpoint = true)
                        reviewSnapshots.clear()
                        mutableState.update { it.copy(reviews = emptyList()) }
                    }
                    mutableState.update { it.copy(
                        library = stored.entries.values.sortedWith(compareByDescending<AnimeLibraryEntry> { entry -> entry.pinned }
                            .thenByDescending { entry -> entry.updatedAt }),
                        settings = stored.settings,
                        searchHistory = stored.searchHistory,
                        resumeEntry = stored.entries.values.filter { entry -> entry.resume?.let { progress ->
                            !progress.completed && progress.positionMs > 0 &&
                                progress.updatedAt > (stored.dismissedResume[entry.subject.id] ?: -1L)
                        } == true }.maxByOrNull { entry -> entry.resume?.updatedAt ?: 0L },
                        canUndo = stored.undo?.let { undo ->
                            stored.entries[undo.subjectId]?.revision == undo.expectedRevision
                        } == true,
                    ) }
                    playerInstance?.configure(playerOptions(stored.settings))
                    rebuildOptions()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取。") }
        }
        scope.launch {
            sources.extensions.ready.await()
            var previous = sources.extensions.state.value.associateBy { it.id }
            sources.extensions.state.collect { listings ->
                val changed = listings.filter { old -> previous[old.id]?.let { before ->
                    before.revision != old.revision || before.enabled != old.enabled || before.installed != old.installed
                } == true }.map { it.id }.toSet()
                previous = listings.associateBy { it.id }
                mutableState.update { it.copy(sourceExtensions = listings,
                    sourceCatalogs = it.sourceCatalogs.filterNot { catalog -> catalog.title.sourceId in changed },
                    sourceResults = it.sourceResults.map { result -> if (result.providerId in changed)
                        result.copy(items = emptyList(), loading = false, loadingMore = false, hasMore = false, page = 0,
                            error = "来源版本或启用状态已改变，请单独刷新。") else result }) }
                if (changed.isNotEmpty()) {
                    changed.forEach {
                        catalogRequests[it] = ++catalogGeneration
                        sourceSearchRequests[it] = ++sourceSearchSequence
                        sourceSearchJobs.remove(it)?.cancel()
                    }
                    if (previewSourceId in changed) dismissSourcePreview()
                    if (resolvingSourceId in changed || pending?.option?.sourceId in changed) cancelPlaybackResolution()
                    if (prepared?.option?.sourceId in changed) clearPreparation()
                    rebuildOptions()
                    changed.forEach(::refreshSource)
                }
                projectRoutes()
            }
        }
        scope.launch {
            routing.state.collect {
                projectRoutes()
                prepared?.takeIf { it.asset.route != DownloadRoutingStore.activeSourceSnapshot(it.option.sourceId) }?.let {
                    clearPreparation()
                    active?.preparationAttempted = false
                }
            }
        }
        scope.launch {
            try { refreshAccounts() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice("追番账号未能读取，番剧浏览仍可使用。请重新连接账号。") }
        }
    }

    fun discover(mode: AnimeBrowseMode = AnimeBrowseMode.POPULAR) {
        mutableState.update { it.copy(browseMode = mode,
            catalogTag = if (mode == AnimeBrowseMode.CALENDAR) "" else it.catalogTag,
            catalogYear = if (mode == AnimeBrowseMode.CALENDAR) null else it.catalogYear) }
        browse("")
    }

    fun search(query: String) {
        val keyword = query.trim()
        if (keyword.isNotEmpty()) mutate { library.rememberSearch(keyword) }
        browse(keyword)
    }

    fun clearSearchHistory() = mutate { library.clearSearchHistory() }
    fun dismissResume(subjectId: String) = mutate { library.dismissResume(subjectId) }

    fun selectCalendarDay(day: Int?) {
        if (day != null && day !in 1..7) return
        mutableState.update { it.copy(calendarDay = day, browseMode = AnimeBrowseMode.CALENDAR, catalogTag = "", catalogYear = null) }
        browse("")
    }

    fun setCatalogFilters(year: Int?, tag: String) {
        if (year != null && year !in 1..9998) { notice("请输入有效年份。"); return }
        mutableState.update { it.copy(catalogYear = year, catalogTag = tag.trim(),
            browseMode = if (tag.isNotBlank()) AnimeBrowseMode.POPULAR else it.browseMode) }
        browse(mutableState.value.query)
    }

    fun loadMoreCatalog() {
        val current = mutableState.value
        if (!current.catalogHasMore || current.catalogLoading || current.catalogLoadingMore) return
        browse(current.query, append = true)
    }

    private fun browse(query: String, append: Boolean = false) {
        val generation = ++browseGeneration
        browseJob?.cancel()
        val before = mutableState.value
        val page = if (append) before.catalogPage + 1 else 1
        mutableState.update { it.copy(query = query, catalogLoading = !append, catalogLoadingMore = append,
            catalogError = null, catalog = if (append) it.catalog else emptyList(),
            catalogPage = if (append) it.catalogPage else 0, catalogHasMore = if (append) it.catalogHasMore else false) }
        browseJob = scope.launch {
            try {
                if (!append) metadata.cachedBrowse(before.browseMode, query, page, before.catalogYear,
                    before.catalogTag, before.calendarDay)?.let { cached ->
                    if (generation == browseGeneration) mutableState.update { it.copy(catalog = cached.items,
                        catalogPage = cached.page, catalogHasMore = cached.hasMore) }
                }
                val result = metadata.browse(before.browseMode, query, page, before.catalogYear, before.catalogTag, before.calendarDay)
                if (generation == browseGeneration) mutableState.update {
                    val added = result.items.filter { item -> !append || it.catalog.none { old -> old.id == item.id } }
                    it.copy(catalog = (if (append) it.catalog + added else result.items).distinctBy(AnimeSubject::id),
                        catalogPage = result.page, catalogHasMore = result.hasMore && (!append || added.isNotEmpty()),
                        catalogLoading = false, catalogLoadingMore = false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == browseGeneration) mutableState.update { it.copy(catalogLoading = false,
                    catalogLoadingMore = false, catalogError = failure.message ?: "番剧列表未能加载，请重试。") }
            }
        }
    }

    fun open(subject: AnimeSubject) = openDetail(subject, resumeEpisodeId = null)

    fun resume(subjectId: String) {
        val entry = library.state.value.entries[subjectId] ?: return
        val resume = entry.resume?.takeUnless { it.completed }
        if (resume?.localDownloadId != null) openDownload(resume.localDownloadId, entry)
        else openDetail(entry.subject, resume?.episodeId)
    }

    private fun openDetail(subject: AnimeSubject, resumeEpisodeId: String?) {
        closePlayback()
        val generation = ++detailGeneration
        detailJob?.cancel()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        previewSourceId = null
        previewSourceRevision = null
        ++searchGeneration
        ++previewGeneration
        resumeRequest = resumeEpisodeId?.let { subject.id to it }
        mutableState.update { it.copy(
            detail = AnimeDetail(subject, emptyList()), detailLoading = true, detailError = null,
            detailFromCache = false, relatedSubjects = emptyList(),
            sourceCatalogs = emptyList(), sourceResults = emptyList(), episodeOptions = emptyMap(),
            sourceBindings = library.state.value.bindings.filter { binding -> binding.subjectId == subject.id },
            sourceCatalogErrors = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, selectedEpisodeId = null,
            sourceGroups = emptyMap(), preferredSourceId = null,
        ) }
        // Source catalogues have independent ownership and do not wait for the metadata service.
        boundCatalogJob = scope.launch {
            try { library.awaitReady(); sources.extensions.ready.await(); loadBoundCatalogs(AnimeDetail(subject, emptyList()), generation) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentDetail(generation, subject.id)) notice(failure.message ?: "来源目录未能读取。") }
        }
        relatedJob = scope.launch {
            try {
                val related = metadata.related(subject)
                if (currentDetail(generation, subject.id)) mutableState.update { it.copy(relatedSubjects = related) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Related subjects are optional; failure cannot invalidate the current detail. */ }
        }
        detailJob = scope.launch {
            try {
                library.awaitReady()
                metadata.cachedDetail(subject)?.let { cached ->
                    if (!currentDetail(generation, subject.id)) return@launch
                    mutableState.update { it.copy(detail = cached, detailFromCache = true) }
                    rebuildOptions()
                }
                val detail = metadata.detail(subject)
                if (!currentDetail(generation, subject.id)) return@launch
                mutableState.update { it.copy(detail = detail, detailLoading = false, detailFromCache = false) }
                rebuildOptions()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (currentDetail(generation, subject.id)) mutableState.update {
                    it.copy(detailLoading = false, detailError = failure.message ?: "番剧详情未能加载，请重试。")
                }
            }
        }
    }

    fun closeDetail() {
        closePlayback()
        resumeRequest = null
        ++detailGeneration
        ++searchGeneration
        ++previewGeneration
        detailJob?.cancel()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        mutableState.update { it.copy(detail = null, detailLoading = false, detailError = null,
            sourceResults = emptyList(), sourceCatalogs = emptyList(), episodeOptions = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, preferredSourceId = null,
            sourceGroups = emptyMap(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap(),
            relatedSubjects = emptyList(), detailFromCache = false) }
    }

    private suspend fun loadBoundCatalogs(detail: AnimeDetail, generation: Long) = coroutineScope {
        library.state.value.bindings.filter { it.subjectId == detail.subject.id }.forEach { binding ->
            launch { loadBoundCatalog(binding, generation) }
        }
    }

    private suspend fun loadBoundCatalog(binding: AnimeSourceBinding, generation: Long) {
        val sourceId = binding.catalog.sourceId
        if (!currentDetail(generation, binding.subjectId) || !stillBound(binding.subjectId, binding.catalog)) return
        val request = ++catalogGeneration
        catalogRequests[sourceId] = request
        mutableState.update { it.copy(sourceCatalogErrors = it.sourceCatalogErrors - sourceId) }
        fun current() = request == catalogRequests[sourceId] &&
            currentDetail(generation, binding.subjectId) && stillBound(binding.subjectId, binding.catalog)
        try {
            val provider = sources.provider(sourceId) ?: error("这个来源当前不可用，可以解除关联。")
            val catalog = provider.catalog(binding.catalog)
            check(catalog.title.sourceId == sourceId && catalog.title.id == binding.catalog.id &&
                catalog.title.url == binding.catalog.url) { "来源目录身份已变化，请重新搜索并确认关联。" }
            if (!current()) return
            mutableState.update { it.copy(
                sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == sourceId } + catalog,
                sourceCatalogErrors = it.sourceCatalogErrors - sourceId,
            ) }
            rebuildOptions()
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (current()) mutableState.update { it.copy(sourceCatalogErrors = it.sourceCatalogErrors +
                (sourceId to (failure.message ?: "目录暂不可用，请重试。"))) }
        }
    }

    fun refreshSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        val binding = library.state.value.bindings.firstOrNull {
            it.subjectId == detail.subject.id && it.catalog.sourceId == sourceId
        } ?: return
        val generation = detailGeneration
        scope.launch { loadBoundCatalog(binding, generation) }
    }

    fun searchSources(query: String = mutableState.value.detail?.subject?.title.orEmpty()) {
        if (mutableState.value.detail == null) return
        val keyword = query.trim()
        if (keyword.isBlank()) return
        ++searchGeneration
        sourceQuery = keyword
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        mutableState.update { it.copy(sourceError = null, sourceResults =
            sources.providers.map { source -> AnimeSourceResult(source.id, source.name) }) }
        sources.providers.forEach { requestSourceSearch(it.id, append = false) }
    }

    fun retrySourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = false)
    fun loadMoreSourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = true)

    private fun requestSourceSearch(sourceId: String, append: Boolean) {
        val detail = mutableState.value.detail ?: return
        val provider = sources.provider(sourceId) ?: return
        if (sourceQuery.isBlank()) sourceQuery = detail.subject.title
        val previous = mutableState.value.sourceResults.firstOrNull { it.providerId == sourceId }
            ?: AnimeSourceResult(sourceId, provider.name)
        if (append && (!previous.hasMore || previous.loading || previous.loadingMore)) return
        val page = if (append) previous.page + 1 else 1
        val keyword = sourceQuery
        val owner = detailGeneration
        val generation = searchGeneration
        val request = ++sourceSearchSequence
        sourceSearchRequests[sourceId] = request
        sourceSearchJobs.remove(sourceId)?.cancel()
        if (mutableState.value.sourceResults.none { it.providerId == sourceId }) mutableState.update {
            it.copy(sourceResults = it.sourceResults + previous)
        }
        updateSourceResult(previous.copy(loading = !append, loadingMore = append, error = null))
        sourceSearchJobs[sourceId] = scope.launch {
            fun current() = generation == searchGeneration && sourceSearchRequests[sourceId] == request &&
                currentDetail(owner, detail.subject.id)
            try {
                val items = provider.search(keyword, page).distinctBy { it.id to it.url }
                check(items.all { it.sourceId == sourceId }) { "来源搜索返回了不同来源的条目。" }
                if (!current()) return@launch
                val added = if (append) items.filter { item -> previous.items.none { it.id == item.id && it.url == item.url } } else items
                updateSourceResult(AnimeSourceResult(provider.id, provider.name,
                    items = if (append) previous.items + added else items, page = page,
                    hasMore = provider.supportsSearchPagination && added.isNotEmpty()))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current()) updateSourceResult(previous.copy(loading = false, loadingMore = false,
                    error = failure.message ?: "这个来源暂未响应，请单独重试。"))
            }
        }
    }

    private fun updateSourceResult(result: AnimeSourceResult) = mutableState.update {
        it.copy(sourceResults = it.sourceResults.map { old -> if (old.providerId == result.providerId) result else old })
    }

    fun previewSource(title: AnimeSourceTitle) {
        val subjectId = mutableState.value.detail?.subject?.id ?: return
        val owner = detailGeneration
        val generation = ++previewGeneration
        previewJob?.cancel()
        previewSourceId = title.sourceId
        previewSourceRevision = sourceRevision(title.sourceId)
        mutableState.update { it.copy(previewCatalog = null, previewLoading = true, sourceError = null) }
        previewJob = scope.launch {
            try {
                val provider = sources.provider(title.sourceId) ?: error("这个来源尚未安装。")
                val catalog = provider.catalog(title)
                if (generation == previewGeneration && currentDetail(owner, subjectId))
                    mutableState.update { it.copy(previewCatalog = catalog, previewLoading = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == previewGeneration && currentDetail(owner, subjectId))
                    mutableState.update { it.copy(previewLoading = false, sourceError = failure.message ?: "来源目录未能读取。") }
            }
        }
    }

    fun dismissSourcePreview() {
        ++previewGeneration
        previewJob?.cancel()
        previewSourceId = null
        previewSourceRevision = null
        mutableState.update { it.copy(previewCatalog = null, previewLoading = false) }
    }

    fun bindPreviewSource() {
        val detail = mutableState.value.detail ?: return
        val catalog = mutableState.value.previewCatalog ?: return
        val expectedRevision = previewSourceRevision
        if (previewSourceId != catalog.title.sourceId || sources.provider(catalog.title.sourceId) == null ||
            expectedRevision != sourceRevision(catalog.title.sourceId)) {
            dismissSourcePreview()
            notice("来源已经变化，请重新读取条目后关联。")
            return
        }
        val owner = detailGeneration
        mutate {
            val groups = catalog.episodes.map { it.group }.distinct()
            library.edit(detail.subject, editBindings = { bindings ->
                check(sources.provider(catalog.title.sourceId) != null && expectedRevision == sourceRevision(catalog.title.sourceId)) {
                    "来源已经变化，请重新读取条目后关联。"
                }
                val previous = bindings.firstOrNull { it.catalog.sourceId == catalog.title.sourceId }
                bindings.filterNot { it.catalog.sourceId == catalog.title.sourceId } + AnimeSourceBinding(
                    detail.subject.id, catalog.title,
                    episodeMappings = previous?.takeIf { it.catalog.id == catalog.title.id && it.catalog.url == catalog.title.url }
                        ?.episodeMappings.orEmpty(),
                    preferred = previous?.preferred ?: bindings.isEmpty(),
                    selectedGroup = previous?.takeIf { it.catalog.id == catalog.title.id && it.catalog.url == catalog.title.url }
                        ?.selectedGroup ?: groups.singleOrNull())
            })
            if (currentDetail(owner, detail.subject.id)) {
                ++previewGeneration
                previewSourceId = null
                previewSourceRevision = null
                catalogRequests[catalog.title.sourceId] = ++catalogGeneration
                mutableState.update { it.copy(previewCatalog = null, previewLoading = false,
                    sourceCatalogErrors = it.sourceCatalogErrors - catalog.title.sourceId,
                    sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == catalog.title.sourceId } +
                        if (sources.provider(catalog.title.sourceId) != null && expectedRevision == sourceRevision(catalog.title.sourceId))
                            listOf(catalog) else emptyList()) }
                rebuildOptions()
                if (expectedRevision != sourceRevision(catalog.title.sourceId)) refreshSource(catalog.title.sourceId)
            }
            notice("已关联这个来源，可以撤销。")
        }
    }

    fun unlinkSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.filterNot { it.catalog.sourceId == sourceId } })
            if (mutableState.value.detail?.subject?.id == detail.subject.id) {
                catalogRequests[sourceId] = ++catalogGeneration
                mutableState.update { it.copy(
                    sourceCatalogs = it.sourceCatalogs.filterNot { catalog -> catalog.title.sourceId == sourceId },
                    sourceCatalogErrors = it.sourceCatalogErrors - sourceId,
                ) }
                rebuildOptions()
            }
            notice("已解除关联，观看记录保留。")
        }
    }

    fun setPreferredSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        mutate {
            library.edit(detail.subject, editBindings = { bindings ->
                check(bindings.any { it.catalog.sourceId == sourceId }) { "请先关联这个来源。" }
                bindings.map { it.copy(preferred = it.catalog.sourceId == sourceId) }
            })
            notice("后续选集优先使用这个来源，当前播放不变。")
        }
    }

    fun selectSourceGroup(sourceId: String, group: String) {
        val detail = mutableState.value.detail ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId } ?: return
        if (catalog.episodes.none { it.group == group }) return
        val allowedIds = catalog.episodes.filter { it.group == group }.map { it.id }.toSet()
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.map { binding ->
                if (binding.catalog.sourceId != sourceId) binding else {
                    check(binding.catalog.id == catalog.title.id && binding.catalog.url == catalog.title.url) {
                        "来源关联已经变化，请重新选择季与线路。"
                    }
                    binding.copy(selectedGroup = group,
                        episodeMappings = binding.episodeMappings.filterValues { it in allowedIds })
                }
            } })
            notice("已选择季与线路；同组唯一对应的集数可直接播放。")
        }
    }

    fun mapEpisode(episodeId: String, sourceId: String, sourceEpisodeId: String) {
        val detail = mutableState.value.detail ?: return
        if (detail.episodes.none { it.id == episodeId }) return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId } ?: return
        val episode = catalog.episodes.singleOrNull { it.id == sourceEpisodeId } ?: return
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.map { binding ->
                if (binding.catalog.sourceId != sourceId) binding else {
                    check(binding.catalog.id == catalog.title.id && binding.catalog.url == catalog.title.url) {
                        "来源关联已经变化，请重新核对这一集。"
                    }
                    binding.copy(selectedGroup = episode.group,
                        episodeMappings = binding.episodeMappings.filterValues { oldId ->
                            catalog.episodes.any { it.id == oldId && it.group == episode.group }
                        } + (episodeId to sourceEpisodeId))
                }
            } })
            notice("已记住本集对应关系，可以撤销。")
        }
    }

    private fun rebuildOptions() {
        val detail = mutableState.value.detail ?: return
        val bindings = library.state.value.bindings.filter { it.subjectId == detail.subject.id }
        val catalogs = mutableState.value.sourceCatalogs
        val preferred = bindings.firstOrNull { it.preferred }?.catalog?.sourceId
        val options = detail.episodes.associate { canonical ->
            canonical.id to bindings.flatMap { binding ->
                val catalog = catalogs.firstOrNull {
                    it.title.sourceId == binding.catalog.sourceId && it.title.id == binding.catalog.id && it.title.url == binding.catalog.url
                } ?: return@flatMap emptyList()
                val group = binding.selectedGroup ?: catalog.episodes.map { it.group }.distinct().singleOrNull()
                    ?: return@flatMap emptyList()
                val inGroup = catalog.episodes.filter { it.group == group }
                val explicit = binding.episodeMappings[canonical.id]?.let { id -> inGroup.singleOrNull { it.id == id } }
                val matches = explicit?.let(::listOf) ?: inGroup.filter {
                    it.kind == canonical.kind && it.number != null && it.number == canonical.number
                }
                matches.map { episode -> AnimeEpisodeOption(binding.catalog.sourceId,
                    sources.provider(binding.catalog.sourceId)?.name ?: binding.catalog.sourceId,
                    binding.catalog, episode, confirmed = explicit != null || matches.size == 1) }
            }.sortedWith(compareByDescending<AnimeEpisodeOption> { it.sourceId == preferred }.thenBy { it.sourceName })
        }
        mutableState.update { it.copy(episodeOptions = options, preferredSourceId = preferred,
            sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true,
            sourceBindings = bindings,
            sourceCatalogErrors = it.sourceCatalogErrors.filterKeys { id -> bindings.any { binding -> binding.catalog.sourceId == id } },
            sourceGroups = bindings.mapNotNull { binding ->
                binding.selectedGroup?.let { group -> binding.catalog.sourceId to group }
            }.toMap()) }
        prepared?.let { candidate ->
            if (!validCandidate(candidate) || options[candidate.episode.id].orEmpty().firstOrNull { it.confirmed } != candidate.option) {
                clearPreparation()
                active?.preparationAttempted = false
            }
        }
        resumeRequest?.takeIf { it.first == detail.subject.id }?.let { request ->
            if (options[request.second].orEmpty().any { it.confirmed }) {
                resumeRequest = null
                play(request.second)
            }
        }
    }

    fun play(episodeId: String, option: AnimeEpisodeOption? = null) {
        recoverySources.clear()
        sourceRecoveryAttempts = 0
        resolveAndPlay(episodeId, option)
    }

    private fun resolveAndPlay(episodeId: String, option: AnimeEpisodeOption? = null) {
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        val options = mutableState.value.episodeOptions[episodeId].orEmpty()
        val selected = option?.takeIf { offered -> options.any { it == offered && it.confirmed } }
            ?: if (option == null) options.firstOrNull { it.confirmed } else null
        if (selected == null) {
            mutableState.update { it.copy(sourceError = "请先确认本集的来源与集数对应。") }
            return
        }
        resumeRequest = null
        val available = prepared?.takeIf { it.episode.id == episodeId && it.option == selected && validCandidate(it) }
        clearPreparation()
        val owner = detailGeneration
        val generation = ++resolveGeneration
        resolveJob?.cancel()
        pending = null
        resolvingSourceId = selected.sourceId
        recoverySources += selected.sourceId
        mutableState.update { it.copy(resolving = true, sourceError = null, pendingPlayback = null) }
        resolveJob = scope.launch {
            try {
                library.awaitReady()
                val revision = sourceRevision(selected.sourceId)
                val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                    it.title.sourceId == selected.sourceId && it.title.id == selected.catalog.id && it.title.url == selected.catalog.url
                } ?: error("来源目录已变化，请重新选集。")
                val provider = sources.provider(selected.sourceId) ?: error("这个来源当前不可用。")
                val resolved = available?.asset ?: provider.resolve(catalog, selected.episode).copy(episodeId = episode.id,
                    title = detail.subject.title + " · " + episode.title)
                check(resolved.sourceId == selected.sourceId && resolved.sourceEpisodeId == selected.episode.id) {
                    "来源返回的剧集身份不一致，请重新读取目录。"
                }
                if (generation != resolveGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                check(revision == sourceRevision(selected.sourceId)) { "来源已更新，请重新解析。" }
                check(resolved.expiresAt == null || resolved.expiresAt > wallTimeMillis()) { "播放地址已过期，请重试解析。" }
                val live = active?.takeIf { it.detail.subject.id == detail.subject.id && it.episode.id == episodeId }
                val liveSnapshot = playerInstance?.state?.value?.takeIf { snapshot -> live != null && belongsTo(live, snapshot) }
                val previous = library.progressFor(detail.subject.id, episodeId)
                val position = if (liveSnapshot != null && !liveSnapshot.ended) liveSnapshot.positionMs.coerceAtLeast(0)
                    else previous?.takeUnless { it.completed }?.positionMs ?: 0L
                val previousAsset = live?.asset?.id ?: previous?.assetId
                val choice = PendingPlayback(detail, episode, selected, resolved, position, generation, revision)
                if (position > 0L && previousAsset != null && previousAsset != resolved.id) {
                    pending = choice
                    mutableState.update { it.copy(resolving = false, pendingPlayback = AnimePlaybackChoice(
                        episode, selected, position, live?.asset?.sourceId ?: previous?.sourceId.orEmpty())) }
                } else startPlayback(choice)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id)) {
                    mutableState.update { it.copy(resolving = false, sourceError = failure.message ?: "播放地址解析失败，请切换来源或重试。") }
                    active?.takeIf { playerInstance?.state?.value?.phase == AnimePlayerPhase.ERROR }?.let(::recoverPlayback)
                }
            } finally { if (generation == resolveGeneration) resolvingSourceId = null }
        }
    }

    fun confirmPlayback(resumePrevious: Boolean) {
        val choice = pending ?: return
        if (choice.generation != resolveGeneration || mutableState.value.detail?.subject?.id != choice.detail.subject.id) return
        pending = null
        mutableState.update { it.copy(pendingPlayback = null, resolving = true) }
        resolveJob = scope.launch {
            try { startPlayback(choice.copy(positionMs = if (resumePrevious) choice.positionMs else 0L,
                useSourceStart = resumePrevious)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (choice.generation == resolveGeneration) mutableState.update {
                    it.copy(resolving = false, sourceError = failure.message ?: "播放器未能打开。")
                }
            }
        }
    }

    fun dismissPlaybackChoice() = cancelPlaybackResolution()

    fun cancelPlaybackResolution() {
        ++resolveGeneration
        resolveJob?.cancel()
        resolveJob = null
        resolvingSourceId = null
        pending = null
        mutableState.update { it.copy(resolving = false, pendingPlayback = null) }
    }

    private fun sourceRevision(sourceId: String): String? = sources.extensions.state.value.firstOrNull { it.id == sourceId }?.revision

    private fun validCandidate(choice: PendingPlayback): Boolean =
        choice.localMedia != null || sources.provider(choice.option.sourceId) != null && sourceRevision(choice.option.sourceId) == choice.sourceRevision &&
            (choice.sourceRevision == null || choice.asset.sourceRevision == choice.sourceRevision) &&
            (choice.asset.expiresAt == null || choice.asset.expiresAt > wallTimeMillis()) &&
            choice.asset.route == DownloadRoutingStore.activeSourceSnapshot(choice.option.sourceId) &&
            mutableState.value.episodeOptions[choice.episode.id].orEmpty().any { it == choice.option && it.confirmed }

    private suspend fun startPlayback(choice: PendingPlayback, rememberPrevious: Boolean = true) {
        check(validCandidate(choice)) { "来源、网络路线或集数对应已变化，请重新解析。" }
        if (choice.generation != resolveGeneration) return
        saveCurrentPlayback()
        if (choice.generation != resolveGeneration) return
        check(validCandidate(choice) && mutableState.value.detail?.subject?.id == choice.detail.subject.id) {
            "来源关联已变化，请重新选择这一集。"
        }
        val engine = player
        val old = active
        val oldSnapshot = engine.state.value
        if (rememberPrevious && old != null && old.detail.subject.id == choice.detail.subject.id &&
            belongsTo(old, oldSnapshot) && oldSnapshot.phase == AnimePlayerPhase.READY)
            rollback = PlaybackReceipt(old, oldSnapshot.positionMs, oldSnapshot.playWhenReady)
        else if (old?.detail?.subject?.id != choice.detail.subject.id) rollback = null
        val nextEpoch = (playbackEpochs[choice.detail.subject.id] ?: 0L) + 1L
        playbackEpochs[choice.detail.subject.id] = nextEpoch
        pending = null
        val position = if (choice.positionMs > 0 || !choice.useSourceStart) choice.positionMs
            else choice.asset.startPositionMs?.coerceAtLeast(0) ?: 0L
        active = ActivePlayback(choice.detail, choice.episode, choice.option, choice.asset,
            nextEpoch, engine.state.value.sessionGeneration + 1, library.state.value.restoreEpoch,
            sourceRevision = choice.sourceRevision, localMedia = choice.localMedia, openingSkipped = position > 0)
        danmaku.setContext(active?.let(::playbackKey))
        lastCheckpointAt = 0L
        lastSavedPosition = -1L
        lastSavedPhase = null
        mutableState.update { it.copy(resolving = false, selectedEpisodeId = choice.episode.id, pendingPlayback = null,
            canRestorePlayback = false, sourceError = null,
            sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true,
            playbackResolutions = choice.asset.resolutions, selectedResolutionId = choice.resolutionId ?:
                choice.asset.resolutions.singleOrNull { item -> item.url == choice.asset.url }?.id) }
        engine.configure(playerOptions(mutableState.value.settings))
        engine.open(AnimePlayerRequest(choice.asset, position, choice.playWhenReady, choice.localMedia))
    }

    fun restorePreviousPlayback() {
        val receipt = rollback ?: return
        val previous = receipt.playback
        if (previous.detail.subject.id != mutableState.value.detail?.subject?.id) return
        cancelPlaybackResolution()
        clearPreparation()
        val choice = PendingPlayback(previous.detail, previous.episode, previous.option, previous.asset,
            receipt.positionMs, resolveGeneration, previous.sourceRevision, receipt.playWhenReady,
            localMedia = previous.localMedia, useSourceStart = false)
        scope.launch {
            try {
                startPlayback(choice, rememberPrevious = false)
                rollback = null
                notice("已恢复上次播放。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "原播放地址已失效，请重新选择来源。") }
        }
    }

    fun selectResolution(id: String) {
        val owner = active ?: return
        val resolution = owner.asset.resolutions.singleOrNull { it.id == id } ?: return
        val snapshot = playerInstance?.state?.value ?: return
        if (!belongsTo(owner, snapshot)) return
        cancelPlaybackResolution()
        clearPreparation()
        val asset = owner.asset.copy(id = owner.option.sourceId + ":quality:" +
            dev.mangaflow.anime.sources.animeHash(owner.option.episode.id + "\n" + resolution.id + "\n" +
                dev.mangaflow.anime.sources.animeContentIdentity(resolution.url)),
            url = resolution.url, mimeType = resolution.mimeType ?: owner.asset.mimeType,
            headers = owner.asset.headers + resolution.headers)
        val choice = PendingPlayback(owner.detail, owner.episode, owner.option, asset,
            snapshot.positionMs, resolveGeneration, owner.sourceRevision, snapshot.playWhenReady, id,
            useSourceStart = false)
        scope.launch {
            try { startPlayback(choice) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "清晰度切换未完成。") }
        }
    }

    fun retryPlayback() {
        val current = active ?: return
        if (current.localMedia != null) player.retry() else play(current.episode.id, current.option)
    }

    fun closePlayback() = stopPlayback(discardCheckpoint = false)

    private fun stopPlayback(discardCheckpoint: Boolean) {
        if (!discardCheckpoint) flushPlayback()
        else active?.let { playbackEpochs[it.detail.subject.id] = it.epoch + 1 }
        cancelPlaybackResolution()
        clearPreparation()
        rollback = null
        active = null
        danmaku.setContext(null)
        playerInstance?.stop()
        if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(false)
        mutableState.update { it.copy(selectedEpisodeId = null, playbackResolutions = emptyList(),
            selectedResolutionId = null, canRestorePlayback = false, sourceDanmakuAvailable = false) }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) flushPlayback()
        playerInstance?.setForeground(value)
        if (downloadsOwner.isInitialized()) downloads.setForeground(value)
        if (!value && !mutableState.value.settings.backgroundAudio) clearPreparation()
    }

    fun flushPlayback() {
        val owner = active ?: return
        val snapshot = playerInstance?.state?.value ?: return
        checkpoint(owner, snapshot, force = true)
    }

    private fun belongsTo(owner: ActivePlayback, snapshot: AnimePlayerState) =
        snapshot.sessionGeneration == owner.playerGeneration && snapshot.episodeId == owner.episode.id && snapshot.assetKey == owner.asset.id

    private fun playbackKey(owner: ActivePlayback) = "${owner.detail.subject.id}/${owner.episode.id}/${owner.epoch}/${owner.libraryEpoch}"

    private fun hasSourceDanmaku(owner: ActivePlayback): Boolean = owner.localMedia == null &&
        owner.option.sourceId == "anime-lanerc-ani" && sources.provider(owner.option.sourceId) != null &&
        mutableState.value.sourceCatalogs.any { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && owner.option.episode in it.episodes }

    fun loadCurrentSourceDanmaku() {
        val owner = active ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && it.title.url == owner.option.catalog.url } ?: run {
            notice("当前来源目录已改变，请刷新后核对弹幕剧集。"); return
        }
        danmaku.loadSource(catalog, owner.option.episode)
    }

    private suspend fun saveCurrentPlayback() {
        val owner = active ?: return
        val snapshot = playerInstance?.state?.value ?: return
        if (!belongsTo(owner, snapshot) || snapshot.phase !in setOf(AnimePlayerPhase.READY, AnimePlayerPhase.ENDED)) return
        library.checkpoint(owner.detail.subject, progress(owner, snapshot)) {
            playbackEpochs[owner.detail.subject.id] == owner.epoch && library.state.value.restoreEpoch == owner.libraryEpoch
        }
    }

    private fun progress(owner: ActivePlayback, snapshot: AnimePlayerState) = AnimeWatchProgress(
        owner.episode.id, owner.asset.id, owner.asset.sourceId, snapshot.positionMs.coerceAtLeast(0L),
        snapshot.durationMs.coerceAtLeast(0L), wallTimeMillis(), snapshot.ended, owner.detail.subject.id, owner.localMedia?.downloadId)

    private fun observePlayer(engine: AnimePlayer) {
        scope.launch {
            engine.state.collect { snapshot ->
                val owner = active ?: return@collect
                if (!belongsTo(owner, snapshot)) return@collect
                danmaku.updatePosition(playbackKey(owner), snapshot.positionMs, snapshot.isPlaying)
                if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(snapshot.isBuffering ||
                    snapshot.phase == AnimePlayerPhase.OPENING || mutableState.value.resolving)
                checkpoint(owner, snapshot, force = snapshot.phase != lastSavedPhase || snapshot.ended || !snapshot.playWhenReady)
                if (snapshot.phase == AnimePlayerPhase.ERROR) {
                    mutableState.update { it.copy(canRestorePlayback = rollback != null) }
                    if (!owner.recoveryHandled) { owner.recoveryHandled = true; recoverPlayback(owner) }
                }
                if (snapshot.isPlaying) {
                    val settings = mutableState.value.settings
                    if (!owner.openingSkipped && settings.skipOpeningSeconds > 0 && snapshot.durationMs > 0) {
                        owner.openingSkipped = true
                        val target = settings.skipOpeningSeconds * 1_000L
                        if (target < snapshot.durationMs && snapshot.positionMs < target) engine.seekTo(target)
                    }
                    if (!owner.endingSkipped && settings.skipEndingSeconds > 0 && snapshot.durationMs > 0 &&
                        snapshot.positionMs >= (snapshot.durationMs - settings.skipEndingSeconds * 1_000L).coerceAtLeast(0) &&
                        settings.skipEndingSeconds * 1_000L < snapshot.durationMs) {
                        owner.endingSkipped = true
                        engine.seekTo(snapshot.durationMs)
                    }
                    if (owner.localMedia == null && settings.prepareNextEpisode && !owner.preparationAttempted && !mutableState.value.resolving) prepareNext(owner)
                } else if (snapshot.isBuffering || !snapshot.playWhenReady) {
                    if (prepareJob?.isActive == true) { clearPreparation(); owner.preparationAttempted = false }
                }
                if (snapshot.ended && !owner.endActionHandled) {
                    owner.endActionHandled = true
                    if (mutableState.value.sleepAtEpisodeEnd) {
                        setSleepTimer(null); cancelPlaybackResolution(); clearPreparation(); engine.pause()
                    }
                    else if (mutableState.value.settings.autoPlayNext &&
                        (foreground || mutableState.value.settings.backgroundAudio || snapshot.inPictureInPicture)) {
                        nextEpisode(owner)?.let { next -> play(next.id) }
                    }
                }
            }
        }
    }

    private fun checkpoint(owner: ActivePlayback, snapshot: AnimePlayerState, force: Boolean) {
        if (!belongsTo(owner, snapshot) || snapshot.phase != AnimePlayerPhase.READY && snapshot.phase != AnimePlayerPhase.ENDED) return
        val now = wallTimeMillis()
        if (!force && now - lastCheckpointAt < 5_000L) return
        val ended = snapshot.ended && !owner.endedSaved
        if (!ended && snapshot.positionMs == lastSavedPosition && snapshot.phase == lastSavedPhase) return
        lastCheckpointAt = now
        lastSavedPosition = snapshot.positionMs
        lastSavedPhase = snapshot.phase
        val progress = progress(owner, snapshot)
        scope.launch {
            try {
                library.checkpoint(owner.detail.subject, progress) {
                    playbackEpochs[owner.detail.subject.id] == owner.epoch && library.state.value.restoreEpoch == owner.libraryEpoch
                }
                if (ended) owner.endedSaved = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (active === owner) { lastCheckpointAt = 0L; lastSavedPosition = -1L; lastSavedPhase = null }
                notice(failure.message ?: "播放进度暂未保存，请重试。")
            }
        }
    }

    private fun nextEpisode(owner: ActivePlayback): AnimeEpisode? {
        val episodes = owner.detail.episodes.filter { it.kind == owner.episode.kind }.sortedBy { it.number }
        val index = episodes.indexOfFirst { it.id == owner.episode.id }
        return episodes.getOrNull(index + 1)?.takeIf { next -> index >= 0 &&
            mutableState.value.episodeOptions[next.id].orEmpty().any { it.confirmed } }
    }

    private fun prepareNext(owner: ActivePlayback) {
        val next = nextEpisode(owner) ?: return
        val option = mutableState.value.episodeOptions[next.id].orEmpty().firstOrNull { it.confirmed } ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == option.catalog.sourceId &&
            it.title.id == option.catalog.id && it.title.url == option.catalog.url } ?: return
        val provider = sources.provider(option.sourceId) ?: return
        owner.preparationAttempted = true
        val revision = sourceRevision(option.sourceId)
        val generation = resolveGeneration
        prepareJob = scope.launch {
            try {
                val asset = provider.resolve(catalog, option.episode).copy(episodeId = next.id,
                    title = owner.detail.subject.title + " · " + next.title)
                val choice = PendingPlayback(owner.detail, next, option, asset, 0L, generation, revision)
                if (active === owner && generation == resolveGeneration && mutableState.value.settings.prepareNextEpisode &&
                    asset.sourceId == option.sourceId && asset.sourceEpisodeId == option.episode.id && validCandidate(choice)) {
                    prepared = choice
                    mutableState.update { it.copy(nextEpisodeReady = true) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Foreground playback remains authoritative; preparation is optional. */ }
        }
    }

    private fun clearPreparation() {
        prepareJob?.cancel()
        prepareJob = null
        prepared = null
        mutableState.update { it.copy(nextEpisodeReady = false) }
    }

    private fun recoverPlayback(owner: ActivePlayback) {
        if (owner.localMedia != null || !mutableState.value.settings.automaticSourceRecovery || active !== owner ||
            pending != null || mutableState.value.resolving) return
        if (sourceRecoveryAttempts >= mutableState.value.settings.automaticRecoveryAttempts) return
        recoverySources += owner.option.sourceId
        val alternative = mutableState.value.episodeOptions[owner.episode.id].orEmpty()
            .firstOrNull { it.confirmed && it.sourceId !in recoverySources } ?: return
        sourceRecoveryAttempts++
        resolveAndPlay(owner.episode.id, alternative)
    }

    fun updatePlaybackSettings(settings: AnimePlaybackSettings) = mutate {
        library.setSettings(settings.validate())
        playerInstance?.configure(playerOptions(settings))
        if (!settings.prepareNextEpisode) clearPreparation()
        else active?.preparationAttempted = false
    }

    private fun playerOptions(settings: AnimePlaybackSettings) = AnimePlayerOptions(settings.backgroundAudio,
        settings.bufferMinMs, settings.bufferMaxMs, settings.bufferStartMs, settings.bufferRebufferMs, settings.automaticRecoveryAttempts)

    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false) {
        if (minutes != null && minutes <= 0) { notice("睡眠计时需要大于零的分钟数。"); return }
        sleepJob?.cancel()
        val deadline = minutes?.let { wallTimeMillis() + it * 60_000L }
        mutableState.update { it.copy(sleepDeadlineMs = deadline, sleepAtEpisodeEnd = atEpisodeEnd && deadline == null) }
        if (deadline != null) sleepJob = scope.launch {
            delay((deadline - wallTimeMillis()).coerceAtLeast(0))
            if (mutableState.value.sleepDeadlineMs == deadline) {
                cancelPlaybackResolution()
                clearPreparation()
                playerInstance?.pause()
                flushPlayback()
                mutableState.update { it.copy(sleepDeadlineMs = null, sleepAtEpisodeEnd = false) }
                notice("已到睡眠时间，播放已暂停。")
            }
        }
    }

    fun downloadCurrent() {
        val owner = active ?: run { notice("请先打开要保存的剧集。"); return }
        if (owner.localMedia != null) { notice("当前播放来自已保存的离线剧集。"); return }
        mutate {
            val snapshot = playerInstance?.state?.value
            downloads.setPlaybackBusy(snapshot?.isBuffering == true || snapshot?.phase == AnimePlayerPhase.OPENING || mutableState.value.resolving)
            downloads.enqueue(owner.detail.subject, owner.episode, owner.asset)
            notice("已加入离线下载，可在下载列表查看进度。")
        }
    }

    fun pauseDownload(id: String) = mutate { downloads.pause(id) }
    fun resumeDownload(id: String) = mutate { downloads.resume(id) }
    fun removeDownload(id: String) = mutate { downloads.remove(id) }

    fun playDownload(id: String) = openDownload(id, fallback = null)

    private fun openDownload(id: String, fallback: AnimeLibraryEntry?) {
        cancelPlaybackResolution()
        clearPreparation()
        val request = resolveGeneration
        mutableState.update { it.copy(resolving = true, sourceError = null) }
        resolveJob = scope.launch {
            try {
                library.awaitReady()
                val offline = downloads.playback(id)
                check(fallback == null || offline.subject.id == fallback.subject.id &&
                    offline.episode.id == fallback.resume?.episodeId && offline.asset.id == fallback.resume?.assetId) {
                    "离线内容与续看记录不一致。"
                }
                if (request != resolveGeneration) return@launch
                ++detailGeneration
                ++searchGeneration
                ++previewGeneration
                detailJob?.cancel(); boundCatalogJob?.cancel(); relatedJob?.cancel(); previewJob?.cancel()
                sourceSearchJobs.values.forEach { it.cancel() }
                sourceSearchJobs.clear()
                resumeRequest = null
                val cached = metadata.cachedDetail(offline.subject)
                if (request != resolveGeneration) return@launch
                val detail = cached?.takeIf { it.episodes.any { episode -> episode.id == offline.episode.id } }
                    ?: AnimeDetail(offline.subject, listOf(offline.episode))
                val catalog = AnimeSourceTitle(offline.asset.sourceId, id, offline.subject.title, "",
                    offline.subject.coverUrl, offline.subject.year)
                val native = AnimeSourceEpisode(offline.asset.sourceEpisodeId, offline.episode.title, "",
                    offline.episode.number, offline.episode.kind, edition = offline.asset.edition)
                val option = AnimeEpisodeOption(offline.asset.sourceId, "离线", catalog, native, confirmed = true)
                val previous = library.progressFor(offline.subject.id, offline.episode.id)
                val position = previous?.takeIf { !it.completed && it.assetId == offline.asset.id }?.positionMs ?: 0L
                mutableState.update { it.copy(detail = detail, detailLoading = false, detailError = null,
                    detailFromCache = cached != null, relatedSubjects = emptyList(), sourceResults = emptyList(),
                    sourceCatalogs = emptyList(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap(),
                    episodeOptions = emptyMap(), previewCatalog = null, previewLoading = false,
                    preferredSourceId = null, sourceGroups = emptyMap()) }
                startPlayback(PendingPlayback(detail, offline.episode, option, offline.asset, position, request,
                    localMedia = offline.localMedia))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == resolveGeneration) {
                    if (fallback != null) {
                        openDetail(fallback.subject, fallback.resume?.episodeId)
                        notice("原离线文件不可用，已打开该集的关联来源。")
                    } else mutableState.update { it.copy(resolving = false,
                        sourceError = failure.message ?: "离线文件未能打开，请检查下载列表。") }
                }
            }
        }
    }

    fun setFollowed(subject: AnimeSubject, followed: Boolean) = mutate {
        library.edit(subject) { it.copy(followed = followed) }
        notice(if (followed) "已加入追番。" else "已取消追番，观看记录仍保留。")
    }

    fun setWatchStatus(subjectId: String, status: AnimeWatchStatus) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(status = status, followed = true) } }
    }

    fun setFolder(subjectId: String, folder: String) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(folder = folder.trim()) } }
    }

    fun setPinned(subjectId: String, pinned: Boolean) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(pinned = pinned) } }
    }

    fun clearWatchHistory(subjectId: String) = clearEntryHistory(subjectId, removeEntry = false)
    fun removeLibraryEntry(subjectId: String) = clearEntryHistory(subjectId, removeEntry = true)

    private fun clearEntryHistory(subjectId: String, removeEntry: Boolean) {
        if (active?.detail?.subject?.id == subjectId) stopPlayback(discardCheckpoint = true)
        else playbackEpochs[subjectId] = (playbackEpochs[subjectId] ?: 0L) + 1
        if (mutableState.value.detail?.subject?.id == subjectId) cancelPlaybackResolution()
        resumeRequest = resumeRequest?.takeUnless { it.first == subjectId }
        mutate {
            library.clearWatchHistory(subjectId, removeEntry)
            if (mutableState.value.detail?.subject?.id == subjectId && removeEntry) {
                mutableState.update { it.copy(sourceCatalogs = emptyList()) }
                rebuildOptions()
            }
            notice(if (removeEntry) "已移除这部番的本机记录。" else "已清除观看位置；追番状态与已看标记保留。")
        }
    }

    fun installSource(id: String) = sourceOperation(id) { sources.extensions.install(id) }
    fun updateSource(id: String) = sourceOperation(id) { sources.extensions.update(id) }
    fun rollbackSource(id: String) = sourceOperation(id) { sources.extensions.rollback(id) }
    fun enableSource(id: String, enabled: Boolean) = sourceOperation(id) { sources.extensions.setEnabled(id, enabled) }
    fun removeSource(id: String) = sourceOperation(id) { sources.extensions.remove(id) }
    fun setSourceOptions(id: String, optionsJson: String) = sourceOperation(id) { sources.extensions.setOptions(id, optionsJson) }

    fun installVideoSources() = sourceOperation("all-video") {
        val failures = sources.extensions.installAllVideo()
        notice(if (failures.isEmpty()) "视频来源已安装，可在来源面板关联番剧。"
            else "来源安装已完成；${failures.size} 个来源失败，可在对应条目重试。")
    }

    private fun sourceOperation(id: String, block: suspend () -> Unit) {
        if (id in mutableState.value.sourceOperationIds || "all-video" in mutableState.value.sourceOperationIds ||
            id == "all-video" && mutableState.value.sourceOperationIds.isNotEmpty()) return
        mutableState.update { it.copy(sourceOperationIds = it.sourceOperationIds + id) }
        scope.launch {
            try { sources.extensions.ready.await(); block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "来源操作未完成，原版本已保留。") }
            finally { mutableState.update { it.copy(sourceOperationIds = it.sourceOperationIds - id) } }
        }
    }

    private fun projectRoutes() {
        val settings = routing.state.value
        val ids = sourceHomes.keys + sources.extensions.state.value.map { it.id } + tracking.providerIds
        mutableState.update { it.copy(sourceRoutes = ids.associateWith { id ->
            settings.sourceOverrides[id]?.mode ?: settings.defaultMode
        }) }
    }

    fun setEpisodeCompleted(episodeId: String, completed: Boolean) {
        val detail = mutableState.value.detail ?: return
        if (detail.episodes.none { it.id == episodeId }) return
        mutate {
            library.edit(detail.subject) { it.copy(completedEpisodeIds =
                if (completed) it.completedEpisodeIds + episodeId else it.completedEpisodeIds - episodeId) }
        }
    }

    fun connectTracking(providerId: String, token: String) {
        if (mutableState.value.syncing) return
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                val account = tracking.connect(providerId, token.trim())
                clearProviderReviews(providerId)
                refreshAccounts()
                notice("已连接 " + account.displayName + "。在番剧详情中核对进度后再同步。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "账号连接失败，原账号未替换。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    fun disconnectTracking(providerId: String) {
        if (mutableState.value.syncing) return
        mutate {
            withContext(Dispatchers.IO) { tracking.disconnect(providerId) }
            clearProviderReviews(providerId)
            refreshAccounts()
            notice("账号已断开，本地追番和播放进度保留。")
        }
    }

    private suspend fun refreshAccounts() {
        val (accounts, problems) = withContext(Dispatchers.IO) {
            tracking.providerIds.mapNotNull(tracking::account) to
                tracking.providerIds.mapNotNull(tracking::credentialError)
        }
        mutableState.update { it.copy(accounts = accounts,
            notice = problems.firstOrNull() ?: it.notice) }
    }

    private fun clearProviderReviews(providerId: String) {
        val ids = mutableState.value.reviews.filter { it.providerId == providerId }.map { it.id }.toSet()
        ids.forEach(reviewSnapshots::remove)
        mutableState.update { it.copy(reviews = it.reviews.filterNot { review -> review.id in ids }) }
    }

    fun reviewTracking(providerId: String, remoteSubjectId: String? = null) {
        val detail = mutableState.value.detail ?: return
        if (detail.episodes.isEmpty() || mutableState.value.syncing) return
        val account = mutableState.value.accounts.firstOrNull { it.providerId == providerId }
            ?: run { notice("请先连接这个追番账号。"); return }
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                library.awaitReady()
                val remoteId = remoteSubjectId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: library.trackingTarget(detail.subject.id, providerId, account.ownerId)
                    ?: (if (providerId == "anime-bangumi") detail.subject.externalIds["bangumi"]
                        ?: detail.subject.id.removePrefix("bangumi:").takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) } else null)
                    ?: error("请填写这个平台的番剧 ID，并确认对应的是同一季。")
                require(remoteId.isNotBlank() && remoteId.all(Char::isDigit)) { "番剧 ID 应为该平台的数字 ID。" }
                val remote = tracking.read(providerId, remoteId)
                check(tracking.account(providerId)?.ownerId == remote.ownerId && remote.ownerId == account.ownerId) {
                    "追番账号已经变化，请重新核对。"
                }
                library.setTrackingTarget(detail.subject.id, providerId, account.ownerId, remoteId)
                installReview(detail, remote)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "远端进度读取失败，现有记录和差异保留。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    private fun installReview(detail: AnimeDetail, remote: AnimeTrackingSnapshot) {
        val local = library.state.value.entries[detail.subject.id] ?: AnimeLibraryEntry(detail.subject)
        val localNumbers = mainNumbers(detail, local.completedEpisodeIds)
        val localStatus = local.status.takeIf { local.followed }
        val previousIds = mutableState.value.reviews.filter {
            it.subjectId == detail.subject.id && it.providerId == remote.providerId
        }.map { it.id }.toSet()
        previousIds.forEach(reviewSnapshots::remove)
        val review = AnimeTrackingReview(randomUuid(), detail.subject.id, detail.subject.title, remote.providerId,
            localStatus, remote.status, localNumbers, remote.completedNumbers, remote.remoteSubjectId, local.revision)
        val same = localStatus == remote.status && localNumbers == remote.completedNumbers
        if (!same) reviewSnapshots[review.id] = ReviewContext(detail, remote)
        mutableState.update { it.copy(reviews = it.reviews.filterNot { row -> row.id in previousIds } +
            if (same) emptyList() else listOf(review)) }
        notice(if (same) "这个平台的追番状态与正片已看记录一致。秒级续播仍由本机保存。" else "发现差异，请分别选择追番状态和已看集数。")
    }

    fun applyReview(reviewId: String, statusChoice: AnimeSyncChoice, episodesChoice: AnimeSyncChoice, publish: Boolean = false) {
        if (mutableState.value.syncing) return
        val review = mutableState.value.reviews.firstOrNull { it.id == reviewId } ?: return
        val inspected = reviewSnapshots[reviewId] ?: return
        if (statusChoice == AnimeSyncChoice.MERGE) { notice("追番状态请选择本机或远端。"); return }
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                library.awaitReady()
                val before = library.state.value.entries[review.subjectId] ?: AnimeLibraryEntry(inspected.detail.subject)
                check(before.revision == review.localRevision) { "本地记录已变化，请重新核对，尚未应用旧选择。" }
                check(tracking.account(review.providerId)?.ownerId == inspected.remote.ownerId) {
                    "账号已变化，请重新核对。"
                }
                val nextStatus = if (statusChoice == AnimeSyncChoice.REMOTE) review.remoteStatus else review.localStatus
                val numbers = when (episodesChoice) {
                    AnimeSyncChoice.LOCAL -> review.localNumbers
                    AnimeSyncChoice.REMOTE -> review.remoteNumbers
                    AnimeSyncChoice.MERGE -> review.localNumbers + review.remoteNumbers
                }
                val main = inspected.detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
                val selected = numbers.map { number ->
                    main.singleOrNull { it.number == number }?.id
                        ?: error("远端包含本季目录中无法唯一对应的集数，请刷新目录并人工核对。")
                }.toSet()
                val mainIds = main.map { it.id }.toSet()
                // Remote writes are never hidden behind a local apply. Each provider revalidates its revision.
                val receipt = if (publish) tracking.write(inspected.remote, nextStatus, numbers) else null
                if (receipt != null) {
                    check(receipt.ownerId == inspected.remote.ownerId && receipt.remoteSubjectId == inspected.remote.remoteSubjectId) {
                        "远端返回的账号或番剧身份变化，请重新核对。"
                    }
                }
                try {
                    library.edit(inspected.detail.subject, expectedRevision = review.localRevision) {
                        it.copy(followed = nextStatus != null, status = nextStatus ?: it.status,
                            completedEpisodeIds = (it.completedEpisodeIds - mainIds) + selected)
                    }
                } catch (failure: Exception) {
                    if (receipt != null) throw IllegalStateException(
                        "远端已保存；本地应用失败，原本地记录保留。请重新核对差异。", failure)
                    throw failure
                }
                reviewSnapshots.remove(reviewId)
                mutableState.update { it.copy(reviews = it.reviews.filterNot { row -> row.id == reviewId }) }
                notice(if (publish) "已同步选定字段。本机修改可撤销；远端撤回需重新核对后同步。" else "已应用到本机，可以撤销。远端记录未改动。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "尚未完成应用，请重新核对后重试。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    fun undo() = mutate {
        if (!library.undo()) notice("记录已有后续变化，未覆盖新的操作。")
        else {
            val detail = mutableState.value.detail
            if (detail != null) {
                mutableState.update { it.copy(sourceCatalogs = it.sourceCatalogs.filter { catalog ->
                    stillBound(detail.subject.id, catalog.title)
                }, sourceCatalogErrors = emptyMap()) }
                rebuildOptions()
                loadBoundCatalogs(detail, detailGeneration)
            }
            notice("已撤销上一步本机修改。")
        }
    }

    fun setSourceRoute(sourceId: String, mode: DownloadRouteMode) {
        if (mode == DownloadRouteMode.SYSTEM) return
        mutate {
            withContext(Dispatchers.IO) {
                val settings = routing.state.value
                val existing = settings.sourceOverrides[sourceId]
                val endpoint = if (existing?.mode == DownloadRouteMode.PROXY) existing.proxy else settings.proxy
                routing.setSource(sourceId, SourceNetworkSettings(mode, if (mode == DownloadRouteMode.PROXY) endpoint else null))
            }
            notice("后续解析与播放使用新路线；当前播放保持原路线。")
        }
    }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    private fun mainNumbers(detail: AnimeDetail, completed: Set<String>): Set<Double> =
        detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.id in completed }.map { it.number }.toSet()

    private fun subject(id: String): AnimeSubject? = mutableState.value.detail?.subject?.takeIf { it.id == id }
        ?: library.state.value.entries[id]?.subject

    private fun stillBound(subjectId: String, title: AnimeSourceTitle): Boolean =
        library.state.value.bindings.any { it.subjectId == subjectId && it.catalog.sourceId == title.sourceId && it.catalog.id == title.id && it.catalog.url == title.url }

    private fun currentDetail(generation: Long, subjectId: String) =
        generation == detailGeneration && mutableState.value.detail?.subject?.id == subjectId

    private fun notice(message: String) = mutableState.update { it.copy(notice = message) }

    private fun mutate(block: suspend () -> Unit) {
        scope.launch {
            try { library.awaitReady(); block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "操作尚未保存，请重试。") }
        }
    }
}
