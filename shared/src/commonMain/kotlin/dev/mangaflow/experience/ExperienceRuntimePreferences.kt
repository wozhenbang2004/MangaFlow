package dev.mangaflow.experience

import kotlinx.serialization.json.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind

enum class CacheEvictionOrder { UNUSED_FIRST, LEAST_RECENT, LARGEST_FIRST }
enum class ExperienceRuntimeGroup { READING, PARALLEL, CACHE, SOURCES, BACKGROUND, DEVICE }

/** User-controlled pipeline parameters. Null follows the platform/selected profile, never a hidden manual ceiling. */
data class ExperienceRuntimePreferences(
    val nextChapterPages: Int = 3,
    val predictionSeconds: Int? = null,
    val idleWindowSeconds: Int = 8,
    val prefetchRetries: Int = 2,
    val prefetchRetryBaseSeconds: Int = 2,
    val prefetchRetryMaxSeconds: Int = 8,
    val previewWidthPx: Int = 1600,
    val previewHeightPx: Int = 4096,
    val standbyPreviewWidthPx: Int = 512,
    val standbyPreviewHeightPx: Int = 1024,
    val chapterPreparationRequests: Int? = null,
    val tileDecodeRequests: Int? = null,
    val chapterPreparationTimeoutSeconds: Int = 25,
    val networkCheckTimeoutSeconds: Int = 240,
    val imageConnectSeconds: Int = 20,
    val imageTotalMinutes: Int = 30,
    val materializationMiB: Int? = null,
    val maxImageMiB: Int = 96,
    val scriptDecodeMiPixels: Int = 16,
    val fallbackDecodeMiPixels: Int? = null,
    val resourceSampleSeconds: Int = 10,
    val cacheSampleSeconds: Int = 2,
    val diskReserveMiB: Int? = null,
    val diskRecoveryMiB: Int = 64,
    val catalogMiB: Int? = null,
    val catalogMemoryMiB: Int = 8,
    val catalogMemoryEntries: Int = 4,
    val memoryRecoverySeconds: Int = 60,
    val thermalRecoverySeconds: Int = 30,
    val warmRecoverySeconds: Int = 15,
    val powerRecoverySeconds: Int = 15,
    val cpuRecoverySeconds: Int = 20,
    val cpuBusyPercent: Int = 85,
    val heapBusyPercent: Int = 88,
    val lowBatteryPercent: Int = 20,
    // Legacy persisted name: now controls age-based disk expiry only. Capacity is independent.
    val automaticCleanup: Boolean = true,
    val evictionOrder: CacheEvictionOrder = CacheEvictionOrder.UNUSED_FIRST,
) {
    val maxImageBytes: Long get() = maxImageMiB.toLong() * EXPERIENCE_MIB
    fun checked(): ExperienceRuntimePreferences = ExperienceRuntimeField.entries.fold(this) { value, field ->
        field.withValue(value, field.value(value))
    }
}

/** One catalogue drives the advanced editor, serialization and reset behavior. Sliders are suggestions, not limits. */
enum class ExperienceRuntimeField(
    val label: String, val unit: String, val group: ExperienceRuntimeGroup,
    val minimum: Int = 1, val followsAutomatic: Boolean = false, val semanticMaximum: Int? = null,
    val help: String,
) {
    NEXT_CHAPTER("下章原图", "页", ExperienceRuntimeGroup.READING, minimum = 0, help = "紧接当前章节的原图准备范围；0关闭。"),
    PREDICTION("预测时间", "秒", ExperienceRuntimeGroup.READING, followsAutomatic = true, help = "根据实际翻页速度预测的时间跨度。"),
    IDLE_WINDOW("静止收拢", "秒", ExperienceRuntimeGroup.READING, help = "停止翻页后收拢自动窗口的等待时间。"),
    PREFETCH_RETRIES("预读重试", "次", ExperienceRuntimeGroup.READING, minimum = 0, help = "每页真实失败后的额外尝试；排队和资源暂停不计入。"),
    RETRY_BASE("初次重试", "秒", ExperienceRuntimeGroup.READING, help = "真实失败后首次重试间隔。"),
    RETRY_MAX("重试间隔", "秒", ExperienceRuntimeGroup.READING, help = "后续预读重试的最长间隔；不改变服务端冷却。"),
    PREVIEW_WIDTH("阅读预览宽", "px", ExperienceRuntimeGroup.READING, help = "解码显示图像的目标宽度，不是预读页数；磁盘原图与放大细节保留。"),
    PREVIEW_HEIGHT("阅读预览高", "px", ExperienceRuntimeGroup.READING, help = "解码显示图像的目标高度，不是预读页数；磁盘原图与放大细节保留。"),
    STANDBY_WIDTH("备源预览宽", "px", ExperienceRuntimeGroup.SOURCES, help = "备源内存预览的目标宽度。"),
    STANDBY_HEIGHT("备源预览高", "px", ExperienceRuntimeGroup.SOURCES, help = "备源内存预览的目标高度。"),
    CHAPTER_PREPARATION("目录准备", "个", ExperienceRuntimeGroup.PARALLEL, followsAutomatic = true, help = "同时解析下载目录的实际许可数；自动为2。"),
    TILE_DECODES("分块解码", "个", ExperienceRuntimeGroup.PARALLEL, followsAutomatic = true, help = "Windows/iOS区域图片的解码许可；Android由原生图片引擎管理。"),
    CHAPTER_TIMEOUT("目录等待", "秒", ExperienceRuntimeGroup.BACKGROUND, help = "下载目录准备请求的时间预算。"),
    CHECK_TIMEOUT("检查时限", "秒", ExperienceRuntimeGroup.BACKGROUND, help = "一轮线路检查的整体时间预算。"),
    IMAGE_CONNECT("图片连接", "秒", ExperienceRuntimeGroup.BACKGROUND, help = "图片建立连接的等待；平台不支持独立连接计时的端使用请求时限。"),
    IMAGE_TOTAL("单图总时限", "分钟", ExperienceRuntimeGroup.BACKGROUND, help = "单次图片传输的整体时限；无新数据等待单独在多源恢复中配置。"),
    MATERIALIZATION("原图处理", "MiB", ExperienceRuntimeGroup.CACHE, followsAutomatic = true, help = "后台原图处理工作预算，含脚本压缩缓冲及原生JM还原暂用内存，与磁盘窗口分开。当前页晋升后让它完成；原生像素使用保守预留，不是实测RSS。"),
    MAX_IMAGE("单图压缩", "MiB", ExperienceRuntimeGroup.CACHE, help = "脚本和原生读取单图压缩数据/处理结果的允许大小；平台数组和可用内存仍有实际限制。"),
    SCRIPT_PIXELS("还原解码", "Mpx", ExperienceRuntimeGroup.CACHE, help = "脚本及原生JM图片还原前的整图像素预算；每Mpx为1048576像素。"),
    FALLBACK_PIXELS("整图解码", "Mpx", ExperienceRuntimeGroup.CACHE, followsAutomatic = true, help = "缺少原生区域解码时的像素预算；自动Windows16、iOS8，每Mpx为1048576像素。"),
    RESOURCE_SAMPLE("设备采样", "秒", ExperienceRuntimeGroup.DEVICE, help = "应用或实际后台工作运行期间读取设备信息的间隔。"),
    CACHE_SAMPLE("缓存采样", "秒", ExperienceRuntimeGroup.DEVICE, help = "缓存用量与阅读准备状态检查的间隔；同一采样任务合并重复请求。"),
    DISK_RESERVE("磁盘留空", "MiB", ExperienceRuntimeGroup.CACHE, minimum = 0, followsAutomatic = true, help = "自动空间保护的留空量；显式0只受真实写入结果约束。"),
    DISK_RECOVERY("恢复余量", "MiB", ExperienceRuntimeGroup.CACHE, minimum = 0, help = "暂停磁盘写入后额外恢复余量，避免反复启停。"),
    CATALOG("目录磁盘", "MiB", ExperienceRuntimeGroup.CACHE, followsAutomatic = true, help = "可回收漫画目录的保留目标；当前阅读与离线下载依赖始终受保护。"),
    CATALOG_MEMORY("目录内存", "MiB", ExperienceRuntimeGroup.CACHE, help = "已解析章节目录的可复用内存预算；当前屏幕引用独立保留。"),
    CATALOG_ENTRIES("目录驻留", "项", ExperienceRuntimeGroup.CACHE, help = "可复用的已解析章节目录数。"),
    MEMORY_RECOVERY("内存恢复", "秒", ExperienceRuntimeGroup.DEVICE, minimum = 0, help = "系统内存告警消失后的自动策略恢复等待。"),
    THERMAL_RECOVERY("温度恢复", "秒", ExperienceRuntimeGroup.DEVICE, minimum = 0, help = "温度告警消失后的自动策略恢复等待。"),
    WARM_RECOVERY("轻热恢复", "秒", ExperienceRuntimeGroup.DEVICE, minimum = 0, help = "轻度温度压力消失后的自动策略恢复等待。"),
    POWER_RECOVERY("节电恢复", "秒", ExperienceRuntimeGroup.DEVICE, minimum = 0, help = "系统省电状态解除后的自动策略恢复等待。"),
    CPU_RECOVERY("负载恢复", "秒", ExperienceRuntimeGroup.DEVICE, minimum = 0, help = "处理器高负载消失后的自动策略恢复等待。"),
    CPU_BUSY("CPU繁忙", "%", ExperienceRuntimeGroup.DEVICE, semanticMaximum = 100, help = "触发自动资源调整的实际整机CPU负载阈值。"),
    HEAP_BUSY("堆压力", "%", ExperienceRuntimeGroup.DEVICE, semanticMaximum = 100, help = "触发自动资源调整的应用堆占比阈值，不是整机RAM占比。"),
    LOW_BATTERY("低电量", "%", ExperienceRuntimeGroup.DEVICE, minimum = 0, semanticMaximum = 100, help = "启用电量保护且未充电时的自动调整阈值。"),
    ;

    fun value(p: ExperienceRuntimePreferences): Int? = when (this) {
        NEXT_CHAPTER -> p.nextChapterPages; PREDICTION -> p.predictionSeconds; IDLE_WINDOW -> p.idleWindowSeconds
        PREFETCH_RETRIES -> p.prefetchRetries; RETRY_BASE -> p.prefetchRetryBaseSeconds; RETRY_MAX -> p.prefetchRetryMaxSeconds
        PREVIEW_WIDTH -> p.previewWidthPx; PREVIEW_HEIGHT -> p.previewHeightPx; STANDBY_WIDTH -> p.standbyPreviewWidthPx; STANDBY_HEIGHT -> p.standbyPreviewHeightPx
        CHAPTER_PREPARATION -> p.chapterPreparationRequests; TILE_DECODES -> p.tileDecodeRequests
        CHAPTER_TIMEOUT -> p.chapterPreparationTimeoutSeconds; CHECK_TIMEOUT -> p.networkCheckTimeoutSeconds; IMAGE_CONNECT -> p.imageConnectSeconds; IMAGE_TOTAL -> p.imageTotalMinutes
        MATERIALIZATION -> p.materializationMiB; MAX_IMAGE -> p.maxImageMiB; SCRIPT_PIXELS -> p.scriptDecodeMiPixels; FALLBACK_PIXELS -> p.fallbackDecodeMiPixels
        RESOURCE_SAMPLE -> p.resourceSampleSeconds; CACHE_SAMPLE -> p.cacheSampleSeconds; DISK_RESERVE -> p.diskReserveMiB
        DISK_RECOVERY -> p.diskRecoveryMiB; CATALOG -> p.catalogMiB; CATALOG_MEMORY -> p.catalogMemoryMiB; CATALOG_ENTRIES -> p.catalogMemoryEntries; MEMORY_RECOVERY -> p.memoryRecoverySeconds
        THERMAL_RECOVERY -> p.thermalRecoverySeconds; WARM_RECOVERY -> p.warmRecoverySeconds; POWER_RECOVERY -> p.powerRecoverySeconds; CPU_RECOVERY -> p.cpuRecoverySeconds
        CPU_BUSY -> p.cpuBusyPercent; HEAP_BUSY -> p.heapBusyPercent; LOW_BATTERY -> p.lowBatteryPercent
    }

    fun withValue(p: ExperienceRuntimePreferences, requested: Int?): ExperienceRuntimePreferences {
        val n = (requested ?: if (followsAutomatic) null else value(ExperienceRuntimePreferences()))
            ?.coerceAtLeast(minimum)?.let { if (semanticMaximum == null) it else it.coerceAtMost(semanticMaximum) }
        return when (this) {
            NEXT_CHAPTER -> p.copy(nextChapterPages = n!!); PREDICTION -> p.copy(predictionSeconds = n); IDLE_WINDOW -> p.copy(idleWindowSeconds = n!!)
            PREFETCH_RETRIES -> p.copy(prefetchRetries = n!!); RETRY_BASE -> p.copy(prefetchRetryBaseSeconds = n!!); RETRY_MAX -> p.copy(prefetchRetryMaxSeconds = n!!)
            PREVIEW_WIDTH -> p.copy(previewWidthPx = n!!); PREVIEW_HEIGHT -> p.copy(previewHeightPx = n!!); STANDBY_WIDTH -> p.copy(standbyPreviewWidthPx = n!!); STANDBY_HEIGHT -> p.copy(standbyPreviewHeightPx = n!!)
            CHAPTER_PREPARATION -> p.copy(chapterPreparationRequests = n); TILE_DECODES -> p.copy(tileDecodeRequests = n)
            CHAPTER_TIMEOUT -> p.copy(chapterPreparationTimeoutSeconds = n!!); CHECK_TIMEOUT -> p.copy(networkCheckTimeoutSeconds = n!!); IMAGE_CONNECT -> p.copy(imageConnectSeconds = n!!); IMAGE_TOTAL -> p.copy(imageTotalMinutes = n!!)
            MATERIALIZATION -> p.copy(materializationMiB = n); MAX_IMAGE -> p.copy(maxImageMiB = n!!); SCRIPT_PIXELS -> p.copy(scriptDecodeMiPixels = n!!); FALLBACK_PIXELS -> p.copy(fallbackDecodeMiPixels = n)
            RESOURCE_SAMPLE -> p.copy(resourceSampleSeconds = n!!); CACHE_SAMPLE -> p.copy(cacheSampleSeconds = n!!); DISK_RESERVE -> p.copy(diskReserveMiB = n)
            DISK_RECOVERY -> p.copy(diskRecoveryMiB = n!!); CATALOG -> p.copy(catalogMiB = n); CATALOG_MEMORY -> p.copy(catalogMemoryMiB = n!!); CATALOG_ENTRIES -> p.copy(catalogMemoryEntries = n!!); MEMORY_RECOVERY -> p.copy(memoryRecoverySeconds = n!!)
            THERMAL_RECOVERY -> p.copy(thermalRecoverySeconds = n!!); WARM_RECOVERY -> p.copy(warmRecoverySeconds = n!!); POWER_RECOVERY -> p.copy(powerRecoverySeconds = n!!); CPU_RECOVERY -> p.copy(cpuRecoverySeconds = n!!)
            CPU_BUSY -> p.copy(cpuBusyPercent = n!!); HEAP_BUSY -> p.copy(heapBusyPercent = n!!); LOW_BATTERY -> p.copy(lowBatteryPercent = n!!)
        }
    }

    fun effectiveValue(budget: ExperienceBudget, usage: ExperienceUsage): Int? {
        val p = budget.runtime
        val desktop = platformKind == PlatformKind.DESKTOP
        if (this == TILE_DECODES && platformKind == PlatformKind.ANDROID) return null
        if (this == IMAGE_CONNECT) return (effectiveImageTransportTimeoutMillis(p.imageConnectSeconds.toLong() * 1_000L) / 1_000L).toInt()
        if (this == IMAGE_TOTAL) return (effectiveImageTransportTimeoutMillis(p.imageTotalMinutes.toLong() * 60_000L) / 60_000L).toInt()
        return value(p) ?: when (this) {
            PREDICTION -> when (budget.mode) { ExperienceMode.ECO -> 2; ExperienceMode.AUTO -> 5; ExperienceMode.PERFORMANCE -> 8 }
            CHAPTER_PREPARATION -> 2
            TILE_DECODES -> if (desktop) 2 else 1
            MATERIALIZATION -> (budget.materializationBytes / EXPERIENCE_MIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            FALLBACK_PIXELS -> if (desktop) 16 else if (platformKind == PlatformKind.IOS) 8 else null
            DISK_RESERVE -> (budget.diskReserveBytes / EXPERIENCE_MIB).toInt()
            CATALOG -> (minOf(budget.diskCacheBytes / 16L, 32L * EXPERIENCE_MIB) / EXPERIENCE_MIB).toInt()
            else -> null
        }
    }
}

internal fun ExperienceRuntimePreferences.toJson(): JsonObject = buildJsonObject {
    ExperienceRuntimeField.entries.forEach { field -> field.value(this@toJson)?.let { put(field.name, it) } }
    put("automaticCleanup", automaticCleanup); put("evictionOrder", evictionOrder.name)
}

internal fun runtimePreferencesFromJson(value: JsonObject?): ExperienceRuntimePreferences {
    if (value == null) return ExperienceRuntimePreferences()
    val numbers = ExperienceRuntimeField.entries.fold(ExperienceRuntimePreferences()) { current, field ->
        val parsed = value[field.name]?.jsonPrimitive?.intOrNull
        if (parsed == null) current else field.withValue(current, parsed)
    }
    return numbers.copy(
        automaticCleanup = value["automaticCleanup"]?.jsonPrimitive?.booleanOrNull ?: true,
        evictionOrder = CacheEvictionOrder.entries.firstOrNull { it.name == value["evictionOrder"]?.jsonPrimitive?.contentOrNull }
            ?: CacheEvictionOrder.UNUSED_FIRST,
    )
}

/**
 * OkHttp's Android/JVM builders store each timeout as Int milliseconds. This is an SDK
 * representation limit, not a limit on the user's saved preference. Darwin keeps Long values.
 */
val imageTransportTimeoutLimitMillis: Long?
    get() = when (platformKind) {
        PlatformKind.ANDROID, PlatformKind.DESKTOP -> Int.MAX_VALUE.toLong()
        PlatformKind.IOS -> null
    }

/** Exposed to the editor so a requested value and the actual platform deadline stay distinct. */
fun effectiveImageTransportTimeoutMillis(requestedMillis: Long): Long =
    imageTransportTimeoutLimitMillis?.let { requestedMillis.coerceAtMost(it) } ?: requestedMillis

/** A request freezes its transport deadlines; applying settings affects subsequently admitted work. */
internal data class ExperienceImageTimeouts(val connectMillis: Long = 20_000L,
    val idleMillis: Long = 45_000L, val totalMillis: Long = 30L * 60_000L)

internal fun ExperienceImageTimeouts.forCurrentPlatform() = copy(
    connectMillis = effectiveImageTransportTimeoutMillis(connectMillis),
    idleMillis = effectiveImageTransportTimeoutMillis(idleMillis),
    totalMillis = effectiveImageTransportTimeoutMillis(totalMillis),
)

internal fun ExperienceBudget.imageTimeouts() = ExperienceImageTimeouts(
    runtime.imageConnectSeconds.toLong() * 1_000L,
    recovery.imageTimeoutSeconds.toLong() * 1_000L,
    runtime.imageTotalMinutes.toLong() * 60_000L,
).forCurrentPlatform()
