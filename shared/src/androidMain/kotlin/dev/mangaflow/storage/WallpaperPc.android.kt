package dev.mangaflow.storage

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import dev.mangaflow.platform.PlatformContext

actual fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient {
    val app = context.applicationContext
    val metrics = app.resources.displayMetrics
    return JvmWallpaperPcClient(app, Build.MODEL, metrics.widthPixels, metrics.heightPixels) {
        val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = manager?.createMulticastLock("mangaflow-wallpaper-pc")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val release: () -> Unit = { if (lock?.isHeld == true) lock.release() }
        release
    }
}
