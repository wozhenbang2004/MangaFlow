package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
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
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
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
    private val videoModels = ImportedEngineModels(File(root, "video-models"), EnhancementEngineIds.ANDROID_VIDEO_ONNX)
    private val runtimes = PanelRuntimeStore.get(context)
    private val nativeGate = Mutex()
    private var session: OrtSession? = null
    private var sessionIdentity: String? = null

    override suspend fun inspect(): EngineInventory = withContext(Dispatchers.IO) {
        val installed = models.list()
        val installedVideo = videoModels.list()
        val runtime = runtimes.states.value[PanelRuntimeId.ONNX]
        fun modelReadiness(models: List<EngineModel>) = when {
            runtime?.installed != true -> EngineReadiness.NOT_INSTALLED
            models.isEmpty() -> EngineReadiness.NEEDS_MODELS
            else -> EngineReadiness.READY_FOR_TRIAL
        }
        val readiness = modelReadiness(installed)
        val videoReadiness = modelReadiness(installedVideo)
        val canInstall = runtime?.installed != true && runtime?.downloading != true
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
                canInstall = canInstall),
            EngineDescriptor(EnhancementEngineIds.ANDROID_RIFE, "RIFE 运动补帧", "待 Media3 原生适配", "MIT · rife-ncnn-vulkan",
                "https://github.com/nihui/rife-ncnn-vulkan", EngineReadiness.UNSUPPORTED,
                "当前没有经过此设备验证的 Media3/Vulkan 补帧链路；保持原始帧率", emptySet(),
                setOf(EngineContent.VIDEO), supportsInterpolation = true, realtime = true),
            EngineDescriptor(EnhancementEngineIds.ANDROID_VIDEO_ONNX, "番剧 ONNX 离线增强", ANDROID_VIDEO_ENGINE_VERSION,
                "Apache-2.0 / MIT；模型遵循各自许可", "https://github.com/androidx/media/tree/1.11.1/libraries/transformer",
                videoReadiness, when (videoReadiness) {
                    EngineReadiness.NOT_INSTALLED -> "先准备签名 ONNX 运行库，再导入适用的 FP32 NCHW RGB 视频模型"
                    EngineReadiness.NEEDS_MODELS -> "导入 1×/2×/4× FP32 NCHW RGB 模型后，可增强已下载的 SDR MP4"
                    else -> "使用 Android 编解码器和 CPU ONNX，保留逐帧时间轴和 AAC 音轨；速度与设备兼容性待实际试用"
                }, setOf(1, 2, 4), setOf(EngineContent.VIDEO), canInstall = canInstall,
                videoOutput = EngineVideoContainer.MP4),
        ) + anime4kEngineDescriptors(), installed + installedVideo, (installed + installedVideo).sumOf { it.bytes }, runtime?.bytes ?: 0)
    }

    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory {
        require(engineId in setOf(EnhancementEngineIds.ANDROID_ONNX, EnhancementEngineIds.ANDROID_VIDEO_ONNX)) {
            "当前设备没有这一引擎的可安装组件"
        }
        progress(EngineProgress(EngineStage.DOWNLOAD, detail = "已交给现有组件下载队列，遵守系统后台执行限制"))
        runtimes.download(PanelRuntimeId.ONNX)
        return inspect()
    }

    override suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel = withContext(Dispatchers.IO) {
        val destination = if (content == EngineContent.VIDEO) videoModels else models
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
                destination.import(temporary, nativeScale, content, name.substringBeforeLast('.'))
            } finally { temporary.delete() }
        } else {
            require(uri.scheme == null || uri.scheme == "file") { "只接受用户选择的本机模型文件" }
            destination.import(File(if (uri.scheme == "file") checkNotNull(uri.path) else sourcePath), nativeScale, content)
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
        withContext(Dispatchers.IO) {
            nativeGate.withLock {
                val model = requireNotNull(request.model) { "请选择适用的番剧 ONNX 模型" }
                require(model.engineId == EnhancementEngineIds.ANDROID_VIDEO_ONNX && EngineContent.VIDEO in model.contents &&
                    model.nativeScale == request.scale) { "视频模型类型或倍率不匹配" }
                require(request.targetFps == null && request.frameMultiplier == null && request.segmentSeconds == 0) {
                    "当前 Android 离线引擎支持整集画质处理，使用原始帧率"
                }
                check(engineSha256(File(model.path)) == model.sha256) { "模型已改变，请重新选择" }
                try {
                    runtimes.ensureLoaded(PanelRuntimeId.ONNX)
                    progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "准备真实 ONNX CPU 模型"))
                    prepareAndroidVideo(context, request, model, modelSession(model), progress)
                } catch (memory: OutOfMemoryError) {
                    session?.close(); session = null; sessionIdentity = null
                    throw IllegalStateException("当前模型或输出尺寸超出手机内存预算，原视频保持可用", memory)
                } catch (link: LinkageError) {
                    throw IllegalStateException("设备的原生推理组件不可用，原视频保持可用", link)
                }
            }
        }

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
        val coroutine = currentCoroutineContext()
        val renderer = AndroidOnnxTiles(session, decoder.width, decoder.height,
            request.scale, request.tileSize, request.overlap, request.maximumOutputBytes)
        val decoderOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        EnginePngWriter(output, renderer.outWidth, renderer.outHeight).use { writer ->
            renderer.render(coroutine::ensureActive, { left, top, width, height, pixels ->
                val bitmap = decoder.decodeRegion(Rect(left, top, left + width, top + height), decoderOptions)
                    ?: error("无法解码模型分块")
                try {
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                } finally { bitmap.recycle() }
            }, { _, row -> writer.row(row) }, { completed, total ->
                progress(EngineProgress(EngineStage.PROCESS, completed, total, detail = "重叠分块 · CPU ONNX"))
            })
            writer.finish()
        }
    }
}
