package dev.mangaflow.enhancement.engine

import dev.mangaflow.APP_DISPLAY_NAME

import dev.mangaflow.platform.PlatformContext
import com.luciad.imageio.webp.WebPReadParam
import java.awt.Rectangle
import java.io.File
import java.io.IOException
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
    private val inventoryDigests = EngineInventoryDigests()
    private val animeJaNai = DesktopAnimeJaNai(root)
    private val anime4kVariants = dev.mangaflow.anime.player.Anime4KOptionalModels(context)
    private val imports = ImportedEngineModels(File(context.dataRoot, "enhancement/models"), EnhancementEngineIds.MANGAJANAI)
    private val videoImports = ImportedEngineModels(File(context.dataRoot, "enhancement/models/video"), EnhancementEngineIds.VIDEOJANAI)
    private val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")

    override suspend fun inspect(): EngineInventory = withContext(Dispatchers.IO) {
        val liveModels = if (windows) animeJaNai.models(inventoryDigests::digest) else emptyList()
        val models = installedModels() + DesktopArtCnn.models.takeIf { windows }.orEmpty() +
            liveModels + anime4kVariants.installed()
        val frameModels = installedFrameModels(runtime.folder(DesktopEnginePackages.video), inventoryDigests::digest) +
            if (windows) animeJaNai.frames(inventoryDigests::digest) else emptyList()
        fun descriptor(pack: DesktopEnginePackage, title: String, video: Boolean): EngineDescriptor {
            val executable = if (windows) runtime.executable(pack, inventoryDigests::digest) else null
            val missingMangaRuntime = pack == DesktopEnginePackages.manga && executable != null &&
                !hasMangaGpuRuntime(executable)
            val installed = models.any { it.engineId == pack.id } || video && frameModels.any { it.engineId == pack.id }
            val ready = when {
                !windows -> EngineReadiness.UNSUPPORTED
                executable == null || missingMangaRuntime -> EngineReadiness.NOT_INSTALLED
                !installed -> EngineReadiness.NEEDS_MODELS
                video && runtime.executable(DesktopEnginePackages.ffmpeg, inventoryDigests::digest) == null -> EngineReadiness.NEEDS_MODELS
                else -> EngineReadiness.READY_FOR_TRIAL
            }
            return EngineDescriptor(pack.id, title, pack.version, "GPL-3.0；JaNai 权重按其非商用许可",
                if (video) "https://github.com/the-database/VideoJaNai" else "https://github.com/the-database/MangaJaNaiCLI",
                ready, when (ready) {
                    EngineReadiness.UNSUPPORTED -> "当前高质量原生组件仅提供 Windows x64；原画和轻量处理继续可用"
                    EngineReadiness.NOT_INSTALLED -> if (missingMangaRuntime) "漫画组件已下载，仍需准备 GPU 运行库"
                        else "下载固定版本组件并校验，再准备此设备所需的运行库与模型"
                    EngineReadiness.NEEDS_MODELS -> "组件已安装；继续准备模型、运行库和媒体校验工具"
                    else -> "已找到校验后的组件与模型；需在真实内容上试用，尚未证明设备长期稳定"
                }, setOf(1, 2, 4), if (video) setOf(EngineContent.VIDEO) else setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON),
                canInstall = windows && ready != EngineReadiness.READY_FOR_TRIAL, supportsInterpolation = video,
                downloadBytes = pack.bytes + if (video) DesktopEnginePackages.ffmpeg.bytes else 0,
                videoOutput = EngineVideoContainer.MATROSKA.takeIf { video })
        }
        EngineInventory(listOf(
            EngineDescriptor(EnhancementEngineIds.LOCAL_DETAIL, "轻量线条处理", EnhancementEngineIds.LOCAL_DETAIL_VERSION,
                APP_DISPLAY_NAME, "https://github.com/wozhenbang2004/MangaFlow", EngineReadiness.BUILT_IN,
                "1× 保守去噪，保留文字与细线；非 AI 超分", setOf(1),
                setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON)),
            descriptor(DesktopEnginePackages.manga, "MangaJaNai / IllustrationJaNai", false),
            descriptor(DesktopEnginePackages.video, "AnimeJaNai / RIFE 离线处理", true),
            EngineDescriptor(EnhancementEngineIds.DESKTOP_ARTCNN, "ArtCNN 实时亮度超分", DesktopArtCnn.VERSION, "MIT",
                DesktopArtCnn.SOURCE, if (windows) EngineReadiness.BUILT_IN else EngineReadiness.UNSUPPORTED,
                "真实 C4F16 / C4F32 GPU 模型，原生 2× 亮度超分。渲染器条件与实际执行在播放中显示。",
                setOf(2), setOf(EngineContent.VIDEO), realtime = true),
            animeJaNai.runtimeDescriptor(windows, liveModels),
            animeJaNai.frameDescriptor(windows, liveModels.isNotEmpty(), frameModels),
        ) + anime4kEngineDescriptors() + anime4kVariants.descriptors(),
            models, models.sumOf { it.bytes } + frameModels.sumOf { it.bytes }, directoryBytes(root), frameModels)
    }

    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory {
        if (dev.mangaflow.anime.player.Anime4KOptionalModels.supports(engineId)) {
            withContext(Dispatchers.IO) { anime4kVariants.install(engineId, progress) }
            return inspect()
        }
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
        if (pack == DesktopEnginePackages.manga) {
            check(hasMangaGpuRuntime(executable)) { "漫画增强 GPU 运行库尚未准备完成，请重试安装" }
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
        val started = System.nanoTime()
        val sourceHash = request.expectedSourceSha256 ?: engineSha256(source)
        val local = request.engineId == EnhancementEngineIds.LOCAL_DETAIL
        val work = File(destination.parentFile, "image-${UUID.randomUUID()}")
        check(work.mkdir())
        try {
            val output: File
            val sourceSize: Pair<Int, Int>
            if (local) {
                require(request.scale == 1) { "轻量处理仅支持 1×；2×/4× 需要真实超分模型" }
                output = File(work, "result.png")
                sourceSize = renderDetail(source, output, request.maximumWorkingBytes, progress)
            } else {
                require(request.engineId == EnhancementEngineIds.MANGAJANAI && windows)
                val model = request.model ?: error("请安装或导入适合此页面的真实模型")
                require(model.engineId == request.engineId && request.content in model.contents) { "当前模型不适用于这一类页面" }
                require(request.scale == model.nativeScale || request.scale == 1) { "所选倍率没有匹配的模型" }
                val weights = File(model.path)
                check(engineSha256(weights) == model.sha256) { "模型文件已改变，请重新检查" }
                val executable = runtime.executable(DesktopEnginePackages.manga) ?: error("请先准备漫画增强组件")
                check(hasMangaGpuRuntime(executable)) { "请在画质与性能中准备漫画增强 GPU 运行库" }
                val input = prepareMangaJaNaiInput(source, work, request.maximumWorkingBytes)
                sourceSize = input.width to input.height
                val inputPath = input.file.canonicalPath
                val results = File(work, "output").also { check(it.mkdir()) }
                val tile = if (request.tileSize == 0) "auto" else request.tileSize.coerceIn(256, 1024).toString()
                val args = listOf("--no-config", inputPath, "-o", results.canonicalPath,
                    "--model", weights.canonicalPath, "--format", "png", "--filename", "result",
                    "--scale", request.scale.toString(), "--tile", tile,
                    "--overlap", maxOf(128, request.overlap).toString(), "--workers", "1", "--streams", "1",
                    "--progress", "lines")
                var copiedAnimation = false
                var engineFailure: String? = null
                runEngineProcess(executable, args) { line ->
                    when {
                        line.startsWith("ENGINE_BUILD ") -> progress(EngineProgress(EngineStage.MODEL_LOAD, detail = "首次准备此模型与分块尺寸"))
                        line.startsWith("ENGINE_READY ") -> progress(EngineProgress(EngineStage.PROCESS, detail = "模型已就绪，正在增强页面"))
                        line.startsWith("NATIVE ") -> progress(EngineProgress(EngineStage.PROCESS, detail = "原生 GPU Kernel 已实际启用"))
                        line.startsWith("BACKEND_FALLBACK") -> progress(EngineProgress(EngineStage.PROCESS, detail = "引擎已切换到兼容后端"))
                        line.startsWith("$inputPath: animated ") && ", copied unchanged to " in line -> copiedAnimation = true
                        line.startsWith("ERROR ") && engineFailure == null ->
                            engineFailure = line.substringAfter(": ", "组件报告处理错误")
                        else -> Unit
                    }
                }
                currentCoroutineContext().ensureActive()
                // In lines mode the CLI can emit a per-image error and still exit with status zero.
                engineFailure?.let { reason ->
                    val detail = reason.replace(inputPath, "原图", ignoreCase = true)
                        .replace(weights.canonicalPath, "模型文件", ignoreCase = true)
                        .replace(work.canonicalPath, "增强工作目录", ignoreCase = true)
                        .replace(executable.parentFile.canonicalPath, "漫画增强组件目录", ignoreCase = true)
                        .trim().take(180).ifBlank { "组件报告处理错误" }
                    error("漫画增强引擎失败：$detail")
                }
                check(!copiedAnimation) { "此页是动图，已保留原有动画" }
                output = results.listFiles().orEmpty().singleOrNull { it.extension.equals("png", true) }
                    ?: error("引擎未生成唯一的完整 PNG 输出")
            }
            currentCoroutineContext().ensureActive()
            check(engineSha256(source) == sourceHash) { "处理期间原始页面发生变化，结果已丢弃" }
            request.model?.let { check(engineSha256(File(it.path)) == it.sha256) { "处理期间模型发生变化，结果已丢弃" } }
            progress(EngineProgress(EngineStage.VALIDATE, detail = "校验增强页面"))
            require(output.length() <= request.maximumOutputBytes) {
                "增强文件超过单图压缩或增强缓存额度，可调整对应额度后重试"
            }
            val png = inspectEnginePng(output)
            check(png.width.toLong() == sourceSize.first.toLong() * request.scale &&
                png.height.toLong() == sourceSize.second.toLong() * request.scale) { "增强结果尺寸与目标倍率不符" }
            val result = EngineOutput(destination.canonicalPath, png.width, png.height, png.sha256, output.length(),
                request.engineId, if (local) EnhancementEngineIds.LOCAL_DETAIL_VERSION else DesktopEnginePackages.manga.version,
                if (local) EnhancementEngineIds.LOCAL_DETAIL_DIGEST else checkNotNull(request.model).sha256,
                (System.nanoTime() - started) / 1_000_000)
            currentCoroutineContext().ensureActive()
            moveNewEngineFile(output, destination, png.sha256)
            progress(EngineProgress(EngineStage.COMPLETE, 1, 1))
            result
        } finally { work.deleteRecursively() }
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
                .take(200).mapNotNull { file -> describeInstalledModel(file, engineId, inventoryDigests::digest) }.toList()
        }
        // The official setup may use its shared store. Only read model-data directories named in its own runtime manifest.
        val manifests = owned.flatMap { (folder, _) -> if (folder.isDirectory)
            folder.walkTopDown().maxDepth(6).filter { it.name == "aji-runtime.json" && it.isFile }.toList() else emptyList() }
        val shared = manifests.flatMap { manifest -> readRuntimeModelDirectories(manifest) }.distinctBy { it.canonicalPath }
            .flatMap { folder -> if (folder.isDirectory) folder.walkTopDown().maxDepth(4).filter {
                it.isFile && it.extension.equals("onnx", true) && (it.name.contains("MangaJaNai") || it.name.contains("IllustrationJaNai"))
            }.take(200).mapNotNull { describeInstalledModel(it, EnhancementEngineIds.MANGAJANAI, inventoryDigests::digest) }.toList() else emptyList() }
        return (imports.list() + videoImports.list() + found + shared).distinctBy { it.path }
    }
}

private fun installedFrameModels(directory: File, digest: (File) -> String): List<EngineModel> = if (!directory.isDirectory) emptyList() else
    directory.walkTopDown().maxDepth(9).onEnter { it.canonicalFile == it.absoluteFile }
        .filter { it.isFile && it.canonicalFile == it.absoluteFile && it.name.matches(Regex("rife_v4\\.[0-9]{1,2}(?:_lite)?(?:_ensemble)?\\.onnx")) }
        .map { file ->
            val hash = digest(file)
            EngineModel("rife:$hash", EnhancementEngineIds.VIDEOJANAI, file.nameWithoutExtension, file.canonicalPath, hash, 1,
                setOf(EngineContent.VIDEO), "MIT · RIFE（以组件内许可为准）", "sha256:${hash.take(12)}", file.length(), "RIFE_FRAME_PAIR")
        }.toList()

/** Match the pinned CLI's private runtime and installed GUI lookup. */
private fun hasMangaGpuRuntime(executable: File): Boolean {
    val directory = executable.parentFile
    if (File(directory, "aji.dll").isFile &&
        listOf("onnxruntime.dll", "nvinfer_11.dll", "aji-runtime.json").any { File(directory, it).isFile }) return true
    return System.getenv("LOCALAPPDATA")?.let {
        File(it, "Programs/MangaJaNaiConverterGui/inference/aji.dll").isFile
    } == true
}

private fun describeInstalledModel(file: File, engineId: String, digest: (File) -> String): EngineModel? = runCatching {
    val scale = Regex("(?:^|_)([124])x[_-]", RegexOption.IGNORE_CASE).find(file.name)?.groupValues?.get(1)?.toInt()
        ?: return null
    val content = when {
        file.name.contains("AnimeJaNai") -> setOf(EngineContent.VIDEO)
        file.name.contains("IllustrationJaNai") -> setOf(EngineContent.COLOR, EngineContent.WEBTOON)
        else -> setOf(EngineContent.BLACK_WHITE)
    }
    val hash = digest(file)
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

private inline fun <T> withImageReader(file: File, block: (ImageReader) -> T): T = try {
    val input: ImageInputStream = ImageIO.createImageInputStream(file) ?: error("页面格式无法读取")
    input.use {
        val readers = ImageIO.getImageReaders(input)
        check(readers.hasNext()) { "当前增强器不支持此图片格式" }
        val reader = readers.next()
        try { reader.input = input; block(reader) } finally { reader.dispose() }
    }
} catch (failure: LinkageError) {
    // A native decoder that cannot load should fail this page, not the shared queue.
    throw IOException("本页的原图解码组件无法加载，已保留原图", failure)
}

private data class MangaJaNaiInput(val file: File, val width: Int, val height: Int)

/** The CLI selects a decoder from the filename. Cache files can have an opaque extension. */
private fun prepareMangaJaNaiInput(source: File, work: File, budget: Long): MangaJaNaiInput =
    withImageReader(source) { reader ->
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        require(width > 0 && height > 0) { "原图尺寸无效" }
        val format = reader.formatName.lowercase()
        val extension = when (format) {
            "jpeg", "jpg" -> "jpg"
            "tif", "tiff" -> "tiff"
            "png", "webp", "bmp", "avif", "jxl" -> format
            "gif" -> {
                // The pinned CLI accepts GIF only inside archives. Preserve alpha and all pixels
                // of a single-frame GIF, and leave a genuinely animated page to the reader.
                check(reader.getNumImages(true) == 1) { "此页是动图，已保留原有动画" }
                require(width.toLong() * height <= budget / 8) { "此页的无损转换超出图片处理内存额度" }
                val converted = File(work, "input.png")
                val image = reader.read(0)
                try { check(ImageIO.write(image, "png", converted)) { "无法写入静态 GIF 的无损输入" } }
                finally { image.flush() }
                return@withImageReader MangaJaNaiInput(converted, width, height)
            }
            else -> error("当前漫画组件不支持这一页面编码")
        }
        if (source.extension.equals(extension, true)) return@withImageReader MangaJaNaiInput(source, width, height)
        val alias = File(work, "input.$extension")
        try {
            Files.createLink(alias.toPath(), source.toPath())
        } catch (_: UnsupportedOperationException) {
            Files.copy(source.toPath(), alias.toPath())
        } catch (_: IOException) {
            // Across volumes, or on a file system without hard links, keep the same decoded format.
            Files.copy(source.toPath(), alias.toPath())
        }
        MangaJaNaiInput(alias, width, height)
    }

private suspend fun renderDetail(
    source: File, output: File, budget: Long, progress: (EngineProgress) -> Unit,
): Pair<Int, Int> = withImageReader(source) { reader ->
    val width = reader.getWidth(0)
    val height = reader.getHeight(0)
    require(width > 0 && height > 0)
    if (reader.formatName.equals("gif", true)) {
        check(reader.getNumImages(true) == 1) { "此页是动图，已保留原有动画" }
    }
    // Reserve space for the decoded bitmap, converted ARGB rows, the output row and filter halo.
    // A normal page can be decoded in one go, while a long strip uses the same bounded path.
    val rowBytes = width.toLong() * 16
    val availableRows = minOf(budget / rowBytes, Int.MAX_VALUE.toLong() / width)
    require(availableRows >= minOf(height.toLong(), 4L) + 1) { "当前图片处理内存额度不足以容纳此页的一行及滤镜边缘" }
    val bandRows = if (height < availableRows) height else (availableRows - 4L).toInt().coerceAtLeast(1)
    val chunkRows = minOf(bandRows, 64)
    val pixelRows = minOf(height.toLong(), chunkRows.toLong() + 2).toInt()
    val pixels = IntArray(width * pixelRows)
    val row = IntArray(width)
    val context = currentCoroutineContext()
    EnginePngWriter(output, width, height).use { png ->
        var top = 0
        while (top < height) {
            context.ensureActive()
            val bottom = top + minOf(bandRows, height - top)
            val param = reader.defaultReadParam
            val first = maxOf(0, top - 1)
            // libwebp rounds the start of a crop down to an even coordinate.
            val from = if (param is WebPReadParam) first - first % 2 else first
            val to = if (bottom == height) bottom else bottom + 1
            if (from > 0 || to < height) {
                if (param is WebPReadParam) {
                    param.isUseCropping = true; param.cropLeft = 0; param.cropTop = from
                    param.cropWidth = width; param.cropHeight = to - from
                } else param.sourceRegion = Rectangle(0, from, width, to - from)
            }
            val band = reader.read(0, param)
            try {
                check(band.width == width && band.height == to - from) { "原图解码区域尺寸不符" }
                var next = top
                while (next < bottom) {
                    val end = next + minOf(chunkRows, bottom - next)
                    val startRow = maxOf(0, next - 1)
                    val endRow = if (end == height) end else end + 1
                    val count = endRow - startRow
                    band.getRGB(0, startRow - from, width, count, pixels, 0, width)
                    for (y in next until end) {
                        context.ensureActive()
                        for (x in 0 until width) row[x] = localDetailPixel(pixels, width, count, x, y - startRow)
                        png.row(row)
                    }
                    next = end
                    progress(EngineProgress(EngineStage.PROCESS, next.toLong(), height.toLong(), detail = "1× 保守去噪"))
                }
            } finally { band.flush() }
            top = bottom
        }
        png.finish()
    }
    width to height
}
