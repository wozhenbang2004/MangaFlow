@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.reader

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mangaflow.data.Page
import kotlinx.cinterop.*
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLCreateWithFileSystemPath
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
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
    if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path else null

private fun <T> withSource(path: String, block: (CGImageSourceRef) -> T): T {
    val text = CFStringCreateWithCString(null, path, kCFStringEncodingUTF8) ?: error("原图路径无效")
    val url = try { CFURLCreateWithFileSystemPath(null, text, kCFURLPOSIXPathStyle, false) }
        finally { CFRelease(text) }
    requireNotNull(url) { "原图路径无效" }
    val source = try { CGImageSourceCreateWithURL(url, null) } finally { CFRelease(url) }
        ?: error("此图片格式暂时无法读取")
    return try { block(source) } finally { CFRelease(source) }
}

private fun CFDictionaryRef.number(key: CFStringRef?): Long = memScoped {
    val value = CFDictionaryGetValue(this@number, key) ?: return@memScoped 0L
    val output = alloc<LongVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberSInt64Type, output.ptr)) output.value else 0L
}

internal actual suspend fun readNativeReaderSize(path: String): IntSize? = withSource(path) { source ->
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return@withSource null
    try {
        val width = properties.number(kCGImagePropertyPixelWidth).toInt()
        val height = properties.number(kCGImagePropertyPixelHeight).toInt()
        if (width <= 0 || height <= 0) null
        else if (properties.number(kCGImagePropertyOrientation) in 5L..8L) IntSize(height, width) else IntSize(width, height)
    } finally { CFRelease(properties) }
}

internal actual suspend fun decodeNativeReaderRegion(path: String, bounds: IntRect, sampleSize: Int): ReaderRegion = withSource(path) { source ->
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)
        ?: error("原图尺寸暂时无法读取")
    val dimensions = try {
        val rawWidth = properties.number(kCGImagePropertyPixelWidth)
        val rawHeight = properties.number(kCGImagePropertyPixelHeight)
        val orientation = properties.number(kCGImagePropertyOrientation).toInt()
        require(rawWidth in 1L..262_144L && rawHeight in 1L..262_144L &&
            rawWidth * rawHeight <= 1_000_000_000L) { "图片尺寸超出安全解码范围，当前显示预览" }
        if (orientation in 5..8) IntSize(rawHeight.toInt(), rawWidth.toInt())
        else IntSize(rawWidth.toInt(), rawHeight.toInt())
    } finally { CFRelease(properties) }
    require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= dimensions.width &&
        bounds.bottom <= dimensions.height && bounds.width > 0 && bounds.height > 0) { "原图区域无效" }

    // ImageIO does not expose a general compressed-image ROI decoder. Bound the actual
    // full-image decode request before constructing a CGImage, then crop only that result.
    // Normal images keep their original resolution; oversized images disclose the extra sample.
    val requestedSample = sampleSize.coerceAtLeast(1)
    var effectiveSample = requestedSample
    fun decodedPixelBound(sample: Int): Long =
        ((dimensions.width.toLong() + sample - 1) / sample) * ((dimensions.height.toLong() + sample - 1) / sample)
    while (decodedPixelBound(effectiveSample) > 8_388_608L) effectiveSample *= 2
    val maximumEdge = (maxOf(dimensions.width, dimensions.height).toLong() / effectiveSample).coerceAtLeast(1L)
    val decoded = memScoped {
        val maximum = alloc<LongVar>().apply { value = maximumEdge }
        val maximumNumber = CFNumberCreate(null, kCFNumberSInt64Type, maximum.ptr)
            ?: error("图片采样参数无效")
        val keys = allocArray<COpaquePointerVar>(5)
        val values = allocArray<COpaquePointerVar>(5)
        keys[0] = kCGImageSourceShouldCache; values[0] = kCFBooleanFalse
        keys[1] = kCGImageSourceCreateThumbnailFromImageAlways; values[1] = kCFBooleanTrue
        keys[2] = kCGImageSourceCreateThumbnailWithTransform; values[2] = kCFBooleanTrue
        keys[3] = kCGImageSourceThumbnailMaxPixelSize; values[3] = maximumNumber
        keys[4] = kCGImageSourceShouldCacheImmediately; values[4] = kCFBooleanTrue
        val options = CFDictionaryCreate(null, keys, values, 5, null, null)
        try { CGImageSourceCreateThumbnailAtIndex(source, 0u, options) }
        finally { options?.let { CFRelease(it) }; CFRelease(maximumNumber) }
    } ?: error("原图细节暂时无法读取")
    try {
        val decodedWidth = CGImageGetWidth(decoded).toInt()
        val decodedHeight = CGImageGetHeight(decoded).toInt()
        check(decodedWidth > 0 && decodedHeight > 0 &&
            decodedWidth.toLong() * decodedHeight <= 8_388_608L) { "图片解码输出超出内存预算，当前显示预览" }
        val left = bounds.left.toDouble() / dimensions.width * decodedWidth
        val top = bounds.top.toDouble() / dimensions.height * decodedHeight
        val right = bounds.right.toDouble() / dimensions.width * decodedWidth
        val bottom = bounds.bottom.toDouble() / dimensions.height * decodedHeight
        val cropLeft = kotlin.math.floor(left).toInt().coerceIn(0, decodedWidth - 1)
        val cropTop = kotlin.math.floor(top).toInt().coerceIn(0, decodedHeight - 1)
        val cropRight = ceil(right).toInt().coerceIn(cropLeft + 1, decodedWidth)
        val cropBottom = ceil(bottom).toInt().coerceIn(cropTop + 1, decodedHeight)
        val cropped = CGImageCreateWithImageInRect(decoded, CGRectMake(cropLeft.toDouble(), cropTop.toDouble(),
            (cropRight - cropLeft).toDouble(), (cropBottom - cropTop).toDouble())) ?: error("原图区域无效")
        try {
            val drawWidth = ceil(bounds.width.toDouble() / effectiveSample).coerceAtLeast(1.0)
            val drawHeight = ceil(bounds.height.toDouble() / effectiveSample).coerceAtLeast(1.0)
            check(drawWidth * drawHeight <= 8_388_608) { "原图区域过大" }
            val xScale = drawWidth / (right - left)
            val yScale = drawHeight / (bottom - top)
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(drawWidth, drawHeight), false, 1.0)
            val data = try {
                UIImage.imageWithCGImage(cropped).drawInRect(CGRectMake((cropLeft - left) * xScale,
                    (cropTop - top) * yScale, (cropRight - cropLeft) * xScale, (cropBottom - cropTop) * yScale))
                UIImagePNGRepresentation(requireNotNull(UIGraphicsGetImageFromCurrentImageContext()))
            } finally { UIGraphicsEndImageContext() } ?: error("原图区域暂时无法转换")
            val bytes = requireNotNull(data.bytes).reinterpret<kotlinx.cinterop.ByteVar>().readBytes(data.length.toInt())
            ReaderRegion(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap(),
                if (effectiveSample > requestedSample)
                    "为保护内存，这张超大图片的细节按 1/$effectiveSample 采样显示，原文件未修改"
                else null)
        } finally { CGImageRelease(cropped) }
    } finally { CGImageRelease(decoded) }
}

@Composable
internal actual fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean, lightChrome: Boolean) {
    val application = UIApplication.sharedApplication
    DisposableEffect(application) {
        val oldIdle = application.idleTimerDisabled
        val oldStatus = application.statusBarHidden
        val oldStyle = application.statusBarStyle
        onDispose {
            application.idleTimerDisabled = oldIdle
            application.statusBarStyle = oldStyle
            application.setStatusBarHidden(oldStatus, UIStatusBarAnimationFade)
        }
    }
    LaunchedEffect(application, keepScreenOn, controlsVisible, lightChrome) {
        application.idleTimerDisabled = keepScreenOn
        application.statusBarStyle = if (lightChrome) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent
        application.setStatusBarHidden(!controlsVisible, UIStatusBarAnimationFade)
    }
}

@Composable
internal actual fun ConfigureReaderDialogWindow(dimAmount: Float) {
    // iOS uses the common Compose dialog presentation; Android's native window dim is inapplicable.
}

@Composable
internal actual fun readerNavigationInsets(): WindowInsets = WindowInsets.navigationBars
@Composable
internal actual fun readerStatusInsets(): WindowInsets = WindowInsets.statusBars
