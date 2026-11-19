package dev.mangaflow.anime.downloads

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.NetworkClient

internal data class AnimeHlsDownloadPlan(
    val playlist: String,
    val resources: List<AnimeResourcePlan>,
    val identity: String,
    val childPlaylists: Map<String, String> = emptyMap(),
)

/** Download the exact video variant and the source-declared default rendition as one local HLS package. */
internal suspend fun loadAnimeHlsDownload(asset: AnimePlaybackAsset, client: NetworkClient,
    guard: () -> Unit): AnimeHlsDownloadPlan {
    guard()
    val (base, text) = fetchAnimePlaylist(asset, client)
    val lines = hlsLines(text)
    if (lines.none { it.startsWith("#EXT-X-STREAM-INF:") }) return parseAnimeHlsMedia(base, lines, firstResource = 0)
    val selection = parseDefaultAudioMaster(base, lines)
    guard()
    val (videoBase, videoText) = fetchAnimePlaylist(asset, client, selection.video)
    val video = parseAnimeHlsDownload(videoBase, videoText)
    guard()
    val (audioBase, audioText) = fetchAnimePlaylist(asset, client, selection.audio)
    require(audioBase != videoBase) { "HLS 视频和默认音轨解析为同一个地址，无法确认独立音频" }
    val audio = parseAnimeHlsMedia(audioBase, hlsLines(audioText), firstResource = video.resources.size)
    return AnimeHlsDownloadPlan(selection.playlist, video.resources + audio.resources.map { it.copy(audioRendition = true) },
        animeDigest(selection.playlist + video.identity + audio.identity),
        mapOf("video.m3u8" to video.playlist, "audio.m3u8" to audio.playlist))
}

private data class AnimeHlsDefaultRendition(val video: HttpUrl, val audio: HttpUrl, val playlist: String)

private fun parseDefaultAudioMaster(base: HttpUrl, lines: List<String>): AnimeHlsDefaultRendition {
    require(lines.firstOrNull() == "#EXTM3U") { "来源返回的内容不是有效 HLS 清单" }
    val variants = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF:") }
    require(variants.size == 1) { "这份 HLS 有多个画质，请选择具体画质线路再下载" }
    val variant = variants.single()
    require(lines.none { line -> line.startsWith("#EXT") && !line.startsWith("#EXT-X-STREAM-INF:") &&
        !line.startsWith("#EXT-X-MEDIA:") && !line.startsWith("#EXT-X-VERSION:") &&
        line != "#EXTM3U" && line != "#EXT-X-INDEPENDENT-SEGMENTS" }) {
        "这份 HLS 主清单包含当前离线保存不支持的规则，请选择完整的媒体线路"
    }
    val videoUri = lines.getOrNull(variant.index + 1)?.takeUnless { it.startsWith('#') }
    require(videoUri != null && lines.count { !it.startsWith('#') } == 1) { "HLS 主清单缺少唯一的视频地址" }
    val videoAttributes = hlsAttributes(variant.value.substringAfter(':'))
    val audioGroup = videoAttributes.quoted("AUDIO") ?: error("HLS 主清单未指定独立音轨，请选择媒体线路")
    require("SUBTITLES" !in videoAttributes && "VIDEO" !in videoAttributes &&
        videoAttributes["CLOSED-CAPTIONS"].let { it == null || it == "NONE" }) {
        "这份 HLS 包含独立字幕、视频或闭路字幕版本，当前离线保存仅支持单视频及其默认音轨"
    }
    val default = lines.asSequence().filter { it.startsWith("#EXT-X-MEDIA:") }
        .map { hlsAttributes(it.substringAfter(':')) }
        .filter { it["TYPE"] == "AUDIO" && it.quoted("GROUP-ID") == audioGroup && it["DEFAULT"] == "YES" }
        .toList().singleOrNull() ?: error("HLS 主清单没有唯一的默认音轨，无法确认离线音频版本")
    val audioUri = default.quoted("URI") ?: error("HLS 默认音轨缺少独立媒体地址")
    val name = default.quoted("NAME") ?: error("HLS 默认音轨缺少名称")
    val bandwidth = videoAttributes["BANDWIDTH"]?.toIntOrNull()?.takeIf { it > 0 }
        ?: error("HLS 视频线路缺少有效的带宽声明")
    val playlist = buildString {
        appendLine("#EXTM3U")
        if ("#EXT-X-INDEPENDENT-SEGMENTS" in lines) appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"offline-audio\",NAME=\"").append(name)
        append("\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"")
        for (attribute in listOf("LANGUAGE", "CHANNELS")) {
            default.quoted(attribute)?.let { append(',').append(attribute).append("=\"").append(it).append('"') }
        }
        appendLine()
        append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
        videoAttributes.quoted("CODECS")?.let { append(",CODECS=\"").append(it).append('"') }
        videoAttributes["RESOLUTION"]?.takeIf { it.matches(HLS_RESOLUTION) }?.let { append(",RESOLUTION=").append(it) }
        appendLine(",AUDIO=\"offline-audio\"")
        appendLine("video.m3u8")
    }
    fun child(uri: String): HttpUrl = base.resolve(uri)?.let { checkedAnimeDownloadUrl(it.toString()) }
        ?: error("HLS 子清单地址无效")
    val video = child(videoUri)
    val audio = child(audioUri)
    require(video != audio) { "HLS 视频和默认音轨必须提供独立的媒体地址" }
    return AnimeHlsDefaultRendition(video, audio, playlist)
}

private val HLS_ATTRIBUTE = Regex("([A-Z0-9-]+)=(\"[^\"\\p{Cc}]*\"|[^,\"\\s]+)(,|$)")
private val HLS_RESOLUTION = Regex("[1-9][0-9]*x[1-9][0-9]*")

private fun hlsAttributes(value: String): Map<String, String> = buildMap {
    require(value.isNotEmpty() && !value.endsWith(',')) { "HLS 主清单属性格式无效" }
    var next = 0
    while (next < value.length) {
        val match = HLS_ATTRIBUTE.matchAt(value, next) ?: error("HLS 主清单属性格式无效")
        val (key, attribute) = match.destructured
        require(put(key, attribute) == null) { "HLS 主清单重复声明属性 $key" }
        next = match.range.last + 1
    }
}

private fun Map<String, String>.quoted(key: String): String? = get(key)?.takeIf { it.length > 2 && it.first() == '"' }
    ?.substring(1)?.dropLast(1)

private fun hlsLines(text: String) = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

/** Only a closed, self-contained media playlist is admitted; every retained URI is rewritten. */
internal fun parseAnimeHlsDownload(base: HttpUrl, text: String): AnimeHlsDownloadPlan =
    parseAnimeHlsMedia(base, hlsLines(text), firstResource = 0)

private fun parseAnimeHlsMedia(base: HttpUrl, lines: List<String>, firstResource: Int): AnimeHlsDownloadPlan {
    require(lines.firstOrNull() == "#EXTM3U") { "来源返回的内容不是有效 HLS 清单" }
    require(lines.none { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:") }) {
        "这条 HLS 包含多画质或独立音轨，请先选择一个完整的媒体线路再下载"
    }
    require(lines.any { it == "#EXT-X-ENDLIST" }) { "直播或仍在更新的 HLS 无法保存成完整离线剧集" }
    require(lines.count { it.startsWith("#EXT-X-TARGETDURATION:") } == 1) { "HLS 清单缺少唯一的目标分段时长" }
    val resources = mutableListOf<AnimeResourcePlan>()
    val output = mutableListOf("#EXTM3U")
    var segments = 0
    var pendingDuration = false
    var ended = false
    fun resource(uri: String, initialization: Boolean): String {
        val url = base.resolve(uri)?.let { checkedAnimeDownloadUrl(it.toString()) }
            ?: error("HLS 资源地址无效")
        val name = "${if (initialization) "init" else "segment"}-${firstResource + resources.size}.bin"
        resources += AnimeResourcePlan(name, url.toString(), if (initialization) "video/mp4" else null)
        return name
    }
    for (line in lines.drop(1)) {
        require(!ended || line.startsWith('#') && !line.startsWith("#EXT")) { "HLS 结束标记后仍有媒体数据" }
        when {
            line == "#EXT-X-ENDLIST" -> { ended = true; output += line }
            line.startsWith("#EXT-X-KEY:") -> require(line.substringAfter(':').trim() == "METHOD=NONE") {
                "这条 HLS 使用加密密钥；当前离线保存只支持未加密媒体，请选择其他线路"
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> error("这条 HLS 使用分段字节范围，当前离线保存尚不支持")
            line.startsWith("#EXT-X-MAP:") -> {
                val attributes = line.substringAfter(':')
                require(!attributes.contains("BYTERANGE=")) { "HLS 初始化段使用字节范围，当前离线保存尚不支持" }
                val match = Regex("URI=\"([^\"]+)\"").matchEntire(attributes)
                    ?: error("HLS 初始化段格式不受当前离线保存支持")
                output += "#EXT-X-MAP:URI=\"${resource(match.groupValues[1], true)}\""
            }
            line.startsWith("#EXTINF:") -> {
                require(!pendingDuration) { "HLS 分段缺少对应资源" }
                val value = line.substringAfter(':').substringBefore(',')
                require(value.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true) { "HLS 分段时长无效" }
                output += "#EXTINF:$value,"
                pendingDuration = true
            }
            line in setOf("#EXT-X-DISCONTINUITY", "#EXT-X-INDEPENDENT-SEGMENTS") -> output += line
            line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> {
                require(line.substringAfter(':') in setOf("VOD", "EVENT")) { "HLS 清单类型无效" }
                output += "#EXT-X-PLAYLIST-TYPE:VOD"
            }
            listOf("#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:",
                "#EXT-X-DISCONTINUITY-SEQUENCE:").any(line::startsWith) -> {
                val positive = line.startsWith("#EXT-X-TARGETDURATION:") || line.startsWith("#EXT-X-VERSION:")
                require(line.substringAfter(':').toLongOrNull()?.let { if (positive) it > 0 else it >= 0 } == true) { "HLS 清单参数无效" }
                output += line
            }
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> Unit
            line.startsWith("#EXT") -> error("这条 HLS 使用当前离线保存尚不支持的清单扩展（${line.substringBefore(':').take(48)}）")
            line.startsWith('#') -> Unit
            else -> {
                require(pendingDuration && !ended) { "HLS 分段缺少有效时长" }
                output += resource(line, false)
                pendingDuration = false
                segments++
            }
        }
    }
    require(segments > 0 && !pendingDuration) { "HLS 清单没有完整的媒体分段" }
    val playlist = output.joinToString("\n", postfix = "\n")
    return AnimeHlsDownloadPlan(playlist, resources, animeDigest(playlist + resources.joinToString("\n") { it.url }))
}
