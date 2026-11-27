package dev.mangaflow.data.plugins

import dev.mangaflow.net.identity

import dev.mangaflow.data.ChapterDownloadTransport
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sources.nativeJmImageEpoch
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.PrefetchResourceOwner
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.PrefetchPromoted
import dev.mangaflow.experience.PrefetchCapacityExceeded
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.hmacSha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.storage.storageImageBounds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScriptImageEvent(val sourceId: String, val stage: String, val success: Boolean, val at: Long)
internal class ScriptImageRefreshRequired(message: String) : Exception(message)
private fun imageState(allowed: Boolean, message: String) { if (!allowed) throw ScriptImageRefreshRequired(message) }
private val imageEvents = MutableSharedFlow<ScriptImageEvent>(extraBufferCapacity = 64)
val scriptImageEvents = imageEvents.asSharedFlow()

private data class ImageRequestKey(val sourceId: String, val owner: String, val revision: String,
    val epoch: String, val token: String, val route: String)
private data class ResolvedImage(val at: Long, val page: Page)

private fun resolutionKey(owner: String, bundle: ScriptSourcePackage, epoch: String, token: String,
    route: DownloadRouteSnapshot?) =
    ImageRequestKey(bundle.sourceId, owner, bundle.digest, epoch, token, route?.identity() ?: "reader")

private fun cachedResolution(key: ImageRequestKey): ResolvedImage? = synchronized(imageStateLock) {
    resolvedImages[key]?.takeIf { wallTimeMillis() - it.at in 0L until 30_000L }
}

private fun ResolvedImage.forPage(original: Page) =
    page.copy(index = original.index, width = original.width, height = original.height)

private val imageStateLock = SynchronizedObject()
private val resolvedImages = linkedMapOf<ImageRequestKey, ResolvedImage>()
private val failedImageRetries = linkedSetOf<ImageRequestKey>()
private val imageGates = Array(16) { Mutex() }
private val decodeGates = Array(64) { Mutex() }
private val savedHeaderRefs = linkedSetOf<String>()
private val headerSecrets = linkedMapOf<String, String>()
private val sourceEpochOverrides = mutableMapOf<String, String>()
// Process-local, non-secret cache/session fence. Readers need no imageStateLock.
private val mutableSourceImageRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
val sourceImageRevisions = mutableSourceImageRevisions.asStateFlow()
internal fun notifySourceImageSessionChanged(sourceId: String) {
    mutableSourceImageRevisions.update { it + (sourceId to ((it[sourceId] ?: 0L) + 1L)) }
}

private val imageSessionJobs = mutableMapOf<String, MutableSet<Job>>()

fun scriptSourceImageEpoch(context: PlatformContext, sourceId: String): String {
    if (sourceId == "jmcomic") return nativeJmImageEpoch(context)
    if (!sourceId.startsWith("js_")) return "initial"
    return synchronized(imageStateLock) { sourceEpochOverrides[sourceId] }
        ?: context.getSharedPreferences("mangaflow_script_image_sessions").getString("epoch_$sourceId", "initial").orEmpty()
}

/** A late script callback must not restore cookies or settings after its session was replaced. */
internal fun <T> withScriptSourceImageEpoch(context: PlatformContext, sourceId: String,
    expected: String, replaceAfter: Boolean = false, operation: () -> T): T {
    val interrupted = mutableListOf<Job>()
    return try {
        synchronized(imageStateLock) {
            val current = sourceEpochOverrides[sourceId]
                ?: context.getSharedPreferences("mangaflow_script_image_sessions")
                    .getString("epoch_$sourceId", "initial").orEmpty()
            if (current != expected) throw CancellationException("来源、账号或设置已变化。")
            operation().also { if (replaceAfter) replaceScriptImageSessionLocked(context, sourceId, interrupted) }
        }
    } finally { interrupted.forEach { it.cancel(CancellationException("来源图片会话已变化。")) } }
}

/** Caller owns imageStateLock: committing credentials and advancing their fence are indivisible. */
private fun replaceScriptImageSessionLocked(context: PlatformContext, sourceId: String, interrupted: MutableList<Job>) {
    val epoch = randomUuid()
    sourceEpochOverrides[sourceId] = epoch
    try {
        resolvedImages.entries.removeAll { it.key.sourceId == sourceId }
        failedImageRetries.removeAll { it.sourceId == sourceId }
        headerSecrets.entries.removeAll { JSONObject(it.value).optString("sourceId") == sourceId }
        check(context.getSharedPreferences("mangaflow_script_image_sessions").edit()
            .putString("epoch_$sourceId", epoch).commit()) { "来源图片会话状态未能保存，请检查存储空间。" }
    } finally {
        // The in-memory fence changed even if persistence fails. Never leave manifests
        // under the former revision reusable; the original storage error still propagates.
        interrupted += imageSessionJobs.remove(sourceId).orEmpty()
        notifySourceImageSessionChanged(sourceId)
    }
}

/** Account/settings changes invalidate late image resolutions and protected headers across restarts. */
fun invalidateScriptSourceImages(context: PlatformContext, sourceId: String) {
    if (!sourceId.startsWith("js_")) return
    val interrupted = mutableListOf<Job>()
    try { synchronized(imageStateLock) { replaceScriptImageSessionLocked(context, sourceId, interrupted) } }
    finally { interrupted.forEach { it.cancel(CancellationException("来源图片会话已变化。")) } }
}

/** Package publication and its session fence must be observed as one source change. */
internal fun <T> withScriptSourcePackageChanges(context: PlatformContext, sourceIds: Set<String>, operation: () -> T): T {
    if (sourceIds.isEmpty()) return operation()
    val interrupted = mutableListOf<Job>()
    return try {
        synchronized(imageStateLock) {
            operation().also { sourceIds.forEach { replaceScriptImageSessionLocked(context, it, interrupted) } }
        }
    } finally { interrupted.forEach { it.cancel(CancellationException("来源脚本已变化。")) } }
}

private suspend fun <T> withImageSession(context: PlatformContext, sourceId: String, epoch: String,
    operation: suspend () -> T): T = try {
    coroutineScope {
        val owned = requireNotNull(currentCoroutineContext()[Job])
        synchronized(imageStateLock) { imageSessionJobs.getOrPut(sourceId) { mutableSetOf() }.add(owned) }
        try {
            imageState(scriptSourceImageEpoch(context, sourceId) == epoch, "来源、账号或设置已变化，请刷新本章。")
            operation()
        } finally {
            synchronized(imageStateLock) {
                imageSessionJobs[sourceId]?.let { jobs -> jobs.remove(owned); if (jobs.isEmpty()) imageSessionJobs.remove(sourceId) }
            }
        }
    }
} catch (failure: CancellationException) {
    // A source-session change cancels only this image child, not its reader/download owner.
    currentCoroutineContext().ensureActive()
    if (scriptSourceImageEpoch(context, sourceId) != epoch)
        throw ScriptImageRefreshRequired("来源、账号或设置已变化，请刷新本章。")
    throw failure
}

internal fun scriptImageOwner(mangaId: String, chapterId: String): String {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    return SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId)
}

fun invalidateScriptImage(mangaId: String, chapterId: String, token: String) {
    if (token.isEmpty()) return
    val owner = scriptImageOwner(mangaId, chapterId)
    synchronized(imageStateLock) {
        // Only an explicit retry of an already resolved image arms its source failure hook.
        // Preserve the exact revision, session and frozen route; optional loads cannot rearm it.
        failedImageRetries.addAll(resolvedImages.keys.filter { it.owner == owner && it.token == token })
        while (failedImageRetries.size > 128) failedImageRetries.remove(failedImageRetries.first())
        resolvedImages.entries.removeAll { it.key.owner == owner && it.key.token == token }
    }
}

/** Removes only a failed generated cache image; downloaded/user-imported files are never touched. */
internal suspend fun discardFailedScriptImage(context: PlatformContext, url: String) = withContext(Dispatchers.IO) {
    val path = dev.mangaflow.reader.readerFilePath(url) ?: return@withContext
    val file = StorageFile(path)
    val directory = StorageFile(cacheDirectory(context), "script-decoded")
    if (file.parentFile?.canonicalFile == directory.canonicalFile && file.name.matches(Regex("[a-f0-9]{64}\\.img")))
        file.delete()
}

/** Image headers belong to this request; connection framing and proxy credentials belong to the transport. */
internal fun checkedScriptImageHeaders(values: Map<String, String>): Map<String, String> {
    require(values.size <= 64 && values.entries.sumOf { it.key.length + it.value.length } <= 32_768) { "来源图片请求头超过限制。" }
    val managed = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "accept-encoding")
    return values.filterKeys { it.lowercase() !in managed }.mapValues { (name, value) ->
        require(name.isNotBlank() && name.length <= 128 && name.all { it.code in 33..126 && it != ':' } &&
            value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "来源图片请求头无效。" }
        value
    }
}

private fun headers(value: JSONObject?): Map<String, String> = checkedScriptImageHeaders(
    value?.let { json -> json.keys().asSequence().associateWith(json::getString) }.orEmpty())

/** Resolving a visible page starts its own bounded source operation, never a whole-chapter image crawl. */
internal suspend fun resolveScriptPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    route: DownloadRouteSnapshot? = null,
    promoted: () -> Boolean = { true },
    work: PrefetchWork? = null,
): Page {
    if (page.url.startsWith("file://")) return page.copy(headers = emptyMap())
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    val epoch = scriptSourceImageEpoch(context, ref.sourceId)
    imageState(page.sourceSessionEpoch.isEmpty() || page.sourceSessionEpoch == epoch, "来源、账号或设置已变化，请刷新本章。")
    val owner = scriptImageOwner(mangaId, chapterId)
    val original = restoreScriptPageHeaders(context, owner, page)
    if (original.sourceImageToken.isEmpty()) {
        require(!original.url.startsWith("mangaflow-script:")) { "图片来源标识缺失，请刷新本章。" }
        return original
    }
    require(ref.sourceId.startsWith("js_") && original.sourceImageToken.length <= 8192) { "图片与脚本来源不一致。" }
    val store = SourcePluginStore.get(context)
    store.ready.await()
    val bundle = store.installed.value.firstOrNull { it.sourceId == ref.sourceId }
        ?: throw ScriptImageRefreshRequired("图片来源已移除，请重新安装后刷新本章。")
    imageState(original.sourceRevision.isNotBlank() && original.sourceRevision == bundle.digest, "图片来源已更新，请刷新本章后重试。")
    val transport = currentCoroutineContext()[ChapterDownloadTransport]
    val selectedRoute = route ?: transport?.route ?: DownloadRoutingStore.get(context).sourceSnapshot(ref.sourceId)
    val key = resolutionKey(owner, bundle, epoch, original.sourceImageToken, selectedRoute)
    return imageGates[(key.hashCode() and Int.MAX_VALUE) % imageGates.size].withLock {
        cachedResolution(key)?.let { return@withLock it.forPage(original) }
        try {
            val retryFailure = synchronized(imageStateLock) { failedImageRetries.remove(key) }
            val call: suspend () -> String = { store.runtime.call(bundle, "resolveImage", JSONArray()
                .put(original.sourceImageToken).put(ref.mangaId).put(ref.chapterId).put(retryFailure)) }
            val resolve: suspend () -> String = { ExperiencePolicyStore.get(context).withResolveAdmission(promoted,
                { work?.admission(PrefetchStage.RESOLVING, it) }) {
                withImageSession(context, ref.sourceId, epoch) {
                    if (selectedRoute == null || transport?.route == selectedRoute) call() else {
                        val client = NetworkClient(route = selectedRoute)
                        try { withContext(ChapterDownloadTransport(selectedRoute, client)) { call() } } finally { client.close() }
                    }
                }
            } }
            val response = if (work != null) work.during(PrefetchStage.RESOLVING) { resolve() } else resolve()
            currentCoroutineContext().ensureActive()
            if (store.installed.value.none { it.sourceId == ref.sourceId && it.digest == bundle.digest })
                throw ScriptImageRefreshRequired("图片来源已更新或移除，请刷新本章。")
            if (scriptSourceImageEpoch(context, ref.sourceId) != epoch) throw ScriptImageRefreshRequired("来源、账号或设置已变化，请刷新本章。")
            val result = JSONObject(response).getJSONObject("result")
            val url = publicPluginUrl(result.getString("url")).toString()
            val transform = result.optString("transform")
            require(transform in setOf("", "jm") && (transform != "jm" || bundle.metadata.optString("key") == "jm")) { "来源图片变换暂不兼容。" }
            val resolved = original.copy(url = url, headers = headers(result.optJSONObject("headers")),
                headerSecretRef = "", headerSecretBinding = "", sourceSessionEpoch = epoch, transform = transform)
            synchronized(imageStateLock) {
                resolvedImages.remove(key)
                resolvedImages[key] = ResolvedImage(wallTimeMillis(), resolved)
                while (resolvedImages.size > 128) resolvedImages.remove(resolvedImages.keys.first())
            }
            imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, "resolve", true, wallTimeMillis()))
            resolved
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, "resolve", false, wallTimeMillis()))
            throw failure
        }
    }
}

private data class ScriptImageFile(val file: StorageFile, val gate: Mutex, val filename: String,
    val headers: Map<String, String>) {
    fun localPage(page: Page, epoch: String): Page =
        page.copy(url = file.toURI(), headers = emptyMap(), transform = "", sourceSessionEpoch = epoch)

    fun cachedPage(page: Page, epoch: String, maxImageBytes: Long): Page? =
        if (file.isFile && file.length() in 128L..maxImageBytes && storageImageBounds(file) != null)
            localPage(page, epoch) else null
}

private fun scriptImageFile(context: PlatformContext, owner: String, nativeChapterId: String, page: Page,
    bundle: ScriptSourcePackage, epoch: String): ScriptImageFile {
    imageState(page.sourceRevision.isEmpty() && page.transform.isEmpty() || page.sourceRevision == bundle.digest,
        "图片来源已更新，请刷新本章后重试。")
    imageState(page.sourceSessionEpoch.isEmpty() || page.sourceSessionEpoch == epoch, "来源、账号或设置已变化，请刷新本章。")
    require(page.transform in setOf("", "jm")) { "来源图片变换暂不兼容。" }
    val url = publicPluginUrl(page.url)
    val filename = url.pathSegments.lastOrNull().orEmpty()
    if (page.transform == "jm") {
        require(bundle.metadata.optString("protocol") == "venera" && bundle.metadata.optString("key") == "jm" &&
            page.sourceRevision == bundle.digest && nativeChapterId.matches(Regex("[0-9]{1,14}"))) { "图片解码来源身份不一致。" }
        require(filename.matches(Regex("[A-Za-z0-9_-]{1,100}\\.(?i:jpg|jpeg|png|webp|gif)")) &&
            url.pathSegments.windowed(2).any { it[0] == "photos" && it[1] == nativeChapterId }) { "图片解码地址与章节不一致。" }
    }
    val actualHeaders = checkedScriptImageHeaders(page.headers)
    val encodedHeaders = JSONObject(actualHeaders.entries.sortedBy { it.key.lowercase() }
        .associate { it.key.lowercase() to it.value }).toString()
    val fingerprint = digest(owner + "|" + bundle.digest + "|" + epoch + "|" +
        page.url + "|" + page.transform + "|" + encodedHeaders)
    val target = StorageFile(StorageFile(cacheDirectory(context), "script-decoded"), "$fingerprint.img")
    return ScriptImageFile(target, decodeGates[(fingerprint.hashCode() and Int.MAX_VALUE) % decodeGates.size], filename, actualHeaders)
}

/** Look only for a published file. Opaque tokens need the live resolver's exact, unexpired receipt. */
internal suspend fun cachedScriptReaderPage(context: PlatformContext, mangaId: String, chapterId: String,
    page: Page): Page? = withContext(Dispatchers.IO) {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    if (!ref.sourceId.startsWith("js_") || page.url.startsWith("file:") ||
        page.sourceRouteId.isNotBlank() && page.sourceRouteId != ref.sourceId) return@withContext null
    val store = SourcePluginStore.forCacheRead() ?: return@withContext null
    val bundle = store.installed.value.firstOrNull { it.sourceId == ref.sourceId } ?: return@withContext null
    try {
        val owner = scriptImageOwner(mangaId, chapterId)
        val epoch = scriptSourceImageEpoch(context, ref.sourceId)
        if (page.sourceSessionEpoch.isNotEmpty() && page.sourceSessionEpoch != epoch) return@withContext null
        val original = restoreScriptPageHeaders(context, owner, page)
        val transport = currentCoroutineContext()[ChapterDownloadTransport]
        val routing = DownloadRoutingStore.get(context)
        val route = transport?.route ?: routing.sourceSnapshot(ref.sourceId)
        val token = original.sourceImageToken
        if (token.isNotEmpty() && (token.length > 8192 || original.sourceRevision != bundle.digest))
            return@withContext null
        val key = if (token.isEmpty()) null else resolutionKey(owner, bundle, epoch, token, route)
        val receipt = key?.let { cachedResolution(it) ?: return@withContext null }
        val resolved = receipt?.forPage(original) ?: original
        val cacheFile = scriptImageFile(context, owner, ref.chapterId, resolved, bundle, epoch)
        if (!cacheFile.file.isFile || !cacheFile.gate.tryLock()) return@withContext null
        try {
            val release = dev.mangaflow.storage.StorageCacheStore.get(context).leaseFile(
                dev.mangaflow.storage.CacheBackend.SCRIPT, cacheFile.file, mangaId, chapterId, ref.sourceId,
                page.index, used = false)
            try {
                val hit = cacheFile.cachedPage(resolved, epoch, ExperiencePolicyStore.get(context).state.value.runtime.maxImageBytes)
                    ?: return@withContext null
                currentCoroutineContext().ensureActive()
                hit.takeIf {
                    scriptSourceImageEpoch(context, ref.sourceId) == epoch &&
                        store.installed.value.any { it.sourceId == ref.sourceId && it.digest == bundle.digest } &&
                        (transport != null || routing.sourceSnapshot(ref.sourceId) == route) &&
                        (key == null || cachedResolution(key) === receipt)
                }
            } finally { release() }
        } finally { cacheFile.gate.unlock() }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
}

/** Script images keep their public-host/redirect boundary before Coil receives an app-private file.
 * Plain images retain their exact bytes. Only the explicitly recognized JM transform is decoded. */
internal suspend fun materializeScriptPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    route: DownloadRouteSnapshot? = null,
    onBytes: ((Long) -> Unit)? = null,
    prefetched: Boolean = false,
    promoted: () -> Boolean = { false },
    work: PrefetchWork? = null,
    retry: Boolean = false,
    prefetchBudget: PrefetchByteBudget? = null,
): Page {
    if (page.url.startsWith("file://")) return page.copy(headers = emptyMap())
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    if (!ref.sourceId.startsWith("js_")) {
        require(page.transform.isEmpty()) { "图片变换与来源不一致。" }
        return page
    }
    val store = SourcePluginStore.get(context)
    store.ready.await()
    val bundle = store.installed.value.firstOrNull { it.sourceId == ref.sourceId }
        ?: throw ScriptImageRefreshRequired("图片来源已移除，请重新安装后刷新本章。")
    val epoch = scriptSourceImageEpoch(context, ref.sourceId)
    val cacheFile = scriptImageFile(context, scriptImageOwner(mangaId, chapterId), ref.chapterId, page, bundle, epoch)
    val target = cacheFile.file
    val policy = ExperiencePolicyStore.get(context)
    val runtime = policy.state.value.runtime
    val maxImageBytes = runtime.maxImageBytes
    val transport = currentCoroutineContext()[ChapterDownloadTransport]
    // Foreground cache hits must not require an available network route. Optional
    // current() checks and actual fetches evaluate this only inside the lease's finally.
    val selectedRoute by lazy { route ?: transport?.route ?: DownloadRoutingStore.get(context).sourceSnapshot(ref.sourceId) }
    val releaseCache = dev.mangaflow.storage.StorageCacheStore.get(context).leaseFile(
        dev.mangaflow.storage.CacheBackend.SCRIPT, target, mangaId, chapterId, ref.sourceId, page.index, !prefetched)
    val stage = if (page.transform == "jm") "decode" else "image"
    fun current() = scriptSourceImageEpoch(context, ref.sourceId) == epoch &&
        store.installed.value.any { it.sourceId == ref.sourceId && it.digest == bundle.digest } &&
        // A download owns its frozen transport. Optional reader preparation instead
        // expires when its source route changes; changing layout is not a new owner.
        (!prefetched || transport != null || DownloadRoutingStore.get(context).sourceSnapshot(ref.sourceId) == selectedRoute)
    // A key has one writer; unrelated originals no longer hold CPU slots while downloading.
    return try { cacheFile.gate.withLock {
        withContext(Dispatchers.IO) {
            if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
            if (!retry) cacheFile.cachedPage(page, epoch, maxImageBytes)?.let { return@withContext it }
            try {
                val fetch: suspend () -> ByteArray = { withImageSession(context, ref.sourceId, epoch) {
                    val started = monotonicTimeMillis()
                    var responseCode: Int? = null
                    var retryAfter: String? = null
                    var received = 0L
                    var failed = false
                    var ignoreFeedback = false
                    try {
                        fetchScriptImageBytes(context, page.url, cacheFile.headers, selectedRoute,
                            onBytes = { count ->
                                if (count > 0L) {
                                    if (!current()) throw ScriptImageRefreshRequired("图片来源、账号或预读路由已变化，请刷新本章。")
                                    received += count
                                    onBytes?.invoke(count)
                                    work?.onBytes(count)
                                }
                            }, onResponse = { code, responseHeaders ->
                                responseCode = code
                                retryAfter = responseHeaders.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
                                if (code in 200..299) {
                                    val encoding = responseHeaders.entries.firstOrNull { it.key.equals("Content-Encoding", true) }?.value?.trim()
                                    val identity = encoding.isNullOrEmpty() || encoding.equals("identity", true)
                                    val length = if (identity) responseHeaders.entries.firstOrNull { it.key.equals("Content-Length", true) }
                                        ?.value?.trim()?.toLongOrNull()?.takeIf { it >= 0L } else null
                                    work?.beginBody(length)
                                    if (prefetched && !promoted()) {
                                        // Darwin buffers compressed bodies before inflation; only foreground can own those.
                                        if (!identity && dev.mangaflow.platform.platformKind == dev.mangaflow.platform.PlatformKind.IOS)
                                            throw PrefetchBudgetExceeded()
                                        if (length != null) {
                                            val limits = policy.state.value
                                            val streamCap = limits.prefetchBytes.takeIf { length > it }
                                            val materialCap = limits.materializationBytes.takeIf { length > it }
                                            if (streamCap != null || materialCap != null)
                                                throw PrefetchCapacityExceeded(streamingBytes = length, materializationBytes = length,
                                                    streamingLimitBytes = streamCap, materializationLimitBytes = materialCap)
                                            prefetchBudget?.requireRemaining(length)
                                        }
                                    }
                                }
                            })
                    } catch (failure: Exception) {
                        ignoreFeedback = failure is CancellationException || failure is PrefetchBudgetExceeded || failure is PrefetchPromoted ||
                            failure is DownloadRouteException || failure is SourceException && responseCode == null && failure.retryAfterSeconds > 0L
                        failed = !ignoreFeedback
                        throw failure
                    } finally {
                        if (!ignoreFeedback && current()) ExperiencePolicyStore.get(context).recordTransfer(
                            ref.sourceId, responseCode, received, monotonicTimeMillis() - started, retryAfter, failed,
                        )
                    }
                } }
                val waiting: (dev.mangaflow.experience.AdmissionWait?) -> Unit = { work?.admission(PrefetchStage.DOWNLOADING, it) }
                val admittedFetch: suspend () -> ByteArray = { when {
                    prefetched -> {
                        if (promoted()) policy.withImageAdmission(reader = true, source = ref.sourceId, onWaiting = waiting) { fetch() }
                        else try {
                            val streamCap = policy.state.value.prefetchBytes
                            if (!promoted()) {
                                if (streamCap < 1L) throw PrefetchCapacityExceeded(streamingBytes = 1L, streamingLimitBytes = streamCap)
                                prefetchBudget?.requireRemaining(1L)
                            }
                            val remaining = minOf(streamCap, prefetchBudget?.remaining ?: Long.MAX_VALUE)
                            policy.withPrefetchAdmission(ref.sourceId,
                                minOf(policy.estimatedPageBytes(page.url), remaining.coerceAtLeast(0L)), promoted, waiting) { fetch() }
                        } catch (_: PrefetchPromoted) {
                            policy.withImageAdmission(reader = true, source = ref.sourceId, onWaiting = waiting) { fetch() }
                        }
                    }
                    // Download workers already own their frozen route and background-page permit.
                    transport != null -> { work?.admission(PrefetchStage.DOWNLOADING, null); fetch() }
                    else -> policy.withImageAdmission(reader = true, source = ref.sourceId, onWaiting = waiting) { fetch() }
                } }
                val bytes = if (work != null) work.during(PrefetchStage.DOWNLOADING) { admittedFetch() } else admittedFetch()
                require(bytes.size.toLong() in 128L..maxImageBytes) { "来源原图为空或超过大小限制。" }
                currentCoroutineContext().ensureActive()
                if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
                val outputBytes = if (page.transform == "jm") {
                    val decode: suspend () -> ByteArray = { policy.withDecodeAdmission(optional = prefetched, promoted = promoted,
                        onWaiting = { work?.admission(PrefetchStage.CHECKING, it) }) {
                        withContext(Dispatchers.Default) { decodeScriptJmImage(bytes, ref.chapterId, cacheFile.filename, page.url, page.index,
                            runtime.scriptDecodeMiPixels.toLong() * 1_048_576L, maxImageBytes) }
                    } }
                    if (work != null) work.during(PrefetchStage.CHECKING) { decode() } else decode()
                } else bytes
                require(outputBytes.size.toLong() in 128L..maxImageBytes) { "来源图片处理结果无效。" }
                currentCoroutineContext().ensureActive()
                if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
                val atomic = StorageAtomicFile(target)
                val output = atomic.startWrite()
                try {
                    output.write(outputBytes)
                    atomic.finishWrite(output)
                } catch (failure: Exception) { atomic.failWrite(output); throw failure }
                if (storageImageBounds(target) == null) {
                    target.delete()
                    throw SourceException("来源返回的内容不是可读取的图片。")
                }
                if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
                imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, stage, true, wallTimeMillis()))
                cacheFile.localPage(page, epoch)
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException || failure is PrefetchBudgetExceeded) throw failure
                imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, stage, false, wallTimeMillis()))
                throw failure
            }
        }
    } } finally { releaseCache() }
}

/** The same original-byte cache as foreground reading, admitted under its current future-page budget. */
internal suspend fun prefetchScriptPage(context: PlatformContext, mangaId: String, chapterId: String,
    page: Page, budget: PrefetchByteBudget, work: PrefetchWork): Page? {
    val source = SourceKeys.decodeChapter(mangaId, chapterId).sourceId
    if (!source.startsWith("js_")) return null
    val policy = ExperiencePolicyStore.get(context)
    val route = currentCoroutineContext()[ChapterDownloadTransport]?.route
        ?: DownloadRoutingStore.get(context).sourceSnapshot(source)
    val transferLock = SynchronizedObject()
    val receipt = budget.child()
    val resourceOwner = PrefetchResourceOwner()
    var completed = false
    var retained = 0L
    var received = 0L
    var closed = false
    val started = monotonicTimeMillis()
    suspend fun load(): Page {
        currentCoroutineContext().ensureActive()
        val resolved = resolveScriptPage(context, mangaId, chapterId, page, route, promoted = { work.isForeground }, work = work)
        val result = materializeScriptPage(context, mangaId, chapterId, resolved, route,
            prefetched = true, promoted = { work.isForeground }, work = work,
            prefetchBudget = receipt, onBytes = { count ->
            synchronized(transferLock) {
                if (closed) throw CancellationException("图片预取已结束。")
                received += count
                work.ifOptional {
                    policy.requirePrefetchWrite()
                    policy.acceptPrefetchChunk(count, resourceOwner, memoryBacked = true)
                    try { receipt.accept(count) }
                    catch (failure: Throwable) {
                        policy.releasePrefetchBytes(count, resourceOwner, memoryBacked = true)
                        throw failure
                    }
                    retained += count
                }
            }
        })
        val path = dev.mangaflow.reader.readerFilePath(result.url)
        val size = path?.let { StorageFile(it).length() }
        val cacheHit = synchronized(transferLock) { received == 0L }
        if (cacheHit && size != null) work.ifOptional {
            receipt.acceptCached(size)
        }
        policy.recordImage(monotonicTimeMillis() - started, memory = false, disk = cacheHit,
            prefetch = !work.isForeground, key = page.url, bytes = size)
        return result
    }
    return try {
        load().also { completed = true }
    } finally {
        val released = synchronized(transferLock) { closed = true; retained.also { retained = 0L } }
        policy.releasePrefetchBytes(released, resourceOwner, memoryBacked = true)
        if (!completed) receipt.close()
    }
}

/** Shared chapter headers reuse one vault item; the manifest contains only its opaque reference. */
internal fun protectScriptPageHeaders(context: PlatformContext, owner: String, page: Page): Page {
    if (page.headerSecretRef.isNotEmpty()) {
        // An unavailable vault (for example before device unlock) must not overwrite an
        // existing authenticated reference with its public fallback fields.
        return try { restoreScriptPageHeaders(context, owner, page) } catch (_: Exception) { page }
    }
    val sourceId = SourceKeys.decodeChapter("", owner).sourceId
    val epoch = scriptSourceImageEpoch(context, sourceId)
    // Persisting a failed/paused old task must not bless its old credentials with
    // the new account's epoch. Its source-refresh requirement remains authoritative.
    if (page.sourceSessionEpoch.isNotEmpty() && page.sourceSessionEpoch != epoch) return page
    val checked = checkedScriptImageHeaders(page.headers)
    if (checked.isEmpty()) return page
    val value = JSONObject().put("owner", owner).put("sourceId", sourceId).put("epoch", epoch)
        .put("headers", JSONObject(checked)).toString()
    val reference = digest(value)
    synchronized(imageStateLock) {
        if (reference !in savedHeaderRefs) {
            writeSecret(context, "source_image_headers_$reference", value)
            savedHeaderRefs += reference
            headerSecrets[reference] = value
            while (headerSecrets.size > 128) headerSecrets.remove(headerSecrets.keys.first())
            while (savedHeaderRefs.size > 512) savedHeaderRefs.remove(savedHeaderRefs.first())
        }
    }
    return page.copy(headers = checked, headerSecretRef = reference, headerSecretBinding = headerBinding(value, page.url), sourceSessionEpoch = epoch)
}

internal fun restoreScriptPageHeaders(context: PlatformContext, owner: String, page: Page): Page {
    if (page.headerSecretRef.isEmpty()) return page
    require(page.headerSecretRef.matches(Regex("[a-f0-9]{64}"))) { "图片会话引用无效。" }
    val saved = synchronized(imageStateLock) { headerSecrets[page.headerSecretRef] }
        ?: readSecret(context, "source_image_headers_" + page.headerSecretRef)
        ?: throw SourceException("图片会话已失效，请刷新本章；已下载的图片仍可离线阅读。")
    require(digest(saved) == page.headerSecretRef) { "图片会话校验失败，请刷新本章。" }
    val value = JSONObject(saved)
    require(value.getString("owner") == owner) { "图片会话不属于当前章节。" }
    val sourceId = SourceKeys.decodeChapter("", owner).sourceId
    imageState(value.optString("epoch", "initial") == scriptSourceImageEpoch(context, sourceId), "来源、账号或设置已变化，请刷新本章。")
    require(page.headerSecretBinding.isNotEmpty() && headerBinding(saved, page.url) == page.headerSecretBinding) {
        "图片地址与受保护会话不一致，请刷新本章。"
    }
    synchronized(imageStateLock) {
        headerSecrets[page.headerSecretRef] = saved
        while (headerSecrets.size > 128) headerSecrets.remove(headerSecrets.keys.first())
    }
    return page.copy(headers = headers(value.getJSONObject("headers")))
}

private fun headerBinding(secret: String, url: String): String = hmacSha256(secret.encodeToByteArray(), url.encodeToByteArray())
    .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

private fun digest(value: String): String = sha256(value.encodeToByteArray())
    .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

internal expect fun decodeScriptJmImage(bytes: ByteArray, chapterId: String, filename: String, url: String, index: Int, maxPixels: Long, maxEncodedBytes: Long): ByteArray
