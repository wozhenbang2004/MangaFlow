package dev.mangaflow.data

/** Android/JVM retain IOException compatibility; Apple uses the native Kotlin exception. */
expect class SourceException(
    message: String,
    sourceUrl: String = "",
    retryAfterSeconds: Long = 0,
    cause: Throwable? = null,
    httpStatus: Int? = null,
) : Exception {
    val sourceUrl: String
    val retryAfterSeconds: Long
    val httpStatus: Int?
}
