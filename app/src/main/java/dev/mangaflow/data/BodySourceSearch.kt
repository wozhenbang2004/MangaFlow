package dev.mangaflow.data

import android.os.Build
import java.text.Normalizer
import java.util.Locale

/** Use platform ICU like the existing review matcher; no bundled dictionary or title-specific aliases. */
private fun convertBodyTitle(value: String, transform: String): String =
    if (Build.VERSION.SDK_INT >= 29) try {
        android.icu.text.Transliterator.getInstance(transform).transliterate(value)
    } catch (_: IllegalArgumentException) { value } else value

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
    convertBodyTitle(Normalizer.normalize(value, Normalizer.Form.NFKC), "Traditional-Simplified")
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it in "≡∞×+" }

internal fun normalizedBodySourceAuthor(value: String): String {
    val expanded = buildString {
        value.forEach { character ->
            if (character == '々' && isNotEmpty()) append(last()) else append(character)
        }
    }
    return normalizedBodySourceTitle(expanded)
}
