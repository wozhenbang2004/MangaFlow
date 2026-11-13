package dev.mangaflow.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Small migration adapter over kotlinx.serialization; source parsers retain their original contracts. */
class JSONException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val parser = Json { isLenient = true; allowTrailingComma = true }
private fun parseJson(text: String): Any? = try { unwrap(parser.parseToJsonElement(text.removePrefix("\uFEFF"))) }
    catch (error: Exception) { throw JSONException("Invalid JSON", error) }
private fun unwrap(value: JsonElement): Any? = when (value) {
    JsonNull -> JSONObject.NULL
    is JsonObject -> JSONObject(value.mapValues { unwrap(it.value) })
    is JsonArray -> JSONArray(value.map(::unwrap))
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull
        ?: value.longOrNull ?: value.doubleOrNull ?: throw JSONException("Invalid JSON value")
}
private fun wrap(value: Any?): JsonElement = when (value) {
    null, JSONObject.NULL -> JsonNull
    is JSONObject -> JsonObject(value.values.mapValues { wrap(it.value) })
    is JSONArray -> JsonArray(value.values.map(::wrap))
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> {
        if (!value.toDouble().isFinite()) throw JSONException("Non-finite JSON number")
        JsonPrimitive(value)
    }
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to wrap(item) })
    is Iterable<*> -> JsonArray(value.map(::wrap))
    else -> JsonPrimitive(value.toString())
}

class JSONObject {
    internal val values: MutableMap<String, Any?>
    constructor() { values = linkedMapOf() }
    constructor(text: String) { values = ((parseJson(text) as? JSONObject)
        ?: throw JSONException("Expected a JSON object")).values }
    constructor(map: Map<*, *>) { values = linkedMapOf<String, Any?>().apply {
        map.forEach { (key, value) -> if (key != null) put(key.toString(), normalized(value)) }
    } }
    fun put(key: String, value: Any?): JSONObject = apply {
        if (value == null) values.remove(key) else values[key] = normalized(value)
    }
    fun opt(key: String): Any? = values[key]
    fun get(key: String): Any = values[key] ?: throw JSONException("Missing JSON property: $key")
    fun has(key: String): Boolean = values.containsKey(key)
    fun isNull(key: String): Boolean = values[key] == null || values[key] === NULL
    fun length(): Int = values.size
    fun keys(): Iterator<String> = values.keys.iterator()
    fun remove(key: String): Any? = values.remove(key)
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    fun getJSONObject(key: String): JSONObject = optJSONObject(key) ?: throw JSONException("Expected object: $key")
    fun getJSONArray(key: String): JSONArray = optJSONArray(key) ?: throw JSONException("Expected array: $key")
    fun getString(key: String): String = stringValue(get(key)) ?: throw JSONException("Expected text: $key")
    fun optString(key: String): String = optString(key, "")
    fun optString(key: String, fallback: String): String = stringValue(opt(key)) ?: fallback
    fun optString(key: String, fallback: Nothing?): String? = stringValue(opt(key))
    fun optInt(key: String, fallback: Int = 0): Int = numberValue(opt(key))?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = numberValue(opt(key))?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = numberValue(opt(key))?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = booleanValue(opt(key)) ?: fallback
    fun getInt(key: String): Int = numberValue(get(key))?.toInt() ?: throw JSONException("Expected integer: $key")
    fun getLong(key: String): Long = numberValue(get(key))?.toLong() ?: throw JSONException("Expected integer: $key")
    fun getDouble(key: String): Double = numberValue(get(key))?.toDouble() ?: throw JSONException("Expected number: $key")
    fun getBoolean(key: String): Boolean = booleanValue(get(key)) ?: throw JSONException("Expected boolean: $key")
    override fun toString(): String = wrap(this).toString()
    fun toString(indentSpaces: Int): String = toString()
    companion object {
        /** Distinct JSON null, retaining org.json's opt/isNull and explicit-null behavior. */
        val NULL: Any = object { override fun toString() = "null" }
        fun quote(value: String?): String = JsonPrimitive(value.orEmpty()).toString()
    }
}

class JSONArray {
    internal val values: MutableList<Any?>
    constructor() { values = mutableListOf() }
    constructor(text: String) { values = ((parseJson(text) as? JSONArray)
        ?: throw JSONException("Expected a JSON array")).values }
    constructor(items: Collection<*>) { values = items.mapTo(mutableListOf(), ::normalized) }
    fun put(value: Any?): JSONArray = apply { values.add(normalized(value)) }
    fun put(index: Int, value: Any?): JSONArray = apply {
        if (index < 0) throw JSONException("Negative array index")
        while (values.size <= index) values.add(JSONObject.NULL)
        values[index] = normalized(value)
    }
    fun length(): Int = values.size
    fun opt(index: Int): Any? = values.getOrNull(index)
    fun get(index: Int): Any = opt(index) ?: throw JSONException("Missing JSON index: $index")
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray
    fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: throw JSONException("Expected object: $index")
    fun getJSONArray(index: Int): JSONArray = optJSONArray(index) ?: throw JSONException("Expected array: $index")
    fun optString(index: Int, fallback: String = ""): String = stringValue(opt(index)) ?: fallback
    fun getString(index: Int): String = stringValue(get(index)) ?: throw JSONException("Expected text: $index")
    fun optInt(index: Int, fallback: Int = 0): Int = numberValue(opt(index))?.toInt() ?: fallback
    fun optLong(index: Int, fallback: Long = 0): Long = numberValue(opt(index))?.toLong() ?: fallback
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = numberValue(opt(index))?.toDouble() ?: fallback
    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = booleanValue(opt(index)) ?: fallback
    fun getInt(index: Int): Int = numberValue(get(index))?.toInt() ?: throw JSONException("Expected integer: $index")
    fun getLong(index: Int): Long = numberValue(get(index))?.toLong() ?: throw JSONException("Expected integer: $index")
    fun getDouble(index: Int): Double = numberValue(get(index))?.toDouble() ?: throw JSONException("Expected number: $index")
    fun getBoolean(index: Int): Boolean = booleanValue(get(index)) ?: throw JSONException("Expected boolean: $index")
    fun isNull(index: Int): Boolean = opt(index) == null || opt(index) === JSONObject.NULL
    fun remove(index: Int): Any? = if (index in values.indices) values.removeAt(index) else null
    override fun toString(): String = wrap(this).toString()
    fun toString(indentSpaces: Int): String = toString()
}

/** Reads bounded literal arrays produced by the attributed packed-script extractor, never executes JS. */
class JSONTokener(private val text: String) {
    fun nextValue(): Any? {
        require(text.length <= 4 * 1024 * 1024) { "JSON literal is too large" }
        return parseJson(normalizeSingleQuotes(text))
    }
    private fun normalizeSingleQuotes(value: String): String = buildString(value.length) {
        var index = 0
        var quote: Char? = null
        while (index < value.length) {
            val char = value[index++]
            val active = quote
            if (active == null) {
                when (char) { '\'', '"' -> { quote = char; append('"') }; else -> append(char) }
            } else if (char == active) { quote = null; append('"') }
            else if (char == '\\' && index < value.length) {
                val escaped = value[index++]
                if (escaped == '\'' && active == '\'') append('\'')
                else { append('\\'); append(escaped) }
            } else if (char == '"' && active == '\'') append("\\\"")
            else append(char)
        }
        if (quote != null) throw JSONException("Unterminated JSON string")
    }
}

private fun normalized(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject(value)
    is Collection<*> -> JSONArray(value)
    else -> value
}
private fun stringValue(value: Any?): String? = when (value) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
}
private fun numberValue(value: Any?): Number? = when (value) {
    is Number -> value
    is String -> value.toLongOrNull() ?: value.toDoubleOrNull()
    else -> null
}
private fun booleanValue(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> when { value.equals("true", true) -> true; value.equals("false", true) -> false; else -> null }
    else -> null
}
