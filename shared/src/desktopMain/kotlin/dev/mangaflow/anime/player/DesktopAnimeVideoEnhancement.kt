package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MPVHandle
import java.io.File

/** Owns only this player's shader list. Calls and publication are confined to Main. */
internal class DesktopAnimeVideoEnhancement(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
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
                // append accepts a single literal path, even with ':' / ';' / '\\'.
                // mpv's string getter prints this list with the platform separator.
                val expectedList = paths.joinToString(File.pathSeparator)
                var configured = false
                var lastSampleTime: Double? = null
                var budgetOverruns = 0
                while (isCurrentSession(handle, generation, mode)) {
                    val width = positiveDimension(handle, "video-params/w")
                    val height = positiveDimension(handle, "video-params/h")
                    val gamma = handle.getPropertyString("video-params/gamma")?.lowercase()
                    val output = handle.getPropertyString("current-vo")
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
                        }
                        check(handle.getPropertyString("glsl-shaders") == expectedList) {
                            "The renderer did not confirm the complete shader list"
                        }
                        val sample = model?.let { readArtCnnPasses(handle, it.title) }
                        if (sample == null) {
                            update(generation, state(mode, AnimeVideoEnhancementPhase.CONFIGURED,
                                if (model == null) "播放器未反馈增强结果，尚不能确认是否生效"
                                else "模型已加载，等待光栅尺寸和渲染器执行 ArtCNN", width, height))
                        } else {
                            val frameTime = handle.getPropertyDoubleOrNull("video-pts")?.takeIf(Double::isFinite)
                            val sourceFps = handle.getPropertyDoubleOrNull("container-fps")?.takeIf { it.isFinite() && it > 0 }
                            if (frameTime != null && frameTime != lastSampleTime && sourceFps != null) {
                                lastSampleTime = frameTime
                                budgetOverruns = if (sample > 1_000_000_000.0 / sourceFps * .85) budgetOverruns + 1 else 0
                            }
                            if (budgetOverruns >= 3) {
                                check(clearOwnedShaders()) { "无法关闭已超出预算的模型" }
                                update(generation, state(mode, AnimeVideoEnhancementPhase.UNAVAILABLE,
                                    "实际 GPU pass 耗时超过帧预算，原画继续播放", width, height))
                                return@launch
                            }
                            update(generation, state(mode, AnimeVideoEnhancementPhase.ACTIVE,
                                "2× 亮度超分 · 本次 8 个 GPU pass ${(sample / 10_000L) / 100.0}ms", width, height)
                                .copy(modelName = model?.title))
                        }
                    }
                    // Keep HDR / video-track changes gated without replacing the
                    // event listener owned by Mediamp's playback state machine.
                    delay(500L)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isCurrentSession(handle, generation, mode)) {
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

    /** Scalar subproperties of mpv 0.40's latest fresh-render pass data. They aren't a display FPS counter. */
    private fun readArtCnnPasses(handle: MPVHandle, name: String): Long? {
        val prefix = "vo-passes/fresh"
        val count = handle.getPropertyLongOrNull("$prefix/count")?.takeIf { it in 1..128 }?.toInt() ?: return null
        val timings = linkedMapOf<String, Long>()
        for (index in 0 until count) {
            val desc = handle.getPropertyString("$prefix/$index/desc") ?: continue
            val start = desc.indexOf("$name (")
            if (start < 0) continue
            val pass = desc.substring(start + name.length + 2).substringBefore(')')
            val nanos = handle.getPropertyLongOrNull("$prefix/$index/last")?.takeIf { it in 1..1_000_000_000 } ?: continue
            timings[pass] = nanos
        }
        return timings.values.sum().takeIf { timings.size == 8 && "Depth-To-Space" in timings }
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
