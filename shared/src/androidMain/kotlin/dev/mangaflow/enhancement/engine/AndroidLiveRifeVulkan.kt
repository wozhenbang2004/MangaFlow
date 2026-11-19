package dev.mangaflow.enhancement.engine

import android.content.Context
import android.content.pm.PackageManager
import dev.mangaflow.enhancement.EnhancedAssetIdentity
import dev.mangaflow.experience.ExperiencePolicyStore
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
            ready.requiresUpdate -> EngineReadiness.FAILED
            model == null -> EngineReadiness.NEEDS_MODELS
            else -> EngineReadiness.READY_FOR_TRIAL
        }, when {
            !hardware -> "当前应用架构或设备未报告 Vulkan 硬件能力"
            ready.pendingRemoval -> "Vulkan 组件将在重启后移除"
            ready.requiresUpdate -> "组件需要更新至 1.2.0；已下载的 RIFE 权重保留，更新后支持原尺寸 1080p SDR 输入"
            !ready.installed -> "先安装签名 ncnn Vulkan 组件，再准备 RIFE 4.6 权重"
            model == null -> "下载并校验官方 RIFE 4.6 ncnn 参数和权重"
            else -> "接受最高 1080p SDR 原尺寸输入，先补帧再画质增强；这是输入范围，实时速度取决于设备与组合负载，超预算保留原帧率"
        }, setOf(1), setOf(EngineContent.VIDEO), canInstall = hardware && !ready.pendingRemoval &&
            (!ready.installed || ready.requiresUpdate || model == null), supportsInterpolation = true, realtime = true,
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
        check(!ready.requiresUpdate) { "组件需要更新至 1.2.0；请在补帧组件中更新，已下载的 RIFE 权重会保留" }
        require(model() == expected) { "RIFE 4.6 Vulkan 参数或权重尚未通过本机校验" }
        runtime.ensureLoaded(PanelRuntimeId.CUGAN)
        currentCoroutineContext().ensureActive()
        return AndroidRifeVulkanJni.open(File(installed, PARTS[0].name).path, File(installed, PARTS[1].name).path)
            .also { check(it != 0L) { "当前 GPU 或驱动无法运行完整的 RIFE 4.6 Vulkan 网络" } }
    }

    companion object {
        private const val UPSTREAM = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"
        private const val VERSION = "ncnn-20261020 / RIFE 4.6 a7532fc / 2"
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
internal class AndroidLiveRifeVulkan private constructor(
    private var handle: Long,
    private val resources: ExperiencePolicyStore,
    private var reservedBytes: Long,
    private var releaseBudget: () -> Unit,
) : AutoCloseable {
    @Synchronized
    fun prepareFrameSize(width: Int, height: Int) {
        check(handle != 0L) { "RIFE Vulkan 会话已关闭" }
        val needed = memoryBudget(width, height)
        if (needed > reservedBytes) {
            // Keep the old reservation until a larger frame has secured its replacement.
            val release = resources.tryAcquireEnhancement(0, needed, sharedRealtimeOwner = "anime-player")
                ?: error("当前资源不足以处理此尺寸的实时补帧")
            val previousRelease = releaseBudget
            releaseBudget = release
            reservedBytes = needed
            previousRelease()
        }
    }

    @Synchronized
    fun infer(previous: IntArray, next: IntArray, width: Int, height: Int, timestep: Float, destination: IntArray) {
        prepareFrameSize(width, height)
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
        if (current != 0L) try { AndroidRifeVulkanJni.close(current) } finally { releaseBudget() }
    }

    companion object {
        // The graph accepts dynamic shapes. These are the same bounded native limits,
        // not a resize: original frames and generated frames retain the input dimensions.
        const val MAX_SIDE = 1920
        const val MAX_PIXELS = 1920 * 1080

        fun memoryBudget(width: Int, height: Int): Long {
            val pixels = width.toLong() * height
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && pixels <= MAX_PIXELS) {
                "实时补帧支持最高 1080p 输入，当前 ${width}×${height} 保持源帧率"
            }
            // Scale the existing admission estimate; this is not observed process/GPU memory.
            return maxOf(128L * 1024 * 1024, (128L * 1024 * 1024 * pixels + 1280 * 720 - 1) / (1280 * 720))
        }

        suspend fun open(context: Context, model: EngineModel, width: Int, height: Int): AndroidLiveRifeVulkan {
            val resources = ExperiencePolicyStore.get(context)
            val bytes = memoryBudget(width, height)
            val release = resources.tryAcquireEnhancement(0, bytes, sharedRealtimeOwner = "anime-player")
                ?: error("当前资源不足以开启实时补帧")
            var pointer = 0L
            try {
                pointer = AndroidRifeVulkanModels(context).open(model)
                currentCoroutineContext().ensureActive()
                return AndroidLiveRifeVulkan(pointer, resources, bytes, release)
            } catch (failure: Throwable) {
                try { if (pointer != 0L) AndroidRifeVulkanJni.close(pointer) } finally { release() }
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
