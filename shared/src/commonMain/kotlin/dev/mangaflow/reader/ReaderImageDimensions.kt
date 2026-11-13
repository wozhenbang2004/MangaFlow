package dev.mangaflow.reader

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import dev.mangaflow.data.Page
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Header-only dimensions are bounded and survive chapter navigation without retaining pixels. */
internal object ReaderDimensionCache {
    private const val MAX_ENTRIES = 512
    private val gate = SynchronizedObject()
    private val entries = linkedMapOf<String, IntSize>()
    fun get(url: String): IntSize? = synchronized(gate) {
        entries.remove(url)?.also { entries[url] = it }
    }
    fun put(url: String, dimensions: IntSize) = synchronized(gate) {
        if (dimensions.width > 0 && dimensions.height > 0) {
            entries.remove(url)
            entries[url] = dimensions
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
    }
}

// Serialize only colliding header reads; unrelated pages keep their existing IO concurrency.
private val dimensionReads = Array(16) { Mutex() }

/** Read bytes already present locally; never fetch an image simply to measure it. */
internal suspend fun readerImageDimensions(
    imageLoader: ImageLoader,
    page: Page,
    diskCacheKey: String? = page.url,
): IntSize? {
    ReaderDimensionCache.get(page.url)?.let { return it }
    return withContext(Dispatchers.IO) {
        dimensionReads[(page.url.hashCode() and Int.MAX_VALUE) % dimensionReads.size].withLock {
            ReaderDimensionCache.get(page.url)?.let { return@withLock it }
            val dimensions = try {
                val file = readerFilePath(page.url)
                if (file != null) readNativeReaderSize(file)
                else diskCacheKey?.let { key ->
                    imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
                        readNativeReaderSize(snapshot.data.toString())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            dimensions?.also { ReaderDimensionCache.put(page.url, it) }
        }
    }
}
