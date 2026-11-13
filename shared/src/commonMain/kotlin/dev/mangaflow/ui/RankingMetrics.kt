package dev.mangaflow.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.data.Manga

/** Period heat keeps the selected ranking's native count; scores share the existing adaptive line. */
@Composable
internal fun RankingMetrics(manga: Manga, heatLabel: String, onExpand: () -> Unit) {
    MangaMetricBadges(
        manga = manga,
        modifier = Modifier.fillMaxWidth(),
        foreground = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
        heatLabel = heatLabel,
        compact = true,
        plain = true,
        showMore = true,
        periodHeat = true,
        onExpand = onExpand,
    )
}
