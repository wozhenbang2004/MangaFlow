package dev.mangaflow.enhancement.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Reuses one bounded tensor and two row strips for still images and sequential decoded video frames. */
internal class AndroidOnnxTiles(
    private val session: OrtSession,
    private val width: Int,
    private val height: Int,
    private val scale: Int,
    tileSize: Int,
    overlapSize: Int,
    budget: Long,
    private val requireRgb: Boolean = false,
    private val runOptions: OrtSession.RunOptions? = null,
) {
    private val input = session.inputInfo.values.single().info as TensorInfo
    private val inputName = session.inputNames.single()
    private val tileW = tileDimension(input.shape[3], tileSize)
    private val tileH = tileDimension(input.shape[2], tileSize)
    private val overlap = maxOf(16, overlapSize).coerceAtMost(minOf(tileW, tileH) / 4)
    private val stepX = tileW - 2 * overlap
    private val stepY = tileH - 2 * overlap
    private val blend = minOf(8, overlap / 2)
    val outWidth = Math.multiplyExact(width, scale)
    val outHeight = Math.multiplyExact(height, scale)
    private val channels = input.shape[1].toInt()
    private val total = ((width.toLong() + stepX - 1) / stepX) * ((height.toLong() + stepY - 1) / stepY)
    private val inputShape = longArrayOf(1, channels.toLong(), tileH.toLong(), tileW.toLong())
    private val environment = OrtEnvironment.getEnvironment()
    private val data = ByteBuffer.allocateDirect(tileW * tileH * channels * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val accumulated: FloatArray
    private val previous: FloatArray
    private val row: IntArray
    private val regionW = minOf(width, tileW)
    private val regionH = minOf(height, tileH)
    private val pixels = IntArray(regionW * regionH)

    init {
        require(!requireRgb || channels == 3) { "番剧模型需要 RGB 三通道输入" }
        val tileOutputFloats = tileW.toLong() * tileH * scale * scale * 3
        val stripFloats = outWidth.toLong() * minOf(outHeight, (stepY + 2 * blend) * scale) * 4
        val tailFloats = outWidth.toLong() * minOf(outHeight, 2 * blend * scale) * 4
        require((tileOutputFloats * 2 + stripFloats + tailFloats + tileW.toLong() * tileH * (channels + 1)) * 4 <= budget) {
            "分块与输出行缓冲超过当前准入预算，请减小分块"
        }
        accumulated = FloatArray(Math.toIntExact(stripFloats))
        previous = FloatArray(Math.toIntExact(tailFloats))
        row = IntArray(outWidth)
    }

    fun render(
        checkActive: () -> Unit,
        readRegion: (left: Int, top: Int, width: Int, height: Int, pixels: IntArray) -> Unit,
        writeRow: (y: Int, pixels: IntArray) -> Unit,
        onTile: (completed: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        var previousTop = 0
        var previousLength = 0
        var completed = 0L
        var top = 0
        onTile(0L, total)
        while (top < height) {
            checkActive()
            val bottom = minOf(height, top + stepY)
            val bandTop = maxOf(0, top - blend) * scale
            val bandBottom = minOf(height, bottom + blend) * scale
            val bandLength = (bandBottom - bandTop) * outWidth * 4
            accumulated.fill(0f, 0, bandLength)
            if (previousLength > 0) previous.copyInto(accumulated, (previousTop - bandTop) * outWidth * 4, endIndex = previousLength)
            var left = 0
            while (left < width) {
                checkActive()
                val right = minOf(width, left + stepX)
                val originX = (left - overlap).coerceIn(0, maxOf(0, width - tileW))
                val originY = (top - overlap).coerceIn(0, maxOf(0, height - tileH))
                readRegion(originX, originY, regionW, regionH, pixels)
                require(pixels.all { it ushr 24 == 255 }) { "当前 AI 模型路径不改写透明像素" }
                data.clear()
                for (channel in 0 until channels) for (y in 0 until tileH) for (x in 0 until tileW) {
                    val pixel = pixels[reflect(y, regionH) * regionW + reflect(x, regionW)]
                    val value = if (channels == 1) ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) / 256f
                        else (pixel ushr (16 - channel * 8) and 255).toFloat()
                    data.put(value / 255f)
                }
                data.rewind()
                OnnxTensor.createTensor(environment, data, inputShape).use { tensor ->
                    val inputs = mapOf(inputName to tensor)
                    (if (runOptions == null) session.run(inputs) else session.run(inputs, runOptions)).use { result ->
                        val image = result[0] as? OnnxTensor ?: error("模型未输出图像张量")
                        val shape = image.info.shape
                        require(image.info.type == OnnxJavaType.FLOAT && shape.size == 4 && shape[0] == 1L && shape[1] in longArrayOf(1, 3) &&
                            (!requireRgb || shape[1] == 3L) && shape[2] == tileH.toLong() * scale && shape[3] == tileW.toLong() * scale) {
                            "模型实际输出与声明倍率或格式不符"
                        }
                        val values = image.floatBuffer
                        val outputChannels = shape[1].toInt()
                        val stride = tileW * scale
                        val plane = stride * tileH * scale
                        val drawLeft = maxOf(0, left - blend) * scale
                        val drawRight = minOf(width, right + blend) * scale
                        for (y in bandTop until bandBottom) for (x in drawLeft until drawRight) {
                            val px = x.toFloat() / scale
                            val py = y.toFloat() / scale
                            val weight = blendWeight(px, left, right, width) * blendWeight(py, top, bottom, height)
                            val index = (y - bandTop) * outWidth * 4 + x * 4
                            val sample = (y - originY * scale) * stride + x - originX * scale
                            for (channel in 0..2) {
                                val value = values.get((if (outputChannels == 1) 0 else channel) * plane + sample)
                                require(value.isFinite()) { "模型输出含非有限像素" }
                                accumulated[index + channel] += value.coerceIn(0f, 1f) * weight
                            }
                            accumulated[index + 3] += weight
                        }
                    }
                }
                onTile(++completed, total)
                left = right
            }
            val flushBottom = if (bottom == height) bandBottom else (bottom - blend) * scale
            for (y in bandTop until flushBottom) {
                for (x in 0 until outWidth) {
                    val p = (y - bandTop) * outWidth * 4 + x * 4
                    val weight = accumulated[p + 3]
                    check(weight > 0f) { "分块没有完整覆盖画面" }
                    fun channel(offset: Int) = (accumulated[p + offset] / weight * 255f).roundToInt().coerceIn(0, 255)
                    row[x] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                }
                writeRow(y, row)
            }
            previousTop = flushBottom
            previousLength = (bandBottom - flushBottom) * outWidth * 4
            if (previousLength > 0) accumulated.copyInto(previous, startIndex = (flushBottom - bandTop) * outWidth * 4,
                endIndex = bandLength)
            top = bottom
        }
    }

    private fun tileDimension(value: Long, requested: Int): Int {
        if (value <= 0L) return requested.coerceIn(64, 512)
        require(value in 32..1024) { "当前模型固定分块超出手机支持范围" }
        return value.toInt()
    }

    private fun reflect(value: Int, length: Int): Int {
        if (length <= 1) return 0
        val cycle = (length - 1) * 2
        val at = value % cycle
        return if (at < length) at else cycle - at
    }

    private fun blendWeight(value: Float, start: Int, end: Int, limit: Int): Float = when {
        start > 0 && value < start + blend -> ((value - (start - blend) + 0.5f) / (2 * blend)).coerceIn(0.001f, 1f)
        end < limit && value > end - blend -> (((end + blend) - value - 0.5f) / (2 * blend)).coerceIn(0.001f, 1f)
        else -> 1f
    }
}
