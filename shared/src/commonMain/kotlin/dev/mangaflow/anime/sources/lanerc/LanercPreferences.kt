package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.wallTimeMillis
import io.ktor.http.fromHttpToGmtDate

/** Per-source private state. A new installation revision cannot inherit an old callback's writes. */
internal class LanercPreferences(
    private val context: PlatformContext,
    sourceId: String,
    private val revision: String,
    private val writable: Boolean,
    private val checkCurrent: () -> Unit,
) {
    private val secretKey = "anime-lanerc:" + animeHash(sourceId)
    private val saved = if (writable) JSONObject(readSecret(context, secretKey) ?: "{}") else JSONObject()
    private val values = if (saved.optString("revision") == revision) saved.optJSONObject("values") ?: JSONObject() else JSONObject()
    private val cookies = if (saved.optString("revision") == revision) saved.optJSONObject("cookies") ?: JSONObject() else JSONObject()
    private var calls = 0

    fun call(raw: String): String {
        checkCurrent()
        require(++calls <= 256 && raw.length <= 65_536) { "来源会话操作超出限制。" }
        val request = JSONObject(raw)
        val key = request.optString("key")
        return when (request.getString("op")) {
            "get" -> { require(key.length in 1..160); values.opt(key)?.let { JSONObject.quote(it.toString()) } ?: "null" }
            "set", "remove" -> {
                require(key.length in 1..160)
                if (request.getString("op") == "set") values.put(key, request.optString("value")) else values.remove(key)
                persist(); "true"
            }
            "cookiesGet" -> cookieObject(hostUrl(key)).toString()
            "cookiesClear" -> {
                if (key.isBlank()) cookies.keys().asSequence().toList().forEach(cookies::remove)
                else cookies.remove(hostUrl(key).host)
                persist(); "true"
            }
            "cookiesSet" -> {
                val url = hostUrl(key)
                val incoming = request.optJSONObject("value") ?: JSONObject()
                require(incoming.length() <= 128)
                incoming.keys().forEach { name -> receive(url, "$name=${incoming.optString(name)}; Path=/") }
                "true"
            }
            else -> throw IllegalArgumentException("来源会话操作不受支持。")
        }
    }

    fun header(url: HttpUrl): String = cookieObject(url).let { objectValue ->
        objectValue.keys().asSequence().joinToString("; ") { "$it=${objectValue.optString(it)}" }
    }

    fun receive(url: HttpUrl, header: String) {
        checkCurrent()
        if (header.isBlank()) return
        require(header.length <= 32_768)
        val entries = cookies.optJSONArray(url.host) ?: JSONArray()
        val retained = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.toMutableList()
        for (line in header.split(Regex(",(?=\\s*[^;,=\\s]+\\s*=)"))) {
            val parts = line.split(';').map(String::trim)
            val pair = parts.firstOrNull().orEmpty().split('=', limit = 2)
            if (pair.size != 2 || !pair[0].matches(Regex("[!#%&'*+.^_`|~A-Za-z0-9-]{1,160}")) ||
                pair[1].any { it.code < 32 || it.code == 127 } || pair[1].length > 8192) continue
            val attributes = parts.drop(1).associate { part -> part.substringBefore('=').lowercase() to part.substringAfter('=', "") }
            // Cookie authority is host-only. A script may explicitly set another public host's jar,
            // but an HTTP response cannot delegate credentials to siblings or a parent domain.
            val path = attributes["path"]?.takeIf { it.startsWith('/') } ?: url.encodedPath.substringBeforeLast('/', "").ifBlank { "/" }
            val maxAge = attributes["max-age"]?.toLongOrNull()
            val expires = maxAge?.let { wallTimeMillis() + it.coerceIn(-1, 31_536_000L) * 1000 }
                ?: attributes["expires"]?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }
            retained.removeAll { it.optString("name") == pair[0] && it.optString("path") == path }
            if (expires == null || expires > wallTimeMillis()) retained += JSONObject().put("name", pair[0]).put("value", pair[1])
                .put("path", path).put("expires", expires ?: JSONObject.NULL)
        }
        require(retained.size <= 128)
        cookies.put(url.host, JSONArray(retained))
        persist()
    }

    private fun cookieObject(url: HttpUrl): JSONObject {
        val entries = cookies.optJSONArray(url.host) ?: return JSONObject()
        val result = JSONObject()
        (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .filter { entry ->
                val path = entry.optString("path", "/")
                (entry.isNull("expires") || entry.optLong("expires") > wallTimeMillis()) &&
                    (url.encodedPath == path || url.encodedPath.startsWith(path.trimEnd('/') + "/"))
            }.sortedBy { it.optString("path").length }
            .forEach { result.put(it.optString("name"), it.optString("value")) }
        return result
    }

    private fun hostUrl(host: String) = animePublicUrl(if (host.contains("://")) host else "https://$host/")
    private fun persist() {
        check(writable) { "验证来源时不能修改会话。" }
        checkCurrent()
        val encoded = JSONObject().put("revision", revision).put("values", values).put("cookies", cookies).toString()
        require(values.length() <= 128 && cookies.length() <= 32 && encoded.length <= 262_144) { "来源会话存储超出限制。" }
        writeSecret(context, secretKey, encoded)
        checkCurrent()
    }
}
