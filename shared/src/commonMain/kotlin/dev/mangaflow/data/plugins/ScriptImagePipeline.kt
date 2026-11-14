package dev.mangaflow.data.plugins

import dev.mangaflow.data.ChapterDownloadTransport
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.PrefetchPromoted
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteSnapshot
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ScriptImageEvent(val sourceId: String, val stage: String, val success: Boolean, val at: Long)
internal class ScriptImageRefreshRequired(message: String) : Exception(message)
private fun imageState(allowed: Boolean, message: String) { if (!allowed) throw ScriptImageRefreshRequired(message) }
private val imageEvents = MutableSharedFlow<ScriptImageEvent>(extraBufferCapacity = 64)
val scriptImageEvents = imageEvents.asSharedFlow()

private data class ImageRequestKey(val sourceId: String, val owner: String, val revision: String,
    val epoch: String, val token: String, val route: String)
private data class ResolvedImage(val at: Long, val page: Page)
private val imageStateLock = SynchronizedObject()
private val resolvedImages = linkedMapOf<ImageRequestKey, ResolvedImage>()
private val imageGates = Array(16) { Mutex() }
private val decodeGates = Array(16) { Mutex() }
private val decodeSlots = Semaphore(2)
private val scriptPrefetchSlots = Semaphore(1)
private val savedHeaderRefs = linkedSetOf<String>()
private val headerSecrets = linkedMapOf<String, String>()
private val sourceEpochOverrides = mutableMapOf<String, String>()
private val imageSessionJobs = mutableMapOf<String, MutableSet<Job>>()

fun scriptSourceImageEpoch(context: PlatformContext, sourceId: String): String {
    if (!sourceId.startsWith("js_")) return "initial"
    return synchronized(imageStateLock) { sourceEpochOverrides[sourceId] }
        ?: context.getSharedPreferences("mangaflow_script_image_sessions").getString("epoch_$sourceId", "initial").orEmpty()
}

/** A late script callback must not restore cookies or settings after its session was replaced. */
internal fun <T> withScriptSourceImageEpoch(context: PlatformContext, sourceId: String,
    expected: String, operation: () -> T): T = synchronized(imageStateLock) {
    val current = sourceEpochOverrides[sourceId]
        ?: context.getSharedPreferences("mangaflow_script_image_sessions")
            .getString("epoch_$sourceId", "initial").orEmpty()
    if (current != expected) throw CancellationException("来源账号或设置已变化。")
    operation()
}

/** Account/settings changes invalidate late image resolutions and protected headers across restarts. */
fun invalidateScriptSourceImages(context: PlatformContext, sourceId: String) {
    if (!sourceId.startsWith("js_")) return
    val epoch = randomUuid()
    val interrupted = synchronized(imageStateLock) {
        sourceEpochOverrides[sourceId] = epoch
        resolvedImages.entries.removeAll { it.key.sourceId == sourceId }
        headerSecrets.entries.removeAll { JSONObject(it.value).optString("sourceId") == sourceId }
        check(context.getSharedPreferences("mangaflow_script_image_sessions").edit()
            .putString("epoch_$sourceId", epoch).commit()) { "来源图片会话状态未能保存，请检查存储空间。" }
        imageSessionJobs.remove(sourceId).orEmpty().toList()
    }
    interrupted.forEach { it.cancel(CancellationException("来源图片会话已变化。")) }
}

private suspend fun <T> withImageSession(context: PlatformContext, sourceId: String, epoch: String,
    operation: suspend () -> T): T = try {
    coroutineScope {
        val owned = requireNotNull(currentCoroutineContext()[Job])
        synchronized(imageStateLock) { imageSessionJobs.getOrPut(sourceId) { mutableSetOf() }.add(owned) }
        try {
            imageState(scriptSourceImageEpoch(context, sourceId) == epoch, "来源账号或设置已变化，请刷新本章。")
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
        throw ScriptImageRefreshRequired("来源账号或设置已变化，请刷新本章。")
    throw failure
}

internal fun scriptImageOwner(mangaId: String, chapterId: String): String {
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    return SourceKeys.chapter(ref.sourceId, ref.mangaId, ref.chapterId)
}

fun invalidateScriptImage(mangaId: String, chapterId: String, token: String) {
    if (token.isEmpty()) return
    val owner = scriptImageOwner(mangaId, chapterId)
    synchronized(imageStateLock) { resolvedImages.entries.removeAll { it.key.owner == owner && it.key.token == token } }
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
suspend fun resolveScriptPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    route: DownloadRouteSnapshot? = null,
): Page {
    if (page.url.startsWith("file://")) return page.copy(headers = emptyMap())
    val ref = SourceKeys.decodeChapter(mangaId, chapterId)
    val epoch = scriptSourceImageEpoch(context, ref.sourceId)
    imageState(page.sourceSessionEpoch.isEmpty() || page.sourceSessionEpoch == epoch, "来源账号或设置已变化，请刷新本章。")
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
    val selectedRoute = route ?: transport?.route
    val routeKey = selectedRoute?.let { it.mode.name + "|" + it.proxy?.type?.name.orEmpty() + "|" +
        it.proxy?.host.orEmpty() + "|" + it.proxy?.port?.toString().orEmpty() } ?: "reader"
    val key = ImageRequestKey(ref.sourceId, owner, bundle.digest, epoch, original.sourceImageToken, routeKey)
    return imageGates[(key.hashCode() and Int.MAX_VALUE) % imageGates.size].withLock {
        synchronized(imageStateLock) {
            resolvedImages[key]?.takeIf { wallTimeMillis() - it.at in 0L until 30_000L }?.page
        }?.let { return@withLock it.copy(index = original.index, width = original.width, height = original.height) }
        try {
            val call: suspend () -> String = { store.runtime.call(bundle, "resolveImage", JSONArray()
                .put(original.sourceImageToken).put(ref.mangaId).put(ref.chapterId)) }
            val response = withImageSession(context, ref.sourceId, epoch) {
                if (selectedRoute == null || transport?.route == selectedRoute) call() else {
                    val client = NetworkClient(route = selectedRoute)
                    try { withContext(ChapterDownloadTransport(selectedRoute, client)) { call() } } finally { client.close() }
                }
            }
            currentCoroutineContext().ensureActive()
            if (store.installed.value.none { it.sourceId == ref.sourceId && it.digest == bundle.digest })
                throw ScriptImageRefreshRequired("图片来源已更新或移除，请刷新本章。")
            if (scriptSourceImageEpoch(context, ref.sourceId) != epoch) throw ScriptImageRefreshRequired("来源账号或设置已变化，请刷新本章。")
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

/** Script images keep their public-host/redirect boundary before Coil receives an app-private file.
 * Plain images retain their exact bytes. Only the explicitly recognized JM transform is decoded. */
suspend fun materializeScriptPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    route: DownloadRouteSnapshot? = null,
    onBytes: ((Long) -> Unit)? = null,
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
    imageState(page.sourceRevision.isEmpty() && page.transform.isEmpty() || page.sourceRevision == bundle.digest,
        "图片来源已更新，请刷新本章后重试。")
    val epoch = scriptSourceImageEpoch(context, ref.sourceId)
    imageState(page.sourceSessionEpoch.isEmpty() || page.sourceSessionEpoch == epoch, "来源账号或设置已变化，请刷新本章。")
    require(page.transform in setOf("", "jm")) { "来源图片变换暂不兼容。" }
    val url = publicPluginUrl(page.url)
    val filename = url.pathSegments.lastOrNull().orEmpty()
    if (page.transform == "jm") {
        require(bundle.metadata.optString("protocol") == "venera" && bundle.metadata.optString("key") == "jm" &&
            page.sourceRevision == bundle.digest && ref.chapterId.matches(Regex("[0-9]{1,14}"))) { "图片解码来源身份不一致。" }
        require(filename.matches(Regex("[A-Za-z0-9_-]{1,100}\\.(?i:jpg|jpeg|png|webp|gif)")) &&
            url.pathSegments.windowed(2).any { it[0] == "photos" && it[1] == ref.chapterId }) { "图片解码地址与章节不一致。" }
    }
    val actualHeaders = checkedScriptImageHeaders(page.headers)
    val encodedHeaders = JSONObject(actualHeaders.entries.sortedBy { it.key.lowercase() }
        .associate { it.key.lowercase() to it.value }).toString()
    val fingerprint = digest(scriptImageOwner(mangaId, chapterId) + "|" + bundle.digest + "|" + epoch + "|" +
        page.url + "|" + page.transform + "|" + encodedHeaders)
    val target = StorageFile(StorageFile(cacheDirectory(context), "script-decoded"), "$fingerprint.img")
    val stage = if (page.transform == "jm") "decode" else "image"
    fun current() = scriptSourceImageEpoch(context, ref.sourceId) == epoch &&
        store.installed.value.any { it.sourceId == ref.sourceId && it.digest == bundle.digest }
    return decodeGates[(fingerprint.hashCode() and Int.MAX_VALUE) % decodeGates.size].withLock { decodeSlots.withPermit {
        withContext(Dispatchers.IO) {
            if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
            if (target.isFile && target.length() in 128L..MAX_IMAGE_BYTES && storageImageBounds(target) != null)
                return@withContext page.copy(url = target.toURI(), headers = emptyMap(), transform = "", sourceSessionEpoch = epoch)
            try {
                val bytes = withImageSession(context, ref.sourceId, epoch) {
                    fetchScriptImageBytes(context, page.url, actualHeaders,
                        route ?: currentCoroutineContext()[ChapterDownloadTransport]?.route, onBytes)
                }
                require(bytes.size.toLong() in 128L..MAX_IMAGE_BYTES) { "来源原图为空或超过大小限制。" }
                currentCoroutineContext().ensureActive()
                if (!current()) throw ScriptImageRefreshRequired("图片来源或账号已变化，请刷新本章。")
                val outputBytes = if (page.transform == "jm")
                    decodeScriptJmImage(bytes, ref.chapterId, filename, page.url, page.index) else bytes
                require(outputBytes.size.toLong() in 128L..MAX_IMAGE_BYTES) { "来源图片处理结果无效。" }
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
                pruneDecoded(target.parentFile)
                imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, stage, true, wallTimeMillis()))
                page.copy(url = target.toURI(), headers = emptyMap(), transform = "", sourceSessionEpoch = epoch)
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException || failure is PrefetchBudgetExceeded) throw failure
                imageEvents.tryEmit(ScriptImageEvent(ref.sourceId, stage, false, wallTimeMillis()))
                throw failure
            }
        }
    } }
}

/** The same original-byte cache as foreground reading, admitted under its current future-page budget. */
internal suspend fun prefetchScriptPage(context: PlatformContext, mangaId: String, chapterId: String,
    page: Page, budget: PrefetchByteBudget, work: PrefetchWork): Page? = scriptPrefetchSlots.withPermit {
    val source = SourceKeys.decodeChapter(mangaId, chapterId).sourceId
    if (!source.startsWith("js_")) return@withPermit null
    val policy = ExperiencePolicyStore.get(context)
    val transferLock = SynchronizedObject()
    var retained = 0L
    var received = 0L
    var closed = false
    val started = monotonicTimeMillis()
    suspend fun load(): Page {
        currentCoroutineContext().ensureActive()
        val resolved = resolveScriptPage(context, mangaId, chapterId, page)
        val result = materializeScriptPage(context, mangaId, chapterId, resolved, onBytes = { count ->
            synchronized(transferLock) {
                if (closed) throw CancellationException("图片预取已结束。")
                received += count
                work.ifOptional {
                    if (!policy.canWritePrefetch()) throw PrefetchBudgetExceeded()
                    budget.accept(count)
                    policy.acceptPrefetchChunk(count)
                    retained += count
                }
            }
        })
        val path = dev.mangaflow.reader.readerFilePath(result.url)
        val size = path?.let { StorageFile(it).length() }
        val cacheHit = synchronized(transferLock) { received == 0L }
        if (cacheHit && size != null) work.ifOptional { budget.accept(size) }
        policy.recordImage(monotonicTimeMillis() - started, memory = false, disk = cacheHit,
            prefetch = !work.isForeground, key = page.url, bytes = size)
        return result
    }
    try {
        try { policy.withPrefetchAdmission(source, policy.estimatedPageBytes(page.url), { work.isForeground }) { load() } }
        catch (_: PrefetchPromoted) { policy.withImageAdmission(reader = true) { load() } }
    } finally {
        val released = synchronized(transferLock) { closed = true; retained.also { retained = 0L } }
        policy.releasePrefetchBytes(released)
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
    imageState(value.optString("epoch", "initial") == scriptSourceImageEpoch(context, sourceId), "来源账号或设置已变化，请刷新本章。")
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
private fun pruneDecoded(directory: StorageFile?) {
    val files = directory?.listFiles()?.filter { it.isFile && it.extension == "img" }.orEmpty()
    var size = files.sumOf { it.length() }
    if (size <= 384L * 1024 * 1024) return
    for (file in files.sortedBy { it.lastModified() }) {
        if (size <= 256L * 1024 * 1024) break
        val bytes = file.length(); if (file.delete()) size -= bytes
    }
}
private const val MAX_IMAGE_BYTES = 96L * 1024 * 1024

internal expect fun decodeScriptJmImage(bytes: ByteArray, chapterId: String, filename: String, url: String, index: Int): ByteArray
