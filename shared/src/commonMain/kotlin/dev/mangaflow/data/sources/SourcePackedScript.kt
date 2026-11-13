package dev.mangaflow.data.sources

/**
 * Bounded data-only adaptation of Keiyoushi's Apache-2.0 lib/unpacker at
 * fc7a827769f612dfa6dccb51e819f5b4eecaeab5. Never evaluates JavaScript.
 * Packer's argument dictionary is expanded into text for subsequent JSON parsing.
 */
internal object SourcePackedScript {
    private const val MAX_INPUT = 1_048_576
    private const val MAX_OUTPUT = 4_194_304
    private val invocation = Regex("""\}\s*\(""")
    private val words = Regex("""\b[0-9A-Za-z]+\b""")

    fun unpack(script: String): String {
        require(script.length in 1..MAX_INPUT) { "来源脚本大小不受支持。" }
        for (match in invocation.findAll(script).take(32)) {
            val arguments = runCatching {
                val reader = Reader(script, match.range.last + 1)
                val payload = reader.string()
                reader.comma()
                val radix = reader.number()
                reader.comma()
                val count = reader.number()
                reader.comma()
                val dictionary = reader.string()
                reader.expect(".split")
                reader.expect("(")
                require(reader.string() == "|")
                reader.expect(")")
                require(radix in 2..62 && count in 1..100_000)
                Triple(payload, radix, dictionary.split('|').also { require(it.size in count..100_000) })
            }.getOrNull() ?: continue
            val (payload, radix, dictionary) = arguments
            val result = StringBuilder(payload.length)
            var end = 0
            fun append(value: String) {
                require(result.length + value.length <= MAX_OUTPUT) { "来源脚本展开结果过大。" }
                result.append(value)
            }
            for (word in words.findAll(payload)) {
                append(payload.substring(end, word.range.first))
                val index = indexOf(word.value, radix, dictionary.size)
                append(if (index != null) dictionary[index].ifEmpty { word.value } else word.value)
                end = word.range.last + 1
            }
            append(payload.substring(end))
            return result.toString()
        }
        throw IllegalArgumentException("来源图片脚本格式已变化，暂时无法解析。")
    }

    private fun indexOf(word: String, radix: Int, limit: Int): Int? {
        var index = 0L
        for (character in word) {
            val digit = when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'z' -> character - 'a' + 10
                in 'A'..'Z' -> character - 'A' + 36
                else -> return null
            }
            if (digit >= radix) return null
            index = index * radix + digit
            if (index >= limit) return null
        }
        return index.toInt()
    }

    private class Reader(private val text: String, private var index: Int) {
        private fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        fun expect(value: String) {
            whitespace()
            require(text.startsWith(value, index))
            index += value.length
        }
        fun comma() = expect(",")
        fun number(): Int {
            whitespace()
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            require(index > start)
            return text.substring(start, index).toInt()
        }
        fun string(): String {
            whitespace()
            val quote = text.getOrNull(index++)
            require(quote == '\'' || quote == '"')
            val result = StringBuilder()
            while (index < text.length) {
                val value = text[index++]
                if (value == quote) return result.toString()
                if (value != '\\') { result.append(value); continue }
                require(index < text.length)
                result.append(when (val escaped = text[index++]) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'
                    'x', 'u' -> {
                        val count = if (escaped == 'x') 2 else 4
                        require(index + count <= text.length)
                        val character = text.substring(index, index + count).toInt(16).toChar()
                        index += count
                        character
                    }
                    else -> escaped
                })
            }
            throw IllegalArgumentException("来源脚本字符串不完整。")
        }
    }
}
