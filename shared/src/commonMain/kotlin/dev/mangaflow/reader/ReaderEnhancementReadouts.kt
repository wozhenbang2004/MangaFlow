package dev.mangaflow.reader

import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.EnhancementTaskPhase
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.ReaderEnhancementState
import dev.mangaflow.enhancement.engine.EngineStage
import dev.mangaflow.enhancement.ui.EnhancementReadout
import dev.mangaflow.platform.formatDecimal

/** Assets are prepared files. This controller has no decoded-image presentation acknowledgement. */
internal fun readerEnhancementReadouts(
    metrics: Set<EnhancementMetric>, state: ReaderEnhancementState, policy: EnhancementPolicy, currentPage: Int?,
): List<EnhancementReadout> = buildList {
    val pageMatches = currentPage != null && state.currentPage == currentPage
    val enabled = policy.tier != QualityTier.ORIGINAL
    val task = state.currentTask.takeIf { pageMatches && enabled }
    if (EnhancementMetric.QUALITY in metrics) {
        val value = when {
            !enabled -> "原图"
            !pageMatches -> "本页增强 —"
            state.currentEnhanced -> "本页已备"
            task?.phase == EnhancementTaskPhase.RUNNING ->
                if (task.progress?.stage == EngineStage.MODEL_LOAD) "模型加载中" else "本页增强中"
            task?.phase == EnhancementTaskPhase.FAILED -> "本页增强失败"
            task?.phase == EnhancementTaskPhase.PAUSED -> "本页增强暂停"
            task?.phase == EnhancementTaskPhase.CANCELLED -> "本页增强取消"
            task != null -> "本页增强等待"
            else -> "本页未备"
        }
        add(EnhancementReadout(EnhancementMetric.QUALITY, value,
            if (enabled) listOfNotNull("本页增强文件，$value；文件就绪不代表已经呈现",
                task?.reason ?: task?.progress?.detail).joinToString("，") else "当前选择原图"))
    }
    if (EnhancementMetric.DIMENSIONS in metrics) {
        val asset = currentPage?.let(state.assets::get).takeIf { enabled && pageMatches && state.currentEnhanced }
        val size = asset?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" }
        add(EnhancementReadout(EnhancementMetric.DIMENSIONS, size?.let { "已备 $it" } ?: "增强输出 —",
            "本页已备增强文件尺寸 ${size ?: "无观测"}"))
    }
    if (EnhancementMetric.PREPARED in metrics) {
        val value = when {
            !enabled -> "增强未开启"
            !pageMatches -> "前方增强 —"
            state.nextReady -> "前方已备"
            else -> "前方未备"
        }
        add(EnhancementReadout(EnhancementMetric.PREPARED, value, "沿阅读方向的邻页增强文件，$value"))
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
