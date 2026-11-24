package dev.mangaflow.anime.sources

/** Native implementations keep distinct IDs; saved script selections are never rewritten. */
internal val BUNDLED_API_ANIME_IDS = setOf("anime-native-jinpai")
internal val BUNDLED_ANIME_SOURCE_IDS = BUNDLED_HTML_ANIME_IDS + BUNDLED_API_ANIME_IDS + NATIVE_LMM85_SOURCE
internal val STORED_BUNDLED_ANIME_SOURCE_IDS = BUNDLED_ANIME_SOURCE_IDS + RETIRED_YZX_NATIVE

internal fun bundledAnimeSourceVersion(id: String): String =
    when {
        id == NATIVE_LMM85_SOURCE -> "2026-11-20-lmm85"
        id in BUNDLED_API_ANIME_IDS -> "2026-11-18-native-api"
        else -> BUNDLED_HTML_ANIME_VERSION
    }
