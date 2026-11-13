@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.ui.glass

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import dev.mangaflow.platform.PlatformContext
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

internal actual fun platformIsLowRamDevice(context: PlatformContext): Boolean =
    platform.Foundation.NSProcessInfo.processInfo.physicalMemory < 3_000_000_000UL

internal actual fun applySoftShadowBlur(paint: Paint, radius: Float) {
    paint.skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius)
}
