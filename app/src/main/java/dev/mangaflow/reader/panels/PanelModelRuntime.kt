/*
 * Nano preprocessing/decoding adapted from Komai's MlPanelBoundaryDetector.kt and
 * YoloPanelDecoder.kt, commit f567e2be8f30b59f04d14dd7fc589e186fb72ff8 (Apache-2.0).
 * Inkwell's exact 14-feature ordering contract is adapted from detect_and_order.py,
 * revision fa7afba52ba7dc7b0840c7702b767972b5e17a9b (reading-order model: MIT).
 * Changes: optional verified files, correct signed quantization, finite geometry,
 * bounded CPU concurrency, Kotlin/ORT buffers and right-to-left feature mirroring.
 * Full source identities and terms are in THIRD_PARTY_NOTICES.md and assets/licenses.
 */
package dev.mangaflow.reader.panels

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

/** CPU-only, on-demand native runtimes. Native calls finish before their session can close. */
class PanelModelRuntime(context: Context) {
    private val store = PanelModelStore.get(context)
    private val runtimes = PanelRuntimeStore.get(context)
    private val mutex = Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var nano: Interpreter? = null
    private var quality: OrtSession? = null
    private var detectorIdentity: String? = null
    private var ordering: OrtSession? = null
    private var orderingIdentity: String? = null

    suspend fun detect(bitmap: Bitmap, engine: PanelEngine): List<PanelBox> {
        require(engine == PanelEngine.NANO || engine == PanelEngine.HIGH_QUALITY)
        val id = if (engine == PanelEngine.NANO) PanelModelId.NANO else PanelModelId.HIGH_QUALITY
        val file = store.installedFile(id)
        runtimes.ensureLoaded(if (engine == PanelEngine.NANO) PanelRuntimeId.LITERT else PanelRuntimeId.ONNX)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                try {
                    require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
                    ensureDetector(file, id)
                    val boxes = if (id == PanelModelId.NANO) detectNano(bitmap) else detectQuality(bitmap)
                    currentCoroutineContext().ensureActive()
                    boxes
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: OutOfMemoryError) {
                    releaseDetector()
                    throw IllegalStateException("模型需要更多内存，可切换轻量或传统识别", failure)
                } catch (failure: LinkageError) {
                    releaseDetector()
                    throw IllegalStateException("此设备暂时无法载入模型，可使用传统识别", failure)
                }
            }
        }
    }

    suspend fun order(boxes: List<PanelBox>, direction: PanelDirection): List<PanelBox> {
        if (boxes.size < 2) return boxes
        require(boxes.size <= 100) { "分镜数量过多，请使用几何顺序" }
        val file = store.installedFile(PanelModelId.ORDER)
        runtimes.ensureLoaded(PanelRuntimeId.ONNX)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                try {
                    val stamp = identity(file, PanelModelId.ORDER)
                    if (orderingIdentity != stamp) {
                        ordering?.close()
                        ordering = null
                        orderingIdentity = null
                        ordering = createOnnxSession(file)
                        orderingIdentity = stamp
                    }
                    val session = checkNotNull(ordering)
                    val geometry = boxes.map { box ->
                        if (direction == PanelDirection.RIGHT_TO_LEFT) {
                            box.copy(left = 1f - box.right, right = 1f - box.left)
                        } else box
                    }
                    val heights = geometry.map { it.height }.sorted()
                    val middle = heights.size / 2
                    val medianHeight = if (heights.size % 2 == 0) {
                        (heights[middle - 1] + heights[middle]) / 2f
                    } else heights[middle]
                    val pairs = boxes.size * (boxes.size - 1)
                    val features = directFloats(pairs * 14)
                    for (a in geometry.indices) {
                        for (b in geometry.indices) {
                            if (a != b) features.put(pairFeatures(geometry[a], geometry[b], medianHeight, boxes.size))
                        }
                    }
                    features.rewind()
                    val scores = FloatArray(boxes.size)
                    OnnxTensor.createTensor(environment, features, longArrayOf(pairs.toLong(), 14L)).use { tensor ->
                        session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                            val probabilities = positiveProbabilities(result[1], pairs)
                            var pair = 0
                            for (a in boxes.indices) {
                                for (b in boxes.indices) if (a != b) scores[a] += probabilities[pair++]
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    boxes.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).map { boxes[it] }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: OutOfMemoryError) {
                    ordering?.close()
                    ordering = null
                    orderingIdentity = null
                    throw IllegalStateException("顺序模型内存不足，已保留几何顺序", failure)
                } catch (failure: LinkageError) {
                    throw IllegalStateException("此设备暂时无法载入顺序模型", failure)
                }
            }
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Default) {
        mutex.withLock {
            releaseDetector()
            ordering?.close()
            ordering = null
            orderingIdentity = null
            // OrtEnvironment is process-wide and remains owned by ONNX Runtime.
        }
    }

    /** Switching to the traditional engine also gives back the previous model's native memory. */
    suspend fun releaseDetectionModel() = withContext(NonCancellable + Dispatchers.Default) {
        mutex.withLock { releaseDetector() }
    }

    private fun identity(file: File, id: PanelModelId): String =
        "${store.spec(id).sha256}:${file.length()}:${file.lastModified()}"

    private fun ensureDetector(file: File, id: PanelModelId) {
        val stamp = identity(file, id)
        if (detectorIdentity == stamp) return
        releaseDetector()
        if (id == PanelModelId.NANO) {
            FileInputStream(file).channel.use { channel ->
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                nano = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            }
        } else {
            quality = createOnnxSession(file)
        }
        detectorIdentity = stamp
    }

    private fun createOnnxSession(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        options.setMemoryPatternOptimization(false)
        environment.createSession(file.absolutePath, options)
    }

    private fun releaseDetector() {
        nano?.close()
        nano = null
        quality?.close()
        quality = null
        detectorIdentity = null
    }

    private fun detectNano(page: Bitmap): List<PanelBox> {
        val interpreter = checkNotNull(nano)
        val input = interpreter.getInputTensor(0)
        val shape = input.shape()
        require(shape.contentEquals(intArrayOf(1, 640, 640, 3))) { "轻量模型输入格式不符" }
        val size = shape[1]
        val scale = minOf(size / page.width.toFloat(), size / page.height.toFloat())
        val width = (page.width * scale).roundToInt().coerceIn(1, size)
        val height = (page.height * scale).roundToInt().coerceIn(1, size)
        val padX = (size - width) / 2
        val padY = (size - height) / 2
        val resized = Bitmap.createScaledBitmap(page, width, height, true)
        val buffer = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
        val inputType = input.dataType()
        val quantization = input.quantizationParams()
        require(inputType == DataType.FLOAT32 || quantization.scale > 0f) { "轻量模型量化参数不符" }
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val inside = x - padX in 0 until width && y - padY in 0 until height
                    val pixel = if (inside) pixels[(y - padY) * width + x - padX] else 0x727272
                    putChannel(buffer, (pixel shr 16) and 255, inputType, quantization.scale, quantization.zeroPoint)
                    putChannel(buffer, (pixel shr 8) and 255, inputType, quantization.scale, quantization.zeroPoint)
                    putChannel(buffer, pixel and 255, inputType, quantization.scale, quantization.zeroPoint)
                }
            }
        } finally {
            if (resized !== page) resized.recycle()
        }
        buffer.rewind()
        val output = interpreter.getOutputTensor(0)
        val out = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(buffer, out)
        val raw = readOutput(out, output)
        val outShape = output.shape()
        require(outShape.size == 3 && outShape[0] == 1) { "轻量模型输出格式不符" }
        val transposed = outShape[1] < outShape[2]
        val attributes = if (transposed) outShape[1] else outShape[2]
        val predictions = if (transposed) outShape[2] else outShape[1]
        require(attributes == 6) { "轻量模型输出类别不符" }
        fun at(prediction: Int, attribute: Int): Float = if (transposed) {
            raw[attribute * predictions + prediction]
        } else raw[prediction * attributes + attribute]
        val endToEnd = predictions <= 1000
        val maxCoordinate = (0 until minOf(predictions, 64)).maxOfOrNull { prediction ->
            (0..3).maxOf { attribute -> at(prediction, attribute).takeIf { it.isFinite() } ?: 0f }
        } ?: 0f
        val coordinateScale = if (maxCoordinate <= 1.5f) size.toFloat() else 1f
        val boxes = ArrayList<PanelBox>()
        for (prediction in 0 until predictions) {
            val score = at(prediction, 4)
            val classValue = at(prediction, 5)
            val panel = if (endToEnd) classValue.isFinite() && classValue.roundToInt() == 0 else score >= classValue
            if (!panel || !score.isFinite() || score < .25f) continue
            val a = at(prediction, 0) * coordinateScale
            val b = at(prediction, 1) * coordinateScale
            val c = at(prediction, 2) * coordinateScale
            val d = at(prediction, 3) * coordinateScale
            val x1 = if (endToEnd) a else a - c / 2f
            val y1 = if (endToEnd) b else b - d / 2f
            val x2 = if (endToEnd) c else a + c / 2f
            val y2 = if (endToEnd) d else b + d / 2f
            normalizedBox(
                (x1 - padX) / scale / page.width,
                (y1 - padY) / scale / page.height,
                (x2 - padX) / scale / page.width,
                (y2 - padY) / scale / page.height,
                score,
            )?.let(boxes::add)
        }
        return suppress(boxes)
    }

    private fun putChannel(buffer: ByteBuffer, channel: Int, type: DataType, scale: Float, zeroPoint: Int) {
        val value = channel / 255f
        when (type) {
            DataType.FLOAT32 -> buffer.putFloat(value)
            DataType.UINT8, DataType.INT8 -> {
                val quantized = (value / scale + zeroPoint).roundToInt()
                val bounded = if (type == DataType.INT8) quantized.coerceIn(-128, 127) else quantized.coerceIn(0, 255)
                buffer.put(bounded.toByte())
            }
            else -> error("轻量模型输入类型不符")
        }
    }

    private fun readOutput(buffer: ByteBuffer, tensor: Tensor): FloatArray {
        buffer.rewind()
        val count = tensor.shape().fold(1) { size, dimension -> size * dimension }
        val type = tensor.dataType()
        return when (type) {
            DataType.FLOAT32 -> FloatArray(count) { buffer.float }
            DataType.INT8, DataType.UINT8 -> {
                val quantization = tensor.quantizationParams()
                FloatArray(count) {
                    val byte = buffer.get().toInt()
                    val integer = if (type == DataType.UINT8) byte and 255 else byte
                    (integer - quantization.zeroPoint) * quantization.scale
                }
            }
            else -> error("轻量模型输出类型不符")
        }
    }

    private fun detectQuality(page: Bitmap): List<PanelBox> {
        val size = 1152
        val resized = Bitmap.createScaledBitmap(page, size, size, true)
        val input = directFloats(3 * size * size)
        try {
            val pixels = IntArray(size * size)
            resized.getPixels(pixels, 0, size, 0, 0, size, size)
            val means = floatArrayOf(.485f, .456f, .406f)
            val deviations = floatArrayOf(.229f, .224f, .225f)
            for (channel in 0..2) {
                val shift = 16 - channel * 8
                for (pixel in pixels) {
                    input.put((((pixel shr shift) and 255) / 255f - means[channel]) / deviations[channel])
                }
            }
        } finally {
            if (resized !== page) resized.recycle()
        }
        input.rewind()
        val session = checkNotNull(quality)
        return OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            // Request boxes/logits only. Never materialize the 300 full-size instance masks in Java.
            session.run(mapOf("input" to tensor), setOf("dets", "labels")).use { result ->
                val dets = result.get("dets").get() as OnnxTensor
                val labels = result.get("labels").get() as OnnxTensor
                require(dets.info.shape.contentEquals(longArrayOf(1, 300, 4))) { "高精度模型框格式不符" }
                require(labels.info.shape.contentEquals(longArrayOf(1, 300, 5))) { "高精度模型类别格式不符" }
                val boxes = dets.floatBuffer
                val logits = labels.floatBuffer
                val found = ArrayList<PanelBox>()
                for (index in 0 until 300) {
                    var bestClass = 0
                    for (candidate in 1..3) {
                        if (logits[index * 5 + candidate] > logits[index * 5 + bestClass]) bestClass = candidate
                    }
                    if (bestClass != 3) continue
                    val score = (1.0 / (1.0 + exp(-logits[index * 5 + 3].toDouble()))).toFloat()
                    if (!score.isFinite() || score < .5f) continue
                    val centerX = boxes[index * 4]
                    val centerY = boxes[index * 4 + 1]
                    val width = boxes[index * 4 + 2]
                    val height = boxes[index * 4 + 3]
                    normalizedBox(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f, score)
                        ?.let(found::add)
                }
                suppress(found)
            }
        }
    }

    private fun normalizedBox(left: Float, top: Float, right: Float, bottom: Float, score: Float): PanelBox? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val box = PanelBox(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f), score.coerceIn(0f, 1f))
        return box.takeIf { it.width >= .01f && it.height >= .01f && it.width * it.height >= .001f }
    }

    private fun suppress(boxes: List<PanelBox>): List<PanelBox> {
        val kept = mutableListOf<PanelBox>()
        for (box in boxes.sortedByDescending { it.confidence }) {
            val area = box.width * box.height
            val duplicate = kept.any { other ->
                val intersection = (minOf(box.right, other.right) - maxOf(box.left, other.left)).coerceAtLeast(0f) *
                    (minOf(box.bottom, other.bottom) - maxOf(box.top, other.top)).coerceAtLeast(0f)
                val otherArea = other.width * other.height
                intersection / (area + otherArea - intersection).coerceAtLeast(.000001f) > .45f ||
                    (intersection / minOf(area, otherArea).coerceAtLeast(.000001f) > .9f &&
                        minOf(area, otherArea) / maxOf(area, otherArea) > .65f)
            }
            if (!duplicate) kept.add(box)
        }
        return kept.take(100)
    }

    private fun pairFeatures(a: PanelBox, b: PanelBox, medianHeight: Float, count: Int): FloatArray {
        val dx = b.centerX - a.centerX
        val verticalOverlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f) /
            minOf(a.height, b.height).coerceAtLeast(.000001f)
        val horizontalOverlap = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) /
            minOf(a.width, b.width).coerceAtLeast(.000001f)
        fun flag(value: Boolean) = if (value) 1f else 0f
        return floatArrayOf(
            dx, b.centerY - a.centerY, verticalOverlap, horizontalOverlap,
            a.height / b.height.coerceAtLeast(.000001f), a.width / b.width.coerceAtLeast(.000001f),
            flag(a.centerX < .5f), flag(b.centerX < .5f), flag(a.centerY < .5f), flag(b.centerY < .5f),
            flag(a.height > medianHeight * 1.4f), flag(b.height > medianHeight * 1.4f),
            count / 20f, flag(verticalOverlap >= .3f && dx > 0),
        )
    }

    private fun positiveProbabilities(value: OnnxValue, expected: Int): FloatArray = when (value) {
        is OnnxTensor -> {
            require(value.info.shape.contentEquals(longArrayOf(expected.toLong(), 2L))) { "顺序模型输出格式不符" }
            val buffer = value.floatBuffer
            FloatArray(expected) { index -> buffer[index * 2 + 1] }
        }
        is OnnxSequence -> {
            // Sequence.getValue creates native wrappers which must be closed separately.
            val entries = value.value
            try {
                require(entries.size == expected) { "顺序模型输出数量不符" }
                FloatArray(expected) { index ->
                    val map = (entries[index] as OnnxMap).value
                    val probability = map[1L] ?: map[1]
                    (probability as? Number)?.toFloat()?.takeIf { it.isFinite() }
                        ?: error("顺序模型概率格式不符")
                }
            } finally {
                entries.forEach { it.close() }
            }
        }
        else -> error("顺序模型输出类型不符")
    }

    private fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        const val VERSION = "models-y26n40a285-k1152bfbbd4-orderfa7afb-v1"
    }
}
