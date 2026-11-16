package dev.mangaflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import dev.mangaflow.data.CHAPTER_DIMENSION_SAMPLE_LIMIT
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.experience.readerPageRequest
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.reader.ReaderDimensionCache
import dev.mangaflow.reader.readerFilePath
import dev.mangaflow.reader.readerImageDimensions
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class ChapterResolutionState(
    val compactLabel: String,
    val detail: String,
    val known: Boolean,
    val busy: Boolean,
    val error: String?,
    val canMeasure: Boolean,
    val measure: () -> Unit,
)

private val resolutionSamples = Semaphore(2)

/**
 * Source-qualified metadata and original-byte headers only. Visible source rows may
 * read one original page after their disclosure settles, sharing Coil's normal cache.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun rememberChapterResolution(
    repository: MangaRepository?,
    mangaId: String,
    chapterId: String,
    entry: DownloadEntry? = null,
    declaredPages: Int? = null,
    autoMeasure: Boolean = false,
    active: Boolean = true,
): ChapterResolutionState {
    val context = LocalMangaContext.current
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val experienceBudget by experience.state.collectAsStateWithLifecycle()
    val downloads = remember(context) { DownloadStore.get(context) }
    val loader = remember(context) { SingletonImageLoader.get(imagePlatformContext(context)) }
    val sourceId = remember(mangaId, chapterId) {
        if (mangaId.startsWith("local:") || chapterId.startsWith("local:")) null
        else runCatching { SourceKeys.decodeChapter(mangaId, chapterId).sourceId }.getOrNull()
    }
    var imageOwner by remember(repository, mangaId, chapterId) { mutableStateOf<ChapterImageOwner?>(null) }
    var ownerRequest by remember(repository, mangaId, chapterId) { mutableIntStateOf(0) }
    val revision by ReaderDimensionCache.revision.collectAsStateWithLifecycle()
    val exactEntry = entry?.takeIf { it.chapter.id == chapterId }
    val retained = remember(repository, mangaId, chapterId, exactEntry?.id,
        exactEntry?.completedPages, exactEntry?.status, revision) {
        exactEntry?.let { downloads.record(it.id) }
            ?.takeIf { it.entry.chapter.id == chapterId }?.pages?.takeIf { it.isNotEmpty() }
            ?: runCatching { repository?.cachedPages(mangaId, chapterId) }.getOrNull()
    }
    var pages by remember(repository, mangaId, chapterId) { mutableStateOf(retained.orEmpty()) }
    var busy by remember(repository, mangaId, chapterId) { mutableStateOf(false) }
    var error by remember(repository, mangaId, chapterId) { mutableStateOf<String?>(null) }
    var automaticAttempted by remember(repository, mangaId, chapterId) { mutableStateOf(false) }
    var measurementRequest by remember(repository, mangaId, chapterId) { mutableIntStateOf(0) }
    var handledRequest by remember(repository, mangaId, chapterId) { mutableIntStateOf(0) }
    var measurementGeneration by remember(repository, mangaId, chapterId) { mutableIntStateOf(0) }

    LaunchedEffect(repository, mangaId, chapterId, retained, exactEntry?.completedPages, active) {
        if (!active) return@LaunchedEffect
        val request = ++ownerRequest
        try {
            if (!retained.isNullOrEmpty()) pages = retained
            else if (exactEntry != null) {
                downloads.storedPages(exactEntry.manga.id, chapterId)?.let { pages = it }
            }
            val localFiles = mangaId.startsWith("local:") && pages.isNotEmpty() &&
                pages.all { readerFilePath(it.url) != null }
            val owner = if (localFiles) ChapterImageOwner("local", emptyMap())
                else readChapterImageOwner(repository, mangaId, chapterId, sourceId)
            if (request != ownerRequest) return@LaunchedEffect
            imageOwner = owner
            if (owner == null) return@LaunchedEffect
            // At most three cached-file headers; no request, pixel decoding or chapter scan.
            val samples = pages.take(3)
            for (page in samples) readerImageDimensions(loader, page, owner.identity(page),
                runtime = experienceBudget.runtime)
            if (exactEntry != null && samples.isNotEmpty()) {
                val cover = downloads.coverPage(exactEntry.manga.id, chapterId)
                val original = samples.first()
                if (cover != null && cover.index == original.index && readerFilePath(cover.url) != null && original.transform.isBlank()) {
                    readerImageDimensions(loader, cover, owner.identity(cover), originalPage = original,
                        originalCacheIdentity = owner.identity(original), runtime = experienceBudget.runtime)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A missing/cleaned local cache leaves explicit unknown dimensions.
            if (request == ownerRequest) imageOwner = null
        }
    }

    val inspected = remember(pages, revision, imageOwner) {
        pages.asSequence().take(CHAPTER_DIMENSION_SAMPLE_LIMIT)
            .filter { it.index >= 0 }.distinctBy(Page::index).mapNotNull { page ->
                val measured = imageOwner?.identity(page)?.let(ReaderDimensionCache::get)
                val size = measured ?: page.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { IntSize(it.width, it.height) }
                size?.let { ResolutionSample(page.index, it, measured != null) }
            }.toList()
    }
    val primary = inspected.firstOrNull { it.measured } ?: inspected.firstOrNull()
    val count = pages.size.takeIf { it > 0 }
        ?: exactEntry?.totalPages?.takeIf { it > 0 }
        ?: declaredPages?.takeIf { it > 0 }
    val coverage = if (count != null) "已知" + inspected.size + "/" + count + "页"
        else "已知" + inspected.size + "页，整章页数未知"
    val detail = if (primary == null) {
        listOfNotNull(count?.let { "本章" + it + "页" },
            "尺寸未获取；仅读取一页原图尺寸，不代表整章画质。").joinToString(" · ")
    } else {
        val examples = inspected.take(3).joinToString("；") {
            "第" + (it.index + 1) + "页 " + resolutionPair(it.size) + " px" +
                if (it.measured) "（原图实测）" else "（来源标注）"
        }
        examples + " · " + coverage + " · 仅代表已读取页面的像素尺寸"
    }
    // Closing or changing the visible source cancels this effect and its queued/network work.
    // A cache revision never restarts a failed request; retry remains an explicit action.
    LaunchedEffect(repository, mangaId, chapterId, active, autoMeasure, measurementRequest) {
        val generation = measurementGeneration + 1
        measurementGeneration = generation
        if (!active || repository == null || sourceId == null) {
            busy = false
            return@LaunchedEffect
        }
        val requested = measurementRequest
        val manual = requested > handledRequest
        if (!manual && (!autoMeasure || automaticAttempted || primary != null)) return@LaunchedEffect
        busy = true
        error = null
        try {
            withTimeout(30_000L) {
                resolutionSamples.withPermit {
                    val loaded = pages.takeIf { it.isNotEmpty() }
                        ?: repository.pages(mangaId, chapterId)
                    if (loaded.isEmpty()) throw IllegalStateException()
                    // Reading the manifest can replace chapter headers. One fresh snapshot owns
                    // both the request and its original-dimension alias, never the old composition.
                    val request = ++ownerRequest
                    val owner = readChapterImageOwner(repository, mangaId, chapterId, sourceId)
                    if (request == ownerRequest) imageOwner = owner
                    requireNotNull(owner) { "当前来源的图片身份不可用。" }
                    pages = loaded
                    // Recheck after queuing: another consumer may have filled this exact cache.
                    if (!manual && loaded.asSequence().take(CHAPTER_DIMENSION_SAMPLE_LIMIT)
                        .filter { it.index >= 0 }.any {
                            ReaderDimensionCache.get(owner.identity(it)) != null || it.width > 0 && it.height > 0
                        }) return@withPermit
                    val page = loaded.asSequence().take(CHAPTER_DIMENSION_SAMPLE_LIMIT)
                        .firstOrNull { ReaderDimensionCache.get(owner.identity(it)) == null }
                        ?: loaded.first()
                    var size = readerImageDimensions(loader, page, owner.identity(page),
                        runtime = experienceBudget.runtime)
                    if (size == null && exactEntry != null && page.index == loaded.first().index) {
                        val local = downloads.coverPage(exactEntry.manga.id, chapterId)
                        if (local != null && local.index == page.index && readerFilePath(local.url) != null && page.transform.isBlank())
                            size = readerImageDimensions(loader, local, owner.identity(local), originalPage = page,
                                originalCacheIdentity = owner.identity(page), runtime = experienceBudget.runtime)
                    }
                    if (size == null) {
                        val acquired = resolveScriptPage(context, mangaId, chapterId, page)
                        val resolved = materializeScriptPage(context, mangaId, chapterId, acquired)
                        val headers = owner.headers + resolved.headers
                        val result = withContext(Dispatchers.IO) {
                            loader.execute(readerPageRequest(context, resolved, headers, owner.sourceId)
                                .size(1)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .decoderFactory(BlackholeDecoder.Factory())
                                .build())
                        }
                        if (result !is SuccessResult) throw IllegalStateException()
                        val original = page.takeIf { it.transform.isBlank() && acquired.transform.isBlank() }
                        val resolvedIdentity = result.diskCacheKey ?: readerPageCacheKey(resolved, headers, owner.sourceId)
                        size = readerImageDimensions(loader, resolved, resolvedIdentity, originalPage = original,
                            originalCacheIdentity = original?.let(owner::identity), runtime = experienceBudget.runtime)
                    }
                    if (size == null) throw IllegalStateException()
                }
            }
        } catch (_: TimeoutCancellationException) {
            if (measurementGeneration == generation)
                error = "读取尺寸超时，请重试；阅读与下载不受影响。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (measurementGeneration == generation)
                error = "原图尺寸暂不可用，请重试；阅读与下载不受影响。"
        } finally {
            if (measurementGeneration == generation) busy = false
        }
        // Cancellation does not consume an attempt; reversing a close may resume it.
        if (measurementGeneration == generation) {
            if (manual) handledRequest = requested else automaticAttempted = true
        }
    }
    val measure: () -> Unit = {
        if (active && !busy && repository != null && sourceId != null) {
            busy = true
            error = null
            measurementRequest += 1
        }
    }
    return ChapterResolutionState(
        compactLabel = primary?.let { resolutionPair(it.size) } ?: "尺寸未知",
        detail = listOfNotNull(detail, error).joinToString(" · "),
        known = primary != null,
        busy = busy || autoMeasure && !automaticAttempted && primary == null && repository != null && sourceId != null,
        error = error,
        canMeasure = active && repository != null && sourceId != null && !busy, measure = measure,
    )
}

/** Missing source headers stay unknown; an empty-map fallback could select another session's bytes. */
private data class ChapterImageOwner(val sourceId: String, val headers: Map<String, String>) {
    fun identity(page: Page) = readerPageCacheKey(page, headers, sourceId)
}

private suspend fun readChapterImageOwner(repository: MangaRepository?, mangaId: String,
    chapterId: String, sourceId: String?): ChapterImageOwner? = withContext(Dispatchers.IO) {
    if (repository == null || sourceId == null) return@withContext null
    try {
        ChapterImageOwner(sourceId, repository.imageHeaders(mangaId, chapterId).toMap())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

private data class ResolutionSample(val index: Int, val size: IntSize, val measured: Boolean)
private fun resolutionPair(size: IntSize): String = size.width.toString() + " × " + size.height
