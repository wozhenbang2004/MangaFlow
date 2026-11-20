@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun platformAnimeSampleHeight(bytes: ByteArray): Int? {
    if (bytes.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        transportSampleHeight(bytes)?.let { return@withContext it }
        // This source owns only the bytes already read by the bounded probe.
        val source = object : MediaDataSource() {
            override fun getSize(): Long = bytes.size.toLong()
            override fun close() = Unit
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (size == 0) return 0
                if (position < 0L || position >= bytes.size) return -1
                val from = position.toInt()
                val count = minOf(size, bytes.size - from)
                bytes.copyInto(buffer, offset, from, from + count)
                return count
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            (0 until extractor.trackCount).firstNotNullOfOrNull { track ->
                val format = extractor.getTrackFormat(track)
                val video = format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                if (video && format.containsKey(MediaFormat.KEY_HEIGHT))
                    format.getInteger(MediaFormat.KEY_HEIGHT).takeIf { it in 144..8640 }
                else null
            }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}

private fun transportSampleHeight(bytes: ByteArray): Int? {
    if (bytes.size < TsExtractor.TS_PACKET_SIZE * 5) return null
    var position = 0
    val reader = DataReader { target, offset, length ->
        if (position == bytes.size) C.RESULT_END_OF_INPUT else {
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            count
        }
    }
    val input = DefaultExtractorInput(reader, 0L, bytes.size.toLong())
    val extractor = TsExtractor(TsExtractor.MODE_HLS, 0, SubtitleParser.Factory.UNSUPPORTED,
        TimestampAdjuster(0L), DefaultTsPayloadReaderFactory(), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
    var height: Int? = null
    val sink = DiscardingTrackOutput()
    return try {
        if (!extractor.sniff(input)) return null
        input.resetPeekPosition()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type != C.TRACK_TYPE_VIDEO) sink
                else object : ForwardingTrackOutput(sink) {
                    override fun format(format: Format) {
                        height = format.height.takeIf { it in 144..8640 }
                    }
                }

            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val seek = PositionHolder()
        repeat(bytes.size / TsExtractor.TS_PACKET_SIZE + 8) {
            if (extractor.read(input, seek) != Extractor.RESULT_CONTINUE || height != null) return height
        }
        height
    } catch (_: Exception) {
        null
    } finally {
        extractor.release()
    }
}
