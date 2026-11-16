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
import android.os.Debug
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Display
import dev.mangaflow.net.DownloadRouteSnapshot
import coil3.network.okhttp.asNetworkClient
import dev.mangaflow.net.applyDownloadRoute
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File
import dev.mangaflow.platform.PlatformContext

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val app = context.applicationContext
    val memory = runCatching { app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    val power = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
    val info = runCatching { ActivityManager.MemoryInfo().also { memory?.getMemoryInfo(it) } }.getOrNull()
    val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val runtime = Runtime.getRuntime()
    val processors = runtime.availableProcessors().takeIf { it > 0 }
    val disk = runCatching { StatFs(app.cacheDir.absolutePath) }.getOrNull()
    val processMemory = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo) }.getOrNull()
    return ExperienceResources(
        device = ExperienceDeviceInfo(
            platformName = "Android",
            osVersion = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            deviceModel = listOfNotNull(experienceHardwareLabel(Build.MANUFACTURER), experienceHardwareLabel(Build.MODEL))
                .joinToString(" ").ifEmpty { null },
            processorName = if (Build.VERSION.SDK_INT >= 31) experienceHardwareLabel(Build.SOC_MODEL) else null,
            processorArchitecture = experienceHardwareLabel(System.getProperty("os.arch")),
            logicalProcessorCount = processors,
        ),
        sampledAtMillis = System.currentTimeMillis(),
        processCpuLoad = AndroidProcessCpuSample.read(processors),
        processResidentBytes = readOwnResidentBytes(),
        processProportionalBytes = processMemory?.totalPss?.takeIf { it > 0 }?.toLong()?.times(1024L),
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
        freeDiskBytes = runCatching { disk?.availableBytes?.takeIf { it >= 0L } }.getOrNull(),
        totalDiskBytes = runCatching { disk?.totalBytes?.takeIf { it > 0L } }.getOrNull(),
        processHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        processHeapLimitBytes = runtime.maxMemory(),
        availableMemoryBytes = info?.takeIf { memory != null }?.availMem,
        totalMemoryBytes = info?.takeIf { memory != null }?.totalMem?.takeIf { it > 0L },
        processorCount = processors,
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

/** One interval measurement under the existing policy sampler; no extra polling thread. */
private object AndroidProcessCpuSample {
    private var previousWall: Long? = null
    private var previousCpu: Long? = null
    private var previousProcessors: Int? = null

    @Synchronized fun read(processors: Int?): Double? {
        val wall = SystemClock.elapsedRealtime()
        val cpu = android.os.Process.getElapsedCpuTime()
        val oldWall = previousWall
        val oldCpu = previousCpu
        val oldProcessors = previousProcessors
        // A very close lifecycle notification must not replace a useful interval baseline.
        if (oldWall != null && wall - oldWall < 100L) return null
        previousWall = wall
        previousCpu = cpu
        previousProcessors = processors
        if (oldWall == null || oldCpu == null || processors == null || processors != oldProcessors ||
            wall <= oldWall || cpu < oldCpu) return null
        return ((cpu - oldCpu).toDouble() / (wall - oldWall).toDouble() / processors.toDouble())
            .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    }
}

/** Kernel RSS is an approximate resident counter; PSS remains separately labelled. */
private fun readOwnResidentBytes(): Long? = runCatching {
    File("/proc/self/status").bufferedReader().use { reader ->
        reader.lineSequence().take(160).firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.let { fields ->
                val kib = fields.firstOrNull()?.toLongOrNull()
                if (fields.getOrNull(1) == "kB" && kib != null && kib > 0L && kib <= Long.MAX_VALUE / 1024L)
                    kib * 1024L else null
            }
    }
}.getOrNull()

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

internal actual fun createExperienceNetworkClient(route: DownloadRouteSnapshot?, timeouts: ExperienceImageTimeouts): ExperienceNetworkTransport {
    val effective = timeouts.forCurrentPlatform()
    val client = OkHttpClient.Builder().applyDownloadRoute(route)
        .connectTimeout(effective.connectMillis, TimeUnit.MILLISECONDS).readTimeout(effective.idleMillis, TimeUnit.MILLISECONDS)
        .callTimeout(effective.totalMillis, TimeUnit.MILLISECONDS).build()
    return ExperienceNetworkTransport(client.asNetworkClient()) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
