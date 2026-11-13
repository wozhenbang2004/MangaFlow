package dev.mangaflow.data

actual class SourceException actual constructor(
    message: String,
    actual val sourceUrl: String,
    actual val retryAfterSeconds: Long,
    cause: Throwable?,
    actual val httpStatus: Int?,
) : Exception(message, cause)
