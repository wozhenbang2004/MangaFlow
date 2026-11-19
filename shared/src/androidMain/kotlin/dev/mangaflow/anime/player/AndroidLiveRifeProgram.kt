@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlShaderProgram
import dev.mangaflow.enhancement.engine.AndroidLiveRifeVulkan
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToLong

/** Generates frame timestamps against the original stream clock, leaving audio and subtitle ownership in ExoPlayer. */
internal class AndroidLiveRifeProgram(
    private val model: AndroidLiveRifeVulkan,
    private val targetFps: Double?,
    private val report: (RifeSample) -> Unit,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener {}
    private var errorExecutor: Executor = Executor { it.run() }
    private val readFbo = IntArray(1)
    private val lastFbo = IntArray(1)
    private var output: GlTextureInfo? = null
    private var width = 0
    private var height = 0
    private var pixelBytes: ByteBuffer? = null
    private var previous = IntArray(0)
    private var current = IntArray(0)
    private var generated = IntArray(0)
    private var previousPts = NONE
    private var currentPts = NONE
    private var lastPts = NONE
    private var originPts = NONE
    private var gridIndex = 0L
    private var pairPending = false
    private var cut = false
    private var inFlight = false
    private var endRequested = false
    private var ended = false
    private var failed = false
    private var sourceOnly = false
    private var sampleStart = NONE
    private var sampleFrames = 0L
    private var synthesized = 0L
    private var slowFrames = 0
    private var emptyIntervals = 0
    private var detachReason: String? = null
    private var lastInferenceNanos: Long? = null

    init {
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
        check(!inFlight && !pairPending && !endRequested)
        if (input.width != width || input.height != height) {
            output?.release()
            width = input.width
            height = input.height
            val count = Math.multiplyExact(width, height)
            require(count in 1..MAX_PIXELS) { "切换后的画面高于实时补帧试用分辨率" }
            previous = IntArray(count)
            current = IntArray(count)
            generated = IntArray(count)
            pixelBytes = ByteBuffer.allocateDirect(count * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val texture = GlUtil.createTexture(width, height, false)
            output = try { provider.createBuffersForTexture(texture, width, height) }
                catch (failure: Exception) { GlUtil.deleteTexture(texture); throw failure }
            previousPts = NONE
        }
        if (lastPts != NONE && presentationTimeUs <= lastPts) {
            inputListener.onInputFrameProcessed(input)
            inputListener.onReadyToAcceptInputFrame()
            return@guard
        }
        read(input, current)
        inputListener.onInputFrameProcessed(input)
        currentPts = presentationTimeUs
        if (previousPts == NONE || currentPts - previousPts !in 1L..MAX_INTERVAL_US) {
            originPts = currentPts
            gridIndex = 1
            commitInput()
            emit(previousPts, previous, false)
            return@guard
        }
        pairPending = true
        cut = isCutOrHeld()
        drain()
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) = guard {
        if (outputTexture !== output || !inFlight) return@guard
        inFlight = false
        drain()
    }

    override fun signalEndOfCurrentInputStream() = guard {
        if (endRequested || ended) return@guard
        endRequested = true
        drain()
    }

    override fun flush() {
        if (failed) return
        previousPts = NONE
        currentPts = NONE
        lastPts = NONE
        originPts = NONE
        gridIndex = 0L
        pairPending = false
        inFlight = false
        endRequested = false
        ended = false
        sampleStart = NONE
        sampleFrames = 0
        synthesized = 0
        slowFrames = 0
        emptyIntervals = 0
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try { output?.release() }
        finally { GLES20.glDeleteFramebuffers(1, readFbo, 0) }
    }

    private fun drain() {
        if (inFlight || ended || failed) return
        if (pairPending) {
            val target = targetFps
            val nextStamp = when {
                sourceOnly -> currentPts
                target == null -> if (lastPts <= previousPts) previousPts + (currentPts - previousPts) / 2 else currentPts
                else -> originPts + (gridIndex * (1_000_000.0 / target)).roundToLong()
            }
            if (nextStamp <= lastPts || nextStamp < previousPts) {
                // A timestamp discontinuity invalidates the previous pair rather than generating across it.
                originPts = currentPts
                gridIndex = 1
                commitInput()
                emit(previousPts, previous, false)
                return
            }
            if (nextStamp > currentPts) {
                if (lastPts <= previousPts) emptyIntervals++ else emptyIntervals = 0
                if (emptyIntervals >= 3) {
                    fallback("当前实际帧间距已达到目标，继续使用源帧率")
                    return
                }
                commitInput()
            } else {
                gridIndex++
                if (nextStamp == currentPts) {
                    commitInput()
                    emit(previousPts, previous, false)
                } else if (cut || nextStamp == previousPts) {
                    val nearest = if (nextStamp - previousPts <= currentPts - nextStamp) previous else current
                    emit(nextStamp, nearest, false)
                } else {
                    val nanos = System.nanoTime()
                    try {
                        model.infer(previous, current, width, height,
                            (nextStamp - previousPts).toFloat() / (currentPts - previousPts), generated)
                        val elapsed = System.nanoTime() - nanos
                        lastInferenceNanos = elapsed
                        val budget = (if (target == null) (currentPts - previousPts) / 2.0 else 1_000_000.0 / target) * 850
                        slowFrames = if (elapsed > budget) slowFrames + 1 else 0
                        if (slowFrames >= 3 || elapsed > 200_000_000L) {
                            fallback("实际推理超过帧预算，已恢复源帧率")
                            return
                        }
                        emit(nextStamp, generated, true)
                    } catch (_: Exception) {
                        fallback("本机 Vulkan 补帧中断，已恢复源帧率")
                    } catch (_: LinkageError) {
                        fallback("Vulkan 补帧组件加载失败，已恢复源帧率")
                    }
                }
                return
            }
        }
        if (endRequested) {
            // Media3 marks the stream ended once the last original PTS has reached its renderer.
            if (previousPts != NONE && lastPts < previousPts) emit(previousPts, previous, false)
            else {
                ended = true
                outputListener.onCurrentOutputStreamEnded()
            }
        } else {
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    private fun fallback(reason: String) {
        sourceOnly = true
        detachReason = reason
        commitInput()
        emit(previousPts, previous, false)
    }

    private fun commitInput() {
        val old = previous
        previous = current
        current = old
        previousPts = currentPts
        pairPending = false
    }

    private fun emit(pts: Long, frame: IntArray, interpolated: Boolean) {
        val texture = checkNotNull(output)
        val bytes = checkNotNull(pixelBytes)
        val packed = bytes.asIntBuffer()
        for (y in 0 until height) {
            val a = y * width
            val b = (height - y - 1) * width
            for (x in 0 until width) packed.put(b + x, swap(frame[a + x]))
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        lastPts = pts
        inFlight = true
        if (sampleStart == NONE) sampleStart = pts
        if (interpolated) synthesized++
        sampleFrames++
        val span = pts - sampleStart
        if (detachReason != null || span >= 600_000) {
            report(RifeSample(synthesized, if (span > 0 && sampleFrames >= 2) (sampleFrames - 1) * 1_000_000.0 / span else null,
                lastInferenceNanos, detachReason))
            sampleStart = pts
            sampleFrames = 1
        }
        outputListener.onOutputFrameAvailable(texture, pts)
    }

    private fun read(texture: GlTextureInfo, frame: IntArray) {
        val bytes = checkNotNull(pixelBytes)
        val packed = bytes.asIntBuffer()
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, lastFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo[0])
        try {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture.texId, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkGlError()
            for (y in 0 until height) {
                val a = y * width
                val b = (height - y - 1) * width
                for (x in 0 until width) frame[a + x] = swap(packed.get(b + x)) or (255 shl 24)
            }
        } finally {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lastFbo[0])
        }
    }

    private fun isCutOrHeld(): Boolean {
        var luminanceDelta = 0L
        var samples = 0
        for (y in 0 until height step (height / 48).coerceAtLeast(1)) {
            for (x in 0 until width step (width / 48).coerceAtLeast(1)) {
                val i = y * width + x
                luminanceDelta += abs(luma(previous[i]) - luma(current[i]))
                samples++
            }
        }
        return luminanceDelta >= samples * 255 * 0.15 || previous.contentEquals(current)
    }

    private fun luma(pixel: Int) = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) ushr 8
    private fun swap(pixel: Int) = (pixel and 0xff00ff00.toInt()) or ((pixel ushr 16) and 255) or ((pixel and 255) shl 16)

    private inline fun guard(action: () -> Unit) {
        if (failed) return
        try { action() }
        catch (failure: Exception) {
            failed = true
            errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(failure)) }
        }
    }

    data class RifeSample(val synthesized: Long, val graphFps: Double?, val inferenceNanos: Long?, val failure: String?)

    companion object {
        const val MAX_PIXELS = 1280 * 720
        private const val MAX_INTERVAL_US = 150_000L
        private const val NONE = Long.MIN_VALUE
    }
}
