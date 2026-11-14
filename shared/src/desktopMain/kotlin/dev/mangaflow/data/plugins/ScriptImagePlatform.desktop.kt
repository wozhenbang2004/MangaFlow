package dev.mangaflow.data.plugins

import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.core.crypto.JmImageTool
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun decodeScriptJmImage(bytes: ByteArray, chapterId: String, filename: String, url: String, index: Int): ByteArray {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        requireNotNull(input) { "图片格式暂不支持。" }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "图片格式暂不支持。" }
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216L) { "图片解码尺寸超过安全内存范围。" }
        } finally { reader.dispose() }
    }
    return JmImageTool.decryptImage(bytes, JmImage(chapterId, "220980", filename,
        url.substringBefore('?'), url.substringAfter('?', ""), index + 1))
}
