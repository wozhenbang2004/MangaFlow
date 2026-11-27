package dev.mangaflow.data.sources

import dev.mangaflow.platform.storage.StorageFile
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Some native encoders collapse a stream error to false. Keep the original write/limit cause. */
internal fun writeJmRestoredFile(destination: StorageFile, maximum: Long, encode: (OutputStream) -> Unit) {
    val file = jmCacheIo { FileOutputStream(destination.path) }
    var failure: Exception? = null
    var written = 0L
    val output = object : OutputStream() {
        private fun append(count: Int, operation: () -> Unit) {
            failure?.let { throw it }
            try {
                if (Thread.currentThread().isInterrupted) throw CancellationException("JM image restoration cancelled")
                if (count.toLong() > maximum - written)
                    throw JmPageLimitExceeded(JmPageLimit.ENCODED_BYTES, maximum)
                jmCacheIo(operation)
                written += count
            } catch (error: Exception) {
                failure = error
                throw error
            }
        }

        override fun write(value: Int) = append(1) { file.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            append(length) { file.write(bytes, offset, length) }
    }
    try {
        encode(output)
    } finally {
        try { jmCacheIo { file.close() } }
        finally { failure?.let { throw it } }
    }
}
