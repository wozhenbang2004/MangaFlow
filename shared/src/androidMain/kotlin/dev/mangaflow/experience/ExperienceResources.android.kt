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
import android.os.BatteryManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Display
import coil3.network.NetworkClient
import coil3.network.okhttp.asNetworkClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import dev.mangaflow.platform.PlatformContext

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val app = context.applicationContext
    val memory = runCatching { app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    val power = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
    val info = runCatching { ActivityManager.MemoryInfo().also { memory?.getMemoryInfo(it) } }.getOrNull()
    val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val runtime = Runtime.getRuntime()
    return ExperienceResources(
        lowMemoryDevice = runCatching { memory?.isLowRamDevice }.getOrNull(),
        memoryPressure = info?.takeIf { memory != null }?.lowMemory,
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
        processHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        processHeapLimitBytes = runtime.maxMemory(),
        availableMemoryBytes = info?.takeIf { memory != null }?.availMem,
        batteryPercent = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else null
        },
        charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        },
        metered = runCatching { network?.isActiveNetworkMetered }.getOrNull(),
        networkAvailable = runCatching { network?.let { it.activeNetwork != null } }.getOrNull(),
        supportedRefreshRates = runCatching {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
            val current = display?.mode
            display?.supportedModes?.filter { current != null && it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                ?.map { it.refreshRate.toInt() }?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        }.getOrDefault(emptyList()),
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
            if (!released && level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
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
        addAction(Intent.ACTION_BATTERY_CHANGED)
    }
    val componentRegistered = runCatching { app.registerComponentCallbacks(callbacks); true }.getOrDefault(false)
    val receiverRegistered = runCatching {
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        true
    }.getOrDefault(false)
    val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!released) changed(false) }
        override fun onLost(network: Network) { if (!released) changed(false) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { if (!released) changed(false) }
    }
    val networkRegistered = runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback); connectivity != null }.getOrDefault(false)
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
            if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            releaseThermal?.invoke()
        }
    }
}

internal actual fun createExperienceNetworkClient(): NetworkClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build().asNetworkClient()
