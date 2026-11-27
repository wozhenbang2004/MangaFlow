package dev.mangaflow.enhancement.engine

import dev.mangaflow.enhancement.EnhancedAssetIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openani.mediamp.mpv.MPVHandle

/** Downloads the official host and keeps it isolated from the bundled Mediamp runtime. */
internal class DesktopAnimeJaNai(private val root: File) {
    private val native = File(root, "animejanai-player-$HOST_VERSION")
    private val previousNative = File(root, "animejanai-player-3.12.3")
    private val rife = File(root, "rife-v4.22")
    private val previousRife = File(root, "animejanai-player-rife-3.12.3")

    private fun installedHost(digest: (File) -> String = ::engineSha256): File? = when {
        available(native, runtimeFiles, digest) -> native
        available(previousNative, previousRuntimeFiles, digest) -> previousNative
        else -> null
    }

    fun models(digest: (File) -> String): List<EngineModel> {
        val host = installedHost(digest) ?: return emptyList()
        return videoWeights.filterValues { host == native || it.path in previousRuntimeFiles }.map { (key, weight) ->
            EngineModel("animejanai-live:$key", EnhancementEngineIds.DESKTOP_REALTIME, weight.title,
                File(host, weight.path).absolutePath, weight.sha256, weight.scale, setOf(EngineContent.VIDEO),
                if (weight.temporal) "Apache-2.0 · 上游随包许可" else "上游 AnimeJaNai 权重许可", VERSION, weight.bytes)
        }
    }

    private fun installedRife(digest: (File) -> String = ::engineSha256) =
        listOf(rife, previousRife).firstOrNull { available(it, rifeFiles, digest) }

    fun frames(digest: (File) -> String): List<EngineModel> {
        val weight = installedRife(digest)?.resolve(rifeWeight.path) ?: return emptyList()
        return listOf(EngineModel(RIFE_ID, EnhancementEngineIds.DESKTOP_REALTIME_RIFE, rifeWeight.title,
            weight.absolutePath, rifeWeight.sha256, 1, setOf(EngineContent.VIDEO),
            "上游 RIFE 权重许可", VERSION, weight.length()))
    }

    fun runtimeDescriptor(supported: Boolean, models: List<EngineModel>): EngineDescriptor {
        val complete = models.any { it.id == "animejanai-live:temporalfix-s1" }
        return EngineDescriptor(
            EnhancementEngineIds.DESKTOP_REALTIME, "AnimeJaNai 实时超分 · DirectML", VERSION,
            "mpv GPL-2.0+；推理与权重按各自上游许可", SOURCE,
            readiness(supported, models.isNotEmpty()),
            if (models.isNotEmpty() && !complete) "已有内核可继续超分和补帧，更新组件可增加 TemporalFix。"
            else "可选 Direct3D 11 播放内核。安装后选择模型并重新打开应用；在实际视频获得输出后显示运行状态。",
            setOf(1, 2), setOf(EngineContent.VIDEO), canInstall = supported && !complete,
            realtime = true, downloadBytes = if (complete) 0L else portable.bytes,
        )
    }

    fun frameDescriptor(supported: Boolean, hostInstalled: Boolean, frameModels: List<EngineModel>): EngineDescriptor {
        val weightInstalled = frameModels.any { it.id == RIFE_ID }
        val reusable = frameModels.any {
            it.engineId == EnhancementEngineIds.VIDEOJANAI && it.sha256 == rifeWeight.sha256 &&
                File(it.path).name == File(rifeWeight.path).name
        }
        return EngineDescriptor(
            EnhancementEngineIds.DESKTOP_REALTIME_RIFE, "RIFE v4.22 实时补帧 · DirectML", VERSION,
            "上游 RIFE 权重许可", SOURCE, readiness(supported, hostInstalled && weightInstalled),
            when {
                weightInstalled && !hostInstalled -> "已找到校验通过的 RIFE 权重，只需准备播放内核。"
                !weightInstalled && reusable -> "可复用离线组件中校验通过的 RIFE 权重。首次启用后重新打开应用。"
                else -> "实时均衡补帧使用 AnimeJaNai 播放内核。首次启用组件后重新打开应用。"
            },
            setOf(1), setOf(EngineContent.VIDEO), canInstall = supported && (!hostInstalled || !weightInstalled),
            supportsInterpolation = true, realtime = true,
            downloadBytes = (if (hostInstalled) 0L else portable.bytes) + (if (weightInstalled || reusable) 0L else rifePack.bytes),
        )
    }

    suspend fun install(withRife: Boolean, progress: (EngineProgress) -> Unit): Unit = installLock.withLock {
        if (!withRife || installedHost() == null)
            installPack(portable, native, runtimeFiles, "mpv-upscale-2x_animejanai-v$VERSION/", progress)
        if (withRife && installedRife() == null && !reuseOfflineRife(progress))
            installPack(rifePack, rife, rifeFiles, "", progress)
    }

    private suspend fun reuseOfflineRife(progress: (EngineProgress) -> Unit): Boolean {
        val offline = DesktopEngineRuntime(root).folder(DesktopEnginePackages.video)
        if (!offline.isDirectory || !owned(offline)) return false
        val weight = offline.walkTopDown().maxDepth(9).onEnter(::owned).firstOrNull {
            it.name == File(rifeWeight.path).name && it.isFile && owned(it) && engineSha256(it) == rifeWeight.sha256
        } ?: return false
        val staging = Files.createTempDirectory(root.toPath(), "rife-weight-").toFile()
        try {
            val output = staging.resolve(rifeWeight.path)
            check(output.parentFile.mkdirs())
            Files.copy(weight.toPath(), output.toPath())
            check(engineSha256(output) == rifeWeight.sha256) { "补帧权重校验失败" }
            currentCoroutineContext().ensureActive()
            publish(staging, rife, rifeFiles)
            progress(EngineProgress(EngineStage.COMPLETE, detail = "复用已校验的 RIFE v4.22 权重"))
            return true
        } finally { staging.deleteRecursively() }
    }

    /** Mediamp loads this process-wide before its first player. A failed launch returns to stock on the next boot. */
    fun configureProcess() = synchronized(processLock) {
        if (processStarted) return@synchronized
        processStarted = true
        if (!isWindows()) return@synchronized
        val started = File(root, "animejanai-$HOST_VERSION-starting")
        if (started.exists()) {
            started.delete()
            processIssue = "上次原生内核未取得首帧，本次使用原有播放内核"
            return@synchronized
        }
        // Both spatial and motion options can be enabled after the first player has opened.
        val host = installedHost()
        if (host == null) {
            if (native.exists() || previousNative.exists()) processIssue = "播放内核校验未通过，请在增强组件中修复"
            return@synchronized
        }
        try {
            val player = preparePlayerDirectory(host)
            check(started.createNewFile())
            MPVHandle.setRuntimeLibraryDirectory(player.absolutePath, extractRuntimeLibrary = false)
            selectedNative = host
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

    fun usesNativeRuntime() = selectedNative == native || selectedNative == previousNative

    fun unavailableReason() = processIssue ?: "AnimeJaNai 内核需在重新打开应用后启用"

    fun onlineWindowUnavailableReason(): String? = when {
        usesNativeRuntime() -> null
        !isWindows() -> "当前平台没有在线增强窗口的播放内核"
        !native.isDirectory && !previousNative.isDirectory -> "在线增强的播放需要已安装的 AnimeJaNai 播放内核。当前使用原画或实时增强。"
        else -> processIssue ?: "重新打开应用后可启用支持在线增强窗口的 AnimeJaNai 内核"
    }

    fun session(model: EngineModel?, frame: EngineModel?, fraction: Pair<Int, Int>?): FilterSession? {
        val host = selectedNative?.takeIf { usesNativeRuntime() } ?: return null
        if (model == null && frame == null) return null
        val weight = model?.let { value -> videoWeights.entries.find { "animejanai-live:${it.key}" == value.id }?.value }
        if (model != null && (weight == null || weight.sha256 != model.sha256 ||
                host == previousNative && weight.path !in previousRuntimeFiles)) return null
        val frameHost = if (frame != null) {
            if (frame.id != RIFE_ID || frame.sha256 != rifeWeight.sha256 || fraction == null) return null
            installedRife() ?: return null
        } else null
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
                val modelName = weight?.let { File(it.path).nameWithoutExtension }
                if (weight != null && !weight.temporal) appendLine("chain_1_model_1_name=$modelName")
                else appendLine("chain_1_upscale=no")
                if (weight?.temporal == true) {
                    appendLine("chain_1_temporal_fix_model=$modelName")
                    appendLine("chain_1_temporal_fix_scene_threshold=0.150")
                }
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
            val filter = "@mangaflow-aji:animejanai:slot=1:lib=${path(host.resolve("animejanai/inference/aji.dll"))}" +
                ":conf=${path(conf)}:model-dir=${path(host.resolve("animejanai/onnx"))}" +
                (frameHost?.let { ":rife-model-dir=${path(it.resolve("animejanai/rife"))}" } ?: "") + ":stats=${path(stats)}"
            FilterSession(temporary, filter, stats, weight?.let { File(it.path).nameWithoutExtension },
                weight?.scale ?: 1, weight?.temporal == true, frame != null)
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            throw failure
        }
    }

    private suspend fun installPack(pack: DesktopEnginePackage, target: File, files: Map<String, String>, prefix: String,
        progress: (EngineProgress) -> Unit) {
        if (available(target, files)) {
            if (target == native) preparePlayerDirectory(target)
            return
        }
        check(isWindows() && (root.isDirectory || root.mkdirs()))
        check(owned(target)) { "组件目录已重定向" }
        val systemRoot = System.getenv("SystemRoot") ?: error("未找到 Windows 系统目录")
        val tar = File(systemRoot, "System32/tar.exe").canonicalFile
        check(tar.isFile) { "此 Windows 版本缺少系统组件 tar.exe" }
        DesktopEngineRuntime(root).withVerifiedArchive(pack, progress, { available(target, files) }) { archive ->
            val staging = Files.createTempDirectory(root.toPath(), "${pack.id}-").toFile()
            try {
                progress(EngineProgress(EngineStage.INSTALL, detail = "展开播放器和模型所需文件"))
                runEngineProcess(tar, listOf("-xf", archive.absolutePath, "-C", staging.absolutePath) +
                    files.keys.map { prefix + it }, staging, timeoutMillis = 180_000L)
                if (prefix.isNotEmpty()) {
                    val extracted = File(staging, prefix.removeSuffix("/"))
                    val player = File(staging, "player").apply { check(mkdir()) }
                    check(extracted.resolve("libmpv-2.dll").renameTo(player.resolve("libmpv-2.dll")))
                    check(extracted.resolve("License.txt").renameTo(staging.resolve("License.txt")))
                    check(extracted.resolve("animejanai").renameTo(staging.resolve("animejanai")))
                    check(extracted.delete())
                    preparePlayerDirectory(staging, fresh = true)
                }
                check(available(staging, files)) { "展开的原生文件校验失败" }
                currentCoroutineContext().ensureActive()
                publish(staging, target, files)
                progress(EngineProgress(EngineStage.COMPLETE, detail = "已安装官方组件，等待实际播放确认"))
            } finally { staging.deleteRecursively() }
        }
    }

    private fun owned(file: File): Boolean {
        val base = root.canonicalFile.toPath()
        val expected = base.resolve(file.absoluteFile.relativeTo(root.absoluteFile).path).normalize()
        return expected.startsWith(base) && expected == file.canonicalFile.toPath() &&
            !Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.toPath().toRealPath() == expected)
    }

    private fun member(folder: File, path: String) =
        folder.resolve(if (path == "libmpv-2.dll") "player/$path" else path)

    private fun available(folder: File, files: Map<String, String>, digest: (File) -> String = ::engineSha256) = runCatching {
        folder.isDirectory && owned(folder) && files.all { (path, expected) ->
            val file = member(folder, path)
            file.isFile && owned(file) && digest(file) == expected
        }
    }.getOrDefault(false)

    // Preserve valid members if a previous installation was interrupted or an unrelated file exists.
    private fun publish(staging: File, target: File, files: Map<String, String>): Unit = synchronized(processLock) {
        check(owned(target)) { "组件目录已重定向" }
        if (!target.exists()) {
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return@synchronized
            } catch (failure: java.nio.file.FileSystemException) {
                if (!target.isDirectory || !owned(target)) throw failure
            }
        }
        check(target.isDirectory) { "组件目录不可用" }
        for ((path, digest) in files) {
            val output = member(target, path)
            check(owned(output)) { "组件文件已重定向" }
            if (output.isFile && engineSha256(output) == digest) continue
            check(output.parentFile.isDirectory || output.parentFile.mkdirs())
            Files.move(member(staging, path).toPath(), output.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        if (target == native) preparePlayerDirectory(target)
    }

    // These DLLs belong to the app, while patched libmpv and the model weights belong to the pinned upstream.
    private fun preparePlayerDirectory(folder: File, fresh: Boolean = false): File = synchronized(processLock) {
        val original = folder.resolve("player")
        val mpv = "libmpv-2.dll"
        val expected = bundledLibraries + (mpv to runtimeFiles.getValue(mpv))
        fun knownDlls(directory: File) = directory.listFiles()?.all {
            !it.extension.equals("dll", ignoreCase = true) || it.name in expected
        } == true
        // Earlier processes may still be using the old DLL set. Leave it available to them.
        val player = if (fresh) original else {
            if (knownDlls(original) && bundledLibraries.all { (name, digest) ->
                    val file = original.resolve(name)
                    owned(file) && file.isFile && engineSha256(file) == digest
                }) return@synchronized original
            folder.resolve("player-$bundledIdentity").also {
                check(owned(it) && (it.isDirectory || it.mkdir() || it.isDirectory) && knownDlls(it)) { "播放器依赖目录不可用" }
            }
        }
        for ((name, digest) in expected) {
            val installed = File(player, name)
            check(owned(installed)) { "播放器依赖已重定向" }
            if (installed.isFile && engineSha256(installed) == digest) continue
            val part = Files.createTempFile(player.toPath(), ".mangaflow-", ".tmp")
            try {
                val input = if (name == mpv) original.resolve(name).inputStream() else resource(name)
                input.use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
                check(engineSha256(part.toFile()) == digest) { "播放器依赖校验失败：$name" }
                Files.move(part, installed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(part) }
        }
        player
    }

    private val bundledIdentity by lazy {
        EnhancedAssetIdentity.digest(bundledLibraries.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }).take(24)
    }

    private val bundledLibraries by lazy {
        checkNotNull(MPVHandle::class.java.classLoader.getResourceAsStream("mpv-natives-windows-x64.txt"))
            .bufferedReader().useLines { lines ->
                lines.map(String::trim).filter { it.isNotEmpty() }.onEach {
                    check(Regex("[A-Za-z0-9_.+-]+\\.dll").matches(it))
                }.filter { it != "libmpv-2.dll" }.toList()
            }.also { check("mediampv.dll" in it) }.associateWith { name ->
                val digest = MessageDigest.getInstance("SHA-256")
                resource(name).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        digest.update(buffer, 0, length)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
    }

    private fun resource(name: String) = MPVHandle::class.java.classLoader.getResourceAsStream(name)
        ?: error("安装包缺少播放器依赖 $name")

    private fun readiness(platform: Boolean, installed: Boolean) = when {
        !platform -> EngineReadiness.UNSUPPORTED
        installed -> EngineReadiness.READY_FOR_TRIAL
        else -> EngineReadiness.NOT_INSTALLED
    }

    class FilterSession(val directory: File, val filter: String, val stats: File, val weightName: String?,
        val scale: Int, val temporal: Boolean, val interpolate: Boolean) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    private data class Weight(val path: String, val title: String, val bytes: Long, val sha256: String,
        val scale: Int = 2, val temporal: Boolean = false)
    companion object {
        const val VERSION = "3.12.3"
        private const val HOST_VERSION = "$VERSION-2"
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
            "temporalfix-s1" to Weight("animejanai/onnx/temporalfix_s1_v1.1_op18_fp16.onnx",
                "TemporalFix S1 v1.1 · 1×", 1_200_722, "90deed0d4b672c918e112a760107455a9a1689db22b5b45311b23953ab3dd35f",
                scale = 1, temporal = true),
        )
        private val rifeWeight = Weight("animejanai/rife/rife_v4.22.onnx", "RIFE v4.22", 0,
            "77700a2395ac91d88bf1b886fb144e14cfbca9daf9312f2a460ddd2e7e0d0f8e")
        private val rifeFiles = mapOf(rifeWeight.path to rifeWeight.sha256)
        private val runtimeFiles = mapOf(
            "libmpv-2.dll" to "e4cecd94750a9eb23a8686fdd9e4a8dfcc8b63fbdcd6cfdcb03698193e9c9c58",
            "License.txt" to "af12b683565da402c7c5936c7c965229e5255002d5d5ffdd4ac5323b4d975558",
            "animejanai/inference/aji.dll" to "8f980e8aead754040331d8a0d13ca02f954b8033f2a811831079baf48e653a34",
            "animejanai/inference/aji_dml.dll" to "b6ff6f05bf9909074b97b481083fbd05e93a11573b351cb48b0c7c801729c02a",
            "animejanai/inference/DirectML.dll" to "9c9e6d822561c6c41b90e6994b3e8857cf1d66dbfb1e0c4c799c7c89b4e92da1",
            "animejanai/inference/DirectML_LICENSE.txt" to "a05138e3a085ff60a44881eedfa58dccb03ecc1d7b1f6ae888418e8c2fec4b8d",
            "animejanai/inference/onnxruntime.dll" to "e7eedec6a6f26dc39dc948276a75ef6d2bee3fff944d874ceed0bbd3b97bff40",
            "animejanai/onnx/vs_temporalfix_LICENSE.txt" to "e9069d4929f3d56d8b0722cc38f55106f32570b3ca67f2bbcc6c1f139e2557ea",
        ) + videoWeights.values.associate { it.path to it.sha256 }
        // The originally shipped 3.12.3 host predates the TemporalFix component and its license additions.
        private val previousRuntimeFiles = runtimeFiles - setOf("License.txt", "animejanai/inference/DirectML_LICENSE.txt",
            "animejanai/onnx/vs_temporalfix_LICENSE.txt", videoWeights.getValue("temporalfix-s1").path)
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
