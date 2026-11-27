package dev.mangaflow.data.sources

import dev.mangaflow.platform.storage.StorageFile
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.core.crypto.JmImageTool

// ImageIO can fall back to Skia's encoded ByteArray reader on this platform.
internal fun jmHeaderWorkingBytes(encodedBytes: Long): Long = encodedBytes * 2L

@Suppress("UNUSED_PARAMETER")
internal fun restoreJmFile(source: StorageFile, pending: StorageFile, image: JmImage, segments: Int,
    pixels: Long, maximum: Long, reserve: (Long) -> (() -> Unit)): StorageFile {
    val release = reserve(source.length() * 2L + pixels * 24L)
    return try {
        val input = jmCacheIo { source.readBytes() }
        val result = JmImageTool.decryptImage(input, image)
        if (result === input) source else {
            if (result.size.toLong() > maximum) throw JmPageLimitExceeded(JmPageLimit.ENCODED_BYTES, maximum)
            writeJmRestoredFile(pending, maximum) { it.write(result) }
            pending
        }
    } finally { release() }
}
