@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.PassthroughShaderProgram
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val REALTIME_WORKING_BYTES = 96L * 1024 * 1024
private const val REALTIME_MAX_INPUT_PIXELS = 1280L * 720L

/** The renderer and its GL programs share one verified optional hardware session and admission. */
internal class AndroidRealtimeVideoModel private constructor(
    private val session: OrtSession?,
    private val cugan: AndroidCuganSession?,
    private val releaseBudget: () -> Unit,
    private val width: Int,
    private val height: Int,
    private val scale: Int,
) : AutoCloseable {
    private val lock = Any()
    private var retired = false
    private var programs = 0
    private val firstModelFrame = AtomicBoolean(true)
    @Volatile private var activeFrames: AndroidRealtimeFrameStream? = null

    fun invalidateObservations() { activeFrames?.invalidate() }

    fun ownsFrame(sample: AndroidRealtimeFrameStream.Sample): Boolean =
        activeFrames?.window === sample.window

    fun effect(frameBudgetNanos: Double, onSample: (AndroidRealtimeFrameStream.Sample) -> Unit): GlEffect = GlEffect { _, hdr ->
        val frames = AndroidRealtimeFrameStream(maximumInputPixels(scale), frameBudgetNanos, firstModelFrame, onSample)
        synchronized(lock) {
            check(!retired) { "实时模型会话已结束" }
            programs++
            activeFrames = frames
        }
        try {
            if (hdr) object : PassthroughShaderProgram() {
                override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo,
                    presentationTimeUs: Long) {
                    val window = frames.window
                    super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
                    frames.copied(window, inputTexture.width, inputTexture.height, hdr = true)
                }

                override fun flush() {
                    frames.invalidate()
                    super.flush()
                }

                override fun release() {
                    try { super.release() } finally { releaseProgram(frames) }
                }
            } else AndroidRgbVideoProgram(session, width, height, scale, REALTIME_WORKING_BYTES,
                checkActive = {}, liveFrames = frames, onReleased = { releaseProgram(frames) }, cugan = cugan, onFrame = {})
        } catch (failure: Throwable) {
            releaseProgram(frames)
            throw failure
        }
    }

    override fun close() {
        val dispose = synchronized(lock) {
            if (retired) false else {
                retired = true
                activeFrames = null
                programs == 0
            }
        }
        if (dispose) dispose()
    }

    private fun releaseProgram(frames: AndroidRealtimeFrameStream) {
        val dispose = synchronized(lock) {
            if (activeFrames === frames) activeFrames = null
            --programs == 0 && retired
        }
        if (dispose) dispose()
    }

    private fun dispose() {
        try { session?.close() ?: cugan?.close() } finally { releaseBudget() }
    }

    companion object {
        private fun maximumInputPixels(scale: Int): Int {
            val bytesPerPixel = 4L * (1 + scale.toLong() * scale)
            return minOf(REALTIME_MAX_INPUT_PIXELS, REALTIME_WORKING_BYTES / 2 / bytesPerPixel).toInt()
        }

        fun supportsInput(width: Int, height: Int, scale: Int): Boolean =
            scale in setOf(1, 2, 4) && width > 0 && height > 0 && width.toLong() * height <= maximumInputPixels(scale)

        suspend fun open(context: Context, model: EngineModel, width: Int, height: Int): AndroidRealtimeVideoModel {
            val vulkan = model.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO
            require(vulkan || Build.VERSION.SDK_INT >= 29) { "此 Android 版本不支持仅硬件的 NNAPI 推理" }
            require((vulkan || model.engineId == EnhancementEngineIds.ANDROID_VIDEO_ONNX) && EngineContent.VIDEO in model.contents &&
                model.nativeScale in setOf(1, 2, 4)) { "请选择与倍率匹配的番剧模型" }
            require(supportsInput(width, height, model.nativeScale)) {
                "当前视频尺寸超出实时模型的工作范围，可使用提前或整集处理"
            }
            val resources = ExperiencePolicyStore.get(context)
            val permit = resources.tryAcquireEnhancement(0, REALTIME_WORKING_BYTES, sharedRealtimeOwner = "anime-player")
                ?: error("当前资源不足于启用实时模型，原画继续播放")
            var native: OrtSession? = null
            var cugan: AndroidCuganSession? = null
            try {
                if (vulkan) {
                    val opened = AndroidCuganVulkan(context, File(context.noBackupFilesDir, "enhancement")).open(model)
                    cugan = opened
                    currentCoroutineContext().ensureActive()
                    return AndroidRealtimeVideoModel(null, opened, permit, width, height, model.nativeScale)
                }
                check(engineSha256(File(model.path)) == model.sha256) { "视频模型已改变，请重新导入" }
                PanelRuntimeStore.get(context).ensureLoaded(PanelRuntimeId.ONNX)
                currentCoroutineContext().ensureActive()
                val created = OrtSession.SessionOptions().use { options ->
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    options.setMemoryPatternOptimization(false)
                    options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                    options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    OrtEnvironment.getEnvironment().createSession(model.path, options)
                }
                native = created
                val input = created.inputInfo.values.singleOrNull()?.info as? TensorInfo
                require(input != null && input.type == OnnxJavaType.FLOAT && input.shape.size == 4 &&
                    input.shape[0] in longArrayOf(1L, -1L) && input.shape[1] == 3L && created.outputNames.size == 1) {
                    "该模型的输入与输出协议无法用于直接播放"
                }
                currentCoroutineContext().ensureActive()
                return AndroidRealtimeVideoModel(created, null, permit, width, height, model.nativeScale)
            } catch (failure: Throwable) {
                try { native?.close() ?: cugan?.close() } finally { permit() }
                throw failure
            }
        }
    }
}

/** A seek or GL flush replaces the window. An in-flight draw can only update the window it captured. */
internal class AndroidRealtimeFrameStream(
    val maximumInputPixels: Int,
    private val frameBudgetNanos: Double,
    private val firstModelFrame: AtomicBoolean,
    private val onSample: (Sample) -> Unit,
) {
    // Media3's GL thread alone mutates a window. Main only compares its identity.
    class Window {
        var submitted = 0L
        var consecutiveSlow = 0
        var lastReportNanos = 0L
    }
    data class Sample(val window: Window, val submitted: Long, val elapsedNanos: Long, val completedAtMillis: Long,
        val inputWidth: Int, val inputHeight: Int, val width: Int, val height: Int, val tooSlow: Boolean,
        val originalReason: String? = null)

    @Volatile var window = Window()
        private set

    fun invalidate() { window = Window() }

    fun copied(owner: Window, width: Int, height: Int, hdr: Boolean = false) {
        if (owner !== window || owner.lastReportNanos != 0L) return
        owner.lastReportNanos = SystemClock.elapsedRealtimeNanos()
        val reason = if (hdr) "HDR 保持原始色彩，切回支持的 SDR 片段后会恢复实时模型"
            else "当前 ${width}×${height} 超出实时模型处理范围，切到支持的尺寸后会恢复"
        onSample(Sample(owner, 0L, 0L, owner.lastReportNanos / 1_000_000L,
            width, height, width, height, tooSlow = false, originalReason = reason))
    }

    fun submitted(owner: Window, startedAtNanos: Long, inputWidth: Int, inputHeight: Int, width: Int, height: Int) {
        if (owner !== window) return
        val completed = SystemClock.elapsedRealtimeNanos()
        val elapsed = completed - startedAtNanos
        owner.submitted++
        owner.consecutiveSlow = if (elapsed > frameBudgetNanos) owner.consecutiveSlow + 1 else 0
        val first = owner.submitted == 1L && firstModelFrame.compareAndSet(true, false)
        val tooSlow = first && elapsed > 120_000_000L || owner.consecutiveSlow >= 3
        if (tooSlow || owner.submitted == 1L || completed - owner.lastReportNanos >= 350_000_000L) {
            owner.lastReportNanos = completed
            onSample(Sample(owner, owner.submitted, elapsed, completed / 1_000_000L,
                inputWidth, inputHeight, width, height, tooSlow))
        }
    }
}
