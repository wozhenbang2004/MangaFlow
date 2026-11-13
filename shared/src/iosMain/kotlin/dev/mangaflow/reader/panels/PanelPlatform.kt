package dev.mangaflow.reader.panels

import dev.mangaflow.data.Page
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.dataDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun panelModelDirectory(context: PlatformContext): StorageFile = StorageFile(dataDirectory(context), "panel-models")

actual class PanelRepository actual constructor(context: PlatformContext) {
    private val delegate = PortablePanelRepository(context)
    actual suspend fun detect(page: Page, headers: Map<String, String>, resolvePage: suspend (Page) -> Page,
        engine: PanelEngine, direction: PanelDirection, useLearnedOrder: Boolean, force: Boolean): PanelDetection =
        delegate.detect(page, headers, resolvePage, engine, direction, useLearnedOrder, force)
    actual suspend fun close() { delegate.close() }
}

/** Android .so catalogs are never downloaded or reported as loadable on another OS. */
actual class PanelRuntimeStore private constructor() {
    actual val abi: String? = null
    actual val states: StateFlow<Map<PanelRuntimeId, PanelRuntimeState>> = MutableStateFlow(
        PanelRuntimeId.entries.associateWith { id -> PanelRuntimeState(error = if (id == PanelRuntimeId.QUICKJS)
            "当前平台尚未提供漫画源脚本引擎" else "当前平台尚未提供可选 AI 运行组件；传统分镜可正常使用") }
    ).asStateFlow()
    actual fun spec(id: PanelRuntimeId): PanelRuntimeSpec = when (id) {
        PanelRuntimeId.LITERT -> PanelRuntimeSpec(id, "轻量 AI 运行库", "1.4.2", "当前平台尚未提供；可使用传统分镜", "Apache-2.0")
        PanelRuntimeId.ONNX -> PanelRuntimeSpec(id, "高精度与排序运行库", "1.31.1", "当前平台尚未提供；可使用传统分镜", "MIT")
        PanelRuntimeId.QUICKJS -> PanelRuntimeSpec(id, "漫画源脚本引擎", "1.0.18", "当前平台尚未提供此脚本运行时", "Apache-2.0 / MIT")
    }
    actual suspend fun download(id: PanelRuntimeId): Unit = error("当前平台尚未提供经过校验的可选运行组件")
    actual fun cancel(id: PanelRuntimeId) { /* This platform never starts an unavailable runtime transfer. */ }
    actual suspend fun remove(id: PanelRuntimeId): Unit = error("当前平台未安装可选运行组件")
    actual companion object {
        private val instance = PanelRuntimeStore()
        actual fun get(context: PlatformContext): PanelRuntimeStore = instance
    }
}
