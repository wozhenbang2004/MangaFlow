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
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.sha256
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
import okio.*

internal class ExperienceNetworkTransport(val client: NetworkClient, val close: () -> Unit)
internal expect fun createExperienceNetworkClient(route: DownloadRouteSnapshot? = null): ExperienceNetworkTransport
internal enum class ExperienceImageWork { VISIBLE, READER, PREFETCH }
private val workKey = Extras.Key(ExperienceImageWork.VISIBLE)
private val bytesKey = Extras.Key<PrefetchByteBudget?>(null)
private val sourceKey = Extras.Key<String?>(null)
private val workTokenKey = Extras.Key<PrefetchWork?>(null)
private val readerTransferKey = Extras.Key<ReaderTransferObserver?>(null)
private val cacheOnlyKey = Extras.Key(false)

/** Only successfully written body bytes advance this monotonic foreground inactivity clock. */
internal data class ReaderTransferSnapshot(
    val receivedBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val lastProgressAt: Long? = null,
)

/** Owned by one reader attempt, including any native/script image materialization before Coil. */
internal class ReaderTransferObserver {
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
}

/** Ownership can promote one displayed page without lifting the rest of its byte window. */
internal class PrefetchWork(private val promoted: () -> Unit = {}) {
    private val lock = StorageLock()
    private val foreground = atomic(false)
    val isForeground: Boolean get() = foreground.value
    fun promote() {
        val changed = synchronized(lock) { foreground.compareAndSet(false, true) }
        if (changed) promoted()
    }
    fun ifOptional(block: () -> Unit) = synchronized(lock) { if (!foreground.value) block() }
}
internal class PrefetchPromoted : RuntimeException()

/** Explicit source identity, never inferred from a shared CDN host. */
internal fun ImageRequest.Builder.sourceNetwork(source: String) = apply { extras[sourceKey] = source }

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

/** Held for a whole future-page window, including completed requests, not recycled per page. */
internal class PrefetchByteBudget(private val maximum: Long) {
    private val lock = StorageLock()
    private var spent = 0L
    val remaining: Long get() = synchronized(lock) { (maximum - spent).coerceAtLeast(0L) }
    fun exhaust() = synchronized(lock) { spent = maximum }
    fun accept(count: Long) = synchronized(lock) {
        if (count < 0L || count > maximum - spent) throw PrefetchBudgetExceeded()
        spent += count
    }
}
internal class PrefetchBudgetExceeded : IOException("本次预取已达到资源预算")
internal fun experienceOrigin(url: String) = url.toHttpUrlOrNull()?.host ?: "local"

private val publicImageHeaders = setOf("referer", "user-agent", "accept", "accept-language", "accept-encoding", "origin")

private fun readerPageHeaders(page: Page, headers: Map<String, String>): Map<String, String> = buildMap {
    headers.forEach { (name, value) -> put(name.lowercase(), value) }
    page.headers.forEach { (name, value) -> put(name.lowercase(), value) }
}

/** Protected images cannot reuse an old session's mutable URL or credential-bearing cache entry. */
internal fun readerPageCacheKey(page: Page, headers: Map<String, String>, source: String = ""): String {
    val merged = readerPageHeaders(page, headers)
    val scoped = page.sourceRevision.isNotBlank() || page.sourceSessionEpoch.isNotBlank() ||
        page.sourceImageToken.isNotBlank() || page.headerSecretRef.isNotBlank() ||
        page.headerSecretBinding.isNotBlank() || page.transform.isNotBlank() ||
        merged.keys.any { it !in publicImageHeaders }
    if (!scoped) return page.url
    // Length prefixes retain exact boundaries; only the digest is retained in cache keys.
    val identity = buildString {
        fun field(value: String) { append(value.length).append(':').append(value) }
        field("reader-owned-v1"); field(source); field(page.url); field(page.sourceRevision)
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
    val key = readerPageCacheKey(page, headers, source)
    if (page.url.startsWith("file:")) dev.mangaflow.storage.StorageCacheStore.get(context).noteMemoryFile(page.url, key)
    return ImageRequest.Builder(imagePlatformContext(context)).data(page.url)
        .readerPriority(source.takeIf { it.isNotBlank() })
        .memoryCacheKey(key).diskCacheKey(key)
        .httpHeaders(coil3.network.NetworkHeaders.Builder().apply {
            readerPageHeaders(page, headers).forEach { (name, value) -> set(name, value) }
        }.build())
}

/** Preview-only bound; original-file dimensions and full-detail tiles are never reduced. */
internal fun readerPreviewSize(widthPx: Int, heightPx: Int): IntSize =
    IntSize(widthPx.coerceIn(1, 1600), heightPx.coerceIn(1, 4096))

internal data class OriginalPagePrefetchResult(
    val received: Boolean,
    val diskCacheKey: String? = null,
    val deferred: Boolean = false,
    val failure: Throwable? = null,
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
    catch (_: ExperienceAdmissionDeferred) { return OriginalPagePrefetchResult(false, deferred = true) }
    catch (_: OutOfMemoryError) { return OriginalPagePrefetchResult(false, deferred = true) }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is SuccessResult -> {
            if (result.dataSource != DataSource.NETWORK) try {
                bytes.accept(ExperiencePolicyStore.get(context).estimatedPageBytes(page.url))
            } catch (_: PrefetchBudgetExceeded) {
                bytes.exhaust()
                return OriginalPagePrefetchResult(false, result.diskCacheKey, deferred = true)
            }
            OriginalPagePrefetchResult(true, result.diskCacheKey)
        }
        is ErrorResult -> {
            val failure = result.throwable
            if (failure is CancellationException) throw failure
            if (failure is PrefetchBudgetExceeded || failure is ExperienceAdmissionDeferred ||
                failure is PrefetchPromoted || failure is OutOfMemoryError)
                OriginalPagePrefetchResult(false, deferred = true)
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
)
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
): ReaderPagePreflight {
    if (!warmMemory) return inspectReaderPage(context, page, headers, source,
        viewportWidthPx, viewportHeightPx, diskCacheKey, false, work)
    val size = readerPreviewSize(viewportWidthPx, viewportHeightPx)
    val key = WarmPreviewKey(readerPageCacheKey(page, headers, source), Size(size.width, size.height))
    var owner = false
    val flight = synchronized(warmPreviewLock) {
        warmPreviews[key] ?: WarmPreviewFlight(work).also { warmPreviews[key] = it; owner = true }
    }
    if (!owner) {
        if (work.isForeground) flight.work.promote()
        return flight.done.await()
    }
    try {
        return inspectReaderPage(context, page, headers, source, viewportWidthPx, viewportHeightPx,
            diskCacheKey, true, work).also { flight.done.complete(it) }
    } finally {
        // Cancellation/missing resources release waiters without poisoning this page or source.
        flight.done.complete(ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED))
        synchronized(warmPreviewLock) { if (warmPreviews[key] === flight) warmPreviews.remove(key) }
    }
}

private suspend fun awaitWarmReaderPreview(request: ImageRequest, size: Size) {
    if (request.extras[workKey] != ExperienceImageWork.READER || !request.memoryCachePolicy.readEnabled) return
    val identity = request.memoryCacheKey ?: return
    val flight = synchronized(warmPreviewLock) { warmPreviews[WarmPreviewKey(identity, size)] } ?: return
    flight.work.promote()
    flight.done.await()
}

/**
 * Inspect bytes already owned by this page. Far pages read headers only; only a real sampled
 * decode reports READY. Cache misses, local resource stops and cancellation are not source faults.
 * Even Coil's cache-control fallback is vetoed before transport, so inspection never downloads.
 */
private suspend fun inspectReaderPage(
    context: PlatformContext, page: Page, headers: Map<String, String>, source: String,
    viewportWidthPx: Int, viewportHeightPx: Int, diskCacheKey: String? = null,
    warmMemory: Boolean = true, work: PrefetchWork = PrefetchWork(),
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
    val dimensions = try { readerImageDimensions(loader, page, key, refresh = true) }
    catch (_: OutOfMemoryError) { return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, diskCacheKey = key) }
    currentCoroutineContext().ensureActive()
    if (!warmMemory) return ReaderPagePreflight(ReaderPagePreflightStatus.UNVERIFIED, dimensions, key)
    if (!policy.canDecodePrefetch() && !work.isForeground)
        return ReaderPagePreflight(ReaderPagePreflightStatus.DEFERRED, dimensions, key)
    val size = readerPreviewSize(viewportWidthPx, viewportHeightPx)
    // Keep room for the visible spread and its detail tiles. Never shrink a warm preview to
    // a different quality/key just to make it fit: the foreground request owns that decision.
    val estimatedPixels = dimensions?.let { original ->
        val scale = minOf(1.0, size.width.toDouble() / original.width, size.height.toDouble() / original.height)
        kotlin.math.ceil(original.width * scale).toLong() * kotlin.math.ceil(original.height * scale).toLong()
    } ?: (size.width.toLong() * size.height)
    val decodeLimit = minOf((loader.memoryCache?.maxSize ?: 0L) / 4L, 24L * EXPERIENCE_MIB)
    if (!work.isForeground && estimatedPixels * 4L > decodeLimit)
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
            ReaderPagePreflight(ReaderPagePreflightStatus.READY, dimensions, result.diskCacheKey ?: key)
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
        val memory = ExperienceMemoryCache(MemoryCache.Builder().maxSizePercent(imageContext, .32)
            .weakReferencesEnabled(false).build())
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
                    if (request.extras[cacheOnlyKey] != true) awaitWarmReaderPreview(request, chain.size)
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
            policy.withDecodeAdmission(optional, { work?.isForeground == true }) {
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
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        // A cache-only inspection must never claim a source slot or start HTTP.
        if (request.extras[cacheOnlyKey] == true) throw PrefetchCacheUnavailable()
        val optional = request.extras[workKey] == ExperienceImageWork.PREFETCH
        val token = request.extras[workTokenKey]
        val source = request.extras[sourceKey] ?: experienceOrigin(request.url)
        suspend fun transfer(): T = performRequest(request, block)
        return if (optional && token?.isForeground != true) {
            try {
                token?.ifOptional { if (!policy.canWritePrefetch()) throw PrefetchBudgetExceeded() }
                policy.withPrefetchAdmission(source,
                    policy.estimatedPageBytes(request.url), { token?.isForeground == true }) { transfer() }
            } catch (_: PrefetchPromoted) {
                policy.withImageAdmission(reader = true, source = source) { transfer() }
            }
        } else policy.withImageAdmission(reader = request.extras[workKey] == ExperienceImageWork.READER ||
            token?.isForeground == true, source = source) { transfer() }
    }

    private suspend fun <T> performRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        val started = monotonicTimeMillis()
        val optional = request.extras[workKey] == ExperienceImageWork.PREFETCH
        val budget = request.extras[bytesKey]
        val token = request.extras[workTokenKey]
        val readerTransfer = request.extras[readerTransferKey]
        var received = 0L
        var accepted = 0L
        var code: Int? = null
        var retry: String? = null
        var failed = false
        var interrupted = false
        val selectedRoute = request.extras[sourceKey]?.let { DownloadRoutingStore.activeSourceSnapshot(it) }
        val owned = selectedRoute?.takeIf { it.mode != DownloadRouteMode.SYSTEM }?.let(::createExperienceNetworkClient)
        try {
            return (owned?.client ?: delegate.client).executeRequest(request) responseBlock@{ response ->
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
                                        budget?.accept(byteCount) ?: throw PrefetchBudgetExceeded()
                                        policy.acceptPrefetchChunk(byteCount)
                                        accepted += byteCount
                                    }
                                }
                                sink.write(source, byteCount)
                                if (byteCount > 0L) {
                                    received += byteCount
                                    if (successfulBody) readerTransfer?.onBytes(byteCount)
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
            }
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
            if (accepted > 0L) policy.releasePrefetchBytes(accepted)
            // Cancellations and local budget stops do not count as a slow/failing source.
            if ((!interrupted || code == 429 || code == 503) && (code != null || failed)) policy.recordTransfer(request.extras[sourceKey] ?: experienceOrigin(request.url), code,
                received, monotonicTimeMillis() - started, retry, failed)
        }
    }
}
