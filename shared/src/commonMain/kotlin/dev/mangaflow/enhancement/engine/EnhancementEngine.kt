package dev.mangaflow.enhancement.engine

import dev.mangaflow.platform.PlatformContext

/** Capability is separate from a user's desired preset and from successful device qualification. */
enum class EngineReadiness { BUILT_IN, NOT_INSTALLED, NEEDS_MODELS, READY_FOR_TRIAL, UNSUPPORTED, FAILED }
enum class EngineContent { BLACK_WHITE, COLOR, WEBTOON, VIDEO }
enum class EngineStage { DOWNLOAD, VERIFY, INSTALL, MODEL_LOAD, PROCESS, ENCODE, VALIDATE, COMPLETE }
enum class EngineVideoContainer(val extension: String, val mimeType: String) {
    MATROSKA("mkv", "video/x-matroska"), MP4("mp4", "video/mp4"),
}

data class EngineDescriptor(
    val id: String,
    val title: String,
    val version: String,
    val license: String,
    val sourceUrl: String,
    val readiness: EngineReadiness,
    val detail: String,
    val supportedScales: Set<Int>,
    val contents: Set<EngineContent>,
    val canInstall: Boolean = false,
    val supportsInterpolation: Boolean = false,
    val realtime: Boolean = false,
    val downloadBytes: Long? = null,
    val videoOutput: EngineVideoContainer? = null,
)

/** sha256 is measured from these installed weights, never inferred from a model's name. */
data class EngineModel(
    val id: String,
    val engineId: String,
    val title: String,
    val path: String,
    val sha256: String,
    val nativeScale: Int,
    val contents: Set<EngineContent>,
    val license: String,
    val version: String,
    val bytes: Long,
    val inputType: String = "NCHW_RGB_FLOAT",
)

data class EngineInventory(
    val engines: List<EngineDescriptor>,
    val models: List<EngineModel> = emptyList(),
    val modelBytes: Long = 0,
    val runtimeBytes: Long = 0,
    val frameModels: List<EngineModel> = emptyList(),
)


data class EnhancementModelOffer(
    val id: String,
    val title: String,
    val detail: String,
    val engineId: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeLabel: String,
    val scale: Int,
    val contents: Set<EngineContent>,
    val license: String,
)

/** Pinned published model files. Installation is not proof of runtime compatibility. */
val enhancementModelOffers = listOf(
    EnhancementModelOffer(
        "animesharp-v2-mosr-soft-fp32", "AnimeSharp V2 · 柔和",
        "2× 动漫插画与压缩修复；非商用条款，首次试用后确认手机性能",
        EnhancementEngineIds.ANDROID_ONNX,
        "https://huggingface.co/Kim2091/AnimeSharpV2",
        "https://huggingface.co/Kim2091/AnimeSharpV2/resolve/62e044a759142d60eae771c232dc70bf4cbec6c9/2x-AnimeSharpV2_MoSR_Soft_fp32.onnx",
        "53164f683476d2013752867979cfc8018a5fa4c0dcb7a0ffdf207581fbe67ff7",
        "约 17.4 MB", 2, setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON),
        "CC BY-NC-SA 4.0 · 非商用",
    ),
    EnhancementModelOffer(
        "realesrgan-anime-6b-fp32", "Real-ESRGAN · Anime 6B",
        "4× 动漫与插画超分；黑白漫画的文字、网点与清晰度需本机验证",
        EnhancementEngineIds.ANDROID_ONNX,
        "https://huggingface.co/mhmtaufiq/realesrgan-onnx",
        "https://huggingface.co/mhmtaufiq/realesrgan-onnx/resolve/c524eb8/RealESRGAN_x4plus_anime_6B.onnx",
        "82f458db35dd94f2200f9a32fd0232c581da861c1b2e5c956d5574b0e9c5aea2",
        "约 18.4 MB", 4, setOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON),
        "Real-ESRGAN BSD-3-Clause · 第三方 ONNX 导出",
    ),
)

/** FPS is completed processing throughput, never display FPS or hardware GPU utilization. */
data class EngineProgress(
    val stage: EngineStage,
    val completed: Long = 0,
    val total: Long? = null,
    val processingFps: Double? = null,
    val detail: String? = null,
    val bytes: Long? = null,
    val failed: Boolean = false,
)

data class EngineImageRequest(
    val sourcePath: String,
    /** A new, caller-owned temporary path. The engine refuses source aliases and existing output. */
    val destinationPath: String,
    val engineId: String = EnhancementEngineIds.LOCAL_DETAIL,
    val content: EngineContent = EngineContent.BLACK_WHITE,
    val scale: Int = 1,
    val model: EngineModel? = null,
    val tileSize: Int = 256,
    val overlap: Int = 32,
    /** Maximum encoded result size; never a limit on uncompressed working pixels. */
    val maximumOutputBytes: Long = 256L * 1024 * 1024,
    /** Explicit tile/row-buffer admission budget, not measured model RAM or VRAM. */
    val maximumWorkingBytes: Long = maximumOutputBytes,
)

data class EngineVideoRequest(
    val sourcePath: String,
    val destinationPath: String,
    val workDirectory: String,
    val model: EngineModel? = null,
    val scale: Int = 2,
    /** Null keeps the original timebase; a positive value requests RIFE interpolation. */
    val targetFps: Double? = null,
    /** Exact multiplier resolved against measured source FPS; mutually exclusive with targetFps. */
    val frameMultiplier: Int? = null,
    val rifeVersion: String = "4.22",
    val rifeEnsemble: Boolean = false,
    val expectedRifeSha256: String? = null,
    val sceneThreshold: Double = 0.15,
    val encoder: String = "nvenc-hevc",
    /** Checkpointing is supported only when the installed engine has exact segment support. */
    val segmentSeconds: Int = 0,
)

data class EngineVideoMetadata(
    val durationMillis: Long,
    val fps: Double?,
    val audioTracks: Int,
    val subtitleTracks: Int,
    val chapters: Int,
    val interpolatedFrames: Int? = null,
)

data class EngineOutput(
    val path: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val bytes: Long,
    val engineId: String,
    val engineVersion: String,
    val modelSha256: String,
    /** End-to-end elapsed wall time, explicitly not GPU execution time. */
    val elapsedMillis: Long,
    val gpuExecutionMillis: Double? = null,
    val video: EngineVideoMetadata? = null,
)

object EnhancementEngineIds {
    const val LOCAL_DETAIL = "local-detail-v1"
    const val LOCAL_DETAIL_VERSION = "1.0.0"
    /** Algorithm identity, not a claim that this non-neural filter has trained weights. */
    const val LOCAL_DETAIL_DIGEST = "a9241c52aac7eecfb6b4ced9fdd587004da45864954d528047406ce3aa09b620"
    const val ANDROID_ONNX = "android-onnx"
    const val ANDROID_CUGAN = "android-realcugan-vulkan"
    const val ANDROID_CUGAN_VIDEO = "android-realcugan-video-vulkan"
    const val ANDROID_CUGAN_TILE_SIZE = 96
    const val ANDROID_CUGAN_PADDING = 18
    const val ANDROID_VIDEO_ONNX = "android-video-onnx"
    const val ANDROID_VIDEO_REALTIME = "android-video-nnapi"
    const val MANGAJANAI = "mangajanai-cli"
    const val VIDEOJANAI = "videojanai-cli"
    const val ANDROID_RIFE = "android-rife"
    const val ANDROID_RIFE_VULKAN = "android-rife-vulkan"
    const val DESKTOP_REALTIME = "animejanai-realtime"
    const val DESKTOP_REALTIME_RIFE = "animejanai-realtime-rife"
    const val DESKTOP_ARTCNN = "artcnn-glsl"
    const val ANIME4K_SMALL = "anime4k-small"
    const val ANIME4K_MEDIUM = "anime4k-medium"
}

internal fun anime4kEngineDescriptors(): List<EngineDescriptor> = listOf(
    EnhancementEngineIds.ANIME4K_SMALL to "S/S", EnhancementEngineIds.ANIME4K_MEDIUM to "M/M",
).map { (id, profile) ->
    EngineDescriptor(id, "Anime4K $profile", "7684e9586f8d", "MIT",
        "https://github.com/bloc97/Anime4K/tree/7684e9586f8dcc738af08a1cdceb024cc184f426",
        EngineReadiness.BUILT_IN, "1× 修复 / 2× 修复与放大；运行效果和长期负载需以此设备的真实观测为准",
        setOf(1, 2), setOf(EngineContent.VIDEO), realtime = true)
}

data class EngineImageInfo(val width: Int, val height: Int, val content: EngineContent)

/** Does not own a competing resource governor: all work must be admitted by EnhancementScheduler. */
interface EnhancementEngine {
    suspend fun inspect(): EngineInventory
    suspend fun install(engineId: String, progress: (EngineProgress) -> Unit = {}): EngineInventory
    /** Imports data weights only; native executables are accepted only through pinned installers. */
    suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel
    suspend fun downloadModel(offer: EnhancementModelOffer, progress: (EngineProgress) -> Unit = {}): EngineModel =
        error("当前平台没有适配此模型的直接下载流程")
    /** Reads real image dimensions and sampled color; geometry alone never classifies a page as adult. */
    suspend fun describeImage(sourcePath: String): EngineImageInfo
    suspend fun processImage(request: EngineImageRequest, progress: (EngineProgress) -> Unit = {}): EngineOutput
    suspend fun processVideo(request: EngineVideoRequest, progress: (EngineProgress) -> Unit = {}): EngineOutput
    suspend fun close()
}

expect fun createEnhancementEngine(context: PlatformContext): EnhancementEngine
