package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext
import java.io.File
import java.nio.file.Files

internal actual fun readExperienceResources(context: PlatformContext): ExperienceResources = ExperienceResources(
    // JVM heap limits are not a reliable measurement of physical RAM or OS memory pressure.
    freeDiskBytes = runCatching {
        generateSequence(File(context.cacheRoot).absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }?.let { Files.getFileStore(it.toPath()).usableSpace }
    }.getOrNull(),
)

/** No portable event source for desktop OS power/thermal state; leave those values unknown. */
internal actual fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit = {}
