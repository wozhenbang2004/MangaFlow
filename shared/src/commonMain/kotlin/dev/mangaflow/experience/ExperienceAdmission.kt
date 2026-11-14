package dev.mangaflow.experience

/** A queue state, never an HTTP failure or a completed transfer. */
internal enum class AdmissionWaitReason {
    NO_NETWORK, STORAGE_LOW, THERMAL, MEMORY_PRESSURE, BACKGROUND_RESTRICTED,
    READING_PRIORITY, SOURCE_BACKOFF, SOURCE_LIMIT, GLOBAL_LIMIT, CHAPTER_LIMIT, FAIR_QUEUE,
}

/** Remaining time is monotonic; consumers may convert it to their persisted wall-clock deadline. */
internal data class AdmissionWait(val reason: AdmissionWaitReason, val retryAfterMillis: Long? = null)

/** A bounded worker can persist its wait and release the platform execution slot. */
internal class ExperienceAdmissionDeferred(val wait: AdmissionWait) : Exception("等待下载资源")
