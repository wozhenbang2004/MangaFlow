package dev.mangaflow.reader

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mangaflow.data.Page
import dev.mangaflow.experience.ExperienceRuntimePreferences
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.ClosedByInterruptException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.stream.FileImageInputStream
import com.luciad.imageio.webp.WebPReadParam
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

@Composable
internal actual fun ReaderZoomableImage(model: Any?, contentDescription: String?, modifier: Modifier,
    state: ReaderImageState, alignment: Alignment, contentScale: ContentScale,
    gesturesEnabled: Boolean, onClick: ((Offset) -> Unit)?) =
    PortableReaderZoomableImage(model, contentDescription, modifier, state, alignment, contentScale, gesturesEnabled, onClick)

@Composable
internal actual fun VerticalReaderTiles(page: Page, diskCacheKey: String?, imageSize: IntSize,
    displayWidth: Int, displayHeight: Int, visibleWindow: () -> VerticalPageWindow?,
    onDetailStatus: (ReaderDetailStatus?) -> Unit) =
    PortableVerticalReaderTiles(page, diskCacheKey, imageSize, displayWidth, displayHeight, visibleWindow, onDetailStatus)

internal actual fun readerFilePath(uri: String): String? =
    if (uri.startsWith("file:")) runCatching { File(URI(uri)).absolutePath }.getOrNull() else null

internal actual suspend fun readNativeReaderSize(path: String, runtime: ExperienceRuntimePreferences): IntSize? {
    val file = checkedReaderFile(path, runtime)
    ReaderHeaderInput(RandomAccessFile(file, "r")).use { input ->
        var dimensions: IntSize? = null
        try {
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    if (reader.javaClass.name == "com.luciad.imageio.webp.WebPReader") {
                        check(file.length() <= Int.MAX_VALUE.toLong()) { "WebP原图超过平台单次字节数组范围，当前显示预览" }
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width > 0 && height > 0) dimensions = IntSize(width, height)
                } finally { reader.dispose() }
            }
        } catch (failure: IOException) {
            if (failure is InterruptedIOException || failure is ClosedByInterruptException) throw failure
            // A codec can reject a truncated header with IOException. Only a real
            // stream failure is a storage error; invalid data may try the next codec.
        } catch (_: LinkageError) { /* The native codec is unavailable on this CPU. */ }
        // ImageIO's provider selection may swallow an input error, so check even
        // when it returned no reader or an apparently successful header result.
        input.failure?.let { throw it }
        dimensions?.let { return if (jpegOrientation(path) in 5..8) IntSize(it.height, it.width) else it }
    }
    // Skia handles native formats absent from the installed ImageIO readers; size is header-only.
    // Keep file IO, configured limits, cancellation and memory pressure outside
    // the malformed-encoding catch. The JM owner reserves this encoded buffer.
    val bytes = file.readReaderBytes(runtime.maxImageBytes)
    return try {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).use { IntSize(it.width, it.height) }
    } catch (_: IllegalArgumentException) { null }
}

/** Track the file boundary, not the exception class chosen by an image codec. */
private class ReaderHeaderInput(private val file: RandomAccessFile) : FileImageInputStream(file) {
    var failure: IOException? = null
        private set
    private inline fun <T> readFile(block: () -> T): T = try { block() }
    catch (failure: IOException) { this.failure = this.failure ?: failure; throw failure }
    override fun read(): Int = readFile { super.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = readFile { super.read(bytes, offset, length) }
    override fun seek(position: Long) = readFile { super.seek(position) }
    override fun length(): Long = readFile { file.length() }
}

internal actual fun createReaderRegionDecoder(path: String): ReaderRegionDecoder = DesktopReaderRegionDecoder(path)

internal actual suspend fun decodeNativeReaderRegion(
    path: String, bounds: IntRect, sampleSize: Int, runtime: ExperienceRuntimePreferences,
): ReaderRegion {
    val decoder = createReaderRegionDecoder(path)
    return try { decoder.decode(bounds, sampleSize, runtime) }
    finally { decoder.close() }
}

/**
 * One image effect owns these reusable readers. Admission stays in ExperiencePolicyStore;
 * there is no global file/bitmap cache, and no native handle survives the image's effect.
 */
private class DesktopReaderRegionDecoder(private val path: String) : ReaderRegionDecoder {
    private val lock = Any()
    private val idle = ArrayDeque<DesktopReaderInput>()
    private var stamp: Pair<Long, Long>? = null
    private var orientation = 1
    private var closed = false

    override suspend fun decode(bounds: IntRect, sampleSize: Int, runtime: ExperienceRuntimePreferences): ReaderRegion {
        val context = currentCoroutineContext()
        val started = ReaderDecodeDiagnostics.begin()
        var succeeded = false
        try {
            // Interruption releases blocking file IO; codec progress also requests cooperative abort.
            val result = runInterruptible {
                val file = checkedReaderFile(path, runtime)
                synchronized(lock) {
                    check(!closed) { "原图读取已结束" }
                    val actual = file.length() to file.lastModified()
                    if (stamp == null) {
                        orientation = jpegOrientation(path)
                        stamp = actual
                    } else check(stamp == actual) { "原图文件已变化，请重新打开此页" }
                }
                context.ensureActive()
                val input = try {
                    val reused = synchronized(lock) {
                        if (idle.isEmpty()) null else idle.removeFirst().also { DesktopReaderInputRetention.release(it.encodedBytes) }
                    }
                    (reused ?: openReaderInput(file))?.also { ReaderDecodeDiagnostics.readerAcquired(reused != null) }
                } catch (_: LinkageError) { null }
                if (input == null) decodeSkiaRegion(file, bounds, sampleSize, runtime, context)
                else {
                    var decodedSuccessfully = false
                    try {
                        val result = input.decode(bounds, sampleSize, orientation, runtime, context)
                        context.ensureActive()
                        decodedSuccessfully = true
                        result
                    } catch (failure: LinkageError) {
                        context.ensureActive()
                        // Preserve the existing optional-codec fallback, with the same pixel bound.
                        decodeSkiaRegion(file, bounds, sampleSize, runtime, context)
                    } finally {
                        val retained = synchronized(lock) {
                            // This is an idle handle cache, not a second concurrency limit.
                            if (decodedSuccessfully && input.canRetainAfterDecode && !closed && idle.size < 4 &&
                                DesktopReaderInputRetention.retain(input.encodedBytes)) {
                                idle.addLast(input); true
                            } else false
                        }
                        if (!retained) input.close()
                    }
                }
            }
            succeeded = true
            return result
        } finally { ReaderDecodeDiagnostics.finish(started, succeeded, !context.isActive) }
    }

    override fun close() {
        val readers = synchronized(lock) {
            closed = true
            idle.toList().also { idle.clear() }
        }
        var failure: Throwable? = null
        readers.forEach {
            DesktopReaderInputRetention.release(it.encodedBytes)
            try { it.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}

private fun openReaderInput(file: File): DesktopReaderInput? {
    val input = ReaderHeaderInput(RandomAccessFile(file, "r"))
    var reader: ImageReader? = null
    try {
        val readers = ImageIO.getImageReaders(input)
        input.failure?.let { throw it }
        if (!readers.hasNext()) { input.close(); return null }
        reader = readers.next()
        // Panning can revisit an earlier region; do not ask providers to discard seekable state.
        reader.setInput(input, false, true)
        val pinnedWebp = reader.javaClass.name == "com.luciad.imageio.webp.WebPReader"
        if (pinnedWebp) {
            check(file.length() <= Int.MAX_VALUE.toLong()) { "WebP原图超过平台单次字节数组范围，当前显示预览" }
        }
        // Pinned WebP 0.3.3 retains only encoded fData, not its returned pixel/raster buffers.
        // JDK 21 PNG keeps theImage; JPEG clears image but still keeps target's full raster.
        // Unknown providers are equally ineligible. This controls idle retention only;
        // every provider keeps the same decode admission, region, sample and tile cache.
        return DesktopReaderInput(reader, input, reader.getWidth(0), reader.getHeight(0),
            encodedBytes = if (pinnedWebp) file.length() else 0L, canRetainAfterDecode = pinnedWebp)
    } catch (failure: Throwable) {
        try { reader?.dispose() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        try { input.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}

private class DesktopReaderInput(
    private val reader: ImageReader, private val input: ReaderHeaderInput,
    private val width: Int, private val height: Int,
    val encodedBytes: Long,
    val canRetainAfterDecode: Boolean,
) {
    fun decode(bounds: IntRect, sampleSize: Int, orientation: Int,
        runtime: ExperienceRuntimePreferences, context: CoroutineContext): ReaderRegion {
        val source = inverseOrientationBounds(bounds, width, height, orientation)
        require(source.left >= 0 && source.top >= 0 && source.right <= width && source.bottom <= height &&
            source.width > 0 && source.height > 0) { "原图区域无效" }
        val sample = sampleSize.coerceAtLeast(1)
        val params = reader.defaultReadParam.apply {
            if (this is WebPReadParam) {
                // libwebp must crop before allocating a long strip; sourceRegion is ignored.
                isUseCropping = true
                cropLeft = source.left
                cropTop = source.top
                cropWidth = source.width
                cropHeight = source.height
                isUseScaling = sample > 1
                scaledWidth = ceil(source.width.toDouble() / sample).toInt()
                scaledHeight = ceil(source.height.toDouble() / sample).toInt()
            } else {
                sourceRegion = java.awt.Rectangle(source.left, source.top, source.width, source.height)
                setSourceSubsampling(sample, sample, 0, 0)
            }
        }
        val cancellation = ReaderDecodeCancellation(context)
        reader.addIIOReadProgressListener(cancellation)
        try {
            context.ensureActive()
            val decoded = reader.read(0, params)
            context.ensureActive()
            input.failure?.let { throw it }
            checkNotNull(decoded) { "原图区域暂时无法读取" }
            val oriented = orient(decoded, orientation)
            context.ensureActive()
            return ReaderRegion(oriented.composeBitmap(runtime.maxImageBytes))
        } finally { reader.removeIIOReadProgressListener(cancellation) }
    }

    fun close() { try { reader.dispose() } finally { input.close() } }
}

/** Bound the pinned WebP provider's known fData retention across all displayed image effects. */
private object DesktopReaderInputRetention {
    private val limit = minOf(Runtime.getRuntime().maxMemory() / 64L, 64L * 1_048_576L)
    private var bytes = 0L
    @Synchronized fun retain(size: Long): Boolean {
        if (size > limit - bytes) return false
        bytes += size
        ReaderDecodeDiagnostics.retainedInputChanged(size)
        return true
    }
    @Synchronized fun release(size: Long) {
        bytes -= size
        ReaderDecodeDiagnostics.retainedInputChanged(-size)
    }
}

/** ImageIO's public cooperative-abort contract; unsupported native codecs still settle normally. */
private class ReaderDecodeCancellation(private val context: CoroutineContext) : IIOReadProgressListener {
    private fun check(source: ImageReader) { if (!context.isActive) source.abort() }
    override fun imageStarted(source: ImageReader, imageIndex: Int) = check(source)
    override fun imageProgress(source: ImageReader, percentageDone: Float) = check(source)
    override fun imageComplete(source: ImageReader) = Unit
    override fun sequenceStarted(source: ImageReader, minIndex: Int) = check(source)
    override fun sequenceComplete(source: ImageReader) = Unit
    override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) = check(source)
    override fun thumbnailProgress(source: ImageReader, percentageDone: Float) = check(source)
    override fun thumbnailComplete(source: ImageReader) = Unit
    override fun readAborted(source: ImageReader) = Unit
}

private fun decodeSkiaRegion(file: File, bounds: IntRect, sampleSize: Int,
    runtime: ExperienceRuntimePreferences, context: CoroutineContext): ReaderRegion {
    // Skia may decode the whole source: keep the existing explicit pixel budget.
    val fallbackPixels = (runtime.fallbackDecodeMiPixels ?: 16).toLong() * 1_048_576L
    org.jetbrains.skia.Image.makeFromEncoded(file.readReaderBytes(runtime.maxImageBytes)).use { source ->
        context.ensureActive()
        check(source.width > 0 && source.height > 0 && source.width.toLong() * source.height <= fallbackPixels) {
            "此格式尚无区域解码器，原图超过整图解码配置，当前显示预览"
        }
        require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= source.width &&
            bounds.bottom <= source.height && bounds.width > 0 && bounds.height > 0) { "原图区域无效" }
        // A draw of an undecodable lazy image can silently paint nothing. Preserve the
        // old readPixels failure signal with a one-pixel target, not a full-size ROI copy.
        org.jetbrains.skia.Bitmap().use { probe ->
            check(probe.allocN32Pixels(1, 1) && source.readPixels(probe, bounds.left, bounds.top)) {
                "原图区域暂时无法读取"
            }
        }
        context.ensureActive()
        val width = ceil(bounds.width.toDouble() / sampleSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val height = ceil(bounds.height.toDouble() / sampleSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        org.jetbrains.skia.Surface.makeRasterN32Premul(width, height).use { surface ->
            // Draw the source region directly at its LOD size. The former full-resolution
            // region bitmap plus makeFromBitmap copy amplified transient native memory.
            surface.canvas.drawImageRect(source,
                org.jetbrains.skia.Rect.makeLTRB(bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat()),
                org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()))
            context.ensureActive()
            return ReaderRegion(surface.makeImageSnapshot().toComposeImageBitmap())
        }
    }
}

private fun BufferedImage.composeBitmap(maxEncodedBytes: Long): ImageBitmap {
    // The official Compose converter copies ordinary sRGB pixels without a PNG encode/decode.
    // Keep the existing codec path for higher bit depth and other color spaces.
    if (colorModel.colorSpace.isCS_sRGB && colorModel.componentSize.all { it <= 8 }) {
        return toComposeImageBitmap()
    }
    val output = ReaderEncodedOutput(maxEncodedBytes)
    check(ImageIO.write(this, "png", output)) { "原图区域无法转换" }
    return org.jetbrains.skia.Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
}

private fun checkedReaderFile(path: String, runtime: ExperienceRuntimePreferences): File = File(path).also { file ->
    check(file.isFile && file.length() > 0L) { "原图文件暂不可用，当前显示预览" }
    check(file.length() <= runtime.maxImageBytes) { "原图压缩数据超过单图配置，当前显示预览" }
}

/** Read the checked immutable snapshot without an unbounded growth allocation. */
private fun File.readReaderBytes(maximum: Long): ByteArray {
    val length = length()
    check(length in 1L..maximum) { "原图压缩数据超过单图配置，当前显示预览" }
    check(length <= Int.MAX_VALUE.toLong()) { "原图超过平台单次字节数组范围，当前显示预览" }
    val bytes = ByteArray(length.toInt())
    inputStream().use { input ->
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            check(read > 0) { "原图文件已变化，请重新打开此页" }
            offset += read
        }
        check(input.read() == -1) { "原图文件已变化，请重新打开此页" }
    }
    return bytes
}

/** The color-space fallback also obeys the encoded-data limit while it writes. */
private class ReaderEncodedOutput(private val maximum: Long) : ByteArrayOutputStream() {
    private fun allow(bytes: Int) {
        val next = size().toLong() + bytes.toLong()
        check(bytes >= 0 && next <= maximum && next <= Int.MAX_VALUE.toLong()) {
            "原图转换数据超过单图配置或平台数组范围，当前显示预览"
        }
    }
    @Synchronized override fun write(value: Int) { allow(1); super.write(value) }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        allow(length); super.write(bytes, offset, length)
    }
}

private fun inverseOrientationBounds(rect: IntRect, width: Int, height: Int, orientation: Int): IntRect = when (orientation) {
    2 -> IntRect(width - rect.right, rect.top, width - rect.left, rect.bottom)
    3 -> IntRect(width - rect.right, height - rect.bottom, width - rect.left, height - rect.top)
    4 -> IntRect(rect.left, height - rect.bottom, rect.right, height - rect.top)
    5 -> IntRect(rect.top, rect.left, rect.bottom, rect.right)
    6 -> IntRect(rect.top, height - rect.right, rect.bottom, height - rect.left)
    7 -> IntRect(width - rect.bottom, height - rect.right, width - rect.top, height - rect.left)
    8 -> IntRect(width - rect.bottom, rect.left, width - rect.top, rect.right)
    else -> rect
}

private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
    if (orientation == 1) return source
    val width = source.width
    val height = source.height
    val target = BufferedImage(if (orientation in 5..8) height else width,
        if (orientation in 5..8) width else height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        val matrix = when (orientation) {
            2 -> java.awt.geom.AffineTransform(-1.0, 0.0, 0.0, 1.0, width.toDouble(), 0.0)
            3 -> java.awt.geom.AffineTransform(-1.0, 0.0, 0.0, -1.0, width.toDouble(), height.toDouble())
            4 -> java.awt.geom.AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height.toDouble())
            5 -> java.awt.geom.AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> java.awt.geom.AffineTransform(0.0, 1.0, -1.0, 0.0, height.toDouble(), 0.0)
            7 -> java.awt.geom.AffineTransform(0.0, -1.0, -1.0, 0.0, height.toDouble(), width.toDouble())
            8 -> java.awt.geom.AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width.toDouble())
            else -> java.awt.geom.AffineTransform()
        }
        graphics.drawImage(source, matrix, null)
    } finally { graphics.dispose() }
    return target
}

/** Bounded EXIF orientation parsing; pixels remain with the native region reader. */
private fun jpegOrientation(path: String): Int = runCatching {
    File(path).inputStream().buffered().use { input ->
        if (input.read() != 0xff || input.read() != 0xd8) return@use 1
        var inspected = 2
        while (inspected < 1_048_576) {
            if (input.read() != 0xff) return@use 1
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker < 0 || marker in setOf(0xda, 0xd9)) return@use 1
            val length = (input.read() shl 8) or input.read()
            if (length < 2) return@use 1
            val data = input.readNBytes(length - 2)
            inspected += length + 2
            if (marker != 0xe1 || data.size < 14 || String(data, 0, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") continue
            val little = data[6] == 0x49.toByte() && data[7] == 0x49.toByte()
            fun u16(at: Int): Int {
                require(at >= 0 && at + 1 < data.size)
                val a = data[at].toInt() and 255; val b = data[at + 1].toInt() and 255
                return if (little) a or (b shl 8) else (a shl 8) or b
            }
            fun u32(at: Int): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16)
                else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
            if (u16(8) != 42) continue
            val offset = u32(10)
            require(offset <= data.size - 8)
            val start = offset.toInt() + 6
            val count = u16(start).coerceAtMost(512)
            for (index in 0 until count) {
                val at = start + 2 + index * 12
                if (at + 12 > data.size) break
                if (u16(at) == 0x112 && u16(at + 2) == 3 && u32(at + 4) == 1L) return@use u16(at + 8).coerceIn(1, 8)
            }
        }
        1
    }
}.getOrDefault(1)

@Composable
internal actual fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean, lightChrome: Boolean) {
    DisposableEffect(keepScreenOn) {
        val inhibitor = if (keepScreenOn) runCatching {
            val os = System.getProperty("os.name").lowercase()
            val command = when {
                "mac" in os -> listOf("caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString())
                "win" in os -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class MangaFlowPower { [DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint flags); }'; try { while ([Console]::In.Read() -ne -1) { [MangaFlowPower]::SetThreadExecutionState(2147483651) | Out-Null } } finally { [MangaFlowPower]::SetThreadExecutionState(2147483648) | Out-Null }")
                else -> listOf("systemd-inhibit", "--what=idle:sleep", "--who=MangaFlow", "--why=阅读期间保持屏幕", "--mode=block", "sh", "-c", "cat >/dev/null")
            }
            ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().also { process -> process.outputStream.write('\n'.code); process.outputStream.flush() }
        }.getOrNull() else null
        onDispose {
            inhibitor?.let { process -> runCatching { process.outputStream.close() }; process.destroy() }
        }
    }
}

@Composable
internal actual fun ConfigureReaderDialogWindow(dimAmount: Float) {
    // Compose owns desktop dialog presence/scrims; there is no Android-style native dim attribute.
}

@Composable
internal actual fun readerNavigationInsets(): WindowInsets = WindowInsets.navigationBars
@Composable
internal actual fun readerStatusInsets(): WindowInsets = WindowInsets.statusBars
