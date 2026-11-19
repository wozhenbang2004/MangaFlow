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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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

    fun descriptor(model: EngineModel?) = EngineDescriptor(
        EnhancementEngineIds.ANDROID_CUGAN, "Real-CUGAN · Vulkan 2×", ENGINE_VERSION, "MIT / BSD-3-Clause",
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
            else -> "彩色漫画和条漫可选用原版 NOSE 2× 无降噪模型，实际设备兼容性在处理时确认"
        }, setOf(2), CONTENTS, canInstall = available && !runtime.pendingRemoval && (!runtime.installed || model == null),
        downloadBytes = MODEL_BYTES.takeIf { runtime.installed && model == null },
    )

    fun model(): EngineModel? {
        if (!available || PARTS.any { !it.matches(File(installed, it.name)) }) return null
        return EngineModel(ENGINE_MODEL_ID, EnhancementEngineIds.ANDROID_CUGAN, "Real-CUGAN NOSE · 2× 无降噪",
            installed.canonicalPath, MODEL_IDENTITY, 2, CONTENTS, "MIT", UPSTREAM_COMMIT, MODEL_BYTES,
            inputType = "NCNN_RGB_VKMAT")
    }

    suspend fun install(progress: (EngineProgress) -> Unit) = installs.withLock {
        check(available) { "当前设备无法加载 Vulkan 图像增强组件" }
        if (!runtime.installed) {
            runtimes.download(PanelRuntimeId.CUGAN)
            val status = runtimes.states.first { options ->
                val current = options.getValue(PanelRuntimeId.CUGAN)
                if (current.downloading) progress(EngineProgress(EngineStage.DOWNLOAD, current.bytes,
                    current.totalBytes.takeIf { it > 0L }, detail = "正在下载签名 ncnn Vulkan 组件", bytes = current.bytes))
                !current.downloading
            }.getValue(PanelRuntimeId.CUGAN)
            check(status.installed) { status.error ?: "ncnn Vulkan 组件未能安装" }
        }
        check(!runtime.pendingRemoval) { "ncnn Vulkan 组件将在重启后移除" }
        if (model() != null) return@withLock
        check(modelRoot.isDirectory || modelRoot.mkdirs()) { "无法创建 Real-CUGAN 目录" }
        val temporary = File(modelRoot, "download-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            var count = 0L
            for (part in PARTS) {
                val output = File(temporary, part.name)
                val connection = URL("$MODEL_URL/${part.name}").openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 40_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    check(connection.responseCode == 200) { "Real-CUGAN 下载 HTTP ${connection.responseCode}" }
                    var partBytes = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(output).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                partBytes += read
                                check(partBytes <= part.bytes) { "Real-CUGAN 文件长度异常" }
                                stream.write(buffer, 0, read)
                                progress(EngineProgress(EngineStage.DOWNLOAD, count + partBytes, MODEL_BYTES, bytes = count + partBytes))
                            }
                            stream.fd.sync()
                        }
                    }
                    progress(EngineProgress(EngineStage.VERIFY, detail = "核对官方文件大小与 SHA-256"))
                    check(part.matches(output)) { "Real-CUGAN 文件摘要不符，已拒绝安装" }
                    count += partBytes
                } finally {
                    connection.disconnect()
                }
            }
            currentCoroutineContext().ensureActive()
            // Both members are checked before their version directory becomes visible.
            if (installed.exists()) check(installed.deleteRecursively()) { "无法移除损坏的旧模型" }
            Files.move(temporary.toPath(), installed.toPath(), ATOMIC_MOVE)
            check(model() != null) { "Real-CUGAN 安装尚未确认" }
        } finally {
            temporary.deleteRecursively()
        }
    }

    suspend fun render(decoder: BitmapRegionDecoder, output: File, request: EngineImageRequest,
        progress: (EngineProgress) -> Unit) {
        val model = checkNotNull(model()) { "Real-CUGAN 模型尚未通过本机校验" }
        require(request.model == model && request.scale == 2 && request.content in CONTENTS &&
            request.tileSize == TILE_SIZE && request.overlap == PADDING) { "Real-CUGAN 模型、范围、倍率或分块身份不匹配" }
        val width = decoder.width
        val height = decoder.height
        val outputWidth = Math.multiplyExact(width, 2)
        val outputHeight = Math.multiplyExact(height, 2)
        val bandHeight = minOf(height, TILE_SIZE) * 2
        val bandBytes = outputWidth.toLong() * bandHeight * Int.SIZE_BYTES
        require(bandBytes + WORKING_BYTES <= request.maximumOutputBytes) { "Real-CUGAN 行缓冲超过当前处理预算" }
        progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "加载 ncnn Vulkan 和 Real-CUGAN NOSE"))
        runtimes.ensureLoaded(PanelRuntimeId.CUGAN)
        val handle = AndroidCuganVulkanJni.open(File(installed, PARTS[0].name).path, File(installed, PARTS[1].name).path)
        check(handle != 0L) { "当前 GPU 或 ncnn 驱动无法运行这个完整 Vulkan 模型" }
        try {
            val band = IntArray(Math.multiplyExact(outputWidth, bandHeight))
            val row = IntArray(outputWidth)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            var processed = 0L
            val total = ((width.toLong() + TILE_SIZE - 1) / TILE_SIZE) * ((height.toLong() + TILE_SIZE - 1) / TILE_SIZE)
            EnginePngWriter(output, outputWidth, outputHeight).use { writer ->
                for (top in 0 until height step TILE_SIZE) {
                    val tileHeight = minOf(TILE_SIZE, height - top)
                    val ys = reflectedCoordinates(top, tileHeight, height)
                    for (left in 0 until width step TILE_SIZE) {
                        currentCoroutineContext().ensureActive()
                        val tileWidth = minOf(TILE_SIZE, width - left)
                        val xs = reflectedCoordinates(left, tileWidth, width)
                        val tile = region(decoder, xs, ys, options)
                        val resultWidth = tileWidth * 2
                        val result = IntArray(resultWidth * tileHeight * 2)
                        val status = AndroidCuganVulkanJni.process(handle, tile, result, tileWidth, tileHeight)
                        check(status == 0) { when (status) {
                            -2 -> "Vulkan 分块资源不足"
                            -3 -> "Vulkan 模型提交或执行未完成"
                            -4 -> "Real-CUGAN 输出像素或尺寸未通过校验"
                            else -> "Real-CUGAN 输入传递未完成"
                        } }
                        currentCoroutineContext().ensureActive()
                        for (y in 0 until tileHeight * 2) {
                            result.copyInto(band, y * outputWidth + left * 2, y * resultWidth, (y + 1) * resultWidth)
                        }
                        progress(EngineProgress(EngineStage.PROCESS, ++processed, total, detail = "Real-CUGAN · Vulkan 已完成分块"))
                    }
                    for (y in 0 until tileHeight * 2) {
                        band.copyInto(row, 0, y * outputWidth, (y + 1) * outputWidth)
                        writer.row(row)
                    }
                }
                writer.finish()
            }
            check(model() == model) { "处理期间 Real-CUGAN 模型发生变化" }
        } finally {
            AndroidCuganVulkanJni.close(handle)
        }
    }

    private fun region(decoder: BitmapRegionDecoder, xs: IntArray, ys: IntArray, options: BitmapFactory.Options): IntArray {
        val left = xs.min()
        val top = ys.min()
        val right = xs.max() + 1
        val bottom = ys.max() + 1
        val width = right - left
        val height = bottom - top
        val decoded = decoder.decodeRegion(Rect(left, top, right, bottom), options) ?: error("无法解码 Real-CUGAN 分块")
        val pixels = IntArray(width * height)
        try {
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally { decoded.recycle() }
        require(pixels.all { it ushr 24 == 255 }) { "Real-CUGAN 路径保留含透明像素的原图" }
        return IntArray(xs.size * ys.size) { index ->
            pixels[(ys[index / xs.size] - top) * width + xs[index % xs.size] - left]
        }
    }

    /** Same symmetric edge and even tile dimensions as the upstream 2× preprocessor. */
    private fun reflectedCoordinates(start: Int, size: Int, limit: Int) =
        IntArray(size + size % 2 + PADDING * 2) { index ->
            if (limit <= 1) 0 else {
                val cycle = (limit - 1) * 2
                val position = Math.floorMod(start - PADDING + index, cycle)
                if (position < limit) position else cycle - position
            }
        }

    private data class ModelPart(val name: String, val bytes: Long, val sha256: String) {
        fun matches(file: File) = file.isFile && file.length() == bytes && engineSha256(file) == sha256
    }

    companion object {
        const val ENGINE_VERSION = "ncnn-20261020 / Real-CUGAN NOSE 395302c / 1"
        const val TILE_SIZE = EnhancementEngineIds.ANDROID_CUGAN_TILE_SIZE
        const val PADDING = EnhancementEngineIds.ANDROID_CUGAN_PADDING
        private const val UPSTREAM_COMMIT = "395302c5c70f1bff604c974e92e0a87e45c9f9ee"
        private const val SOURCE_URL = "https://github.com/nihui/realcugan-ncnn-vulkan/tree/$UPSTREAM_COMMIT"
        private const val MODEL_URL = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/$UPSTREAM_COMMIT/models/models-nose"
        private const val ENGINE_MODEL_ID = "real-cugan:nose-2x-395302c"
        private const val MODEL_BYTES = 2_554_717L
        private const val WORKING_BYTES = 8L * 1024 * 1024
        private val CONTENTS = setOf(EngineContent.COLOR, EngineContent.WEBTOON)
        private val PARTS = listOf(
            ModelPart("up2x-no-denoise.param", 3_309L, "c370b70321d45301b9c682121130ad167b3dd1bc8e122c1d77c7ac44f1943708"),
            ModelPart("up2x-no-denoise.bin", 2_551_408L, "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a"),
        )
        private val MODEL_IDENTITY = EnhancedAssetIdentity.digest(PARTS.joinToString("|") { it.sha256 })
    }
}

internal object AndroidCuganVulkanJni {
    external fun open(params: String, weights: String): Long
    external fun process(handle: Long, input: IntArray, output: IntArray, width: Int, height: Int): Int
    external fun close(handle: Long)
}
