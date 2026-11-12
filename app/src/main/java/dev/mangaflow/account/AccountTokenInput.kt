package dev.mangaflow.account

import dev.mangaflow.data.SourceException
import java.net.URLDecoder

internal object AccountTokenInput {
    fun parse(input: String): String {
        if (input.length !in 1..16_384 || input.any { it == '\r' || it == '\n' || it == '\u0000' }) invalid()
        var value = input.trim()
        val cookieHeader = value.startsWith("Cookie:", ignoreCase = true)
        val authorizationHeader = value.startsWith("Authorization:", ignoreCase = true)
        if (cookieHeader) value = value.substringAfter(':').trim()
        if (authorizationHeader) value = value.substringAfter(':').trim()

        val tokenPrefix = Regex("(?i)^Token[ \\t]+")
        val candidate = when {
            authorizationHeader || tokenPrefix.containsMatchIn(value) -> {
                val prefix = tokenPrefix.find(value) ?: invalid()
                value.substring(prefix.range.last + 1).trim()
            }
            cookieHeader || ';' in value || Regex("(?i)^token[ \\t]*=").containsMatchIn(value) -> {
                val tokens = value.split(';').mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator < 0 || !part.substring(0, separator).trim().equals("token", true)) null
                    else part.substring(separator + 1).trim().removeSurrounding("\"")
                }
                tokens.singleOrNull() ?: invalid()
            }
            else -> value
        }
        val decoded = try {
            // A Cookie token is not form data: a literal '+' must remain '+'.
            URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) { invalid() }
        return validate(decoded)
    }

    fun validate(token: String): String {
        if (token.length !in 8..2048 || token.any { it.code <= 0x20 || it.code >= 0x7f }) invalid()
        return token
    }

    private fun invalid(): Nothing = throw SourceException("登录凭据格式无效，请粘贴自己的 token 或包含 token 的 Cookie。")
}
