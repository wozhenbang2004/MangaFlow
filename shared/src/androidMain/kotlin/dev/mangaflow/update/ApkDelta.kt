package dev.mangaflow.update

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

internal data class ApkDelta(val baseVersionCode: Long, val baseSha256: String, val url: String, val bytes: Long, val sha256: String)

internal fun reconstructApk(base: File, delta: File, output: File, size: Long, checkActive: () -> Unit) {
    val buffer = ByteArray(64 * 1024)
    RandomAccessFile(base, "r").use { original ->
        DataInputStream(GZIPInputStream(delta.inputStream().buffered(), buffer.size)).use { patch ->
            require(patch.readInt() == 0x4D464431) { "不支持的 APK 差分格式" }
            output.outputStream().buffered().use { destination ->
                var written = 0L
                while (written < size) {
                    checkActive()
                    val operation = patch.readUnsignedByte()
                    var remaining = patch.readInt()
                    require(remaining > 0 && remaining <= size - written) { "APK 差分超出安装包大小" }
                    if (operation == 1) {
                        val offset = patch.readInt().toLong()
                        require(offset >= 0 && offset <= original.length() - remaining) { "APK 差分基线位置无效" }
                        original.seek(offset)
                    } else require(operation == 0) { "APK 差分操作无效" }
                    while (remaining > 0) {
                        checkActive()
                        val count = minOf(remaining, buffer.size)
                        if (operation == 1) original.readFully(buffer, 0, count)
                        else patch.readFully(buffer, 0, count)
                        destination.write(buffer, 0, count)
                        written += count
                        remaining -= count
                    }
                }
                require(patch.read() == -1) { "APK 差分包含多余数据" }
            }
        }
    }
}
