package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.EngineContent
import dev.mangaflow.enhancement.engine.EngineDescriptor
import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.enhancement.engine.EngineProgress
import dev.mangaflow.enhancement.engine.EngineReadiness
import dev.mangaflow.enhancement.engine.EngineStage
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.dataDirectory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** Optional original Anime4K code/weights, versioned and individually verified before a renderer reads them. */
internal class Anime4KOptionalModels(private val context: PlatformContext) {
    private val root get() = StorageFile(dataDirectory(context), "enhancement/anime4k/${Anime4KShaderLibrary.UPSTREAM_COMMIT}")

    fun descriptors(): List<EngineDescriptor> = profiles.map { profile ->
        val installed = profile.shaders.all { verifies(modelFile(profile, it), it) }
        EngineDescriptor(profile.id, "Anime4K ${profile.size}/${profile.size}", VERSION, "MIT", SOURCE,
            if (installed) EngineReadiness.READY_FOR_TRIAL else EngineReadiness.NOT_INSTALLED,
            "Anime4K 实时系列 · 1× 线条修复或 2× 修复及放大。实际运行取决于设备、分辨率和 GPU 预算。",
            setOf(1, 2), setOf(EngineContent.VIDEO), canInstall = !installed, realtime = true,
            downloadBytes = profile.shaders.sumOf { it.bytes })
    }

    fun installed(): List<EngineModel> = profiles.mapNotNull { profile ->
        val path = folder(profile)
        if (!profile.shaders.all { verifies(modelFile(profile, it), it) })
            return@mapNotNull null
        EngineModel(profile.id, profile.id, "Anime4K ${profile.size}/${profile.size}", path.canonicalPath, profile.digest,
            2, setOf(EngineContent.VIDEO), "MIT", VERSION, profile.shaders.sumOf { it.bytes }, "GLSL")
    }

    suspend fun install(id: String, progress: (EngineProgress) -> Unit): Unit = installLock.withLock {
        val profile = profiles.single { it.id == id }
        if (profile.shaders.all { verifies(modelFile(profile, it), it) }) return@withLock
        val destination = folder(profile)
        val temporary = StorageFile(root, "download-${randomUuid()}")
        check(root.isDirectory || root.mkdirs())
        check(temporary.mkdir())
        val client = NetworkClient(route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.AI_MODELS))
        val total = profile.shaders.sumOf { it.bytes }
        try {
            var received = 0L
            for (file in profile.shaders) {
                val request = Request.Builder().url("$RAW/${file.directory}/${file.name}").get().build()
                val response = client.execute(request, maximumBytes = file.bytes)
                check(response.isSuccessful && response.bytes.size.toLong() == file.bytes &&
                    response.bytes.toByteString().sha256().hex() == file.sha256) { "Anime4K 官方资源校验失败" }
                StorageFile(temporary, file.name).writeBytes(response.bytes)
                received += file.bytes
                progress(EngineProgress(EngineStage.VERIFY, received, total, detail = "已核对官方大小与摘要", bytes = received))
            }
            check(!destination.exists() || destination.deleteRecursively())
            check(temporary.renameTo(destination)) { "无法安装已校验的 Anime4K 资源" }
            progress(EngineProgress(EngineStage.COMPLETE, detail = "可选实时模型已安装"))
        } finally {
            client.close()
            temporary.deleteRecursively()
        }
    }

    fun shaders(model: EngineModel, outputScale: Int): List<Anime4KShader> {
        require(outputScale in 1..2)
        val profile = profiles.single { model.id == it.id && model.engineId == it.id && model.sha256 == it.digest }
        require(model.path == folder(profile).canonicalPath)
        return profile.shaders.take(outputScale).map { asset ->
            val file = modelFile(profile, asset)
            check(file.isFile && file.length() == asset.bytes) { "已安装 Anime4K 资源发生变化" }
            val bytes = file.readBytes()
            check(bytes.size.toLong() == asset.bytes && bytes.toByteString().sha256().hex() == asset.sha256) {
                "已安装 Anime4K 资源发生变化"
            }
            Anime4KShader(asset.name, bytes.decodeToString())
        }
    }

    private fun folder(profile: Profile) = StorageFile(root, profile.id)
    private fun modelFile(profile: Profile, shader: Shader) = StorageFile(folder(profile), shader.name)
    private fun verifies(file: StorageFile, shader: Shader) =
        file.isFile && file.length() == shader.bytes && file.readBytes().toByteString().sha256().hex() == shader.sha256

    private data class Shader(val name: String, val directory: String, val bytes: Long, val sha256: String)
    private data class Profile(val size: String, val shaders: List<Shader>) {
        val id = "anime4k-optional-${size.lowercase()}"
        val digest = shaders.joinToString("|") { it.sha256 }.encodeUtf8().sha256().hex()
    }

    companion object {
        private const val VERSION = "7684e9586f8d"
        private const val SOURCE = "https://github.com/bloc97/Anime4K/tree/${Anime4KShaderLibrary.UPSTREAM_COMMIT}"
        private const val RAW = "https://raw.githubusercontent.com/bloc97/Anime4K/${Anime4KShaderLibrary.UPSTREAM_COMMIT}/glsl"
        private val installLock = Mutex()
        private val profiles = listOf(
            profile("L", 69_921, "d6efe215e6ee8af1ec560478a91afc1df83fac4ba43b2c806ee61ca2267ed674",
                73_443, "db1fedf7be82f6fd9034e6bf39b64daf2b7576988bb584ec38f24f5236b1cd97"),
            profile("VL", 144_075, "35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e",
                146_743, "5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d"),
            profile("UL", 308_660, "81ec48ff700108c8e1571ed82d4527006ee200c05e94f8b7f836c02179169b40",
                290_257, "fa7cf0ecc1cca84d8291bbff5a42b60f5816d57b4e97d42bc377235ac8db02e8"),
        )

        fun supports(id: String) = profiles.any { it.id == id }

        private fun profile(size: String, restoreBytes: Long, restoreSha: String, upscaleBytes: Long, upscaleSha: String) =
            Profile(size, listOf(
                Shader("Anime4K_Restore_CNN_$size.glsl", "Restore", restoreBytes, restoreSha),
                Shader("Anime4K_Upscale_CNN_x2_$size.glsl", "Upscale", upscaleBytes, upscaleSha),
            ))
    }
}
