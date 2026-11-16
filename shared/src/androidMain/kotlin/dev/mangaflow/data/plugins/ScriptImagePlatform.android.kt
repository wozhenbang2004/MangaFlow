package dev.mangaflow.data.plugins

import android.graphics.BitmapFactory
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.core.crypto.JmImageTool

internal actual fun decodeScriptJmImage(bytes: ByteArray, chapterId: String, filename: String, url: String, index: Int, maxPixels: Long, maxEncodedBytes: Long): ByteArray {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0 &&
        options.outWidth.toLong() * options.outHeight <= maxPixels) { "图片解码尺寸超过配置的像素预算。" }
    return JmImageTool.decryptImage(bytes, JmImage(chapterId, "220980", filename,
        url.substringBefore('?'), url.substringAfter('?', ""), index + 1))
}
