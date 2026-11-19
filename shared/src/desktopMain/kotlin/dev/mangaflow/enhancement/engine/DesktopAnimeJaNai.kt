package dev.mangaflow.enhancement.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openani.mediamp.mpv.MPVHandle

/** Downloads the official host and keeps it isolated from the bundled Mediamp runtime. */
internal class DesktopAnimeJaNai(private val root: File) {
    private val native = File(root, "animejanai-player-$VERSION")
    private val rife = File(root, "animejanai-player-rife-$VERSION")

    fun models(): List<EngineModel> = if (available(native, runtimeFiles)) {
        videoWeights.map { (key, weight) ->
            EngineModel("animejanai-live:$key", EnhancementEngineIds.DESKTOP_REALTIME, weight.title,
                File(native, weight.path).absolutePath, weight.sha256, 2, setOf(EngineContent.VIDEO),
                "上游 AnimeJaNai 权重许可", VERSION, weight.bytes)
        }
    } else emptyList()

    fun frames(): List<EngineModel> = if (available(rife, mapOf(rifeWeight.path to rifeWeight.sha256))) {
        listOf(EngineModel(RIFE_ID, EnhancementEngineIds.DESKTOP_REALTIME_RIFE, rifeWeight.title,
            File(rife, rifeWeight.path).absolutePath, rifeWeight.sha256, 1, setOf(EngineContent.VIDEO),
            "上游 RIFE 权重许可", VERSION, File(rife, rifeWeight.path).length()))
    } else emptyList()

    fun runtimeDescriptor(supported: Boolean) = EngineDescriptor(
        EnhancementEngineIds.DESKTOP_REALTIME, "AnimeJaNai 实时超分 · DirectML", VERSION,
        "mpv GPL-2.0+；推理与权重按各自上游许可", SOURCE,
        readiness(supported, models().isNotEmpty()),
        "可选 Direct3D 11 播放内核。安装后选择模型并重新打开应用；在实际视频获得输出后显示运行状态。",
        setOf(2), setOf(EngineContent.VIDEO), canInstall = supported && models().isEmpty(),
        realtime = true, downloadBytes = portable.bytes,
    )

    fun frameDescriptor(supported: Boolean) = EngineDescriptor(
        EnhancementEngineIds.DESKTOP_REALTIME_RIFE, "RIFE v4.22 实时补帧 · DirectML", VERSION,
        "上游 RIFE 权重许可", SOURCE, readiness(supported, frames().isNotEmpty() && models().isNotEmpty()),
        "当前支持均衡档。即使画质选原画也可单独试用；60/120 FPS 由实际源帧率和设备决定。",
        setOf(1), setOf(EngineContent.VIDEO), canInstall = supported && frames().isEmpty(),
        supportsInterpolation = true, realtime = true, downloadBytes = rifePack.bytes,
    )

    suspend fun install(withRife: Boolean, progress: (EngineProgress) -> Unit): Unit = installLock.withLock {
        installPack(portable, native, runtimeFiles, "mpv-upscale-2x_animejanai-v$VERSION/", progress)
        if (withRife) installPack(rifePack, rife, mapOf(rifeWeight.path to rifeWeight.sha256), "", progress)
    }

    /** Mediamp loads this process-wide before its first player. A failed launch returns to stock on the next boot. */
    fun configureProcess(requestAlternate: Boolean) = synchronized(processLock) {
        if (processStarted) return@synchronized
        processStarted = true
        if (!isWindows()) return@synchronized
        val started = File(root, "animejanai-$VERSION-starting")
        if (started.exists()) {
            started.delete()
            processIssue = "上次原生内核未取得首帧，本次使用原有播放内核"
            return@synchronized
        }
        if (!requestAlternate) return@synchronized
        try {
            require(available(native, runtimeFiles, verifyContents = true)) { "组件完整性校验未通过" }
            require(root.isDirectory || root.mkdirs())
            check(started.createNewFile())
            MPVHandle.setRuntimeLibraryDirectory(native.resolve("player").absolutePath, extractRuntimeLibrary = false)
            selectedNative = native
            pendingReceipt = started
        } catch (_: LinkageError) {
            started.delete()
            processIssue = "无法载入 Direct3D 11 内核，请检查 Windows 运行库"
        } catch (_: Exception) {
            started.delete()
            processIssue = "AnimeJaNai 内核无法载入，继续使用原有内核"
        }
    }

    fun confirmFirstFrame() = synchronized(processLock) {
        pendingReceipt?.let { if (it.delete() || !it.exists()) pendingReceipt = null }
    }

    fun usesNativeRuntime() = selectedNative == native

    fun unavailableReason() = processIssue ?: "AnimeJaNai 内核需在重新打开应用后启用"

    fun session(model: EngineModel?, frame: EngineModel?, fraction: Pair<Int, Int>?): FilterSession? {
        if (selectedNative != native || model == null && frame == null) return null
        val weight = model?.let { value -> videoWeights.entries.find { "animejanai-live:${it.key}" == value.id }?.value }
        if (model != null && (weight == null || weight.sha256 != model.sha256)) return null
        if (frame != null && (frame.id != RIFE_ID || frame.sha256 != rifeWeight.sha256 ||
                !available(rife, mapOf(rifeWeight.path to rifeWeight.sha256), verifyContents = true) || fraction == null)) return null
        val temporary = Files.createTempDirectory("mangaflow-animejanai-").toFile()
        return try {
            val conf = File(temporary, "animejanai.conf")
            conf.writeText(buildString {
                appendLine("[global]")
                appendLine("config_version=3")
                appendLine("backend=DirectML")
                appendLine("logging=yes")
                appendLine("default_slot=1")
                appendLine("[slot_1]")
                appendLine("profile_name=MangaFlow")
                appendLine("chain_1_max_resolution=1920x1080")
                appendLine("chain_1_max_fps=61")
                if (weight != null) appendLine("chain_1_model_1_name=${File(weight.path).nameWithoutExtension}")
                else appendLine("chain_1_upscale=no")
                if (frame != null && fraction != null) {
                    appendLine("chain_1_rife=yes")
                    appendLine("chain_1_rife_factor_numerator=${fraction.first}")
                    appendLine("chain_1_rife_factor_denominator=${fraction.second}")
                    appendLine("chain_1_rife_model=422")
                    appendLine("chain_1_rife_before_upscale=yes")
                    appendLine("chain_1_rife_scene_detect_threshold=0.150")
                }
            })
            val stats = File(temporary, "stats.log")
            // mpv fixed-length UTF-8 quoting handles drive letters, %, spaces and filter delimiters.
            fun path(file: File): String {
                val value = file.absolutePath.replace('\\', '/')
                return "%${value.toByteArray(Charsets.UTF_8).size}%$value"
            }
            val filter = "@mangaflow-aji:animejanai:slot=1:lib=${path(native.resolve("animejanai/inference/aji.dll"))}" +
                ":conf=${path(conf)}:model-dir=${path(native.resolve("animejanai/onnx"))}" +
                ":rife-model-dir=${path(rife.resolve("animejanai/rife"))}:stats=${path(stats)}"
            FilterSession(temporary, filter, stats, weight?.let { File(it.path).nameWithoutExtension },
                frame != null)
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            throw failure
        }
    }

    private suspend fun installPack(pack: DesktopEnginePackage, target: File, files: Map<String, String>, prefix: String,
        progress: (EngineProgress) -> Unit) {
        if (available(target, files, verifyContents = true)) return
        check(isWindows() && (root.isDirectory || root.mkdirs()))
        check(!target.exists()) { "组件文件已改变，请从增强组件目录移出旧文件后重装" }
        val systemRoot = System.getenv("SystemRoot") ?: error("未找到 Windows 系统目录")
        val tar = File(systemRoot, "System32/tar.exe").canonicalFile
        check(tar.isFile) { "此 Windows 版本缺少系统组件 tar.exe" }
        val archive = File(root, "${pack.id}-${UUID.randomUUID()}.7z.partial")
        val staging = File(root, "${pack.id}-${UUID.randomUUID()}.partial")
        try {
            DesktopEngineRuntime(root).download(pack, archive, progress)
            progress(EngineProgress(EngineStage.VERIFY, detail = "校验 AnimeJaNai 官方归档"))
            check(engineSha256(archive) == pack.sha256) { "官方归档校验失败" }
            check(staging.mkdir())
            progress(EngineProgress(EngineStage.INSTALL, detail = "展开播放器和模型所需文件"))
            runEngineProcess(tar, listOf("-xf", archive.absolutePath, "-C", staging.absolutePath) +
                files.keys.map { prefix + it }, staging, timeoutMillis = 180_000L)
            if (prefix.isNotEmpty()) {
                val extracted = File(staging, prefix.removeSuffix("/"))
                val player = File(staging, "player").apply { check(mkdir()) }
                check(extracted.resolve("libmpv-2.dll").renameTo(player.resolve("libmpv-2.dll")))
                check(extracted.resolve("animejanai").renameTo(staging.resolve("animejanai")))
                check(extracted.delete())
                stockFiles().forEach { name ->
                    val stream = MPVHandle::class.java.classLoader.getResourceAsStream(name)
                        ?: error("安装包缺少播放器依赖 $name")
                    stream.use { Files.copy(it, File(player, name).toPath()) }
                }
            }
            check(available(staging, files, verifyContents = true)) { "展开的原生文件校验失败" }
            currentCoroutineContext().ensureActive()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            progress(EngineProgress(EngineStage.COMPLETE, detail = "已安装官方组件，等待实际播放确认"))
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun available(folder: File, files: Map<String, String>, verifyContents: Boolean = false): Boolean =
        runCatching {
            if (!folder.isDirectory) return false
            val official = files.all { (path, digest) ->
                val file = File(folder, if (path == "libmpv-2.dll") "player/$path" else path)
                file.isFile && file.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath()) &&
                    (!verifyContents || engineSha256(file) == digest)
            }
            official && ("libmpv-2.dll" !in files || stockFiles().all { name ->
                val file = folder.resolve("player/$name")
                file.isFile && file.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath()) &&
                    (!verifyContents || MPVHandle::class.java.classLoader.getResourceAsStream(name)?.use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            digest.update(buffer, 0, length)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) } == engineSha256(file)
                    } == true)
            })
        }.getOrDefault(false)

    private fun stockFiles(): List<String> =
        checkNotNull(MPVHandle::class.java.classLoader.getResourceAsStream("mpv-natives-windows-x64.txt"))
            .bufferedReader().useLines { lines ->
                lines.map(String::trim).filter { it.isNotEmpty() }.onEach {
                    check(Regex("[A-Za-z0-9_.-]+\\.dll").matches(it))
                }.filter { it != "libmpv-2.dll" }.toList()
            }.also { check("mediampv.dll" in it) }

    private fun readiness(platform: Boolean, installed: Boolean) = when {
        !platform -> EngineReadiness.UNSUPPORTED
        installed -> EngineReadiness.READY_FOR_TRIAL
        else -> EngineReadiness.NOT_INSTALLED
    }

    class FilterSession(val directory: File, val filter: String, val stats: File, val weightName: String?,
        val interpolate: Boolean) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    private data class Weight(val path: String, val title: String, val bytes: Long, val sha256: String)
    companion object {
        const val VERSION = "3.12.3"
        const val RIFE_ID = "animejanai-live:rife-v4.22"
        const val SOURCE = "https://github.com/the-database/mpv-AnimeJaNai/releases/tag/$VERSION"
        private const val downloads = "https://github.com/the-database/mpv-AnimeJaNai/releases/download/$VERSION"
        private val portable = DesktopEnginePackage(EnhancementEngineIds.DESKTOP_REALTIME, VERSION, "",
            "$downloads/mpv-upscale-2x_animejanai-full-package-$VERSION.7z.001",
            "3f6466aca9da824735e6455fe025f7de3505c8e95012590b3539101814509413", 229_331_542)
        private val rifePack = DesktopEnginePackage(EnhancementEngineIds.DESKTOP_REALTIME_RIFE, VERSION, "",
            "$downloads/component-rife.7z", "ad673f26e4d5922448d128f19307c6609998a0cf64deea54b31f9e0959cfa99e", 462_444_574)
        private val videoWeights = linkedMapOf(
            "performance" to Weight("animejanai/onnx/2x_AnimeJaNai_HD_V3.1_Performance_SPANF3_b5f48_unshuffle_fp16.onnx",
                "AnimeJaNai HD V3.1 Performance", 740_318, "7b804d87320f37b7269d6e27796046fbd975e95e2aa1f65d5557f52a36282716"),
            "balanced" to Weight("animejanai/onnx/2x_AnimeJaNai_HD_V3.1_Balanced_SPANF3_b8f64_unshuffle_fp16.onnx",
                "AnimeJaNai HD V3.1 Balanced", 1_971_970, "b308e93c3dda3c4f9f968295b2b7b8d1c0583a711bca53a08d51134d9d1cbd5c"),
        )
        private val rifeWeight = Weight("animejanai/rife/rife_v4.22.onnx", "RIFE v4.22", 0,
            "77700a2395ac91d88bf1b886fb144e14cfbca9daf9312f2a460ddd2e7e0d0f8e")
        private val runtimeFiles = mapOf(
            "libmpv-2.dll" to "e4cecd94750a9eb23a8686fdd9e4a8dfcc8b63fbdcd6cfdcb03698193e9c9c58",
            "animejanai/inference/aji.dll" to "8f980e8aead754040331d8a0d13ca02f954b8033f2a811831079baf48e653a34",
            "animejanai/inference/aji_dml.dll" to "b6ff6f05bf9909074b97b481083fbd05e93a11573b351cb48b0c7c801729c02a",
            "animejanai/inference/DirectML.dll" to "9c9e6d822561c6c41b90e6994b3e8857cf1d66dbfb1e0c4c799c7c89b4e92da1",
            "animejanai/inference/onnxruntime.dll" to "e7eedec6a6f26dc39dc948276a75ef6d2bee3fff944d874ceed0bbd3b97bff40",
        ) + videoWeights.values.associate { it.path to it.sha256 }
        private val installLock = Mutex()
        private val processLock = Any()
        private var processStarted = false
        @Volatile private var selectedNative: File? = null
        @Volatile private var processIssue: String? = null
        private var pendingReceipt: File? = null

        private fun isWindows() = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64")
    }
}
