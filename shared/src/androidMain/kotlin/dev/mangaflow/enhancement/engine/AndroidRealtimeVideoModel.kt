@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.GlEffect
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.util.EnumSet
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
    private val bufferBytes: Long,
) : AutoCloseable {
    private val lock = Any()
    private var retired = false
    private var programs = 0

    fun effect(onProcessed: (elapsedNanos: Long) -> Unit): GlEffect = GlEffect { _, hdr ->
        if (hdr) throw VideoFrameProcessingException("HDR 保持原始色彩")
        synchronized(lock) {
            check(!retired) { "实时模型会话已结束" }
            programs++
        }
        try {
            AndroidRgbVideoProgram(session, width, height, scale, REALTIME_WORKING_BYTES - bufferBytes,
                checkActive = {}, onProcessed = onProcessed, onReleased = ::releaseProgram, cugan = cugan, onFrame = {})
        } catch (failure: Throwable) {
            releaseProgram()
            throw failure
        }
    }

    override fun close() {
        val dispose = synchronized(lock) {
            if (retired) false else { retired = true; programs == 0 }
        }
        if (dispose) dispose()
    }

    private fun releaseProgram() {
        val dispose = synchronized(lock) { --programs == 0 && retired }
        if (dispose) dispose()
    }

    private fun dispose() {
        try { session?.close() ?: cugan?.close() } finally { releaseBudget() }
    }

    companion object {
        suspend fun open(context: Context, model: EngineModel, width: Int, height: Int): AndroidRealtimeVideoModel {
            val vulkan = model.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO
            require(vulkan || Build.VERSION.SDK_INT >= 29) { "此 Android 版本不支持仅硬件的 NNAPI 推理" }
            require((vulkan || model.engineId == EnhancementEngineIds.ANDROID_VIDEO_ONNX) && EngineContent.VIDEO in model.contents &&
                model.nativeScale in setOf(1, 2, 4)) { "请选择与倍率匹配的番剧模型" }
            val pixelCount = width.toLong() * height
            val buffers = pixelCount * (1 + model.nativeScale * model.nativeScale) * 4
            require(width > 0 && height > 0 && pixelCount <= REALTIME_MAX_INPUT_PIXELS && buffers <= REALTIME_WORKING_BYTES / 2) {
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
                    return AndroidRealtimeVideoModel(null, opened, permit, width, height, model.nativeScale, buffers)
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
                return AndroidRealtimeVideoModel(created, null, permit, width, height, model.nativeScale, buffers)
            } catch (failure: Throwable) {
                try { native?.close() ?: cugan?.close() } finally { permit() }
                throw failure
            }
        }
    }
}
