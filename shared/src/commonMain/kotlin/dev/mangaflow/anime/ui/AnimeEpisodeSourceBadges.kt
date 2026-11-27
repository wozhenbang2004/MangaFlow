package dev.mangaflow.anime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeEpisodeOption
import dev.mangaflow.anime.AnimeScreenState

/** Like manga chapter variants, these marks belong to one confirmed episode mapping. */
internal data class AnimeEpisodeSources(
    val sources: List<Pair<String, String>>,
    val retrySources: Int,
    val unavailableSources: Int,
)

internal fun animeEpisodeSources(
    episodeId: String,
    lines: List<AnimeEpisodeOption>,
    controller: AnimeController,
    state: AnimeScreenState,
): AnimeEpisodeSources {
    val grouped = lines.groupBy { it.sourceId }
    return AnimeEpisodeSources(
        sources = grouped.map { (id, options) -> id to options.first().sourceName },
        retrySources = grouped.values.count { options ->
            options.any { controller.canAttemptPlaybackLine(episodeId, it, state) } &&
                options.all { controller.recentPlaybackLineFailure(episodeId, it, state) != null }
        },
        unavailableSources = grouped.values.count { options ->
            options.none { controller.canAttemptPlaybackLine(episodeId, it, state) }
        },
    )
}

@Composable
internal fun AnimeEpisodeSourceBadges(
    sources: AnimeEpisodeSources,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ink: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onOpen: () -> Unit,
) {
    if (sources.sources.isEmpty()) return
    AnimeSourceBadge(sources.sources, modifier.semantics {
        stateDescription = buildList {
            add("本集已匹配 ${sources.sources.size} 个来源")
            if (sources.retrySources > 0) add("${sources.retrySources} 个来源待重试")
            if (sources.unavailableSources > 0) add("${sources.unavailableSources} 个来源暂不可用")
        }.joinToString("，")
    }, compact = true, enabled = enabled, ink = ink, onClick = onOpen)
}
