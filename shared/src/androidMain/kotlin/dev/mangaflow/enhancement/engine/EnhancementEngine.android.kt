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
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual fun createEnhancementEngine(context: PlatformContext): EnhancementEngine = AndroidEnhancementEngine(context)

private class AndroidEnhancementEngine(context: Context) : EnhancementEngine {
    private val context = context.applicationContext
    private val root = File(this.context.noBackupFilesDir, "enhancement")
    private val models = ImportedEngineModels(File(root, "models"), EnhancementEngineIds.ANDROID_ONNX)
    private val videoModels = ImportedEngineModels(File(root, "video-models"), EnhancementEngineIds.ANDROID_VIDEO_ONNX)
    private val rifeModels = AndroidRifeModels(File(root, "rife-models"))
    private val rifeVulkan = AndroidRifeVulkanModels(this.context)
    private val cugan = AndroidCuganVulkan(this.context, root)
    private val anime4kVariants = dev.mangaflow.anime.player.Anime4KOptionalModels(this.context)
    private val runtimes = PanelRuntimeStore.get(context)
    private val nativeGate = Mutex()
    private var session: OrtSession? = null
    private var sessionIdentity: String? = null

    override suspend fun inspect(): EngineInventory = withContext(Dispatchers.IO) {
        val installed = models.list()
        val installedVideo = videoModels.list()
        val installedVariants = anime4kVariants.installed()
        val frameModels = rifeModels.installed()
        val liveRife = rifeVulkan.model()
        val cuganModel = cugan.model()
        val cuganVideoModel = cugan.videoModel(cuganModel)
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
            cugan.descriptor(cuganModel),
            EngineDescriptor(EnhancementEngineIds.ANDROID_RIFE, "RIFE 运动补帧", ANDROID_VIDEO_ENGINE_VERSION, "上游 RIFE 与导出模型各自许可",
                "https://github.com/AmusementClub/vs-mlrt/releases/tag/external-models", modelReadiness(frameModels),
                "离线 CPU 推理。导入官方包内 rife_v2 的 4.25 Lite、4.22 或 4.26 FP32。精细、极致各对对应基座做双向两次推理。",
                setOf(1), setOf(EngineContent.VIDEO), canInstall = canInstall, supportsInterpolation = true),
            rifeVulkan.descriptor(liveRife),
            EngineDescriptor(EnhancementEngineIds.ANDROID_VIDEO_ONNX, "番剧 ONNX 离线增强", ANDROID_VIDEO_ENGINE_VERSION,
                "Apache-2.0 / MIT；模型遵循各自许可", "https://github.com/androidx/media/tree/1.11.1/libraries/transformer",
                if (frameModels.isNotEmpty()) modelReadiness(installedVideo + frameModels) else videoReadiness, when (videoReadiness) {
                    EngineReadiness.NOT_INSTALLED -> "先准备签名 ONNX 运行库，再导入适用的 FP32 NCHW RGB 视频模型"
                    EngineReadiness.NEEDS_MODELS -> "导入 1×/2×/4× FP32 NCHW RGB 模型后，可增强已下载的 SDR MP4"
                    else -> "使用 Android 编解码器和 CPU ONNX，保留逐帧时间轴和 AAC 音轨；速度与设备兼容性待实际试用"
                }, setOf(1, 2, 4), setOf(EngineContent.VIDEO), canInstall = canInstall, supportsInterpolation = true,
                videoOutput = EngineVideoContainer.MP4),
            cugan.descriptor(cuganVideoModel, video = true),
            EngineDescriptor(EnhancementEngineIds.ANDROID_VIDEO_REALTIME, "NNAPI 实时模型试用", ANDROID_VIDEO_ENGINE_VERSION,
                "MIT；模型遵循各自许可", "https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html",
                if (Build.VERSION.SDK_INT >= 29) videoReadiness else EngineReadiness.UNSUPPORTED,
                "Android 10+ 可尝试仅硬件执行已导入的 RGB 模型。实际图与设备支持、处理耗时和回退状态在播放中显示。",
                setOf(1, 2, 4), setOf(EngineContent.VIDEO), realtime = true),
        ) + anime4kEngineDescriptors() + anime4kVariants.descriptors(),
            installed + installedVideo + installedVariants + listOfNotNull(cuganModel, cuganVideoModel),
            (installed + installedVideo + installedVariants + frameModels + listOfNotNull(cuganModel, liveRife)).sumOf { it.bytes },
            (runtime?.bytes ?: 0) + (runtimes.states.value[PanelRuntimeId.CUGAN]?.bytes ?: 0), frameModels + listOfNotNull(liveRife))
    }

    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory {
        if (dev.mangaflow.anime.player.Anime4KOptionalModels.supports(engineId)) {
            withContext(Dispatchers.IO) { anime4kVariants.install(engineId, progress) }
            return inspect()
        }
        if (engineId == EnhancementEngineIds.ANDROID_RIFE_VULKAN) {
            withContext(Dispatchers.IO) { rifeVulkan.install(progress) }
            return inspect()
        }
        if (engineId == EnhancementEngineIds.ANDROID_CUGAN || engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO) {
            withContext(Dispatchers.IO) { cugan.install(progress) }
            return inspect()
        }
        require(engineId in setOf(EnhancementEngineIds.ANDROID_ONNX, EnhancementEngineIds.ANDROID_VIDEO_ONNX,
            EnhancementEngineIds.ANDROID_RIFE)) {
            "当前设备没有这一引擎的可安装组件"
        }
        progress(EngineProgress(EngineStage.DOWNLOAD, detail = "已交给现有组件下载队列，遵守系统后台执行限制"))
        runtimes.download(PanelRuntimeId.ONNX)
        return inspect()
    }

    override suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel = withContext(Dispatchers.IO) {
        val destination = if (content == EngineContent.VIDEO) videoModels else models
        suspend fun importFile(file: File, name: String): EngineModel {
            if (content == EngineContent.VIDEO) rifeModels.tryImport(file, name)?.let { return it }
            return destination.import(file, nativeScale, content, name.substringBeforeLast('.'))
        }
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
                importFile(temporary, name)
            } finally { temporary.delete() }
        } else {
            require(uri.scheme == null || uri.scheme == "file") { "只接受用户选择的本机模型文件" }
            val file = File(if (uri.scheme == "file") checkNotNull(uri.path) else sourcePath)
            importFile(file, file.name)
        }
    }


    /** HTTPS-only, pinned digest download. Failed transfers never publish model metadata. */
    override suspend fun downloadModel(offer: EnhancementModelOffer,
        progress: (EngineProgress) -> Unit): EngineModel = withContext(Dispatchers.IO) {
        require(offer.engineId == EnhancementEngineIds.ANDROID_ONNX &&
            offer in enhancementModelOffers && offer.sha256.matches(Regex("[0-9a-f]{64}"))) {
            "仅允许下载已核对来源与摘要的 Android 图像模型"
        }
        val folder = File(root, "model-downloads").also { check(it.isDirectory || it.mkdirs()) }
        val temporary = File(folder, "${offer.id}-${UUID.randomUUID()}.onnx")
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES).build()
        fun checkedUrl(value: String): URI {
            val uri = URI(value)
            val host = uri.host?.lowercase().orEmpty()
            check(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
                (host == "huggingface.co" || host.endsWith(".hf.co") ||
                    host.endsWith(".huggingface.co") || host.endsWith(".cloudfront.net") ||
                    host.endsWith(".amazonaws.com"))) { "模型来源重定向不符合 HTTPS 下载规则" }
            return uri
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var address = offer.downloadUrl
            var redirects = 0
            var downloaded = 0L
            while (true) {
                val checked = checkedUrl(address)
                val response = client.newCall(Request.Builder().url(checked.toString()).get()
                    .header("Accept-Encoding", "identity").build()).execute()
                if (response.code in 300..399) {
                    val target = response.use { it.header("Location") }
                        ?: error("模型下载重定向缺少地址")
                    check(++redirects <= 6) { "模型下载重定向过多" }
                    address = checked.resolve(target).toString()
                    continue
                }
                response.use { result ->
                    check(result.isSuccessful) { "模型下载 HTTP ${result.code}" }
                    val body = result.body ?: error("模型下载响应为空")
                    java.io.FileOutputStream(temporary).buffered().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                check(downloaded <= 2L * 1024 * 1024 * 1024) { "模型文件超过 2 GiB" }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                progress(EngineProgress(EngineStage.DOWNLOAD, downloaded,
                                    body.contentLength().takeIf { it > 0L },
                                    detail = offer.title, bytes = downloaded))
                            }
                        }
                    }
                }
                break
            }
            check(downloaded > 0L) { "模型文件为空" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            progress(EngineProgress(EngineStage.VERIFY, detail = "校验发布者提供的 SHA-256"))
            check(actual == offer.sha256) { "模型 SHA-256 与固定版本不符，已拒绝安装" }
            progress(EngineProgress(EngineStage.INSTALL, detail = "保存已校验模型到本机"))
            models.import(temporary, offer.scale, offer.contents.first(),
                offer.title, offer.license, offer.contents)
        } finally {
            temporary.delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
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
                val engineVersion = when (request.engineId) {
                    EnhancementEngineIds.LOCAL_DETAIL -> {
                        require(request.scale == 1) { "轻量处理只支持 1×" }
                        renderLocal(decoder, output, request.maximumOutputBytes, progress)
                        EnhancementEngineIds.LOCAL_DETAIL_VERSION
                    }
                    EnhancementEngineIds.ANDROID_CUGAN -> {
                        cugan.render(decoder, output, request, progress)
                        AndroidCuganVulkan.ENGINE_VERSION
                    }
                    EnhancementEngineIds.ANDROID_ONNX -> {
                        val model = request.model ?: error("尚未选择适合当前页面的模型")
                        require(request.content in model.contents && model.engineId == request.engineId) { "模型不适用于此页面类别" }
                        require(model.nativeScale == request.scale) { "所选倍率与模型原生倍率不一致" }
                        check(engineSha256(File(model.path)) == model.sha256) { "模型已改变，请重新选择" }
                        progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "准备真实 ONNX CPU 模型"))
                        runtimes.ensureLoaded(PanelRuntimeId.ONNX)
                        val readySession = modelSession(model)
                        currentCoroutineContext().ensureActive()
                        progress(EngineProgress(EngineStage.PROCESS, detail = "正在处理首个分块 · CPU ONNX"))
                        renderOnnx(decoder, output, request, readySession, progress)
                        check(engineSha256(File(model.path)) == model.sha256) { "处理期间模型发生变化，结果已丢弃" }
                        "1.31.1"
                    }
                    else -> error("此图像增强引擎不可用")
                }
                currentCoroutineContext().ensureActive()
                check(engineSha256(source) == sourceHash) { "原始页面已变化，旧增强结果不再使用" }
                progress(EngineProgress(EngineStage.VALIDATE, detail = "校验完整 PNG 与页面尺寸"))
                val size = verifyEnginePng(output)
                check(size.first.toLong() == decoder.width.toLong() * request.scale && size.second.toLong() == decoder.height.toLong() * request.scale)
                val local = request.engineId == EnhancementEngineIds.LOCAL_DETAIL
                val result = EngineOutput(destination.canonicalPath, size.first, size.second, engineSha256(output), output.length(),
                    request.engineId, engineVersion,
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
                val model = request.model
                val vulkan = model?.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO
                val interpolation = request.targetFps != null || request.frameMultiplier != null
                require(interpolation || model != null) { "请选择适用的番剧 ONNX 模型" }
                require(model == null && request.scale == 1 || model != null &&
                    model.engineId in setOf(EnhancementEngineIds.ANDROID_VIDEO_ONNX, EnhancementEngineIds.ANDROID_CUGAN_VIDEO) &&
                    EngineContent.VIDEO in model.contents &&
                    model.nativeScale == request.scale) { "视频模型类型或倍率不匹配" }
                require(request.segmentSeconds == 0 && (request.targetFps == null || request.frameMultiplier == null)) {
                    "当前 Android 离线引擎仅接收一个补帧目标，支持整集处理"
                }
                fun modelMatches() = model == null || if (vulkan) cugan.matches(model) else engineSha256(File(model.path)) == model.sha256
                check(modelMatches()) { "模型已改变，请重新选择" }
                val rife = if (interpolation) checkNotNull(rifeModels.find(request.rifeVersion, request.expectedRifeSha256)) {
                    "需导入 RIFE ${request.rifeVersion} 的官方 FP32 v2 模型"
                } else null
                try {
                    if (!vulkan || rife != null) runtimes.ensureLoaded(PanelRuntimeId.ONNX)
                    progress(EngineProgress(EngineStage.MODEL_LOAD, detail = if (vulkan) "准备 Vulkan Real-CUGAN 模型" else "准备真实 ONNX CPU 模型"))
                    val image = model?.takeUnless { vulkan }?.let(::modelSession)
                    val gpu = if (vulkan) cugan.open(checkNotNull(model)) else null
                    gpu.use { session ->
                        createSessionIfPresent(rife).use { frame ->
                            prepareAndroidVideo(context, request, model, image, rife, frame, session, ::modelMatches, progress)
                        }
                    }
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
        val created = checkNotNull(createSessionIfPresent(model))
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

    private fun createSessionIfPresent(model: EngineModel?): OrtSession? = model?.let {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(it.path, options)
        }
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
        OrtSession.RunOptions().use { runOptions ->
            coroutineScope {
                // Coroutine cancellation cannot interrupt session.run's native call by itself.
                // Signal its supported termination flag from a different dispatcher, then keep
                // the native gate and options alive until the real call and this observer exit.
                val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { runCatching { runOptions.setTerminate(true) } }
                }
                try {
                    val renderer = AndroidOnnxTiles(session, decoder.width, decoder.height,
                        request.scale, request.tileSize, request.overlap, request.maximumOutputBytes,
                        runOptions = runOptions)
                    val decoderOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    }
                    EnginePngWriter(output, renderer.outWidth, renderer.outHeight).use { writer ->
                        renderer.render(coroutine::ensureActive, { left, top, width, height, pixels ->
                            val bitmap = decoder.decodeRegion(Rect(left, top, left + width, top + height), decoderOptions)
                                ?: error("无法解码模型分块")
                            try { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                            finally { bitmap.recycle() }
                        }, { _, row -> writer.row(row) }, { completed, total ->
                            progress(EngineProgress(EngineStage.PROCESS, completed, total,
                                detail = if (completed == 0L) "正在处理首个分块 · CPU ONNX" else "重叠分块 · CPU ONNX"))
                        })
                        coroutine.ensureActive()
                        writer.finish()
                    }
                } finally {
                    withContext(NonCancellable) { cancellation.cancelAndJoin() }
                }
            }
        }
    }
}
