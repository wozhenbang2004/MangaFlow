package dev.mangaflow.reader

import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.CachePolicy
import coil3.size.Scale
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.plugins.cachedScriptReaderPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.invalidateScriptImage
import dev.mangaflow.data.sources.cachedJmReaderPage
import dev.mangaflow.data.sources.materializeJmPage
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.readerPageRequest
import dev.mangaflow.experience.readerPreparationWork
import dev.mangaflow.experience.readerPreviewSize
import dev.mangaflow.experience.withPrefetchImageInactivity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.storage.DownloadPhase
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.StorageCacheStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads keep first claim on a page; private source caches can satisfy the remaining request locally. */
internal suspend fun resolveReaderPrefetchPage(
    context: PlatformContext, downloads: DownloadStore, workId: String, owner: String,
    chapterId: String, sourceId: String, page: Page,
): Page? {
    val candidate = downloads.resolvePrefetchPage(workId, chapterId, page) ?: return null
    if (candidate.url.startsWith("file:") ||
        candidate.sourceRouteId.isNotBlank() && candidate.sourceRouteId != sourceId) return candidate
    return when {
        sourceId == "jmcomic" -> cachedJmReaderPage(context, owner, chapterId, candidate) ?: candidate
        sourceId.startsWith("js_") -> cachedScriptReaderPage(context, owner, chapterId, candidate) ?: candidate
        else -> candidate
    }
}

/**
 * One foreground target preparation path for an exact single-page repair and a whole-source switch.
 * Existing downloads, script/native materialization and Coil retain transport/cache ownership.
 * Returning means a real image decoded successfully; the caller still owns manifest/session CAS.
 */
internal suspend fun prepareReaderSourcePage(
    context: PlatformContext,
    workId: String,
    variant: ChapterVariant,
    page: Page,
    headers: Map<String, String>,
    downloads: DownloadStore,
    viewportWidth: Int,
    viewportHeight: Int,
    retry: Boolean = false,
    onPreview: ((Image) -> Unit)? = null,
): Page {
    val policy = ExperiencePolicyStore.get(context)
    val storage = StorageCacheStore.get(context)
    val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
    val token = PrefetchWork(policy::wakeAdmissions).also { it.promote() }
    val release = storage.protectReading(workId, variant.storageId, owner)
    try {
        return withPrefetchImageInactivity(token,
            policy.state.value.recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L) {
            storage.registerPages(workId, variant.storageId, variant.sourceId, listOf(page), headers)
            withContext(ReaderPreparationWorkContext(token)) {
                if (retry) invalidateScriptImage(owner, variant.storageId, page.sourceImageToken)
                val local = resolvePreparationDownload(downloads, workId, variant, page, token, retry)
                val native = if (local.url.startsWith("file:")) local else {
                    val script = resolveScriptPage(context, owner, variant.storageId, local, work = token)
                    val materialized = materializeScriptPage(context, owner, variant.storageId, script, work = token, retry = retry)
                    if (variant.sourceId == "jmcomic")
                        materializeJmPage(context, owner, variant.storageId, materialized, retry = retry) ?: materialized
                    else materialized
                }
                currentCoroutineContext().ensureActive()
                storage.registerPages(workId, variant.storageId, variant.sourceId, listOf(native), headers)
                val runtime = policy.state.value.runtime
                val size = readerPreviewSize(viewportWidth, viewportHeight,
                    maxWidthPx = runtime.previewWidthPx, maxHeightPx = runtime.previewHeightPx)
                when (val result = SingletonImageLoader.get(imagePlatformContext(context)).execute(
                    readerPageRequest(context, native, headers, variant.sourceId).size(size.width, size.height)
                        .apply { if (retry && (native.url.startsWith("https:") || native.url.startsWith("http:"))) {
                            diskCachePolicy(CachePolicy.WRITE_ONLY); memoryCachePolicy(CachePolicy.DISABLED)
                        } }
                        .scale(Scale.FIT).readerPreparationWork(token).build())) {
                    is SuccessResult -> {
                        require(result.image.width > 0 && result.image.height > 0) { "备用图片尚不可读" }
                        currentCoroutineContext().ensureActive()
                        onPreview?.invoke(result.image)
                    }
                    is ErrorResult -> throw result.throwable
                }
                native
            }
        }
    } finally { release() }
}

/** The exact existing download's real phases and positive bytes extend preparation's idle clock. */
private suspend fun resolvePreparationDownload(
    downloads: DownloadStore, workId: String, variant: ChapterVariant, page: Page, token: PrefetchWork, retry: Boolean,
): Page = coroutineScope {
    token.queued(PrefetchStage.RESOLVING)
    val observation = launch {
        var transfer: String? = null
        var bytes = 0L
        downloads.observePageTransfer(workId, variant.storageId, page.index, expectedPage = page).collect { value ->
            if (value == null) return@collect
            val identity = "${value.workId}:${value.generation}:${value.transferId}"
            if (identity != transfer) { transfer = identity; bytes = value.transferredBytes; token.beginBody(value.expectedBytes) }
            when (value.phase) {
                DownloadPhase.CONNECTING, DownloadPhase.FIRST_BYTE_WAIT, DownloadPhase.TRANSFERRING ->
                    token.admission(PrefetchStage.DOWNLOADING, null)
                DownloadPhase.VERIFYING, DownloadPhase.COMMITTING, DownloadPhase.COMPLETED -> token.queued(PrefetchStage.CHECKING)
                else -> token.queued(PrefetchStage.QUEUED)
            }
            val received = (value.transferredBytes - bytes).coerceAtLeast(0L)
            if (received > 0L) token.onBytes(received)
            bytes = value.transferredBytes
        }
    }
    try { if (retry) downloads.retryReaderPage(workId, variant.storageId, page)
        else downloads.resolveReaderPage(workId, variant.storageId, page) }
    finally { observation.cancelAndJoin(); token.queued(PrefetchStage.CHECKING) }
}
