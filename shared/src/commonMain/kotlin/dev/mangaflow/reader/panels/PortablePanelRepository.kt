package dev.mangaflow.reader.panels

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.compose.asPainter
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import dev.mangaflow.data.Page
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.reader.readerFilePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Actual traditional detection on native platforms, with an explicit optional-runtime capability. */
internal class PortablePanelRepository(private val context: PlatformContext) {
    private val gate = Mutex()
    private val cache = StorageFile(cacheDirectory(context), "reader-panels")
    private var closed = false
    suspend fun detect(page: Page, headers: Map<String, String>, resolvePage: suspend (Page) -> Page,
        engine: PanelEngine, direction: PanelDirection, useLearnedOrder: Boolean, force: Boolean): PanelDetection = gate.withLock {
        check(!closed) { "分镜阅读已关闭" }
        if (engine == PanelEngine.OFF) return@withLock PanelDetection(listOf(PanelBox.FULL), engine.name)
        val key = withContext(Dispatchers.IO) { cacheKey(page, engine, direction, useLearnedOrder) }
        if (!force) withContext(Dispatchers.IO) { readCache(key) }?.let { return@withLock it }
        val resource = resolvePage(page)
        val imageContext = imagePlatformContext(context)
        val result = SingletonImageLoader.get(imageContext).execute(ImageRequest.Builder(imageContext).data(resource.url)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
            .size(1536, 1536).precision(Precision.EXACT).scale(Scale.FIT)
            .memoryCachePolicy(CachePolicy.DISABLED).build())
        if (result is ErrorResult) throw IllegalStateException("这一页暂时无法识别，请重试", result.throwable)
        val image = (result as? SuccessResult)?.image ?: error("这一页的图片格式暂不支持分镜")
        check(image.width > 0 && image.height > 0) { "分镜图片尺寸无效" }
        val ratio = minOf(1f, 1536f / maxOf(image.width, image.height))
        val width = (image.width * ratio).toInt().coerceAtLeast(1)
        val height = (image.height * ratio).toInt().coerceAtLeast(1)
        val bitmap = ImageBitmap(width, height)
        val painter = image.asPainter(imageContext)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            with(painter) { draw(size = size) }
        }
        val raw = withContext(Dispatchers.Default) { ClassicPanelDetector.detect(bitmap) }
        currentCoroutineContext().ensureActive()
        val valid = raw.mapNotNull { it.sanitized() }.distinct().take(64)
        val boxes = PanelOrdering.order(valid.ifEmpty { listOf(PanelBox.FULL) }, direction,
            isSpread = width.toFloat() / height > 1.2f)
        val notices = buildList {
            if (engine != PanelEngine.CLASSIC) add("当前平台尚未提供所选 AI 运行组件，已使用传统识别")
            if (useLearnedOrder) add("当前平台尚未提供 AI 排序组件，已使用几何顺序")
            if (boxes.size == 1) add("没有找到可靠分镜，保留整页阅读")
        }
        val detection = PanelDetection(boxes, PanelEngine.CLASSIC.name, notices.takeIf { it.isNotEmpty() }?.joinToString("；"))
        withContext(Dispatchers.IO) { writeCache(key, detection) }
        detection
    }
    suspend fun close() = gate.withLock { closed = true }

    private fun cacheKey(page: Page, engine: PanelEngine, direction: PanelDirection, learned: Boolean): String {
        val local = readerFilePath(page.url)?.let(::StorageFile)
        val identity = listOf(1, ClassicPanelDetector.VERSION, PANEL_MODEL_RUNTIME_VERSION, page.url,
            page.index, page.width, page.height, local?.length(), local?.lastModified(), engine.name, direction.name, learned)
            .joinToString("\u0000")
        return sha256(identity.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun readCache(key: String): PanelDetection? = runCatching {
        val file = StorageFile(cache, "$key.json")
        if (!file.isFile || file.length() !in 1..65_536 || wallTimeMillis() - file.lastModified() > 2_592_000_000L) return@runCatching null
        val json = JSONObject(file.readText())
        if (json.optInt("schema") != 1 || json.optString("engine") != PanelEngine.CLASSIC.name) return@runCatching null
        val values = json.getJSONArray("boxes")
        if (values.length() !in 1..64) return@runCatching null
        val boxes = (0 until values.length()).map { index ->
            val value = values.getJSONArray(index)
            PanelBox(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(), value.getDouble(2).toFloat(),
                value.getDouble(3).toFloat(), value.getDouble(4).toFloat()).sanitized() ?: return@runCatching null
        }
        PanelDetection(boxes, PanelEngine.CLASSIC.name, json.optString("notice").takeIf { it.isNotBlank() })
    }.getOrNull()
    private fun writeCache(key: String, result: PanelDetection) {
        runCatching {
            check(cache.isDirectory || cache.mkdirs())
            val json = JSONObject().put("schema", 1).put("engine", result.engine).put("notice", result.notice.orEmpty())
                .put("boxes", JSONArray().apply { result.boxes.forEach { box ->
                    put(JSONArray(listOf(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble(), box.confidence.toDouble())))
                } })
            val atomic = StorageAtomicFile(StorageFile(cache, "$key.json"))
            val output = atomic.startWrite()
            try { output.write(json.toString().encodeToByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            var bytes = 0L
            cache.listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
                ?.forEachIndexed { index, file -> bytes += file.length(); if (index >= 128 || bytes > 8L * 1024 * 1024) file.delete() }
        }
    }
}
