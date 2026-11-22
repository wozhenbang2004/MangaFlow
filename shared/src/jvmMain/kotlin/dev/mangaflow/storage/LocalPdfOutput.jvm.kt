package dev.mangaflow.storage

import dev.mangaflow.platform.storage.StorageFile
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded output for the Android bitmap encoder, with the existing import byte limits. */
internal class LocalPdfOutput(
    file: StorageFile,
    private val limit: Long,
    private val limitFailure: LocalPdfFailure,
    private val coroutine: CoroutineContext,
) : OutputStream() {
    private val output = FileOutputStream(file.path)
    private var written = 0L
    private var rejected: LocalPdfImportException? = null

    override fun write(value: Int) {
        beforeWrite(1)
        output.write(value)
        written++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        beforeWrite(length)
        output.write(bytes, offset, length)
        written += length
    }

    private fun beforeWrite(length: Int) {
        coroutine.ensureActive()
        if (length.toLong() > limit - written) {
            val failure = LocalPdfImportException(limitFailure)
            rejected = failure
            throw failure
        }
    }

    fun checkComplete() {
        coroutine.ensureActive()
        rejected?.let { throw it }
    }

    override fun flush() = output.flush()
    override fun close() = output.close()
}
