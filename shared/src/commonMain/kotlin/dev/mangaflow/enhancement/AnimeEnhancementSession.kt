package dev.mangaflow.enhancement

import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.experience.ExperienceResources

fun animeEnhancementSessionKey(state: AnimePlayerState): String =
    "anime:${state.assetKey}:${state.sessionGeneration}"

data class RealtimeEnhancementSelection(val mode: AnimeVideoEnhancementMode, val scale: Int, val reason: String? = null,
    val model: EngineModel? = null)

/** Only the actual installed realtime backend is mapped here. Desired offline models remain unchanged. */
fun selectRealtimeEnhancement(policy: EnhancementPolicy, resources: ExperienceResources,
    sourceFps: Double?, measuredGpuP95Millis: Double?, realtimeModel: EngineModel? = null): RealtimeEnhancementSelection {
    val scale = policy.scale.takeIf { it in 1..2 } ?: 1
    fun original(reason: String? = null) = RealtimeEnhancementSelection(AnimeVideoEnhancementMode.OFF, scale, reason)
    if (policy.tier == QualityTier.ORIGINAL) return original()
    if (PreparationMode.IMMEDIATE !in policy.preparation) return original(when {
        PreparationMode.SMART in policy.preparation -> "优先使用已完成的增强结果；当前在线片段尚未准备，继续播放原画"
        PreparationMode.COMPLETE in policy.preparation -> "仅使用完整预处理结果；未准备的内容保持原画"
        else -> "即时和提前处理均已关闭，已有增强版本仍可从离线任务打开"
    })
    if (resources.memoryPressure == true || (resources.thermalPressure ?: 0) >= 2)
        return original("设备保护已暂缓增强，目标档位保留；当前显示原画")
    if (policy.tier in setOf(QualityTier.FINE, QualityTier.ULTRA) || realtimeModel != null && policy.modelId != null) {
        if (realtimeModel != null) return RealtimeEnhancementSelection(AnimeVideoEnhancementMode.MODEL, realtimeModel.nativeScale,
            model = realtimeModel)
        return original("所选高质量模型用于离线预处理；当前实时内核尚未适配，原画继续播放")
    }
    if (policy.scale !in 1..2) return original("当前实时引擎支持 1× 修复与 2× 输出；更高倍率可离线处理")
    val mode = when (policy.tier) {
        QualityTier.LIGHT -> AnimeVideoEnhancementMode.FAST
        QualityTier.BALANCED -> AnimeVideoEnhancementMode.QUALITY
        QualityTier.AUTO -> if (measuredGpuP95Millis != null && sourceFps != null && sourceFps > 0 &&
            measuredGpuP95Millis >= 1000.0 / sourceFps * .8) AnimeVideoEnhancementMode.FAST else AnimeVideoEnhancementMode.QUALITY
        else -> AnimeVideoEnhancementMode.OFF
    }
    return RealtimeEnhancementSelection(mode, scale, when {
        policy.frameMode != FrameMode.ORIGINAL -> "当前画质增强独立运行；RIFE 补帧需离线处理或已适配的实时内核"
        policy.tier == QualityTier.AUTO && mode == AnimeVideoEnhancementMode.FAST -> "本次 GPU 处理接近帧预算，自动选用轻量"
        else -> null
    })
}
