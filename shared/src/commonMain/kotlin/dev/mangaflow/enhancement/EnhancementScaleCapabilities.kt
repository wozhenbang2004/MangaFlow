package dev.mangaflow.enhancement

import dev.mangaflow.anime.player.Anime4KOptionalModels
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.enhancement.engine.EngineContent
import dev.mangaflow.enhancement.engine.EngineDescriptor
import dev.mangaflow.enhancement.engine.EngineInventory
import dev.mangaflow.enhancement.engine.EngineReadiness
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlin.math.abs

/** Installed picture capabilities, not desired settings or a claim of measured device performance. */
internal fun enhancementOutputScales(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory): List<Int> {
    val contents = when {
        content == EnhancementContent.ANIME -> setOf(EngineContent.VIDEO)
        policy.style == ImageStyle.MONOCHROME -> setOf(EngineContent.BLACK_WHITE)
        policy.style == ImageStyle.COLOR -> setOf(EngineContent.COLOR)
        policy.style == ImageStyle.WEBTOON -> setOf(EngineContent.WEBTOON)
        else -> setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON)
    }
    val engines = inventory.engines.filter { it.availableForScaleSelection() && it.contents.any(contents::contains) }
    fun profileScales(id: String): Set<Int> = engines.firstOrNull { it.id == id }
        ?.supportedScales.orEmpty().intersect(setOf(1, 2))
    fun modelScales(id: String): Set<Int> {
        val model = inventory.models.firstOrNull { it.id == id } ?: return emptySet()
        val engine = engines.firstOrNull { it.id == model.engineId } ?: return emptySet()
        if (model.contents.none { it in contents && it in engine.contents }) return emptySet()
        // Anime4K's installed profile stores its 2× identity; restore-only is also a real 1× path.
        return if (content == EnhancementContent.ANIME && Anime4KOptionalModels.supports(model.engineId))
            engine.supportedScales.intersect(setOf(1, 2))
        else setOf(model.nativeScale).intersect(engine.supportedScales).filter { it > 0 }.toSet()
    }
    policy.modelId?.let { id ->
        return if (content == EnhancementContent.ANIME && id in setOf(
                EnhancementEngineIds.ANIME4K_SMALL, EnhancementEngineIds.ANIME4K_MEDIUM))
            profileScales(id).sorted()
        else modelScales(id).sorted()
    }

    // Original stores a future enhancement choice without enabling or rewriting the saved tier.
    val tier = policy.tier.takeUnless { it == QualityTier.ORIGINAL } ?: QualityTier.AUTO
    val scales = mutableSetOf<Int>()
    if (content != EnhancementContent.ANIME) {
        if (tier in setOf(QualityTier.LIGHT, QualityTier.AUTO, QualityTier.BALANCED) &&
            engines.any { it.id == EnhancementEngineIds.LOCAL_DETAIL && 1 in it.supportedScales }) scales += 1
        if (tier != QualityTier.LIGHT) inventory.models.forEach { model ->
            scales += modelScales(model.id).filter {
                it > 1 || tier in setOf(QualityTier.FINE, QualityTier.ULTRA)
            }
        }
        return scales.sorted()
    }

    val selectedRealtimeScales = mutableSetOf<Int>()
    when (tier) {
        QualityTier.LIGHT -> scales += profileScales(EnhancementEngineIds.ANIME4K_SMALL)
        QualityTier.BALANCED -> scales += profileScales(EnhancementEngineIds.ANIME4K_MEDIUM)
        QualityTier.AUTO -> {
            scales += profileScales(EnhancementEngineIds.ANIME4K_SMALL)
            scales += profileScales(EnhancementEngineIds.ANIME4K_MEDIUM)
        }
        QualityTier.FINE, QualityTier.ULTRA -> when (platformKind) {
            PlatformKind.DESKTOP -> {
                // Keep AnimeController.preferredRealtimeModel's exact tier priority, including
                // its choice before matching scale; a different installed profile is not fallback.
                val preferred = if (tier == QualityTier.FINE) "animejanai-live:performance" else "animejanai-live:balanced"
                val id = if (inventory.models.any { it.id == preferred }) preferred
                    else if (tier == QualityTier.FINE) "artcnn:C4F16" else "artcnn:C4F32"
                inventory.models.firstOrNull { it.id == id && it.nativeScale in 1..2 && it.engineId in setOf(
                    EnhancementEngineIds.DESKTOP_ARTCNN, EnhancementEngineIds.DESKTOP_REALTIME,
                ) }?.let { model ->
                    selectedRealtimeScales += model.nativeScale
                    scales += modelScales(model.id).intersect(setOf(1, 2))
                }
            }
            PlatformKind.ANDROID -> {
                val realtime = inventory.engines.firstOrNull { it.id == EnhancementEngineIds.ANDROID_VIDEO_REALTIME }
                // The current Android player admits model enhancement only on Android 10+.
                if (realtime != null && realtime.readiness != EngineReadiness.UNSUPPORTED) {
                    val models = inventory.models.filter { EngineContent.VIDEO in it.contents && it.engineId in setOf(
                        EnhancementEngineIds.ANDROID_CUGAN_VIDEO, EnhancementEngineIds.ANDROID_VIDEO_ONNX,
                    ) }
                    models.groupBy { it.nativeScale }.values.forEach { candidates ->
                        val model = candidates.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO }
                            ?: candidates.first()
                        selectedRealtimeScales += model.nativeScale
                        if (model.engineId != EnhancementEngineIds.ANDROID_VIDEO_ONNX || realtime.availableForScaleSelection())
                            scales += modelScales(model.id).intersect(realtime.supportedScales)
                    }
                }
            }
            else -> Unit
        }
        QualityTier.ORIGINAL -> Unit
    }
    // These are the existing automatic preparation routes. Interpolation-only descriptors and
    // frame weights never contribute a picture scale, even when their runtime reports ready.
    val offlineAutomatic = tier in setOf(QualityTier.AUTO, QualityTier.BALANCED)
    val frameEngine = when (platformKind) {
        PlatformKind.ANDROID -> EnhancementEngineIds.ANDROID_RIFE_VULKAN
        PlatformKind.DESKTOP -> EnhancementEngineIds.DESKTOP_REALTIME_RIFE
        else -> null
    }
    val realtimeFramesSelected = PreparationMode.IMMEDIATE in policy.preparation &&
        policy.frameMode != FrameMode.ORIGINAL && policy.frameQuality == FrameQuality.BALANCED &&
        (policy.frameMode != FrameMode.CUSTOM || policy.customFps in 1..(if (platformKind == PlatformKind.ANDROID) 120 else 240)) &&
        inventory.frameModels.any { it.engineId == frameEngine }
    val onlineAutomatic = tier in setOf(QualityTier.AUTO, QualityTier.BALANCED, QualityTier.FINE, QualityTier.ULTRA) &&
        PreparationMode.SMART in policy.preparation && policy.cacheMiB >= 16 && !realtimeFramesSelected
    val onlineEngines = when (platformKind) {
        PlatformKind.ANDROID -> setOf(EnhancementEngineIds.ANDROID_CUGAN_VIDEO, EnhancementEngineIds.ANDROID_VIDEO_ONNX)
        PlatformKind.DESKTOP -> setOf(EnhancementEngineIds.VIDEOJANAI)
        else -> emptySet()
    }
    if (offlineAutomatic) inventory.models.filter { model ->
        EngineContent.VIDEO in model.contents && engines.any { it.id == model.engineId && it.videoOutput != null }
    }.groupBy { it.nativeScale }.values.forEach { candidates ->
        scales += modelScales(candidates.first().id)
    }
    if (onlineAutomatic) inventory.models.filter {
        EngineContent.VIDEO in it.contents && it.engineId in onlineEngines
    }.groupBy { it.nativeScale }.values.forEach { candidates ->
        val model = candidates.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO }
            ?: candidates.first()
        // The player does not prepare an online window while this scale selects MODEL mode.
        if (PreparationMode.IMMEDIATE !in policy.preparation || model.nativeScale !in selectedRealtimeScales)
            scales += modelScales(model.id)
    }
    return scales.sorted()
}

/** A preview for an explicit model-selection action; callers alone decide whether to save it. */
internal fun enhancementModelSelectionPolicy(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory, modelId: String?): EnhancementPolicy? {
    val choice = policy.copy(modelId = modelId, preparation =
        if (content == EnhancementContent.ANIME && policy.tier != QualityTier.ORIGINAL)
            policy.preparation + PreparationMode.IMMEDIATE else policy.preparation)
    val scales = enhancementOutputScales(content, choice, inventory)
    val scale = policy.scale.takeIf { it in scales }
        ?: scales.minByOrNull { abs(it.toLong() - policy.scale.toLong()) } ?: return null
    return choice.copy(scale = scale)
}

private fun EngineDescriptor.availableForScaleSelection(): Boolean =
    readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL)
