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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import com.luciad.imageio.webp.WebPReadParam
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

internal actual suspend fun readNativeReaderSize(path: String): IntSize? {
    try {
        ImageIO.createImageInputStream(File(path))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    if (reader.javaClass.name == "com.luciad.imageio.webp.WebPReader") {
                        check(File(path).length() <= 67_108_864L) { "WebP 原图压缩文件过大" }
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    return if (jpegOrientation(path) in 5..8) IntSize(height, width) else IntSize(width, height)
                } finally { reader.dispose() }
            }
        }
    } catch (_: LinkageError) { /* The native codec is unavailable on this CPU; use the bounded Skia path. */ }
    // Skia handles native formats absent from the installed ImageIO readers; size is header-only.
    val file = File(path)
    if (!file.isFile || file.length() !in 1..67_108_864L) return null
    return runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).use { IntSize(it.width, it.height) } }.getOrNull()
}

internal actual suspend fun decodeNativeReaderRegion(path: String, bounds: IntRect, sampleSize: Int): ReaderRegion {
    val file = File(path)
    try {
        ImageIO.createImageInputStream(file)?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    if (reader.javaClass.name == "com.luciad.imageio.webp.WebPReader") {
                        check(file.length() <= 67_108_864L) { "WebP 原图压缩文件过大" }
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    val orientation = jpegOrientation(path)
                    val source = inverseOrientationBounds(bounds, width, height, orientation)
                    val params = reader.defaultReadParam.apply {
                        if (this is WebPReadParam) {
                            check(file.length() <= 67_108_864L) { "WebP 原图压缩文件过大" }
                            // This provider ignores ImageReadParam.sourceRegion; use libwebp's real
                            // crop/scaling options so long WebP strips never allocate the full image.
                            isUseCropping = true
                            cropLeft = source.left
                            cropTop = source.top
                            cropWidth = source.width
                            cropHeight = source.height
                            isUseScaling = sampleSize > 1
                            scaledWidth = ceil(source.width.toDouble() / sampleSize.coerceAtLeast(1)).toInt()
                            scaledHeight = ceil(source.height.toDouble() / sampleSize.coerceAtLeast(1)).toInt()
                        } else {
                            sourceRegion = java.awt.Rectangle(source.left, source.top, source.width, source.height)
                            setSourceSubsampling(sampleSize.coerceAtLeast(1), sampleSize.coerceAtLeast(1), 0, 0)
                        }
                    }
                    val decoded = reader.read(0, params) ?: error("原图区域暂时无法读取")
                    return ReaderRegion(orient(decoded, orientation).composeBitmap())
                } finally { reader.dispose() }
            }
        }
    } catch (_: LinkageError) { /* A missing optional ImageIO native binary cannot crash the reader. */ }
    // Explicitly bound the non-region fallback; never decode an unbounded long image in memory.
    check(file.length() in 1..67_108_864L) { "此格式的原图过大，暂时无法读取细节" }
    org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).use { source ->
        check(source.width.toLong() * source.height <= 16_777_216L) { "此格式尚无区域解码器，无法安全读取大图细节" }
        org.jetbrains.skia.Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(bounds.width, bounds.height)) { "原图区域无法分配" }
            check(source.readPixels(bitmap, bounds.left, bounds.top)) { "原图区域暂时无法读取" }
            org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { region ->
                val width = ceil(bounds.width.toDouble() / sampleSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val height = ceil(bounds.height.toDouble() / sampleSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                org.jetbrains.skia.Surface.makeRasterN32Premul(width, height).use { surface ->
                    surface.canvas.drawImageRect(region, org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()))
                    return ReaderRegion(surface.makeImageSnapshot().toComposeImageBitmap())
                }
            }
        }
    }
}

private fun BufferedImage.composeBitmap(): ImageBitmap {
    // The official Compose converter copies ordinary sRGB pixels without a PNG encode/decode.
    // Keep the existing codec path for higher bit depth and other color spaces.
    if (colorModel.colorSpace.isCS_sRGB && colorModel.componentSize.all { it <= 8 }) {
        return toComposeImageBitmap()
    }
    val output = ByteArrayOutputStream()
    check(ImageIO.write(this, "png", output)) { "原图区域无法转换" }
    return org.jetbrains.skia.Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
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
