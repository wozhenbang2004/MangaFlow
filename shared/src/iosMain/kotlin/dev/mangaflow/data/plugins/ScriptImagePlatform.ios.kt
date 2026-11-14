@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.data.plugins

import dev.mangaflow.platform.md5
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.ImageIO.*
import platform.UIKit.*

/** The JM v1.1.12 strip algorithm on CoreGraphics; this does not install a native JM API adapter. */
internal actual fun decodeScriptJmImage(bytes: ByteArray, chapterId: String, filename: String, url: String, index: Int): ByteArray {
    val photoId = chapterId.toLong()
    if (photoId < 220980L || filename.endsWith(".gif", true)) return bytes
    val count = if (photoId < 268850L) 10 else {
        val hex = md5((chapterId + filename.substringBeforeLast('.')).encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        (hex.last().code % (if (photoId < 421926L) 10 else 8)) * 2 + 2
    }
    val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
        ?: error("图片数据无法读取。")
    val source = try { CGImageSourceCreateWithData(data, null) } finally { CFRelease(data) }
        ?: error("图片格式暂不支持。")
    try {
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: error("图片尺寸无法读取。")
        val width: Int
        val height: Int
        try {
            width = properties.imageNumber(kCGImagePropertyPixelWidth).toInt()
            height = properties.imageNumber(kCGImagePropertyPixelHeight).toInt()
        } finally { CFRelease(properties) }
        require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216L) { "图片解码尺寸超过安全内存范围。" }
        if (height < count) return bytes
        val image = CGImageSourceCreateImageAtIndex(source, 0u, null) ?: error("图片暂时无法解码。")
        try {
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), false, 1.0)
            val encoded = try {
                val strip = height / count
                val remainder = height % count
                var targetY = 0
                for (segment in 0 until count) {
                    val blockHeight = strip + if (segment == 0) remainder else 0
                    val sourceY = if (segment == 0) height - blockHeight else height - strip * (segment + 1) - remainder
                    val crop = CGImageCreateWithImageInRect(image,
                        CGRectMake(0.0, sourceY.toDouble(), width.toDouble(), blockHeight.toDouble()))
                        ?: error("图片分块暂时无法解码。")
                    try { UIImage.imageWithCGImage(crop).drawInRect(
                        CGRectMake(0.0, targetY.toDouble(), width.toDouble(), blockHeight.toDouble())) }
                    finally { CGImageRelease(crop) }
                    targetY += blockHeight
                }
                UIImagePNGRepresentation(requireNotNull(UIGraphicsGetImageFromCurrentImageContext()))
            } finally { UIGraphicsEndImageContext() } ?: error("图片解码结果无法编码。")
            require(encoded.length <= 96uL * 1024uL * 1024uL) { "图片解码结果超过限制。" }
            return requireNotNull(encoded.bytes).reinterpret<ByteVar>().readBytes(encoded.length.toInt())
        } finally { CGImageRelease(image) }
    } finally { CFRelease(source) }
}

private fun CFDictionaryRef.imageNumber(key: CFStringRef?): Long = memScoped {
    val value = CFDictionaryGetValue(this@imageNumber, key) ?: return@memScoped 0L
    val output = alloc<LongVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberSInt64Type, output.ptr)) output.value else 0L
}
