package dev.mangaflow.anime

import dev.mangaflow.anime.player.AnimePlayer
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerRequest
import dev.mangaflow.anime.player.AnimePlayerState
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
    private val library = AnimeLibraryStore(context, scope)
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeScreenState())
    val state = mutableState.asStateFlow()
    val sourceHomes: Map<String, String> get() = sources.providers.associate { it.id to it.homeUrl } + mapOf(
        "anime-bangumi" to "https://api.bgm.tv/", "anime-anilist" to "https://graphql.anilist.co/")
    private var playerInstance: AnimePlayer? = null
    val player: AnimePlayer
        get() = playerInstance ?: createAnimePlayer(context, scope).also {
            playerInstance = it
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
    private var resolveJob: Job? = null
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
        var endedSaved: Boolean = false,
    )
    private data class PendingPlayback(
        val detail: AnimeDetail, val episode: AnimeEpisode, val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset, val positionMs: Long, val generation: Long,
    )
    private data class ReviewContext(val detail: AnimeDetail, val remote: AnimeTrackingSnapshot)

    init {
        scope.launch {
            try {
                library.awaitReady()
                library.state.collect { stored ->
                    mutableState.update { it.copy(
                        library = stored.entries.values.sortedByDescending { entry -> entry.updatedAt },
                        canUndo = stored.undo?.let { undo ->
                            stored.entries[undo.subjectId]?.revision == undo.expectedRevision
                        } == true,
                    ) }
                    rebuildOptions()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取。") }
        }
        scope.launch {
            routing.state.collect { settings ->
                val ids = sources.providers.map { it.id } + tracking.providerIds
                mutableState.update { it.copy(sourceRoutes = ids.associateWith { id ->
                    settings.sourceOverrides[id]?.mode ?: settings.defaultMode
                }) }
            }
        }
        scope.launch {
            try { refreshAccounts() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice("追番账号未能读取，番剧浏览仍可使用。请重新连接账号。") }
        }
    }

    fun discover() = browse("")
    fun search(query: String) = browse(query.trim())

    private fun browse(query: String) {
        val generation = ++browseGeneration
        browseJob?.cancel()
        mutableState.update { it.copy(query = query, catalogLoading = true, catalogError = null) }
        browseJob = scope.launch {
            try {
                val result = if (query.isEmpty()) metadata.discover() else metadata.search(query)
                if (generation == browseGeneration) mutableState.update {
                    it.copy(catalog = result.distinctBy(AnimeSubject::id), catalogLoading = false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == browseGeneration) mutableState.update {
                    it.copy(catalogLoading = false, catalogError = failure.message ?: "番剧列表未能加载，请重试。")
                }
            }
        }
    }

    fun open(subject: AnimeSubject) {
        closePlayback()
        val generation = ++detailGeneration
        detailJob?.cancel()
        sourceJob?.cancel()
        previewJob?.cancel()
        ++searchGeneration
        ++previewGeneration
        mutableState.update { it.copy(
            detail = AnimeDetail(subject, emptyList()), detailLoading = true, detailError = null,
            sourceCatalogs = emptyList(), sourceResults = emptyList(), episodeOptions = emptyMap(),
            sourceBindings = library.state.value.bindings.filter { binding -> binding.subjectId == subject.id },
            sourceCatalogErrors = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, selectedEpisodeId = null,
            sourceGroups = emptyMap(), preferredSourceId = null,
        ) }
        detailJob = scope.launch {
            try {
                library.awaitReady()
                val detail = metadata.detail(subject)
                if (!currentDetail(generation, subject.id)) return@launch
                mutableState.update { it.copy(detail = detail, detailLoading = false) }
                rebuildOptions()
                loadBoundCatalogs(detail, generation)
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
        ++detailGeneration
        ++searchGeneration
        ++previewGeneration
        detailJob?.cancel()
        sourceJob?.cancel()
        previewJob?.cancel()
        mutableState.update { it.copy(detail = null, detailLoading = false, detailError = null,
            sourceResults = emptyList(), sourceCatalogs = emptyList(), episodeOptions = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, preferredSourceId = null,
            sourceGroups = emptyMap(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap()) }
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
        val detail = mutableState.value.detail ?: return
        val keyword = query.trim()
        if (keyword.isBlank()) return
        val generation = ++searchGeneration
        val owner = detailGeneration
        sourceJob?.cancel()
        mutableState.update { it.copy(sourceError = null, sourceResults =
            sources.providers.map { source -> AnimeSourceResult(source.id, source.name, loading = true) }) }
        sourceJob = scope.launch {
            coroutineScope {
                sources.providers.forEach { provider ->
                    launch {
                        try {
                            val items = provider.search(keyword)
                            if (generation == searchGeneration && currentDetail(owner, detail.subject.id))
                                updateSourceResult(AnimeSourceResult(provider.id, provider.name, items))
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            if (generation == searchGeneration && currentDetail(owner, detail.subject.id))
                                updateSourceResult(AnimeSourceResult(provider.id, provider.name,
                                    error = failure.message ?: "这个来源暂未响应，请单独重试。"))
                        }
                    }
                }
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
        mutableState.update { it.copy(previewCatalog = null, previewLoading = false) }
    }

    fun bindPreviewSource() {
        val detail = mutableState.value.detail ?: return
        val catalog = mutableState.value.previewCatalog ?: return
        val owner = detailGeneration
        mutate {
            val groups = catalog.episodes.map { it.group }.distinct()
            library.edit(detail.subject, editBindings = { bindings ->
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
                catalogRequests[catalog.title.sourceId] = ++catalogGeneration
                mutableState.update { it.copy(previewCatalog = null, previewLoading = false,
                    sourceCatalogErrors = it.sourceCatalogErrors - catalog.title.sourceId,
                    sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == catalog.title.sourceId } + catalog) }
                rebuildOptions()
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
            sourceBindings = bindings,
            sourceCatalogErrors = it.sourceCatalogErrors.filterKeys { id -> bindings.any { binding -> binding.catalog.sourceId == id } },
            sourceGroups = bindings.mapNotNull { binding ->
                binding.selectedGroup?.let { group -> binding.catalog.sourceId to group }
            }.toMap()) }
    }

    fun play(episodeId: String, option: AnimeEpisodeOption? = null) {
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        val options = mutableState.value.episodeOptions[episodeId].orEmpty()
        val selected = option?.takeIf { offered -> options.any { it == offered && it.confirmed } }
            ?: if (option == null) options.firstOrNull { it.confirmed } else null
        if (selected == null) {
            mutableState.update { it.copy(sourceError = "请先确认本集的来源与集数对应。") }
            return
        }
        flushPlayback()
        playerInstance?.pause()
        val owner = detailGeneration
        val generation = ++resolveGeneration
        resolveJob?.cancel()
        pending = null
        mutableState.update { it.copy(resolving = true, sourceError = null, pendingPlayback = null) }
        resolveJob = scope.launch {
            try {
                val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                    it.title.sourceId == selected.sourceId && it.title.id == selected.catalog.id
                } ?: error("来源目录已变化，请重新选集。")
                val provider = sources.provider(selected.sourceId) ?: error("这个来源尚未安装。")
                val resolved = provider.resolve(catalog, selected.episode).copy(episodeId = episode.id,
                    title = detail.subject.title + " · " + episode.title)
                check(resolved.sourceId == selected.sourceId && resolved.sourceEpisodeId == selected.episode.id) {
                    "来源返回的剧集身份不一致，请重新读取目录。"
                }
                check(resolved.expiresAt == null || resolved.expiresAt > wallTimeMillis()) { "播放地址已过期，请重试解析。" }
                if (generation != resolveGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                val previous = library.state.value.progress[episodeId]
                val position = previous?.takeUnless { it.completed }?.positionMs ?: 0L
                if (previous != null && position > 0L && previous.assetId != resolved.id) {
                    pending = PendingPlayback(detail, episode, selected, resolved, position, generation)
                    mutableState.update { it.copy(resolving = false, pendingPlayback = AnimePlaybackChoice(
                        episode, selected, position, previous.sourceId)) }
                } else startPlayback(PendingPlayback(detail, episode, selected, resolved, position, generation))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id))
                    mutableState.update { it.copy(resolving = false, sourceError = failure.message ?: "播放地址解析失败，请切换来源或重试。") }
            }
        }
    }

    fun confirmPlayback(resumePrevious: Boolean) {
        val choice = pending ?: return
        if (choice.generation != resolveGeneration || mutableState.value.detail?.subject?.id != choice.detail.subject.id) return
        try {
            check(choice.asset.expiresAt == null || choice.asset.expiresAt > wallTimeMillis()) {
                "播放地址已过期，请重新解析。"
            }
            startPlayback(choice.copy(positionMs = if (resumePrevious) choice.positionMs else 0L))
        } catch (failure: Exception) {
            pending = null
            mutableState.update { it.copy(pendingPlayback = null, sourceError = failure.message ?: "播放器未能打开。") }
        }
    }

    fun dismissPlaybackChoice() {
        pending = null
        mutableState.update { it.copy(pendingPlayback = null) }
    }

    private fun startPlayback(choice: PendingPlayback) {
        val nextEpoch = (playbackEpochs[choice.detail.subject.id] ?: 0L) + 1L
        playbackEpochs[choice.detail.subject.id] = nextEpoch
        pending = null
        val engine = player
        engine.open(AnimePlayerRequest(choice.asset, choice.positionMs, true))
        active = ActivePlayback(choice.detail, choice.episode, choice.option, choice.asset,
            nextEpoch, engine.state.value.sessionGeneration)
        lastCheckpointAt = 0L
        lastSavedPosition = -1L
        lastSavedPhase = null
        mutableState.update { it.copy(resolving = false, selectedEpisodeId = choice.episode.id, pendingPlayback = null) }
    }

    fun retryPlayback() {
        val current = active ?: return
        play(current.episode.id, current.option)
    }

    fun closePlayback() {
        flushPlayback()
        ++resolveGeneration
        resolveJob?.cancel()
        pending = null
        active = null
        playerInstance?.stop()
        mutableState.update { it.copy(selectedEpisodeId = null, resolving = false, pendingPlayback = null) }
    }

    fun flushPlayback() {
        val owner = active ?: return
        val snapshot = playerInstance?.state?.value ?: return
        checkpoint(owner, snapshot, force = true)
    }

    private fun observePlayer(engine: AnimePlayer) {
        scope.launch {
            engine.state.collect { snapshot ->
                val owner = active ?: return@collect
                if (snapshot.sessionGeneration != owner.playerGeneration || snapshot.episodeId != owner.episode.id ||
                    snapshot.assetKey != owner.asset.id) return@collect
                checkpoint(owner, snapshot, force = snapshot.phase != lastSavedPhase || snapshot.ended || !snapshot.playWhenReady)
            }
        }
    }

    private fun checkpoint(owner: ActivePlayback, snapshot: AnimePlayerState, force: Boolean) {
        if (snapshot.sessionGeneration != owner.playerGeneration || snapshot.episodeId != owner.episode.id ||
            snapshot.phase != AnimePlayerPhase.READY && snapshot.phase != AnimePlayerPhase.ENDED) return
        val now = wallTimeMillis()
        if (!force && now - lastCheckpointAt < 5_000L) return
        val ended = snapshot.ended && !owner.endedSaved
        if (!ended && snapshot.positionMs == lastSavedPosition && snapshot.phase == lastSavedPhase) return
        lastCheckpointAt = now
        lastSavedPosition = snapshot.positionMs
        lastSavedPhase = snapshot.phase
        val progress = AnimeWatchProgress(owner.episode.id, owner.asset.id, owner.asset.sourceId,
            snapshot.positionMs.coerceAtLeast(0L), snapshot.durationMs.coerceAtLeast(0L), now, snapshot.ended)
        scope.launch {
            try {
                library.checkpoint(owner.detail.subject, progress) { playbackEpochs[owner.detail.subject.id] == owner.epoch }
                if (ended) owner.endedSaved = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (active === owner) {
                    lastCheckpointAt = 0L
                    lastSavedPosition = -1L
                    lastSavedPhase = null
                }
                notice(failure.message ?: "播放进度暂未保存，请重试。")
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
