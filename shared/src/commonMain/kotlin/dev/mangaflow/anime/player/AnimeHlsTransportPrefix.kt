package dev.mangaflow.anime.player

private const val TRANSPORT_PACKET_BYTES = 188
private const val TRANSPORT_CONFIRM_PACKETS = 5
internal const val ANIME_HLS_IMAGE_PREFIX_LIMIT = 256 * 1024
internal const val ANIME_HLS_IMAGE_INSPECTION_LIMIT =
    ANIME_HLS_IMAGE_PREFIX_LIMIT + TRANSPORT_PACKET_BYTES * TRANSPORT_CONFIRM_PACKETS

internal fun isIndependentAnimeHlsSegmentPlaylist(text: String): Boolean {
    val lines = text.lineSequence().map(String::trim)
    return lines.any { it.startsWith("#EXTINF:") } && lines.none { line ->
        line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") ||
            line.startsWith("#EXT-X-BYTERANGE:") || line.startsWith("#EXT-X-MAP:") ||
            line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:") ||
            line == "#EXT-X-I-FRAMES-ONLY"
    }
}

internal fun animeHlsImageSignature(bytes: ByteArray): Boolean = pngSignature(bytes) || jpegSignature(bytes)

/** Accept only an actual image boundary and five complete MPEG-TS packets at that exact byte. */
internal fun animeHlsTransportOffset(bytes: ByteArray, size: Int): Int = when {
    size < 8 -> 0
    pngSignature(bytes) -> pngTransportOffset(bytes, size)
    jpegSignature(bytes) -> jpegTransportOffset(bytes, size)
    else -> 0
}

private fun pngSignature(bytes: ByteArray): Boolean =
    bytes[0].unsigned() == 0x89 && bytes[1].unsigned() == 0x50 &&
        bytes[2].unsigned() == 0x4e && bytes[3].unsigned() == 0x47 &&
        bytes[4].unsigned() == 0x0d && bytes[5].unsigned() == 0x0a &&
        bytes[6].unsigned() == 0x1a && bytes[7].unsigned() == 0x0a

private fun jpegSignature(bytes: ByteArray): Boolean =
    bytes[0].unsigned() == 0xff && bytes[1].unsigned() == 0xd8 && bytes[2].unsigned() == 0xff

private fun pngTransportOffset(bytes: ByteArray, size: Int): Int {
    if (size < 33 || pngChunkLength(bytes, 8) != 13L ||
        !pngChunkIs(bytes, 12, 0x49, 0x48, 0x44, 0x52)) return 0
    var position = 8
    while (position + 12 <= size && position < ANIME_HLS_IMAGE_PREFIX_LIMIT) {
        val length = pngChunkLength(bytes, position)
        val end = position.toLong() + 12L + length
        if (length > ANIME_HLS_IMAGE_PREFIX_LIMIT || end > size || end > ANIME_HLS_IMAGE_PREFIX_LIMIT) return 0
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
    val last = minOf(size - TRANSPORT_PACKET_BYTES * TRANSPORT_CONFIRM_PACKETS, ANIME_HLS_IMAGE_PREFIX_LIMIT)
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
