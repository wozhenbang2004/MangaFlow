package dev.mangaflow.enhancement.engine

import dev.mangaflow.platform.PlatformContext

/** V1.0 targets Android and Windows. Keep the shared API linkable without claiming an iOS backend. */
actual fun createEnhancementEngine(context: PlatformContext): EnhancementEngine = object : EnhancementEngine {
    override suspend fun inspect() = EngineInventory(listOf(EngineDescriptor("ios-unavailable", "画质预处理", "未支持", "",
        "https://github.com/wozhenbang2004/MangaFlow", EngineReadiness.UNSUPPORTED,
        "本次增强系统面向 Android 与 Windows；当前设备继续使用原画", emptySet(), emptySet())))
    override suspend fun install(engineId: String, progress: (EngineProgress) -> Unit): EngineInventory =
        throw UnsupportedOperationException("当前平台没有本次增强组件")
    override suspend fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): EngineModel =
        throw UnsupportedOperationException("当前平台没有模型导入运行路径")
    override suspend fun describeImage(sourcePath: String): EngineImageInfo =
        throw UnsupportedOperationException("当前平台没有本次图像预处理路径")
    override suspend fun processImage(request: EngineImageRequest, progress: (EngineProgress) -> Unit): EngineOutput =
        throw UnsupportedOperationException("当前平台继续显示原图")
    override suspend fun processVideo(request: EngineVideoRequest, progress: (EngineProgress) -> Unit): EngineOutput =
        throw UnsupportedOperationException("当前平台继续播放原视频")
    override suspend fun close() = Unit
}
