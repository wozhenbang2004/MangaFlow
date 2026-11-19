package dev.mangaflow.anime.player

import dev.mangaflow.platform.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import okio.buffer
import okio.use as useOkio

/** Serves only the lease's completed files. HLS relative references never leave this closed map. */
internal class AnimeLocalStreamBridge private constructor(
    private val lease: AnimeLocalMediaLease,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val token = randomUuid().replace("-", "") + randomUuid().replace("-", "")
    private lateinit var server: AnimeLoopbackServer
    val mediaUrl: String get() = "http://127.0.0.1:${server.port}/$token/${lease.entryName}"

    fun close() {
        if (::server.isInitialized) server.close()
        scope.cancel()
    }

    private suspend fun handle(connection: AnimeLoopbackConnection) {
        val output = ResponseWriter(connection)
        try {
            val request = readRequest(connection)
            if (request.method !in setOf("GET", "HEAD")) { output.error(405, "Method not allowed"); return }
            val prefix = "/$token/"
            val path = request.target.substringBefore('?')
            val resource = if (path.startsWith(prefix)) lease.resources[path.removePrefix(prefix)] else null
            if (resource == null) { output.error(404, "Unknown offline resource"); return }
            check(resource.file.isFile && resource.file.length() == resource.size) { "Offline resource changed" }
            val rangeHeader = request.headers["range"]
            val range = if (rangeHeader == null) 0L to (resource.size - 1L) else animeByteRange(rangeHeader, resource.size)
            if (range == null) {
                output.headers(416, mapOf("Content-Range" to "bytes */${resource.size}", "Content-Length" to "0"))
                return
            }
            val length = range.second - range.first + 1L
            val headers = linkedMapOf("Content-Type" to resource.mimeType, "Content-Length" to length.toString(),
                "Accept-Ranges" to "bytes", "Cache-Control" to "private, no-store")
            if (rangeHeader != null) headers["Content-Range"] = "bytes ${range.first}-${range.second}/${resource.size}"
            output.headers(if (rangeHeader == null) 200 else 206, headers)
            if (request.method == "HEAD") return
            FileSystem.SYSTEM.openReadOnly(resource.file.nativePath).useOkio { file ->
                file.source(range.first).buffer().useOkio { input ->
                    val buffer = ByteArray(32 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "Offline resource truncated" }
                        output.bytes(buffer, count)
                        remaining -= count
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Throwable) { if (!output.started) runCatching { output.error(500, "Offline resource unavailable") } }
        finally { connection.close() }
    }

    companion object {
        suspend fun create(lease: AnimeLocalMediaLease, scope: CoroutineScope): AnimeLocalStreamBridge {
            var pending: AnimeLocalStreamBridge? = null
            return try {
                withContext(Dispatchers.IO) {
                    AnimeLocalStreamBridge(lease, scope).also { bridge ->
                        pending = bridge
                        bridge.server = AnimeLoopbackServer(bridge.scope, bridge::handle)
                    }
                }
            } catch (failure: Throwable) { pending?.close(); throw failure }
        }
    }
}

internal fun animeByteRange(value: String, size: Long): Pair<Long, Long>? {
    if (size <= 0) return null
    val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(value.trim()) ?: return null
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    if (first.isEmpty()) {
        val count = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (size - count.coerceAtMost(size)) to (size - 1)
    }
    val start = first.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
    val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
    return (start to end).takeIf { end >= start }
}
