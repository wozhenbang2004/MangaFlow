package dev.mangaflow.experience

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dev.mangaflow.platform.PlatformContext

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val app = context.applicationContext
    val memory = runCatching { app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    val power = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
    return ExperienceResources(
        lowMemoryDevice = runCatching { memory?.isLowRamDevice }.getOrNull(),
        memoryPressure = runCatching {
            memory?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo).lowMemory }
        }.getOrNull(),
        lowPower = runCatching { power?.isPowerSaveMode }.getOrNull(),
        thermalPressure = if (Build.VERSION.SDK_INT >= 29) runCatching {
            power?.currentThermalStatus?.let { status ->
                when {
                    status >= PowerManager.THERMAL_STATUS_CRITICAL -> 3
                    status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
                    status >= PowerManager.THERMAL_STATUS_LIGHT -> 1
                    else -> 0
                }
            }
        }.getOrNull() else null,
        freeDiskBytes = runCatching { StatFs(app.cacheDir.absolutePath).availableBytes }.getOrNull(),
    )
}

/** Native events only. Registration and release belong to the policy's visible/worker lifetime. */
@Suppress("DEPRECATION")
internal actual fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit {
    val app = context.applicationContext
    var released = false
    val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { if (!released) changed(true) }
        override fun onTrimMemory(level: Int) {
            // UI_HIDDEN is a lifecycle signal, not proof of memory pressure.
            if (!released && (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)) {
                changed(true)
            }
        }
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (!released) changed(false) }
    }
    val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).apply {
        addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
        addAction(Intent.ACTION_DEVICE_STORAGE_OK)
    }
    val componentRegistered = runCatching { app.registerComponentCallbacks(callbacks); true }.getOrDefault(false)
    val receiverRegistered = runCatching {
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        true
    }.getOrDefault(false)
    val releaseThermal: (() -> Unit)? = if (Build.VERSION.SDK_INT >= 29) {
        val power = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        val listener = PowerManager.OnThermalStatusChangedListener { if (!released) changed(false) }
        if (power != null && runCatching { power.addThermalStatusListener(listener); true }.getOrDefault(false)) {
            { runCatching { power.removeThermalStatusListener(listener) }; Unit }
        } else null
    } else null
    return {
        if (!released) {
            released = true
            if (componentRegistered) runCatching { app.unregisterComponentCallbacks(callbacks) }
            if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
            releaseThermal?.invoke()
        }
    }
}
