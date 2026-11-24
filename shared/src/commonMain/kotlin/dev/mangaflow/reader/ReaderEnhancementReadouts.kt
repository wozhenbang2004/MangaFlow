package dev.mangaflow.reader

import androidx.compose.ui.unit.IntSize
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.EnhancementTaskPhase
import dev.mangaflow.enhancement.EnhancementTask
import dev.mangaflow.enhancement.PreparationMode
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.ReaderEnhancedPage
import dev.mangaflow.enhancement.ReaderEnhancementState
import dev.mangaflow.enhancement.engine.EngineStage
import dev.mangaflow.enhancement.ui.EnhancementReadout
import dev.mangaflow.platform.formatDecimal

internal data class ReaderEnhancementResolution(
    val valueLabel: String,
    val label: String,
    val originalLabel: String?,
    val description: String,
    val enhanced: Boolean,
)

private fun briefEnhancementDetail(value: String): String {
    val brief = value.replace('\n', ' ').trim()
    return if (brief.length > 48) brief.take(48) + "…" else brief
}

/** The caller supplies the exact page/source-matched completed asset, never a configured scale. */
internal fun readerEnhancementResolution(
    pageIndex: Int, originalSize: IntSize?, asset: ReaderEnhancedPage?,
): ReaderEnhancementResolution {
    val original = originalSize?.takeIf { it.width > 0 && it.height > 0 }
    val output = asset?.takeIf { it.width > 0 && it.height > 0 }
    val originalLabel = original?.let { "${it.width} × ${it.height} px" }
    val value = output?.let { "${it.width} × ${it.height} px" } ?: originalLabel ?: "—"
    val origin = original?.let { "原图 ${it.width} × ${it.height} 像素" } ?: "原图尺寸尚未获取"
    return ReaderEnhancementResolution(
        valueLabel = value,
        label = (if (output != null) "增强 " else "原图 ") + value,
        originalLabel = originalLabel,
        description = "第 ${pageIndex + 1} 页，" + if (output != null)
            "已备增强文件 ${output.width} × ${output.height} 像素，$origin" else origin,
        enhanced = output != null,
    )
}

/** Use only engine-reported completed work; a running task is not an output receipt. */
internal fun readerEnhancementTaskProgress(task: EnhancementTask, pageLabel: String = "本页"): String = when (task.phase) {
    EnhancementTaskPhase.RUNNING -> {
        val progress = task.progress
        when (progress?.stage) {
            EngineStage.MODEL_LOAD -> "模型加载中"
            EngineStage.PROCESS -> if (progress.total?.let { it > 0L } == true)
                "${pageLabel}处理 ${progress.completed}/${progress.total}"
                else "${pageLabel}处理中"
            EngineStage.ENCODE -> "增强文件写入中"
            EngineStage.VALIDATE -> "增强文件校验中"
            EngineStage.COMPLETE -> "增强结果收尾中"
            EngineStage.DOWNLOAD -> "增强组件下载中"
            EngineStage.VERIFY -> "增强输入校验中"
            EngineStage.INSTALL -> "增强组件安装中"
            null -> "${pageLabel}增强准备中"
        }
    }
    EnhancementTaskPhase.FAILED -> task.reason?.takeIf { it.isNotBlank() }?.let {
        "${pageLabel}增强失败 · ${briefEnhancementDetail(it)}"
    } ?: "${pageLabel}增强失败"
    EnhancementTaskPhase.PAUSED -> "${pageLabel}增强暂停"
    EnhancementTaskPhase.CANCELLED -> "${pageLabel}增强取消"
    EnhancementTaskPhase.COMPLETED -> "${pageLabel}增强已备"
    else -> "${pageLabel}增强等待"
}

/** Assets are prepared files. This controller has no decoded-image presentation acknowledgement. */
internal fun readerEnhancementReadouts(
    metrics: Set<EnhancementMetric>, state: ReaderEnhancementState, policy: EnhancementPolicy, currentPage: Int?,
): List<EnhancementReadout> = buildList {
    val pageMatches = currentPage != null && state.currentPage == currentPage
    val enabled = policy.tier != QualityTier.ORIGINAL
    val visiblePages = if (pageMatches) state.visiblePages.ifEmpty { setOf(state.currentPage) }.sorted() else emptyList()
    val readyPages = visiblePages.count { it in state.assets }
    val pageScope = when (visiblePages.size) { 2 -> "双页"; in 3..Int.MAX_VALUE -> "可见页"; else -> "本页" }
    val task = state.currentTask.takeIf { pageMatches && enabled }
    val currentDetail = state.detail?.takeIf { pageMatches && enabled && !state.currentEnhanced && it.isNotBlank() }
    val currentProgress = task?.takeIf { it.phase != EnhancementTaskPhase.COMPLETED }?.let {
        readerEnhancementTaskProgress(it, it.title?.ifBlank { "本页" } ?: "本页")
    } ?: currentDetail?.let(::briefEnhancementDetail)
    if (EnhancementMetric.QUALITY in metrics) {
        val value = when {
            !enabled -> "原图"
            !pageMatches -> "本页增强 —"
            task?.phase == EnhancementTaskPhase.FAILED || state.currentFailure != null -> if (visiblePages.size > 1)
                "可见页增强 $readyPages/${visiblePages.size} · 有页增强失败" else "增强失败 · 沿用原图"
            visiblePages.size > 1 -> "${pageScope}增强 $readyPages/${visiblePages.size}"
            state.currentEnhanced -> "本页增强已备"
            currentProgress != null -> currentProgress
            else -> "本页未备"
        }
        add(EnhancementReadout(EnhancementMetric.QUALITY, value,
            if (enabled) listOfNotNull("${pageScope}增强文件，$value；文件就绪不代表已经呈现",
                (currentDetail ?: task?.reason ?: task?.progress?.detail ?: currentProgress)?.takeUnless { it in value })
                .joinToString("，") else "当前选择原图"))
    }
    if (EnhancementMetric.DIMENSIONS in metrics) {
        val outputs = visiblePages.filter { enabled && it in state.assets }
        if (outputs.isEmpty()) {
            add(EnhancementReadout(EnhancementMetric.DIMENSIONS, "增强尺寸 —", "可见页尚无已备增强文件尺寸"))
        } else outputs.forEach { index ->
            val resolution = readerEnhancementResolution(index, null, state.assets[index])
            val prefix = if (visiblePages.size > 1) "P${index + 1} · " else ""
            add(EnhancementReadout(EnhancementMetric.DIMENSIONS, prefix + resolution.label, resolution.description))
        }
    }
    if (EnhancementMetric.PREPARED in metrics) {
        val aheadReady = state.aheadPages.count { it in state.assets }
        val preparationDetail = state.preparationDetail?.takeIf {
            enabled && pageMatches && PreparationMode.SMART in policy.preparation &&
                state.aheadPages.isNotEmpty() && it.isNotBlank()
        }?.takeUnless { it == currentDetail && EnhancementMetric.QUALITY in metrics }
        val prepared = when {
            !enabled -> "增强未开启"
            !pageMatches -> "前方增强 —"
            PreparationMode.SMART !in policy.preparation -> "提前增强未开启"
            state.aheadPages.isEmpty() -> "前方无待备页"
            else -> "前方增强 $aheadReady/${state.aheadPages.size} 页"
        }
        add(EnhancementReadout(EnhancementMetric.PREPARED, prepared,
            "沿阅读方向、不包含当前可见页的增强文件，$prepared" +
                (if (pageMatches && enabled && state.nextReady) "；下一屏全部已备" else "") +
                preparationDetail?.let { "；$it" }.orEmpty()))
    }
    if (EnhancementMetric.TASKS in metrics) {
        val value = if (currentPage == null) "增强任务 —" else "处理 ${state.running} · 等待 ${state.queued}"
        add(EnhancementReadout(EnhancementMetric.TASKS, value,
            "当前阅读窗口增强任务，$value；等待包括排队、暂缓和暂停"))
    }
    if (EnhancementMetric.PAGE_LATENCY in metrics) {
        fun elapsed(value: Double?) = value?.takeIf { currentPage != null && it.isFinite() && it >= 0.0 }
            ?.let { formatDecimal(it, 0) } ?: "—"
        val p50 = elapsed(state.elapsedP50Millis)
        val p95 = elapsed(state.elapsedP95Millis)
        add(EnhancementReadout(EnhancementMetric.PAGE_LATENCY, "整页 P50/P95 $p50/$p95 ms",
            "本次阅读的整页处理耗时，P50 $p50 毫秒，P95 $p95 毫秒"))
    }
    if (EnhancementMetric.CACHE_HITS in metrics) {
        val ratio = if (currentPage != null && state.cacheLookups > 0L)
            formatDecimal(state.cacheHits * 100.0 / state.cacheLookups, 0) + "%" else "—"
        add(EnhancementReadout(EnhancementMetric.CACHE_HITS, "命中 $ratio", "本次阅读增强缓存命中率 $ratio"))
    }
}
