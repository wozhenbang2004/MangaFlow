package dev.mangaflow.enhancement.engine

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun engineSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
    }
    return digest.digest().engineHex()
}

private fun ByteArray.engineHex(): String = buildString(size * 2) {
    val digits = "0123456789abcdef"
    for (byte in this@engineHex) {
        val value = byte.toInt() and 255
        append(digits[value ushr 4]).append(digits[value and 15])
    }
}

/** Inventory-only reuse. Opening a native executable or weight still verifies its actual bytes. */
internal class EngineInventoryDigests {
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private val entries = object : LinkedHashMap<File, Pair<Stamp, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, Pair<Stamp, String>>) = size > 512
    }

    private fun stamp(file: File): Stamp? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let {
            Stamp(it.size(), it.lastModifiedTime(), it.fileKey())
        }
    } catch (_: java.io.IOException) { null }
      catch (_: UnsupportedOperationException) { null }

    @Synchronized
    fun digest(file: File): String {
        val before = stamp(file) ?: return engineSha256(file)
        entries[file]?.takeIf { it.first == before }?.let { return it.second }
        val value = engineSha256(file)
        if (before == stamp(file)) entries[file] = before to value
        return value
    }
}

internal fun newEngineOutput(source: File, destination: File) {
    require(source.isFile && source.length() > 0) { "原始文件尚未完整准备好" }
    require(source.canonicalFile != destination.canonicalFile) { "增强输出不能覆盖原文件" }
    require(!destination.exists()) { "增强临时输出已存在，请使用新的任务目录" }
    check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建增强工作目录" }
}

/** Creates a new destination exclusively; the cache owner publishes it only after this returns. */
internal fun moveNewEngineFile(source: File, destination: File, expectedSha256: String? = null) {
    val linked = try {
        Files.createLink(destination.toPath(), source.toPath())
        true
    } catch (existing: java.nio.file.FileAlreadyExistsException) { throw existing }
    catch (_: UnsupportedOperationException) { false }
    catch (_: java.nio.file.FileSystemException) { false }
    if (linked) {
        // Publication is complete. Failure to remove our private temporary link must not retry a copy over it.
        source.delete()
        return
    }
    var created = false
    try {
        Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
            created = true
            source.inputStream().use { it.copyTo(output) }
        }
        require((expectedSha256 ?: engineSha256(source)) == engineSha256(destination)) { "增强资产复制校验失败" }
        source.delete()
    } catch (failure: Throwable) {
        if (created) destination.delete()
        throw failure
    }
}

/** JPEG orientation must not silently change the geometry used by the reader. */
internal fun engineJpegOrientation(file: File): Int = java.io.DataInputStream(file.inputStream().buffered()).use { input ->
    if (input.readUnsignedByte() != 0xff || input.readUnsignedByte() != 0xd8) return@use 1
    var inspected = 2
    while (inspected < 1_048_576) {
        if (input.readUnsignedByte() != 0xff) return@use 1
        var marker = input.readUnsignedByte()
        while (marker == 0xff) marker = input.readUnsignedByte()
        if (marker == 0xda || marker == 0xd9) return@use 1
        val length = input.readUnsignedShort()
        require(length >= 2)
        val bytes = ByteArray(length - 2).also { input.readFully(it) }; inspected += length + 2
        if (marker != 0xe1 || bytes.size < 14 || String(bytes, 0, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") continue
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(if (bytes[6] == 0x49.toByte() && bytes[7] == 0x49.toByte())
            java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
        require(buffer.getShort(8).toInt() == 42)
        val start = Math.addExact(buffer.getInt(10), 6)
        require(start in 6..(bytes.size - 2))
        val count = buffer.getShort(start).toInt() and 65535
        require(count <= 512)
        for (index in 0 until count) {
            val at = start + 2 + index * 12
            require(at + 12 <= bytes.size)
            if ((buffer.getShort(at).toInt() and 65535) == 0x112 && buffer.getShort(at + 2).toInt() == 3 && buffer.getInt(at + 4) == 1)
                return@use (buffer.getShort(at + 8).toInt() and 65535).also { require(it in 1..8) }
        }
    }
    1
}

internal fun directoryBytes(directory: File): Long = if (!directory.isDirectory) 0 else
    directory.walkTopDown().maxDepth(12).filter { it.isFile }.fold(0L) { sum, file ->
        (sum + file.length()).takeIf { it >= sum } ?: Long.MAX_VALUE
    }

internal fun classifyEngineImage(width: Int, height: Int, pixels: IntArray): EngineContent {
    var opaque = 0; var color = 0
    pixels.forEach { pixel ->
        if (pixel ushr 24 >= 128) {
            opaque++
            val r = pixel ushr 16 and 255; val g = pixel ushr 8 and 255; val b = pixel and 255
            if (maxOf(r, g, b) - minOf(r, g, b) > 12) color++
        }
    }
    return when {
        opaque == 0 || color.toDouble() / opaque < 0.01 -> EngineContent.BLACK_WHITE
        height.toLong() > width.toLong() * 4 -> EngineContent.WEBTOON
        else -> EngineContent.COLOR
    }
}

internal data class EnginePngInfo(val width: Int, val height: Int, val sha256: String)

private fun pngRasterBytes(width: Int, height: Int, bitsPerPixel: Int, interlace: Int): Long {
    fun passBytes(w: Int, h: Int): Long {
        if (w == 0 || h == 0) return 0L
        val rowBytes = (w.toLong() * bitsPerPixel + 7) / 8 + 1
        return Math.multiplyExact(rowBytes, h.toLong())
    }
    if (interlace == 0) return passBytes(width, height)
    require(interlace == 1) { "PNG 交错方式无效" }
    fun span(size: Int, start: Int, step: Int) = if (size <= start) 0 else (size - 1 - start) / step + 1
    val adam7 = arrayOf(
        intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
        intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2),
        intArrayOf(0, 1, 1, 2),
    )
    return adam7.fold(0L) { sum, (x, y, dx, dy) ->
        Math.addExact(sum, passBytes(span(width, x, dx), span(height, y, dy)))
    }
}

/** Verify chunks, deflated raster length and encoded-byte identity in one bounded streaming pass. */
internal fun inspectEnginePng(file: File): EnginePngInfo {
    val inflater = java.util.zip.Inflater()
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        java.io.DataInputStream(DigestInputStream(file.inputStream(), digest).buffered(64 * 1024)).use { input ->
            val magic = ByteArray(8).also { input.readFully(it) }
            require(magic.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "增强结果不是 PNG" }
            var width = 0; var height = 0; var expected = 0L; var decoded = 0L; var dataSeen = false
            val scratch = ByteArray(64 * 1024)
            val uncompressed = ByteArray(64 * 1024)
            while (true) {
                val length = input.readInt()
                require(length >= 0) { "PNG 数据块无效" }
                val type = ByteArray(4).also { input.readFully(it) }
                val label = String(type, Charsets.US_ASCII)
                val crc = CRC32().apply { update(type) }
                var remaining = length
                if (label == "IHDR") {
                    require(width == 0 && length == 13)
                    val header = ByteArray(13).also { input.readFully(it) }; crc.update(header); remaining = 0
                    val buffer = java.nio.ByteBuffer.wrap(header)
                    width = buffer.int; height = buffer.int
                    val bits = header[8].toInt() and 255
                    val color = header[9].toInt() and 255
                    val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> error("PNG 色彩格式不受支持") }
                    val validDepth = when (color) {
                        0 -> bits in setOf(1, 2, 4, 8, 16)
                        3 -> bits in setOf(1, 2, 4, 8)
                        else -> bits == 8 || bits == 16
                    }
                    require(width > 0 && height > 0 && validDepth && header[10].toInt() == 0 && header[11].toInt() == 0) {
                        "PNG 尺寸或像素格式无效"
                    }
                    expected = pngRasterBytes(width, height, bits * channels, header[12].toInt())
                }
                while (remaining > 0) {
                    val n = minOf(scratch.size, remaining)
                    input.readFully(scratch, 0, n); crc.update(scratch, 0, n); remaining -= n
                    if (label == "IDAT") {
                        require(width > 0); dataSeen = true
                        inflater.setInput(scratch, 0, n)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            val got = inflater.inflate(uncompressed)
                            require(got > 0 || inflater.needsInput() || inflater.finished()) { "PNG 压缩数据无效" }
                            decoded += got
                            require(decoded <= expected) { "PNG 解码大小与头部不符" }
                        }
                    }
                }
                require((input.readInt().toLong() and 0xffff_ffffL) == crc.value) { "PNG 数据校验失败" }
                if (label == "IEND") {
                    require(length == 0 && dataSeen && inflater.finished() && decoded == expected && input.read() == -1) { "PNG 输出不完整" }
                    return EnginePngInfo(width, height, digest.digest().engineHex())
                }
            }
        }
        error("PNG 数据缺少完整的 IEND 结束标记")
    } finally { inflater.end() }
}

internal fun verifyEnginePng(file: File): Pair<Int, Int> =
    inspectEnginePng(file).let { it.width to it.height }

internal class ImportedEngineModels(private val root: File, private val engineId: String) {
    private val inventoryDigests = EngineInventoryDigests()

    fun import(source: File, scale: Int, content: EngineContent, title: String = source.nameWithoutExtension,
        license: String = "用户导入；请按模型原许可使用",
        contents: Set<EngineContent> = setOf(content)): EngineModel {
        require(source.isFile && source.extension.equals("onnx", true)) { "请选择 ONNX 模型文件" }
        require(source.length() in 1..(2L * 1024 * 1024 * 1024)) { "模型文件为空或超过 2 GiB" }
        require(scale in setOf(1, 2, 4)) { "当前导入支持 1×、2×、4× 模型" }
        require(content != EngineContent.VIDEO || engineId in setOf(EnhancementEngineIds.VIDEOJANAI,
            EnhancementEngineIds.ANDROID_VIDEO_ONNX)) { "当前引擎尚未适配视频模型导入" }
        check(root.isDirectory || root.mkdirs())
        val hash = engineSha256(source)
        val target = File(root, "$hash.onnx")
        if (!target.exists()) {
            val temporary = File(root, "$hash-${java.util.UUID.randomUUID()}.partial")
            try {
                source.inputStream().use { input ->
                    Files.newOutputStream(temporary.toPath(), StandardOpenOption.CREATE_NEW).use { output -> input.copyTo(output) }
                }
                check(engineSha256(temporary) == hash) { "复制期间模型发生变化" }
                try { Files.move(temporary.toPath(), target.toPath()) }
                catch (exists: java.nio.file.FileAlreadyExistsException) {
                    check(engineSha256(target) == hash) { "现有模型校验失败" }
                }
            } finally { temporary.delete() }
        }
        check(engineSha256(target) == hash) { "模型校验失败" }
        val properties = Properties().apply {
            setProperty("title", title.take(256))
            setProperty("scale", scale.toString())
            setProperty("content", content.name)
            setProperty("contents", contents.joinToString(",") { it.name })
            setProperty("license", license.take(256))
            setProperty("sha256", hash)
        }
        File(root, "$hash.properties").outputStream().use { properties.store(it, "Imported ONNX data weights") }
        return describe(target, properties)
    }

    fun list(): List<EngineModel> = root.listFiles().orEmpty().filter { it.extension == "properties" }.mapNotNull { file ->
        runCatching {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            val hash = properties.getProperty("sha256").orEmpty()
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val weights = File(root, "$hash.onnx")
            require(weights.isFile && weights.length() > 0 && inventoryDigests.digest(weights) == hash)
            describe(weights, properties)
        }.getOrNull()
    }

    private fun describe(file: File, properties: Properties) = EngineModel(
        id = "$engineId:${properties.getProperty("sha256")}", engineId = engineId,
        title = properties.getProperty("title") ?: file.nameWithoutExtension,
        path = file.canonicalPath, sha256 = properties.getProperty("sha256"),
        nativeScale = properties.getProperty("scale").toInt().also { require(it in setOf(1, 2, 4)) },
        contents = properties.getProperty("contents")?.split(",")?.mapNotNull { entry ->
            EngineContent.entries.firstOrNull { it.name == entry }
        }?.takeIf { it.isNotEmpty() }?.toSet()
            ?: setOf(EngineContent.valueOf(properties.getProperty("content"))),
        license = properties.getProperty("license", "用户导入；请按模型原许可使用"),
        version = "sha256:${properties.getProperty("sha256").take(12)}",
        bytes = file.length(),
    )
}

/** Writes rows directly to PNG. A long strip never needs a full enhanced bitmap in RAM. */
internal class EnginePngWriter(destination: File, private val width: Int, private val height: Int) : Closeable {
    // One row for the whole page. Every byte is consumed synchronously by the deflater.
    private val rowBytes = ByteArray(Math.addExact(Math.multiplyExact(width, 4), 1))
    private val output = DataOutputStream(BufferedOutputStream(
        Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW)))
    private var rows = 0
    private var finished = false
    private val chunks = object : OutputStream() {
        private val bytes = ByteArray(64 * 1024)
        private var count = 0
        override fun write(value: Int) { bytes[count++] = value.toByte(); if (count == bytes.size) flush() }
        override fun write(data: ByteArray, offset: Int, length: Int) {
            var at = offset
            var left = length
            while (left > 0) {
                val copy = minOf(left, bytes.size - count)
                data.copyInto(bytes, count, at, at + copy)
                count += copy; at += copy; left -= copy
                if (count == bytes.size) flush()
            }
        }
        override fun flush() { if (count > 0) { chunk("IDAT", bytes, count); count = 0 } }
        override fun close() { flush() }
    }
    private val deflater: Deflater
    private val compressed: DeflaterOutputStream

    init {
        require(width > 0 && height > 0)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = java.io.ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(width); out.writeInt(height); out.writeByte(8); out.writeByte(6)
                out.writeByte(0); out.writeByte(0); out.writeByte(0)
            }
        }.toByteArray()
        chunk("IHDR", header, header.size)
        deflater = Deflater()
        compressed = DeflaterOutputStream(chunks, deflater, 64 * 1024)
    }

    fun row(pixels: IntArray, offset: Int = 0) {
        check(!finished && rows < height)
        require(offset >= 0 && pixels.size - offset >= width)
        val bytes = rowBytes // The leading filter byte stays zero (PNG None).
        for (x in 0 until width) {
            val pixel = pixels[offset + x]
            val p = 1 + x * 4
            bytes[p] = (pixel ushr 16).toByte(); bytes[p + 1] = (pixel ushr 8).toByte()
            bytes[p + 2] = pixel.toByte(); bytes[p + 3] = (pixel ushr 24).toByte()
        }
        compressed.write(bytes)
        rows++
    }

    fun finish() {
        check(rows == height) { "增强输出行数不完整" }
        compressed.finish(); chunks.flush()
        chunk("IEND", byteArrayOf(), 0)
        output.flush(); finished = true
    }

    private fun chunk(type: String, bytes: ByteArray, count: Int) {
        val label = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(count); output.write(label); output.write(bytes, 0, count)
        val crc = CRC32().apply { update(label); update(bytes, 0, count) }
        output.writeInt(crc.value.toInt())
    }

    override fun close() {
        try { compressed.close() } finally { try { deflater.end() } finally { output.close() } }
    }
}

/** Conservative 1× edge-preserving denoise. This is explicitly not a neural upscaler. */
internal fun localDetailPixel(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
    val at = y * width + x
    val center = pixels[at]
    if (center ushr 24 != 255) return center
    if (x > 0 && x < width - 1 && y > 0 && y < height - 1 &&
        pixels[at - 1] == center && pixels[at + 1] == center &&
        pixels[at - width] == center && pixels[at + width] == center &&
        pixels[at - width - 1] == center && pixels[at - width + 1] == center &&
        pixels[at + width - 1] == center && pixels[at + width + 1] == center) return center
    fun luma(pixel: Int) = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) ushr 8
    val light = luma(center)
    var red = 0.0; var green = 0.0; var blue = 0.0; var total = 0.0
    for (dy in -1..1) for (dx in -1..1) {
        val pixel = pixels[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
        val delta = abs(light - luma(pixel))
        if (delta > 12 || pixel ushr 24 != 255) continue // protect lettering, outlines and screentone edges
        val weight = if (dx == 0 && dy == 0) 8.0 else (13 - delta) / 13.0
        red += (pixel ushr 16 and 255) * weight; green += (pixel ushr 8 and 255) * weight
        blue += (pixel and 255) * weight; total += weight
    }
    return (255 shl 24) or ((red / total).roundToInt().coerceIn(0, 255) shl 16) or
        ((green / total).roundToInt().coerceIn(0, 255) shl 8) or (blue / total).roundToInt().coerceIn(0, 255)
}
