package dev.mangaflow.reader.panels

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import dev.mangaflow.data.Page
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates are disposable; downloaded originals, history and collections stay in their own stores. */
actual class PanelRepository actual constructor(context: Context) {
    private val context = context.applicationContext
    private val cache = File(this.context.cacheDir, "reader-panels")
    private val gate = Mutex()
    private val models = PanelModelRuntime(this.context)
    private var closed = false

    actual suspend fun detect(
        page: Page,
        headers: Map<String, String>,
        resolvePage: suspend (Page) -> Page,
        engine: PanelEngine,
        direction: PanelDirection,
        useLearnedOrder: Boolean,
        force: Boolean,
    ): PanelDetection = gate.withLock {
        check(!closed) { "分镜阅读已关闭" }
        if (engine == PanelEngine.OFF) return@withLock PanelDetection(listOf(PanelBox.FULL), engine.name)
        val key = withContext(Dispatchers.IO) { cacheKey(page, engine, direction, useLearnedOrder) }
        if (!force) withContext(Dispatchers.IO) { readCache(key) }?.let { return@withLock it }
        currentCoroutineContext().ensureActive()
        val resolved = resolvePage(page)
        val result = context.imageLoader.execute(ImageRequest.Builder(context)
            .data(if (resolved.url.startsWith("file:")) File(URI(resolved.url)) else resolved.url)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
            .size(1536, 1536).precision(Precision.EXACT).scale(Scale.FIT)
            // This analysis bitmap is owned by this call, not by the displayed original or Coil's memory cache.
            .allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).build())
        if (result is ErrorResult) throw IOException("这一页暂时无法识别，请重试", result.throwable)
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
            ?: throw IOException("这一页的图片格式暂不支持分镜")
        try {
            var actualEngine = engine
            var notice: String? = null
            var cacheable = true
            val raw = withContext(Dispatchers.Default) {
                if (engine == PanelEngine.CLASSIC) {
                    models.releaseDetectionModel()
                    ClassicPanelDetector.detect(bitmap.asImageBitmap())
                }
                else try { models.detect(bitmap, engine) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    actualEngine = PanelEngine.CLASSIC
                    models.releaseDetectionModel()
                    notice = "所选模型暂不可用，已使用传统识别"
                    cacheable = false
                    ClassicPanelDetector.detect(bitmap.asImageBitmap())
                }
            }
            currentCoroutineContext().ensureActive()
            val valid = raw.asSequence().mapNotNull { it.sanitized() }.distinct().take(MAX_BOXES).toList()
            var boxes = PanelOrdering.order(valid.ifEmpty { listOf(PanelBox.FULL) }, direction,
                isSpread = bitmap.width.toFloat() / bitmap.height > 1.2f)
            if (useLearnedOrder && boxes.size > 1) {
                try {
                    val ordered = models.order(boxes, direction)
                    // A sorting model may reorder detections; it must never silently drop artwork.
                    if (ordered.size != boxes.size || ordered.toSet() != boxes.toSet()) throw IOException("分镜排序结果不完整")
                    boxes = ordered
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    notice = listOfNotNull(notice, "顺序模型暂不可用，已使用版面顺序").joinToString("；")
                    cacheable = false
                }
            }
            if (boxes.size == 1 && (boxes.first() == PanelBox.FULL || valid.isEmpty())) {
                notice = listOfNotNull(notice, "没有找到可靠分镜，保留整页阅读").joinToString("；")
                boxes = listOf(PanelBox.FULL)
            }
            val detection = PanelDetection(boxes, actualEngine.name, notice)
            if (cacheable) withContext(Dispatchers.IO) { writeCache(key, detection) }
            detection
        } finally { bitmap.recycle() }
    }

    actual suspend fun close() = gate.withLock {
        closed = true
        models.close()
    }

    private fun cacheKey(page: Page, engine: PanelEngine, direction: PanelDirection, learned: Boolean): String {
        val local = if (page.url.startsWith("file:")) runCatching { File(URI(page.url)) }.getOrNull() else null
        val identity = listOf(SCHEMA, ClassicPanelDetector.VERSION, PanelModelRuntime.VERSION,
            page.url, page.index, page.width, page.height, local?.length(), local?.lastModified(), engine.name, direction.name, learned)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): PanelDetection? = runCatching {
        val file = File(cache, "$key.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return@runCatching null
        val json = JSONObject(file.readText())
        if (json.optInt("schema") != SCHEMA) return@runCatching null
        val values = json.getJSONArray("boxes")
        if (values.length() !in 1..MAX_BOXES) return@runCatching null
        val boxes = (0 until values.length()).map { index ->
            val value = values.getJSONArray(index)
            PanelBox(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(), value.getDouble(2).toFloat(),
                value.getDouble(3).toFloat(), value.getDouble(4).toFloat()).sanitized()
                ?: return@runCatching null
        }
        val engine = json.getString("engine")
        if (PanelEngine.entries.none { it.name == engine }) return@runCatching null
        file.setLastModified(System.currentTimeMillis())
        PanelDetection(boxes, engine, json.optString("notice").takeIf { it.isNotBlank() })
    }.getOrNull()

    private fun writeCache(key: String, result: PanelDetection) {
        // Cache storage failure must not take down an otherwise usable image or detector.
        runCatching {
            if (!cache.isDirectory && !cache.mkdirs()) return@runCatching
            val json = JSONObject().put("schema", SCHEMA).put("engine", result.engine)
                .put("notice", result.notice.orEmpty()).put("boxes", JSONArray().apply {
                    result.boxes.forEach { box -> put(JSONArray(listOf(box.left.toDouble(), box.top.toDouble(),
                        box.right.toDouble(), box.bottom.toDouble(), box.confidence.toDouble()))) }
                })
            val temporary = File(cache, "$key.tmp")
            val destination = File(cache, "$key.json")
            try {
                temporary.writeText(json.toString())
                if (!temporary.renameTo(destination)) return@runCatching
            } finally { temporary.delete() }
            val files = cache.listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }.orEmpty()
            var bytes = 0L
            files.forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= MAX_CACHED_PAGES || bytes > MAX_CACHE_BYTES) file.delete()
            }
        }
    }

    private companion object {
        const val SCHEMA = 1
        const val MAX_BOXES = 64
        const val MAX_CACHED_PAGES = 128
        const val MAX_CACHE_BYTES = 8L * 1024 * 1024
        const val MAX_FILE_BYTES = 64L * 1024
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
