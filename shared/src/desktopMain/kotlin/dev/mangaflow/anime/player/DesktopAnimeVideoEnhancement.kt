package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.openani.mediamp.mpv.MPVHandle
import java.io.File

/** Owns only this player's shader list. Mutations/publication stay on Main; native observations use IO. */
internal class DesktopAnimeVideoEnhancement(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val renderingActive: StateFlow<Boolean>,
    private val isCurrentSession: (MPVHandle, Long, AnimeVideoEnhancementMode) -> Boolean,
    private val update: (Long, AnimeVideoEnhancementState) -> Unit,
) {
    private var handle: MPVHandle? = null
    private var generation = -1L
    private var mode = AnimeVideoEnhancementMode.OFF
    private var scale = 2
    private var model: EngineModel? = null
    private var mediaReady = false
    private var monitoring: Job? = null
    private var ownsShaders = false

    fun configure(handle: MPVHandle?, generation: Long, mode: AnimeVideoEnhancementMode, mediaReady: Boolean, scale: Int = 2,
        model: EngineModel? = null) {
        if (this.handle === handle && this.generation == generation && this.mode == mode &&
            this.mediaReady == mediaReady && this.scale == scale && this.model == model) return

        monitoring?.cancel()
        monitoring = null
        val previousHandle = this.handle
        val cleared = clearOwnedShaders()
        this.handle = handle
        this.generation = generation
        this.mode = mode
        this.scale = scale
        this.model = model
        this.mediaReady = mediaReady
        // A replaced backend is closed by the existing session owner. Its failed
        // cleanup must not transfer ownership or errors to a different backend.
        if (previousHandle !== handle) ownsShaders = false
        if (!cleared && previousHandle === handle) {
            update(generation, state(mode, AnimeVideoEnhancementPhase.FAILED,
                "无法确认增强已关闭，请重新打开视频"))
            return
        }
        if (mode == AnimeVideoEnhancementMode.OFF) {
            update(generation, state(mode, AnimeVideoEnhancementPhase.OFF))
            return
        }
        update(generation, state(mode, AnimeVideoEnhancementPhase.WAITING, "等待视频画面"))
        if (handle == null || !mediaReady) return

        monitoring = scope.launch(Dispatchers.Main.immediate) {
            try {
                val paths = withContext(Dispatchers.IO) { DesktopAnime4KShaderStore.prepare(context, mode, scale, model) }
                check(paths.isNotEmpty()) { "No enhancement shader was prepared" }
                val anime4kSize = when {
                    model != null && Anime4KOptionalModels.supports(model.engineId) ->
                        model.title.substringAfter(' ').substringBefore('/')
                    mode == AnimeVideoEnhancementMode.FAST -> "S"
                    mode == AnimeVideoEnhancementMode.QUALITY -> "M"
                    else -> null
                }
                val appliedModel = model?.title ?: anime4kSize?.let { "Anime4K $it/$it" }
                // append accepts a single literal path, even with ':' / ';' / '\\'.
                // mpv's string getter prints this list with the platform separator.
                val expectedList = paths.joinToString(File.pathSeparator)
                var configured = false
                var lastSampleTime: Double? = null
                var budgetOverruns = 0
                while (isCurrentSession(handle, generation, mode)) {
                    // Property reads synchronize with mpv's engine and may wait behind GPU work.
                    val observed = withContext(Dispatchers.IO) { readObservation(handle, anime4kSize, model) }
                    if (!isCurrentSession(handle, generation, mode) || this@DesktopAnimeVideoEnhancement.handle !== handle)
                        return@launch
                    val width = observed.width
                    val height = observed.height
                    val gamma = observed.gamma
                    val output = observed.output
                    val unavailable = when {
                        width == null || height == null -> AnimeVideoEnhancementPhase.WAITING to "等待视频画面"
                        gamma in HDR_TRANSFERS -> AnimeVideoEnhancementPhase.UNAVAILABLE to
                            "HDR 视频使用原始画质，保留原生色彩处理"
                        gamma.isNullOrBlank() || gamma == "auto" || gamma == "unknown" ->
                            AnimeVideoEnhancementPhase.UNAVAILABLE to "当前视频色彩信息尚未明确，保持原始画质"
                        gamma !in SDR_TRANSFERS -> AnimeVideoEnhancementPhase.UNAVAILABLE to
                            "当前色彩格式暂不启用增强，保持原始画质"
                        output.isNullOrBlank() -> AnimeVideoEnhancementPhase.WAITING to "等待视频渲染表面"
                        output != "libmpv" -> AnimeVideoEnhancementPhase.UNAVAILABLE to
                            "当前渲染内核不支持这组增强配置，保持原始画质"
                        else -> null
                    }
                    if (unavailable != null) {
                        check(clearOwnedShaders()) { "Cannot clear the current shader list" }
                        configured = false
                        update(generation, state(mode, unavailable.first, unavailable.second, width, height))
                    } else {
                        var shaderList = observed.shaderList
                        if (!configured) {
                            // Mediamp desktop's libmpv GPU renderer consumes these
                            // hooks. Keep its existing VO, color management and hwdec.
                            ownsShaders = true
                            check(clearShaderList(handle)) { "Cannot reset the shader list" }
                            for (path in paths) {
                                check(handle.command("change-list", "glsl-shaders", "append", path)) {
                                    "The renderer did not accept an enhancement shader"
                                }
                            }
                            configured = true
                            shaderList = withContext(Dispatchers.IO) { handle.getPropertyString("glsl-shaders") }
                            if (!isCurrentSession(handle, generation, mode) || this@DesktopAnimeVideoEnhancement.handle !== handle)
                                return@launch
                        }
                        check(shaderList == expectedList) {
                            "The renderer did not confirm the complete shader list"
                        }
                        val sample = observed.shaderPasses
                        if (sample == null) {
                            update(generation, state(mode, AnimeVideoEnhancementPhase.CONFIGURED,
                                "着色器已加载。当前渲染器未提供可读的增强计数。", width, height)
                                .copy(modelName = appliedModel))
                        } else {
                            val frameTime = observed.videoPts
                            val sourceFps = observed.sourceFps
                            if (frameTime != null && frameTime != lastSampleTime && sourceFps != null) {
                                lastSampleTime = frameTime
                                budgetOverruns = if (sample.nanos > 1_000_000_000.0 / sourceFps * .85) budgetOverruns + 1 else 0
                            }
                            if (budgetOverruns >= 3) {
                                check(clearOwnedShaders()) { "无法关闭已超出预算的模型" }
                                update(generation, state(mode, AnimeVideoEnhancementPhase.UNAVAILABLE,
                                    "实际 GPU pass 耗时超过帧预算，原画继续播放", width, height))
                                return@launch
                            }
                            val effect = when {
                                anime4kSize == null -> "ArtCNN 2× 亮度超分"
                                sample.upscaled -> "Anime4K 2× 放大"
                                scale == 2 -> "Anime4K 修复运行 · 当前画面尺寸未触发放大"
                                else -> "Anime4K 1× 修复"
                            }
                            update(generation, state(mode, AnimeVideoEnhancementPhase.ACTIVE,
                                "$effect · ${(sample.nanos / 10_000L) / 100.0} ms", width, height)
                                .copy(modelName = appliedModel))
                        }
                    }
                    // Keep HDR / video-track changes gated without replacing the
                    // event listener owned by Mediamp's playback state machine.
                    delay(500L)
                    if (!renderingActive.value) {
                        renderingActive.first { it }
                        lastSampleTime = null
                        budgetOverruns = 0
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrentSession(handle, generation, mode) && this@DesktopAnimeVideoEnhancement.handle === handle) {
                    val restored = clearOwnedShaders()
                    update(generation, state(mode, AnimeVideoEnhancementPhase.FAILED,
                        if (restored) "画质模型暂不可用，已恢复原始画质"
                        else "无法确认增强已关闭，请重新打开视频"))
                }
            }
        }
    }

    fun release() {
        monitoring?.cancel()
        monitoring = null
        clearOwnedShaders()
        // The caller immediately releases this native player, including its GPU
        // context; failed cleanup here cannot survive into the next session.
        handle = null
        ownsShaders = false
        mediaReady = false
        generation = -1L
    }

    private fun clearOwnedShaders(): Boolean {
        if (!ownsShaders) return true
        val current = handle ?: return false
        return clearShaderList(current).also { if (it) ownsShaders = false }
    }

    private fun clearShaderList(handle: MPVHandle): Boolean = try {
        val cleared = handle.command("change-list", "glsl-shaders", "clr", "")
        if (cleared && handle.getPropertyString("glsl-shaders") == "") true
        else handle.setPropertyString("glsl-shaders", "") && handle.getPropertyString("glsl-shaders") == ""
    } catch (_: Exception) { false }

    private fun positiveDimension(handle: MPVHandle, property: String): Int? =
        handle.getPropertyLongOrNull(property)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private data class ShaderPasses(val nanos: Long, val upscaled: Boolean)

    private data class Observation(
        val width: Int?, val height: Int?, val gamma: String?, val output: String?,
        val shaderList: String?, val shaderPasses: ShaderPasses?, val videoPts: Double?, val sourceFps: Double?,
    )

    private fun readObservation(handle: MPVHandle, anime4kSize: String?, model: EngineModel?): Observation {
        val width = positiveDimension(handle, "video-params/w")
        val height = positiveDimension(handle, "video-params/h")
        val gamma = handle.getPropertyString("video-params/gamma")?.lowercase()
        val output = handle.getPropertyString("current-vo")
        val shaderList = handle.getPropertyString("glsl-shaders")
        val passes = if (width != null && height != null &&
            gamma in SDR_TRANSFERS && output == "libmpv" && !shaderList.isNullOrBlank()) {
            val fresh = readFreshPasses(handle)
            if (anime4kSize != null) readAnime4KPasses(fresh, anime4kSize)
            else model?.let { readArtCnnPasses(fresh, it.title)?.let { nanos -> ShaderPasses(nanos, upscaled = true) } }
        } else null
        return Observation(width, height, gamma, output, shaderList, passes,
            if (passes != null) handle.getPropertyDoubleOrNull("time-pos")?.takeIf(Double::isFinite) else null,
            if (passes != null) handle.getPropertyDoubleOrNull("container-fps")?.takeIf { it.isFinite() && it > 0 } else null)
    }

    private data class RenderPass(val description: String, val nanos: Long)

    /** Mediamp reads raw string properties. mpv serializes this node as JSON and rejects scalar subkeys. */
    private fun readFreshPasses(handle: MPVHandle): List<RenderPass> {
        val payload = handle.getPropertyString("vo-passes") ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
        val fresh = root["fresh"] as? JsonArray ?: return emptyList()
        return fresh.mapNotNull { element ->
            val pass = element as? JsonObject ?: return@mapNotNull null
            val description = (pass["desc"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val nanos = (pass["last"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..1_000_000_000 }
                ?: return@mapNotNull null
            RenderPass(description, nanos)
        }
    }

    private fun readArtCnnPasses(passes: List<RenderPass>, name: String): Long? {
        val timings = linkedMapOf<String, Long>()
        for ((desc, nanos) in passes) {
            val start = desc.indexOf("$name (")
            if (start < 0) continue
            val pass = desc.substring(start + name.length + 2).substringBefore(')')
            timings[pass] = nanos
        }
        return timings.values.sum().takeIf { timings.size == 8 && "Depth-To-Space" in timings }
    }

    private fun readAnime4KPasses(passes: List<RenderPass>, size: String): ShaderPasses? {
        var elapsed = 0L
        var restores = false
        var upscales = false
        for ((description, time) in passes) {
            if ("Anime4K" !in description || "($size)" !in description) continue
            elapsed += time
            restores = restores || "Restore-CNN" in description
            upscales = upscales || "Upscale-CNN" in description
        }
        return ShaderPasses(elapsed, upscaled = upscales).takeIf { restores || upscales }
    }

    private fun state(
        mode: AnimeVideoEnhancementMode,
        phase: AnimeVideoEnhancementPhase,
        detail: String? = null,
        width: Int? = null,
        height: Int? = null,
    ) = AnimeVideoEnhancementState(mode, phase, detail, width, height)
    // video-out-params describes the decoded/filter size, not the render target.
    // Leave output dimensions unknown rather than presenting the input as 4K.

    private companion object {
        val HDR_TRANSFERS = setOf("pq", "hlg", "smpte2084", "arib-std-b67")
        val SDR_TRANSFERS = setOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2",
            "gamma2.4", "gamma2.6", "gamma2.8", "prophoto")
    }
}
