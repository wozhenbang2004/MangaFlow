package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.anime.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.roundToInt

/** Root-retained positions belong to the visible feed, never to an unrelated search request. */
internal class AnimeDiscoveryScrolls(homeSourceState: MutableState<String?>) {
    val grids = mutableMapOf<String, LazyGridState>()
    val queries = mutableMapOf<String, AnimeDiscoveryQuery>()
    var featuredKey: String? = null
    var homeSourceId by homeSourceState
    var expandedHomeSections by mutableStateOf(emptySet<String>())
    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }
}

internal fun animePreferredColumns(width: Dp, preferred: Int?): Int = when {
    preferred != null && preferred > 0 -> preferred
    platformKind == PlatformKind.DESKTOP -> 0
    width >= 600.dp -> 4
    else -> 3
}

@Composable
internal fun animeDiscoveryColumns(width: Dp, preferred: Int?): Int {
    val columns = animePreferredColumns(width, preferred)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return when {
        columns == 1 -> 1
        platformKind == PlatformKind.DESKTOP && columns == 0 ->
            MangaLayout.automaticDesktopColumns(width, fontScale, 10.dp, 12.dp)
        else -> MangaLayout.columns(width, columns,
            (if (columns >= 4) 68.dp else 80.dp) * fontScale, 8.dp, 12.dp)
    }
}

internal fun animeDiscoveryProviderLabel(provider: AnimeDiscoveryProvider): String = when (provider) {
    AnimeDiscoveryProvider.BANGUMI -> "Bangumi"
    AnimeDiscoveryProvider.ANILIST -> "AniList"
}

internal fun animeDiscoveryOrderLabel(order: AnimeDiscoveryOrder): String = when (order) {
    AnimeDiscoveryOrder.RANK -> "评分榜"
    AnimeDiscoveryOrder.POPULARITY -> "人气"
    AnimeDiscoveryOrder.SCORE -> "评分"
    AnimeDiscoveryOrder.TRENDING -> "趋势"
}

internal fun animeDiscoveryCaption(query: AnimeDiscoveryQuery): String =
    animeDiscoveryProviderLabel(query.provider) + " · " +
        if (query.calendar) "放送日历" else animeDiscoveryOrderLabel(query.order)

internal fun animeSeasonLabel(season: AnimeSeason): String = when (season) {
    AnimeSeason.WINTER -> "冬季"
    AnimeSeason.SPRING -> "春季"
    AnimeSeason.SUMMER -> "夏季"
    AnimeSeason.FALL -> "秋季"
}

internal fun animeCompactCount(value: Long): String = when {
    value >= 100_000_000 -> animeDecimal(value / 100_000_000.0) + "亿"
    value >= 10_000 -> animeDecimal(value / 10_000.0) + "万"
    else -> value.toString()
}

private fun animeDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

internal fun animeDiscoveryMetric(item: AnimeDiscoveryItem, query: AnimeDiscoveryQuery): String? = when {
    query.calendar -> item.airWeekday?.takeIf { it in 1..7 }
        ?.let { "周" + listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] + "放送" }
    query.order == AnimeDiscoveryOrder.POPULARITY -> item.popularity?.let { "人气 " + animeCompactCount(it) }
    query.order == AnimeDiscoveryOrder.TRENDING -> item.trending?.let { "趋势 " + animeCompactCount(it) }
    query.order == AnimeDiscoveryOrder.RANK -> item.rank?.let { "评分榜 #$it" }
    else -> null
}

/** Cover, press and typography adapt the existing anime tile; each card has one metadata line. */
@Composable
internal fun AnimeDiscoveryCard(item: AnimeDiscoveryItem, query: AnimeDiscoveryQuery,
    modifier: Modifier = Modifier, list: Boolean = false, onOpen: () -> Unit) {
    val rating = item.rating?.takeIf { it > 0 && it.isFinite() }
        ?.let { animeDecimal(it) + if (item.ratingScale == 10) "" else "/${item.ratingScale}" }
    val caption = listOfNotNull(animeDiscoveryMetric(item, query), item.year?.toString(),
        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).take(2).joinToString(" · ")
    AnimeCompactCoverCard(item.key, item.title, item.coverUrl, caption,
        badge = rating, badgeDescription = rating?.let { "评分 $it，满分 ${item.ratingScale}" },
        description = listOfNotNull(item.title, animeDiscoveryProviderLabel(query.provider),
            rating?.let { "评分 $it，满分 ${item.ratingScale}" }, caption.takeIf(String::isNotBlank)).joinToString("，"),
        modifier = modifier, list = list, onOpen = onOpen)
}

@Composable
internal fun AnimeSourceDiscoveryCard(title: AnimeSourceTitle, modifier: Modifier = Modifier,
    list: Boolean = false, onOpen: () -> Unit) {
    val metadata = listOfNotNull(title.year?.toString(), title.ratingText.takeIf(String::isNotBlank),
        title.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).take(2).joinToString(" · ")
    val update = title.updateText.takeIf(String::isNotBlank)
    val caption = if (list) listOfNotNull(update, metadata.takeIf(String::isNotBlank)).joinToString(" · ")
        else update ?: metadata
    AnimeCompactCoverCard(title.sourceId + ":" + title.id, title.title, title.coverUrl, caption,
        badge = null, badgeDescription = null,
        description = listOfNotNull(title.title, update?.let { "来源标注：$it" },
            metadata.takeIf(String::isNotBlank)).joinToString("，"),
        modifier = modifier, list = list, onOpen = onOpen)
}

@Composable
private fun AnimeCompactCoverCard(key: String, title: String, cover: String, caption: String,
    badge: String?, badgeDescription: String?, description: String, modifier: Modifier,
    list: Boolean, onOpen: () -> Unit) {
    val interaction = remember(key) { MutableInteractionSource() }
    val click = modifier.motionPress(interaction, pressedScale = .98f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看$title", onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = description }
    if (list) {
        Row(click.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimePoster(cover, Modifier.width(52.dp).height(74.dp))
            Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!badge.isNullOrBlank()) AnimeDiscoveryBadge(badge, badgeDescription)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(click, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(.7f).clearAndSetSemantics { }) {
                AnimePoster(cover, Modifier.fillMaxSize())
                if (!badge.isNullOrBlank()) Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                    AnimeDiscoveryBadge(badge, badgeDescription)
                }
            }
            Text(title, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(caption, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AnimeDiscoveryBadge(text: String, description: String?) {
    Row(Modifier.clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))
        .padding(horizontal = 5.dp, vertical = 3.dp)
        .semantics { contentDescription = description ?: text },
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Star, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
