package dev.mangaflow.reader

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import dev.mangaflow.data.Page
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Original-file headers only. No decoded preview, viewport or tile sizes belong in this cache. */
internal object ReaderDimensionCache {
    private const val MAX_ENTRIES = 512
    private val gate = SynchronizedObject()
    private val entries = linkedMapOf<String, IntSize>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun get(url: String): IntSize? = synchronized(gate) {
        entries.remove(url)?.also { entries[url] = it }
    }

    fun put(url: String, dimensions: IntSize) = synchronized(gate) {
        if (url.isNotBlank() && dimensions.width > 0 && dimensions.height > 0) {
            val changed = entries[url] != dimensions
            entries.remove(url)
            entries[url] = dimensions
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            if (changed) _revision.value += 1
        }
    }
}

// Serialize only colliding header reads; unrelated pages keep their existing IO concurrency.
private val dimensionReads = Array(16) { Mutex() }

/**
 * Read original bytes already present locally; measuring never requests an image.
 * originalPage is supplied only by a caller that resolved this exact owning chapter/page
 * to its unmodified saved file. A changed remote/retry URL is never an alias.
 */
internal suspend fun readerImageDimensions(
    imageLoader: ImageLoader,
    page: Page,
    diskCacheKey: String? = page.url,
    originalPage: Page? = null,
    refresh: Boolean = false,
): IntSize? {
    val file = readerFilePath(page.url)
    fun publish(dimensions: IntSize?): IntSize? = dimensions?.takeIf {
        it.width > 0 && it.height > 0
    }?.also { size ->
        ReaderDimensionCache.put(page.url, size)
        originalPage?.takeIf { original ->
            original.index == page.index && (original.url == page.url || file != null)
        }?.let { ReaderDimensionCache.put(it.url, size) }
    }
    if (!refresh) ReaderDimensionCache.get(page.url)?.let { return publish(it) }
    return withContext(Dispatchers.IO) {
        dimensionReads[(page.url.hashCode() and Int.MAX_VALUE) % dimensionReads.size].withLock {
            if (!refresh) ReaderDimensionCache.get(page.url)?.let { return@withLock publish(it) }
            val dimensions = try {
                if (file != null) readNativeReaderSize(file)
                else diskCacheKey?.let { key ->
                    imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
                        readNativeReaderSize(snapshot.data.toString())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            publish(dimensions)
        }
    }
}
