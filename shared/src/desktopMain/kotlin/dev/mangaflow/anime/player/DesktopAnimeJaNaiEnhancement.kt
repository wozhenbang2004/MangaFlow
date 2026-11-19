package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.DesktopAnimeJaNai
import dev.mangaflow.enhancement.engine.EngineModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.openani.mediamp.mpv.MPVHandle
import kotlin.math.abs
import kotlin.math.roundToInt

/** The filter stays in the current player's graph; audio, subtitles, seeking and track ownership stay with mpv. */
internal class DesktopAnimeJaNaiEnhancement(
    private val runtime: DesktopAnimeJaNai,
    private val scope: CoroutineScope,
    private val renderingActive: StateFlow<Boolean>,
    private val refreshHz: () -> Double?,
    private val update: (Long, AnimeVideoEnhancementState?, AnimeRealtimeMotionState?) -> Unit,
) {
    private data class Target(
        val handle: MPVHandle,
        val generation: Long,
        val mode: AnimeVideoEnhancementMode,
        val model: EngineModel?,
        val frames: AnimeRealtimeFrameRequest?,
    )

    private var target: Target? = null
    private var job: Job? = null
    private var filter: DesktopAnimeJaNai.FilterSession? = null

    fun configure(handle: MPVHandle?, generation: Long, mode: AnimeVideoEnhancementMode,
        mediaReady: Boolean, model: EngineModel?, frames: AnimeRealtimeFrameRequest?) {
        val next = handle?.takeIf { mediaReady && (model != null || frames != null) }
            ?.let { Target(it, generation, if (model == null) AnimeVideoEnhancementMode.OFF else mode, model, frames) }
        if (target == next) return
        release()
        target = next
        if (next == null) return

        job = scope.launch(Dispatchers.Main.immediate) {
            var inputWidth: Int? = null
            var inputHeight: Int? = null
            var targetFps: Double? = null
            fun state(phase: AnimeVideoEnhancementPhase, detail: String, width: Int? = null, height: Int? = null,
                outputFps: Double? = null) {
                if (target != next) return
                val quality = next.model?.let { model ->
                    AnimeVideoEnhancementState(next.mode, phase, detail, inputWidth, inputHeight, width, height,
                        modelName = model.title.takeIf { phase == AnimeVideoEnhancementPhase.ACTIVE })
                }
                val motion = next.frames?.let { frames ->
                    val noTarget = targetFps == null && phase == AnimeVideoEnhancementPhase.ACTIVE
                    AnimeRealtimeMotionState(frames.model.title,
                        if (noTarget) AnimeVideoEnhancementPhase.UNAVAILABLE else phase,
                        if (noTarget) "目标帧率未落在当前源帧率的可补帧范围" else detail,
                        graphFps = outputFps, inputWidth = inputWidth, inputHeight = inputHeight,
                        targetFps = targetFps)
                }
                update(generation, quality, motion)
            }
            try {
                if (!runtime.usesNativeRuntime()) {
                    state(AnimeVideoEnhancementPhase.UNAVAILABLE, runtime.unavailableReason())
                    return@launch
                }
                var initialPts: Double? = null
                var outputDeadlineNanos = Long.MAX_VALUE
                var lastDropCount: Long? = null
                var overloadSamples = 0
                while (target == next) {
                    val backend = next.handle
                    val observation = withContext(Dispatchers.IO) { readObservation(backend) }
                    if (target != next) return@launch
                    inputWidth = observation.width
                    inputHeight = observation.height
                    val color = observation.color
                    val sourceFps = observation.sourceFps
                    val width = inputWidth
                    val height = inputHeight
                    val format = observation.hardwareDecoder
                    val paused = observation.paused
                    when {
                        width == null || height == null || color.isNullOrBlank() || color in setOf("auto", "unknown") -> {
                            state(AnimeVideoEnhancementPhase.WAITING, "等待解码画面与色彩信息")
                            delay(400)
                            renderingActive.first { it }
                            continue
                        }
                        color !in sdr || width.toLong() * height > 1920L * 1080L || sourceFps == null -> {
                            clearFilter()
                            state(AnimeVideoEnhancementPhase.UNAVAILABLE, "当前片源不在 SDR 1080p 与 12–61 FPS 的试用范围")
                            return@launch
                        }
                        format != "d3d11va" -> {
                            clearFilter()
                            state(AnimeVideoEnhancementPhase.UNAVAILABLE, "当前视频未使用 Direct3D 11 硬件解码")
                            return@launch
                        }
                    }
                    val frames = next.frames
                    if (filter == null) {
                        val ratio = frames?.let { request ->
                            when {
                                request.automatic -> {
                                    val ceiling = refreshHz()?.takeIf { it.isFinite() && it > 0 } ?: 240.0
                                    rationalFactor(minOf(sourceFps * 2, ceiling, 240.0), sourceFps)
                                }
                                request.targetFps == null -> 2 to 1
                                else -> rationalFactor(request.targetFps, sourceFps)
                            }
                        }
                        if (frames != null && ratio == null && next.model == null) {
                            state(AnimeVideoEnhancementPhase.UNAVAILABLE, "目标帧率未落在当前源帧率的可补帧范围")
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            val prepared = runtime.session(next.model, frames?.model?.takeIf { ratio != null }, ratio)
                            // Returning a resource through a canceled dispatcher hop can lose its owner.
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    if (target == next) filter = prepared else prepared?.close()
                                }
                            } catch (failure: Throwable) {
                                prepared?.close()
                                throw failure
                            }
                        }
                        val native = filter
                        if (native == null) {
                            state(AnimeVideoEnhancementPhase.UNAVAILABLE, runtime.unavailableReason())
                            return@launch
                        }
                        val startPts = observation.position
                        check(backend.command("vf", "add", native.filter)) { "原生滤镜无法加载" }
                        targetFps = ratio?.let { (numerator, denominator) -> sourceFps * numerator / denominator }
                        initialPts = startPts
                        outputDeadlineNanos = System.nanoTime() + 12_000_000_000L
                        state(AnimeVideoEnhancementPhase.CONFIGURED, "DirectML 已配置，等待实际输出")
                    }
                    val native = checkNotNull(filter)
                    val receipt = withContext(Dispatchers.IO) {
                        runCatching { native.stats.takeIf { it.isFile && it.length() in 1L..32768L }?.readText() }.getOrNull()
                    }
                    val outWidth = observation.outputWidth
                    val outHeight = observation.outputHeight
                    val position = observation.position
                    val filteredFps = observation.filteredFps
                    val modelReceipt = if (native.temporal) "Applied Temporal Model: ${native.weightName} ("
                        else "Applied Model: ${native.weightName};"
                    val modelApplied = native.weightName == null || (receipt?.contains(modelReceipt) == true &&
                        outWidth == width * native.scale && outHeight == height * native.scale)
                    val interpolated = !native.interpolate || (receipt?.contains("applied RIFE v422", ignoreCase = true) == true &&
                        filteredFps != null && filteredFps > sourceFps * 1.07)
                    val observed = modelApplied && interpolated && position != null && position != initialPts
                    if (observed) {
                        val dropped = observation.droppedFrames
                        val previousDrops = lastDropCount
                        if (!paused && dropped != null && previousDrops != null && dropped - previousDrops >= 4) overloadSamples++
                        else overloadSamples = 0
                        lastDropCount = dropped
                        if (overloadSamples >= 3) {
                            clearFilter()
                            state(AnimeVideoEnhancementPhase.UNAVAILABLE, "连续观察到较多丢帧，已恢复原画与源帧率")
                            return@launch
                        }
                        val nativePasses = receipt?.lineSequence()?.any { "Native kernels:" in it } == true
                        val directMlPasses = receipt?.contains("; using DirectML") == true
                        val executedBackend = when {
                            nativePasses && directMlPasses -> "原生 GPU Kernel / DirectML 已输出"
                            nativePasses -> "原生 GPU Kernel 已输出"
                            else -> "DirectML 已输出"
                        }
                        val detail = listOfNotNull(executedBackend,
                            if (native.weightName != null) "${outWidth}×$outHeight" else null,
                            if (native.interpolate && filteredFps != null) "滤镜输出估计 ${(filteredFps * 10).roundToInt() / 10.0} FPS" else null)
                        state(AnimeVideoEnhancementPhase.ACTIVE, detail.joinToString(" · "), outWidth, outHeight,
                            filteredFps.takeIf { native.interpolate })
                        outputDeadlineNanos = System.nanoTime() + 12_000_000_000L
                    } else if (paused) {
                        outputDeadlineNanos = System.nanoTime() + 12_000_000_000L
                    } else if (System.nanoTime() >= outputDeadlineNanos) {
                        clearFilter()
                        state(AnimeVideoEnhancementPhase.UNAVAILABLE, "未观察到模型输出，已恢复原画与源帧率")
                        return@launch
                    }
                    delay(600)
                    if (!renderingActive.value) {
                        renderingActive.first { it }
                        outputDeadlineNanos = System.nanoTime() + 12_000_000_000L
                        lastDropCount = null
                        overloadSamples = 0
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (target == next) {
                    clearFilter()
                    state(AnimeVideoEnhancementPhase.FAILED, "原生增强暂不可用，已请求恢复原画与源帧率")
                }
            }
        }
    }

    fun release() {
        job?.cancel()
        job = null
        clearFilter()
        target = null
    }

    private fun clearFilter() {
        val prepared = filter ?: return
        filter = null
        runCatching { target?.handle?.command("vf", "remove", "@mangaflow-aji") }
        prepared.close()
    }

    private fun dimension(handle: MPVHandle, property: String): Int? =
        handle.getPropertyLongOrNull(property)?.takeIf { it in 1..32768 }?.toInt()

    private data class Observation(
        val width: Int?, val height: Int?, val color: String?, val sourceFps: Double?,
        val hardwareDecoder: String?, val paused: Boolean, val outputWidth: Int?, val outputHeight: Int?,
        val position: Double?, val filteredFps: Double?, val droppedFrames: Long?,
    )

    /** Native property reads may wait for mpv; their registry-owned lifetime is safe off the UI thread. */
    private fun readObservation(handle: MPVHandle) = Observation(
        width = dimension(handle, "video-params/w"),
        height = dimension(handle, "video-params/h"),
        color = handle.getPropertyString("video-params/gamma")?.lowercase(),
        sourceFps = handle.getPropertyDoubleOrNull("container-fps")?.takeIf { it.isFinite() && it in 12.0..61.0 },
        hardwareDecoder = handle.getPropertyString("hwdec-current"),
        paused = handle.getPropertyBooleanOrNull("pause") == true,
        outputWidth = dimension(handle, "video-out-params/w"),
        outputHeight = dimension(handle, "video-out-params/h"),
        position = handle.getPropertyDoubleOrNull("video-pts")?.takeIf(Double::isFinite),
        filteredFps = handle.getPropertyDoubleOrNull("estimated-vf-fps")?.takeIf { it.isFinite() && it in 1.0..240.0 },
        droppedFrames = handle.getPropertyLongOrNull("frame-drop-count")?.takeIf { it >= 0L },
    )

    private fun rationalFactor(target: Double, source: Double): Pair<Int, Int>? {
        val ratio = target / source
        if (ratio <= 1.01 || ratio > 8) return null
        var nearest = Double.MAX_VALUE
        var selected: Pair<Int, Int>? = null
        for (denominator in 1..1001) {
            val numerator = (denominator * ratio).roundToInt()
            val error = abs(numerator.toDouble() / denominator - ratio)
            if (error < nearest) {
                nearest = error
                selected = numerator to denominator
                if (error < 0.0000001) break
            }
        }
        return selected
    }

    private companion object {
        val sdr = setOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.8", "prophoto")
    }
}
