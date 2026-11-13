package dev.mangaflow.data

import dev.mangaflow.platform.md5

/** Exact decimal comparison for upstream chapter labels; no floating-point rounding enters logical IDs. */
internal class DecimalNumber private constructor(private val plain: String) : Comparable<DecimalNumber> {
    fun toPlainString(): String = plain
    fun signum(): Int = when { plain == "0" -> 0; plain.startsWith('-') -> -1; else -> 1 }
    fun scale(): Int = plain.substringAfter('.', "").length
    fun longValueExact(): Long = plain.takeUnless { '.' in it }?.toLongOrNull() ?: throw ArithmeticException("Not an exact long")
    override fun toString(): String = plain
    override fun compareTo(other: DecimalNumber): Int {
        if (plain == other.plain) return 0
        val negative = plain.startsWith('-')
        val otherNegative = other.plain.startsWith('-')
        if (negative != otherNegative) return if (negative) -1 else 1
        val left = plain.removePrefix("-").split('.')
        val right = other.plain.removePrefix("-").split('.')
        val integer = left[0].length.compareTo(right[0].length).takeIf { it != 0 } ?: left[0].compareTo(right[0])
        val fraction = if (integer != 0) integer else {
            val a = left.getOrElse(1) { "" }; val b = right.getOrElse(1) { "" }
            a.padEnd(maxOf(a.length, b.length), '0').compareTo(b.padEnd(maxOf(a.length, b.length), '0'))
        }
        return if (negative) -fraction else fraction
    }
    companion object {
        fun parse(value: String): DecimalNumber? {
            if (value.length !in 1..4096) return null
            val match = Regex("([+-]?)([0-9]+)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?").matchEntire(value) ?: return null
            val exponent = match.groupValues[4].ifEmpty { "0" }.toIntOrNull()?.takeIf { it in -4096..4096 } ?: return null
            val digits = match.groupValues[2] + match.groupValues[3]
            val point = match.groupValues[2].length + exponent
            val expanded = when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length)
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }.split('.')
            val integer = expanded[0].trimStart('0').ifEmpty { "0" }
            val fraction = expanded.getOrElse(1) { "" }.trimEnd('0')
            val text = integer + if (fraction.isEmpty()) "" else ".$fraction"
            return DecimalNumber(if (match.groupValues[1] == "-" && text != "0") "-$text" else text)
        }
        fun valueOf(value: Long): DecimalNumber = DecimalNumber(value.toString())
    }
}
internal fun String.toPortableDecimalOrNull(): DecimalNumber? = DecimalNumber.parse(this)
internal fun ByteArray.hexString(): String = joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
internal fun canonicalUuid(value: String): String {
    require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) { "Invalid UUID" }
    return value.lowercase()
}
internal fun nameUuid(value: ByteArray): String {
    val bytes = md5(value)
    require(bytes.size == 16)
    bytes[6] = ((bytes[6].toInt() and 15) or 0x30).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 0x80).toByte()
    val text = bytes.hexString()
    return "${text.take(8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-${text.substring(16, 20)}-${text.substring(20)}"
}
internal fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key).also { if (it == null) put(key, value) }
