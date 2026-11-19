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
    val realtimeFramesSelected = policy.realtimeInterpolationEnabled &&
        policy.frameMode != FrameMode.ORIGINAL && policy.frameQuality == FrameQuality.BALANCED &&
        (policy.frameMode != FrameMode.CUSTOM || policy.customFps in 1..(if (platformKind == PlatformKind.ANDROID) 120 else 240)) &&
        inventory.frameModels.any { it.engineId == frameEngine }
    val onlineAutomatic = tier in setOf(QualityTier.AUTO, QualityTier.BALANCED, QualityTier.FINE, QualityTier.ULTRA) &&
        policy.realtimeEnhancementEnabled && PreparationMode.SMART in policy.preparation && !realtimeFramesSelected
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
        if (!policy.realtimeEnhancementEnabled || model.nativeScale !in selectedRealtimeScales)
            scales += modelScales(model.id)
    }
    return scales.sorted()
}

/** A preview for an explicit model-selection action; callers alone decide whether to save it. */
internal fun enhancementModelSelectionPolicy(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory, modelId: String?): EnhancementPolicy? {
    val choice = policy.copy(modelId = modelId, realtimeEnhancementEnabled =
        if (content == EnhancementContent.ANIME && policy.tier != QualityTier.ORIGINAL) true
        else policy.realtimeEnhancementEnabled)
    val scales = enhancementOutputScales(content, choice, inventory)
    val scale = policy.scale.takeIf { it in scales }
        ?: scales.minByOrNull { abs(it.toLong() - policy.scale.toLong()) } ?: return null
    return choice.copy(scale = scale)
}

private fun EngineDescriptor.availableForScaleSelection(): Boolean =
    readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL)

internal data class EnhancementPresetDescription(val algorithm: String, val detail: String, val unavailable: Boolean = false)

/** Describes selection, never an assertion that the model has produced or displayed an image. */
internal fun enhancementPresetDescription(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory): EnhancementPresetDescription {
    if (policy.tier == QualityTier.ORIGINAL) return EnhancementPresetDescription(
        if (content == EnhancementContent.ANIME) "原画" else "原图", "画质增强已关闭，输出倍率与其他设置保留。")
    if (content != EnhancementContent.ANIME) {
        val types = when (policy.style) {
            ImageStyle.MONOCHROME -> listOf(EngineContent.BLACK_WHITE)
            ImageStyle.COLOR -> listOf(EngineContent.COLOR)
            ImageStyle.WEBTOON -> listOf(EngineContent.WEBTOON)
            ImageStyle.AUTO -> listOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON)
        }
        val choices = types.map { type -> type to runCatching { selectReaderEnhancementModel(inventory, policy, type) } }
        fun title(choice: Result<ReaderEnhancementModel>): String = choice.getOrNull()?.let {
            it.model?.title ?: "本地 1× 轻量修复"
        } ?: "暂无兼容模型"
        val titles = choices.map { title(it.second) }.distinct()
        val algorithm = if (titles.size == 1) titles.single() else choices.joinToString("；") { (type, choice) ->
            val typeLabel = when (type) {
                EngineContent.BLACK_WHITE -> "黑白"
                EngineContent.COLOR -> "彩色"
                EngineContent.WEBTOON -> "条漫"
                EngineContent.VIDEO -> "视频"
            }
            "$typeLabel：${title(choice)}"
        }
        val reason = choices.mapNotNull { it.second.exceptionOrNull()?.message }.distinct().joinToString("；")
        val behavior = when (policy.tier) {
            QualityTier.LIGHT -> "1× 本地修复，不使用 AI 放大模型。"
            QualityTier.AUTO, QualityTier.BALANCED -> if (policy.scale == 1)
                "当前 1× 使用本地修复；自动与均衡在此配置下效果相同。"
                else "按图片类型和倍率匹配已就绪模型；自动与均衡当前共用这套选择规则。"
            QualityTier.FINE, QualityTier.ULTRA ->
                "精细与极致当前没有独立的漫画模型预设，共用兼容模型选择；档名不会改变同一模型的强度。可通过自选模型更换算法。"
            QualityTier.ORIGINAL -> ""
        }
        return EnhancementPresetDescription(algorithm, listOf(behavior, reason).filter { it.isNotBlank() }.joinToString("\n"),
            choices.any { it.second.isFailure })
    }
    fun profile(id: String, fallback: String, detail: String): EnhancementPresetDescription {
        val engine = inventory.engines.firstOrNull { it.id == id }
        val available = engine != null && engine.availableForScaleSelection() && policy.scale in engine.supportedScales
        return EnhancementPresetDescription(engine?.title ?: fallback,
            detail + if (available) "" else " 当前 ${policy.scale}× 不能用于此实时预设，请调整输出倍率或自选模型。",
            unavailable = !available)
    }
    return when (policy.tier) {
        QualityTier.LIGHT -> profile(EnhancementEngineIds.ANIME4K_SMALL, "Anime4K S/S",
            "固定使用 S/S；1× 只修复，2× 修复并放大。")
        QualityTier.BALANCED -> profile(EnhancementEngineIds.ANIME4K_MEDIUM, "Anime4K M/M",
            "固定使用 M/M；1× 只修复，2× 修复并放大。")
        QualityTier.AUTO -> profile(EnhancementEngineIds.ANIME4K_MEDIUM,
            "Anime4K M/M", if (policy.locked)
                "已固定 M/M；内存或温度保护仍可暂用原画。"
            else "先使用 M/M；取得片源帧率和处理耗时后，接近帧预算时改用 S/S。本次播放降档后保持 S/S。")
            .let { if (policy.locked) it else it.copy(algorithm = "Anime4K M/M 或 S/S") }
        QualityTier.FINE, QualityTier.ULTRA -> {
            val candidates = inventory.models.filter { it.nativeScale == policy.scale && EngineContent.VIDEO in it.contents }
            val model = when (platformKind) {
                PlatformKind.DESKTOP -> {
                    val preferred = if (policy.tier == QualityTier.FINE) "animejanai-live:performance" else "animejanai-live:balanced"
                    val id = if (inventory.models.any { it.id == preferred }) preferred
                        else if (policy.tier == QualityTier.FINE) "artcnn:C4F16" else "artcnn:C4F32"
                    candidates.firstOrNull { it.id == id && it.nativeScale in 1..2 && it.engineId in setOf(
                        EnhancementEngineIds.DESKTOP_REALTIME, EnhancementEngineIds.DESKTOP_ARTCNN) }
                }
                PlatformKind.ANDROID -> candidates.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO }
                    ?: candidates.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_VIDEO_ONNX }
                else -> null
            }
            val detail = if (platformKind == PlatformKind.ANDROID)
                "精细与极致当前共用兼容视频模型选择，没有独立的两级预设；档名不会改变模型强度。可自选 Anime4K 的具体型号。"
                else "按此预设的固定模型选择；实际能否实时运行，以当前播放状态为准。"
            EnhancementPresetDescription(model?.title ?: "尚无兼容的实时模型", detail,
                unavailable = model == null)
        }
        QualityTier.ORIGINAL -> EnhancementPresetDescription("原画", "画质增强已关闭。")
    }
}
