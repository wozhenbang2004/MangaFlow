package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext
import java.io.File
import java.nio.file.Files
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import dev.mangaflow.net.DownloadRouteSnapshot
import coil3.network.ktor3.asNetworkClient
import dev.mangaflow.net.platformHttpClient
import io.ktor.client.plugins.HttpTimeout
import oshi.SystemInfo

/** OSHI's memoized hardware/native adapters are reused, without an independent monitor. */
private val desktopSystemInfo by lazy { runCatching { SystemInfo() }.getOrNull() }
private val desktopDeviceInfo by lazy {
    val hardware = runCatching { desktopSystemInfo?.hardware }.getOrNull()
    val processor = runCatching { hardware?.processor }.getOrNull()
    val computer = runCatching { hardware?.computerSystem }.getOrNull()
    ExperienceDeviceInfo(
        platformName = experienceHardwareLabel(System.getProperty("os.name")),
        osVersion = experienceHardwareLabel(System.getProperty("os.version")),
        deviceModel = listOfNotNull(
            runCatching { experienceHardwareLabel(computer?.manufacturer) }.getOrNull(),
            runCatching { experienceHardwareLabel(computer?.model) }.getOrNull(),
        ).distinct().joinToString(" ").ifEmpty { null },
        processorName = runCatching { experienceHardwareLabel(processor?.processorIdentifier?.name) }.getOrNull(),
        processorArchitecture = experienceHardwareLabel(System.getProperty("os.arch")),
        physicalProcessorCount = runCatching { processor?.physicalProcessorCount?.takeIf { it > 0 } }.getOrNull(),
        logicalProcessorCount = runCatching { processor?.logicalProcessorCount?.takeIf { it > 0 } }.getOrNull(),
        processorMaxFrequencyHz = runCatching { processor?.maxFreq?.takeIf { it > 0L } }.getOrNull(),
    )
}

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val runtime = Runtime.getRuntime()
    val os = runCatching { ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean }.getOrNull()
    val physical = runCatching { desktopSystemInfo?.hardware?.memory }.getOrNull()
    val totalMemory = runCatching { physical?.total?.takeIf { it > 0L } }.getOrNull()
        ?: runCatching { os?.totalMemorySize?.takeIf { it > 0L } }.getOrNull()
    val disk = runCatching {
        generateSequence(File(context.cacheRoot).absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }?.let { Files.getFileStore(it.toPath()) }
    }.getOrNull()
    val resident = runCatching {
        desktopSystemInfo?.operatingSystem?.let { system ->
            system.getProcess(system.processId)?.residentMemory?.takeIf { it > 0L }
        }
    }.getOrNull()
    return ExperienceResources(
        device = desktopDeviceInfo,
        sampledAtMillis = System.currentTimeMillis(),
        processResidentBytes = resident,
        processCpuLoad = runCatching { os?.processCpuLoad?.takeIf { it.isFinite() && it in 0.0..1.0 } }.getOrNull(),
        // Heap load and physical availability remain separately named measurements.
        processHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        processHeapLimitBytes = runtime.maxMemory(),
        totalMemoryBytes = totalMemory,
        processorCount = runtime.availableProcessors().takeIf { it > 0 },
        // OSHI reports availability, including reclaimable memory where the OS provides it.
        // JDK freeMemorySize is only free memory and is not silently relabelled as available.
        availableMemoryBytes = runCatching { physical?.available?.takeIf { it >= 0L } }.getOrNull(),
        lowMemoryDevice = totalMemory?.let { it < 4L * 1024 * EXPERIENCE_MIB },
        cpuLoad = runCatching { os?.cpuLoad?.takeIf { it.isFinite() && it in 0.0..1.0 } }.getOrNull(),
        freeDiskBytes = runCatching { disk?.usableSpace?.takeIf { it >= 0L } }.getOrNull(),
        totalDiskBytes = runCatching { disk?.totalSpace?.takeIf { it > 0L } }.getOrNull(),
    )
}

/** No portable event source for desktop OS power/thermal state; leave those values unknown. */
internal actual fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit = {}

internal actual fun createExperienceNetworkClient(route: DownloadRouteSnapshot?, timeouts: ExperienceImageTimeouts): ExperienceNetworkTransport {
    val effective = timeouts.forCurrentPlatform()
    val client = platformHttpClient(false, route, observeRelayTraffic = true) {
        install(HttpTimeout) {
            connectTimeoutMillis = effective.connectMillis
            socketTimeoutMillis = effective.idleMillis
            requestTimeoutMillis = effective.totalMillis
        }
    }
    return ExperienceNetworkTransport(client.asNetworkClient()) { client.close() }
}
