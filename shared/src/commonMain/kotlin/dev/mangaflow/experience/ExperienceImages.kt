@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package dev.mangaflow.experience

import coil3.EventListener
import coil3.Extras
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.BlackholeDecoder
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
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.cacheDirectory
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import okio.*

internal expect fun createExperienceNetworkClient(): NetworkClient
internal enum class ExperienceImageWork { VISIBLE, READER, PREFETCH }
private val workKey = Extras.Key(ExperienceImageWork.VISIBLE)
private val bytesKey = Extras.Key<PrefetchByteBudget?>(null)
private val sourceKey = Extras.Key<String?>(null)
private val workTokenKey = Extras.Key<PrefetchWork?>(null)

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

/** Original bytes only. Native decrypted sources keep foreground materialization ownership. */
internal suspend fun prefetchOriginalPage(
    context: PlatformContext, page: dev.mangaflow.data.Page, headers: Map<String, String>,
    source: String, bytes: PrefetchByteBudget,
): Boolean {
    if (source == "jmcomic" || !page.url.startsWith("https:")) return false
    val imageContext = imagePlatformContext(context)
    val request = ImageRequest.Builder(imageContext).data(page.url)
        .httpHeaders(coil3.network.NetworkHeaders.Builder().apply {
            headers.forEach { (name, value) -> set(name, value) }
        }.build())
        .prefetchPriority(bytes, source).size(1)
        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED)
        .decoderFactory(BlackholeDecoder.Factory()).build()
    return SingletonImageLoader.get(imageContext).execute(request) is SuccessResult
}

/** The sole cache factory used by every platform entry, including Android Application. */
internal fun initializeExperienceImages(context: PlatformContext) {
    val app = applicationContext(context)
    val policy = ExperiencePolicyStore.get(app)
    SingletonImageLoader.setSafe { imageContext ->
        val memory = ExperienceMemoryCache(MemoryCache.Builder().maxSizePercent(imageContext, .32)
            .weakReferencesEnabled(false).build())
        val disk = ExperienceDiskCache(StorageFile(cacheDirectory(app), "image-cache").nativePath,
            policy.state.value.diskCacheBytes, policy::refreshCacheUsage)
        ImageLoader.Builder(imageContext)
            .components {
                add(NetworkFetcher.Factory(networkClient = { ExperienceImageNetworkClient(createExperienceNetworkClient(), policy) }))
                add(Interceptor { chain ->
                    val request = chain.request
                    val work = request.extras[workKey] ?: ExperienceImageWork.VISIBLE
                    val remote = request.data.toString().startsWith("https:") || request.data.toString().startsWith("http:")
                    if (!remote) chain.proceed()
                    else if (work == ExperienceImageWork.PREFETCH) {
                        val token = request.extras[workTokenKey]
                        if (token?.isForeground == true) policy.withImageAdmission(true) { chain.proceed() }
                        else try {
                            token?.ifOptional { if (!policy.canWritePrefetch()) throw PrefetchBudgetExceeded() }
                            policy.withPrefetchAdmission(request.extras[sourceKey] ?: experienceOrigin(request.data.toString()),
                                policy.estimatedPageBytes(request.data.toString()), { token?.isForeground == true }) { chain.proceed() }
                        } catch (_: PrefetchPromoted) {
                            policy.withImageAdmission(true) { chain.proceed() }
                        }
                    } else policy.withImageAdmission(work == ExperienceImageWork.READER) { chain.proceed() }
                })
            }
            .memoryCache(memory).diskCache(disk)
            .eventListenerFactory { request -> ExperienceImageEvents(policy, request) }
            .crossfade(160)
            .build().also { policy.attachImageLoader(it, memory, disk) }
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
        policy.recordImage(monotonicTimeMillis() - started, result.dataSource == DataSource.MEMORY_CACHE,
            result.dataSource == DataSource.DISK, prefetch && work?.isForeground != true, key, bytes)
    }
}

/**
 * Preserve each platform's actual HTTP transport. Only marked optional work gets a
 * streaming byte ceiling; foreground originals never inherit a failed prefetch's cap.
 */
private class ExperienceImageNetworkClient(
    private val delegate: NetworkClient, private val policy: ExperiencePolicyStore,
) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        val started = monotonicTimeMillis()
        val optional = request.extras[workKey] == ExperienceImageWork.PREFETCH
        val budget = request.extras[bytesKey]
        val token = request.extras[workTokenKey]
        var received = 0L
        var accepted = 0L
        var code: Int? = null
        var retry: String? = null
        var failed = false
        var interrupted = false
        try {
            return delegate.executeRequest(request) responseBlock@{ response ->
                code = response.code
                retry = response.headers["Retry-After"]
                val body = response.body
                if (body == null) return@responseBlock block(response)
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
                                received += byteCount
                                sink.write(source, byteCount)
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
            if (accepted > 0L) policy.releasePrefetchBytes(accepted)
            // Cancellations and local budget stops do not count as a slow/failing source.
            if ((!interrupted || code == 429 || code == 503) && (code != null || failed)) policy.recordTransfer(request.extras[sourceKey] ?: experienceOrigin(request.url), code,
                received, monotonicTimeMillis() - started, retry, failed)
        }
    }
}
