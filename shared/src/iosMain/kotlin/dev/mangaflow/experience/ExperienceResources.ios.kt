@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.cacheDirectory
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlin.native.Platform
import platform.Foundation.*
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.sysctlbyname
import platform.darwin.task_info
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.size_tVar
import dev.mangaflow.net.DownloadRouteSnapshot
import coil3.network.ktor3.asNetworkClient
import dev.mangaflow.net.platformHttpClient
import io.ktor.client.plugins.HttpTimeout

/** Hardware labels are read once. No device identifiers or inferred model-to-chip table. */
private val iosDeviceInfo by lazy {
    ExperienceDeviceInfo(
        platformName = "iOS",
        osVersion = experienceHardwareLabel(NSProcessInfo.processInfo.operatingSystemVersionString),
        deviceModel = readSysctlString("hw.machine"),
        processorName = readSysctlString("machdep.cpu.brand_string"),
        processorArchitecture = experienceHardwareLabel(Platform.cpuArchitecture.name),
        physicalProcessorCount = readSysctlInt("hw.physicalcpu_max")?.takeIf { it > 0 },
        logicalProcessorCount = NSProcessInfo.processInfo.processorCount.toInt().takeIf { it > 0 },
        // iOS does not promise an available public maximum-frequency counter.
    )
}

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val process = NSProcessInfo.processInfo
    val disk = runCatching {
        NSFileManager.defaultManager.attributesOfFileSystemForPath(cacheDirectory(context).path, null)
    }.getOrNull()
    return ExperienceResources(
        device = iosDeviceInfo,
        sampledAtMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong(),
        processResidentBytes = readOwnResidentBytes(),
        processCpuLoad = IosProcessCpuSample.read(process.processorCount.toInt().takeIf { it > 0 }),
        totalMemoryBytes = process.physicalMemory.toLong().takeIf { it > 0L },
        processorCount = process.activeProcessorCount.toInt().takeIf { it > 0 },
        // Heap allowance, available system RAM and total-system CPU remain unknown.
        lowPower = process.lowPowerModeEnabled,
        thermalPressure = when (process.thermalState) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> 0
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> 1
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> 2
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> 3
            else -> null
        },
        freeDiskBytes = (disk?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue?.takeIf { it >= 0L },
        totalDiskBytes = (disk?.get(NSFileSystemSize) as? NSNumber)?.longLongValue?.takeIf { it > 0L },
    )
}

/** MACH_TASK_BASIC_INFO supplies current process RSS, not virtual size or system RAM. */
private fun readOwnResidentBytes(): Long? = runCatching {
    memScoped {
        val info = alloc<mach_task_basic_info>()
        val count = alloc<UIntVar>()
        val expected = (sizeOf<mach_task_basic_info>() / sizeOf<UIntVar>()).toUInt()
        count.value = expected
        val result = task_info(mach_task_self_, MACH_TASK_BASIC_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS && count.value >= expected) info.resident_size.toLong().takeIf { it > 0L }
        else null
    }
}.getOrNull()

/** The existing application sampler supplies the interval; this object never schedules work. */
private object IosProcessCpuSample {
    private val lock = StorageLock()
    private var previousWall: Long? = null
    private var previousCpu: Double? = null
    private var previousProcessors: Int? = null

    fun read(processors: Int?): Double? = synchronized(lock) {
        val cpu = readOwnCpuSeconds() ?: return@synchronized null
        val wall = monotonicTimeMillis()
        val oldWall = previousWall
        val oldCpu = previousCpu
        val oldProcessors = previousProcessors
        if (oldWall != null && wall - oldWall < 100L) return@synchronized null
        previousWall = wall
        previousCpu = cpu
        previousProcessors = processors
        if (oldWall == null || oldCpu == null || processors == null || processors != oldProcessors ||
            wall <= oldWall || cpu < oldCpu) return@synchronized null
        ((cpu - oldCpu) * 1_000.0 / (wall - oldWall).toDouble() / processors.toDouble())
            .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    }
}

private fun readOwnCpuSeconds(): Double? = runCatching {
    memScoped {
        val usage = alloc<rusage>()
        if (getrusage(RUSAGE_SELF, usage.ptr) != 0) return@memScoped null
        val user = usage.ru_utime.tv_sec.toDouble() + usage.ru_utime.tv_usec.toDouble() / 1_000_000.0
        val system = usage.ru_stime.tv_sec.toDouble() + usage.ru_stime.tv_usec.toDouble() / 1_000_000.0
        (user + system).takeIf { user >= 0.0 && system >= 0.0 && it.isFinite() }
    }
}.getOrNull()

private fun readSysctlString(name: String): String? = runCatching {
    memScoped {
        val length = alloc<size_tVar>()
        length.value = 0uL
        if (sysctlbyname(name, null, length.ptr, null, 0uL) != 0 || length.value !in 1uL..4_096uL)
            return@memScoped null
        val bytes = ByteArray(length.value.toInt())
        val result = bytes.usePinned { sysctlbyname(name, it.addressOf(0), length.ptr, null, 0uL) }
        if (result != 0 || length.value > bytes.size.toULong()) return@memScoped null
        val end = bytes.indexOf(0).takeIf { it >= 0 } ?: length.value.toInt()
        experienceHardwareLabel(bytes.decodeToString(endIndex = end))
    }
}.getOrNull()

private fun readSysctlInt(name: String): Int? = runCatching {
    memScoped {
        val value = alloc<IntVar>()
        val length = alloc<size_tVar>()
        length.value = sizeOf<IntVar>().toULong()
        if (sysctlbyname(name, value.ptr, length.ptr, null, 0uL) == 0 && length.value == sizeOf<IntVar>().toULong())
            value.value else null
    }
}.getOrNull()

internal actual fun createExperienceNetworkClient(route: DownloadRouteSnapshot?, timeouts: ExperienceImageTimeouts): ExperienceNetworkTransport {
    val client = platformHttpClient(false, route, observeRelayTraffic = true) {
    install(HttpTimeout) {
        // Darwin supports socket/request timeouts; it has no separate Ktor connect timeout.
        socketTimeoutMillis = timeouts.idleMillis
        requestTimeoutMillis = timeouts.totalMillis
    }
    }
    return ExperienceNetworkTransport(client.asNetworkClient()) { client.close() }
}

internal actual fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    // Apple's thermal notifications begin only after the first thermalState read.
    NSProcessInfo.processInfo.thermalState
    var released = false
    val observers = listOf(
        NSProcessInfoPowerStateDidChangeNotification,
        NSProcessInfoThermalStateDidChangeNotification,
        UIApplicationDidReceiveMemoryWarningNotification,
    ).map { name ->
        center.addObserverForName(name, null, NSOperationQueue.mainQueue) {
            if (!released) changed(name == UIApplicationDidReceiveMemoryWarningNotification)
        }
    }
    return {
        if (!released) {
            released = true
            observers.forEach { center.removeObserver(it) }
        }
    }
}
