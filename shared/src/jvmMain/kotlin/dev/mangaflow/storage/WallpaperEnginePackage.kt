package dev.mangaflow.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads media, never executes a wallpaper project.
 * The package directory layout is documented by the public PKG readers and the
 * Mirage PKGM0014 writer; this is an independent, bounded JVM reader.
 * Source pins: XHR666/dsh-mpkg-wallpaper ab8d50db9b4a29916be7ba8b45aed452ee80d538
 * lib/pkg-extract.js; laobamac/MirageWallpaper e66f14b9cffa8129602b3582711f5881df91d159
 * Services/MobilePackageArchive.swift and MobileMPKGExporter.swift.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object WallpaperEnginePackage {
    private const val MAX_MEDIA = 512L * 1024 * 1024
    private const val MAX_METADATA = 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val HEAD_BYTES = 4096
    private val metadataJson = Json {
        allowTrailingComma = true
        allowComments = true
    }

    private enum class Kind { IMAGE, VIDEO }
    private data class MediaFormat(val suffix: String, val kind: Kind)
    private data class Entry(val name: String, val offset: Long, val size: Long)
    private data class Slice(val offset: Long, val size: Long, val format: MediaFormat)

    @Suppress("UNUSED_PARAMETER")
    suspend fun extractMedia(source: File, directory: File, title: String): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(source.isFile && source.length() in 1..MAX_MEDIA) {
            "壁纸文件为空或超过 512 MB。"
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法准备壁纸，请检查剩余空间。" }
        RandomAccessFile(source, "r").use { input ->
            val size = input.length()
            val raw = sniff(readHead(input, 0, size), size)
            val slice = if (raw != null) Slice(0, size, raw) else readPackage(input, size)
            val output = File.createTempFile("wallpaper-", ".${slice.format.suffix}", directory)
            var completed = false
            try {
                input.seek(slice.offset)
                output.outputStream().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = slice.size
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "壁纸文件不完整，请从电脑重新发送。" }
                        destination.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                currentCoroutineContext().ensureActive()
                completed = true
                output
            } finally {
                if (!completed) output.delete()
            }
        }
    }

    /** Workshop downloads are directories. Only the declared main media is imported. */
    suspend fun extractProjectMedia(
        directory: File, title: String, expectedType: SteamWallpaperKind, primaryFile: String? = null,
    ): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(directory.isDirectory) { "下载目录不可用，请重新下载。" }
        val root = directory.canonicalFile
        fun ownedFile(name: String): File {
            // Use the same path grammar as packages, then reject symlinks and aliases.
            safeName(name)
            val selected = File(root, name)
            val canonical = selected.canonicalFile
            require(canonical.toPath().startsWith(root.toPath()) && canonical != root &&
                selected.absoluteFile == canonical && canonical.isFile) {
                "壁纸项目包含不安全或缺失的文件路径。"
            }
            return canonical
        }
        val expected = if (expectedType == SteamWallpaperKind.VIDEO) Kind.VIDEO else Kind.IMAGE
        val source = if (File(root, "project.json").exists()) {
            val metadata = ownedFile("project.json")
            require(metadata.length() in 1..MAX_METADATA.toLong()) { "壁纸项目信息无效或过大。" }
            val project = try {
                metadataJson.parseToJsonElement(metadata.readText(Charsets.UTF_8).removePrefix("\uFEFF")) as? JsonObject
                    ?: error("invalid project")
            } catch (_: Exception) {
                error("无法读取壁纸项目信息，请选择原始图片或视频项目。")
            }
            val actual = when (textProperty(project, "type").lowercase(Locale.ROOT)) {
                "image", "picture" -> Kind.IMAGE
                "video" -> Kind.VIDEO
                "scene", "web", "application" -> error("此项目需要场景播放器，不能作为原始图片或视频导入。")
                else -> error("此项目未声明可直接播放的图片或视频。")
            }
            require(actual == expected) { "实际项目类型与所选壁纸类型不一致。" }
            ownedFile(textProperty(project, "file"))
        } else {
            // Legacy Workshop metadata can explicitly name its original image/video.
            // The native download event must supply that exact primary file; never pick a preview.
            ownedFile(primaryFile?.takeIf(String::isNotBlank)
                ?: error("下载内容未提供可确认的主媒体文件。"))
        }
        require(source.length() in 1..MAX_MEDIA) { "壁纸主媒体为空或超过 512 MB。" }
        RandomAccessFile(source, "r").use { input ->
            val format = sniff(readHead(input, 0, source.length()), source.length())
                ?: error("主媒体不是可读取的原始图片或视频。")
            require(format.kind == expected) { "主媒体文件与项目声明的类型不一致。" }
        }
        extractMedia(source, root, title)
    }

    private suspend fun readPackage(input: RandomAccessFile, size: Long): Slice {
        input.seek(0)
        require(readWord(input, size) == 8L) { "收到的文件不是可识别的图片、视频或壁纸包。" }
        val magic = readText(input, 8, size)
        require(Regex("PKG[VM][0-9]{4}").matches(magic)) {
            "暂不支持这个壁纸包格式，请从电脑导出原图片或视频。"
        }
        val count = readWord(input, size)
        require(count in 1..MAX_ENTRIES.toLong()) { "壁纸包目录过大或已损坏。" }
        val entries = ArrayList<Entry>(count.toInt())
        val names = HashSet<String>()
        repeat(count.toInt()) {
            currentCoroutineContext().ensureActive()
            val nameLength = readWord(input, size)
            require(nameLength in 1..1024L) { "壁纸包中的文件名无效。" }
            val name = readText(input, nameLength.toInt(), size)
            val key = safeName(name)
            require(names.add(key)) { "壁纸包中存在重复文件名。" }
            entries += Entry(key, readWord(input, size), readWord(input, size))
            require(input.filePointer <= MAX_METADATA) { "壁纸包目录超过 1 MB，暂不能导入。" }
        }
        val payloadStart = input.filePointer
        val payloadSize = size - payloadStart
        entries.forEach { entry ->
            require(entry.offset <= payloadSize && entry.size <= payloadSize - entry.offset) {
                "壁纸包文件范围无效，请从电脑重新发送。"
            }
        }
        var previousEnd = 0L
        entries.filter { it.size > 0 }.sortedBy { it.offset }.forEach { entry ->
            require(entry.offset >= previousEnd) { "壁纸包文件范围重叠。" }
            previousEnd = entry.offset + entry.size
        }
        val metadata = entries.firstOrNull { it.name == "project.json" }
            ?: error("壁纸包缺少 project.json，请从电脑导出原图片或视频。")
        require(metadata.size in 1..MAX_METADATA.toLong()) { "壁纸包的项目信息无效或过大。" }
        input.seek(payloadStart + metadata.offset)
        val project = try {
            val text = readText(input, metadata.size.toInt(), size).removePrefix("\uFEFF")
            metadataJson.parseToJsonElement(text) as? JsonObject
                ?: error("invalid project")
        } catch (_: Exception) {
            error("无法读取壁纸项目信息；压缩项目需在电脑导出原图片或视频。")
        }
        val type = textProperty(project, "type").lowercase(Locale.ROOT)
        val expected = when (type) {
            "video" -> Kind.VIDEO
            "image", "picture" -> Kind.IMAGE
            "scene", "web", "application" -> error(
                "此壁纸是实时场景，请在电脑转成图片或视频后发送。"
            )
            else -> error("此壁纸包没有可直接播放的图片或视频主文件。")
        }
        val selected = safeName(textProperty(project, "file"))
        val media = entries.firstOrNull { it.name == selected }
            ?: error("壁纸包缺少主媒体文件，请从电脑重新发送。")
        require(media.size in 1..MAX_MEDIA) { "壁纸主媒体为空或超过 512 MB。" }
        val absolute = payloadStart + media.offset
        val format = sniff(readHead(input, absolute, media.size), media.size)
            ?: error("包内媒体无法直接读取；请在电脑导出原图片或视频。")
        require(format.kind == expected) { "壁纸包声明的类型与主媒体不一致。" }
        return Slice(absolute, media.size, format)
    }

    private fun textProperty(project: JsonObject, key: String): String =
        (project[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun safeName(name: String): String {
        require(name.isNotEmpty() && name.length <= 1024 &&
            name.none { it < ' ' || it == '\u007f' || it == '\\' || it == ':' } &&
            name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "壁纸包包含不安全的文件路径。"
        }
        return name.lowercase(Locale.ROOT)
    }

    private fun readWord(input: RandomAccessFile, limit: Long): Long {
        require(input.filePointer <= limit - 4) { "壁纸包目录不完整。" }
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
    }

    private fun readText(input: RandomAccessFile, count: Int, limit: Long): String {
        require(count >= 0 && input.filePointer <= limit - count) { "壁纸包数据不完整。" }
        val data = ByteArray(count)
        input.readFully(data)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) {
            error("壁纸包中的文字编码无效。")
        }
    }

    private fun readHead(input: RandomAccessFile, offset: Long, size: Long): ByteArray {
        val bytes = ByteArray(minOf(size, HEAD_BYTES.toLong()).toInt())
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    private fun sniff(bytes: ByteArray, size: Long): MediaFormat? {
        fun at(offset: Int, vararg values: Int): Boolean =
            offset >= 0 && offset + values.size <= bytes.size &&
                values.indices.all { (bytes[offset + it].toInt() and 255) == values[it] }
        fun ascii(offset: Int, value: String): Boolean =
            offset >= 0 && offset + value.length <= bytes.size &&
                value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
        if (at(0, 0xff, 0xd8, 0xff)) return MediaFormat("jpg", Kind.IMAGE)
        if (at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            return MediaFormat("png", Kind.IMAGE)
        if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return MediaFormat("gif", Kind.IMAGE)
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return MediaFormat("webp", Kind.IMAGE)
        if (size >= 26 && ascii(0, "BM")) return MediaFormat("bmp", Kind.IMAGE)
        if (size >= 16 && ascii(4, "ftyp")) {
            val boxSize = ((bytes[0].toLong() and 255L) shl 24) or
                ((bytes[1].toLong() and 255L) shl 16) or
                ((bytes[2].toLong() and 255L) shl 8) or (bytes[3].toLong() and 255L)
            if (boxSize !in 16..minOf(size, HEAD_BYTES.toLong())) return null
            val brands = mutableSetOf(String(bytes, 8, 4, Charsets.US_ASCII))
            var index = 16
            while (index + 4 <= boxSize.toInt()) {
                brands += String(bytes, index, 4, Charsets.US_ASCII)
                index += 4
            }
            if (brands.any { it == "avif" || it == "avis" }) return MediaFormat("avif", Kind.IMAGE)
            if (brands.any { it in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") })
                return MediaFormat("heic", Kind.IMAGE)
            if (brands.any { it.startsWith("3gp") || it.startsWith("3g2") })
                return MediaFormat("3gp", Kind.VIDEO)
            if (brands.any { it in setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6",
                    "iso7", "iso8", "iso9", "avc1", "hvc1", "hev1", "mp41", "mp42", "M4V ", "qt  ", "dash") })
                return MediaFormat("mp4", Kind.VIDEO)
        }
        if (size >= 32 && at(0, 0x1a, 0x45, 0xdf, 0xa3)) return MediaFormat("mkv", Kind.VIDEO)
        return null
    }
}
