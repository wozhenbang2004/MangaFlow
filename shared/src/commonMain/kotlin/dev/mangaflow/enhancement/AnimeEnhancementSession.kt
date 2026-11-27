package dev.mangaflow.enhancement

import dev.mangaflow.anime.player.AnimeEnhancementProtection
import dev.mangaflow.anime.player.AnimeEnhancementProtectionCause
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.experience.ExperienceResources

fun animeEnhancementSessionKey(state: AnimePlayerState): String =
    "anime:${state.assetKey}:${state.sessionGeneration}"

data class RealtimeEnhancementSelection(val mode: AnimeVideoEnhancementMode, val scale: Int, val reason: String? = null,
    val model: EngineModel? = null, val protection: AnimeEnhancementProtectionCause? = null)

internal fun realtimeResourceProtection(resources: ExperienceResources): AnimeEnhancementProtectionCause? = when {
    (resources.thermalPressure ?: 0) >= 2 -> AnimeEnhancementProtectionCause.THERMAL
    resources.memoryPressure == true -> AnimeEnhancementProtectionCause.MEMORY
    else -> null
}

/** Only the actual installed realtime backend is mapped here. Desired offline models remain unchanged. */
fun selectRealtimeEnhancement(policy: EnhancementPolicy, resources: ExperienceResources,
    sourceFps: Double?, measuredGpuP95Millis: Double?, realtimeModel: EngineModel? = null,
    retainLightMode: Boolean = false): RealtimeEnhancementSelection {
    val selected = requestedRealtimePicture(policy, sourceFps, measuredGpuP95Millis, realtimeModel, retainLightMode)
    if (selected.mode == AnimeVideoEnhancementMode.OFF) return selected
    val cause = realtimeResourceProtection(resources) ?: return selected
    val reason = AnimeEnhancementProtection(cause, picture = true, motion = false).description
    return RealtimeEnhancementSelection(AnimeVideoEnhancementMode.OFF, policy.scale.takeIf { it in 1..2 } ?: 1,
        reason = reason, protection = cause)
}

private fun requestedRealtimePicture(policy: EnhancementPolicy, sourceFps: Double?, measuredGpuP95Millis: Double?,
    realtimeModel: EngineModel?, retainLightMode: Boolean): RealtimeEnhancementSelection {
    val scale = policy.scale.takeIf { it in 1..2 } ?: 1
    fun original(reason: String? = null) = RealtimeEnhancementSelection(AnimeVideoEnhancementMode.OFF, scale, reason)
    if (policy.tier == QualityTier.ORIGINAL) return original()
    if (!policy.realtimeEnhancementEnabled) return original("播放时画质增强已关闭；模型和已完成缓存保留，当前显示原画")
    val builtInMode = when (policy.modelId) {
        EnhancementEngineIds.ANIME4K_SMALL -> AnimeVideoEnhancementMode.FAST
        EnhancementEngineIds.ANIME4K_MEDIUM -> AnimeVideoEnhancementMode.QUALITY
        else -> null
    }
    if (builtInMode != null) {
        if (policy.scale !in 1..2) return original("所选 Anime4K 支持 1× 修复与 2× 输出，请调整输出倍率")
        return RealtimeEnhancementSelection(builtInMode, scale)
    }
    if (policy.tier in setOf(QualityTier.FINE, QualityTier.ULTRA) || policy.modelId != null) {
        if (realtimeModel != null) return RealtimeEnhancementSelection(AnimeVideoEnhancementMode.MODEL, realtimeModel.nativeScale,
            model = realtimeModel)
        return original(if (policy.modelId != null)
            "所选模型当前无法用于实时播放，请检查模型与倍率，或在画质模型中选择自动；暂时保留原画"
        else "所选高质量模型用于离线预处理；当前实时内核尚未适配，原画继续播放")
    }
    if (policy.scale !in 1..2) return original("当前实时引擎支持 1× 修复与 2× 输出；更高倍率可离线处理")
    val mode = when (policy.tier) {
        QualityTier.LIGHT -> AnimeVideoEnhancementMode.FAST
        QualityTier.BALANCED -> AnimeVideoEnhancementMode.QUALITY
        QualityTier.AUTO -> {
            val expensive = sourceFps != null && sourceFps.isFinite() && sourceFps > 0.0 &&
                measuredGpuP95Millis != null && measuredGpuP95Millis.isFinite() &&
                measuredGpuP95Millis >= 1000.0 / sourceFps * .8
            if (!policy.locked && (retainLightMode || expensive)) AnimeVideoEnhancementMode.FAST
            else AnimeVideoEnhancementMode.QUALITY
        }
        else -> AnimeVideoEnhancementMode.OFF
    }
    val protection = AnimeEnhancementProtectionCause.GPU_BUDGET.takeIf {
        policy.tier == QualityTier.AUTO && mode == AnimeVideoEnhancementMode.FAST
    }
    return RealtimeEnhancementSelection(mode, scale, when {
        protection != null -> AnimeEnhancementProtection(protection, picture = true, motion = false).description
        policy.realtimeInterpolationEnabled && policy.frameMode != FrameMode.ORIGINAL -> "画质增强与补帧分别运行，补帧结果见当前播放读数"
        else -> null
    }, protection = protection)
}
