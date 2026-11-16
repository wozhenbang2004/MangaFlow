package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

internal actual fun <T> lanercBlocking(context: CoroutineContext, block: suspend () -> T): T =
    // A local event loop, not the QuickJS dispatcher: HTTP continuations cannot queue
    // behind the synchronous JS callback that is waiting for them.
    runBlocking(context.minusKey(ContinuationInterceptor)) { block() }

internal actual fun lanercPlatformCrypto(request: JSONObject): ByteArray {
    val operation = request.getString("operation")
    val options = request.optJSONObject("options") ?: JSONObject()
    val encrypt = request.optBoolean("encrypt", false)
    val input = decodeLanercBytes(request.optString("text"), options.optString("input", if (encrypt || operation == "pbkdf2") "utf8" else "base64"))
    if (operation == "inflate") {
        val inflater = Inflater(options.optBoolean("nowrap", false))
        try {
            return InflaterInputStream(ByteArrayInputStream(input), inflater).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 8 * 1024 * 1024) { "解压后的来源响应过大。" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally { inflater.end() }
    }
    if (operation == "pbkdf2") {
        val iterations = request.optInt("iterations", 1_000)
        val bits = request.optInt("bits", 256)
        require(iterations in 1..1_000_000 && bits in 8..4096 && bits % 8 == 0)
        val digest = request.optString("algorithm", "SHA-256").uppercase().replace("-", "")
        require(digest in setOf("SHA1", "SHA256", "SHA512"))
        val digestBits = when (digest) { "SHA1" -> 160; "SHA256" -> 256; else -> 512 }
        // Native crypto does not yield to the JS interrupt hook. Bound total HMAC work,
        // including all requested output blocks, before entering that callback.
        require(iterations.toLong() * ((bits + digestBits - 1) / digestBits) <= 1_000_000L) {
            "来源密钥派生参数超出本地处理范围。"
        }
        val spec = PBEKeySpec(request.optString("text").toCharArray(),
            decodeLanercBytes(request.optString("salt"), options.optString("saltFormat", options.optString("keyFormat", "utf8"))), iterations, bits)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmac$digest").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    val mode = options.optString("mode", if (operation == "rsa") "ECB" else "CBC").uppercase()
    val padding = options.optString("padding", if (operation == "rsa") "PKCS1" else "PKCS5").uppercase()
    val cipherPadding = when (padding) {
        "PKCS5", "PKCS7", "PKCS5PADDING", "PKCS7PADDING" -> "PKCS5Padding"
        "NOPADDING", "NONE", "ZERO", "ZEROPADDING" -> "NoPadding"
        "PKCS1", "PKCS1PADDING" -> "PKCS1Padding"
        "OAEP", "OAEPWITHSHA-1ANDMGF1PADDING" -> "OAEPWithSHA-1AndMGF1Padding"
        "OAEPWITHSHA-256ANDMGF1PADDING" -> "OAEPWithSHA-256AndMGF1Padding"
        else -> throw LanercCapabilityException("该来源使用了尚未支持的加密填充方式。")
    }
    if (operation == "rsa") {
        require(mode == "ECB")
        val keyText = request.optString("key").replace(Regex("-----[^-]+-----"), "").filterNot(Char::isWhitespace)
        val keyBytes = decodeLanercBytes(keyText, "base64")
        require(keyBytes.size <= 16_384) { "来源 RSA 密钥超出本地处理范围。" }
        val factory = KeyFactory.getInstance("RSA")
        val key = if (encrypt) factory.generatePublic(X509EncodedKeySpec(keyBytes)) else factory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        require((key as RSAKey).modulus.bitLength() <= 16_384) { "来源 RSA 密钥超出本地处理范围。" }
        return Cipher.getInstance("RSA/ECB/$cipherPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key); doFinal(input)
        }
    }
    val algorithm = when (operation) {
        "aes" -> "AES"
        "des" -> "DES"
        "tripleDes" -> "DESede"
        else -> throw LanercCapabilityException("该来源使用了尚未支持的加密能力。")
    }
    require(mode in setOf("CBC", "ECB", "GCM", "CTR"))
    val key = lanercSymmetricKey(operation, decodeLanercBytes(request.optString("key"), options.optString("keyFormat", "utf8")))
    val blockBytes = if (algorithm == "AES") 16 else 8
    val ivBytes = decodeLanercBytes(options.optString("iv"), options.optString("ivFormat", options.optString("keyFormat", "utf8")))
    val iv = if (mode == "GCM") { if (ivBytes.isEmpty()) ByteArray(12) else ivBytes } else ivBytes.copyOf(blockBytes)
    val effectivePadding = if (mode in setOf("GCM", "CTR") && !options.has("padding")) "NoPadding" else cipherPadding
    val cipher = Cipher.getInstance("$algorithm/$mode/$effectivePadding")
    val effectiveKey = if (algorithm == "DESede" && key.size == 16) key + key.copyOfRange(0, 8) else key
    val keySpec = SecretKeySpec(effectiveKey, algorithm)
    val direction = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
    when (mode) {
        "ECB" -> cipher.init(direction, keySpec)
        "GCM" -> {
            val tagBits = options.optInt("tagLen", options.optInt("tagBits", options.optInt("tagLength", options.optInt("tagLengthBits", 128)))).takeIf { it > 0 } ?: 128
            require(tagBits in setOf(96, 104, 112, 120, 128))
            cipher.init(direction, keySpec, GCMParameterSpec(tagBits, iv))
            if (options.has("aad")) cipher.updateAAD(decodeLanercBytes(options.optString("aad"), options.optString("aadFormat", "utf8")))
        }
        else -> cipher.init(direction, keySpec, IvParameterSpec(iv))
    }
    val zeroPadding = padding in setOf("ZERO", "ZEROPADDING")
    val bytes = if (zeroPadding && encrypt && input.size % blockBytes != 0)
        input.copyOf(input.size + blockBytes - input.size % blockBytes) else input
    val result = cipher.doFinal(bytes)
    return if (zeroPadding && !encrypt) result.copyOf((result.indexOfLast { it != 0.toByte() } + 1).coerceAtLeast(0)) else result
}
