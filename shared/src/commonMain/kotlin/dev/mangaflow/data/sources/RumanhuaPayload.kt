@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.data.sources

import dev.mangaflow.json.JSONArray
import kotlin.io.encoding.Base64

/**
 * Data-only adaptation of Keiyoushi MMLook/PageDecrypt (Apache-2.0),
 * 9080fbfd1d02ce2cad0f7466996fa19816f39401. This decodes the public reader's
 * image manifest; it never evaluates JavaScript or modifies image quality.
 */
internal object RumanhuaPayload {
    private val assignment = Regex("""^\s*var\s+__c0rst96\s*=\s*"([A-Za-z0-9+/=\s]+)"\s*;?\s*$""")
    private val codec = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val keys = listOf("smkhy258", "smkd95fv", "md496952", "cdcsdwq", "vbfsa256",
        "cawf151c", "cd56cvda", "8kihnt9", "dso15tlo", "5ko6plhy")

    fun images(script: String, keyIndex: Int): List<String> {
        require(keyIndex in keys.indices) { "如漫画公开阅读资料的密钥版本已变化。" }
        val expanded = SourcePackedScript.unpack(script)
        val data = assignment.matchEntire(expanded)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("如漫画公开阅读资料格式已变化。")
        val first = decode(data)
        val key = keys[keyIndex].encodeToByteArray()
        for (index in first.indices) first[index] = (first[index].toInt() xor key[index % key.size].toInt()).toByte()
        val json = decode(first.decodeToString(throwOnInvalidSequence = true))
            .decodeToString(throwOnInvalidSequence = true)
        val images = JSONArray(json)
        require(images.length() in 1..10_000) { "如漫画没有返回有效的整章图片。" }
        return (0 until images.length()).map { index ->
            images.getString(index).also { require(it.length in 1..8192) { "如漫画图片地址不完整。" } }
        }
    }

    private fun decode(value: String): ByteArray {
        require(value.length in 1..4_194_304) { "如漫画阅读资料大小不受支持。" }
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.isNotEmpty() && compact.all { it in 'A'..'Z' || it in 'a'..'z' ||
            it in '0'..'9' || it == '+' || it == '/' || it == '=' }) { "如漫画阅读资料编码无效。" }
        return codec.decode(compact)
    }
}
