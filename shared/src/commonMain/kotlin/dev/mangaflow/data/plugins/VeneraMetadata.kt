package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONObject

/** Reads declarations as inert text. Installing still requires compiling and inspecting the actual class. */
internal object VeneraMetadata {
    private val declaration = Regex("(?m)^\\s*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+extends\\s+ComicSource\\s*\\{")
    private val adultKeys = setOf("picacg", "jm", "nhentai", "ehentai", "wnacg", "hitomi", "mh18", "hcomic", "jcomic", "manwaba", "hot_manga")
    private fun adultFile(value: String): Boolean = value.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').lowercase().removeSuffix(".js") in adultKeys
    fun isScript(code: String): Boolean = declaration.containsMatchIn(code)
    fun isEntry(value: JSONObject): Boolean = value.optString("fileName").endsWith(".js", true) &&
        value.optString("key").isNotBlank()

    fun fromIndex(value: JSONObject, origin: String): JSONObject {
        val key = value.getString("key")
        require(key.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Venera 源编号无效。" }
        val address = publicPluginUrl(origin).resolve(value.getString("fileName"))
            ?: throw SourceException("Venera 仓库脚本地址无效。")
        val url = publicPluginUrl(address.toString()).toString()
        return JSONObject(value.toString()).put("protocol", "venera")
            .put("lang", value.optString("lang").ifBlank { "und" })
            .put("baseUrl", value.optString("baseUrl").ifBlank { publicPluginUrl(url).newBuilder().fragment(null).build().toString() })
            .put("sourceCodeUrl", url).put("sourceCodeLanguage", 1).put("isManga", true)
            .put("isAdult", adultSourceMetadata(value) || key.lowercase() in adultKeys || adultFile(value.optString("fileName")))
    }

    fun fromScript(code: String, origin: String): JSONObject {
        val match = declaration.find(code) ?: throw SourceException("未找到 Venera ComicSource 声明。")
        val body = code.substring(match.range.last + 1)
        fun field(name: String): String = Regex("(?m)^\\s*" + Regex.escape(name) + "\\s*=\\s*([\"'])([^\"'\\r\\n]{1,4096})\\1")
            .find(body)?.groupValues?.get(2).orEmpty()
        fun booleanField(name: String): Boolean? = Regex("(?m)^\\s*" + Regex.escape(name) + "\\s*=\\s*(true|false)\\b")
            .find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        val key = field("key")
        require(key.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Venera 源必须声明静态 key。" }
        val base = field("baseUrl").takeIf { it.startsWith("https://") }
            ?: origin.takeIf { it.startsWith("https://") } ?: field("url")
        val metadata = JSONObject().put("protocol", "venera").put("entryClass", match.groupValues[1])
            .put("key", key).put("name", field("name")).put("lang", field("lang").ifBlank { "und" })
            .put("version", field("version")).put("minAppVersion", field("minAppVersion"))
            .put("baseUrl", publicPluginUrl(base).toString()).put("isManga", true).put("sourceCodeLanguage", 1)
            .put("isAdult", booleanField("isNsfw") == true || booleanField("isAdult") == true ||
                key.lowercase() in adultKeys || adultFile(origin) || adultFile(field("url")))
        booleanField("isNsfw")?.let { metadata.put("isNsfw", it) }
        if (origin.startsWith("https://")) metadata.put("sourceCodeUrl", publicPluginUrl(origin).toString())
        else if (field("url").startsWith("https://")) metadata.put("sourceCodeUrl", publicPluginUrl(field("url")).toString())
        return metadata
    }

    /** A fork gets an independent package/session identity even when it reuses a ComicSource key. */
    fun namespace(metadata: JSONObject): String {
        val url = publicPluginUrl(metadata.optString("sourceCodeUrl").ifBlank { metadata.getString("baseUrl") })
        val parts = url.pathSegments
        return when {
            url.host == "raw.githubusercontent.com" && parts.size >= 2 -> "github:" + parts.take(2).joinToString("/").lowercase()
            url.host in setOf("github.com", "www.github.com") && parts.size >= 2 -> "github:" + parts.take(2).joinToString("/").lowercase()
            url.host == "cdn.jsdelivr.net" && parts.size >= 3 && parts[0] == "gh" ->
                "github:" + parts[1].lowercase() + "/" + parts[2].substringBefore('@').lowercase()
            else -> url.host + ":" + url.encodedPath.substringBeforeLast('/')
        }
    }
}

/** Static JS metadata accepts quotes/comments/trailing commas, never expressions or executable code. */
internal fun staticJavascriptJson(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c == '/' && text.getOrNull(index + 1) == '/' -> {
                index = text.indexOf('\n', index + 2).takeIf { it >= 0 } ?: text.length
            }
            c == '/' && text.getOrNull(index + 1) == '*' -> {
                val end = text.indexOf("*/", index + 2)
                require(end >= 0) { "源元数据注释不完整。" }; index = end + 2
            }
            c == '\'' || c == '"' -> {
                val quote = c; index++
                val value = StringBuilder()
                var closed = false
                while (index < text.length) {
                    val next = text[index++]
                    if (next == quote) { closed = true; break }
                    if (next != '\\') { value.append(next); continue }
                    require(index < text.length) { "源元数据转义不完整。" }
                    when (val escaped = text[index++]) {
                        'n' -> value.append('\n'); 'r' -> value.append('\r'); 't' -> value.append('\t')
                        'b' -> value.append('\b'); 'f' -> value.append('\u000C')
                        'u', 'x' -> {
                            val count = if (escaped == 'u') 4 else 2
                            require(index + count <= text.length) { "源元数据 Unicode 转义不完整。" }
                            value.append(text.substring(index, index + count).toInt(16).toChar()); index += count
                        }
                        else -> value.append(escaped)
                    }
                }
                require(closed) { "源元数据字符串不完整。" }
                append(JSONObject.quote(value.toString()))
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = index++
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "_$")) index++
                val word = text.substring(start, index)
                var cursor = index
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                if (text.getOrNull(cursor) == ':') append(JSONObject.quote(word))
                else { require(word in setOf("true", "false", "null")) { "源元数据必须是静态字面量。" }; append(word) }
            }
            c == '`' || c == '(' || c == ')' || c == '=' -> throw SourceException("源元数据不支持动态表达式。")
            else -> { append(c); index++ }
        }
    }
}
