package dev.mangaflow.enhancement.engine

import dev.mangaflow.platform.PlatformContext
import com.luciad.imageio.webp.WebPReadParam
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

actual fun createEnhancementEngine(context: PlatformContext): EnhancementEngine = DesktopEnhancementEngine(context)

private class DesktopEnhancementEngine(context: PlatformContext) : EnhancementEngine {
    private val root = File(context.dataRoot, "enhancement/engines")
    private val runtime = DesktopEngineRuntime(root)
    private val animeJaNai = DesktopAnimeJaNai(root)
    private val imports = ImportedEngineModels(File(context.dataRoot, "enhancement/models"), EnhancementEngineIds.MANGAJANAI)
    private val videoImports = ImportedEngineModels(File(context.dataRoot, "enhancement/models/video"), EnhancementEngineIds.VIDEOJANAI)
    private val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")

    override suspend fun inspect(): EngineInventory = withContext(Dispatchers.IO) {
        val models = installedModels() + DesktopArtCnn.models.takeIf { windows }.orEmpty() +
            if (windows) animeJaNai.models() else emptyList()
        val frameModels = installedFrameModels(runtime.folder(DesktopEnginePackages.video)) +
            if (windows) animeJaNai.frames() else emptyList()
        fun descriptor(pack: DesktopEnginePackage, title: String, video: Boolean): EngineDescriptor {
            val executable = if (windows) runtime.executable(pack) else null
            val installed = models.any { it.engineId == pack.id } || video && frameModels.isNotEmpty()
            val ready = when {
                !windows -> EngineReadiness.UNSUPPORTED
                executable == null -> EngineReadiness.NOT_INSTALLED
                !installed -> EngineReadiness.NEEDS_MODELS
                video && runtime.executable(DesktopEnginePackages.ffmpeg) == null -> EngineReadiness.NEEDS_MODELS
                else -> EngineReadiness.READY_FOR_TRIAL
            }
            return EngineDescriptor(pack.id, title, pack.version, "GPL-3.0；JaNai 权重按其非商用许可",
                if (video) "https://github.com/the-database/VideoJaNai" else "https://github.com/the-database/MangaJaNaiCLI",
                ready, when (ready) {
                    EngineReadiness.UNSUPPORTED -> "当前高质量原生组件仅提供 Windows x64；原画和轻量处理继续可用"
                    EngineReadiness.NOT_INSTALLED -> "下载固定版本组件并校验，再准备此设备所需的运行库与模型"
                    EngineReadiness.NEEDS_MODELS -> "组件已安装；继续准备模型、运行库和媒体校验工具"
                    else -> "已找到校验后的组件与模型；需在真实内容上试用，尚未证明设备长期稳定"
                }, setOf(1, 2, 4), if (video) setOf(EngineContent.VIDEO) else setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON),
                canInstall = windows && ready != EngineReadiness.READY_FOR_TRIAL, supportsInterpolation = video,
                downloadBytes = pack.bytes + if (video) DesktopEnginePackages.ffmpeg.bytes else 0,
                videoOutput = EngineVideoContainer.MATROSKA.takeIf { video })
        }
        EngineInventory(listOf(
            EngineDescriptor(EnhancementEngineIds.LOCAL_DETAIL, "轻量线条处理", EnhancementEngineIds.LOCAL_DETAIL_VERSION,
                "MangaFlow", "https://github.com/wozhenbang2004/MangaFlow", EngineReadiness.BUILT_IN,
                "1× 保守去噪，保留文字与细线；非 AI 超分", setOf(1),
                setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON)),
            descriptor(DesktopEnginePackages.manga, "MangaJaNai / IllustrationJaNai", false),
            descriptor(DesktopEnginePackages.video, "AnimeJaNai / RIFE 离线处理", true),
            EngineDescriptor(EnhancementEngineIds.DESKTOP_ARTCNN, "ArtCNN 实时亮度超分", DesktopArtCnn.VERSION, "MIT",
                DesktopArtCnn.SOURCE, if (windows) EngineReadiness.BUILT_IN else EngineReadiness.UNSUPPORTED,
                "真实 C4F16 / C4F32 GPU 模型，原生 2× 亮度超分。渲染器条件与实际执行在播放中显示。",
                setOf(2), setOf(EngineContent.VIDEO), realtime = true),
            animeJaNai.runtimeDescriptor(windows),
            animeJaNai.frameDescriptor(windows),
        ) + anime4kEngineDescriptors(), models, models.sumOf { it.bytes } + frameModels.sumOf { it.bytes }, directoryBytes(root), frameModels)
    }

    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory {
        check(windows) { "当前组件支持 Windows x64" }
        if (engineId == EnhancementEngineIds.DESKTOP_REALTIME || engineId == EnhancementEngineIds.DESKTOP_REALTIME_RIFE) {
            withContext(Dispatchers.IO) { animeJaNai.install(engineId == EnhancementEngineIds.DESKTOP_REALTIME_RIFE, progress) }
            return inspect()
        }
        val pack = when (engineId) {
            EnhancementEngineIds.MANGAJANAI -> DesktopEnginePackages.manga
            EnhancementEngineIds.VIDEOJANAI -> DesktopEnginePackages.video
            else -> error("当前引擎没有可安装的受支持组件")
        }
        val executable = runtime.install(pack, progress)
        progress(EngineProgress(EngineStage.INSTALL, detail = "准备 GPU 运行库和模型；下载大小由当前设备决定"))
        runEngineProcess(executable, listOf("setup", "--progress", "lines")) { line ->
            val parts = line.split(' ')
            if (parts.firstOrNull() == "SETUP_DOWNLOAD") progress(EngineProgress(EngineStage.DOWNLOAD,
                completed = parts.getOrNull(2)?.toLongOrNull() ?: 0, total = parts.getOrNull(3)?.toLongOrNull(),
                detail = "准备 GPU 组件"))
            else if (line.startsWith("SETUP_EXTRACT")) progress(EngineProgress(EngineStage.INSTALL, detail = "展开 GPU 组件"))
        }
        if (pack == DesktopEnginePackages.video) runtime.install(DesktopEnginePackages.ffmpeg, progress)
        progress(EngineProgress(EngineStage.COMPLETE, detail = "组件准备完成；真实内容试用后才有设备性能记录"))
        return inspect()
    }

    override suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel =
        withContext(Dispatchers.IO) {
            val owner = if (content == EngineContent.VIDEO) videoImports else imports
            owner.import(if (sourcePath.startsWith("file:")) File(java.net.URI(sourcePath)) else File(sourcePath), nativeScale, content)
        }

    override suspend fun describeImage(sourcePath: String): EngineImageInfo = withContext(Dispatchers.IO) {
        withImageReader(File(sourcePath)) { reader ->
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width > 0 && height > 0)
            val param = reader.defaultReadParam.apply {
                if (this is WebPReadParam) {
                    isUseScaling = true
                    scaledWidth = minOf(width, 96); scaledHeight = minOf(height, 96)
                } else setSourceSubsampling(maxOf(1, width / 96), maxOf(1, height / 96), 0, 0)
            }
            val sample = reader.read(0, param)
            try {
                val colors = IntArray(sample.width * sample.height)
                sample.getRGB(0, 0, sample.width, sample.height, colors, 0, sample.width)
                EngineImageInfo(width, height, classifyEngineImage(width, height, colors))
            } finally { sample.flush() }
        }
    }

    override suspend fun processImage(request: EngineImageRequest, progress: (EngineProgress) -> Unit): EngineOutput = withContext(Dispatchers.IO) {
        val source = File(request.sourcePath)
        val destination = File(request.destinationPath)
        newEngineOutput(source, destination)
        require(engineJpegOrientation(source) == 1) { "此页面带旋转或镜像元数据；当前增强保留原图以维持阅读几何" }
        val output = File(destination.parentFile, "render-${UUID.randomUUID()}.png")
        val started = System.nanoTime()
        val sourceHash = engineSha256(source)
        try {
            if (request.engineId == EnhancementEngineIds.LOCAL_DETAIL) {
                require(request.scale == 1) { "轻量处理仅支持 1×；2×/4× 需要真实超分模型" }
                renderDetail(source, output, request.maximumOutputBytes, progress)
            } else {
                require(request.engineId == EnhancementEngineIds.MANGAJANAI && windows)
                val model = request.model ?: error("请安装或导入适合此页面的真实模型")
                require(model.engineId == request.engineId && request.content in model.contents) { "当前模型不适用于这一类页面" }
                require(request.scale == model.nativeScale || request.scale == 1) { "所选倍率没有匹配的模型" }
                val weights = File(model.path)
                check(engineSha256(weights) == model.sha256) { "模型文件已改变，请重新检查" }
                val executable = runtime.executable(DesktopEnginePackages.manga) ?: error("请先准备漫画增强组件")
                val temporary = File(output.parentFile, "image-${UUID.randomUUID()}")
                check(temporary.mkdir())
                try {
                    // Cache owners intentionally use opaque file names. The CLI classifies input by extension;
                    // copy the unchanged bytes under the format actually identified by the image decoder.
                    val extension = withImageReader(source) { reader -> when (reader.formatName.lowercase()) {
                        "jpeg", "jpg" -> "jpg"
                        "tif", "tiff" -> "tiff"
                        "png", "webp", "bmp", "gif" -> reader.formatName.lowercase()
                        else -> error("当前漫画组件不支持这一页面编码")
                    } }
                    val input = File(temporary, "input.$extension")
                    Files.copy(source.toPath(), input.toPath())
                    val results = File(temporary, "output").also { check(it.mkdir()) }
                    val args = listOf("--no-config", input.canonicalPath, "-o", results.canonicalPath,
                        "--model", weights.canonicalPath, "--format", "png", "--filename", "result",
                        "--scale", request.scale.toString(), "--tile", request.tileSize.coerceIn(256, 1024).toString(),
                        "--overlap", maxOf(128, request.overlap).toString(), "--workers", "1", "--streams", "1",
                        "--progress", "lines")
                    runEngineProcess(executable, args) { line ->
                        if (line.startsWith("ENGINE_BUILD")) progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "首次准备此模型与分块尺寸"))
                        else if (line.startsWith("NATIVE ")) progress(EngineProgress(EngineStage.PROCESS, detail = "原生 GPU Kernel 已实际启用"))
                        else if (line.startsWith("BACKEND_FALLBACK")) progress(EngineProgress(EngineStage.PROCESS, detail = "引擎已切换到兼容后端"))
                    }
                    currentCoroutineContext().ensureActive()
                    val produced = results.listFiles().orEmpty().singleOrNull { it.extension.equals("png", true) }
                        ?: error("引擎未生成唯一的完整 PNG 输出")
                    val sourceInfo = describeImage(source.path)
                    withImageReader(produced) { reader ->
                        check(reader.getWidth(0).toLong() == sourceInfo.width.toLong() * request.scale &&
                            reader.getHeight(0).toLong() == sourceInfo.height.toLong() * request.scale) { "增强结果尺寸与目标倍率不符" }
                    }
                    moveNewEngineFile(produced, output)
                } finally { temporary.deleteRecursively() }
            }
            currentCoroutineContext().ensureActive()
            check(engineSha256(source) == sourceHash) { "处理期间原始页面发生变化，结果已丢弃" }
            request.model?.let { check(engineSha256(File(it.path)) == it.sha256) { "处理期间模型发生变化，结果已丢弃" } }
            progress(EngineProgress(EngineStage.VALIDATE, detail = "校验增强页面"))
            val dimensions = verifyEnginePng(output)
            val local = request.engineId == EnhancementEngineIds.LOCAL_DETAIL
            val result = EngineOutput(destination.canonicalPath, dimensions.first, dimensions.second, engineSha256(output), output.length(),
                request.engineId, if (local) EnhancementEngineIds.LOCAL_DETAIL_VERSION else DesktopEnginePackages.manga.version,
                if (local) EnhancementEngineIds.LOCAL_DETAIL_DIGEST else checkNotNull(request.model).sha256,
                (System.nanoTime() - started) / 1_000_000)
            currentCoroutineContext().ensureActive()
            moveNewEngineFile(output, destination)
            progress(EngineProgress(EngineStage.COMPLETE, 1, 1))
            result
        } catch (failure: Throwable) { output.delete(); throw failure }
    }

    override suspend fun processVideo(request: EngineVideoRequest, progress: (EngineProgress) -> Unit): EngineOutput {
        check(windows) { "离线视频增强需要 Windows x64" }
        return DesktopVideoPreparation(runtime).process(request, progress)
    }

    override suspend fun close() = Unit

    private fun installedModels(): List<EngineModel> {
        val owned = listOf(
            runtime.folder(DesktopEnginePackages.manga) to EnhancementEngineIds.MANGAJANAI,
            runtime.folder(DesktopEnginePackages.video) to EnhancementEngineIds.VIDEOJANAI,
        )
        val found = owned.flatMap { (folder, engineId) ->
            if (!folder.isDirectory) emptyList() else folder.walkTopDown().maxDepth(9)
                .filter { it.isFile && it.extension.equals("onnx", true) &&
                    (it.name.contains("MangaJaNai") || it.name.contains("IllustrationJaNai") || it.name.contains("AnimeJaNai")) }
                .take(200).mapNotNull { file -> describeInstalledModel(file, engineId) }.toList()
        }
        // The official setup may use its shared store. Only read model-data directories named in its own runtime manifest.
        val manifests = owned.flatMap { (folder, _) -> if (folder.isDirectory)
            folder.walkTopDown().maxDepth(6).filter { it.name == "aji-runtime.json" && it.isFile }.toList() else emptyList() }
        val shared = manifests.flatMap { manifest -> readRuntimeModelDirectories(manifest) }.distinctBy { it.canonicalPath }
            .flatMap { folder -> if (folder.isDirectory) folder.walkTopDown().maxDepth(4).filter {
                it.isFile && it.extension.equals("onnx", true) && (it.name.contains("MangaJaNai") || it.name.contains("IllustrationJaNai"))
            }.take(200).mapNotNull { describeInstalledModel(it, EnhancementEngineIds.MANGAJANAI) }.toList() else emptyList() }
        return (imports.list() + videoImports.list() + found + shared).distinctBy { it.path }
    }
}

private fun installedFrameModels(directory: File): List<EngineModel> = if (!directory.isDirectory) emptyList() else
    directory.walkTopDown().maxDepth(9).filter { it.isFile && it.name.matches(Regex("rife_v4\\.[0-9]{1,2}(?:_lite)?(?:_ensemble)?\\.onnx")) }
        .map { file ->
            val hash = engineSha256(file)
            EngineModel("rife:$hash", EnhancementEngineIds.VIDEOJANAI, file.nameWithoutExtension, file.canonicalPath, hash, 1,
                setOf(EngineContent.VIDEO), "MIT · RIFE（以组件内许可为准）", "sha256:${hash.take(12)}", file.length(), "RIFE_FRAME_PAIR")
        }.toList()

private fun describeInstalledModel(file: File, engineId: String): EngineModel? = runCatching {
    val scale = Regex("(?:^|_)([124])x[_-]", RegexOption.IGNORE_CASE).find(file.name)?.groupValues?.get(1)?.toInt()
        ?: return null
    val content = when {
        file.name.contains("AnimeJaNai") -> setOf(EngineContent.VIDEO)
        file.name.contains("IllustrationJaNai") -> setOf(EngineContent.COLOR, EngineContent.WEBTOON)
        else -> setOf(EngineContent.BLACK_WHITE)
    }
    val hash = engineSha256(file)
    EngineModel("$engineId:$hash", engineId, file.nameWithoutExtension, file.canonicalPath, hash, scale,
        content, "CC BY-NC 4.0 · the-database（以组件内许可为准）", "sha256:${hash.take(12)}", file.length(),
        if (file.name.contains("fp16")) "NCHW_RGB_FLOAT16" else "NCHW_RGB_FLOAT")
}.getOrNull()

private fun readRuntimeModelDirectories(file: File): List<File> = runCatching {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(file.readText())
    val result = mutableListOf<File>()
    fun visit(element: kotlinx.serialization.json.JsonElement, key: String = "") {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (name, value) -> visit(value, name) }
            is kotlinx.serialization.json.JsonArray -> element.forEach { visit(it, key) }
            is kotlinx.serialization.json.JsonPrimitive -> if (key.contains("model", true) && element.isString) {
                val path = File(element.content)
                if (path.isAbsolute && path.isDirectory) result += path
            }
            else -> Unit
        }
    }
    visit(json); result
}.getOrDefault(emptyList())

private inline fun <T> withImageReader(file: File, block: (ImageReader) -> T): T {
    val input: ImageInputStream = ImageIO.createImageInputStream(file) ?: error("页面格式无法读取")
    input.use {
        val readers = ImageIO.getImageReaders(input)
        check(readers.hasNext()) { "当前增强器不支持此图片格式" }
        val reader = readers.next()
        try { reader.input = input; return block(reader) } finally { reader.dispose() }
    }
}

private suspend fun renderDetail(source: File, output: File, budget: Long, progress: (EngineProgress) -> Unit) {
    withImageReader(source) { reader ->
        val width = reader.getWidth(0); val height = reader.getHeight(0)
        require(width > 0 && height > 0 && width.toLong() * 4 < budget)
        val stripHeight = minOf(64, maxOf(1, (budget / (width.toLong() * 16)).toInt()))
        EnginePngWriter(output, width, height).use { png ->
            var top = 0
            while (top < height) {
                currentCoroutineContext().ensureActive()
                val bottom = minOf(height, top + stripHeight)
                val parameter = reader.defaultReadParam
                val first = maxOf(0, top - 1)
                // libwebp snaps crop origins to even pixels; include that row in the explicitly tracked halo.
                val from = if (parameter is WebPReadParam) first - first % 2 else first
                val to = minOf(height, bottom + 1)
                parameter.apply {
                    if (this is WebPReadParam) {
                        isUseCropping = true; cropLeft = 0; cropTop = from
                        cropWidth = width; cropHeight = to - from
                    } else sourceRegion = Rectangle(0, from, width, to - from)
                }
                val strip = reader.read(0, parameter)
                try {
                    val pixels = IntArray(width * strip.height)
                    strip.getRGB(0, 0, width, strip.height, pixels, 0, width)
                    val row = IntArray(width)
                    for (y in top until bottom) {
                        for (x in 0 until width) row[x] = localDetailPixel(pixels, width, strip.height, x, y - from)
                        png.row(row)
                    }
                } finally { strip.flush() }
                top = bottom
                progress(EngineProgress(EngineStage.PROCESS, top.toLong(), height.toLong(), detail = "1× 保守去噪"))
            }
            png.finish()
        }
    }
}
