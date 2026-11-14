package dev.mangaflow.reader

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import dev.mangaflow.data.Page
import dev.mangaflow.experience.readerPageCacheKey
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

    fun get(identity: String): IntSize? = synchronized(gate) {
        entries.remove(identity)?.also { entries[identity] = it }
    }

    fun put(identity: String, dimensions: IntSize) = synchronized(gate) {
        if (identity.isNotBlank() && dimensions.width > 0 && dimensions.height > 0) {
            val changed = entries[identity] != dimensions
            entries.remove(identity)
            entries[identity] = dimensions
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
 * to its unmodified saved file. Protected page aliases require the original exact cache identity;
 * a session-bearing URL or changed remote/retry URL is never an unscoped dimension alias.
 */
internal suspend fun readerImageDimensions(
    imageLoader: ImageLoader,
    page: Page,
    diskCacheKey: String? = readerPageCacheKey(page, emptyMap()),
    originalPage: Page? = null,
    refresh: Boolean = false,
    originalCacheIdentity: String? = null,
): IntSize? {
    val file = readerFilePath(page.url)
    val identity = diskCacheKey ?: readerPageCacheKey(page, emptyMap())
    fun publish(dimensions: IntSize?): IntSize? = dimensions?.takeIf {
        it.width > 0 && it.height > 0
    }?.also { size ->
        ReaderDimensionCache.put(identity, size)
        originalPage?.takeIf { original ->
            original.index == page.index && (original.url == page.url || file != null)
        }?.let { original ->
            val originalIdentity = originalCacheIdentity ?: readerPageCacheKey(original, emptyMap())
                .takeIf { it == original.url && identity == page.url }
            originalIdentity?.let { ReaderDimensionCache.put(it, size) }
        }
    }
    if (!refresh) ReaderDimensionCache.get(identity)?.let { return publish(it) }
    return withContext(Dispatchers.IO) {
        dimensionReads[(identity.hashCode() and Int.MAX_VALUE) % dimensionReads.size].withLock {
            if (!refresh) ReaderDimensionCache.get(identity)?.let { return@withLock publish(it) }
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
