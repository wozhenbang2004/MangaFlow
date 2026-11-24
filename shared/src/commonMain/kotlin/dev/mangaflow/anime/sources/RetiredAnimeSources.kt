package dev.mangaflow.anime.sources

/** Archived identities are valid in existing local records, never in the active source registry. */
internal const val RETIRED_YZX_NATIVE = "anime-native-yzx"
internal const val RETIRED_YZX_SCRIPT = "anime-lanerc-yzx"
internal val RETIRED_ANIME_SOURCE_IDS = setOf(RETIRED_YZX_NATIVE, RETIRED_YZX_SCRIPT)
internal fun isRetiredAnimeSource(id: String): Boolean = id in RETIRED_ANIME_SOURCE_IDS
