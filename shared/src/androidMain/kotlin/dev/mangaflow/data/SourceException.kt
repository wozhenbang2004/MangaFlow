package dev.mangaflow.data

import java.io.IOException

actual class SourceException actual constructor(
    message: String,
    actual val sourceUrl: String,
    actual val retryAfterSeconds: Long,
    cause: Throwable?,
    actual val httpStatus: Int?,
) : IOException(message, cause)
