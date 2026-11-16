package dev.mangaflow.anime.danmaku

enum class AnimeDanmakuMode { SCROLL, TOP, BOTTOM }

data class AnimeDanmakuCandidate(
    val episodeId: Long, val animeId: Long, val animeTitle: String, val episodeTitle: String,
)

data class AnimeDanmakuSettings(
    val enabled: Boolean = true,
    val opacity: Float = 0.85f,
    val fontScale: Float = 1f,
    val showColored: Boolean = true,
    val deduplicate: Boolean = true,
    val density: Int = 8,
    /** Positive values delay comments relative to the video. */
    val offsetMs: Long = 0L,
    val blockedWords: List<String> = emptyList(),
) {
    fun validate(): AnimeDanmakuSettings {
        require(opacity.isFinite() && opacity in 0f..1f && fontScale.isFinite() && fontScale > 0f) { "弹幕透明度或字号超出范围。" }
        require(density > 0) { "弹幕密度需要大于零。" }
        require(blockedWords.size <= 64 && blockedWords.all { it.length <= 200 }) { "弹幕屏蔽词过多或过长。" }
        return this
    }
}

data class AnimeDanmakuFrame(
    val id: String, val text: String, val color: Int, val mode: AnimeDanmakuMode,
    val lane: Int, val progress: Float,
)

data class AnimeDanmakuState(
    val ownerKey: String? = null,
    val configured: Boolean = false,
    val searching: Boolean = false,
    val loading: Boolean = false,
    val query: String = "",
    val candidates: List<AnimeDanmakuCandidate> = emptyList(),
    val selected: AnimeDanmakuCandidate? = null,
    val selectionLabel: String? = null,
    val totalCount: Int = 0,
    val visible: List<AnimeDanmakuFrame> = emptyList(),
    val settings: AnimeDanmakuSettings = AnimeDanmakuSettings(),
    val error: String? = null,
    val notice: String? = null,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
)

internal data class AnimeDanmakuComment(
    val id: String, val timeMs: Long, val text: String, val color: Int, val mode: AnimeDanmakuMode,
)
internal data class AnimeDanmakuSearchResult(val candidates: List<AnimeDanmakuCandidate>, val hasMore: Boolean)
internal class AnimeDanmakuException(message: String) : IllegalStateException(message)
