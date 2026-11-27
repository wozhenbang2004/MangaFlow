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
    private val automatic: Boolean = false,
    private val seekGeneration: () -> Long,
    private val onSynthesizedFrame: (generation: Long, pts: Long) -> Unit,
    private val onReset: (generation: Long) -> Unit,
    private val onRelease: () -> Unit,
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
    private var inputGeneration = NONE
    private var previousTimelinePts = NONE
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
    private var sampledSynthesized = 0L
    private var sampleWallTime = 0L
    private var slowPairs = 0
    private var pairWorkNanos = 0L
    private var pairBudgetNanos = 0L
    private val inputIntervals = LongArray(8)
    private var intervalCount = 0
    private var intervalIndex = 0
    private var observedInputFps: Double? = null
    private var resolvedTargetFps: Double? = if (automatic) null else targetFps
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
            model.prepareFrameSize(width, height)
            val maximumTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTexture, 0)
            GlUtil.checkGlError()
            require(width <= maximumTexture[0] && height <= maximumTexture[0]) { "当前画面超过设备纹理尺寸限制" }
            previous = IntArray(count)
            current = IntArray(count)
            generated = IntArray(count)
            pixelBytes = ByteBuffer.allocateDirect(count * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val texture = GlUtil.createTexture(width, height, false)
            output = try { provider.createBuffersForTexture(texture, width, height) }
                catch (failure: Exception) { GlUtil.deleteTexture(texture); throw failure }
            previousPts = NONE
            resetCadence()
        }
        val generation = seekGeneration()
        if (inputGeneration != generation) {
            // A retained shader is not guaranteed a flush before frames from the new position.
            previousTimelinePts = maxOf(previousTimelinePts, currentPts, lastPts)
            resetHistory(generation)
        }
        // A GL input already queued before the seek can follow the generation handoff.
        // More than one seek may also be coalesced by the decoder. A later backward
        // jump beyond one supported frame interval starts another distinct source run.
        val reachedRewind = previousTimelinePts != NONE && presentationTimeUs < previousTimelinePts
        if (currentPts != NONE && presentationTimeUs < currentPts &&
            (reachedRewind || currentPts - presentationTimeUs > MAX_INTERVAL_US)) {
            resetHistory(generation)
        }
        if (reachedRewind) previousTimelinePts = NONE
        if (lastPts != NONE && presentationTimeUs <= lastPts) {
            inputListener.onInputFrameProcessed(input)
            inputListener.onReadyToAcceptInputFrame()
            return@guard
        }
        // Account for one whole input interval, including all inserted frames. Waiting
        // for downstream release is excluded; later enhancement has its own budget.
        if (!sourceOnly && pairBudgetNanos > 0L) {
            slowPairs = if (pairWorkNanos > pairBudgetNanos) slowPairs + 1 else 0
            if (slowPairs >= 3 || pairWorkNanos > MAX_PAIR_WORK_NANOS) {
                sourceOnly = true
                detachReason = "补帧读回、推理与回传超过实时预算，已恢复源帧率"
            }
        }
        val readStart = System.nanoTime()
        read(input, current)
        pairWorkNanos = System.nanoTime() - readStart
        pairBudgetNanos = 0L
        inputListener.onInputFrameProcessed(input)
        currentPts = presentationTimeUs
        if (previousPts == NONE || currentPts - previousPts !in 1L..MAX_INTERVAL_US) {
            resetCadence()
            originPts = currentPts
            gridIndex = 1
            commitInput()
            emit(previousPts, previous, false)
            return@guard
        }
        val interval = currentPts - previousPts
        pairBudgetNanos = (interval * 850L)
        observeInputInterval(interval)
        if (sourceOnly) {
            fallback(checkNotNull(detachReason))
            return@guard
        }
        if (automatic && resolvedTargetFps == null) {
            // Only timestamps arriving before interpolation determine AUTO's cadence.
            commitInput()
            emit(previousPts, previous, false)
            return@guard
        }
        pairPending = true
        val cutStart = System.nanoTime()
        cut = isCutOrHeld()
        pairWorkNanos += System.nanoTime() - cutStart
        drain()
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) = guard {
        if (outputTexture !== output || !inFlight) return@guard
        inFlight = false
        if (inputGeneration != seekGeneration()) pairPending = false
        drain()
    }

    override fun signalEndOfCurrentInputStream() = guard {
        if (endRequested || ended) return@guard
        endRequested = true
        drain()
    }

    override fun flush() {
        if (failed) return
        inFlight = false
        endRequested = false
        ended = false
        previousTimelinePts = NONE
        resetHistory(seekGeneration())
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    /** Drop frame-pair and rate history on GL. Only a native flush can reclaim a renderer-owned output. */
    private fun resetHistory(generation: Long) {
        inputGeneration = generation
        previousPts = NONE
        currentPts = NONE
        lastPts = NONE
        originPts = NONE
        gridIndex = 0L
        pairPending = false
        sampleStart = NONE
        sampleFrames = 0
        synthesized = 0
        sampledSynthesized = 0
        sampleWallTime = 0
        slowPairs = 0
        // The player owns confirmed failures for this viewing choice. A stale GL
        // decision that never reached it cannot disable the next timeline.
        sourceOnly = false
        detachReason = null
        pairWorkNanos = 0L
        pairBudgetNanos = 0L
        emptyIntervals = 0
        lastInferenceNanos = null
        resetCadence()
        onReset(generation)
    }

    override fun release() {
        try { output?.release() }
        finally {
            onRelease()
            GLES20.glDeleteFramebuffers(1, readFbo, 0)
        }
    }

    private fun drain() {
        if (inFlight || ended || failed) return
        if (pairPending) {
            val target = resolvedTargetFps
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
                        pairWorkNanos += elapsed
                        if (pairWorkNanos > MAX_PAIR_WORK_NANOS) {
                            fallback("补帧读回与推理超过实时预算，已恢复源帧率")
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
            if (inputGeneration == seekGeneration() && previousPts != NONE && lastPts < previousPts)
                emit(previousPts, previous, false)
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
        val uploadStart = System.nanoTime()
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
        pairWorkNanos += System.nanoTime() - uploadStart
        lastPts = pts
        inFlight = true
        val now = System.nanoTime()
        if (sampleStart == NONE) {
            sampleStart = pts
            sampleWallTime = now
        }
        if (interpolated) {
            synthesized++
            onSynthesizedFrame(inputGeneration, pts)
        }
        sampleFrames++
        val span = pts - sampleStart
        if (detachReason != null || span >= 600_000) {
            val wall = now - sampleWallTime
            report(RifeSample(inputGeneration, synthesized, if (span > 0 && sampleFrames >= 2) (sampleFrames - 1) * 1_000_000.0 / span else null,
                if (wall > 0L) (synthesized - sampledSynthesized) * 1_000_000_000.0 / wall else null,
                lastInferenceNanos, detachReason, width, height,
                if (automatic) resolvedTargetFps else targetFps ?: observedInputFps?.times(2.0), observedInputFps))
            sampleStart = pts
            sampleFrames = 1
            sampleWallTime = now
            sampledSynthesized = synthesized
        }
        outputListener.onOutputFrameAvailable(texture, pts)
    }

    private fun resetCadence() {
        intervalCount = 0
        intervalIndex = 0
        observedInputFps = null
        resolvedTargetFps = if (automatic) null else targetFps
    }

    private fun observeInputInterval(intervalUs: Long) {
        inputIntervals[intervalIndex] = intervalUs
        intervalIndex = (intervalIndex + 1) % inputIntervals.size
        intervalCount = (intervalCount + 1).coerceAtMost(inputIntervals.size)
        if (intervalCount < inputIntervals.size || intervalIndex != 0) return
        val sorted = inputIntervals.sorted()
        val medianInterval = (sorted[3] + sorted[4]) / 2.0
        val sourceRate = 1_000_000.0 / medianInterval
        observedInputFps = sourceRate
        if (sourceRate > 60.5) {
            sourceOnly = true
            detachReason = "实际输入超过 60 FPS，继续使用源帧率"
            return
        }
        if (!automatic) return
        val limit = checkNotNull(targetFps)
        if (sourceRate >= limit * 0.99) {
            sourceOnly = true
            detachReason = "实际输入已达到显示屏帧率，无需补帧"
            return
        }
        val nextTarget = minOf(sourceRate * 2.0, limit)
        val previousTarget = resolvedTargetFps
        if (previousTarget == null || abs(nextTarget - previousTarget) / previousTarget > 0.1) {
            resolvedTargetFps = nextTarget
            // Re-anchor only at an input boundary; never generate behind the last output.
            originPts = previousPts
            gridIndex = 1L
        }
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
        } catch (failure: OutOfMemoryError) {
            failed = true
            errorExecutor.execute { errorListener.onError(VideoFrameProcessingException("实时补帧内存不足", failure)) }
        }
    }

    data class RifeSample(val generation: Long, val synthesized: Long, val graphFps: Double?, val generationFps: Double?,
        val inferenceNanos: Long?, val failure: String?, val inputWidth: Int, val inputHeight: Int,
        val targetFps: Double?, val inputFps: Double?)

    companion object {
        private const val MAX_INTERVAL_US = 150_000L
        private const val MAX_PAIR_WORK_NANOS = 200_000_000L
        private const val NONE = Long.MIN_VALUE
    }
}
