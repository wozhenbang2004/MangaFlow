package dev.mangaflow.storage

import dev.mangaflow.platform.storage.StorageFile
import java.awt.AlphaComposite
import java.awt.Color
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.filter.MissingImageReaderException
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.ScratchFile
import org.apache.pdfbox.pdmodel.DefaultResourceCache
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.PDXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters

internal actual suspend fun renderLocalPdf(
    source: StorageFile, staging: StorageFile, limits: LocalPdfLimits,
): List<LocalPdfPage> {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val scratch = StorageFile(staging, ".pdf-cache")
    if (!scratch.mkdirs()) throw LocalPdfImportException(LocalPdfFailure.STORAGE)
    val pages = try {
        val document = try {
            // File-backed random access avoids loading the input PDF into a byte array.
            Loader.loadPDF(File(source.path)) {
                ScratchFile(MemoryUsageSetting.setupTempFileOnly(limits.maxTotalBytes).setTempDir(File(scratch.path)))
            }
        } catch (failure: InvalidPasswordException) {
            throw LocalPdfImportException(LocalPdfFailure.ENCRYPTED, failure)
        } catch (failure: IOException) {
            throw LocalPdfImportException(LocalPdfFailure.INVALID_DOCUMENT, failure)
        }
        document.use {
            limits.checkPageCount(document.numberOfPages)
            // Decoded image XObjects must not accumulate across the whole book.
            document.resourceCache = object : DefaultResourceCache() {
                override fun put(indirect: COSObject, xobject: PDXObject) {
                    if (xobject !is PDImageXObject) super.put(indirect, xobject)
                }
            }
            val result = ArrayList<LocalPdfPage>(document.numberOfPages)
            var completedBytes = 0L
            for (index in 0 until document.numberOfPages) {
                coroutine.ensureActive()
                val byteLimit = limits.pageByteLimit(completedBytes)
                val page = document.getPage(index)
                val raster = localPdfRaster(page.cropBox.width.toDouble(), page.cropBox.height.toDouble())
                // Renderers belong to one page; no collection retains the book's bitmaps.
                val renderer = StrictLocalPdfRenderer(document, coroutine).apply { isSubsamplingAllowed = true }
                val image = try { renderer.renderImage(index, raster.scale, ImageType.ARGB) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: IOException) {
                    val missingDecoder = generateSequence<Throwable>(failure) { it.cause }.take(8)
                        .any { it is MissingImageReaderException }
                    throw LocalPdfImportException(if (missingDecoder) LocalPdfFailure.UNSUPPORTED_IMAGE
                        else LocalPdfFailure.INVALID_PAGE, failure)
                }
                try {
                    coroutine.ensureActive()
                    // Flatten transparent PDF areas onto paper without allocating a second image.
                    image.createGraphics().let { graphics ->
                        try {
                            graphics.composite = AlphaComposite.DstOver
                            graphics.color = Color.WHITE
                            graphics.fillRect(0, 0, image.width, image.height)
                        } finally { graphics.dispose() }
                    }
                    val destination = localPdfPageFile(staging, index)
                    try {
                        LocalPdfImageOutput(destination, byteLimit, limits.byteLimitFailure(completedBytes), coroutine).use { encoded ->
                            if (!ImageIO.write(image, "png", encoded)) throw LocalPdfImportException(LocalPdfFailure.STORAGE)
                        }
                        coroutine.ensureActive()
                        completedBytes += validateLocalPdfPage(destination, image.width, image.height, byteLimit)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: IOException) {
                        coroutine.ensureActive()
                        throw LocalPdfImportException(LocalPdfFailure.STORAGE, failure)
                    }
                    result += LocalPdfPage(image.width, image.height)
                } finally { image.flush() }
            }
            coroutine.ensureActive()
            result
        }
    } finally { scratch.deleteRecursively() }
    if (scratch.exists()) throw LocalPdfImportException(LocalPdfFailure.STORAGE)
    return pages
}

/** Direct seekable output avoids ImageIO's extra heap/temp cache; close never depends on the job. */
private class LocalPdfImageOutput(
    file: StorageFile,
    private val limit: Long,
    private val limitFailure: LocalPdfFailure,
    private val coroutine: CoroutineContext,
) : FileImageOutputStream(File(file.path)) {
    override fun write(value: Int) {
        beforeWrite(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        beforeWrite(length)
        super.write(bytes, offset, length)
    }

    override fun seek(position: Long) {
        coroutine.ensureActive()
        if (position > limit) throw LocalPdfImportException(limitFailure)
        super.seek(position)
    }

    private fun beforeWrite(length: Int) {
        coroutine.ensureActive()
        if (length.toLong() > limit - streamPosition) throw LocalPdfImportException(limitFailure)
    }
}

/** PDFBox normally logs some image failures and keeps drawing; imports must fail as a whole. */
private class StrictLocalPdfRenderer(document: PDDocument, private val coroutine: CoroutineContext) : PDFRenderer(document) {
    override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer = object : PageDrawer(parameters) {
        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            coroutine.ensureActive()
            super.processOperator(operator, operands)
        }

        override fun operatorException(operator: Operator, operands: MutableList<COSBase>, exception: IOException) {
            throw exception
        }
    }
}
