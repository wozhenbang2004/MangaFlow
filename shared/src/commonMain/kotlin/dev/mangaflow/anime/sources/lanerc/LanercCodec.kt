@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.data.plugins.javascriptCodec
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import okio.Buffer
import okio.GzipSource
import okio.buffer
import okio.use
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64

internal class LanercCapabilityException(message: String) : IllegalStateException(message)

/** Blocking adapters execute only inside this operation's background QuickJS callback. */
internal expect fun <T> lanercBlocking(context: CoroutineContext, block: suspend () -> T): T
internal expect fun lanercPlatformCrypto(request: JSONObject): ByteArray

internal fun lanercCodec(raw: String): String {
    require(raw.length <= 2 * 1024 * 1024) { "来源编码参数过大。" }
    val input = JSONObject(raw)
    val options = input.optJSONObject("options") ?: JSONObject()
    val operation = input.getString("operation")
    val text = input.optString("text")
    val result: ByteArray = when (operation) {
        "base64Encode", "hexEncode" -> decodeLanercBytes(text, options.optString("input", "utf8"))
        "base64Decode" -> decodeLanercBytes(text, if (options.optBoolean("urlSafe")) "base64url" else "base64")
        "hexDecode" -> decodeLanercBytes(text, "hex")
        "hash", "hmac", "aes" -> {
            val algorithm = input.optString("algorithm").lowercase().replace("-", "")
            if (operation in setOf("hash", "hmac")) require(algorithm in setOf("md5", "sha1", "sha256", "sha512"))
            val mode = options.optString("mode", "CBC").uppercase()
            if (operation == "aes" && (mode !in setOf("CBC", "ECB") ||
                        options.optString("padding", "PKCS5").uppercase() !in setOf("PKCS5", "PKCS7", "PKCS5PADDING", "PKCS7PADDING"))) {
                lanercPlatformCrypto(input)
            } else {
                val encrypt = input.optBoolean("encrypt", false)
                val bytes = decodeLanercBytes(text, options.optString("input", if (operation == "aes" && !encrypt) "base64" else "utf8"))
                val request = JSONObject().put("op", "venera").put("value", byteArrayJson(bytes))
                    .put("type", when (operation) { "hash" -> algorithm; "hmac" -> "hmac"; else -> "aes-${mode.lowercase()}" })
                    .put("isEncode", encrypt).put("hash", algorithm)
                if (operation in setOf("hmac", "aes")) {
                    val key = decodeLanercBytes(input.optString("key"), options.optString("keyFormat", "utf8"))
                    request.put("key", byteArrayJson(if (operation == "aes") lanercSymmetricKey("aes", key) else key))
                }
                if (operation == "aes" && mode == "CBC") request.put("iv", byteArrayJson(decodeLanercBytes(options.optString("iv"), options.optString("ivFormat", options.optString("keyFormat", "utf8"))).copyOf(16)))
                val array = JSONArray(javascriptCodec(request.toString()))
                ByteArray(array.length()) { array.getInt(it).toByte() }
            }
        }
        "gzip" -> {
            val bytes = decodeLanercBytes(text, options.optString("input", "base64"))
            GzipSource(Buffer().write(bytes)).buffer().use { source ->
                val target = Buffer()
                while (!source.exhausted()) {
                    val count = source.read(target, 16_384L)
                    require(target.size <= 8L * 1024 * 1024) { "解压后的来源响应过大。" }
                    if (count < 0L) break
                }
                target.readByteArray()
            }
        }
        else -> lanercPlatformCrypto(input)
    }
    val defaultOutput = when (operation) {
        "base64Encode" -> if (options.optBoolean("urlSafe")) "base64url" else "base64"
        "hexEncode", "hash", "hmac", "pbkdf2" -> "hex"
        "aes", "rsa", "des", "tripleDes" -> if (input.optBoolean("encrypt")) "base64" else "utf8"
        else -> "utf8"
    }
    return encodeLanercBytes(result, options.optString("output", defaultOutput))
}

internal fun byteArrayJson(bytes: ByteArray) = JSONArray(bytes.map { it.toInt() and 255 })
internal fun decodeLanercBytes(value: String, format: String): ByteArray = when (format.lowercase()) {
    "utf8", "utf-8", "text" -> value.encodeToByteArray()
    "base64" -> Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value.filterNot(Char::isWhitespace))
    "base64url" -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value.filterNot(Char::isWhitespace))
    "hex" -> {
        require(value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" })
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    "binary", "latin1" -> {
        require(value.all { it.code <= 255 })
        ByteArray(value.length) { value[it].code.toByte() }
    }
    else -> throw LanercCapabilityException("该来源使用了尚未支持的字节编码。")
}
internal fun encodeLanercBytes(bytes: ByteArray, format: String): String = when (format.lowercase()) {
    "utf8", "utf-8", "text" -> bytes.decodeToString()
    "base64" -> Base64.encode(bytes)
    "base64url" -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(bytes)
    "hex" -> bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    "binary", "latin1" -> bytes.map { (it.toInt() and 255).toChar() }.joinToString("")
    else -> throw LanercCapabilityException("该来源使用了尚未支持的字节编码。")
}

/** Lanerc's compatibility key sizing is a source protocol behavior, not a new application key. */
internal fun lanercSymmetricKey(operation: String, bytes: ByteArray): ByteArray = bytes.copyOf(when (operation) {
    "aes" -> when { bytes.size >= 32 -> 32; bytes.size >= 24 -> 24; else -> 16 }
    "des" -> 8
    "tripleDes" -> if (bytes.size >= 24) 24 else 16
    else -> bytes.size
})
