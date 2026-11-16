package dev.mangaflow.reader

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.Image
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.approximateReaderPage
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.foregroundRequest
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.data.plugins.invalidateScriptImage
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.data.sources.materializeJmPage
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.PrefetchImageInactivityException
import dev.mangaflow.experience.ExperienceAdmissionDeferred
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.sha256
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.ReaderPageAlignment
import dev.mangaflow.storage.ReaderPageRepairStore
import dev.mangaflow.storage.StorageCacheStore
import dev.mangaflow.storage.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Hash-only complete native manifest identity; no URL, token or cookie is persisted in alignments. */
fun readerManifestFingerprint(pages: List<Page>): String = repairDigest(buildString {
    fun field(value: String) { append(value.length).append(':').append(value) }
    field("reader-manifest-v2")
    field(pages.size.toString())
    pages.forEach { page ->
        field(page.index.toString()); field(page.url); field(page.width.toString()); field(page.height.toString())
        field(page.sourceRevision); field(page.sourceSessionEpoch); field(page.sourceImageToken); field(page.sourceRouteId)
        field(page.transform); field(page.headerSecretRef); field(page.headerSecretBinding)
        field(page.headers.size.toString())
        page.headers.entries.sortedBy { it.key.lowercase() }.forEach { (key, value) -> field(key.lowercase()); field(value) }
    }
})

private fun repairDigest(value: String): String = sha256(value.encodeToByteArray())
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal enum class ReaderRepairPhase { IDLE, DIRECTORY, IMAGE, READY, SAVING, ERROR, DEFERRED }
internal data class ReaderRepairPreview(val page: Page, val image: Image)
internal data class ReaderPageReplacement(
    val alignment: ReaderPageAlignment,
    val variant: ChapterVariant,
    val page: Page,
    val headers: Map<String, String>,
    val preparedPage: Page,
)
internal data class ReaderRepairSelection(
    val primaryIndex: Int,
    val variant: ChapterVariant,
    val phase: ReaderRepairPhase = ReaderRepairPhase.DIRECTORY,
    val pages: List<Page> = emptyList(),
    val targetIndex: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val preview: ReaderRepairPreview? = null,
    val reason: String? = null,
    val expectedRevision: Long? = null,
    val environment: String = "",
)
internal data class ReaderRepairState(
    val replacements: Map<Int, ReaderPageReplacement> = emptyMap(),
    val primaryReferences: Map<Int, Page> = emptyMap(),
    val selection: ReaderRepairSelection? = null,
    val busy: Boolean = false,
    val reason: String? = null,
    val switchReady: ReaderPageReplacement? = null,
)

internal data class ReaderRepairRenderContext(
    val state: ReaderRepairState,
    val controller: ReaderPageRepairController,
    val onRescue: (Int, Boolean) -> Unit,
)
internal val LocalReaderPageRepair = staticCompositionLocalOf<ReaderRepairRenderContext?> { null }

/**
 * This owner has exactly the live reading session's lifetime. The application image loader and
 * repository still own bytes and manifests; this owner only prepares and applies explicit relations.
 */
internal class ReaderPageRepairController(
    private val context: PlatformContext,
    private val workId: String,
    private var chapter: Chapter,
    private val primaryPages: List<Page>,
    private val primaryHeaders: Map<String, String>,
    private val repository: MangaRepository,
    private val downloads: DownloadStore,
    private val scope: CoroutineScope,
    private var viewportWidth: Int,
    private var viewportHeight: Int,
    private val isCurrent: () -> Boolean,
) {
    private val policy = ExperiencePolicyStore.get(context)
    private val store = ReaderPageRepairStore(context)
    private val primaryManifest = readerManifestFingerprint(primaryPages)
    private val primaryOwner = chapterSourceMangaId(workId, chapter)
    private val primaryEnvironment = environment(chapter.sourceId, primaryOwner, chapter.id, primaryHeaders).orEmpty()
    private val failureWindow = ReaderSourceFailureWindow()
    private val attempted = mutableSetOf<Int>()
    private val leases = mutableMapOf<Int, () -> Unit>()
    private val mutableState = MutableStateFlow(ReaderRepairState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var lastSwitchAt: Long? = null
    private var closed = false
    private var selectionLease: (() -> Unit)? = null

    fun updatePresentation(updated: Chapter, width: Int, height: Int) {
        if (closed || updated.id != chapter.id || chapterSourceMangaId(workId, updated) != primaryOwner) return
        chapter = updated; viewportWidth = width; viewportHeight = height
    }

    private fun valid() = !closed && isCurrent() && primaryEnvironment.isNotEmpty() &&
        primaryEnvironment == environment(chapter.sourceId, primaryOwner, chapter.id, repository.imageHeaders(primaryOwner, chapter.id))

    private fun environment(source: String, owner: String, chapterId: String, headers: Map<String, String>): String? = runCatching { repairDigest(buildString {
        append(source).append('\u0000').append(owner).append('\u0000').append(chapterId).append('\u0000')
        append(scriptSourceImageEpoch(context, source)).append('\u0000')
        append(DownloadRoutingStore.get(context).sourceSnapshot(source).identity()).append('\u0000')
        headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
            append(name.length).append(':').append(name.lowercase()).append(value.length).append(':').append(value)
        }
    }) }.getOrNull()

    fun close() {
        closed = true; revision++; job?.cancel(); leases.values.forEach { it() }; leases.clear()
        selectionLease?.invoke(); selectionLease = null
    }
    fun dismiss() {
        revision++; job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(selection = null, busy = false)
    }
    fun selectionClosed() {
        if (mutableState.value.selection == null) { selectionLease?.invoke(); selectionLease = null }
    }
    fun pause() {
        if (closed) return
        revision++; job?.cancel(); job = null; attempted.clear()
        mutableState.value = mutableState.value.copy(busy = false, switchReady = null,
            selection = mutableState.value.selection?.let { selection ->
                selection.copy(phase = if (selection.preview != null) ReaderRepairPhase.READY else ReaderRepairPhase.DEFERRED,
                    reason = if (selection.preview != null) null else "准备已暂停，可重试")
            })
    }
    fun suspendAutomatic() {
        if (closed || mutableState.value.selection != null) return
        revision++; job?.cancel(); job = null; attempted.clear()
        mutableState.value = mutableState.value.copy(busy = false, switchReady = null)
    }
    fun succeeded(index: Int) {
        failureWindow.succeeded(index); attempted.remove(index)
        if (mutableState.value.switchReady?.alignment?.primaryIndex == index)
            mutableState.value = mutableState.value.copy(switchReady = null)
    }
    fun failed(index: Int) { if (index in primaryPages.indices) failureWindow.failed(index) }
    fun primaryResource(index: Int, native: Page, resource: Page) {
        if (valid() && primaryPages.getOrNull(index) == native && mutableState.value.primaryReferences[index] != resource)
            mutableState.value = mutableState.value.copy(primaryReferences = mutableState.value.primaryReferences + (index to resource))
    }

    /** Recheck account, route and manifest authority while overlays are retained. */
    fun inspectOwnership() {
        if (closed) return
        if (!valid()) {
            revision++; job?.cancel(); leases.values.forEach { it() }; leases.clear()
            selectionLease?.invoke(); selectionLease = null
            mutableState.value = ReaderRepairState(reason = "来源或目录已变化，请重新打开这一章")
            return
        }
        val stale = mutableState.value.replacements.filterValues { replacement ->
            val owner = SourceKeys.manga(replacement.variant.sourceId, replacement.variant.mangaId)
            chapter.variants.none { it.storageId == replacement.variant.storageId } ||
                replacement.alignment.targetEnvironment != environment(replacement.variant.sourceId, owner,
                replacement.variant.storageId, repository.imageHeaders(owner, replacement.variant.storageId)) ||
                repository.cachedPages(owner, replacement.variant.storageId)?.let(::readerManifestFingerprint)
                    ?.let { it != replacement.alignment.targetManifest } == true
        }.keys
        if (stale.isNotEmpty()) {
            stale.forEach { leases.remove(it)?.invoke(); attempted.remove(it) }
            mutableState.value = mutableState.value.copy(replacements = mutableState.value.replacements - stale,
                switchReady = null, reason = "备用版本已更新，已恢复原页，请重新对齐")
        }
    }

    fun observeTransfer(replacement: ReaderPageReplacement): Flow<DownloadProgress?> =
        downloads.observePageTransfer(workId, replacement.variant.storageId, replacement.page.index, expectedPage = replacement.page)

    suspend fun resolveReplacement(replacement: ReaderPageReplacement, retry: Boolean, onBytes: ((Long) -> Unit)?): Page {
        check(valid()) { "阅读版本已变化，请重新打开这一章。" }
        val variant = replacement.variant
        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
        check(replacement.alignment.targetEnvironment == environment(variant.sourceId, owner, variant.storageId,
            repository.imageHeaders(owner, variant.storageId))) { "备用来源已变化，请重新对齐。" }
        if (retry) invalidateScriptImage(owner, variant.storageId, replacement.page.sourceImageToken)
        val token = currentCoroutineContext()[ReaderPreparationWorkContext]?.work
        val local = if (retry) downloads.retryReaderPage(workId, variant.storageId, replacement.page)
            else downloads.resolveReaderPage(workId, variant.storageId, replacement.page)
        if (local.url.startsWith("file:")) return local
        val script = resolveScriptPage(context, owner, variant.storageId, local, work = token)
        val materialized = materializeScriptPage(context, owner, variant.storageId, script, onBytes = onBytes, work = token, retry = retry)
        return if (variant.sourceId == "jmcomic") {
            if (token == null) policy.withImageAdmission(true, variant.sourceId) {
                materializeJmPage(context, owner, variant.storageId, materialized, onBytes = onBytes, retry = retry) ?: materialized
            } else token.during(PrefetchStage.DOWNLOADING) {
                policy.withImageAdmission(true, variant.sourceId, onWaiting = { token.admission(PrefetchStage.DOWNLOADING, it) }) {
                    materializeJmPage(context, owner, variant.storageId, materialized,
                        onBytes = { count -> onBytes?.invoke(count); token.onBytes(count) }, retry = retry) ?: materialized
                }
            }
        } else materialized
    }

    /** Automatic work is allowed only for an already confirmed exact relation. */
    fun recover(index: Int) {
        if (!valid() || index !in primaryPages.indices || !failureWindow.isFailed(index) ||
            mutableState.value.selection != null || job?.isActive == true) return
        val settings = policy.state.value.recovery
        if (settings.sourceLocked || !settings.autoRepair && !failureWindow.shouldSwitch(index, settings)) return
        val existing = mutableState.value.replacements[index]
        if (existing != null) { offerSwitch(index, existing); return }
        if (!attempted.add(index)) return
        val generation = ++revision
        job = scope.launch {
            mutableState.value = mutableState.value.copy(busy = true, reason = null)
            try {
                val saved = withContext(Dispatchers.IO) { store.read(workId, chapter.id, index) } ?: return@launch
                if (saved.workId != workId || saved.primaryChapterId != chapter.id || saved.primaryIndex != index ||
                    saved.primaryManifest != primaryManifest || saved.primaryEnvironment != primaryEnvironment ||
                    saved.logicalChapterId != chapter.logicalId.ifBlank { chapter.id }) {
                    mutableState.value = mutableState.value.copy(reason = "原版本已变化，请重新对齐这一页")
                    return@launch
                }
                val variant = chapter.variants.firstOrNull { it.storageId == saved.targetVariantId } ?: return@launch
                val replacement = automaticRetry(generation) { retryImage ->
                    val pages = manifest(variant)
                    val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
                    val headers = repository.imageHeaders(owner, variant.storageId).toMap()
                    if (readerManifestFingerprint(pages) != saved.targetManifest ||
                        environment(variant.sourceId, owner, variant.storageId, headers) != saved.targetEnvironment) {
                        mutableState.value = mutableState.value.copy(reason = "备用版本已变化，请重新对齐这一页")
                        return@automaticRetry null
                    }
                    val page = pages.getOrNull(saved.targetIndex) ?: return@automaticRetry null
                    val prepared = prepare(variant, page, headers, retryImage)
                    if (repository.cachedPages(owner, variant.storageId)?.let(::readerManifestFingerprint)
                            ?.let { it != saved.targetManifest } == true) {
                        mutableState.value = mutableState.value.copy(reason = "备用目录已更新，请重新对齐这一页")
                        return@automaticRetry null
                    }
                    ReaderPageReplacement(saved, variant, page, headers, prepared.page)
                } ?: return@launch
                if (generation != revision || !valid() || policy.state.value.recovery.sourceLocked) return@launch
                if (policy.state.value.recovery.autoRepair) applyReplacement(index, replacement)
                offerSwitch(index, replacement)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == revision) mutableState.value = mutableState.value.copy(reason = "补页超时，可手动重试")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) {
                if (generation == revision) mutableState.value = mutableState.value.copy(reason =
                    if (isReaderPrefetchFailure(failure)) "已对齐的备用页暂不可用" else "资源暂缓，可手动准备备用页")
            } finally {
                if (generation == revision) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private suspend fun <T> automaticRetry(generation: Long, block: suspend (Boolean) -> T): T {
        var retries = 0L
        var retryImage = false
        while (true) {
            try { return block(retryImage) }
            catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (retries >= policy.state.value.recovery.standbyRetryCount.toLong()) throw timeout
                retryImage = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (retries >= policy.state.value.recovery.standbyRetryCount.toLong() ||
                    !isReaderPrefetchFailure(failure) && failure !is PrefetchImageInactivityException &&
                    failure !is ExperienceAdmissionDeferred) throw failure
                retryImage = isReaderPrefetchFailure(failure)
            }
            if (generation != revision || !valid() || policy.state.value.recovery.sourceLocked)
                throw CancellationException("单页补图意图已结束")
            retries++
            mutableState.value = mutableState.value.copy(reason = "已对齐备用页等待重试 · $retries")
            delay(policy.state.value.recovery.standbyRetryDelaySeconds.coerceAtLeast(0).toLong() * 1000L)
        }
    }

    fun select(index: Int, variant: ChapterVariant, forceRefresh: Boolean = false, preferredTarget: Int? = null) {
        if (!valid() || index !in primaryPages.indices || chapter.variants.none { it.storageId == variant.storageId }) return
        val generation = ++revision
        job?.cancel()
        selectionLease?.invoke()
        selectionLease = StorageCacheStore.get(context).protectReading(workId, variant.storageId,
            SourceKeys.manga(variant.sourceId, variant.mangaId))
        mutableState.value = mutableState.value.copy(selection = ReaderRepairSelection(index, variant), busy = false, reason = null)
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.read(workId, chapter.id, index) }
                val pages = manifest(variant, forceRefresh)
                require(pages.isNotEmpty()) { "这一版本没有返回图片" }
                val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
                val headers = repository.imageHeaders(owner, variant.storageId).toMap()
                val target = preferredTarget?.coerceIn(pages.indices) ?: saved?.takeIf {
                    it.targetVariantId == variant.storageId && it.targetManifest == readerManifestFingerprint(pages)
                }?.targetIndex?.coerceIn(pages.indices) ?: approximateReaderPage(index, primaryPages.size, pages.size)
                if (generation != revision || !valid()) return@launch
                mutableState.value = mutableState.value.copy(selection = ReaderRepairSelection(index, variant,
                    ReaderRepairPhase.IMAGE, pages, target, headers, expectedRevision = saved?.revision,
                    environment = requireNotNull(environment(variant.sourceId, owner, variant.storageId, headers)) { "线路配置不可用" }))
                loadSelection(generation, retryImage = forceRefresh)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive(); selectionFailure(generation, "备用目录准备超时", false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { selectionFailure(generation, readerPrefetchErrorLabel(failure), !isReaderPrefetchFailure(failure)) }
        }
    }

    fun retrySelection() {
        val selection = mutableState.value.selection ?: return
        select(selection.primaryIndex, selection.variant, forceRefresh = true,
            preferredTarget = selection.targetIndex.takeIf { selection.pages.isNotEmpty() })
    }

    fun moveTo(index: Int) {
        val selection = mutableState.value.selection ?: return
        if (selection.pages.isEmpty() || !valid()) return
        val target = index.coerceIn(selection.pages.indices)
        val generation = ++revision
        job?.cancel()
        mutableState.value = mutableState.value.copy(selection = selection.copy(targetIndex = target,
            preview = null, phase = ReaderRepairPhase.IMAGE, reason = null))
        job = scope.launch { loadSelection(generation, retryImage = target == selection.targetIndex && selection.phase == ReaderRepairPhase.ERROR) }
    }

    private suspend fun loadSelection(generation: Long, retryImage: Boolean = false) {
        val selection = mutableState.value.selection ?: return
        try {
            val prepared = prepare(selection.variant, selection.pages[selection.targetIndex], selection.headers, retryImage)
            if (generation == revision && valid()) mutableState.value = mutableState.value.copy(
                selection = selection.copy(preview = prepared, phase = ReaderRepairPhase.READY))
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive(); selectionFailure(generation, "备用页准备超时", false)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Throwable) { selectionFailure(generation, readerPrefetchErrorLabel(failure), !isReaderPrefetchFailure(failure)) }
    }

    private fun selectionFailure(generation: Long, reason: String, deferred: Boolean) {
        if (generation != revision || !valid()) return
        mutableState.value = mutableState.value.copy(selection = mutableState.value.selection?.copy(
            phase = if (deferred) ReaderRepairPhase.DEFERRED else ReaderRepairPhase.ERROR, reason = reason))
    }

    /** The user confirms only this artwork pair. Neighbouring page numbers are never inferred. */
    fun confirm() {
        val selection = mutableState.value.selection ?: return
        val prepared = selection.preview ?: return
        if (selection.phase != ReaderRepairPhase.READY || !valid()) return
        val generation = ++revision
        job?.cancel()
        mutableState.value = mutableState.value.copy(selection = selection.copy(phase = ReaderRepairPhase.SAVING))
        job = scope.launch {
            try {
                val owner = SourceKeys.manga(selection.variant.sourceId, selection.variant.mangaId)
                check(chapter.variants.any { it.storageId == selection.variant.storageId }) { "备用章节已变化，请重新选择。" }
                check(selection.environment == environment(selection.variant.sourceId, owner, selection.variant.storageId,
                    repository.imageHeaders(owner, selection.variant.storageId))) { "来源状态已变化，请重新选择。" }
                val current = repository.cachedPages(owner, selection.variant.storageId) ?: selection.pages
                check(readerManifestFingerprint(current) == readerManifestFingerprint(selection.pages)) { "备用目录已更新，请重新对齐。" }
                val saved = withContext(Dispatchers.IO) { store.save(ReaderPageAlignment(workId,
                    chapter.logicalId.ifBlank { chapter.id }, chapter.id, primaryManifest, primaryEnvironment,
                    selection.primaryIndex, selection.variant.storageId, readerManifestFingerprint(selection.pages),
                    selection.environment, selection.targetIndex), selection.expectedRevision) }
                if (generation != revision || !valid()) return@launch
                applyReplacement(selection.primaryIndex, ReaderPageReplacement(saved, selection.variant,
                    selection.pages[selection.targetIndex], selection.headers, prepared.page))
                selectionLease?.invoke(); selectionLease = null
                mutableState.value = mutableState.value.copy(selection = null, reason = null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { selectionFailure(generation, failure.message ?: "对齐未能保存", false) }
        }
    }

    fun undo(index: Int) {
        val replacement = mutableState.value.replacements[index] ?: return
        val generation = ++revision
        job?.cancel()
        job = scope.launch {
            try {
                check(withContext(Dispatchers.IO) { store.remove(replacement.alignment) }) { "对齐已被更新，请重新打开。" }
                if (generation != revision || !valid()) return@launch
                leases.remove(index)?.invoke()
                mutableState.value = mutableState.value.copy(replacements = mutableState.value.replacements - index,
                    selection = null, switchReady = null, reason = null)
                attempted.remove(index)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (generation == revision) mutableState.value = mutableState.value.copy(reason = failure.message) }
        }
    }

    fun consumeSwitch(index: Int): ReaderPageReplacement? {
        val ready = mutableState.value.switchReady ?: return null
        val config = policy.state.value.recovery
        if (ready.alignment.primaryIndex != index) return null
        val owner = SourceKeys.manga(ready.variant.sourceId, ready.variant.mangaId)
        val validTarget = valid() && failureWindow.shouldSwitch(index, config) &&
            chapter.variants.any { it.storageId == ready.variant.storageId } &&
            ready.alignment.targetEnvironment == environment(ready.variant.sourceId, owner, ready.variant.storageId,
                repository.imageHeaders(owner, ready.variant.storageId)) &&
            repository.cachedPages(owner, ready.variant.storageId)?.let(::readerManifestFingerprint)
                ?.let { it == ready.alignment.targetManifest } != false
        mutableState.value = mutableState.value.copy(switchReady = null,
            reason = if (validTarget) mutableState.value.reason else "备用版本或线路已变化，请重新对齐")
        if (!validTarget) return null
        lastSwitchAt = monotonicTimeMillis()
        return ready
    }
    private fun offerSwitch(index: Int, replacement: ReaderPageReplacement) {
        val config = policy.state.value.recovery
        if (!failureWindow.shouldSwitch(index, config)) return
        if (lastSwitchAt?.let { monotonicTimeMillis() - it < config.switchCooldownSeconds.coerceAtLeast(0).toLong() * 1000L } == true) return
        mutableState.value = mutableState.value.copy(switchReady = replacement)
    }

    private fun applyReplacement(index: Int, replacement: ReaderPageReplacement) {
        leases.remove(index)?.invoke()
        leases[index] = StorageCacheStore.get(context).protectReading(workId, replacement.variant.storageId,
            SourceKeys.manga(replacement.variant.sourceId, replacement.variant.mangaId))
        mutableState.value = mutableState.value.copy(replacements = mutableState.value.replacements + (index to replacement), reason = null)
    }

    private suspend fun manifest(variant: ChapterVariant, forceRefresh: Boolean = false): List<Page> = withTimeout(
        policy.state.value.recovery.manifestTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L) {
        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
        check(chapter.variants.any { it.storageId == variant.storageId }) { "备用章节已变化，请重新选择。" }
        (if (forceRefresh) null else repository.cachedPages(owner, variant.storageId)) ?: policy.withResolveAdmission({ true }) {
            policy.withImageAdmission(reader = true, source = variant.sourceId) {
                foregroundRequest("准备单页对齐") { repository.pages(owner, variant.storageId, forceRefresh = forceRefresh) }
            }
        }
    }

    private suspend fun prepare(variant: ChapterVariant, page: Page, headers: Map<String, String>, retry: Boolean = false): ReaderRepairPreview {
        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
        val expected = environment(variant.sourceId, owner, variant.storageId, headers)
        var image: Image? = null
        val prepared = prepareReaderSourcePage(context, workId, variant, page, headers, downloads,
            viewportWidth, viewportHeight, retry = retry, onPreview = { image = it })
        currentCoroutineContext().ensureActive()
        check(valid() && chapter.variants.any { it.storageId == variant.storageId } &&
            expected == environment(variant.sourceId, owner, variant.storageId,
            repository.imageHeaders(owner, variant.storageId))) { "来源、线路或阅读位置已变化。" }
        return ReaderRepairPreview(prepared, requireNotNull(image) { "备用图片尚不可读" })
    }
}
