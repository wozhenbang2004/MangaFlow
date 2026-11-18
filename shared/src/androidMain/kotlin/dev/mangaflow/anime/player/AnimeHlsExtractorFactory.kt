@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package dev.mangaflow.anime.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * Some source CDNs prepend an actual PNG/JPEG to MPEG-TS segments. Recognize only
 * a complete image boundary followed by five complete transport packets.
 *
 * Keep the original DataSource/cache bytes and ExtractorInput positions: Media3
 * accounts for consumed prefixes when retrying plain, byte-range and decrypted
 * HLS segments. This also applies to the same raw bytes in offline downloads.
 * Protocol evidence and the pinned Media3 contract are in docs/anime/lanerc-port.md.
 */
internal class AnimeHlsExtractorFactory : HlsExtractorFactory {
    private val delegate = DefaultHlsExtractorFactory()

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        skipConfirmedImagePrefix(sniffingExtractorInput)
        val extractor = delegate.createExtractor(uri, format, muxedCaptionFormats,
            timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
        return ImagePrefixedHlsExtractor(extractor, sniffingExtractorInput)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
}

private class ImagePrefixedHlsExtractor(
    private val delegate: HlsMediaChunkExtractor,
    private var inspectedInput: ExtractorInput? = null,
) : HlsMediaChunkExtractor {
    override fun init(extractorOutput: ExtractorOutput) = delegate.init(extractorOutput)

    override fun read(extractorInput: ExtractorInput): Boolean {
        // A reusable TS extractor sees a new input for every segment and retry.
        // Only mark an input after sniffing succeeds; I/O failures must remain retryable.
        if (inspectedInput !== extractorInput) {
            skipConfirmedImagePrefix(extractorInput)
            inspectedInput = extractorInput
        }
        return delegate.read(extractorInput)
    }

    override fun isPackedAudioExtractor(): Boolean = delegate.isPackedAudioExtractor
    override fun isReusable(): Boolean = delegate.isReusable
    override fun recreate(): HlsMediaChunkExtractor = ImagePrefixedHlsExtractor(delegate.recreate())
    override fun onTruncatedSegmentParsed() = delegate.onTruncatedSegmentParsed()
}

private const val TRANSPORT_PACKET_BYTES = 188
private const val TRANSPORT_CONFIRM_PACKETS = 5
private const val IMAGE_PREFIX_LIMIT = 256 * 1024
private const val PREFIX_PEEK_BLOCK = 4096

private fun skipConfirmedImagePrefix(input: ExtractorInput) {
    val offset = imageTransportOffset(input)
    if (offset > 0) {
        // All bytes being skipped have already been peeked, so no second network
        // read or alternate byte-position accounting is introduced here.
        input.skipFully(offset)
    }
}

/** No match, truncated image or an over-budget prefix leaves the input unchanged. */
private fun imageTransportOffset(input: ExtractorInput): Int {
    input.resetPeekPosition()
    try {
        val signature = ByteArray(8)
        var size = 0
        while (size < signature.size) {
            val count = input.peek(signature, size, signature.size - size)
            if (count == C.RESULT_END_OF_INPUT) return 0
            if (count <= 0) return 0
            size += count
        }
        val png = signature[0].unsigned() == 0x89 && signature[1].unsigned() == 0x50 &&
            signature[2].unsigned() == 0x4e && signature[3].unsigned() == 0x47 &&
            signature[4].unsigned() == 0x0d && signature[5].unsigned() == 0x0a &&
            signature[6].unsigned() == 0x1a && signature[7].unsigned() == 0x0a
        val jpeg = signature[0].unsigned() == 0xff && signature[1].unsigned() == 0xd8 &&
            signature[2].unsigned() == 0xff
        if (!png && !jpeg) return 0

        // Allocate the bounded scan buffer only after an actual image signature.
        val bytes = ByteArray(IMAGE_PREFIX_LIMIT + TRANSPORT_PACKET_BYTES * TRANSPORT_CONFIRM_PACKETS)
        signature.copyInto(bytes)
        while (true) {
            val offset = if (png) pngTransportOffset(bytes, size) else jpegTransportOffset(bytes, size)
            if (offset > 0) return offset
            if (size >= bytes.size) return 0
            val count = input.peek(bytes, size, minOf(PREFIX_PEEK_BLOCK, bytes.size - size))
            if (count == C.RESULT_END_OF_INPUT || count <= 0) return 0
            size += count
        }
    } catch (_: EOFException) {
        return 0
    } finally {
        // peek() is observational even when transport throws. Other I/O errors
        // propagate to Media3's normal retry/error handling.
        input.resetPeekPosition()
    }
}

private fun pngTransportOffset(bytes: ByteArray, size: Int): Int {
    if (size < 33 || pngChunkLength(bytes, 8) != 13L ||
        !pngChunkIs(bytes, 12, 0x49, 0x48, 0x44, 0x52)) return 0
    var position = 8
    while (position + 12 <= size && position < IMAGE_PREFIX_LIMIT) {
        val length = pngChunkLength(bytes, position)
        val end = position.toLong() + 12L + length
        if (length > IMAGE_PREFIX_LIMIT || end > size || end > IMAGE_PREFIX_LIMIT) return 0
        if (pngChunkIs(bytes, position + 4, 0x49, 0x45, 0x4e, 0x44)) {
            val offset = end.toInt()
            return if (length == 0L && hasTransportPackets(bytes, size, offset)) offset else 0
        }
        position = end.toInt()
    }
    return 0
}

private fun pngChunkLength(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].unsigned().toLong() shl 24) or
        (bytes[offset + 1].unsigned().toLong() shl 16) or
        (bytes[offset + 2].unsigned().toLong() shl 8) or bytes[offset + 3].unsigned().toLong()

private fun pngChunkIs(bytes: ByteArray, offset: Int, a: Int, b: Int, c: Int, d: Int): Boolean =
    bytes[offset].unsigned() == a && bytes[offset + 1].unsigned() == b &&
        bytes[offset + 2].unsigned() == c && bytes[offset + 3].unsigned() == d

private fun jpegTransportOffset(bytes: ByteArray, size: Int): Int {
    val last = minOf(size - TRANSPORT_PACKET_BYTES * TRANSPORT_CONFIRM_PACKETS, IMAGE_PREFIX_LIMIT)
    // An embedded thumbnail may have its own EOI. It is not accepted unless
    // complete consecutive transport packets immediately follow that boundary.
    for (offset in 4..last) {
        if (bytes[offset - 2].unsigned() == 0xff && bytes[offset - 1].unsigned() == 0xd9 &&
            hasTransportPackets(bytes, size, offset)) return offset
    }
    return 0
}

private fun hasTransportPackets(bytes: ByteArray, size: Int, offset: Int): Boolean {
    if (offset <= 0 || offset + TRANSPORT_PACKET_BYTES * TRANSPORT_CONFIRM_PACKETS > size) return false
    repeat(TRANSPORT_CONFIRM_PACKETS) { packet ->
        val start = offset + packet * TRANSPORT_PACKET_BYTES
        if (bytes[start].unsigned() != 0x47 || (bytes[start + 1].unsigned() and 0x80) != 0 ||
            (bytes[start + 3].unsigned() and 0x30) == 0) return false
    }
    return true
}

private fun Byte.unsigned(): Int = toInt() and 0xff
