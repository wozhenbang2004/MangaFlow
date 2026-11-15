@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.cacheDirectory
import platform.Foundation.*
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import dev.mangaflow.net.DownloadRouteSnapshot
import coil3.network.ktor3.asNetworkClient
import dev.mangaflow.net.platformHttpClient
import io.ktor.client.plugins.HttpTimeout

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources {
    val process = NSProcessInfo.processInfo
    return ExperienceResources(
        totalMemoryBytes = process.physicalMemory.toLong().takeIf { it > 0L },
        processorCount = process.activeProcessorCount.toInt().takeIf { it > 0 },
        // Process heap allowance, free physical RAM and CPU load remain genuinely unknown.
        lowPower = process.lowPowerModeEnabled,
        thermalPressure = when (process.thermalState) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> 0
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> 1
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> 2
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> 3
            else -> null
        },
        freeDiskBytes = runCatching {
            (NSFileManager.defaultManager.attributesOfFileSystemForPath(cacheDirectory(context).path, null)
                ?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
        }.getOrNull(),
    )
}

internal actual fun createExperienceNetworkClient(route: DownloadRouteSnapshot?): ExperienceNetworkTransport {
    val client = platformHttpClient(false, route) {
    install(HttpTimeout) {
        // Darwin supports socket/request timeouts; it has no separate Ktor connect timeout.
        socketTimeoutMillis = 45_000L
        requestTimeoutMillis = 30L * 60_000L
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
