package dev.mangaflow.experience

/**
 * A sizing scenario, not an allocation, measured peak, or hard upper bound. The process
 * counter already includes image memory; only unfilled budgets are added to that baseline.
 */
data class ExperienceResourceForecast(
    val currentProcessBytes: Long?,
    val processMemoryKind: ExperienceProcessMemoryKind?,
    val observedProcessPeakBytes: Long?,
    val estimatedProcessPeakLowBytes: Long?,
    val estimatedProcessPeakHighBytes: Long?,
    val imageMemoryBudgetBytes: Long?,
    val encodedMemoryBudgetBytes: Long,
    val decodeWorkingLowBytes: Long?,
    val decodeWorkingHighBytes: Long?,
    val diskBudgetBytes: Long,
    val diskCurrentBytes: Long?,
    val diskObservedPeakBytes: Long?,
    val originalWindowBytes: Long,
    val decodeSampleCount: Int,
    val notes: List<String>,
)

/** The caller can pass an unsaved preview budget without changing any running policy. */
fun estimateExperienceResources(budget: ExperienceBudget, usage: ExperienceUsage): ExperienceResourceForecast {
    val resources = usage.resources
    val resident = resources.processResidentBytes?.takeIf { it >= 0L }
    val proportional = resources.processProportionalBytes?.takeIf { it >= 0L }
    val heap = resources.processHeapBytes?.takeIf { it >= 0L }
    val kind = when {
        resident != null -> ExperienceProcessMemoryKind.RESIDENT
        proportional != null -> ExperienceProcessMemoryKind.PROPORTIONAL
        heap != null -> ExperienceProcessMemoryKind.MANAGED_HEAP
        else -> null
    }
    val current = resident ?: proportional ?: heap
    val observedPeak = when (kind) {
        ExperienceProcessMemoryKind.RESIDENT -> usage.observedResidentPeakBytes
        ExperienceProcessMemoryKind.PROPORTIONAL -> usage.observedProportionalPeakBytes
        ExperienceProcessMemoryKind.MANAGED_HEAP -> usage.observedHeapPeakBytes
        else -> null
    }
    val imageBudget = budget.memoryCacheBytes?.coerceAtLeast(0L)
        ?: usage.memoryCacheBasisBytes?.takeIf { it > 0L }?.let { basis ->
            (basis.toDouble() * budget.memoryCacheFraction.coerceAtLeast(0.0)).toLong().coerceAtLeast(0L)
        }
    val encodedBudget = budget.materializationBytes.coerceAtLeast(0L)
    val sampleCount = usage.decodedSampleCount.coerceAtLeast(0)
    val decodeP90 = usage.decodedPageP90Bytes?.takeIf { it > 0L && sampleCount > 0 }
    val decodeMax = usage.decodedPageMaxBytes?.takeIf { it > 0L && sampleCount > 0 }
    val concurrentDecodes = budget.decodeRequests.coerceAtLeast(0).toLong()
    val decodeLow = if (concurrentDecodes == 0L) 0L else decodeP90?.let { multiplyBytes(it, concurrentDecodes) }
    val decodeHigh = if (concurrentDecodes == 0L) 0L else decodeMax?.let { multiplyBytes(maxOf(it, decodeP90 ?: it), concurrentDecodes) }
    val cacheGrowth = imageBudget?.let { target -> usage.memoryBytes?.let { (target - it.coerceAtLeast(0L)).coerceAtLeast(0L) } }
    val encodedGrowth = (encodedBudget - usage.materializationBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
    // Heap-only observations exclude native pixel buffers and cannot anchor an app-total forecast.
    val processBase = current?.takeIf { kind != ExperienceProcessMemoryKind.MANAGED_HEAP }
    val baseWithGrowth = if (processBase != null && cacheGrowth != null)
        addBytes(addBytes(processBase, cacheGrowth), encodedGrowth) else null
    val low = if (baseWithGrowth != null && decodeLow != null) addBytes(baseWithGrowth, decodeLow) else null
    val high = if (baseWithGrowth != null && decodeHigh != null) addBytes(baseWithGrowth, decodeHigh) else null
    val originalWindow = addBytes(addBytes(budget.adjacentPrefetchBytes, budget.nextChapterPrefetchBytes), budget.standbyPrefetchBytes)
    return ExperienceResourceForecast(
        currentProcessBytes = current,
        processMemoryKind = kind,
        observedProcessPeakBytes = observedPeak,
        estimatedProcessPeakLowBytes = low,
        estimatedProcessPeakHighBytes = high,
        imageMemoryBudgetBytes = imageBudget,
        encodedMemoryBudgetBytes = encodedBudget,
        decodeWorkingLowBytes = decodeLow,
        decodeWorkingHighBytes = decodeHigh,
        diskBudgetBytes = budget.diskCacheBytes.coerceAtLeast(0L),
        diskCurrentBytes = usage.diskBytes,
        diskObservedPeakBytes = usage.observedDiskPeakBytes,
        originalWindowBytes = originalWindow,
        decodeSampleCount = sampleCount,
        notes = buildList {
            if (sampleCount == 0) add("读几页后，按实际解码样本估算；没有用固定单页大小代填。")
            if (processBase == null) add("应用完整内存占用暂不可得；堆内存不能代替进程总量。")
            if (imageBudget == null || usage.memoryBytes == null) add("图片缓存尚未完成采样，配置影响暂不可计算。")
            add("区间使用近期解码 P90／最大样本 × 解码并发，再加当前进程、未填满的图片缓存与原图处理缓冲预算。")
            add("JM 还原的临时内存预留属于预算，不冒充已实测驻留；它与压缩缓冲共享原图处理额度。")
            val tileDecodes = ExperienceRuntimeField.TILE_DECODES.effectiveValue(budget, usage)
            add(if (tileDecodes == null) "这是同类内容的情景估算；原生图片引擎管理的瓦片驻留和临时峰值尚无完整样本。"
                else "这是同类内容的情景估算；长图瓦片单独使用 $tileDecodes 路解码，其驻留和临时峰值尚无完整样本。")
            add("解密副本、GPU／原生分配和新内容可能超过该区间；它不是整机内存上限或应用硬峰值。")
            add("磁盘窗口不会按同等大小占用内存；图片缓存已包含在进程用量中。")
            add("磁盘是共享软预算；正在阅读、写入及保留的内容可能使实际占用暂时超过预算。")
            add("CPU 峰值取决于真实任务与系统调度，无法由并发数换算。")
        },
    )
}

private fun addBytes(left: Long, right: Long): Long {
    val a = left.coerceAtLeast(0L)
    val b = right.coerceAtLeast(0L)
    return if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

private fun multiplyBytes(bytes: Long, count: Long): Long {
    if (bytes <= 0L || count <= 0L) return 0L
    return if (bytes > Long.MAX_VALUE / count) Long.MAX_VALUE else bytes * count
}
