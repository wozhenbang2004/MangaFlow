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
        if (!animeHlsImageSignature(signature)) return 0

        // Allocate the bounded scan buffer only after an actual image signature.
        val bytes = ByteArray(ANIME_HLS_IMAGE_INSPECTION_LIMIT)
        signature.copyInto(bytes)
        while (true) {
            val offset = animeHlsTransportOffset(bytes, size)
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
