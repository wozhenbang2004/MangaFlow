@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.data.plugins

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.md5
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.hmacSha256
import dev.mangaflow.platform.randomUuid
import kotlinx.cinterop.*
import platform.CoreCrypto.*
import kotlin.io.encoding.Base64

internal actual suspend fun prepareJavascriptEngine(context: PlatformContext) = Unit

internal actual fun javascriptCodec(raw: String): String {
    require(raw.length <= 2 * 1024 * 1024) { "漫画源编码数据过大。" }
    val request = JSONObject(raw)
    return when (request.getString("op")) {
        "uuid" -> randomUuid()
        "atob" -> Base64.decode(request.getString("text").filterNot(Char::isWhitespace)).map { (it.toInt() and 255).toChar() }.joinToString("")
        "btoa" -> {
            val text = request.getString("text")
            require(text.all { it.code <= 255 })
            Base64.encode(ByteArray(text.length) { text[it].code.toByte() })
        }
        "aesCbc" -> {
            val encrypt = request.getBoolean("encrypt")
            val bytes = if (encrypt) request.getString("text").encodeToByteArray() else Base64.decode(request.getString("text").filterNot(Char::isWhitespace))
            val output = nativeScriptAes(bytes, request.getString("key").encodeToByteArray(), request.getString("iv").encodeToByteArray(), encrypt, false)
            if (encrypt) Base64.encode(output) else output.decodeToString()
        }
        "venera" -> {
            val type = request.getString("type")
            val encoding = request.optBoolean("isEncode", true)
            val value = request.opt("value")
            val input = nativeScriptBytes(value)
            val result: Any = when (type) {
                "utf8" -> if (encoding) nativeUnsignedBytes((value as? String ?: error("UTF-8 输入无效。")).encodeToByteArray()) else input.decodeToString()
                "base64" -> if (encoding) Base64.encode(input) else nativeUnsignedBytes(Base64.decode((value as? String ?: error("Base64 输入无效。")).filterNot(Char::isWhitespace)))
                "hex" -> if (encoding) input.joinToString("") { it.toUByte().toString(16).padStart(2, '0') } else {
                    val text = value as? String ?: error("Hex 输入无效。")
                    require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" })
                    nativeUnsignedBytes(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
                }
                "md5" -> nativeUnsignedBytes(md5(input))
                "sha256" -> nativeUnsignedBytes(sha256(input))
                "sha1", "sha512" -> nativeUnsignedBytes(nativeDigest(input, type))
                "hmac" -> {
                    val hash = request.optString("hash", "sha256").lowercase().replace("-", "")
                    val bytes = if (hash == "sha256") hmacSha256(nativeScriptBytes(request.opt("key")), input)
                        else nativeHmac(nativeScriptBytes(request.opt("key")), input, hash)
                    if (request.optBoolean("isString", false)) bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') } else nativeUnsignedBytes(bytes)
                }
                "aes-cbc", "aes-ecb" -> nativeUnsignedBytes(nativeScriptAes(input, nativeScriptBytes(request.opt("key")),
                    if (type == "aes-cbc") nativeScriptBytes(request.opt("iv")) else null, encoding, type == "aes-ecb"))
                else -> error("不支持的漫画源加密或编码能力。")
            }
            scriptJsonValue(result)
        }
        else -> error("不支持的漫画源编码或加密方式。")
    }
}

private fun nativeUnsignedBytes(value: ByteArray) = JSONArray(value.map { it.toInt() and 255 })
private fun nativeScriptBytes(value: Any?): ByteArray = when (value) {
    is String -> value.encodeToByteArray()
    is JSONArray -> ByteArray(value.length()) { index ->
        val item = value.get(index)
        require(item is Number && item.toInt() in 0..255 && item.toDouble() == item.toInt().toDouble()) { "漫画源字节数据无效。" }
        item.toInt().toByte()
    }
    else -> error("漫画源字节数据无效。")
}
private fun nativeDigest(input: ByteArray, kind: String): ByteArray {
    val output = ByteArray(if (kind == "sha1") CC_SHA1_DIGEST_LENGTH else CC_SHA512_DIGEST_LENGTH)
    (if (input.isEmpty()) byteArrayOf(0) else input).usePinned { source -> output.usePinned { destination ->
        if (kind == "sha1") CC_SHA1(source.addressOf(0), input.size.convert(), destination.addressOf(0).reinterpret())
        else CC_SHA512(source.addressOf(0), input.size.convert(), destination.addressOf(0).reinterpret())
    } }
    return output
}
private fun nativeHmac(key: ByteArray, input: ByteArray, hash: String): ByteArray {
    val algorithm = when (hash) { "sha1" -> kCCHmacAlgSHA1; "sha512" -> kCCHmacAlgSHA512; "md5" -> kCCHmacAlgMD5; else -> error("不支持的 HMAC 摘要。") }
    val output = ByteArray(when(hash) { "sha1" -> CC_SHA1_DIGEST_LENGTH; "sha512" -> CC_SHA512_DIGEST_LENGTH; else -> CC_MD5_DIGEST_LENGTH })
    (if (key.isEmpty()) byteArrayOf(0) else key).usePinned { keyBytes -> (if (input.isEmpty()) byteArrayOf(0) else input).usePinned { source -> output.usePinned { destination ->
        CCHmac(algorithm, keyBytes.addressOf(0), key.size.convert(), source.addressOf(0), input.size.convert(), destination.addressOf(0))
    } } }
    return output
}
private fun nativeScriptAes(input: ByteArray, key: ByteArray, iv: ByteArray?, encrypt: Boolean, ecb: Boolean): ByteArray = memScoped {
    require(key.size in setOf(16, 24, 32) && (ecb || iv?.size == 16)) { "AES 密钥或 IV 无效。" }
    val output = ByteArray(input.size + kCCBlockSizeAES128.toInt())
    val count = alloc<ULongVar>()
    val status = key.usePinned { keyBytes -> (if (input.isEmpty()) byteArrayOf(0) else input).usePinned { source -> output.usePinned { destination ->
        val perform: (COpaquePointer?) -> Int = { ivBytes ->
            CCCrypt(if (encrypt) kCCEncrypt else kCCDecrypt, kCCAlgorithmAES,
                kCCOptionPKCS7Padding or if (ecb) kCCOptionECBMode else 0u,
                keyBytes.addressOf(0), key.size.convert(), ivBytes, source.addressOf(0), input.size.convert(),
                destination.addressOf(0), output.size.convert(), count.ptr)
        }
        if (iv == null) perform(null) else iv.usePinned { perform(it.addressOf(0)) }
    } } }
    require(status == kCCSuccess) { "漫画源 AES 数据解码失败。" }
    output.copyOf(count.value.toInt())
}
