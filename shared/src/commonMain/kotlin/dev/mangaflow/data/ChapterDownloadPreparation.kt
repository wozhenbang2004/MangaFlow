package dev.mangaflow.data

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.platform.sha256
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class PreparedChapterDownload(val pages: List<Page>, val headers: Map<String, String>)

/** Owned by one preparation; never installed in an ordinary reader's request scope. */
internal class ChapterDownloadTransport(
    val route: DownloadRouteSnapshot,
    val client: NetworkClient,
) : AbstractCoroutineContextElement(Key) {
    // Completed metadata may survive a retry on this exact route; ordinary reads have no scope.
    val cacheKey: String = sha256((route.mode.name + "|" + route.proxy?.type?.name.orEmpty() + "|" +
        route.proxy?.host.orEmpty() + "|" + route.proxy?.port?.toString().orEmpty()).encodeToByteArray())
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    companion object Key : CoroutineContext.Key<ChapterDownloadTransport>
}
