@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.data.sources

import dev.mangaflow.data.SourceException
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.io.encoding.Base64

private const val picaStaticHost = "storage1.picacomic.com"
private const val picaObjectHost = "storage-b.picacomic.com"
private const val picaThumbnailHost = "img.picacomic.com"
private val picaLegacyOriginalHosts = setOf(picaStaticHost, picaObjectHost)
private val picaOriginalHosts = picaLegacyOriginalHosts + setOf("s2.picacomic.com", "s3.picacomic.com")
private val picaObjectSegment = Regex("[A-Za-z0-9_.~-]{1,255}")
private val picaOriginalExtension = setOf("avif", "gif", "jpeg", "jpg", "png", "webp")
private val picaThumbnailCodec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** Keep the declared media route; only the old thumbnail/storage contracts use a different path. */
internal fun picacgMediaUrl(fileServer: String, path: String): String {
    val mediaPath = path.trimStart('/').removePrefix("static/")
    if (mediaPath.length !in 1..4096 || ".." in mediaPath || '?' in mediaPath || '#' in mediaPath ||
        mediaPath.split('/').any { it.isEmpty() || it == "." || it.contains("%2e", ignoreCase = true) }) {
        throw SourceException("哔咔图片地址不完整。")
    }
    val rawServer = fileServer.ifBlank {
        "https://" + when {
            mediaPath.startsWith("tobeimg/") -> picaThumbnailHost
            mediaPath.startsWith("tobs/") -> picaObjectHost
            else -> picaStaticHost
        }
    }
    val server = rawServer.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: throw SourceException("哔咔图片服务器不是有效的 HTTPS 地址。")
    val standardOrigin = server.port == 443 && server.encodedPath == "/"
    if (standardOrigin && server.host in picaLegacyOriginalHosts && mediaPath.startsWith("tobeimg/")) {
        picacgThumbnailOriginal(mediaPath.substringAfterLast('/'))?.let { return it }
    }
    val pathOnServer = when {
        standardOrigin && server.host == picaThumbnailHost && mediaPath.startsWith("tobeimg/") ->
            mediaPath.removePrefix("tobeimg/")
        standardOrigin && server.host == picaObjectHost && mediaPath.startsWith("tobs/") ->
            "static/" + mediaPath.removePrefix("tobs/")
        else -> "static/$mediaPath"
    }
    return checkedBodyUrl(server.toString().trimEnd('/') + "/" + pathOnServer).toString()
}

/** Recover the old saved thumbnail address at display time, without rewriting saved works. */
internal fun repairPicacgCoverUrl(value: String): String {
    val url = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.host in picaLegacyOriginalHosts && it.username.isEmpty() &&
            it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: return value
    val path = url.pathSegments
    val transform = path.getOrNull(1).orEmpty()
    val wasStrippedThumbnail = path.size >= 3 && (
        transform == "fill" || transform.startsWith("rs:fill:") || transform.startsWith("rs%3Afill%3A", ignoreCase = true))
    return if (wasStrippedThumbnail) picacgThumbnailOriginal(path.last()) ?: value else value
}

private fun picacgThumbnailOriginal(fileName: String): String? {
    val token = fileName.substringBeforeLast('.', missingDelimiterValue = "")
    if (token.length !in 16..2048) return null
    val original = runCatching { picaThumbnailCodec.decode(token).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    val url: HttpUrl = original?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.host in picaOriginalHosts && it.username.isEmpty() &&
            it.password.isEmpty() && it.query == null && it.fragment == null && it.encodedPath.startsWith("/static/")
    } ?: return null
    val path = url.pathSegments.drop(1)
    if (path.isEmpty() || path.any { it == "." || it == ".." || !picaObjectSegment.matches(it) } ||
        path.last().substringAfterLast('.', "").lowercase() !in picaOriginalExtension) return null
    return url.toString()
}
