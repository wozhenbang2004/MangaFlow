package dev.mangaflow.data.sources

import dev.mangaflow.data.Page
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot

/** Kotlin JVM adapter available to Android and desktop. iOS does not claim compatibility. */
expect fun createJmBodySource(context: PlatformContext): BodySource?

/**
 * Materialize one image: raw JM CDN data may be scrambled.
 * [onBytes] observes actual positive network body reads, never cached file length.
 * [retry] refreshes an unusable materialized image through its native owner.
 */
expect suspend fun materializeJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    onBytes: ((Long) -> Unit)? = null,
    retry: Boolean = false,
): Page?

/** Used by background downloads, so it must not rely on an active Compose or ViewModel session. */
expect suspend fun decryptedJmPageBytes(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): ByteArray?

/** A background chapter owns and closes its transport; ordinary reading keeps its own client. */
internal interface JmDownloadSession {
    suspend fun pages(): List<Page>
    suspend fun read(page: Page): ByteArray
    fun close()
}

internal expect fun createJmDownloadSession(
    context: PlatformContext, mangaId: String, chapterId: String, route: DownloadRouteSnapshot,
): JmDownloadSession?
