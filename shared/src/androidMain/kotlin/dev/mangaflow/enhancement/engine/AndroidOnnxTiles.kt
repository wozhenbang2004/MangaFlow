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
    private val opaqueBitmapSource: Boolean = false,
) {
    private val input = session.inputInfo.values.single().info as TensorInfo
    private val inputName = session.inputNames.single()
    val outWidth = Math.multiplyExact(width, scale)
    val outHeight = Math.multiplyExact(height, scale)
    private val channels = input.shape[1].toInt()
    private val layout = tileLayout(input.shape[3], input.shape[2], tileSize, overlapSize, budget)
    private val tileW = layout.width
    private val tileH = layout.height
    private val overlap = layout.overlap
    private val stepX = tileW - 2 * overlap
    private val stepY = tileH - 2 * overlap
    private val blend = minOf(8, overlap / 2)
    val tileDescription: String get() = "${tileW}×${tileH}"
    private val total = ((width.toLong() + stepX - 1) / stepX) * ((height.toLong() + stepY - 1) / stepY)
    private val inputShape = longArrayOf(1, channels.toLong(), tileH.toLong(), tileW.toLong())
    private val environment = OrtEnvironment.getEnvironment()
    private val data = ByteBuffer.allocateDirect(tileW * tileH * channels * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val regionW = minOf(width, tileW)
    private val regionH = minOf(height, tileH)
    private val pixels = IntArray(regionW * regionH)
    private val sampleX = IntArray(tileW) { reflect(it, regionW) }
    private val sampleRow = IntArray(tileH) { reflect(it, regionH) * regionW }
    private val middleRows = minOf(outHeight, stepY * scale)
    private val seamWidth = 2 * blend * scale
    private val edgeRows = minOf(outHeight, seamWidth)
    private val middle = IntArray(Math.multiplyExact(outWidth, middleRows))
    private val horizontal = FloatArray(Math.toIntExact(middleRows.toLong() * seamWidth * 4))
    private val topSeam = FloatArray(Math.toIntExact(outWidth.toLong() * edgeRows * 4))
    private val bottomSeam = FloatArray(topSeam.size)
    private val row = IntArray(outWidth)
    private val weightsX = FloatArray(tileW * scale)
    val sourceCacheBytes = budget - layout.workingBytes

    init {
        require(!requireRgb || channels == 3) { "番剧模型需要 RGB 三通道输入" }
    }

    fun render(
        checkActive: () -> Unit,
        readRegion: (left: Int, top: Int, width: Int, height: Int, pixels: IntArray) -> Unit,
        writeRow: (y: Int, pixels: IntArray) -> Unit,
        onTile: (completed: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        // A tensor created over a direct native-order buffer reads that same storage at each run.
        data.clear()
        OnnxTensor.createTensor(environment, data, inputShape).use { tensor ->
            renderTiles(mapOf(inputName to tensor), checkActive, readRegion, writeRow, onTile)
        }
    }

    private fun renderTiles(
        inputs: Map<String, OnnxTensor>,
        checkActive: () -> Unit,
        readRegion: (Int, Int, Int, Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit,
        onTile: (Long, Long) -> Unit,
    ) {
        var leading = topSeam
        var trailing = bottomSeam
        var leadingRows = 0
        var completed = 0L
        var top = 0
        onTile(0L, total)
        while (top < height) {
            checkActive()
            val bottom = minOf(height, top + stepY)
            val bandTop = maxOf(0, top - blend) * scale
            val bandBottom = minOf(height, bottom + blend) * scale
            val middleStart = bandTop + leadingRows
            val middleEnd = if (bottom == height) bandBottom else (bottom - blend) * scale
            trailing.fill(0f, 0, (bandBottom - middleEnd) * outWidth * 4)
            val originY = (top - overlap).coerceIn(0, maxOf(0, height - tileH))
            var left = 0
            while (left < width) {
                checkActive()
                val right = minOf(width, left + stepX)
                val originX = (left - overlap).coerceIn(0, maxOf(0, width - tileW))
                readRegion(originX, originY, regionW, regionH, pixels)
                fillInput()
                (if (runOptions == null) session.run(inputs) else session.run(inputs, runOptions)).use { result ->
                    val image = result[0] as? OnnxTensor ?: error("模型未输出图像张量")
                    val shape = image.info.shape
                    require(image.info.type == OnnxJavaType.FLOAT && shape.size == 4 && shape[0] == 1L &&
                        (shape[1] == 1L || shape[1] == 3L) && (!requireRgb || shape[1] == 3L) &&
                        shape[2] == tileH.toLong() * scale && shape[3] == tileW.toLong() * scale) {
                        "模型实际输出与声明倍率或格式不符"
                    }
                    val values = image.floatBuffer
                    val stride = tileW * scale
                    val plane = if (shape[1] == 1L) 0 else stride * tileH * scale
                    val drawLeft = maxOf(0, left - blend) * scale
                    val drawRight = minOf(width, right + blend) * scale
                    val mergeEnd = if (left > 0) minOf(width, left + blend) * scale else drawLeft
                    val keepStart = if (right < width) (right - blend) * scale else drawRight
                    for (x in drawLeft until drawRight) weightsX[x - drawLeft] = blendWeight(x.toFloat() / scale, left, right, width)

                    for (y in bandTop until bandBottom) {
                        if (y and 63 == 0) checkActive()
                        val weightY = blendWeight(y.toFloat() / scale, top, bottom, height)
                        val sampleBase = (y - originY * scale) * stride - originX * scale
                        val seam = when {
                            y < middleStart -> leading
                            y >= middleEnd -> trailing
                            else -> null
                        }
                        val seamBase = when {
                            y < middleStart -> (y - bandTop) * outWidth * 4
                            y >= middleEnd -> (y - middleEnd) * outWidth * 4
                            else -> 0
                        }
                        val middleBase = (y - middleStart) * outWidth
                        val horizontalBase = (y - middleStart) * seamWidth * 4
                        for (x in drawLeft until drawRight) {
                            val sample = sampleBase + x
                            val red = pixelValue(values.get(sample))
                            val green = if (plane == 0) red else pixelValue(values.get(plane + sample))
                            val blue = if (plane == 0) red else pixelValue(values.get(2 * plane + sample))
                            val weight = weightsX[x - drawLeft] * weightY
                            when {
                                seam != null -> addPixel(seam, seamBase + x * 4, red, green, blue, weight)
                                x < mergeEnd -> {
                                    val index = horizontalBase + (x - drawLeft) * 4
                                    addPixel(horizontal, index, red, green, blue, weight)
                                    middle[middleBase + x] = blendedPixel(horizontal, index)
                                }
                                x >= keepStart -> {
                                    val index = horizontalBase + (x - keepStart) * 4
                                    horizontal[index] = red * weight
                                    horizontal[index + 1] = green * weight
                                    horizontal[index + 2] = blue * weight
                                    horizontal[index + 3] = weight
                                }
                                else -> middle[middleBase + x] = pack(red, green, blue)
                            }
                        }
                    }
                }
                onTile(++completed, total)
                left = right
            }
            for (y in bandTop until middleStart) {
                val start = (y - bandTop) * outWidth * 4
                for (x in 0 until outWidth) row[x] = blendedPixel(leading, start + x * 4)
                writeRow(y, row)
            }
            for (y in middleStart until middleEnd) {
                val from = (y - middleStart) * outWidth
                middle.copyInto(row, 0, from, from + outWidth)
                writeRow(y, row)
            }
            val swap = leading
            leading = trailing
            trailing = swap
            leadingRows = bandBottom - middleEnd
            top = bottom
        }
    }

    private fun fillInput() {
        if (!opaqueBitmapSource) require(pixels.all { it ushr 24 == 255 }) { "当前 AI 模型路径不改写透明像素" }
        data.clear()
        for (channel in 0 until channels) {
            val shift = 16 - channel * 8
            for (offset in sampleRow) for (x in sampleX) {
                val pixel = pixels[offset + x]
                val value = if (channels == 1) {
                    ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) / 256f
                } else (pixel ushr shift and 255).toFloat()
                data.put(value / 255f)
            }
        }
        data.rewind()
    }

    private fun pixelValue(value: Float): Float {
        require(value.isFinite()) { "模型输出含非有限像素" }
        return value.coerceIn(0f, 1f)
    }

    private fun addPixel(seam: FloatArray, at: Int, r: Float, g: Float, b: Float, weight: Float) {
        seam[at] += r * weight
        seam[at + 1] += g * weight
        seam[at + 2] += b * weight
        seam[at + 3] += weight
    }

    private fun blendedPixel(seam: FloatArray, at: Int): Int {
        val weight = seam[at + 3]
        check(weight > 0f) { "分块没有完整覆盖画面" }
        return pack(seam[at] / weight, seam[at + 1] / weight, seam[at + 2] / weight)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        fun byte(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        return (255 shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /** Only overlapping edges need float channels and weights. Completed interior pixels use one Int. */
    private fun tileLayout(fixedWidth: Long, fixedHeight: Long, requested: Int, requestedOverlap: Int,
        budget: Long): TileLayout {
        var tileWidth = tileDimension(fixedWidth, requested)
        var tileHeight = tileDimension(fixedHeight, requested)
        val padding = maxOf(16, requestedOverlap).coerceAtMost(minOf(tileWidth, tileHeight) / 4)
        val minimumDynamicSize = maxOf(64, padding * 4)
        while (true) {
            val fade = minOf(8, padding / 2)
            val rows = minOf(outHeight, (tileHeight - 2 * padding) * scale)
            val seam = 2 * fade * scale
            val sourceTile = minOf(width, tileWidth).toLong() * minOf(height, tileHeight)
            val inputFloats = tileWidth.toLong() * tileHeight * channels
            val outputFloats = tileWidth.toLong() * tileHeight * scale * scale * 3
            val middlePixels = outWidth.toLong() * rows
            val horizontalFloats = rows.toLong() * seam * 4
            val verticalFloats = outWidth.toLong() * minOf(outHeight, seam) * 4
            // Native output + Java copy, one direct input tensor, ARGB region, packed rows,
            // lookup tables, PNG row and both sets of full-precision seam accumulators.
            val bytes = (2 * outputFloats + inputFloats + sourceTile + middlePixels + horizontalFloats +
                2 * verticalFloats + 2L * outWidth + tileWidth + tileHeight + tileWidth * scale) * 4 + 1
            val minimum = bytes + if (opaqueBitmapSource) sourceTile * 4 else 0L
            if (minimum <= budget) return TileLayout(tileWidth, tileHeight, padding, bytes)
            val nextWidth = if (fixedWidth <= 0L && tileWidth > minimumDynamicSize)
                maxOf(minimumDynamicSize, tileWidth - 64) else tileWidth
            val nextHeight = if (fixedHeight <= 0L && tileHeight > minimumDynamicSize)
                maxOf(minimumDynamicSize, tileHeight - 64) else tileHeight
            require(nextWidth != tileWidth || nextHeight != tileHeight) {
                val minimumMiB = (minimum + 1024 * 1024 - 1) / (1024 * 1024)
                val availableMiB = budget / (1024 * 1024)
                "此页的 ${tileWidth}×${tileHeight} 分块缓冲至少需要 ${minimumMiB} MiB，当前处理预算 ${availableMiB} MiB；可选择更低倍率的模型"
            }
            tileWidth = nextWidth
            tileHeight = nextHeight
        }
    }

    private data class TileLayout(val width: Int, val height: Int, val overlap: Int, val workingBytes: Long)

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
