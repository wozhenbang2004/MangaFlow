package dev.mangaflow.ui.glass

import android.app.ActivityManager
import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint
import dev.mangaflow.platform.PlatformContext

internal actual fun platformIsLowRamDevice(context: PlatformContext): Boolean =
    context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != false

internal actual fun applySoftShadowBlur(paint: Paint, radius: Float) {
    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
}
