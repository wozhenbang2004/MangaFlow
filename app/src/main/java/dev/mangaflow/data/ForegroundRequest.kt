package dev.mangaflow.data

import kotlinx.coroutines.withTimeoutOrNull

/** A complete foreground operation has one budget, including all pages and queued requests. */
internal suspend fun <T> foregroundRequest(
    operation: String,
    timeoutMillis: Long = 25_000L,
    block: suspend () -> T,
): T {
    // A nullable payload is valid. Parent cancellation still propagates; only OUR timeout
    // becomes an ordinary recoverable error, so CancellationException handlers cannot hide it.
    val result = withTimeoutOrNull(timeoutMillis) { Result.success(block()) }
        ?: throw SourceException("$operation 超时，请检查网络后重试。")
    return result.getOrThrow()
}
