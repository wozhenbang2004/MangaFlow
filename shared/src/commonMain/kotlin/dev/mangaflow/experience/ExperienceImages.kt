@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package dev.mangaflow.experience

import androidx.compose.ui.unit.IntSize
import coil3.EventListener
import coil3.Extras
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.BlackholeDecoder
import coil3.decode.Decoder
import coil3.decode.DecodeResult
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.network.httpHeaders
import coil3.request.*
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import dev.mangaflow.data.Page
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.sha256
import dev.mangaflow.reader.ReaderDimensionCache
import dev.mangaflow.reader.readNativeReaderSize
import dev.mangaflow.reader.readerFilePath
import dev.mangaflow.reader.readerImageDimensions
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.cacheDirectory
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.*

internal class ExperienceNetworkTransport(val client: NetworkClient, val close: () -> Unit)
internal expect fun createExperienceNetworkClient(route: DownloadRouteSnapshot? = null,
    timeouts: ExperienceImageTimeouts = ExperienceImageTimeouts()): ExperienceNetworkTransport
internal enum class ExperienceImageWork { VISIBLE, READER, PREFETCH }
private val workKey = Extras.Key(ExperienceImageWork.VISIBLE)
private val bytesKey = Extras.Key<PrefetchByteBudget?>(null)
private val sourceKey = Extras.Key<String?>(null)
private val sourceCategoryKey = Extras.Key<DownloadCategory?>(null)
private val workTokenKey = Extras.Key<PrefetchWork?>(null)
private val readerTransferKey = Extras.Key<ReaderTransferObserver?>(null)
private val cacheOnlyKey = Extras.Key(false)

/** Includes the transport veto, so an expired disk entry cannot silently fetch a comparison image. */
internal fun ImageRequest.Builder.readerCacheOnly() = apply {
    extras[cacheOnlyKey] = true
    networkCachePolicy(CachePolicy.DISABLED)
}

/** Only successfully written body bytes advance this monotonic foreground inactivity clock. */
internal data class ReaderTransferSnapshot(
    val receivedBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val lastProgressAt: Long? = null,
)

/** Owned by one reader attempt, including any native/script image materialization before Coil. */
internal class ReaderTransferObserver(val preparationWork: PrefetchWork? = null) {
    private val lock = StorageLock()
    private var current = ReaderTransferSnapshot()

    fun snapshot(): ReaderTransferSnapshot = synchronized(lock) { current }

    /** A new response can change its length; headers alone never extend the waiting deadline. */
    fun beginBody(expectedBytes: Long? = null) = synchronized(lock) {
        current = current.copy(receivedBytes = 0L, expectedBytes = expectedBytes?.takeIf { it >= 0L })
    }

    fun onBytes(count: Long, expectedBytes: Long? = null) {
        if (count <= 0L) return
        synchronized(lock) {
            current = current.copy(
                receivedBytes = current.receivedBytes + count.coerceAtMost(Long.MAX_VALUE - current.receivedBytes),
                expectedBytes = expectedBytes?.takeIf { it >= 0L } ?: current.expectedBytes,
                lastProgressAt = monotonicTimeMillis(),
            )
        }
    }
}

internal fun ImageRequest.Builder.readerTransferProgress(observer: ReaderTransferObserver) = apply {
    extras[readerTransferKey] = observer
    observer.preparationWork?.let { extras[workTokenKey] = it }
}
internal fun ImageRequest.Builder.readerPreparationWork(work: PrefetchWork) = apply { extras[workTokenKey] = work }

internal enum class PrefetchStage { QUEUED, RESOLVING, DOWNLOADING, CHECKING }
internal data class PrefetchWorkSnapshot(
    val stage: PrefetchStage = PrefetchStage.QUEUED,
    val admitted: Boolean = false,
    val wait: AdmissionWait? = null,
    val receivedBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val lastProgressAt: Long? = null,
    val settled: Boolean = false,
    val succeeded: Boolean? = null,
    val diskCacheKey: String? = null,
    val memoryCacheKey: MemoryCache.Key? = null,
)

/** Ownership can promote one displayed page without lifting the rest of its byte window. */
internal class PrefetchWork(private val promoted: () -> Unit = {},
    private val observe: (PrefetchWorkSnapshot) -> Unit = {}) {
    private val lock = StorageLock()
    private val foreground = atomic(false)
    private var activity = PrefetchWorkSnapshot()
    fun snapshot(): PrefetchWorkSnapshot = synchronized(lock) { activity }
    private fun update(notify: Boolean = true, change: (PrefetchWorkSnapshot) -> PrefetchWorkSnapshot) {
        val next = synchronized(lock) { change(activity).also { activity = it } }
        if (notify) observe(next)
    }
    fun admission(stage: PrefetchStage, wait: AdmissionWait?) = update {
        it.copy(stage = stage, admitted = wait == null, wait = wait, settled = false, succeeded = null)
    }
    fun queued(stage: PrefetchStage) = update {
        it.copy(stage = stage, admitted = false, wait = null, settled = false, succeeded = null)
    }
    suspend fun <T> during(stage: PrefetchStage, block: suspend () -> T): T {
        queued(stage)
        var success = false
        return try { block().also { success = true } }
        finally { update { it.copy(stage = stage, admitted = false, wait = null, settled = true, succeeded = success) } }
    }
    fun imageArtifact(diskCacheKey: String?, memoryCacheKey: MemoryCache.Key?, ready: Boolean = false) = update {
        val artifact = it.copy(diskCacheKey = diskCacheKey, memoryCacheKey = memoryCacheKey)
        // Coil can satisfy this exact request from memory without invoking a decoder.
        // This settles the image preparation task, not a claim of newly executed decode work.
        if (ready) artifact.copy(stage = PrefetchStage.CHECKING, admitted = false,
            wait = null, settled = true, succeeded = true) else artifact
    }
    fun beginBody(expectedBytes: Long?) = update {
        it.copy(receivedBytes = 0L, expectedBytes = expectedBytes?.takeIf { count -> count >= 0L })
    }
    fun onBytes(count: Long) {
        if (count <= 0L) return
        update(notify = false) { it.copy(receivedBytes = it.receivedBytes + count.coerceAtMost(Long.MAX_VALUE - it.receivedBytes),
            lastProgressAt = monotonicTimeMillis()) }
    }
    val isForeground: Boolean get() = foreground.value
    fun promote() {
        val changed = synchronized(lock) { foreground.compareAndSet(false, true) }
        if (changed) promoted()
    }
    fun ifOptional(block: () -> Unit) = synchronized(lock) { if (!foreground.value) block() }
}
internal class PrefetchPromoted : RuntimeException()

/** Explicit source identity, never inferred from a shared CDN host. */
internal fun ImageRequest.Builder.sourceNetwork(source: String, category: DownloadCategory? = null) = apply {
    extras[sourceKey] = source
    extras[sourceCategoryKey] = category
}

internal fun ImageRequest.Builder.readerPriority(source: String? = null) = apply {
    extras[workKey] = ExperienceImageWork.READER
    extras[sourceKey] = source
}
internal fun ImageRequest.Builder.prefetchPriority(bytes: PrefetchByteBudget, source: String, work: PrefetchWork = PrefetchWork()) = apply {
    extras[workKey] = ExperienceImageWork.PREFETCH
    extras[bytesKey] = bytes
    extras[sourceKey] = source
    extras[workTokenKey] = work
}

/** Held for a future-page window; optional child tickets release only after their work settles. */
internal class PrefetchByteBudget(private val maximum: Long, private val parent: PrefetchByteBudget? = null) {
    private val lock = StorageLock()
    private var spent = 0L
    private var exhausted = false
    private var closed = false
    val used: Long get() = synchronized(lock) { spent }
    val remaining: Long get() = synchronized(lock) {
        if (closed || exhausted) 0L else minOf((maximum - spent).coerceAtLeast(0L), parent?.remaining ?: Long.MAX_VALUE)
    }
    fun child(): PrefetchByteBudget = PrefetchByteBudget(maximum, this)
    // A failed receipt cannot manufacture a parent charge. Existing ordinary windows still
    // remain exhausted for the rest of their lifetime, while close releases only real bytes.
    fun exhaust() = synchronized(lock) { exhausted = true }
    fun accept(count: Long): Unit = synchronized(lock) {
        if (closed || exhausted || count < 0L || count > maximum - spent) throw PrefetchBudgetExceeded()
        // Every nested operation takes child then parent; parents never acquire child locks.
        parent?.accept(count)
        spent += count
    }
    fun release(count: Long): Unit = synchronized(lock) {
        val released = count.coerceIn(0L, spent)
        spent -= released
        parent?.release(released)
    }
    fun close(): Unit = synchronized(lock) {
        if (!closed) {
            closed = true
            val released = spent
            spent = 0L
            parent?.release(released)
        }
    }
}
internal open class PrefetchBudgetExceeded : IOException("本次预取已达到资源预算")
/** One transport/materialization attempt; fields are owned by ExperiencePolicyStore's lock. */
internal class PrefetchResourceOwner {
    var streaming = 0L
    var materialization = 0L
    var streamingReleases = 0L
    var materializationReleases = 0L
}
/** Full replay demand, with external-only release generations for the blocked resource pools. */
internal class PrefetchResourceBusy(
    val owner: PrefetchResourceOwner,
    val streamingGeneration: Long?, val materializationGeneration: Long?,
    val streamingBytes: Long, val materializationBytes: Long,
) : PrefetchBudgetExceeded()
/** This attempt cannot fit an empty shared pool until its configured/effective cap grows. */
internal class PrefetchCapacityExceeded(
    val streamingBytes: Long = 0L, val materializationBytes: Long = 0L,
) : PrefetchBudgetExceeded()
internal fun experienceOrigin(url: String) = url.toHttpUrlOrNull()?.host ?: "local"

private val publicImageHeaders = setOf("referer", "user-agent", "accept", "accept-language", "accept-encoding", "origin")

private fun readerPageHeaders(page: Page, headers: Map<String, String>): Map<String, String> = buildMap {
    headers.forEach { (name, value) -> put(name.lowercase(), value) }
    page.headers.forEach { (name, value) -> put(name.lowercase(), value) }
}

/** Protected images cannot reuse an old session's mutable URL or credential-bearing cache entry. */
internal fun readerPageCacheKey(page: Page, headers: Map<String, String>, source: String = ""): String {
    // An alternate page must never inherit the primary site's cookies or authorization.
    val ownedHeaders = if (page.sourceRouteId.isNotBlank()) emptyMap() else headers
    val merged = readerPageHeaders(page, ownedHeaders)
    val actualSource = page.sourceRouteId.ifBlank { source }
    val scoped = page.sourceRouteId.isNotBlank() || page.sourceRevision.isNotBlank() ||
        page.sourceSessionEpoch.isNotBlank() ||
        page.sourceImageToken.isNotBlank() || page.headerSecretRef.isNotBlank() ||
        page.headerSecretBinding.isNotBlank() || page.transform.isNotBlank() ||
        merged.keys.any { it !in publicImageHeaders }
    if (!scoped) return page.url
    // Length prefixes retain exact boundaries; only the digest is retained in cache keys.
    val identity = buildString {
        fun field(value: String) { append(value.length).append(':').append(value) }
        field("reader-owned-v1"); field(actualSource); field(page.url); field(page.sourceRevision)
        field(page.sourceSessionEpoch); field(page.sourceImageToken); field(page.headerSecretRef)
        field(page.headerSecretBinding); field(page.transform)
        merged.entries.sortedBy { it.key }.forEach { (name, value) -> field(name); field(value) }
    }
    return "reader-owned:" + sha256(identity.encodeToByteArray()).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}

/** Visible, original-byte and decoded warm requests use the same exact source/cache ownership. */
internal fun readerPageRequest(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String = "",
): ImageRequest.Builder {
    val actualSource = page.sourceRouteId.ifBlank { source }
    val key = readerPageCacheKey(page, headers, actualSource)
    if (page.url.startsWith("file:")) dev.mangaflow.storage.StorageCacheStore.get(context).noteMemoryFile(page.url, key)
    return ImageRequest.Builder(imagePlatformContext(context)).data(page.url)
        .readerPriority(actualSource.takeIf { it.isNotBlank() })
        .memoryCacheKey(key).diskCacheKey(key)
        .httpHeaders(coil3.network.NetworkHeaders.Builder().apply {
            readerPageHeaders(page, if (page.sourceRouteId.isNotBlank()) emptyMap() else headers)
                .forEach { (name, value) -> set(name, value) }
        }.build())
}

/** Preview-only bound; original-file dimensions and full-detail tiles are never reduced. */
internal fun readerPreviewSize(widthPx: Int, heightPx: Int, maxWidthPx: Int = 1600,
    maxHeightPx: Int = 4096): IntSize =
    IntSize(widthPx.coerceIn(1, maxWidthPx.coerceAtLeast(1)),
        heightPx.coerceIn(1, maxHeightPx.coerceAtLeast(1)))

internal data class OriginalPagePrefetchResult(
    val received: Boolean,
    val diskCacheKey: String? = null,
    val deferred: Boolean = false,
    val failure: Throwable? = null,
    val admissionDeferredStage: PrefetchStage? = null,
)

/** Original bytes only; a successful receipt is not an image validation result. */
internal suspend fun prefetchOriginalPageResult(
    context: PlatformContext, page: Page, headers: Map<String, String>,
    source: String, bytes: PrefetchByteBudget, work: PrefetchWork = PrefetchWork(),
): OriginalPagePrefetchResult {
    if (source == "jmcomic" || !page.url.startsWith("https:")) return OriginalPagePrefetchResult(false)
    val imageContext = imagePlatformContext(context)
    val request = readerPageRequest(context, page, headers, source)
        .prefetchPriority(bytes, source, work).size(1)
        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED)
        .decoderFactory(BlackholeDecoder.Factory()).build()
    val result = try { SingletonImageLoader.get(imageContext).execute(request) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: PrefetchBudgetExceeded) { return OriginalPagePrefetchResult(false, deferred = true) }
    catch (_: ExperienceAdmissionDeferred) { return OriginalPagePrefetchResult(false, deferred = true,
        admissionDeferredStage = work.snapshot().stage) }
    catch (_: OutOfMemoryError) { return OriginalPagePrefetchResult(false, deferred = true) }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is SuccessResult -> {
            if (result.dataSource != DataSource.NETWORK) {
                val cachedBytes = inspectReaderPreparedArtifacts(context, page, headers, source,
                    result.diskCacheKey).originalBytes
                if (cachedBytes != null) work.ifOptional {
                    try { bytes.accept(cachedBytes) }
                    catch (_: PrefetchBudgetExceeded) { bytes.exhaust() }
                }
            }
            // A real local hit remains usable even when the optional transfer window is full.
            OriginalPagePrefetchResult(true, result.diskCacheKey)
        }
        is ErrorResult -> {
            val failure = result.throwable
            if (failure is CancellationException) throw failure
            if (failure is PrefetchBudgetExceeded || failure is ExperienceAdmissionDeferred ||
                failure is PrefetchPromoted || failure is OutOfMemoryError)
                OriginalPagePrefetchResult(false, deferred = true,
                    admissionDeferredStage = work.snapshot().stage.takeIf { failure is ExperienceAdmissionDeferred })
            else OriginalPagePrefetchResult(false, failure = failure)
        }
    }
}

/** Compatibility for callers that only need to know whether the original bytes arrived. */
internal suspend fun prefetchOriginalPage(
    context: PlatformContext, page: Page, headers: Map<String, String>,
    source: String, bytes: PrefetchByteBudget,
): Boolean = prefetchOriginalPageResult(context, page, headers, source, bytes).received

internal enum class ReaderPagePreflightStatus { READY, UNVERIFIED, DEFERRED, FAILED }
internal data class ReaderPagePreflight(
    val status: ReaderPagePreflightStatus,
    val dimensions: IntSize? = null,
    val diskCacheKey: String? = null,
    val failure: Throwable? = null,
    val memoryCacheKey: MemoryCache.Key? = null,
)

/** A confirmed header for one exact cached artifact. A live file check is still required. */
internal data class ReaderOriginalStamp(val key: String, val path: Path, val bytes: Long, val modifiedAt: Long)

internal data class ReaderPreparedArtifacts(val originalPresent: Boolean?, val originalBytes: Long?,
    val previewResident: Boolean?, val originalStamp: ReaderOriginalStamp? = null)

/** Inspect this exact artifact locally. Do not execute an image request or touch a memory LRU entry. */
internal suspend fun inspectReaderPreparedArtifacts(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String,
    diskCacheKey: String? = null, memoryCacheKey: MemoryCache.Key? = null,
    previousOriginal: ReaderOriginalStamp? = null,
): ReaderPreparedArtifacts = withContext(Dispatchers.IO) {
    var preview: Boolean? = null
    try {
        val loader = SingletonImageLoader.get(imagePlatformContext(context))
        preview = memoryCacheKey?.let { key -> loader.memoryCache?.keys?.contains(key) }
        val key = diskCacheKey ?: readerPageCacheKey(page, headers, source)
        suspend fun inspect(fileSystem: FileSystem, path: Path): ReaderPreparedArtifacts {
            val metadata = fileSystem.metadataOrNull(path) ?: return ReaderPreparedArtifacts(false, null, preview)
            val bytes = metadata.size?.takeIf { metadata.isRegularFile && it > 0L }
                ?: return ReaderPreparedArtifacts(false, null, preview)
            val stamp = metadata.lastModifiedAtMillis?.takeIf { it > 0L }?.let { ReaderOriginalStamp(key, path, bytes, it) }
            if (stamp != null && stamp == previousOriginal) return ReaderPreparedArtifacts(true, bytes, preview, stamp)
            val dimensions = readNativeReaderSize(path.toString(), ExperiencePolicyStore.get(context).state.value.runtime)
            if (dimensions == null || dimensions.width <= 0 || dimensions.height <= 0)
                return ReaderPreparedArtifacts(false, null, preview)
            // A local writer may replace the file between header inspection and publication.
            val current = fileSystem.metadataOrNull(path)
            if (current == null || !current.isRegularFile || current.size != bytes ||
                current.lastModifiedAtMillis != metadata.lastModifiedAtMillis)
                return ReaderPreparedArtifacts(null, null, preview)
            ReaderDimensionCache.put(key, dimensions)
            return ReaderPreparedArtifacts(true, bytes, preview, stamp)
        }
        val local = readerFilePath(page.url)
        if (local != null) inspect(FileSystem.SYSTEM, StorageFile(local).nativePath)
        else loader.diskCache?.let { cache -> cache.openSnapshot(key)?.use { inspect(cache.fileSystem, it.data) } }
            ?: ReaderPreparedArtifacts(false, null, preview)
    } catch (cancelled: CancellationException) { throw cancelled }
    // Header/EXIF inspection is optional evidence, not permission to kill the
    // chapter scheduler or declare an existing original missing under pressure.
    catch (_: OutOfMemoryError) { ReaderPreparedArtifacts(null, null, preview) }
    catch (_: Exception) { ReaderPreparedArtifacts(null, null, preview) }
}
private class PrefetchCacheUnavailable : IOException("预取原图已不在缓存中")

private data class WarmPreviewKey(val identity: String, val size: Size)
private class WarmPreviewFlight(val work: PrefetchWork) {
    val done = CompletableDeferred<ReaderPagePreflight>()
}
private val warmPreviewLock = StorageLock()
private val warmPreviews = mutableMapOf<WarmPreviewKey, WarmPreviewFlight>()

/** Share an exact warm attempt; the foreground still lets Coil validate the resulting memory entry. */
internal suspend fun preflightReaderPage(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String,
    viewportWidthPx: Int, viewportHeightPx: Int, diskCacheKey: String? = null,
    warmMemory: Boolean = true, work: PrefetchWork = PrefetchWork(),
    maxWidthPx: Int? = null, maxHeightPx: Int? = null,
): ReaderPagePreflight {
    val runtime = ExperiencePolicyStore.get(context).state.value.runtime
    val previewWidth = maxWidthPx ?: runtime.previewWidthPx
    val previewHeight = maxHeightPx ?: runtime.previewHeightPx
    if (!warmMemory) return inspectReaderPage(context, page, headers, source,
        viewportWidthPx, viewportHeightPx, diskCacheKey, false, work, previewWidth, previewHeight)
    val size = readerPreviewSize(viewportWidthPx, viewportHeightPx, previewWidth, previewHeight)
    val key = WarmPreviewKey(readerPageCacheKey(page, headers, source), Size(size.width, size.height))
    var owner = false
    val flight = synchronized(warmPreviewLock) {
        warmPreviews[key] ?: WarmPreviewFlight(work).also { warmPreviews[key] = it; owner = true }
    }
    if (!owner) {
        if (work.isForeground) {
            flight.work.promote()
            return withTimeoutOrNull(readerPreviewJoinMillis(ExperiencePolicyStore.get(context))) { flight.done.await() }
                ?: ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED)
        }
        return flight.done.await()
    }
    try {
        return inspectReaderPage(context, page, headers, source, viewportWidthPx, viewportHeightPx,
            diskCacheKey, true, work, previewWidth, previewHeight).also { flight.done.complete(it) }
    } finally {
        // Cancellation/missing resources release waiters without poisoning this page or source.
        flight.done.complete(ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED))
        synchronized(warmPreviewLock) { if (warmPreviews[key] === flight) warmPreviews.remove(key) }
    }
}

private fun readerPreviewJoinMillis(policy: ExperiencePolicyStore): Long =
    policy.state.value.recovery.slowLoadSeconds.coerceAtLeast(1).toLong() * 1_000L

private suspend fun awaitWarmReaderPreview(request: ImageRequest, size: Size, policy: ExperiencePolicyStore) {
    if (request.extras[workKey] != ExperienceImageWork.READER || !request.memoryCachePolicy.readEnabled) return
    val identity = request.memoryCacheKey ?: return
    val flight = synchronized(warmPreviewLock) { warmPreviews[WarmPreviewKey(identity, size)] } ?: return
    flight.work.promote()
    // Sharing is an optimization, not an unbounded prerequisite for displaying the page.
    // Only this wait times out: its owner keeps its exact flight and decode permit. The
    // foreground then uses Coil's normal caches/admission; warm flights are cache-only,
    // so proceeding cannot duplicate a warm network fetch.
    withTimeoutOrNull(readerPreviewJoinMillis(policy)) { flight.done.await() }
}

/**
 * Inspect bytes already owned by this page. Far pages read headers only; only a real sampled
 * decode reports READY. Cache misses, local resource stops and cancellation are not source faults.
 * Even Coil's cache-control fallback is vetoed before transport, so inspection never downloads.
 */
private suspend fun inspectReaderPage(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String,
    viewportWidthPx: Int, viewportHeightPx: Int, diskCacheKey: String? = null,
    warmMemory: Boolean, work: PrefetchWork, maxWidthPx: Int, maxHeightPx: Int,
): ReaderPagePreflight {
    return try {
        inspectReaderPageBytes(context, page, headers, source, viewportWidthPx, viewportHeightPx,
            diskCacheKey, warmMemory, work, maxWidthPx, maxHeightPx)
    } catch (_: OutOfMemoryError) {
        // The surrounding warm flight still completes/releases its waiters. This
        // optional byte/decode inspection must not cancel the chapter scheduler.
        // Cancellation and other fatal errors are deliberately not caught here.
        ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, diskCacheKey = diskCacheKey)
    }
}

private suspend fun inspectReaderPageBytes(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String,
    viewportWidthPx: Int, viewportHeightPx: Int, diskCacheKey: String?,
    warmMemory: Boolean, work: PrefetchWork, maxWidthPx: Int, maxHeightPx: Int,
): ReaderPagePreflight {
    val policy = ExperiencePolicyStore.get(context)
    if (warmMemory && !policy.canDecodePrefetch() && !work.isForeground)
        return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED)
    val loader = SingletonImageLoader.get(imagePlatformContext(context))
    val key = diskCacheKey ?: readerPageCacheKey(page, headers, source)
    val local = readerFilePath(page.url)
    val remote = page.url.startsWith("https:") || page.url.startsWith("http:")
    if (local == null && !remote) return ReaderPagePreflight(ReaderPagePreflightStatus.UNVERIFIED)
    val retainedBytes = try { withContext(Dispatchers.IO) {
        if (local != null) StorageFile(local).takeIf { it.isFile }?.length()
        else loader.diskCache?.let { cache -> cache.openSnapshot(key)?.use { snapshot ->
            cache.fileSystem.metadata(snapshot.data).size
        } }
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    if (retainedBytes == null) return ReaderPagePreflight(ReaderPagePreflightStatus.UNVERIFIED, diskCacheKey = key)
    if (retainedBytes <= 0L) return ReaderPagePreflight(ReaderPagePreflightStatus.FAILED,
        diskCacheKey = key, failure = IOException("预取图片为空"))
    val dimensions = try { readerImageDimensions(loader, page, key, refresh = true, runtime = policy.state.value.runtime) }
    catch (_: OutOfMemoryError) { return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, diskCacheKey = key) }
    currentCoroutineContext().ensureActive()
    if (!warmMemory) return ReaderPagePreflight(ReaderPagePreflightStatus.UNVERIFIED, dimensions, key)
    if (!policy.canDecodePrefetch() && !work.isForeground)
        return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key)
    val size = readerPreviewSize(viewportWidthPx, viewportHeightPx, maxWidthPx, maxHeightPx)
    // Keep room for the visible spread and its detail tiles. Never shrink a warm preview to
    // a different quality/key just to make it fit: the foreground request owns that decision.
    val estimatedPixels = dimensions?.let { original ->
        val scale = minOf(1.0, size.width.toDouble() / original.width, size.height.toDouble() / original.height)
        kotlin.math.ceil(original.width * scale).toLong() * kotlin.math.ceil(original.height * scale).toLong()
    } ?: (size.width.toLong() * size.height)
    val decodeLimit = minOf((loader.memoryCache?.maxSize ?: 0L) / 4L, 24L * EXPERIENCE_MIB)
    val manualPreview = policy.overrides.value.let { it.decodedPrefetchPages != null || it.memoryMiB != null }
    if (!work.isForeground && !manualPreview && estimatedPixels * 4L > decodeLimit)
        return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key)
    val request = readerPageRequest(context, page, headers, source)
        .prefetchPriority(PrefetchByteBudget(0L), source, work)
        .apply { extras[cacheOnlyKey] = true }
        .size(size.width, size.height).scale(Scale.FIT).crossfade(false)
        .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.READ_ONLY)
        .networkCachePolicy(CachePolicy.DISABLED).build()
    val result = try { loader.execute(request) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: PrefetchBudgetExceeded) { return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key) }
    catch (_: ExperienceAdmissionDeferred) { return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key) }
    catch (_: OutOfMemoryError) { return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key) }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is SuccessResult -> if (result.image.width > 0 && result.image.height > 0)
            ReaderPagePreflight(ReaderPagePreflightStatus.READY, dimensions, result.diskCacheKey ?: key,
                memoryCacheKey = result.memoryCacheKey)
        else ReaderPagePreflight(ReaderPagePreflightStatus.FAILED, dimensions, key,
            IOException("预取图片尺寸无效"))
        is ErrorResult -> {
            val failure = result.throwable
            when (failure) {
                is CancellationException -> throw failure
                is PrefetchCacheUnavailable -> ReaderPagePreflight(ReaderPagePreflightStatus.UNVERIFIED, dimensions, key)
                is PrefetchBudgetExceeded, is ExperienceAdmissionDeferred, is PrefetchPromoted, is OutOfMemoryError ->
                    ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key)
                else -> ReaderPagePreflight(ReaderPagePreflightStatus.FAILED, dimensions, key, failure)
            }
        }
    }
}

/** The sole cache factory used by every platform entry, including Android Application. */
internal fun initializeExperienceImages(context: PlatformContext) {
    val app = applicationContext(context)
    val policy = ExperiencePolicyStore.get(app)
    DownloadRoutingStore.get(app)
    SingletonImageLoader.setSafe { imageContext ->
        // Resolve Coil's platform memory estimate once, then let the policy own the live LRU
        // size. A fixed backing maximum would silently truncate a later manual allocation.
        val automaticTotal = (MemoryCache.Builder().maxSizePercent(imageContext, .32).build().maxSize / .32).toLong()
        val memory = ExperienceMemoryCache(MemoryCache.Builder().maxSizeBytes(Long.MAX_VALUE)
            .weakReferencesEnabled(false).build(), automaticTotal)
        val storage = dev.mangaflow.storage.StorageCacheStore.get(app)
        val disk = ExperienceDiskCache(StorageFile(cacheDirectory(app), "image-cache").nativePath,
            policy.state.value.diskCacheBytes, policy::refreshCacheUsage, storage::cacheWorkFinished)
        storage.attach(disk)
        ImageLoader.Builder(imageContext)
            .components {
                add(NetworkFetcher.Factory(networkClient = { ExperienceImageNetworkClient(createExperienceNetworkClient(), policy) }))
                add(ExperienceDecoderFactory(policy))
                add(Interceptor { chain ->
                    val request = chain.request
                    val work = request.extras[workKey] ?: ExperienceImageWork.VISIBLE
                    val remote = request.data.toString().startsWith("https:") || request.data.toString().startsWith("http:")
                    val cacheKey = request.diskCacheKey ?: request.data?.toString().orEmpty()
                    if (remote && request.extras[cacheOnlyKey] != true)
                        storage.noteCoil(cacheKey, false)
                    // Neither a memory hit nor a local file waits behind network admission.
                    if (request.extras[cacheOnlyKey] != true) awaitWarmReaderPreview(request, chain.size, policy)
                    val result = chain.proceed()
                    if (remote && request.extras[cacheOnlyKey] != true && result is SuccessResult)
                        storage.noteCoil(result.diskCacheKey ?: cacheKey, work != ExperienceImageWork.PREFETCH)
                    result
                })
            }
            .memoryCache(memory).diskCache(disk)
            .eventListenerFactory { request -> ExperienceImageEvents(policy, request) }
            .crossfade(160)
            .build().also { policy.attachImageLoader(it, memory, disk) }
    }
}

/**
 * Decorate the pinned Coil decoder chain rather than keeping a permit through IO.
 * All options, format fallbacks, sampling and image ownership stay with Coil.
 */
private class ExperienceDecoderFactory(private val policy: ExperiencePolicyStore) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val ownIndex = imageLoader.components.decoderFactories.indexOfFirst { it === this }
        if (ownIndex < 0) return null
        val first = imageLoader.components.newDecoder(result, options, imageLoader, ownIndex + 1) ?: return null
        return Decoder {
            val optional = options.extras[workKey] == ExperienceImageWork.PREFETCH
            val work = options.extras[workTokenKey]
            suspend fun decode(): DecodeResult? = policy.withDecodeAdmission(optional,
                { work?.isForeground == true }, { work?.admission(PrefetchStage.CHECKING, it) },
                maxWaitMillis = if (options.extras[workKey] == ExperienceImageWork.READER && work?.isForeground == true)
                    policy.state.value.recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1_000L else null) {
                val started = monotonicTimeMillis()
                var decodedBytes = 0L
                var cancelled = false
                try {
                    var candidate: Pair<Decoder, Int>? = first
                    var decoded: DecodeResult? = null
                    while (candidate != null) {
                        currentCoroutineContext().ensureActive()
                        decoded = candidate.first.decode()
                        if (decoded != null) {
                            decodedBytes = decoded.image.size.coerceAtLeast(0L)
                            break
                        }
                        candidate = imageLoader.components.newDecoder(result, options, imageLoader, candidate.second + 1)
                    }
                    decoded
                } catch (stop: CancellationException) {
                    cancelled = true
                    throw stop
                } finally {
                    if (!cancelled) policy.recordDecode(monotonicTimeMillis() - started, decodedBytes,
                        optional && work?.isForeground != true)
                }
            }
            if (work != null) work.during(PrefetchStage.CHECKING) { decode() } else decode()
        }
    }
}

private class ExperienceImageEvents(
    private val policy: ExperiencePolicyStore, request: ImageRequest,
) : EventListener() {
    private val started = monotonicTimeMillis()
    private val key = request.data.toString()
    private val prefetch = request.extras[workKey] == ExperienceImageWork.PREFETCH
    private val work = request.extras[workTokenKey]
    private var bytes: Long? = null
    override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
        val source = (result as? SourceFetchResult)?.source ?: return
        bytes = runCatching { source.fileOrNull()?.let { source.fileSystem.metadata(it).size } }.getOrNull()
    }
    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val preparedImage = (request.extras[workKey] == ExperienceImageWork.READER ||
            request.extras[cacheOnlyKey] == true) && result.image.width > 0 && result.image.height > 0
        // Original-only prefetch uses BlackholeDecoder: its success is not a ready preview.
        work?.imageArtifact(result.diskCacheKey, result.memoryCacheKey, ready = preparedImage)
        if (request.extras[cacheOnlyKey] == true) return
        policy.recordImage(monotonicTimeMillis() - started, result.dataSource == DataSource.MEMORY_CACHE,
            result.dataSource == DataSource.DISK, prefetch && work?.isForeground != true, key, bytes)
    }
}

/**
 * Preserve each platform's actual HTTP transport. Only marked optional work gets a
 * streaming byte ceiling; foreground originals never inherit a failed prefetch's cap.
 */
private class ExperienceImageNetworkClient(
    private val delegate: ExperienceNetworkTransport, private val policy: ExperiencePolicyStore,
) : NetworkClient {
    private class SystemTransport(val value: ExperienceNetworkTransport, val timeouts: ExperienceImageTimeouts) {
        var users = 0
        var retired = false
    }
    private val transportLock = StorageLock()
    private var systemTransport = SystemTransport(delegate, ExperienceImageTimeouts())

    private fun acquireSystem(timeouts: ExperienceImageTimeouts): Pair<ExperienceNetworkTransport, () -> Unit> {
        val slot = synchronized(transportLock) {
            if (systemTransport.timeouts != timeouts) {
                val previous = systemTransport
                val replacement = SystemTransport(createExperienceNetworkClient(timeouts = timeouts), timeouts)
                previous.retired = true
                systemTransport = replacement
                if (previous.users == 0) previous.value.close()
            }
            systemTransport.also { it.users++ }
        }
        return slot.value to {
            synchronized(transportLock) {
                slot.users--
                if (slot.retired && slot.users == 0) slot.value.close()
            }
        }
    }

    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        // A cache-only inspection must never claim a source slot or start HTTP.
        if (request.extras[cacheOnlyKey] == true) throw PrefetchCacheUnavailable()
        val optional = request.extras[workKey] == ExperienceImageWork.PREFETCH
        val token = request.extras[workTokenKey]
        val source = request.extras[sourceKey] ?: experienceOrigin(request.url)
        suspend fun transfer(): T = performRequest(request, block)
        suspend fun scheduled(): T {
            val waiting: (AdmissionWait?) -> Unit = { token?.admission(PrefetchStage.DOWNLOADING, it) }
            return if (optional && token?.isForeground != true) {
                try {
                    val remaining = minOf(policy.state.value.prefetchBytes,
                        request.extras[bytesKey]?.remaining ?: Long.MAX_VALUE)
                    token?.ifOptional {
                        if (!policy.canWritePrefetch() || remaining <= 0L) throw PrefetchBudgetExceeded()
                    }
                    // Unknown images start with an estimate, not an observed minimum size.
                    // Admit a bounded attempt; response/body bytes enforce the real ceiling.
                    policy.withPrefetchAdmission(source,
                        minOf(policy.estimatedPageBytes(request.url), remaining.coerceAtLeast(0L)),
                        { token?.isForeground == true }, waiting) { transfer() }
                } catch (_: PrefetchPromoted) {
                    policy.withImageAdmission(reader = true, source = source, onWaiting = waiting) { transfer() }
                }
            } else policy.withImageAdmission(reader = request.extras[workKey] == ExperienceImageWork.READER ||
                token?.isForeground == true, source = source, onWaiting = waiting) { transfer() }
        }
        return if (token != null) token.during(PrefetchStage.DOWNLOADING) { scheduled() } else scheduled()
    }

    private suspend fun <T> performRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        val started = monotonicTimeMillis()
        val optional = request.extras[workKey] == ExperienceImageWork.PREFETCH
        val budget = request.extras[bytesKey]
        // Keep a failed/cancelled receipt from consuming the rest of this shared window.
        // Successful original bytes stay charged to the caller's window until it is released.
        val receipt = if (optional) budget?.child() else null
        var completed = false
        val token = request.extras[workTokenKey]
        val readerTransfer = request.extras[readerTransferKey]
        var received = 0L
        var accepted = 0L
        val resourceOwner = PrefetchResourceOwner()
        var code: Int? = null
        var retry: String? = null
        var failed = false
        var interrupted = false
        val selectedRoute = request.extras[sourceKey]?.let {
            DownloadRoutingStore.activeSourceSnapshot(it, request.extras[sourceCategoryKey])
        }
            ?: DownloadRoutingStore.activeDefaultSnapshot()
        val timeouts = policy.state.value.imageTimeouts()
        val owned = selectedRoute.takeIf { it.mode != DownloadRouteMode.SYSTEM }?.let { createExperienceNetworkClient(it, timeouts) }
        val system = if (owned == null) acquireSystem(timeouts) else null
        try {
            return (owned?.client ?: requireNotNull(system).first.client).executeRequest(request) responseBlock@{ response ->
                code = response.code
                retry = response.headers["Retry-After"]
                val body = response.body
                if (body == null) return@responseBlock block(response)
                val successfulBody = response.code in 200..299
                if (successfulBody) {
                    val encoding = response.headers["Content-Encoding"]?.trim()
                    val length = if (encoding.isNullOrEmpty() || encoding.equals("identity", true))
                        response.headers["Content-Length"]?.toLongOrNull() else null
                    readerTransfer?.beginBody(length)
                    token?.beginBody(length)
                }
                if (optional) {
                    requireNotNull(token).ifOptional {
                        if (!policy.canWritePrefetch()) throw PrefetchBudgetExceeded()
                        val length = response.headers["Content-Length"]?.toLongOrNull()
                        if (length != null && length > (budget?.remaining ?: 0L)) throw PrefetchBudgetExceeded()
                    }
                }
                val bounded = object : NetworkResponseBody {
                    override suspend fun writeTo(sink: BufferedSink) {
                        val counter = object : Sink {
                            override fun write(source: Buffer, byteCount: Long) {
                                if (optional) {
                                    requireNotNull(token).ifOptional {
                                        if (!policy.canWritePrefetch()) throw PrefetchBudgetExceeded()
                                        val windowReceipt = receipt ?: throw PrefetchBudgetExceeded()
                                        policy.acceptPrefetchChunk(byteCount, resourceOwner)
                                        try { windowReceipt.accept(byteCount) }
                                        catch (failure: Throwable) {
                                            policy.releasePrefetchBytes(byteCount, resourceOwner)
                                            throw failure
                                        }
                                        accepted += byteCount
                                    }
                                }
                                sink.write(source, byteCount)
                                if (byteCount > 0L) {
                                    received += byteCount
                                    if (successfulBody) { readerTransfer?.onBytes(byteCount); token?.onBytes(byteCount) }
                                }
                            }
                            override fun flush() = sink.flush()
                            override fun timeout(): Timeout = sink.timeout()
                            override fun close() = sink.close()
                        }.buffer()
                        body.writeTo(counter)
                        counter.flush()
                    }
                    override suspend fun writeTo(fileSystem: FileSystem, path: Path) {
                        val sink = fileSystem.sink(path).buffer()
                        try { writeTo(sink) } finally { sink.close() }
                    }
                    override fun close() = body.close()
                }
                block(response.copy(body = bounded))
            }.also { completed = true }
        } catch (cancelled: CancellationException) {
            interrupted = true
            throw cancelled
        } catch (stopped: PrefetchBudgetExceeded) {
            interrupted = true
            throw stopped
        } catch (failure: Exception) {
            failed = true
            throw failure
        } finally {
            owned?.close?.invoke()
            system?.second?.invoke()
            if (accepted > 0L) policy.releasePrefetchBytes(accepted, resourceOwner)
            if (!completed) receipt?.close()
            // Cancellations and local budget stops do not count as a slow/failing source.
            if ((!interrupted || code == 429 || code == 503) && (code != null || failed)) policy.recordTransfer(request.extras[sourceKey] ?: experienceOrigin(request.url), code,
                received, monotonicTimeMillis() - started, retry, failed)
        }
    }
}
