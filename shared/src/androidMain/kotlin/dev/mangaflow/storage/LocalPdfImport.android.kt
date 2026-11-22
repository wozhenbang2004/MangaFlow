package dev.mangaflow.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.mangaflow.platform.storage.StorageFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal actual suspend fun renderLocalPdf(
    source: StorageFile, staging: StorageFile, limits: LocalPdfLimits,
): List<LocalPdfPage> {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val descriptor = ParcelFileDescriptor.open(File(source.path), ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try { PdfRenderer(descriptor) }
    catch (failure: Exception) {
        runCatching { descriptor.close() }
        when (failure) {
            is SecurityException -> throw LocalPdfImportException(LocalPdfFailure.ENCRYPTED, failure)
            else -> throw LocalPdfImportException(LocalPdfFailure.INVALID_DOCUMENT, failure)
        }
    }
    return renderer.use { document ->
        limits.checkPageCount(document.pageCount)
        val pages = ArrayList<LocalPdfPage>(document.pageCount)
        var completedBytes = 0L
        for (index in 0 until document.pageCount) {
            coroutine.ensureActive()
            val byteLimit = limits.pageByteLimit(completedBytes)
            val page = try { document.openPage(index) }
            catch (failure: Exception) { throw LocalPdfImportException(LocalPdfFailure.INVALID_PAGE, failure) }
            page.use {
                val raster = localPdfRaster(page.width.toDouble(), page.height.toDouble())
                val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    try {
                        page.render(bitmap, null, Matrix().apply {
                            setScale(raster.scale, raster.scale)
                        }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } catch (failure: Exception) {
                        throw LocalPdfImportException(LocalPdfFailure.INVALID_PAGE, failure)
                    }
                    // Native rendering finishes one bounded page before cancellation can close it.
                    coroutine.ensureActive()
                    val destination = localPdfPageFile(staging, index)
                    try {
                        LocalPdfOutput(destination, byteLimit, limits.byteLimitFailure(completedBytes), coroutine).use { output ->
                            val saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.checkComplete()
                            if (!saved) throw LocalPdfImportException(LocalPdfFailure.STORAGE)
                        }
                        coroutine.ensureActive()
                        completedBytes += validateLocalPdfPage(destination, raster.width, raster.height, byteLimit)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: IOException) {
                        coroutine.ensureActive()
                        throw LocalPdfImportException(LocalPdfFailure.STORAGE, failure)
                    }
                    pages += LocalPdfPage(raster.width, raster.height)
                } finally { bitmap.recycle() }
            }
        }
        coroutine.ensureActive()
        pages
    }
}
