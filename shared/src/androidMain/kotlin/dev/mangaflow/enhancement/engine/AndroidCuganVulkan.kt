package dev.mangaflow.enhancement.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import dev.mangaflow.enhancement.EnhancedAssetIdentity
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authentic Real-CUGAN NOSE 2×, using the signed optional ncnn VkMat runtime. */
internal class AndroidCuganVulkan(context: Context, root: File) {
    private val runtimes = PanelRuntimeStore.get(context)
    private val modelRoot = File(root, "real-cugan")
    private val installed = File(modelRoot, "nose-2x-395302c")
    private val installs = Mutex()
    private val hardwareAdvertised = context.packageManager.let {
        it.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            it.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }
    private val available get() = hardwareAdvertised && runtimes.abi != null
    private val runtime get() = runtimes.states.value.getValue(PanelRuntimeId.CUGAN)

    fun descriptor(model: EngineModel?, video: Boolean = false) = EngineDescriptor(
        if (video) EnhancementEngineIds.ANDROID_CUGAN_VIDEO else EnhancementEngineIds.ANDROID_CUGAN,
        if (video) "Real-CUGAN · Vulkan 番剧 2×" else "Real-CUGAN · Vulkan 2×",
        if (video) VIDEO_VERSION else ENGINE_VERSION, "MIT / BSD-3-Clause",
        SOURCE_URL, when {
            !available -> EngineReadiness.UNSUPPORTED
            !runtime.installed || runtime.pendingRemoval -> EngineReadiness.NOT_INSTALLED
            model == null -> EngineReadiness.NEEDS_MODELS
            else -> EngineReadiness.READY_FOR_TRIAL
        }, when {
            !hardwareAdvertised -> "此设备未报告 Vulkan 硬件能力，继续使用原图或 ONNX"
            !available -> "当前应用架构没有可加载的 ncnn Vulkan 组件"
            runtime.pendingRemoval -> "ncnn Vulkan 组件将在重启后移除"
            !runtime.installed -> "先下载签名 Vulkan 组件，再准备官方 NOSE 2× 模型"
            model == null -> "安装需下载并校验官方 NOSE 2× 权重与网络参数（约 2.6 MB）"
            else -> if (video) "兼容 SDR 视频可前方预处理、即时试用或离线增强。实时成功以实际处理回执为准。"
                else "彩色漫画和条漫可选用原版 NOSE 2× 无降噪模型，实际设备兼容性在处理时确认"
        }, setOf(2), if (video) setOf(EngineContent.VIDEO) else CONTENTS, canInstall = available && !runtime.pendingRemoval && (!runtime.installed || model == null),
        downloadBytes = MODEL_BYTES.takeIf { runtime.installed && model == null },
        videoOutput = EngineVideoContainer.MP4.takeIf { video }, supportsInterpolation = video, realtime = video,
    )

    fun model(): EngineModel? {
        if (!available || PARTS.any { !it.matches(File(installed, it.name)) }) return null
        return EngineModel(ENGINE_MODEL_ID, EnhancementEngineIds.ANDROID_CUGAN, "Real-CUGAN NOSE · 2× 无降噪",
            installed.canonicalPath, MODEL_IDENTITY, 2, CONTENTS, "MIT", UPSTREAM_COMMIT, MODEL_BYTES,
            inputType = "NCNN_RGB_VKMAT")
    }

    fun videoModel(image: EngineModel?) = image?.copy(id = "$ENGINE_MODEL_ID:video",
        engineId = EnhancementEngineIds.ANDROID_CUGAN_VIDEO, contents = setOf(EngineContent.VIDEO))

    fun matches(request: EngineModel): Boolean =
        if (request.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO) videoModel(model()) == request else model() == request

    suspend fun open(request: EngineModel): AndroidCuganSession {
        require(matches(request)) { "Real-CUGAN 模型尚未通过本机校验" }
        runtimes.ensureLoaded(PanelRuntimeId.CUGAN)
        val handle = AndroidCuganVulkanJni.open(File(installed, PARTS[0].name).path, File(installed, PARTS[1].name).path)
        check(handle != 0L) { "当前 GPU 或 ncnn 驱动无法运行这个完整 Vulkan 模型" }
        return AndroidCuganSession(handle)
    }

    suspend fun install(progress: (EngineProgress) -> Unit) = installs.withLock {
        check(available) { "当前设备无法加载 Vulkan 图像增强组件" }
        installAndroidVulkanModel(runtimes, installed, MODEL_URL, PARTS, progress)
    }

    suspend fun render(decoder: BitmapRegionDecoder, output: File, request: EngineImageRequest,
        progress: (EngineProgress) -> Unit) {
        val model = checkNotNull(request.model) { "Real-CUGAN 模型尚未选择" }
        require(model.engineId == EnhancementEngineIds.ANDROID_CUGAN && request.scale == 2 && request.content in CONTENTS &&
            request.tileSize == TILE_SIZE && request.overlap == PADDING) {
            "Real-CUGAN 模型、范围、倍率或分块身份不匹配"
        }
        val coroutine = currentCoroutineContext()
        progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "加载 ncnn Vulkan 和 Real-CUGAN NOSE"))
        open(model).use { session ->
            val tiles = session.tiles(decoder.width, decoder.height, request.maximumOutputBytes)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            EnginePngWriter(output, tiles.outWidth, tiles.outHeight).use { writer ->
                tiles.render(coroutine::ensureActive, { left, top, width, height, pixels ->
                    val bitmap = decoder.decodeRegion(Rect(left, top, left + width, top + height), options)
                        ?: error("无法解码 Real-CUGAN 分块")
                    try { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                    finally { bitmap.recycle() }
                }, { _, row -> writer.row(row) }) { completed, total ->
                    progress(EngineProgress(EngineStage.PROCESS, completed, total,
                        detail = if (completed == 0L) "正在处理首个分块 · Vulkan Real-CUGAN" else "Real-CUGAN · Vulkan 已完成分块"))
                }
                writer.finish()
            }
            check(matches(model)) { "处理期间 Real-CUGAN 模型发生变化" }
        }
    }

    companion object {
        const val ENGINE_VERSION = "ncnn-20261020 / Real-CUGAN NOSE 395302c / 1"
        const val VIDEO_VERSION = "$ENGINE_VERSION / $ANDROID_VIDEO_ENGINE_VERSION"
        const val TILE_SIZE = EnhancementEngineIds.ANDROID_CUGAN_TILE_SIZE
        const val PADDING = EnhancementEngineIds.ANDROID_CUGAN_PADDING
        private const val UPSTREAM_COMMIT = "395302c5c70f1bff604c974e92e0a87e45c9f9ee"
        private const val SOURCE_URL = "https://github.com/nihui/realcugan-ncnn-vulkan/tree/$UPSTREAM_COMMIT"
        private const val MODEL_URL = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/$UPSTREAM_COMMIT/models/models-nose"
        private const val ENGINE_MODEL_ID = "real-cugan:nose-2x-395302c"
        private const val MODEL_BYTES = 2_554_717L
        private val CONTENTS = setOf(EngineContent.COLOR, EngineContent.WEBTOON)
        private val PARTS = listOf(
            AndroidVulkanModelPart("up2x-no-denoise.param", 3_309L, "c370b70321d45301b9c682121130ad167b3dd1bc8e122c1d77c7ac44f1943708"),
            AndroidVulkanModelPart("up2x-no-denoise.bin", 2_551_408L, "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a"),
        )
        private val MODEL_IDENTITY = EnhancedAssetIdentity.digest(PARTS.joinToString("|") { it.sha256 })
    }
}

internal object AndroidCuganVulkanJni {
    external fun open(params: String, weights: String): Long
    external fun process(handle: Long, input: IntArray, output: IntArray, width: Int, height: Int): Int
    external fun close(handle: Long)
}

internal typealias AndroidRgbRegion = (left: Int, top: Int, width: Int, height: Int, pixels: IntArray) -> Unit
internal typealias AndroidRgbRow = (y: Int, pixels: IntArray) -> Unit

internal class AndroidCuganSession(private val handle: Long) : AutoCloseable {
    fun tiles(width: Int, height: Int, budget: Long) = AndroidCuganTiles(handle, width, height, budget)
    override fun close() = AndroidCuganVulkanJni.close(handle)
}

internal class AndroidCuganTiles(private val handle: Long, private val width: Int, private val height: Int, budget: Long) {
    val outWidth = Math.multiplyExact(width, 2)
    val outHeight = Math.multiplyExact(height, 2)
    private val tileSize = AndroidCuganVulkan.TILE_SIZE
    private val padding = AndroidCuganVulkan.PADDING
    private val bandHeight = minOf(height, tileSize) * 2
    private val band: IntArray
    private val row: IntArray
    private val columns = (0 until width step tileSize).associateWith { regionAxis(it, width) }
    private val rows = (0 until height step tileSize).associateWith { regionAxis(it, height) }
    private val raw = IntArray((tileSize + padding * 2) * (tileSize + padding * 2))
    private val paddedBuffers = mutableMapOf<Int, IntArray>()
    private val outputBuffers = mutableMapOf<Int, IntArray>()

    init {
        require(outWidth.toLong() * bandHeight * Int.SIZE_BYTES + 8L * 1024 * 1024 <= budget) {
            "Real-CUGAN 行缓冲超过当前处理预算"
        }
        band = IntArray(Math.multiplyExact(outWidth, bandHeight))
        row = IntArray(outWidth)
    }

    fun render(checkActive: () -> Unit, read: AndroidRgbRegion, write: AndroidRgbRow,
        onTile: (Long, Long) -> Unit = { _, _ -> }) {
        var completed = 0L
        val total = columns.size.toLong() * rows.size
        onTile(0L, total)
        for ((top, ys) in rows) {
            for ((left, xs) in columns) {
                checkActive()
                read(xs.minimum, ys.minimum, xs.span, ys.span, raw)
                require((0 until xs.span * ys.span).all { raw[it] ushr 24 == 255 }) { "Real-CUGAN 路径保留含透明像素的原图" }
                val padded = paddedBuffers.getOrPut(xs.offsets.size * ys.offsets.size) { IntArray(xs.offsets.size * ys.offsets.size) }
                val result = outputBuffers.getOrPut(xs.size * ys.size * 4) { IntArray(xs.size * ys.size * 4) }
                for (index in padded.indices) {
                    padded[index] = raw[ys.offsets[index / xs.offsets.size] * xs.span + xs.offsets[index % xs.offsets.size]]
                }
                val status = AndroidCuganVulkanJni.process(handle, padded, result, xs.size, ys.size)
                check(status == 0) { when (status) {
                    -2 -> "Vulkan 分块资源不足"
                    -3 -> "Vulkan 模型提交或执行未完成"
                    -4 -> "Real-CUGAN 输出像素或尺寸未通过校验"
                    else -> "Real-CUGAN 输入传递未完成"
                } }
                checkActive()
                val stride = xs.size * 2
                for (y in 0 until ys.size * 2) {
                    result.copyInto(band, y * outWidth + left * 2, y * stride, (y + 1) * stride)
                }
                onTile(++completed, total)
            }
            for (y in 0 until ys.size * 2) {
                band.copyInto(row, 0, y * outWidth, (y + 1) * outWidth)
                write(top * 2 + y, row)
            }
        }
    }

    private data class Axis(val minimum: Int, val span: Int, val size: Int, val offsets: IntArray)

    private fun regionAxis(start: Int, limit: Int): Axis {
        val size = minOf(tileSize, limit - start)
        val coordinates = IntArray(size + size % 2 + padding * 2) { index ->
            if (limit <= 1) 0 else {
                val cycle = (limit - 1) * 2
                val position = Math.floorMod(start - padding + index, cycle)
                if (position < limit) position else cycle - position
            }
        }
        val minimum = coordinates.min()
        val span = coordinates.max() - minimum + 1
        for (index in coordinates.indices) coordinates[index] -= minimum
        return Axis(minimum, span, size, coordinates)
    }
}
