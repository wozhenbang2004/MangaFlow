package dev.mangaflow.data.sources

import dev.mangaflow.data.Page
import dev.mangaflow.platform.PlatformContext

/** Kotlin JVM adapter available to Android and desktop. iOS does not claim compatibility. */
expect fun createJmBodySource(context: PlatformContext): BodySource?

/** Materialize only the requested image. A raw JM CDN response is a scrambled image. */
expect suspend fun materializeJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): Page?

/** Used by background downloads, so it must not rely on an active Compose or ViewModel session. */
expect suspend fun decryptedJmPageBytes(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): ByteArray?
