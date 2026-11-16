package dev.mangaflow.anime.downloads

import dev.mangaflow.net.HttpUrl

internal data class AnimeHlsDownloadPlan(val playlist: String, val resources: List<AnimeResourcePlan>, val identity: String)

/** Only a closed, self-contained media playlist is admitted; every retained URI is rewritten. */
internal fun parseAnimeHlsDownload(base: HttpUrl, text: String): AnimeHlsDownloadPlan {
    val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
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
        val name = "${if (initialization) "init" else "segment"}-${resources.size}.bin"
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
