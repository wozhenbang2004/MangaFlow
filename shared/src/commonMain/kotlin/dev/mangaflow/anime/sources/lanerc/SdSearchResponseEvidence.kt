package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.json.JSONObject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/** Invocation-local observation only. Never changes requests, credentials, scripts or retries. */
internal class SdSearchResponseEvidence private constructor() {
    private var attempts = 0
    private var responseHash: String? = null
    private val invalidJson = mutableListOf<Boolean?>()

    fun beforeRequest(raw: String) {
        if (!isMainRequest(raw)) return
        attempts++
        responseHash = null
        if (attempts <= 2) invalidJson.add(null)
    }

    fun afterResponse(request: String, raw: String) {
        if (!isMainRequest(request) || attempts !in 1..2) return
        val response = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (response.optInt("statusCode") != 200 || !isEndpoint(response.optString("url"))) return
        val body = response.optString("body").trim()
        if (body.length !in 1..MAX_OBSERVED_CHARS) return
        responseHash = animeHash(body)
    }

    fun afterCodec(raw: String, decoded: String) {
        val expected = responseHash ?: return
        if (attempts !in 1..2 || decoded.length > MAX_OBSERVED_CHARS) return
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (input.optString("operation") != "aes" || input.optBoolean("encrypt", false)) return
        val options = input.optJSONObject("options") ?: return
        if (options.optString("input") != "base64" || options.optString("output") != "utf8") return
        val encrypted = input.optString("text").trim()
        if (encrypted.length > MAX_OBSERVED_CHARS || animeHash(encrypted) != expected) return
        // The pinned yyCall retries exactly once when j(plain) is null or JSON is invalid.
        // Legal JSON false/0/objects without result stay unknown: no guessed business schema.
        if (!withinDiagnosticDepth(decoded)) {
            invalidJson[attempts - 1] = null
            return
        }
        val invalid = try {
            if (Json.parseToJsonElement(decoded) == JsonNull) true else false
        } catch (_: SerializationException) {
            true
        }
        invalidJson[attempts - 1] = invalid
    }

    // Bound the extra observer parse independently of the source runtime's own limits.
    // This is only a depth screen, not a syntax validator. Too deep stays unknown.
    private fun withinDiagnosticDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) return false
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return true
    }

    fun twoUnresolvedMainAttempts(): Boolean = attempts == 2 && invalidJson == listOf(true, true)

    private fun isMainRequest(raw: String): Boolean {
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return request.optString("method").equals("POST", ignoreCase = true) && isEndpoint(request.optString("url"))
    }

    private fun isEndpoint(raw: String): Boolean {
        val url = runCatching { animePublicUrl(raw) }.getOrNull() ?: return false
        return url.scheme == "https" && url.host == "u.yyxdmn.com" && url.port == 443 &&
            url.encodedPath == "/api/new_search/result_v2" && url.queryParameterNames.isEmpty() && url.fragment == null
    }

    companion object {
        private const val MAX_OBSERVED_CHARS = 262_144
        private const val AUDITED_DIGEST = "985c0542acefc3afd13519dde396268fab4bab667ee407e9d4ef688d97138fbf"
        fun forCall(id: String, digest: String, method: String, keyword: String): SdSearchResponseEvidence? =
            if (id == "anime-lanerc-sd" && digest == AUDITED_DIGEST && method == "search" && keyword.isNotBlank())
                SdSearchResponseEvidence() else null
    }
}
