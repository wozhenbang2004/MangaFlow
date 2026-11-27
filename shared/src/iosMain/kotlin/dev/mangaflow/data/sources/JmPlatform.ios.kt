package dev.mangaflow.data.sources

import dev.mangaflow.data.Page
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchWork

actual fun createJmBodySource(context: PlatformContext): BodySource? = null

internal actual fun nativeJmImageEpoch(context: PlatformContext): String = "unsupported-native-jm"

actual suspend fun materializeJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    onBytes: ((Long) -> Unit)?,
    retry: Boolean,
): Page? = null

internal actual suspend fun cachedJmReaderPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): Page? = null

internal actual suspend fun prefetchJmPage(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
    budget: PrefetchByteBudget, work: PrefetchWork,
): Page? = null

actual suspend fun decryptedJmPageBytes(
    context: PlatformContext, mangaId: String, chapterId: String, page: Page,
): ByteArray? = null

internal actual fun createJmDownloadSession(
    context: PlatformContext, mangaId: String, chapterId: String, route: DownloadRouteSnapshot,
): JmDownloadSession? = null
