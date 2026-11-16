package dev.mangaflow.anime.player

/** These are MangaFlow's small/medium CNN combinations, not upstream Mode A/B/C. */
enum class AnimeVideoEnhancementMode { OFF, FAST, QUALITY }

/** Configuration acceptance alone cannot claim that a GPU has processed a frame. */
enum class AnimeVideoEnhancementPhase { OFF, WAITING, CONFIGURED, ACTIVE, UNAVAILABLE, FAILED }

data class AnimeVideoEnhancementState(
    val mode: AnimeVideoEnhancementMode = AnimeVideoEnhancementMode.OFF,
    val phase: AnimeVideoEnhancementPhase = AnimeVideoEnhancementPhase.OFF,
    val detail: String? = null,
    val inputWidth: Int? = null,
    val inputHeight: Int? = null,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
)

internal data class Anime4KShader(val name: String, val source: String)
