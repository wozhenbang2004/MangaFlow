package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
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

/** Owns only this player's shader list. Revisions and publication stay on Main. */
internal class DesktopAnimeVideoEnhancement(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val renderingActive: StateFlow<Boolean>,
    private val frameMetrics: () -> AnimePlaybackFrameMetrics?,
    private val isCurrentSession: (MPVHandle, Long, AnimeVideoEnhancementMode) -> Boolean,
    private val update: (Long, AnimeVideoEnhancementState) -> Unit,
) {
    private data class Selection(
        val owner: ShaderOwner?, val generation: Long, val mode: AnimeVideoEnhancementMode,
        val mediaReady: Boolean, val scale: Int, val enhanceAtNativeSize: Boolean,
        val model: EngineModel?, val allowShaders: Boolean,
    )

    /** One lock for each actual mpv handle, retained across toggles on that handle. */
    private class ShaderOwner(val handle: MPVHandle) {
        @Volatile var ticket: Any? = null
        private var ownsList = false

        fun replace(current: Any, argument: String, expected: String): Boolean = synchronized(this) {
            if (current !== ticket) return@synchronized false
            // One native publication, not a transient empty graph or an incomplete preset.
            // Keep ownership even if the mutation succeeds but its readback fails.
            ownsList = true
            check(handle.command("raw", "no-osd", "change-list", "glsl-shaders", "set", argument)) {
                "The renderer did not accept the enhancement shader list"
            }
            if (current !== ticket) return@synchronized false
            check(handle.getPropertyString("glsl-shaders") == expected) {
                "The renderer did not confirm the complete shader list"
            }
            true
        }

        /** Without a ticket, this is the same-handle boundary before a different native consumer. */
        fun clear(current: Any? = null): Boolean = synchronized(this) {
            if (current != null && current !== ticket) return@synchronized false
            if (!ownsList) return@synchronized true
            val cleared = try {
                if (handle.command("raw", "no-osd", "change-list", "glsl-shaders", "clr", "") &&
                    handle.getPropertyString("glsl-shaders") == "") true
                else handle.setPropertyString("glsl-shaders", "") && handle.getPropertyString("glsl-shaders") == ""
            } catch (_: Exception) { false }
            if (cleared) ownsList = false
            cleared
        }
    }

    private var selection: Selection? = null
    private var monitoring: Job? = null
    private var cleanupConfirmed = true

    fun configure(handle: MPVHandle?, generation: Long, mode: AnimeVideoEnhancementMode, mediaReady: Boolean,
        scale: Int, enhanceAtNativeSize: Boolean, model: EngineModel?, allowShaders: Boolean): Boolean {
        val previous = selection
        val oldOwner = previous?.owner
        val owner = handle?.let { if (oldOwner != null && oldOwner.handle === it) oldOwner else ShaderOwner(it) }
        val next = Selection(owner, generation, mode, mediaReady, scale, enhanceAtNativeSize, model, allowShaders)
        if (next == previous) return cleanupConfirmed

        oldOwner?.ticket = null
        monitoring?.cancel()
        monitoring = null
        selection = next
        val wantsShaders = mediaReady && allowShaders && mode != AnimeVideoEnhancementMode.OFF
        val sameHandleBoundary = owner != null && owner === oldOwner &&
            (!wantsShaders || previous?.generation != generation || !cleanupConfirmed)
        // A native graph handoff or a new media open must not overtake a queued
        // shader command. The ordinary eligible GLSL -> GLSL switch skips this fence.
        cleanupConfirmed = !sameHandleBoundary || checkNotNull(owner).clear()
        if (!cleanupConfirmed) {
            update(generation, state(mode, AnimeVideoEnhancementPhase.FAILED,
                "无法确认增强已关闭，请重新打开视频"))
            return false
        }
        if (mode == AnimeVideoEnhancementMode.OFF) {
            update(generation, state(mode, AnimeVideoEnhancementPhase.OFF))
            return true
        }
        val ticket = if (owner != null && wantsShaders) Any() else null
        owner?.ticket = ticket
        update(generation, state(mode, AnimeVideoEnhancementPhase.WAITING, "等待视频画面"))
        if (owner == null || ticket == null || owner.ticket !== ticket) return true

        monitoring = scope.launch(Dispatchers.Main.immediate) {
            fun current() = owner.ticket === ticket && isCurrentSession(owner.handle, generation, mode)
            suspend fun clearCurrent(): Boolean = withContext(Dispatchers.IO) { owner.clear(ticket) }
            try {
                val paths = withContext(Dispatchers.IO) {
                    DesktopAnime4KShaderStore.prepare(context, mode, scale, model, enhanceAtNativeSize)
                }
                check(paths.isNotEmpty()) { "No enhancement shader was prepared" }
                val anime4kSize = when {
                    model != null && Anime4KOptionalModels.supports(model.engineId) ->
                        model.title.substringAfter(' ').substringBefore('/')
                    mode == AnimeVideoEnhancementMode.FAST -> "S"
                    mode == AnimeVideoEnhancementMode.QUALITY -> "M"
                    else -> null
                }
                val appliedModel = model?.title ?: anime4kSize?.let { "Anime4K $it/$it" }
                // mpv's OPT_PATHLIST parser only unescapes the native separator.
                // Preserve Windows drive colons and path backslashes; the getter prints unescaped paths.
                val separator = File.pathSeparator
                val argument = paths.joinToString(separator) { it.replace(separator, "\\$separator") }
                val expectedList = paths.joinToString(separator)
                var configured = false
                var configuredAt = 0L
                var lastSampleTime: Double? = null
                var budgetOverruns = 0
                while (current()) {
                    // Property reads synchronize with mpv's engine and may wait behind GPU work.
                    val observed = withContext(Dispatchers.IO) {
                        readObservation(owner.handle, anime4kSize, model, inspectGraph = configured)
                    }
                    if (!current()) return@launch
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
                        val cleared = clearCurrent()
                        if (!current()) return@launch
                        check(cleared) { "Cannot clear the current shader list" }
                        configured = false
                        lastSampleTime = null
                        budgetOverruns = 0
                        update(generation, state(mode, unavailable.first, unavailable.second, width, height))
                    } else {
                        // The pre-swap observation can belong to the previous graph.
                        val sample = observed.shaderPasses
                        if (!configured) {
                            val replaced = withContext(Dispatchers.IO) { owner.replace(ticket, argument, expectedList) }
                            if (!replaced || !current()) return@launch
                            configured = true
                            configuredAt = monotonicTimeMillis()
                        } else {
                            check(observed.shaderList == expectedList) {
                                "The renderer did not confirm the complete shader list"
                            }
                        }
                        // The embedded VO may not publish shader timings. Reuse the player's
                        // drop counts, counting only time spent on this exact graph.
                        val frames = if (enhanceAtNativeSize && renderingActive.value) frameMetrics() else null
                        val drops = frames?.run { recentDroppedFrames ?: recentOutputDroppedFrames ?: recentDecoderDroppedFrames }
                        if (frames != null && drops != null && frames.windowDurationMs >= 3_000L &&
                            monotonicTimeMillis() - configuredAt >= frames.windowDurationMs &&
                            drops * 1_000L >= frames.windowDurationMs * 4L) {
                            val cleared = clearCurrent()
                            if (!current()) return@launch
                            check(cleared) { "无法关闭已超出预算的模型" }
                            update(generation, state(mode, AnimeVideoEnhancementPhase.UNAVAILABLE,
                                "播放持续丢帧，已恢复原始画质", width, height))
                            return@launch
                        }
                        if (sample == null) {
                            val detail = if (enhanceAtNativeSize)
                                "已配置 2× 超分，画面按窗口尺寸显示。渲染器尚未返回实际输出记录。"
                            else "着色器配置已确认，暂无可读的增强输出记录。"
                            update(generation, state(mode, AnimeVideoEnhancementPhase.CONFIGURED,
                                detail, width, height)
                                .copy(modelName = appliedModel))
                        } else {
                            val frameTime = observed.videoPts
                            val sourceFps = observed.sourceFps
                            if (frameTime != null && frameTime != lastSampleTime && sourceFps != null) {
                                lastSampleTime = frameTime
                                budgetOverruns = if (sample.nanos > 1_000_000_000.0 / sourceFps * .85) budgetOverruns + 1 else 0
                            }
                            if (budgetOverruns >= 3) {
                                val cleared = clearCurrent()
                                if (!current()) return@launch
                                check(cleared) { "无法关闭已超出预算的模型" }
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
                        configuredAt = monotonicTimeMillis()
                        lastSampleTime = null
                        budgetOverruns = 0
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (current()) {
                    val restored = clearCurrent()
                    if (!current()) return@launch
                    update(generation, state(mode, AnimeVideoEnhancementPhase.FAILED,
                        if (restored) "画质模型暂不可用，已恢复原始画质"
                        else "无法确认增强已关闭，请重新打开视频"))
                }
            }
        }
        return true
    }

    fun release() {
        // This exact backend is about to close. Don't enqueue a second native mutation.
        // MediaMP's registry keeps an already entered JNI call safe across that close;
        // queued work loses its ticket, which cannot apply to a new handle.
        selection?.owner?.ticket = null
        selection = null
        monitoring?.cancel()
        monitoring = null
        cleanupConfirmed = true
    }

    private fun positiveDimension(handle: MPVHandle, property: String): Int? =
        handle.getPropertyLongOrNull(property)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private data class ShaderPasses(val nanos: Long, val upscaled: Boolean)

    private data class Observation(
        val width: Int?, val height: Int?, val gamma: String?, val output: String?,
        val shaderList: String?, val shaderPasses: ShaderPasses?, val videoPts: Double?, val sourceFps: Double?,
    )

    private fun readObservation(handle: MPVHandle, anime4kSize: String?, model: EngineModel?,
        inspectGraph: Boolean): Observation {
        val width = positiveDimension(handle, "video-params/w")
        val height = positiveDimension(handle, "video-params/h")
        val gamma = handle.getPropertyString("video-params/gamma")?.lowercase()
        val output = handle.getPropertyString("current-vo")
        val shaderList = if (inspectGraph) handle.getPropertyString("glsl-shaders") else null
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
