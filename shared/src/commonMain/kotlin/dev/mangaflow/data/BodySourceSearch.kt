package dev.mangaflow.data

import dev.mangaflow.platform.*

/** Use platform ICU like the existing review matcher; no bundled dictionary or title-specific aliases. */
private fun convertBodyTitle(value: String, transform: String): String = transliterate(value, transform)

/** Empty searches can try translated forms and confirmed aliases within one bounded source request. */
internal fun bodySourceSearchTerms(query: String, aliases: List<String> = emptyList()): List<String> =
    buildList {
        val primary = query.trim()
        if (primary.isNotEmpty()) {
            add(primary)
            add(convertBodyTitle(primary, "Traditional-Simplified"))
            add(convertBodyTitle(primary, "Simplified-Traditional"))
        }
        aliases.filter(String::isNotBlank).forEach { add(it.trim()) }
    }.filter { it.length in 1..120 }.distinct().take(4)

/** Keep sequel symbols: stripping ≡ would conflate Jujutsu Kaisen with its independent sequel. */
internal fun normalizedBodySourceTitle(value: String): String =
    convertBodyTitle(normalizeNfkc(value), "Traditional-Simplified")
        .replace('迴', '回').lowercase().filter { it.isLetterOrDigit() || it in "≡∞×+" }

internal fun normalizedBodySourceAuthor(value: String): String {
    val expanded = buildString {
        value.forEach { character ->
            if (character == '々' && isNotEmpty()) append(last()) else append(character)
        }
    }
    return normalizedBodySourceTitle(expanded)
}
