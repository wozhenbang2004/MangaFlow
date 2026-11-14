package dev.mangaflow.data.plugins

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun javascriptCodec(raw: String): String {
    val request = JSONObject(raw)
    require(raw.length <= 2 * 1024 * 1024) { "漫画源编码数据过大。" }
    return when (request.getString("op")) {
        "uuid" -> UUID.randomUUID().toString()
        "atob" -> Base64.getMimeDecoder().decode(request.getString("text")).toString(Charsets.ISO_8859_1)
        "btoa" -> {
            val text = request.getString("text")
            require(text.all { it.code <= 255 }) { "btoa 只接受二进制字符串。" }
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.ISO_8859_1))
        }
        "aesCbc" -> {
            val key = request.getString("key").toByteArray(Charsets.UTF_8)
            val iv = request.getString("iv").toByteArray(Charsets.UTF_8)
            val encrypt = request.getBoolean("encrypt")
            val input = if (encrypt) request.getString("text").toByteArray(Charsets.UTF_8) else Base64.getMimeDecoder().decode(request.getString("text"))
            val output = scriptAes(input, key, iv, encrypt, false)
            if (encrypt) Base64.getEncoder().encodeToString(output) else output.toString(Charsets.UTF_8)
        }
        "venera" -> {
            val type = request.getString("type")
            val encoding = request.optBoolean("isEncode", true)
            val value = request.opt("value")
            val input = scriptBytes(value)
            val result: Any = when (type) {
                "utf8" -> if (encoding) unsignedBytes((value as? String ?: error("UTF-8 输入无效。")).toByteArray(Charsets.UTF_8)) else input.toString(Charsets.UTF_8)
                "base64" -> if (encoding) Base64.getEncoder().encodeToString(input) else unsignedBytes(Base64.getMimeDecoder().decode(value as? String ?: error("Base64 输入无效。")))
                "hex" -> if (encoding) input.joinToString("") { "%02x".format(it.toInt() and 255) } else {
                    val text = value as? String ?: error("Hex 输入无效。")
                    require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" })
                    unsignedBytes(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
                }
                "md5", "sha1", "sha256", "sha512" -> unsignedBytes(MessageDigest.getInstance(when(type) { "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; "sha512" -> "SHA-512"; else -> "MD5" }).digest(input))
                "hmac" -> {
                    val algorithm = when(request.optString("hash", "sha256").lowercase().replace("-", "")) {
                        "sha1" -> "HmacSHA1"; "sha256" -> "HmacSHA256"; "sha512" -> "HmacSHA512"; "md5" -> "HmacMD5"
                        else -> error("不支持的 HMAC 摘要。")
                    }
                    val key = scriptBytes(request.opt("key"))
                    val bytes = Mac.getInstance(algorithm).run { init(SecretKeySpec(if (key.isEmpty()) byteArrayOf(0) else key, algorithm)); doFinal(input) }
                    if (request.optBoolean("isString", false)) bytes.joinToString("") { "%02x".format(it.toInt() and 255) } else unsignedBytes(bytes)
                }
                "aes-ecb", "aes-cbc" -> unsignedBytes(scriptAes(input, scriptBytes(request.opt("key")),
                    if (type == "aes-cbc") scriptBytes(request.opt("iv")) else null, encoding, type == "aes-ecb"))
                else -> error("不支持的漫画源加密或编码能力。")
            }
            scriptJsonValue(result)
        }
        else -> error("不支持的漫画源编码或加密方式。")
    }
}

private fun unsignedBytes(value: ByteArray) = JSONArray(value.map { it.toInt() and 255 })
private fun scriptBytes(value: Any?): ByteArray = when (value) {
    is String -> value.toByteArray(Charsets.UTF_8)
    is JSONArray -> ByteArray(value.length()) { index ->
        val item = value.get(index)
        require(item is Number && item.toInt() in 0..255 && item.toDouble() == item.toInt().toDouble()) { "漫画源字节数据无效。" }
        item.toInt().toByte()
    }
    else -> error("漫画源字节数据无效。")
}
private fun scriptAes(input: ByteArray, key: ByteArray, iv: ByteArray?, encrypt: Boolean, ecb: Boolean): ByteArray {
    require(key.size in setOf(16, 24, 32) && (ecb || iv?.size == 16)) { "AES 密钥或 IV 无效。" }
    val cipher = Cipher.getInstance(if (ecb) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding")
    if (ecb) cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    else cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(input)
}
