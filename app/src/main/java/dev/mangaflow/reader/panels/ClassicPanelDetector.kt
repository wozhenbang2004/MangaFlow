package dev.mangaflow.reader.panels

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Kotlin adaptation of the XY gutter branch of jsneij/paneldet, MIT, Copyright (c) 2026 Jorge Sneij.
 * Source: paneldet.py at f0ef27986969fce6ca2dd75e2be2962b56db376c.
 * Changes: bounded Android bitmap input, cooperative cancellation, normalized output and safe margins.
 * Connected components and rotated-image deskew are deliberately not part of this implementation.
 */
internal object ClassicPanelDetector {
    const val VERSION = "paneldet-xy-f0ef279-1"
    private const val MAX_EDGE = 800
    private const val MAX_PANELS = 20

    suspend fun detect(bitmap: Bitmap): List<PanelBox> {
        val ratio = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true) else bitmap
        try {
            val width = small.width
            val height = small.height
            if (width < 32 || height < 32) return listOf(PanelBox.FULL)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            val gray = IntArray(pixels.size)
            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                for (x in 0 until width) {
                    val rgb = pixels[y * width + x]
                    val luminance = .2126 * linear[(rgb ushr 16) and 255] +
                        .7152 * linear[(rgb ushr 8) and 255] + .0722 * linear[rgb and 255]
                    gray[y * width + x] = ((if (luminance <= .0031308) luminance * 12.92
                        else 1.055 * luminance.pow(1.0 / 2.4) - .055) * 255).roundToInt()
                }
            }
            val border = IntArray(width * 2 + height * 2)
            for (x in 0 until width) { border[x] = gray[x]; border[width + x] = gray[(height - 1) * width + x] }
            for (y in 0 until height) { border[width * 2 + y] = gray[y * width]; border[width * 2 + height + y] = gray[y * width + width - 1] }
            val background = median(border)
            val borderFlatness = median(border.map { abs(it - background) }.toIntArray())
            val thickness = maxOf(2, (minOf(width, height) * .02f).toInt())
            val strips = listOf(
                collect(gray, width, 0, 0, width, thickness),
                collect(gray, width, 0, height - thickness, width, height),
                collect(gray, width, 0, 0, thickness, height),
                collect(gray, width, width - thickness, 0, width, height),
            )
            val noise = strips.minOf { strip -> val middle = median(strip); median(strip.map { abs(it - middle) }.toIntArray()) }
            val tolerance = maxOf(26f, noise * 3f).coerceAtMost(72f)
            val backgrounds = mutableListOf(background)
            for (alternative in listOf(255, 0)) if (backgrounds.all { abs(it - alternative) > 40 }) backgrounds.add(alternative)
            val reference = if (borderFlatness <= 6) background
                else backgrounds.minBy { tone -> gray.count { abs(it - tone) > tolerance } }
            val mask = BooleanArray(gray.size) { abs(gray[it] - reference) > tolerance }
            val ink = mask.count { it }
            if (ink == 0) return listOf(PanelBox.FULL)
            val minDimension = maxOf(8, (minOf(width, height) * .045f).toInt())
            var best: Candidate? = null
            for (tone in backgrounds) {
                currentCoroutineContext().ensureActive()
                val raw = mutableListOf<Bounds>()
                split(gray, width, Bounds(0, 0, width, height), minDimension, tone, tolerance, 0, raw)
                val boxes = raw.filter { box ->
                    box.width >= minDimension && box.height >= minDimension && box.area >= width * height * .012f &&
                        count(mask, width, box).toFloat() / box.area >= .55f
                }
                // A sparse cover or prose must never become a sequence of tiny "panels".
                if (boxes.size !in 2..MAX_PANELS) continue
                val covered = BooleanArray(gray.size)
                for (box in boxes) for (y in box.top until box.bottom) for (x in box.left until box.right) covered[y * width + x] = true
                var reached = 0
                var waste = 0
                var coverage = 0
                for (i in covered.indices) if (covered[i]) {
                    coverage++
                    if (mask[i]) reached++ else waste++
                }
                val recall = reached.toFloat() / ink
                if (recall < .85f || coverage.toFloat() / gray.size < .55f) continue
                val candidate = Candidate(boxes, recall - 1.5f * waste / gray.size - .01f * boxes.size)
                if (best == null || candidate.score > best.score) best = candidate
            }
            return best?.boxes?.map { box ->
                // Gutters determine splits; a small outward margin preserves borders and nearby lettering.
                PanelBox((box.left.toFloat() / width - .012f).coerceAtLeast(0f),
                    (box.top.toFloat() / height - .012f).coerceAtLeast(0f),
                    (box.right.toFloat() / width + .012f).coerceAtMost(1f),
                    (box.bottom.toFloat() / height + .012f).coerceAtMost(1f))
            } ?: listOf(PanelBox.FULL)
        } finally { if (small !== bitmap) small.recycle() }
    }

    private suspend fun split(gray: IntArray, stride: Int, box: Bounds, minDimension: Int,
        background: Int, tolerance: Float, depth: Int, result: MutableList<Bounds>) {
        currentCoroutineContext().ensureActive()
        if (depth > 14 || result.size > MAX_PANELS || box.height < minDimension * 2 || box.width < minDimension * 2) {
            result.add(box)
            return
        }
        for (horizontal in listOf(true, false)) {
            val length = if (horizontal) box.height else box.width
            val across = if (horizontal) box.width else box.height
            val content = BooleanArray(length)
            for (line in 0 until length) {
                if (line % 32 == 0) currentCoroutineContext().ensureActive()
                var separatorPixels = 0
                for (offset in 0 until across) {
                    val value = if (horizontal) gray[(box.top + line) * stride + box.left + offset]
                        else gray[(box.top + offset) * stride + box.left + line]
                    if (abs(value - background) <= tolerance || value < 80) separatorPixels++
                }
                content[line] = separatorPixels.toFloat() / across < .995f
            }
            val bands = runs(content, minDimension)
            if (bands.size > 1) {
                for ((a, b) in bands) split(gray, stride,
                    if (horizontal) Bounds(box.left, box.top + a, box.right, box.top + b)
                    else Bounds(box.left + a, box.top, box.left + b, box.bottom),
                    minDimension, background, tolerance, depth + 1, result)
                return
            }
        }
        result.add(box)
    }

    private fun runs(flags: BooleanArray, minLength: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in 0..flags.size) {
            val set = i < flags.size && flags[i]
            if (set && start < 0) start = i
            else if (!set && start >= 0) {
                if (i - start >= minLength) out.add(start to i)
                start = -1
            }
        }
        return out
    }

    private fun collect(gray: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray =
        IntArray((right - left) * (bottom - top)) { i -> gray[(top + i / (right - left)) * stride + left + i % (right - left)] }

    private fun count(mask: BooleanArray, stride: Int, box: Bounds): Int {
        var count = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (mask[y * stride + x]) count++
        return count
    }

    private fun median(values: IntArray): Int = values.sortedArray()[values.size / 2]
    private val linear = DoubleArray(256) { val c = it / 255.0; if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4) }
    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width * height
    }
    private data class Candidate(val boxes: List<Bounds>, val score: Float)
}
