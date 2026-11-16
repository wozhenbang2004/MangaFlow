package dev.mangaflow.experience

/** Public, non-unique hardware information from the device running this application. */
data class ExperienceDeviceInfo(
    val platformName: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val processorName: String? = null,
    /** Reported ISA/ABI; a model name is never inferred from this value. */
    val processorArchitecture: String? = null,
    val physicalProcessorCount: Int? = null,
    val logicalProcessorCount: Int? = null,
    /** Reported maximum, not current frequency or a performance promise. */
    val processorMaxFrequencyHz: Long? = null,
)

enum class ExperienceProcessMemoryKind {
    /** Resident physical pages, including pages shared with other processes. */
    RESIDENT,
    /** Shared pages are apportioned between processes; not interchangeable with RSS. */
    PROPORTIONAL,
    /** The managed runtime only, excluding native allocations and most graphics memory. */
    MANAGED_HEAP,
}

internal fun experienceHardwareLabel(value: String?): String? = value?.trim()
    ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) && !it.equals("null", ignoreCase = true) }

