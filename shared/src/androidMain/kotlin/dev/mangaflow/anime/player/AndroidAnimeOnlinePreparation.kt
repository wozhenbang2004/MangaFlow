@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Clock
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExperimentalAnalyzerModeFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dev.mangaflow.enhancement.engine.AndroidCuganSession
import dev.mangaflow.enhancement.engine.AndroidCuganVulkan
import dev.mangaflow.enhancement.engine.AndroidRgbVideoProgram
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.enhancement.engine.engineSha256
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AndroidPreparedFrame(val signature: Long, val data: ByteArray)

internal data class AndroidPreparedWindow(
    val request: AnimePreparedVideoRequest,
    val frames: Map<Long, AndroidPreparedFrame>,
    val receipt: AnimePreparedVideoWindow,
)

/** The speculative decoder uses the stream's existing route, cache keys and a hard read budget. */
internal suspend fun prepareAndroidAnimeWindow(
    context: Context,
    request: AnimePreparedVideoRequest,
): AndroidPreparedWindow = withContext(Dispatchers.IO) {
    val started = SystemClock.elapsedRealtime()
    require(request.endMs - request.startMs in 5_000L..15_000L) {
        "当前 Android 在线帧缓存支持 5–15 秒窗口；已保存的窗口目标保持不变"
    }
    val asset = request.asset
    val path = Uri.parse(asset.url).path.orEmpty()
    val mimeType = when {
        asset.mimeType?.contains("mpegurl", true) == true || path.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
        asset.mimeType == MimeTypes.VIDEO_MP4 || path.endsWith(".mp4", true) -> MimeTypes.VIDEO_MP4
        else -> error("当前在线预处理需要可剪辑的 MP4 或 HLS 点播线路")
    }
    val width = request.width
    val height = request.height
    val scale = request.model.nativeScale
    val outputWidth = Math.multiplyExact(width, scale)
    val outputHeight = Math.multiplyExact(height, scale)
    require(width >= SAMPLE_SIDE && height >= SAMPLE_SIDE &&
        outputWidth.toLong() * outputHeight <= MAX_OUTPUT_PIXELS && scale in setOf(1, 2, 4)) {
        "当前在线帧缓存输出 ${outputWidth}×${outputHeight}，超过此图形路径的 220 万像素容量，或输入尺寸/倍率不受支持"
    }
    val vulkan = request.model.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO
    val cugan = if (vulkan) AndroidCuganVulkan(context, File(context.noBackupFilesDir, "enhancement")) else null
    val weights = File(request.model.path).canonicalFile
    fun verifyModel() = cugan?.matches(request.model) ?: (engineSha256(weights) == request.model.sha256)
    check(verifyModel()) { "预处理模型已变化" }
    if (!vulkan) PanelRuntimeStore.get(context).ensureLoaded(PanelRuntimeId.ONNX)
    val owner = AndroidAnimeCache.get(context)
    val release = owner.acquire(asset)
    val client = animeMediaHttpClient(asset, forPrefetch = true)
    val output = File(context.cacheDir, "anime-forward-${UUID.randomUUID()}.mp4")
    val job = currentCoroutineContext()
    val captures = linkedMapOf<Long, AndroidPreparedFrame>()
    var bytes = 0L
    try {
        suspend fun captureWindow(session: OrtSession?, cuganSession: AndroidCuganSession?) {
            val upstream = OkHttpDataSource.Factory(client)
            val cache = animeCacheDataSource(asset, owner, upstream)
            val read = AtomicLong(0L)
            val data = DataSource.Factory {
                // HLS playlists/keys stay on their live network path; only progressive MP4 uses the media cache here.
                val delegate = if (mimeType == MimeTypes.VIDEO_MP4 && owner.targetBytes > 0)
                    cache.createDataSource() else upstream.createDataSource()
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        if (read.get() >= request.maximumSourceBytes) throw IOException("在线预处理读取达到预算")
                        return delegate.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val remaining = request.maximumSourceBytes - read.get()
                        if (remaining <= 0) throw IOException("在线预处理读取达到预算")
                        val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        if (count > 0 && read.addAndGet(count.toLong()) > request.maximumSourceBytes)
                            throw IOException("在线预处理读取达到预算")
                        return count
                    }
                }
            }
            val effect = GlEffect { _, isHdr ->
                if (isHdr) throw VideoFrameProcessingException("HDR 片源保持原画")
                val raw = ByteArray(outputWidth * outputHeight * 4)
                AndroidRgbVideoProgram(session, width, height, scale, 64L * 1024 * 1024, job::ensureActive,
                    capture = { time, input, enhanced ->
                        job.ensureActive()
                        check(captures.size < 900) { "在线帧缓存达到 900 帧容量；请缩短准备窗口" }
                        check(time !in captures) { "预处理帧时间不连续" }
                        enhanced.get(raw)
                        val compressed = compressFrame(raw)
                        check(bytes + compressed.size <= request.maximumResultBytes) { "在线帧缓存达到预算" }
                        bytes += compressed.size
                        captures[time] = AndroidPreparedFrame(frameSignature(input, width, height), compressed)
                    }, cugan = cuganSession, onFrame = {})
            }
            coroutineScope {
                val cancelRequests = launch(Dispatchers.IO) {
                    try { awaitCancellation() }
                    finally { client.cancelAll() }
                }
                try { exportWindow(context, request, mimeType, data, effect, output) }
                finally { cancelRequests.cancel() }
            }
        }
        if (cugan != null) {
            cugan.open(request.model).use { captureWindow(null, it) }
        } else {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                OrtEnvironment.getEnvironment().createSession(weights.path, options).use { captureWindow(it, null) }
            }
        }
        job.ensureActive()
        check(verifyModel() && captures.size >= 2) { "没有完整的预处理画面" }
        val times = captures.keys.toList()
        val startUs = request.startMs * 1000L
        val endUs = request.endMs * 1000L
        val first = times.first()
        val offset = when {
            first in startUs..(startUs + 250_000L) -> 0L
            first in 0L..250_000L -> startUs
            else -> error("预处理时间轴与原片不一致")
        }
        val lastUs = times.last() + offset
        check(times.zipWithNext().all { (a, b) -> b > a } && lastUs <= endUs + 50_000L &&
            lastUs >= endUs - 250_000L) { "在线增强窗口未完成" }
        AndroidPreparedWindow(request, captures.mapKeys { it.key + offset }, AnimePreparedVideoWindow(
            (first + offset) / 1000L, minOf(lastUs, endUs) / 1000L, captures.size, bytes, request.model.title, outputWidth, outputHeight,
            SystemClock.elapsedRealtime() - started,
        ))
    } finally {
        closeAnimeMediaHttpClient(client)
        release()
        output.delete()
    }
}

private suspend fun exportWindow(context: Context, window: AnimePreparedVideoRequest, mimeType: String,
    data: DataSource.Factory, effect: GlEffect, output: File) {
    val thread = HandlerThread("AnimeForwardFrames").apply { start() }
    val dispatcher = Handler(thread.looper).asCoroutineDispatcher("AnimeForwardFrames")
    var exporter: Transformer? = null
    val result = CompletableDeferred<Unit>()
    try {
        withContext(dispatcher) {
            val media = MediaItem.Builder().setUri(window.asset.url).setMimeType(mimeType)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(window.startMs).setEndPositionMs(window.endMs).build()).build()
            val edited = EditedMediaItem.Builder(media).setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf(effect))).build()
            val factory = ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(),
                Clock.DEFAULT, DefaultMediaSourceFactory(data))
            val configured = Transformer.Builder(context).setLooper(thread.looper).setAssetLoaderFactory(factory)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setVideoFrameProcessorFactory(DefaultVideoFrameProcessor.Factory.Builder()
                    .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        result.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        result.completeExceptionally(exportException)
                    }
                }).build()
            // The GL effect emits the lossless cache; encoding an unused copy competes with playback.
            exporter = ExperimentalAnalyzerModeFactory.buildAnalyzer(context, configured).also { it.start(edited, output.path) }
        }
        result.await()
    } finally {
        try { withContext(NonCancellable + dispatcher) { exporter?.cancel() } }
        finally { thread.quitSafely() }
    }
}

/** Switch only a matching decoded frame on the current GL stream. The original audio/clock never move. */
internal class AndroidPreparedFrameProgram(
    private val scale: Int,
    prepared: () -> AndroidPreparedWindow?,
    private val onUsed: (AndroidPreparedWindow, () -> Boolean) -> Unit,
) : BaseGlShaderProgram(false, 1) {
    private var program: GlProgram? = null
    private val frames = AndroidPreparedFrameRenderer(prepared)
    private val revision = AtomicLong(0L)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        revision.incrementAndGet()
        val scaledWidth = inputWidth.toLong() * scale
        val scaledHeight = inputHeight.toLong() * scale
        val withinBudget = scaledWidth * scaledHeight in 1L..MAX_OUTPUT_PIXELS
        val width = if (withinBudget) scaledWidth.toInt() else inputWidth
        val height = if (withinBudget) scaledHeight.toInt() else inputHeight
        frames.configure(inputWidth, inputHeight, width, height)
        if (program == null) program = GlProgram(POSITION_SHADER, COPY_SHADER).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val current = revision.get()
        val window = frames.draw(inputTexId, presentationTimeUs, ::copyTexture)
        if (window == null) copyTexture(inputTexId)
        else onUsed(window) { revision.get() == current }
    }

    private fun copyTexture(texture: Int) {
        val copy = checkNotNull(program)
        copy.use()
        copy.setSamplerTexIdUniform("uTexture", texture, 0)
        copy.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun flush() {
        revision.incrementAndGet()
        super.flush()
    }

    override fun release() {
        revision.incrementAndGet()
        try { super.release() } finally {
            try { program?.delete() } finally { frames.release() }
        }
    }
}

/** Reuses a cached result only against the original decoded frame, before any realtime filter. */
internal class AndroidPreparedFrameRenderer(private val prepared: () -> AndroidPreparedWindow?) {
    private var texture = 0
    private val fbo = IntArray(1)
    private val previousFramebuffer = IntArray(1)
    private val previousViewport = IntArray(4)
    private var inputWidth = 0
    private var inputHeight = 0
    private var width = 0
    private var height = 0
    private var decoded = ByteArray(0)
    private var rgba: ByteBuffer? = null
    private val sample = ByteBuffer.allocateDirect(SAMPLE_SIDE * SAMPLE_SIDE * 4)

    fun configure(inputWidth: Int, inputHeight: Int, outputWidth: Int, outputHeight: Int) {
        release()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        width = outputWidth
        height = outputHeight
    }

    /** Returns only after the matched texture has actually been submitted to the caller's output. */
    fun draw(inputTexId: Int, presentationTimeUs: Long, drawTexture: (Int) -> Unit): AndroidPreparedWindow? {
        if (inputWidth < SAMPLE_SIDE || inputHeight < SAMPLE_SIDE || width.toLong() * height !in 1L..MAX_OUTPUT_PIXELS)
            return null
        val window = prepared()?.takeIf {
            it.request.width == inputWidth && it.request.height == inputHeight &&
                it.receipt.outputWidth == width && it.receipt.outputHeight == height
        } ?: return null
        val frame = window.frames[presentationTimeUs] ?: return null
        if (!matchingSource(inputTexId, frame.signature) || !load(frame)) return null
        // Disabling, seeking or reattaching may happen while a compressed frame is being decoded.
        if (prepared() !== window) return null
        drawTexture(texture)
        GlUtil.checkGlError()
        return window
    }

    private fun matchingSource(inputTexId: Int, signature: Long): Boolean {
        if (fbo[0] == 0) {
            GLES20.glGenFramebuffers(1, fbo, 0)
            GlUtil.checkGlError()
        }
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return false
            sample.rewind()
            GLES20.glReadPixels((inputWidth - SAMPLE_SIDE) / 2, (inputHeight - SAMPLE_SIDE) / 2, SAMPLE_SIDE, SAMPLE_SIDE,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, sample)
            GlUtil.checkGlError()
            return frameSignature(sample, SAMPLE_SIDE, SAMPLE_SIDE) == signature
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        }
    }

    private fun load(frame: AndroidPreparedFrame): Boolean {
        if (decoded.isEmpty()) {
            decoded = ByteArray(width * height * 4)
            rgba = ByteBuffer.allocateDirect(decoded.size)
        }
        val inflater = Inflater()
        try {
            inflater.setInput(frame.data)
            var position = 0
            while (position < decoded.size && !inflater.finished()) {
                val count = inflater.inflate(decoded, position, decoded.size - position)
                if (count == 0) break
                position += count
            }
            if (position != decoded.size || !inflater.finished()) return false
        } catch (_: DataFormatException) {
            return false
        } finally { inflater.end() }
        val output = checkNotNull(rgba)
        output.clear()
        output.put(decoded)
        output.rewind()
        if (texture == 0) texture = GlUtil.createTexture(width, height, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, output)
        return true
    }

    fun release() {
        try {
            if (texture != 0) GlUtil.deleteTexture(texture)
        } finally {
            texture = 0
            try {
                if (fbo[0] != 0) GLES20.glDeleteFramebuffers(1, fbo, 0)
            } finally {
                fbo[0] = 0
                decoded = ByteArray(0)
                rgba = null
            }
        }
    }
}

private fun compressFrame(source: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    try {
        deflater.setInput(source)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            check(count > 0) { "未能保存预处理帧" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally { deflater.end() }
}

private const val SAMPLE_SIDE = 12
private const val MAX_OUTPUT_PIXELS = 2_200_000L
private fun frameSignature(bytes: ByteBuffer, width: Int, height: Int): Long {
    val x = (width - SAMPLE_SIDE) / 2
    val y = (height - SAMPLE_SIDE) / 2
    var hash = 1469598103934665603L
    for (row in y until y + SAMPLE_SIDE) for (column in x until x + SAMPLE_SIDE) {
        val p = (row * width + column) * 4
        for (channel in 0 until 3) hash = (hash xor (bytes.get(p + channel).toLong() and 255L)) * 1099511628211L
    }
    return hash
}

private const val POSITION_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
    gl_Position = aFramePosition;
    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

private const val COPY_SHADER = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
"""
