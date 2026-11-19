package dev.mangaflow.anime

import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.ExperienceMode
import dev.mangaflow.experience.ExperienceResources

private const val ANIME_MIB = 1024L * 1024L

/** A projection of the existing shared policy, not a second resource or cache owner. */
data class AnimePreparationBudget(
    val forwardBytes: Long,
    val backBytes: Long,
    val nextEpisodeLeadMs: Long,
    val nextEpisodeDurationMs: Long,
    val nextEpisodeBytes: Long,
    val onlineWindowMs: Long,
)

internal fun resolveAnimePreparationBudget(
    settings: AnimePlaybackSettings,
    policy: ExperienceBudget,
    resources: ExperienceResources,
): AnimePreparationBudget {
    val eco = policy.mode == ExperienceMode.ECO
    val performance = policy.mode == ExperienceMode.PERFORMANCE
    val forwardGoal = (if (eco) 24L else if (performance) 128L else 64L) * ANIME_MIB
    val automaticForward = resources.availableMemoryBytes?.takeIf { it > 0L }
        ?.let { minOf(forwardGoal, (it / 16L).coerceAtLeast(8L * ANIME_MIB)) } ?: forwardGoal
    val forward = settings.bufferForwardMiB?.toLong()?.times(ANIME_MIB) ?: automaticForward
    val automaticNext = (if (eco) 4L else if (performance) 24L else 12L) * ANIME_MIB
    return AnimePreparationBudget(
        forwardBytes = forward,
        backBytes = settings.bufferBackMiB?.toLong()?.times(ANIME_MIB) ?: automaticForward / 8L,
        nextEpisodeLeadMs = (settings.nextEpisodeLeadSeconds?.toLong()
            ?: if (eco) 30L else if (performance) 90L else 60L) * 1_000L,
        nextEpisodeDurationMs = (settings.nextEpisodeBufferSeconds?.toLong()
            ?: if (eco) 8L else if (performance) 30L else 15L) * 1_000L,
        nextEpisodeBytes = settings.nextEpisodeCacheMiB?.toLong()?.times(ANIME_MIB)
            ?: minOf(automaticNext, policy.prefetchBytes.coerceAtLeast(0L)),
        onlineWindowMs = (settings.onlinePreparationSeconds?.toLong()
            ?: if (eco) 5L else if (performance) 15L else 10L) * 1_000L,
    )
}

internal fun AnimePlaybackSettings.sameNextPreparation(other: AnimePlaybackSettings): Boolean =
    prepareNextEpisode == other.prepareNextEpisode && preferredSourceHeight == other.preferredSourceHeight &&
        nextEpisodeLeadSeconds == other.nextEpisodeLeadSeconds &&
        nextEpisodeBufferSeconds == other.nextEpisodeBufferSeconds &&
        nextEpisodeCacheMiB == other.nextEpisodeCacheMiB && predecodeNextEpisode == other.predecodeNextEpisode
