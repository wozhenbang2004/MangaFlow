package dev.mangaflow.enhancement.engine

import android.content.Context
import android.content.pm.PackageManager
import dev.mangaflow.enhancement.EnhancedAssetIdentity
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Separate mobile Vulkan model with the original pinned RIFE v4.6 ncnn graph and weights. */
internal class AndroidRifeVulkanModels(context: Context) {
    private val runtime = PanelRuntimeStore.get(context)
    private val installed = File(context.noBackupFilesDir, "enhancement/rife-vulkan/v4.6-a7532fc")
    private val installLock = Mutex()
    private val hardware = context.packageManager.let {
        it.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            it.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    } && runtime.abi != null
    private val ready get() = runtime.states.value.getValue(PanelRuntimeId.CUGAN)

    fun descriptor(model: EngineModel?) = EngineDescriptor(
        EnhancementEngineIds.ANDROID_RIFE_VULKAN, "RIFE 4.6 · Android Vulkan", VERSION, "MIT / BSD-3-Clause",
        SOURCE, when {
            !hardware -> EngineReadiness.UNSUPPORTED
            !ready.installed || ready.pendingRemoval -> EngineReadiness.NOT_INSTALLED
            model == null -> EngineReadiness.NEEDS_MODELS
            else -> EngineReadiness.READY_FOR_TRIAL
        }, when {
            !hardware -> "当前应用架构或设备未报告 Vulkan 硬件能力"
            ready.pendingRemoval -> "Vulkan 组件将在重启后移除"
            !ready.installed -> "先安装签名 ncnn Vulkan 组件，再准备 RIFE 4.6 权重"
            model == null -> "下载并校验官方 RIFE 4.6 ncnn 参数和权重"
            else -> "移动端 Vulkan 独立型号；画面、实际耗时和可用帧率由播放验证"
        }, setOf(1), setOf(EngineContent.VIDEO), canInstall = hardware && !ready.pendingRemoval &&
            (!ready.installed || model == null), supportsInterpolation = true, realtime = true,
        downloadBytes = MODEL_BYTES.takeIf { ready.installed && model == null },
    )

    fun model(): EngineModel? {
        if (!hardware || !ready.installed || ready.pendingRemoval || PARTS.any { !it.matches(File(installed, it.name)) }) return null
        return EngineModel("rife-vulkan:4.6-a7532fc", EnhancementEngineIds.ANDROID_RIFE_VULKAN, "RIFE 4.6 · Vulkan",
            installed.canonicalPath, DIGEST, 1, setOf(EngineContent.VIDEO), "MIT", "4.6", MODEL_BYTES,
            inputType = "NCNN_RGB_PAIR_TIME_VKMAT")
    }

    suspend fun install(progress: (EngineProgress) -> Unit) = installLock.withLock {
        check(hardware) { "当前设备未报告 Vulkan 硬件能力" }
        installAndroidVulkanModel(runtime, installed, MODEL_URL, PARTS, progress)
    }

    suspend fun open(expected: EngineModel): Long {
        require(model() == expected) { "RIFE 4.6 Vulkan 参数或权重尚未通过本机校验" }
        runtime.ensureLoaded(PanelRuntimeId.CUGAN)
        currentCoroutineContext().ensureActive()
        return AndroidRifeVulkanJni.open(File(installed, PARTS[0].name).path, File(installed, PARTS[1].name).path)
            .also { check(it != 0L) { "当前 GPU 或驱动无法运行完整的 RIFE 4.6 Vulkan 网络" } }
    }

    companion object {
        private const val UPSTREAM = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"
        private const val VERSION = "ncnn-20261020 / RIFE 4.6 a7532fc / 1"
        private const val SOURCE = "https://github.com/nihui/rife-ncnn-vulkan/tree/$UPSTREAM"
        private const val MODEL_URL = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/$UPSTREAM/models/rife-v4.6"
        private const val MODEL_BYTES = 10_630_852L
        private val PARTS = listOf(
            AndroidVulkanModelPart("flownet.param", 16_532L, "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c"),
            AndroidVulkanModelPart("flownet.bin", 10_614_320L, "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958"),
        )
        private val DIGEST = EnhancedAssetIdentity.digest(PARTS.joinToString("|") { it.sha256 })
    }
}

/** The player retains this session until its GL program retires; close and inference cannot overlap. */
internal class AndroidLiveRifeVulkan private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun infer(previous: IntArray, next: IntArray, width: Int, height: Int, timestep: Float, destination: IntArray) {
        check(handle != 0L) { "RIFE Vulkan 会话已关闭" }
        require(width in 1..1920 && height in 1..1920 && width.toLong() * height <= 1280L * 720L) { "RIFE Vulkan 画面尺寸超出本机预算" }
        require(timestep > 0f && timestep < 1f && previous.size == width * height &&
            next.size == previous.size && destination.size == previous.size) { "RIFE Vulkan 帧对或时间参数不匹配" }
        val result = AndroidRifeVulkanJni.infer(handle, previous, next, width, height, timestep, destination)
        check(result == 0) { when (result) {
            -2 -> "RIFE Vulkan 推理缓冲未准备完成"
            -3 -> "RIFE Vulkan 命令提交或计算失败"
            -4 -> "RIFE Vulkan 输出形状或像素未通过校验"
            else -> "RIFE Vulkan 未收到完整的帧对"
        } }
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) AndroidRifeVulkanJni.close(current)
    }

    companion object {
        suspend fun open(context: Context, model: EngineModel): AndroidLiveRifeVulkan {
            val pointer = AndroidRifeVulkanModels(context).open(model)
            try {
                currentCoroutineContext().ensureActive()
                return AndroidLiveRifeVulkan(pointer)
            } catch (failure: Throwable) {
                AndroidRifeVulkanJni.close(pointer)
                throw failure
            }
        }
    }
}

internal object AndroidRifeVulkanJni {
    external fun open(graph: String, weights: String): Long
    external fun infer(handle: Long, previous: IntArray, next: IntArray, width: Int, height: Int,
        timestep: Float, destination: IntArray): Int
    external fun close(handle: Long)
}
