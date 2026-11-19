@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dev.mangaflow.enhancement.EnhancedAssetIdentity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val ANDROID_VIDEO_ENGINE_VERSION = "Media3 1.11.1 / ONNX 1.31.1 / 1"
private const val VIDEO_WORKING_BYTES = 128L * 1024 * 1024

internal suspend fun prepareAndroidVideo(
    context: Context,
    request: EngineVideoRequest,
    model: EngineModel,
    session: OrtSession,
    progress: (EngineProgress) -> Unit,
): EngineOutput {
    val coroutine = currentCoroutineContext()
    val source = File(request.sourcePath)
    val destination = File(request.destinationPath)
    require(destination.extension == "mp4")
    newEngineOutput(source, destination)
    val temporary = File(destination.parentFile, "render-${UUID.randomUUID()}.mp4")
    val measuredAt = SystemClock.elapsedRealtime()
    val inspect = { coroutine.ensureActive() }
    try {
        progress(EngineProgress(EngineStage.VERIFY, detail = "读取原视频帧时间与 AAC 音轨"))
        val sourceInfo = inspectVideo(source, inspect)
        val sourceHash = engineSha256(source)
        val width = Math.multiplyExact(sourceInfo.width, request.scale)
        val height = Math.multiplyExact(sourceInfo.height, request.scale)
        val frameMemory = (sourceInfo.width.toLong() * sourceInfo.height + width.toLong() * height) * 4
        require(width % 2 == 0 && height % 2 == 0 && frameMemory <= VIDEO_WORKING_BYTES / 2) {
            "设备当前无法按此尺寸安全处理，建议选择较小倍率"
        }

        val frameCount = AtomicInteger()
        var lastSampleAt = 0L
        val processingStarted = SystemClock.elapsedRealtime()
        val effect = GlEffect { _, hdr ->
            if (hdr) throw VideoFrameProcessingException("HDR 片源保持原视频")
            AndroidOnnxVideoProgram(session, sourceInfo.width, sourceInfo.height, request.scale, VIDEO_WORKING_BYTES - frameMemory,
                checkActive = inspect) {
                val completed = frameCount.incrementAndGet()
                val now = SystemClock.elapsedRealtime()
                if (completed == 1 || completed == sourceInfo.videoTimes.size || now - lastSampleAt >= 750) {
                    lastSampleAt = now
                    progress(EngineProgress(EngineStage.PROCESS, completed.toLong(), sourceInfo.videoTimes.size.toLong(),
                        completed * 1000.0 / (now - processingStarted).coerceAtLeast(1), "逐帧 RGB 增强 · CPU ONNX"))
                }
            }
        }
        val transcoded = exportVideo(context, source, temporary, effect)
        inspect()

        progress(EngineProgress(EngineStage.VALIDATE, detail = "校验输出尺寸、逐帧时间轴和原始 AAC 音轨"))
        val outputInfo = inspectVideo(temporary, inspect)
        check(outputInfo.width == width && outputInfo.height == height) { "编码器输出尺寸与目标不同" }
        check(transcoded.videoEncoderName != null && transcoded.audioEncoderName == null &&
            frameCount.get() == sourceInfo.videoTimes.size && transcoded.videoFrameCount == frameCount.get()) {
            "编码帧数或音频透传状态与原视频不一致"
        }
        check(sourceInfo.audio?.identity == outputInfo.audio?.identity) { "未能保留原始 AAC 音轨" }
        val sourceStart = minOf(sourceInfo.videoTimes.first(), sourceInfo.audio?.times?.first() ?: Long.MAX_VALUE)
        val outputStart = minOf(outputInfo.videoTimes.first(), outputInfo.audio?.times?.first() ?: Long.MAX_VALUE)
        check(sameTimeline(sourceInfo.videoTimes, outputInfo.videoTimes, sourceStart, outputStart) &&
            sameTimeline(sourceInfo.audio?.times ?: LongArray(0), outputInfo.audio?.times ?: LongArray(0), sourceStart, outputStart) &&
            abs(outputInfo.durationUs - sourceInfo.durationUs) <= 50_000L) {
            "视频或音轨时间轴未通过校验，原版保持可用"
        }
        validateDecodedOutput(temporary, outputInfo.videoTimes, inspect, progress)
        check(engineSha256(source) == sourceHash && engineSha256(File(model.path)) == model.sha256) {
            "处理期间原视频或模型发生变化，增强版未发布"
        }
        val videoSpan = outputInfo.videoTimes.last() - outputInfo.videoTimes.first()
        val output = EngineOutput(destination.canonicalPath, width, height, engineSha256(temporary), temporary.length(),
            EnhancementEngineIds.ANDROID_VIDEO_ONNX, ANDROID_VIDEO_ENGINE_VERSION,
            EnhancedAssetIdentity.digest("${model.sha256}|"), SystemClock.elapsedRealtime() - measuredAt,
            video = EngineVideoMetadata(outputInfo.durationUs / 1_000L,
                videoSpan.takeIf { it > 0L }?.let { (outputInfo.videoTimes.size - 1) * 1_000_000.0 / it },
                if (outputInfo.audio == null) 0 else 1, 0, 0))
        inspect()
        moveNewEngineFile(temporary, destination)
        progress(EngineProgress(EngineStage.COMPLETE, frameCount.get().toLong(), frameCount.get().toLong(),
            detail = "已校验新 MP4 版本", bytes = output.bytes))
        return output
    } finally {
        temporary.delete()
    }
}

/** Transformer owns decoder/encoder/muxer lifetime. Its blocking cancel never occupies the UI. */
private suspend fun exportVideo(context: Context, source: File, destination: File, effect: GlEffect): ExportResult {
    val thread = HandlerThread("MangaFlowVideoExporter").apply { start() }
    val handler = Handler(thread.looper)
    val dispatcher = handler.asCoroutineDispatcher("MangaFlowVideoExporter")
    val completion = CompletableDeferred<ExportResult>()
    var transformer: Transformer? = null
    try {
        withContext(dispatcher) {
            val sourceItem = MediaItem.Builder().setUri(Uri.fromFile(source)).setMimeType(MimeTypes.VIDEO_MP4).build()
            val edited = EditedMediaItem.Builder(sourceItem).setEffects(Effects(emptyList(), listOf(effect))).build()
            val frameProcessor = DefaultVideoFrameProcessor.Factory.Builder()
                .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build()
            val encoders = DefaultEncoderFactory.Builder(context).setEnableFallback(false).setEnableFormatFallback(false).build()
            val exporter = Transformer.Builder(context)
                .setLooper(thread.looper)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoders)
                .setVideoFrameProcessorFactory(frameProcessor)
                .setMaxDelayBetweenMuxerSamplesMs(120_000L)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        completion.complete(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        val cause = generateSequence<Throwable>(exportException) { it.cause }.last().message
                        completion.completeExceptionally(IllegalStateException(
                            cause?.let { "视频增强未完成：$it" } ?: "视频增强未完成，原视频保持可用", exportException))
                    }
                }).build()
            transformer = exporter
            exporter.start(edited, destination.path)
        }
        return completion.await()
    } finally {
        try {
            withContext(NonCancellable + dispatcher) { transformer?.cancel() }
        } finally {
            thread.quitSafely()
        }
    }
}

private data class AudioTrack(val identity: String, val times: LongArray)
private data class VideoInspection(val width: Int, val height: Int, val durationUs: Long,
    val videoTimes: LongArray, val audio: AudioTrack?)

private fun inspectVideo(source: File, checkActive: () -> Unit): VideoInspection {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.path)
        val trackIndices = 0 until extractor.trackCount
        val video = trackIndices.singleOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: error("当前 Android 仅支持单视频轨 MP4")
        val audio = trackIndices.singleOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC }
        require(extractor.trackCount == if (audio == null) 1 else 2) {
            "当前仅支持单视频轨及可选 AAC 音轨。多音轨、内封字幕与其它轨道保留在原视频中"
        }
        val info = extractor.getTrackFormat(video)
        val transfer = info.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val standard = info.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
        require((info.intOrNull(MediaFormat.KEY_ROTATION) ?: 0) == 0 &&
            (transfer == null || transfer == 0 || transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) &&
            (standard == null || standard == 0 || standard in setOf(MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC)) &&
            !info.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) && info.getString(MediaFormat.KEY_MIME) != "video/dolby-vision") {
            "旋转、HDR 与 Dolby Vision 视频保持原版"
        }
        val width = info.getInteger(MediaFormat.KEY_WIDTH)
        val height = info.getInteger(MediaFormat.KEY_HEIGHT)
        val duration = info.getLong(MediaFormat.KEY_DURATION)
        require(width > 0 && height > 0 && duration > 0) { "无法确认视频尺寸或时长" }
        val times = trackTimes(extractor, video, checkActive).also { it.sort() }
        val audioTrack = audio?.let {
            val format = extractor.getTrackFormat(it)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(listOf(format.getString(MediaFormat.KEY_MIME), format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), format.getString(MediaFormat.KEY_LANGUAGE) ?: "und")
                .joinToString("|").toByteArray())
            format.getByteBuffer("csd-0")?.duplicate()?.let { data -> digest.update(data) }
            val audioTimes = trackTimes(extractor, it, checkActive, digest)
            AudioTrack(digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }, audioTimes)
        }
        return VideoInspection(width, height, duration, times, audioTrack)
    } finally {
        extractor.release()
    }
}

private fun trackTimes(extractor: MediaExtractor, track: Int, checkActive: () -> Unit, digest: MessageDigest? = null): LongArray {
    extractor.selectTrack(track)
    try {
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var times = LongArray(1_024)
        var count = 0
        val bytes = if (digest == null) null else ByteBuffer.allocateDirect(256 * 1024)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        while (extractor.sampleTrackIndex == track) {
            if (count % 256 == 0) checkActive()
            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0 && count < 1_024 * 1_024) {
                "受保护或过长的轨道无法安全处理"
            }
            val pts = extractor.sampleTime
            require(pts >= 0L) { "当前无法可靠处理带负时间戳的轨道" }
            if (count == times.size) times = times.copyOf(times.size * 2)
            times[count++] = pts
            if (bytes != null && digest != null) {
                bytes.clear()
                val length = extractor.readSampleData(bytes, 0)
                require(length in 1..bytes.capacity()) { "不能完整读取 AAC 音频帧" }
                header.putInt(0, length)
                digest.update(header.array())
                bytes.position(0)
                bytes.limit(length)
                digest.update(bytes)
            }
            extractor.advance()
        }
        require(count > 0) { "轨道没有可用样本" }
        return times.copyOf(count)
    } finally {
        extractor.unselectTrack(track)
    }
}

private fun sameTimeline(source: LongArray, output: LongArray, sourceStart: Long, outputStart: Long): Boolean =
    source.size == output.size && source.indices.all {
        abs((source[it] - sourceStart) - (output[it] - outputStart)) <= 1_000L
    }

/** Each encoded frame must actually decode before the derivative can replace its temporary file. */
private fun validateDecodedOutput(source: File, expectedTimes: LongArray, checkActive: () -> Unit,
    progress: (EngineProgress) -> Unit) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MimeTypes.VIDEO_H264 }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val decoder = MediaCodec.createDecoderByType(MimeTypes.VIDEO_H264)
        var started = false
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val decoded = LongArray(expectedTimes.size)
            var count = 0
            var inputEnded = false
            var outputEnded = false
            var lastActivity = SystemClock.elapsedRealtime()
            var lastProgress = lastActivity
            while (!outputEnded) {
                checkActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val sampleSize = extractor.readSampleData(input, 0)
                        inputEnded = sampleSize < 0
                        if (inputEnded) decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            require(sampleSize in 1..input.capacity()) { "输出视频包含无法完整读取的帧" }
                            decoder.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000L)
                if (output >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(count < decoded.size) { "输出解码帧数多于原视频" }
                            decoded[count++] = info.presentationTimeUs
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(output, false)
                    }
                    val now = SystemClock.elapsedRealtime()
                    lastActivity = now
                    if (outputEnded || now - lastProgress >= 750L) {
                        lastProgress = now
                        progress(EngineProgress(EngineStage.VALIDATE, count.toLong(), decoded.size.toLong(), detail = "逐帧解码校验"))
                    }
                }
                check(SystemClock.elapsedRealtime() - lastActivity < 15_000L) { "输出视频解码校验停滞，原版保持可用" }
            }
            check(count == decoded.size && decoded.apply { sort() }.contentEquals(expectedTimes)) {
                "输出解码未能保留全部帧及对应时间"
            }
        } finally {
            try { if (started) decoder.stop() } finally { decoder.release() }
        }
    } finally {
        extractor.release()
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private class AndroidOnnxVideoProgram(
    private val session: OrtSession,
    private val width: Int,
    private val height: Int,
    private val scale: Int,
    private val budget: Long,
    private val checkActive: () -> Unit,
    private val onFrame: () -> Unit,
) : BaseGlShaderProgram(false, 1) {
    private var renderer: AndroidOnnxTiles? = null
    private var inputPixels: ByteBuffer? = null
    private var outputPixels: ByteBuffer? = null
    private var texture = 0
    private val inputFramebuffer = IntArray(1)
    private val previousFramebuffer = IntArray(1)
    private var copyProgram: GlProgram? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size = videoFrame {
        require(inputWidth == width && inputHeight == height) { "当前片源解码尺寸发生变化" }
        val plan = AndroidOnnxTiles(session, width, height, scale, 128, 24, budget, requireRgb = true)
        renderer = plan
        texture = GlUtil.createTexture(plan.outWidth, plan.outHeight, false)
        GLES20.glGenFramebuffers(1, inputFramebuffer, 0)
        GlUtil.checkGlError()
        inputPixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        outputPixels = ByteBuffer.allocateDirect(plan.outWidth * plan.outHeight * 4).order(ByteOrder.LITTLE_ENDIAN)
        copyProgram = GlProgram(VIDEO_VERTEX, VIDEO_FRAGMENT).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        Size(plan.outWidth, plan.outHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = videoFrame {
        checkActive()
        val plan = checkNotNull(renderer)
        val input = checkNotNull(inputPixels)
        val output = checkNotNull(outputPixels)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, inputFramebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "当前 GPU 无法读取解码画面"
            }
            input.rewind()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, input)
            GlUtil.checkGlError()
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(previousFramebuffer[0], plan.outWidth, plan.outHeight)
        }
        val inputColors = input.asIntBuffer()
        output.rewind()
        val outputColors = output.asIntBuffer()
        // GL stores rows bottom-up; inference sees the same top-down orientation as a still image.
        plan.render(checkActive, { left, top, regionWidth, regionHeight, pixels ->
            for (y in 0 until regionHeight) {
                val row = (height - 1 - top - y) * width + left
                for (x in 0 until regionWidth) pixels[y * regionWidth + x] = swapRedBlue(inputColors.get(row + x)) or (255 shl 24)
            }
        }, { y, row ->
            val offset = (plan.outHeight - 1 - y) * plan.outWidth
            row.forEachIndexed { x, pixel -> outputColors.put(offset + x, swapRedBlue(pixel)) }
        })
        output.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, plan.outWidth, plan.outHeight, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, output)
        val program = checkNotNull(copyProgram)
        program.use()
        program.setSamplerTexIdUniform("uTexture", texture, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
        onFrame()
    }

    override fun release() {
        val releaseOutput = runCatching { super.release() }
        val releaseProgram = runCatching { copyProgram?.delete() }
        val releaseTexture = runCatching { if (texture != 0) GlUtil.deleteTexture(texture) }
        GLES20.glDeleteFramebuffers(1, inputFramebuffer, 0)
        renderer = null
        inputPixels = null
        outputPixels = null
        releaseOutput.getOrThrow()
        releaseProgram.getOrThrow()
        releaseTexture.getOrThrow()
    }

    private fun swapRedBlue(pixel: Int) = (pixel and 0xff00ff00.toInt()) or ((pixel and 0xff) shl 16) or ((pixel ushr 16) and 0xff)

    private inline fun <T> videoFrame(action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        throw VideoFrameProcessingException.from(failure)
    } catch (memory: OutOfMemoryError) {
        throw VideoFrameProcessingException("手机内存不足，原视频保持可用", memory)
    } catch (link: LinkageError) {
        throw VideoFrameProcessingException("原生推理组件不可用，原视频保持可用", link)
    }
}

private const val VIDEO_VERTEX = """
attribute vec4 aFramePosition;
varying vec2 texCoord;
void main() {
    gl_Position = aFramePosition;
    texCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

private const val VIDEO_FRAGMENT = """
precision mediump float;
varying vec2 texCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, texCoord);
}
"""
