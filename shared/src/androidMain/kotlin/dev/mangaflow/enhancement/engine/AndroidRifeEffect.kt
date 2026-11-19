@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MAX_VIDEO_FRAMES = 1_024 * 1_024

internal fun androidRifeTimes(source: LongArray, request: EngineVideoRequest): LongArray {
    require(source.size >= 2 && (1 until source.size).all { source[it] > source[it - 1] }) {
        "需要可确认顺序与间距的原始视频帧"
    }
    val deltas = LongArray(source.size - 1) { source[it + 1] - source[it] }.apply { sort() }
    val typicalInterval = deltas[deltas.size / 2]
    val start = source.first()
    val end = source.last() + typicalInterval
    if (request.frameMultiplier != null) {
        require(request.frameMultiplier == 2 && source.size <= MAX_VIDEO_FRAMES / 2) { "当前支持 2× 源时间轴补帧" }
        val sourceFps = (source.size - 1) * 1_000_000.0 / (source.last() - start)
        require(sourceFps <= 60.0 && typicalInterval >= 2L) { "当前 Android 的 2× 补帧输出最高为 120 FPS" }
        return LongArray(source.size * 2) { i ->
            val j = i / 2
            if (i % 2 == 0) source[j] else source[j] + ((source.getOrNull(j + 1) ?: end) - source[j]) / 2
        }
    }
    val fps = checkNotNull(request.targetFps)
    val sourceFps = (source.size - 1) * 1_000_000.0 / (source.last() - start)
    require(fps.isFinite() && fps in sourceFps..120.0 && fps > sourceFps) {
        "补帧目标需高于原始帧率，且最高为 120 FPS"
    }
    val period = 1_000_000.0 / fps
    val count = kotlin.math.ceil((end - start) / period).toLong()
    require(count in (source.size + 1).toLong()..MAX_VIDEO_FRAMES.toLong()) { "目标帧数超出离线处理预算" }
    return LongArray(count.toInt()) { start + (it * period).roundToLong() }
}

/** One pair of input frames and one output texture, with backpressure from the downstream encoder. */
internal class AndroidRifeEffect(
    private val session: OrtSession,
    private val expectedWidth: Int,
    private val expectedHeight: Int,
    private val inputTimes: LongArray,
    private val outputTimes: LongArray,
    private val ensemble: Boolean,
    private val threshold: Double,
    private val checkActive: () -> Unit,
    private val outputFrame: (interpolated: Boolean) -> Unit,
) : GlShaderProgram {
    private val width = expectedWidth
    private val height = expectedHeight
    private val framePixels = Math.multiplyExact(width, height)
    private val inputName = session.inputNames.single()
    private val environment = OrtEnvironment.getEnvironment()
    private val shape = longArrayOf(1, 7, height.toLong(), width.toLong())
    private val tensor = ByteBuffer.allocateDirect(framePixels * 7 * java.lang.Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var previous = IntArray(framePixels)
    private var next = IntArray(framePixels)
    private val generated = IntArray(framePixels)
    private val firstPass = if (ensemble) FloatArray(framePixels * 3) else null
    private val pixels = ByteBuffer.allocateDirect(framePixels * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val packed = pixels.asIntBuffer()
    private val readFbo = IntArray(1)
    private val previousFbo = IntArray(1)
    private var output: GlTextureInfo? = null
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
    private var errorExecutor: Executor = Executor { it.run() }
    private var previousTime = 0L
    private var nextTime = 0L
    private var origin = 0L
    private var streamInput = 0
    private var streamOutput = 0
    private var hasPair = false
    private var skipPair = false
    private var inFlight = false
    private var endRequested = false
    private var ended = false
    private var failed = false

    init {
        val info = session.inputInfo.values.single().info as? TensorInfo ?: error("RIFE 输入不是图像张量")
        require(info.type == OnnxJavaType.FLOAT && info.shape.size == 4 && info.shape[0] in longArrayOf(-1, 1) &&
            info.shape[1] == 7L && session.outputNames.size == 1) { "RIFE 需要 FP32 NCHW 7 通道输入" }
        GLES20.glGenFramebuffers(1, readFbo, 0)
        GlUtil.checkGlError()
    }

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
        inputListener = listener
        listener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(listener: GlShaderProgram.OutputListener) { outputListener = listener }
    override fun setErrorListener(executor: Executor, listener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        errorListener = listener
    }

    override fun queueInputFrame(provider: GlObjectsProvider, input: GlTextureInfo, presentationTimeUs: Long) = guard {
        checkActive()
        check(!hasPair && !endRequested && streamInput < inputTimes.size && input.width == width && input.height == height) {
            "RIFE 视频帧尺寸或顺序发生变化"
        }
        if (output == null) {
            val texture = GlUtil.createTexture(width, height, false)
            try { output = provider.createBuffersForTexture(texture, width, height) }
            catch (failure: Exception) { GlUtil.deleteTexture(texture); throw failure }
        }
        if (streamInput == 0) origin = presentationTimeUs
        check(abs(presentationTimeUs - origin - (inputTimes[streamInput] - inputTimes.first())) <= 1_000L) {
            "实际解码帧时间与预检时间轴不一致"
        }
        readTexture(input, if (streamInput == 0) previous else next)
        if (streamInput++ == 0) {
            previousTime = inputTimes.first()
            inputListener.onInputFrameProcessed(input)
            inputListener.onReadyToAcceptInputFrame()
        } else {
            nextTime = inputTimes[streamInput - 1]
            hasPair = true
            skipPair = previous.contentEquals(next) || sceneCut()
            inputListener.onInputFrameProcessed(input)
            emit()
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) = guard {
        if (outputTexture !== output || !inFlight) return@guard
        inFlight = false
        emit()
    }

    override fun signalEndOfCurrentInputStream() = guard {
        if (ended || endRequested) return@guard
        check(streamInput == inputTimes.size) { "解码未提供完整的原始帧" }
        endRequested = true
        emit()
    }

    /** Upstream retains or discards its textures on flush; none of its input textures are retained here. */
    override fun flush() {
        if (failed) return
        streamInput = 0
        streamOutput = 0
        hasPair = false
        inFlight = false
        endRequested = false
        ended = false
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try { output?.release() }
        finally { GLES20.glDeleteFramebuffers(1, readFbo, 0) }
    }

    private fun emit() {
        if (inFlight || ended || failed) return
        checkActive()
        val pts = outputTimes.getOrNull(streamOutput)
        if (hasPair && (pts == null || pts >= nextTime)) {
            val old = previous
            previous = next
            next = old
            previousTime = nextTime
            hasPair = false
            if (!endRequested) {
                inputListener.onReadyToAcceptInputFrame()
                return
            }
        }
        if (endRequested && pts == null) {
            ended = true
            outputListener.onCurrentOutputStreamEnded()
            return
        }
        if (!hasPair && !endRequested) return
        val stamp = checkNotNull(pts)
        check(stamp >= previousTime) { "RIFE 输出时间早于所在原始帧" }
        val interpolate = hasPair && !skipPair && stamp > previousTime
        val frame = when {
            interpolate -> infer((stamp - previousTime).toFloat() / (nextTime - previousTime))
            hasPair && skipPair && stamp > previousTime && stamp - previousTime >= nextTime - stamp -> next
            else -> previous
        }
        val out = checkNotNull(output)
        for (y in 0 until height) {
            val offset = y * width
            val mirrored = (height - 1 - y) * width
            for (x in 0 until width) packed.put(mirrored + x, argbToRgba(frame[offset + x]))
        }
        pixels.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, out.texId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkGlError()
        streamOutput++
        inFlight = true
        outputFrame(interpolate)
        outputListener.onOutputFrameAvailable(out, origin + stamp - inputTimes.first())
    }

    private fun readTexture(input: GlTextureInfo, target: IntArray) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo[0])
        try {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, input.texId, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "当前 GPU 无法读取补帧输入"
            }
            pixels.rewind()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GlUtil.checkGlError()
            for (y in 0 until height) {
                val row = y * width
                val mirrored = (height - 1 - y) * width
                for (x in 0 until width) target[row + x] = argbToRgba(packed.get(mirrored + x)) or (255 shl 24)
            }
        } finally {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFbo[0])
        }
    }

    private fun sceneCut(): Boolean {
        var sum = 0L
        var count = 0L
        for (y in 0 until height step (height / 48).coerceAtLeast(1)) {
            for (x in 0 until width step (width / 48).coerceAtLeast(1)) {
                val index = y * width + x
                sum += abs(luma(previous[index]) - luma(next[index]))
                count++
            }
        }
        return sum.toDouble() / (255.0 * count) >= threshold
    }

    private fun luma(pixel: Int) = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) ushr 8

    private fun infer(time: Float): IntArray {
        if (firstPass != null) runModel(previous, next, time) { output -> output.get(firstPass) }
        val first = if (firstPass == null) previous else next
        val second = if (firstPass == null) next else previous
        val alpha = if (firstPass == null) time else 1f - time
        runModel(first, second, alpha) { result ->
            for (index in 0 until framePixels) {
                var value = 255 shl 24
                for (channel in 0..2) {
                    val position = index + channel * framePixels
                    val pixel = result.get(position)
                    val mixed = firstPass?.let { (pixel + it[position]) / 2f } ?: pixel
                    check(mixed.isFinite()) { "RIFE 产生无效像素" }
                    value = value or ((mixed.coerceIn(0f, 1f) * 255f).roundToInt() shl (16 - channel * 8))
                }
                generated[index] = value
            }
        }
        return generated
    }

    private inline fun runModel(a: IntArray, b: IntArray, time: Float, use: (java.nio.FloatBuffer) -> Unit) {
        checkActive()
        tensor.clear()
        for (frame in listOf(a, b)) for (channel in 0..2) for (pixel in frame)
            tensor.put((pixel ushr (16 - channel * 8) and 255) / 255f)
        repeat(framePixels) { tensor.put(time) }
        tensor.rewind()
        OnnxTensor.createTensor(environment, tensor, shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val image = result[0] as? OnnxTensor ?: error("RIFE 未输出 RGB 张量")
                require(image.info.type == OnnxJavaType.FLOAT &&
                    image.info.shape.contentEquals(longArrayOf(1, 3, height.toLong(), width.toLong()))) { "RIFE 输出尺寸无效" }
                use(image.floatBuffer)
            }
        }
    }

    private fun argbToRgba(pixel: Int) =
        (pixel and 0xff00ff00.toInt()) or ((pixel and 0xff) shl 16) or ((pixel ushr 16) and 0xff)

    private inline fun guard(action: () -> Unit) {
        if (failed) return
        try { action() }
        catch (failure: Exception) { fail(VideoFrameProcessingException.from(failure)) }
        catch (failure: OutOfMemoryError) { fail(VideoFrameProcessingException("RIFE 超出设备内存预算", failure)) }
        catch (failure: LinkageError) { fail(VideoFrameProcessingException("RIFE 原生推理组件不可用", failure)) }
    }

    private fun fail(error: VideoFrameProcessingException) {
        failed = true
        errorExecutor.execute { errorListener.onError(error) }
    }
}
