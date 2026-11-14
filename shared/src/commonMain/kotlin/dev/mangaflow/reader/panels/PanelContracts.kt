package dev.mangaflow.reader.panels

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.data.Page
import kotlinx.coroutines.flow.StateFlow

const val PANEL_MODEL_RUNTIME_VERSION = "models-y26n40a285-k1152bfbbd4-orderfa7afb-v1"

enum class PanelModelId { NANO, HIGH_QUALITY, ORDER }

data class PanelModelState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
)

data class PanelModelSpec(
    val id: PanelModelId,
    val title: String,
    val description: String,
    val sizeLabel: String,
    val license: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val bytes: Long,
    val sha256: String,
    val revision: String,
    internal val fileName: String,
)

enum class PanelRuntimeId { LITERT, ONNX, QUICKJS, STEAM }

data class PanelRuntimeState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val loaded: Boolean = false,
    val pendingRemoval: Boolean = false,
)

data class PanelRuntimeSpec(
    val id: PanelRuntimeId,
    val title: String,
    val version: String,
    val description: String,
    val license: String,
)

expect class PanelRepository(context: PlatformContext) {
    suspend fun detect(page: Page, headers: Map<String, String>, resolvePage: suspend (Page) -> Page,
        engine: PanelEngine, direction: PanelDirection, useLearnedOrder: Boolean,
        force: Boolean = false): PanelDetection
    suspend fun close()
}

expect class PanelRuntimeStore {
    val abi: String?
    val states: StateFlow<Map<PanelRuntimeId, PanelRuntimeState>>
    fun spec(id: PanelRuntimeId): PanelRuntimeSpec
    suspend fun download(id: PanelRuntimeId)
    fun cancel(id: PanelRuntimeId)
    suspend fun remove(id: PanelRuntimeId)
    companion object { fun get(context: PlatformContext): PanelRuntimeStore }
}

internal val PANEL_MODEL_SPECS = listOf(
            PanelModelSpec(
                id = PanelModelId.NANO,
                title = "轻量 · YOLO26 Nano",
                description = "本机识别，适合手机；需单独下载。模型为 AGPL-3.0，来源及条款见许可说明。",
                sizeLabel = "2.71 MiB",
                license = "AGPL-3.0 · Manga109-s 训练来源",
                sourceUrl = "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n",
                downloadUrl = "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/40a2854663d537563cfb95c370288a84c6505b9a/manga_panel_detector_int8.tflite",
                bytes = 2_837_823,
                sha256 = "b1a7d8d4492e04a777ae0d3efd9dc1fbd6e8f361971eadb813279ce3dfd1b464",
                revision = "40a2854663d537563cfb95c370288a84c6505b9a",
                fileName = "yolo26-nano-40a28546.tflite",
            ),
            PanelModelSpec(
                id = PanelModelId.HIGH_QUALITY,
                title = "高精度 · Koharu 1152",
                description = "本机 CPU 识别，内存与耗电较高。旧版 1152 模型采用自定义条款，保留 Manga109 训练数据声明；不是新版 HighRes 1920。",
                sizeLabel = "141.57 MiB",
                license = "自定义 / other · 请阅读模型与 Manga109 条款",
                sourceUrl = "https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx",
                downloadUrl = "https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx/resolve/bfbbd4e5ab34a50459865074fa044da496cebb57/rfdetr-seg-2xlarge.onnx",
                bytes = 148_442_003,
                sha256 = "7cc10d4316371946b8441da3512261a8e148b129abcdb0ea6235ed1d1d06d351",
                revision = "bfbbd4e5ab34a50459865074fa044da496cebb57",
                fileName = "koharu-1152-bfbbd4e5.onnx",
            ),
            PanelModelSpec(
                id = PanelModelId.ORDER,
                title = "智能顺序 · Inkwell",
                description = "仅根据分镜位置预测顺序，不识别画面内容；可配合任意识别模式，支持手动左右阅读方向。",
                sizeLabel = "101 KiB",
                license = "MIT · 仅顺序模型，不含其 AGPL 检测模型",
                sourceUrl = "https://huggingface.co/cedarrapidsboy/inkwell-panel-models",
                downloadUrl = "https://huggingface.co/cedarrapidsboy/inkwell-panel-models/resolve/fa7afba52ba7dc7b0840c7702b767972b5e17a9b/panel-order-model.onnx",
                bytes = 103_294,
                sha256 = "84eb78fc5ec31a3e986a7a16ce10f7baa657ef5c5b4a98877b40aa9354ff3464",
                revision = "fa7afba52ba7dc7b0840c7702b767972b5e17a9b",
                fileName = "inkwell-order-fa7afba5.onnx",
            ),
        ).associateBy { it.id }
