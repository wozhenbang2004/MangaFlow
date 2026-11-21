package dev.mangaflow.data.plugins

import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An immutable, invocation-local GET capability for a catalogue-declared playback parser.
 * The source host supplies it only after verifying the original script and episode ticket.
 * It cannot grant another origin, path, fixed query prefix, request body or credential header.
 */
internal class PublicPlaybackParserGrant private constructor(
    private val origin: HttpUrl,
    private val fixedQueryFields: List<String>,
) {
    fun allows(address: String): Boolean {
        val target = checkedUrl(address) ?: return false
        if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port ||
            target.encodedPath != origin.encodedPath) return false
        // Split at the declared slot boundary, preserving the opaque suffix exactly as the
        // original script appended it (including an inner URL's percent-encoding or '&').
        val fields = target.query?.split('&', limit = fixedQueryFields.size + 1) ?: return false
        if (fields.size != fixedQueryFields.size + 1 || fields.dropLast(1) != fixedQueryFields) return false
        val slot = fields.last()
        if (slot.substringBefore('=') != "url" || slot.substringAfter('=', "").isBlank()) return false
        // Do not reinterpret an inner URL's query as new host instructions. The pinned
        // script owns the complete opaque suffix, including its original repeated names.
        return true
    }

    companion object {
        val PUBLIC_HEADERS = setOf("user-agent", "accept", "accept-language")

        fun fromCataloguePrefix(value: String): PublicPlaybackParserGrant? {
            val url = checkedUrl(value) ?: return null
            val fields = url.query?.split('&') ?: return null
            if (fields.lastOrNull() != "url=" || url.queryParameter("url") != "" ||
                url.querySize != url.queryParameterNames.size || fields.size > 16) return null
            return PublicPlaybackParserGrant(url, fields.dropLast(1).toList())
        }

        private fun checkedUrl(value: String): HttpUrl? = runCatching {
            require(value.length in 1..8192 && value.none { it.code <= 0x20 || it.code in 0x7f..0x9f })
            val url = value.toHttpUrlOrNull() ?: return null
            require(url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                '@' !in value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#'))
            // Validate a copy; the declared HTTP address is never rewritten or retried as HTTPS.
            publicPluginUrl(url.newBuilder().scheme("https").build().toString())
            val host = url.host.trimEnd('.')
            require(listOf("test", "invalid", "example", "onion", "alt", "arpa", "example.com", "example.net", "example.org")
                .none { host == it || host.endsWith(".$it") })
            url
        }.getOrNull()
    }
}
