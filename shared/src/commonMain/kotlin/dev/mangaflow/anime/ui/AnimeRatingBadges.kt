package dev.mangaflow.anime.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.ui.SourceMetricBadges

/** Keep anime identity and score loading separate; render through the manga score component. */
@Composable
internal fun AnimeRatingBadges(
    identity: String,
    scores: List<AnimeSourceScore>,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    valueSize: TextUnit? = null,
    onExpand: (() -> Unit)? = null,
) {
    val metrics = remember(scores) {
        scores.filter { it.rating.isFinite() && it.rating > 0 && it.scale > 0 && it.rating <= it.scale }
            .distinctBy { it.provider }.map { score ->
                MangaMetric(
                    sourceId = animeDiscoveryScoreSourceId(score.provider),
                    name = animeDiscoveryProviderLabel(score.provider),
                    score = score.rating,
                    scoreScale = score.scale,
                    ratingCount = score.ratingCount,
                )
            }
    }
    SourceMetricBadges(
        identity = "anime:$identity",
        metrics = metrics,
        modifier = modifier,
        foreground = foreground,
        compact = true,
        showMore = true,
        plain = true,
        valueSize = valueSize,
        sourceOrder = listOf("douban", "bangumi", "anilist"),
        onExpand = onExpand,
    )
}
