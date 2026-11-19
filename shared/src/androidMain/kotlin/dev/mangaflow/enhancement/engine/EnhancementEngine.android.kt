package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual fun createEnhancementEngine(context: PlatformContext): EnhancementEngine = AndroidEnhancementEngine(context)

private class AndroidEnhancementEngine(context: Context) : EnhancementEngine {
    private val context = context.applicationContext
    private val root = File(this.context.noBackupFilesDir, "enhancement")
    private val models = ImportedEngineModels(File(root, "models"), EnhancementEngineIds.ANDROID_ONNX)
    private val runtimes = PanelRuntimeStore.get(context)
    private val nativeGate = Mutex()
    private var session: OrtSession? = null
    private var sessionIdentity: String? = null

    override suspend fun inspect(): EngineInventory = withContext(Dispatchers.IO) {
        val installed = models.list()
        val runtime = runtimes.states.value[PanelRuntimeId.ONNX]
        val readiness = when {
            runtime?.installed != true -> EngineReadiness.NOT_INSTALLED
            installed.isEmpty() -> EngineReadiness.NEEDS_MODELS
            else -> EngineReadiness.READY_FOR_TRIAL
        }
        EngineInventory(listOf(
            EngineDescriptor(EnhancementEngineIds.LOCAL_DETAIL, "轻量线条处理", EnhancementEngineIds.LOCAL_DETAIL_VERSION,
                "MangaFlow", "https://github.com/wozhenbang2004/MangaFlow", EngineReadiness.BUILT_IN,
                "1× 保守去噪，保留文字与细线；非 AI 超分", setOf(1),
                setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON)),
            EngineDescriptor(EnhancementEngineIds.ANDROID_ONNX, "ONNX 漫画分块增强", "1.31.1", "MIT；模型遵循各自许可",
                "https://github.com/microsoft/onnxruntime", readiness,
                when (readiness) {
                    EngineReadiness.NOT_INSTALLED -> "先准备签名 ONNX 运行库，再导入适合此内容的 FP32 NCHW 图像模型"
                    EngineReadiness.NEEDS_MODELS -> "运行库已准备；导入 1×/2×/4× 模型后在真实页面试用"
                    else -> "CPU 原生推理与重叠分块；未声明 Vulkan 或手机性能验证"
                }, setOf(1, 2, 4), setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON),
                canInstall = runtime?.installed != true && runtime?.downloading != true),
            EngineDescriptor(EnhancementEngineIds.ANDROID_RIFE, "RIFE 运动补帧", "待 Media3 原生适配", "MIT · rife-ncnn-vulkan",
                "https://github.com/nihui/rife-ncnn-vulkan", EngineReadiness.UNSUPPORTED,
                "当前没有经过此设备验证的 Media3/Vulkan 补帧链路；保持原始帧率", emptySet(),
                setOf(EngineContent.VIDEO), supportsInterpolation = true, realtime = true),
            EngineDescriptor(EnhancementEngineIds.VIDEOJANAI, "番剧高质量离线处理", "Windows 组件", "GPL-3.0",
                "https://github.com/the-database/VideoJaNai", EngineReadiness.UNSUPPORTED,
                "当前 Android 未集成视频解码、AI 推理与硬件编码离线链路；可继续实时 Anime4K 或原画",
                setOf(1, 2, 4), setOf(EngineContent.VIDEO), supportsInterpolation = true),
        ) + anime4kEngineDescriptors(), installed, installed.sumOf { it.bytes }, runtime?.bytes ?: 0)
    }

    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory {
        require(engineId == EnhancementEngineIds.ANDROID_ONNX) { "当前设备没有这一引擎的可安装组件" }
        progress(EngineProgress(EngineStage.DOWNLOAD, detail = "已交给现有组件下载队列，遵守系统后台执行限制"))
        runtimes.download(PanelRuntimeId.ONNX)
        return inspect()
    }

    override suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourcePath)
        if (uri.scheme == "content") {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "model.onnx"
            require(name.endsWith(".onnx", ignoreCase = true)) { "请选择 ONNX 模型文件" }
            val incoming = File(root, "imports").also { check(it.isDirectory || it.mkdirs()) }
            val temporary = File(incoming, "${UUID.randomUUID()}.onnx")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    Files.newOutputStream(temporary.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
                        val bytes = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            require(total <= 2L * 1024 * 1024 * 1024) { "模型大于 2 GiB" }
                            output.write(bytes, 0, count)
                        }
                    }
                } ?: error("无法读取所选模型文件")
                models.import(temporary, nativeScale, content, name.substringBeforeLast('.'))
            } finally { temporary.delete() }
        } else {
            require(uri.scheme == null || uri.scheme == "file") { "只接受用户选择的本机模型文件" }
            models.import(File(if (uri.scheme == "file") checkNotNull(uri.path) else sourcePath), nativeScale, content)
        }
    }

    override suspend fun describeImage(sourcePath: String): EngineImageInfo = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "页面格式无法读取" }
        val sampleOptions = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 128)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val sample = BitmapFactory.decodeFile(source.path, sampleOptions) ?: error("页面色彩无法读取")
        try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            EngineImageInfo(options.outWidth, options.outHeight, classifyEngineImage(options.outWidth, options.outHeight, pixels))
        } finally { sample.recycle() }
    }

    override suspend fun processImage(request: EngineImageRequest, progress: (EngineProgress) -> Unit): EngineOutput = withContext(Dispatchers.Default) {
        nativeGate.withLock {
            val source = File(request.sourcePath)
            val destination = File(request.destinationPath)
            newEngineOutput(source, destination)
            val output = File(destination.parentFile, "render-${UUID.randomUUID()}.png")
            val sourceHash = engineSha256(source)
            val started = System.nanoTime()
            // A transformed result must use exactly the original display geometry. Unsupported metadata keeps the original.
            val orientation = runCatching { ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            require(orientation in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)) {
                "此页面带旋转或镜像信息，当前增强保留原图以避免阅读位置改变"
            }
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(source.path, false) ?: error("此格式暂不支持安全分块；保留原图")
            try {
                if (request.engineId == EnhancementEngineIds.LOCAL_DETAIL) {
                    require(request.scale == 1) { "轻量处理只支持 1×" }
                    renderLocal(decoder, output, request.maximumOutputBytes, progress)
                } else {
                    require(request.engineId == EnhancementEngineIds.ANDROID_ONNX)
                    val model = request.model ?: error("尚未选择适合当前页面的模型")
                    require(request.content in model.contents && model.engineId == request.engineId) { "模型不适用于此页面类别" }
                    require(model.nativeScale == request.scale) { "所选倍率与模型原生倍率不一致" }
                    check(engineSha256(File(model.path)) == model.sha256) { "模型已改变，请重新选择" }
                    runtimes.ensureLoaded(PanelRuntimeId.ONNX)
                    progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "准备真实 ONNX CPU 模型"))
                    val live = modelSession(model)
                    renderOnnx(decoder, output, request, live, progress)
                }
                currentCoroutineContext().ensureActive()
                check(engineSha256(source) == sourceHash) { "原始页面已变化，旧增强结果不再使用" }
                request.model?.let { check(engineSha256(File(it.path)) == it.sha256) { "处理期间模型发生变化，结果已丢弃" } }
                progress(EngineProgress(EngineStage.VALIDATE, detail = "校验完整 PNG 与页面尺寸"))
                val size = verifyEnginePng(output)
                check(size.first.toLong() == decoder.width.toLong() * request.scale && size.second.toLong() == decoder.height.toLong() * request.scale)
                val local = request.engineId == EnhancementEngineIds.LOCAL_DETAIL
                val result = EngineOutput(destination.canonicalPath, size.first, size.second, engineSha256(output), output.length(),
                    request.engineId, if (local) EnhancementEngineIds.LOCAL_DETAIL_VERSION else "1.31.1",
                    if (local) EnhancementEngineIds.LOCAL_DETAIL_DIGEST else checkNotNull(request.model).sha256,
                    (System.nanoTime() - started) / 1_000_000)
                currentCoroutineContext().ensureActive()
                moveNewEngineFile(output, destination)
                progress(EngineProgress(EngineStage.COMPLETE, 1, 1))
                result
            } catch (memory: OutOfMemoryError) {
                session?.close(); session = null; sessionIdentity = null; output.delete()
                throw IllegalStateException("此模型超过当前内存预算，已保留原图；可减小分块或在 Windows 提前处理", memory)
            } catch (link: LinkageError) {
                output.delete(); throw IllegalStateException("当前设备的原生推理组件不可用，已保留原图", link)
            } catch (failure: Throwable) { output.delete(); throw failure }
            finally { decoder.recycle() }
        }
    }

    override suspend fun processVideo(request: EngineVideoRequest, progress: (EngineProgress) -> Unit): EngineOutput =
        throw UnsupportedOperationException("当前 Android 没有已适配并验证的离线视频 AI 编码链路，原视频保持可用")

    override suspend fun close() = withContext(NonCancellable + Dispatchers.Default) {
        nativeGate.withLock { session?.close(); session = null; sessionIdentity = null }
    }

    private fun modelSession(model: EngineModel): OrtSession {
        if (sessionIdentity == model.sha256) session?.let { return it }
        session?.close(); session = null; sessionIdentity = null
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(model.path, options)
        }
        try {
            require(created.inputNames.size == 1 && created.outputNames.size == 1) { "当前支持单输入、单输出的图像模型" }
            val input = created.inputInfo.values.single().info as? TensorInfo ?: error("模型输入不是图像张量")
            require(input.type == OnnxJavaType.FLOAT && input.shape.size == 4 && input.shape[0] in longArrayOf(-1, 1) && input.shape[1] in longArrayOf(1, 3)) {
                "当前手机路径支持 FP32 NCHW 灰度/RGB 模型；未自动转换不兼容权重"
            }
            session = created; sessionIdentity = model.sha256
            return created
        } catch (failure: Throwable) { created.close(); throw failure }
    }

    private suspend fun renderLocal(decoder: BitmapRegionDecoder, output: File, budget: Long, progress: (EngineProgress) -> Unit) {
        val width = decoder.width; val height = decoder.height
        require(width.toLong() * 16 <= budget) { "当前页面宽度超过处理内存预算" }
        val step = minOf(64, maxOf(1, (budget / (width.toLong() * 16)).toInt()))
        EnginePngWriter(output, width, height).use { writer ->
            var top = 0
            while (top < height) {
                currentCoroutineContext().ensureActive()
                val bottom = minOf(height, top + step); val from = maxOf(0, top - 1); val to = minOf(height, bottom + 1)
                val bitmap = decoder.decodeRegion(Rect(0, from, width, to), BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                })
                    ?: error("无法解码页面分块")
                try {
                    val pixels = IntArray(width * bitmap.height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
                    val row = IntArray(width)
                    for (y in top until bottom) {
                        for (x in 0 until width) row[x] = localDetailPixel(pixels, width, bitmap.height, x, y - from)
                        writer.row(row)
                    }
                } finally { bitmap.recycle() }
                top = bottom
                progress(EngineProgress(EngineStage.PROCESS, top.toLong(), height.toLong(), detail = "1× 保守去噪"))
            }
            writer.finish()
        }
    }

    private suspend fun renderOnnx(decoder: BitmapRegionDecoder, output: File, request: EngineImageRequest,
        session: OrtSession, progress: (EngineProgress) -> Unit) {
        val info = session.inputInfo.values.single().info as TensorInfo
        val tileW = info.shape[3].takeIf { it > 0 }?.toInt() ?: request.tileSize.coerceIn(64, 512)
        val tileH = info.shape[2].takeIf { it > 0 }?.toInt() ?: request.tileSize.coerceIn(64, 512)
        require(tileW in 32..1024 && tileH in 32..1024) { "当前模型固定分块过大，无法满足手机预算" }
        val overlap = maxOf(16, request.overlap).coerceAtMost(minOf(tileW, tileH) / 4)
        val stepX = tileW - 2 * overlap; val stepY = tileH - 2 * overlap
        val blend = minOf(8, overlap / 2)
        val scale = request.scale; val width = decoder.width; val height = decoder.height
        val outWidth = Math.multiplyExact(width, scale); val outHeight = Math.multiplyExact(height, scale)
        val channels = info.shape[1].toInt()
        val tileOutputFloats = tileW.toLong() * tileH * scale * scale * 3
        val stripFloats = outWidth.toLong() * (stepY + 2 * blend) * scale * 4
        require((tileOutputFloats * 2 + stripFloats * 2 + tileW.toLong() * tileH * channels) * 4 <= request.maximumOutputBytes) {
            "分块与输出行缓冲超过当前准入预算，请减小分块"
        }
        val environment = OrtEnvironment.getEnvironment()
        val data = ByteBuffer.allocateDirect(tileW * tileH * channels * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var previous = FloatArray(0); var previousTop = 0; var previousRows = 0
        var completed = 0L
        val total = ((width.toLong() + stepX - 1) / stepX) * ((height.toLong() + stepY - 1) / stepY)
        EnginePngWriter(output, outWidth, outHeight).use { writer ->
            var top = 0
            while (top < height) {
                currentCoroutineContext().ensureActive()
                val bottom = minOf(height, top + stepY)
                val bandTop = maxOf(0, top - blend) * scale; val bandBottom = minOf(height, bottom + blend) * scale
                val bandRows = bandBottom - bandTop
                val accumulated = FloatArray(Math.multiplyExact(Math.multiplyExact(outWidth, bandRows), 4))
                if (previousRows > 0) previous.copyInto(accumulated, (previousTop - bandTop) * outWidth * 4)
                var left = 0
                while (left < width) {
                    currentCoroutineContext().ensureActive()
                    val right = minOf(width, left + stepX)
                    val originX = (left - overlap).coerceIn(0, maxOf(0, width - tileW))
                    val originY = (top - overlap).coerceIn(0, maxOf(0, height - tileH))
                    val regionW = minOf(width, tileW); val regionH = minOf(height, tileH)
                    val bitmap = decoder.decodeRegion(Rect(originX, originY, originX + regionW, originY + regionH),
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                        }) ?: error("无法解码模型分块")
                    val inputPixels = IntArray(regionW * regionH)
                    try { bitmap.getPixels(inputPixels, 0, regionW, 0, 0, regionW, regionH) } finally { bitmap.recycle() }
                    require(inputPixels.all { it ushr 24 == 255 }) { "当前 AI 模型路径不改写透明像素；保留原图" }
                    data.clear()
                    for (channel in 0 until channels) for (y in 0 until tileH) for (x in 0 until tileW) {
                        val pixel = inputPixels[reflect(y, regionH) * regionW + reflect(x, regionW)]
                        val value = if (channels == 1) ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) / 256f
                            else (pixel ushr (16 - channel * 8) and 255).toFloat()
                        data.put(value / 255f)
                    }
                    data.rewind()
                    OnnxTensor.createTensor(environment, data, longArrayOf(1, channels.toLong(), tileH.toLong(), tileW.toLong())).use { tensor ->
                        session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                            val image = result[0] as? OnnxTensor ?: error("模型未输出图像张量")
                            val shape = image.info.shape
                            require(image.info.type == OnnxJavaType.FLOAT && shape.size == 4 && shape[0] == 1L && shape[1] in longArrayOf(1, 3) &&
                                shape[2] == tileH.toLong() * scale && shape[3] == tileW.toLong() * scale) { "模型实际输出与声明倍率或格式不符" }
                            val values = image.floatBuffer
                            val outputChannels = shape[1].toInt(); val tw = tileW * scale; val plane = tw * tileH * scale
                            val drawLeft = maxOf(0, left - blend) * scale; val drawRight = minOf(width, right + blend) * scale
                            for (y in bandTop until bandBottom) for (x in drawLeft until drawRight) {
                                val px = x.toFloat() / scale; val py = y.toFloat() / scale
                                val weight = blendWeight(px, left, right, width, blend) * blendWeight(py, top, bottom, height, blend)
                                val index = (y - bandTop) * outWidth * 4 + x * 4
                                val sample = (y - originY * scale) * tw + x - originX * scale
                                for (channel in 0..2) {
                                    val value = values.get((if (outputChannels == 1) 0 else channel) * plane + sample)
                                    require(value.isFinite()) { "模型输出含非有限像素" }
                                    accumulated[index + channel] += value.coerceIn(0f, 1f) * weight
                                }
                                accumulated[index + 3] += weight
                            }
                        }
                    }
                    completed++
                    progress(EngineProgress(EngineStage.PROCESS, completed, total, detail = "重叠分块 · CPU ONNX"))
                    left = right
                }
                val flushBottom = if (bottom == height) bandBottom else (bottom - blend) * scale
                val row = IntArray(outWidth)
                for (y in bandTop until flushBottom) {
                    for (x in 0 until outWidth) {
                        val p = (y - bandTop) * outWidth * 4 + x * 4; val weight = accumulated[p + 3]
                        check(weight > 0f) { "分块没有完整覆盖页面" }
                        fun channel(offset: Int) = (accumulated[p + offset] / weight * 255f).roundToInt().coerceIn(0, 255)
                        row[x] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    }
                    writer.row(row)
                }
                previousTop = flushBottom; previousRows = bandBottom - flushBottom
                previous = if (previousRows == 0) FloatArray(0) else accumulated.copyOfRange((flushBottom - bandTop) * outWidth * 4, accumulated.size)
                top = bottom
            }
            writer.finish()
        }
    }

    private fun reflect(value: Int, length: Int): Int {
        if (length <= 1) return 0
        val cycle = (length - 1) * 2; val at = value % cycle
        return if (at < length) at else cycle - at
    }

    private fun blendWeight(value: Float, start: Int, end: Int, limit: Int, band: Int): Float = when {
        start > 0 && value < start + band -> ((value - (start - band) + 0.5f) / (2 * band)).coerceIn(0.001f, 1f)
        end < limit && value > end - band -> (((end + band) - value - 0.5f) / (2 * band)).coerceIn(0.001f, 1f)
        else -> 1f
    }
}
