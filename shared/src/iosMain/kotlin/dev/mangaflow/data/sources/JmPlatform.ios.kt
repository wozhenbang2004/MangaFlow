package dev.mangaflow.data.sources

import dev.mangaflow.data.Page
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot

actual fun createJmBodySource(context: PlatformContext): BodySource? = null

actual suspend fun materializeJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): Page? = null

actual suspend fun decryptedJmPageBytes(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): ByteArray? = null

internal actual fun createJmDownloadSession(
    context: PlatformContext, mangaId: String, chapterId: String, route: DownloadRouteSnapshot,
): JmDownloadSession? = null
