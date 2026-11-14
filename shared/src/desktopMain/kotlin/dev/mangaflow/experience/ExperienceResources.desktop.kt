package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext
import java.io.File
import java.nio.file.Files
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import coil3.network.NetworkClient
import coil3.network.ktor3.asNetworkClient
import dev.mangaflow.net.platformHttpClient

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val runtime = Runtime.getRuntime()
    val os = runCatching { ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean }.getOrNull()
    return ExperienceResources(
        // Heap load and physical availability remain separately named measurements.
        processHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        processHeapLimitBytes = runtime.maxMemory(),
        availableMemoryBytes = runCatching { os?.freeMemorySize?.takeIf { it >= 0L } }.getOrNull(),
        lowMemoryDevice = runCatching { os?.totalMemorySize?.takeIf { it > 0L }?.let { it < 4L * 1024 * EXPERIENCE_MIB } }.getOrNull(),
        cpuLoad = runCatching { os?.cpuLoad?.takeIf { it.isFinite() && it in 0.0..1.0 } }.getOrNull(),
        freeDiskBytes = runCatching {
            generateSequence(File(context.cacheRoot).absoluteFile) { it.parentFile }
                .firstOrNull { it.exists() }?.let { Files.getFileStore(it.toPath()).usableSpace }
        }.getOrNull(),
    )
}

/** No portable event source for desktop OS power/thermal state; leave those values unknown. */
internal actual fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit = {}

internal actual fun createExperienceNetworkClient(): NetworkClient = platformHttpClient(false, null) {}.asNetworkClient()
