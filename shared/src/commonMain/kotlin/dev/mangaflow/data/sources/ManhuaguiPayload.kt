package dev.mangaflow.data.sources

import dev.mangaflow.json.JSONObject

/** Public reader-payload extraction adapted from Keiyoushi (Apache-2.0), fc7a827. */
internal object ManhuaguiPayload {
    private val packed = Regex("""window\[".*?"\](\(.*\)\s*\{[\s\S]+\}\s*\(.*\))""")
    private val compressedDictionary = Regex("""['"]([0-9A-Za-z+/=]+)['"]\[['"].*?['"]]\(['"].*?['"]\)""")

    fun imageData(html: String): JSONObject {
        require(html.length <= 1_048_576) { "漫画柜阅读页过大。" }
        val script = packed.find(html)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("漫画柜未返回可解析的图片脚本。")
        val prepared = compressedDictionary.replace(script) { match ->
            val dictionary = decompressBase64(match.groupValues[1])
                .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
            "'$dictionary'.split('|')"
        }
        val unpacked = SourcePackedScript.unpack(prepared)
        val first = unpacked.indexOf('{')
        val last = unpacked.lastIndexOf('}')
        require(first >= 0 && last > first) { "漫画柜图片资料不完整。" }
        return JSONObject(unpacked.substring(first, last + 1))
    }

    /** Decompression-only subset of Keiyoushi lib/lzstring, with input/output/dictionary bounds. */
    private fun decompressBase64(input: String): String {
        require(input.length in 1..262_144 && input.all { it in ALPHABET }) { "漫画柜压缩资料无效。" }
        var index = 1
        var value = ALPHABET.indexOf(input[0])
        var position = 32
        fun readBits(count: Int): Int {
            require(count in 1..20)
            var bits = 0
            var power = 1
            repeat(count) {
                if (value and position != 0) bits = bits or power
                position = position shr 1
                if (position == 0) {
                    position = 32
                    value = if (index < input.length) ALPHABET.indexOf(input[index++]) else {
                        require(index++ == input.length) { "漫画柜压缩资料不完整。" }
                        0
                    }
                }
                power = power shl 1
            }
            return bits
        }
        val dictionary = mutableListOf("0", "1", "2")
        val character = when (readBits(2)) {
            0 -> readBits(8).toChar()
            1 -> readBits(16).toChar()
            else -> throw IllegalArgumentException("漫画柜压缩资料没有正文。")
        }
        dictionary.add(character.toString())
        var previous = character.toString()
        val result = StringBuilder(previous)
        var enlargeIn = 4
        var bitCount = 3
        while (true) {
            require(index <= input.length + 1 && dictionary.size <= 262_144)
            var code = readBits(bitCount)
            when (code) {
                0 -> { dictionary.add(readBits(8).toChar().toString()); code = dictionary.lastIndex; enlargeIn-- }
                1 -> { dictionary.add(readBits(16).toChar().toString()); code = dictionary.lastIndex; enlargeIn-- }
                2 -> return result.toString()
            }
            if (enlargeIn == 0) { enlargeIn = 1 shl bitCount; bitCount++ }
            val entry = if (code < dictionary.size) dictionary[code] else {
                require(code == dictionary.size) { "漫画柜压缩词典无效。" }
                previous + previous[0]
            }
            require(result.length + entry.length <= 4_194_304) { "漫画柜压缩资料展开结果过大。" }
            result.append(entry)
            dictionary.add(previous + entry[0])
            previous = entry
            enlargeIn--
            if (enlargeIn == 0) { enlargeIn = 1 shl bitCount; bitCount++ }
        }
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
