package dev.mangaflow.data

/** Explicit user values; there are no device-profile or business maxima on these settings. */
data class SourceCheckPreferences(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 30,
    val concurrency: Int = 8,
    val timeoutSeconds: Int = 25,
    val retries: Int = 1,
) {
    fun checked(): SourceCheckPreferences = apply {
        require(intervalMinutes > 0 && concurrency > 0 && timeoutSeconds > 0 && retries >= 0) {
            "周期、并发和超时需为正整数，重试次数不能为负数。"
        }
    }
    val intervalMillis: Long get() = intervalMinutes.toLong() * 60_000L
}

data class SourceCheckScheduleState(
    val running: Boolean = false,
    val lastCompletedAt: Long = 0L,
    val nextCheckAt: Long = 0L,
    val error: String? = null,
    val checkedWorks: Int = 0,
    val totalWorks: Int = 0,
)
