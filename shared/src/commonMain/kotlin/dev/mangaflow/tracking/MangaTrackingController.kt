package dev.mangaflow.tracking

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-owned ordinary-manga tracker. Only reportProgress(), called from the real reader
 * save callback, can produce automatic writes. Restores, imports and LAN merges do not.
 * Remote progress remains an independent fact; this class never saves page history.
 */
class MangaTrackingController(
    context: PlatformContext,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    aniListClientId: String = "",
) {
    private val repository = MangaTrackingRepository(context)
    private val store = MangaTrackingStore(applicationContext(context))
    private val diskMutex = Mutex()
    private val operationMutex = Mutex()
    private var records = TrackingRecords()
    private var workspaceEpoch = 0L
    private var active = false
    private var pumpJob: Job? = null
    private val mutable = MutableStateFlow(MangaTrackingState(
        aniListClientId = aniListClientId.trim().takeIf { it.matches(Regex("[1-9][0-9]*")) }.orEmpty()))
    val state: StateFlow<MangaTrackingState> = mutable.asStateFlow()

    init {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.read() }
                var credentialProblem = false
                val accounts = withContext(Dispatchers.IO) {
                    MangaTrackingProvider.entries.mapNotNull { provider ->
                        try { repository.account(provider)?.let { provider to it } }
                        catch (_: Exception) { credentialProblem = true; null }
                    }.toMap()
                }
                diskMutex.withLock { records = loaded }
                mutable.update { it.copy(ready = true, accounts = accounts, bindings = loaded.bindings, pending = loaded.pending,
                    error = if (credentialProblem) "部分安全凭据暂时无法读取，原凭据已保留；可解锁设备或重新连接该账号。" else null) }
                startPump()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(error = "漫画同步资料暂时无法读取，原文件和凭据已保留。请检查设备存储或解锁后重新打开。") }
            }
        }
    }

    fun openWork(manga: Manga?, chapters: List<Chapter> = emptyList()) {
        val old = mutable.value
        if (old.manga == manga && old.chapters == chapters) return
        workspaceEpoch++
        val suggested = MangaTrackingProvider.entries.mapNotNull { provider ->
            manga?.externalIds?.entries?.firstOrNull { it.key.equals(provider.id, ignoreCase = true) }
                ?.value?.trim()?.takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }?.let { provider to it }
        }.toMap()
        mutable.update { it.copy(manga = manga, chapters = chapters, numberings = trackingNumberings(chapters),
            suggestedIds = suggested, reviews = emptyMap(), candidates = emptyMap(), preview = null, message = null, error = null) }
    }

    /** This gates foreground retry work, not an OS background service. */
    fun setActive(value: Boolean) {
        active = value
        if (value) startPump() else { pumpJob?.cancel(); pumpJob = null }
    }

    fun authorizationUrl(provider: MangaTrackingProvider): String? = when (provider) {
        MangaTrackingProvider.BANGUMI -> "https://next.bgm.tv/demo/access-token"
        MangaTrackingProvider.ANILIST -> mutable.value.aniListClientId.takeIf(String::isNotBlank)?.let { clientId ->
            "https://anilist.co/api/v2/oauth/authorize".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("client_id", clientId).addQueryParameter("response_type", "token").build().toString()
        }
    }

    fun connect(provider: MangaTrackingProvider, accessToken: String) = action(provider) {
        val account = repository.connect(provider, accessToken)
        mutable.update { it.copy(accounts = it.accounts + (provider to account), reviews = it.reviews - provider,
            candidates = it.candidates - provider, preview = null, message = "已连接 " + provider.label + " · " + account.displayName) }
        startPump()
    }

    fun disconnect(provider: MangaTrackingProvider) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.disconnect(provider) }
                mutable.update { it.copy(accounts = it.accounts - provider, reviews = it.reviews - provider,
                    preview = null, message = "已断开 " + provider.label + "，已确认的条目保留在本机。") }
                commit { current -> current.copy(
                    bindings = current.bindings.map { if (it.provider == provider) it.copy(autoSync = false) else it },
                    pending = current.pending.map { if (it.expected.provider == provider)
                        it.copy(blocked = true, message = "账号已断开；重新连接后先核对云端结果。") else it },
                ) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail("未能完整保存断开状态，请检查设备存储后重试。") }
        }
    }

    fun search(provider: MangaTrackingProvider, query: String) {
        val epoch = workspaceEpoch
        action(provider) {
            val candidates = repository.search(provider, query)
            ensureWorkspace(epoch)
            mutable.update { it.copy(candidates = it.candidates + (provider to candidates), preview = null,
                message = if (candidates.isEmpty()) "没有找到匹配的漫画，可尝试原名或输入原站条目 ID。" else "请选择同一部漫画与对应版本。") }
        }
    }

    fun review(provider: MangaTrackingProvider, remoteId: String? = null) {
        val work = mutable.value.manga ?: return fail("请先打开一部漫画。")
        val epoch = workspaceEpoch
        action(provider) {
            val id = remoteId?.trim()?.takeIf(String::isNotBlank) ?: binding(provider)?.subject?.id
                ?: throw IllegalStateException("请先选择漫画条目。")
            val remote = repository.read(provider, id)
            ensureWorkspace(epoch)
            ensureOwner(provider, remote.ownerId)
            val bound = binding(provider)?.takeIf { it.subject.id == remote.subject.id }
            if (bound == null) {
                mutable.update { it.copy(preview = remote, reviews = it.reviews - provider, message = "请确认这是同一漫画、同一版本，再选择编号方式。") }
            } else {
                val local = localReview(work, bound)
                ensureWorkspace(epoch)
                ensureOwner(provider, remote.ownerId)
                mutable.update { it.copy(preview = null, reviews = it.reviews + (provider to
                    MangaTrackingReview(randomUuid(), work.id, remote, local.first, local.second)),
                    message = "已读取云端；请选择采用云端或回写所选进度。") }
            }
        }
    }

    fun confirmBinding(provider: MangaTrackingProvider, numbering: MangaTrackingNumbering? = null) {
        val preview = mutable.value.preview?.takeIf { it.provider == provider } ?: return
        val work = mutable.value.manga ?: return
        val epoch = workspaceEpoch
        action(provider) {
            ensureWorkspace(epoch)
            ensureOwner(provider, preview.ownerId)
            require(mutable.value.preview == preview) { "条目预览已变化，请重新确认。" }
            require(numbering == null || numbering in mutable.value.numberings) { "该编号方式已不在当前目录，请重新选择。" }
            val candidate = MangaTrackingBinding(work.id, work.title, provider, preview.ownerId, preview.subject,
                numbering, remote = preview)
            val local = localReview(work, candidate)
            ensureWorkspace(epoch)
            val bound = candidate.copy(local = local.first)
            commit { current -> current.copy(bindings = current.bindings.filterNot { it.key == bound.key } + bound,
                pending = current.pending.filterNot { it.bindingKey == bound.key }) }
            mutable.update { it.copy(preview = null, reviews = it.reviews + (provider to
                MangaTrackingReview(randomUuid(), work.id, preview, local.first, local.second)),
                message = "已确认漫画条目。自动同步默认关闭，可先对照话数与卷数。") }
        }
    }

    fun setNumbering(provider: MangaTrackingProvider, numbering: MangaTrackingNumbering?) {
        val bound = binding(provider) ?: return fail("请先确认漫画条目。")
        val epoch = workspaceEpoch
        action(provider) {
            ensureWorkspace(epoch)
            ensureOwner(provider, bound.ownerId)
            require(numbering == null || numbering in mutable.value.numberings) { "请从当前目录选择编号方式。" }
            commit { current -> current.copy(
                bindings = current.bindings.map { if (it.key == bound.key) it.copy(numbering = numbering,
                    autoSync = false, completedNumbers = emptySet()) else it },
                pending = current.pending.filterNot { it.bindingKey == bound.key },
            ) }
            mutable.update { it.copy(reviews = it.reviews - provider, message = "编号方式已更新，请重新对照后开启自动同步。") }
        }
    }

    fun unbind(provider: MangaTrackingProvider) {
        val bound = binding(provider) ?: return
        val epoch = workspaceEpoch
        action(provider) {
            ensureWorkspace(epoch)
            ensureOwner(provider, bound.ownerId)
            commit { current -> current.copy(bindings = current.bindings.filterNot { it.key == bound.key },
                pending = current.pending.filterNot { it.bindingKey == bound.key }) }
            mutable.update { it.copy(reviews = it.reviews - provider, preview = null, message = "已解除本机绑定，云端收藏未修改。") }
        }
    }

    fun applyLocal(
        provider: MangaTrackingProvider,
        desired: MangaTrackingProgress? = null,
        reviewToken: String? = mutable.value.reviews[provider]?.token,
    ) = action(provider) {
        val review = requireReview(provider, reviewToken)
        val bound = requireNotNull(binding(provider))
        val selected = desired ?: review.local
        require(selected.status != null) { "请选择想读、在读或其他收藏状态。" }
        validateTotals(review.remote.subject, selected)
        val pending = MangaTrackingPending(bound.key, randomUuid(), review.remote, selected)
        commit { current -> current.copy(
            bindings = current.bindings.map { if (it.key == bound.key) it.copy(local = selected) else it },
            pending = current.pending.filterNot { it.bindingKey == bound.key } + pending,
        ) }
        performPending(pending) { requireReview(provider, reviewToken); Unit }
    }

    /** Accepts tracker facts only. No ChapterProgress, ReadingProgress or page offsets are invented. */
    fun applyRemote(provider: MangaTrackingProvider, reviewToken: String? = mutable.value.reviews[provider]?.token) = action(provider) {
        val review = requireReview(provider, reviewToken)
        val bound = requireNotNull(binding(provider))
        val fresh = repository.read(provider, review.remote.subject.id)
        if (fresh.revision != review.remote.revision)
            throw TrackingException(TrackingFailure.CONFLICT, "云端在对照后发生变化，请重新读取再采用。")
        requireReview(provider, reviewToken)
        commit { current -> current.copy(
            bindings = current.bindings.map { if (it.key == bound.key)
                it.copy(local = fresh.progress, remote = fresh, completedNumbers = emptySet()) else it },
            pending = current.pending.filterNot { it.bindingKey == bound.key },
        ) }
        mutable.update { it.copy(reviews = it.reviews + (provider to review.copy(token = randomUuid(),
            remote = fresh, local = fresh.progress)), message = "已采用云端的收藏状态与话／卷进度，本机精确页码继续保留。") }
    }

    fun setAutoSync(provider: MangaTrackingProvider, enabled: Boolean) {
        val bound = binding(provider) ?: return fail("请先确认漫画条目。")
        val epoch = workspaceEpoch
        action(provider) {
            ensureWorkspace(epoch)
            val numbering = bound.numbering
            require(!enabled || numbering != null) { "请先确认话数或卷数编号方式。" }
            val fresh = if (enabled) repository.read(provider, bound.subject.id) else bound.remote
            ensureWorkspace(epoch)
            ensureOwner(provider, bound.ownerId)
            require(binding(provider)?.key == bound.key) { "当前漫画已变化，请重新操作。" }
            commit { current -> current.copy(
                bindings = current.bindings.map { if (it.key == bound.key) it.copy(autoSync = enabled,
                    remote = fresh, completedNumbers = emptySet()) else it },
                pending = current.pending.mapNotNull { item ->
                    if (item.bindingKey != bound.key || !item.automatic) item
                    else if (item.submitted) item.copy(blocked = true, message = "自动同步设置已变化，请核对上次请求的云端结果。")
                    else null
                },
            ) }
            mutable.update { it.copy(reviews = it.reviews - provider, message = if (enabled)
                "已开启：从云端已读进度接续，实际读完连续的下一话／卷后写回；跳号需手动对照。" else "已关闭自动同步。") }
            startPump()
        }
    }

    /**
     * The caller must call this only after the user's actual reader save. A repeated
     * last-page callback is idempotent, and a reread never resets a completed status.
     */
    fun reportProgress(manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int, currentChapters: List<Chapter> = emptyList()) {
        if (!mutable.value.ready || pageCount <= 0 || pageIndex != pageCount - 1) return
        val ordinal = trackingOrdinal(chapter) ?: return
        val mapped = trackingChapter(currentChapters, ordinal.numbering, ordinal.number) ?: return
        if (!trackingSameChapter(mapped, chapter)) return
        scope.launch {
            try {
                var changed = false
                commit { current ->
                    var pending = current.pending
                    val bindings = current.bindings.map { bound ->
                        if (bound.mangaId != manga.id || !bound.autoSync || bound.numbering != ordinal.numbering ||
                            mutable.value.accounts[bound.provider]?.ownerId != bound.ownerId ||
                            ordinal.number in bound.completedNumbers) return@map bound
                        val remote = bound.remote ?: return@map bound
                        val baseline = remote.progress.number(ordinal.numbering.unit)
                        // Reading an already accounted chapter cannot reopen a finished/paused work.
                        if (ordinal.number <= baseline) return@map bound
                        val completed = bound.completedNumbers + ordinal.number
                        val local = bound.local.withNumber(ordinal.numbering.unit,
                            maxOf(bound.local.number(ordinal.numbering.unit), ordinal.number))
                            .let { if (it.status == null || it.status == MangaTrackingStatus.PLANNING)
                                it.copy(status = MangaTrackingStatus.READING) else it }
                        val next = bound.copy(local = local, completedNumbers = completed)
                        val oldPending = pending.firstOrNull { it.bindingKey == bound.key }
                        if (oldPending == null || !oldPending.submitted && !oldPending.blocked && oldPending.automatic) {
                            automaticPending(next)?.let { item ->
                                pending = pending.filterNot { it.bindingKey == bound.key } + item
                            }
                        }
                        changed = true
                        next
                    }
                    current.copy(bindings = bindings, pending = pending)
                }
                if (changed) mutable.update { it.copy(reviews = if (it.manga?.id == manga.id) emptyMap() else it.reviews) }
                startPump()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail("本次完读尚未保存到同步队列，请检查存储空间后在同步面板重新对照。") }
        }
    }

    fun retryPending(provider: MangaTrackingProvider? = null) = action(provider) {
        commit { current -> current.copy(pending = current.pending.map { item ->
            if ((provider == null || item.expected.provider == provider) &&
                mutable.value.accounts[item.expected.provider]?.ownerId == item.expected.ownerId)
                item.copy(blocked = !item.automatic && !item.submitted, retryAt = 0, attempts = 0,
                    message = if (!item.automatic && !item.submitted) "请重新对照后确认这次回写。" else null)
            else item
        }) }
        mutable.update { it.copy(message = "正在核对待同步记录；结果不确定的请求只读取确认，不直接重发。") }
        startPump()
    }

    /** A real catalogue row, not a fabricated page position; caller preserves same-chapter local pages. */
    fun continuationTarget(provider: MangaTrackingProvider): Chapter? {
        val review = mutable.value.reviews[provider] ?: return null
        val bound = binding(provider) ?: return null
        if (review.mangaId != bound.mangaId || review.remote.subject.id != bound.subject.id ||
            review.remote.ownerId != bound.ownerId) return null
        val numbering = bound.numbering ?: return null
        val completed = review.remote.progress.number(numbering.unit)
        if (completed == Int.MAX_VALUE) return null
        return trackingChapter(mutable.value.chapters, numbering, completed + 1)
    }

    fun clearFeedback() { mutable.update { it.copy(error = null, message = null) } }

    private fun binding(provider: MangaTrackingProvider): MangaTrackingBinding? {
        val current = mutable.value
        val owner = current.accounts[provider]?.ownerId ?: return null
        return current.bindings.firstOrNull { it.provider == provider && it.ownerId == owner && it.mangaId == current.manga?.id }
    }

    private suspend fun localReview(work: Manga, binding: MangaTrackingBinding): Pair<MangaTrackingProgress, Long> {
        val before = library.chapterHistoryRevision.value
        val history = library.chapterHistoryFor(work)
        require(library.chapterHistoryRevision.value == before) { "本机阅读进度已变化，请重新对照。" }
        val numbering = binding.numbering ?: return binding.local to before
        val completed = trackingCompleted(mutable.value.chapters, history, numbering)
        val count = maxOf(binding.local.number(numbering.unit), completed.maxOrNull() ?: 0)
        val local = binding.local.withNumber(numbering.unit, count).let {
            if (it.status == null && (it.chapters > 0 || it.volumes > 0)) it.copy(status = MangaTrackingStatus.READING) else it
        }
        return local to before
    }

    private fun requireReview(provider: MangaTrackingProvider, token: String?): MangaTrackingReview {
        val review = mutable.value.reviews[provider]
        require(review != null && token != null && review.token == token && review.mangaId == mutable.value.manga?.id) {
            "对照内容已变化，请重新读取后确认。"
        }
        ensureOwner(provider, review.remote.ownerId)
        require(library.chapterHistoryRevision.value == review.localRevision) { "本机阅读进度已变化，请重新对照后确认。" }
        require(binding(provider)?.subject?.id == review.remote.subject.id) { "漫画绑定已变化，请重新对照。" }
        return review
    }

    private fun ensureWorkspace(epoch: Long) { require(epoch == workspaceEpoch) { "当前漫画或目录已变化，请重新读取。" } }
    private fun ensureOwner(provider: MangaTrackingProvider, owner: String) {
        if (mutable.value.accounts[provider]?.ownerId != owner)
            throw TrackingException(TrackingFailure.ACCOUNT, "账号已变化，请重新读取当前账号。")
    }

    private fun action(provider: MangaTrackingProvider?, block: suspend () -> Unit) {
        scope.launch {
            operationMutex.withLock {
                if (!mutable.value.ready) return@withLock fail("漫画同步资料尚未就绪，请稍后重试。")
                mutable.update { it.copy(busy = true, checkingProvider = provider, error = null, message = null) }
                try { block() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { fail(failure.message ?: "本次操作未完成，请重新对照。") }
                finally { mutable.update { it.copy(busy = false, checkingProvider = null) } }
            }
        }
    }

    private suspend fun commit(transform: (TrackingRecords) -> TrackingRecords) {
        diskMutex.withLock {
            val next = transform(records)
            if (next == records) return@withLock
            withContext(Dispatchers.IO + NonCancellable) { store.write(next) }
            records = next
            mutable.update { it.copy(bindings = next.bindings, pending = next.pending) }
        }
    }

    private fun automaticPending(binding: MangaTrackingBinding): MangaTrackingPending? {
        val remote = binding.remote ?: return null
        val numbering = binding.numbering ?: return null
        if (!binding.autoSync) return null
        val baseline = remote.progress.number(numbering.unit)
        var contiguous = baseline
        while (contiguous < Int.MAX_VALUE && contiguous + 1 in binding.completedNumbers) contiguous++
        if (contiguous == baseline) return null
        val desired = remote.progress.withNumber(numbering.unit, contiguous).let {
            if (it.status == null || it.status == MangaTrackingStatus.PLANNING) it.copy(status = MangaTrackingStatus.READING) else it
        }
        if (runCatching { validateTotals(remote.subject, desired) }.isFailure) return null
        return MangaTrackingPending(binding.key, randomUuid(), remote, desired, automatic = true)
    }

    private fun startPump() {
        if (!active || !mutable.value.ready || pumpJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (active) {
                    val next = mutable.value.pending.filter { !it.blocked && (it.automatic || it.submitted) &&
                        mutable.value.accounts[it.expected.provider]?.ownerId == it.expected.ownerId }
                        .minByOrNull { it.retryAt } ?: break
                    val wait = next.retryAt - wallTimeMillis()
                    if (wait > 0) { delay(wait.coerceAtMost(60_000)); continue }
                    operationMutex.withLock {
                        if (!active) return@withLock
                        val current = mutable.value.pending.firstOrNull { it.token == next.token && !it.blocked }
                        if (current != null) {
                            mutable.update { it.copy(syncing = true) }
                            try { performPending(current) }
                            finally { mutable.update { it.copy(syncing = false) } }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail("同步队列暂时无法保存，已暂停处理；请检查存储空间后重新核对。") }
            finally { if (pumpJob === coroutineContext[Job]) pumpJob = null }
        }
        pumpJob = job
        job.start()
    }

    private suspend fun performPending(item: MangaTrackingPending, manualGuard: (() -> Unit)? = null) {
        try {
            ensureOwner(item.expected.provider, item.expected.ownerId)
            val result = if (item.submitted) {
                val fresh = repository.read(item.expected.provider, item.expected.subject.id)
                if (fresh.ownerId != item.expected.ownerId || fresh.progress != item.desired)
                    throw TrackingException(TrackingFailure.UNCERTAIN,
                        "已重新读取，但云端尚未对应这次所选进度。请打开漫画重新对照；不会直接重发。")
                fresh
            } else repository.write(item.expected, item.desired) {
                manualGuard?.invoke()
                ensureOwner(item.expected.provider, item.expected.ownerId)
                commit { current ->
                    val live = current.pending.firstOrNull { it.token == item.token }
                    require(live != null && !live.blocked) { "待同步记录已变化，已停止这次提交。" }
                    val bound = current.bindings.firstOrNull { it.key == item.bindingKey }
                    require(bound != null && bound.subject.id == item.expected.subject.id &&
                        (!item.automatic || active && bound.autoSync)) { "自动同步或漫画绑定已变化，已停止这次提交。" }
                    current.copy(pending = current.pending.map {
                        if (it.token == item.token) it.copy(submitted = true) else it
                    })
                }
            }
            commit { current ->
                if (current.pending.none { it.token == item.token }) return@commit current
                val bindings = current.bindings.map { bound ->
                    if (bound.key == item.bindingKey) bound.copy(remote = result,
                        completedNumbers = bound.completedNumbers.filterTo(linkedSetOf()) {
                            it > result.progress.number(bound.numbering?.unit ?: MangaTrackingUnit.CHAPTER)
                        }) else bound
                }
                val next = bindings.firstOrNull { it.key == item.bindingKey }?.let(::automaticPending)
                current.copy(bindings = bindings,
                    pending = current.pending.filterNot { it.token == item.token } + listOfNotNull(next))
            }
            mutable.update { current ->
                val review = current.reviews[item.expected.provider]
                current.copy(message = "已核对 " + item.expected.provider.label + " 的云端进度。", error = null,
                    reviews = if (review?.remote?.subject?.id == result.subject.id && review.remote.ownerId == result.ownerId)
                        current.reviews + (item.expected.provider to review.copy(token = randomUuid(), remote = result,
                            local = current.bindings.firstOrNull { it.key == item.bindingKey }?.local ?: item.desired,
                            localRevision = library.chapterHistoryRevision.value))
                    else current.reviews)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            val reason = (failure as? TrackingException)?.reason
            val live = mutable.value.pending.firstOrNull { it.token == item.token } ?: return
            val submitted = live.submitted
            val attempts = item.attempts + 1
            val retryable = reason == TrackingFailure.NETWORK && item.automatic && !submitted && attempts < 5
            val message = if (submitted) "上次请求结果尚未确认。重新核对只读取云端，不会直接重发。"
                else failure.message ?: "同步未完成，请重新对照。"
            commit { current -> current.copy(pending = current.pending.map {
                if (it.token == item.token) it.copy(blocked = !retryable, attempts = attempts, message = message,
                    retryAt = if (retryable) maxOf((failure as? TrackingException)?.retryAt ?: 0L,
                        wallTimeMillis() + minOf(300_000L, 5_000L * (1L shl attempts.coerceAtMost(6)))) else 0L)
                else it
            }) }
            fail(message)
        }
    }

    private fun fail(message: String) { mutable.update { it.copy(error = message, message = null) } }
}
