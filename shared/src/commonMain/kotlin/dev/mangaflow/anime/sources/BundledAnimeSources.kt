package dev.mangaflow.anime.sources

/** Native implementations keep distinct IDs; saved script selections are never rewritten. */
internal val BUNDLED_API_ANIME_IDS = setOf("anime-native-jinpai", "anime-native-yzx", CYCITY_SOURCE_ID)
internal val BUNDLED_ANIME_SOURCE_IDS = BUNDLED_HTML_ANIME_IDS + BUNDLED_API_ANIME_IDS

internal fun bundledAnimeSourceVersion(id: String): String =
    when {
        id == CYCITY_SOURCE_ID -> "2026-11-20-cycity-web"
        id in BUNDLED_API_ANIME_IDS -> "2026-11-18-native-api"
        else -> BUNDLED_HTML_ANIME_VERSION
    }
