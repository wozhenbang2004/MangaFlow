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
import dev.mangaflow.anime.sources.ANILIST_PROVIDER
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.anime.sources.DOUBAN_PROVIDER
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.detailMetricScore
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.roundToInt

/** Root-retained positions belong to the visible feed, never to an unrelated search request. */
internal class AnimeDiscoveryScrolls {
    val grids = mutableMapOf<String, LazyGridState>()
    val queries = mutableMapOf<String, AnimeDiscoveryQuery>()
    var featuredKey: String? = null
    var placedHomeFeature = false
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
    AnimeDiscoveryProvider.DOUBAN -> "豆瓣"
    AnimeDiscoveryProvider.BANGUMI -> "Bangumi"
    AnimeDiscoveryProvider.ANILIST -> "AniList"
}

internal fun animeDiscoverySourceId(provider: AnimeDiscoveryProvider): String = when (provider) {
    AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
    AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
    AnimeDiscoveryProvider.ANILIST -> ANILIST_PROVIDER
}

internal fun animeDiscoveryOrderLabel(order: AnimeDiscoveryOrder): String = when (order) {
    AnimeDiscoveryOrder.RANK -> "评分榜"
    AnimeDiscoveryOrder.POPULARITY -> "人气"
    AnimeDiscoveryOrder.SCORE -> "评分"
    AnimeDiscoveryOrder.TRENDING -> "趋势"
    AnimeDiscoveryOrder.NEWEST -> "最新"
}

internal fun animeDiscoveryCaption(query: AnimeDiscoveryQuery): String = when {
    query.calendar -> "Bangumi 放送日历"
    query.order == AnimeDiscoveryOrder.RANK -> "Bangumi 评分榜"
    query.order == AnimeDiscoveryOrder.TRENDING -> "AniList 趋势"
    animeDiscoveryPeerQuery(query) != null -> "各站" + animeDiscoveryOrderLabel(query.order)
    else -> animeDiscoveryOrderLabel(query.order) + " · " + animeDiscoveryProviderLabel(query.provider)
}

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
    query.order == AnimeDiscoveryOrder.POPULARITY -> item.popularity?.let {
        val source = item.discoveryProvider?.takeIf { query.combined }?.let(::animeDiscoveryProviderLabel)
        listOfNotNull(source, "人气 " + animeCompactCount(it)).joinToString(" ")
    }
    query.order == AnimeDiscoveryOrder.TRENDING -> item.trending?.let { "趋势 " + animeCompactCount(it) }
    query.order == AnimeDiscoveryOrder.RANK -> item.rank?.let { "评分榜 #$it" }
    else -> null
}

/** Cover, press and typography adapt the existing anime tile; each card has one metadata line. */
@Composable
internal fun AnimeDiscoveryCard(item: AnimeDiscoveryItem, query: AnimeDiscoveryQuery,
    modifier: Modifier = Modifier, list: Boolean = false, onScores: ((AnimeDiscoveryItem) -> Unit)? = null,
    onOpen: () -> Unit) {
    val provider = item.discoveryProvider ?: query.provider
    val scores = animeDiscoverySourceScores(item, provider)
    val caption = listOfNotNull(animeDiscoveryMetric(item, query), item.year?.toString(),
        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).take(2).joinToString(" · ")
    val rated = item.copy(sourceScores = scores)
    val showScores = onScores?.let { open -> { open(rated) } }
    val sourceBadge: (@Composable () -> Unit)? = if (provider != AnimeDiscoveryProvider.DOUBAN && scores.isEmpty() && showScores != null) {
        {
            val sources = animeDiscoverySourceProviders(rated, provider).map {
                animeDiscoverySourceId(it) to animeDiscoveryProviderLabel(it)
            }
            AnimeSourceBadge(sources, compact = true, onClick = showScores)
        }
    } else null
    AnimeCompactCoverCard(item.key, item.title, item.coverUrl, caption, animeDiscoverySourceId(item.coverProvider ?: provider),
        badge = if (scores.isEmpty()) null else ({ AnimeDiscoveryScores(scores, list, showScores) }),
        captionBadge = sourceBadge,
        description = listOfNotNull(item.title, caption.takeIf(String::isNotBlank)).joinToString("，"),
        modifier = modifier, list = list, onOpen = onOpen)
}

internal fun animeDiscoverySourceScores(item: AnimeDiscoveryItem, provider: AnimeDiscoveryProvider): List<AnimeSourceScore> =
    item.sourceScores.ifEmpty {
        item.rating?.let { listOf(AnimeSourceScore(provider, it, item.ratingScale)) }.orEmpty()
    }.filter { it.rating.isFinite() && it.rating > 0 && it.rating <= it.scale }.distinctBy { it.provider }

internal fun animeDiscoverySourceProviders(item: AnimeDiscoveryItem, fallback: AnimeDiscoveryProvider? = null): List<AnimeDiscoveryProvider> =
    (listOfNotNull(item.discoveryProvider ?: fallback, item.coverProvider,
        AnimeDiscoveryProvider.ANILIST.takeIf { item.reference?.sourceId == ANILIST_PROVIDER },
        AnimeDiscoveryProvider.DOUBAN.takeIf { item.reference?.sourceId in setOf(DOUBAN_PROVIDER, "anime-lanerc-douban") }) +
        item.sourceScores.map { it.provider }).distinct()

@Composable
internal fun AnimeSourceDiscoveryCard(title: AnimeSourceTitle, modifier: Modifier = Modifier,
    list: Boolean = false, sourceLabel: String? = null,
    cardKey: String = title.sourceId + ":" + title.id, onSources: (() -> Unit)? = null, onOpen: () -> Unit) {
    val metadata = listOfNotNull(title.year?.toString(), title.ratingText.takeIf(String::isNotBlank),
        title.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).take(2).joinToString(" · ")
    val update = title.updateText.takeIf(String::isNotBlank)
    val extra = metadata.takeIf { it.isNotBlank() && (list || update == null) }
    val caption = listOfNotNull(sourceLabel?.takeIf { onSources == null }, update, extra).joinToString(" · ")
    val sourceBadge: (@Composable () -> Unit)? = onSources?.let { open ->
        { AnimeSourceBadge(listOf(title.sourceId to (sourceLabel ?: animeSourceName(title.sourceId))),
            compact = true, onClick = open) }
    }
    AnimeCompactCoverCard(cardKey, title.title, title.coverUrl, caption, title.sourceId,
        badge = null, captionBadge = sourceBadge,
        description = listOfNotNull(title.title, sourceLabel, update?.let { "来源标注：$it" },
            metadata.takeIf(String::isNotBlank)).joinToString("，"),
        modifier = modifier, list = list, onOpen = onOpen)
}

@Composable
internal fun AnimeSourceGroupDiscoveryCard(group: AnimeSearchGroup, sources: List<Pair<String, String>>,
    modifier: Modifier = Modifier, list: Boolean = false, onSources: () -> Unit, onOpen: () -> Unit) {
    val title = group.sources.firstOrNull() ?: return
    if (group.sources.all { it.sourceId == title.sourceId }) {
        AnimeSourceDiscoveryCard(title, modifier = modifier, list = list, sourceLabel = sources.firstOrNull()?.second,
            cardKey = group.key, onSources = onSources, onOpen = onOpen)
    } else {
        val artwork = group.sources.firstOrNull { it.coverUrl.isNotBlank() } ?: title
        AnimeCompactCoverCard(group.key, title.title, artwork.coverUrl, "", artwork.sourceId,
            badge = null, captionBadge = { AnimeSourceBadge(sources, compact = true, onClick = onSources) },
            description = title.title, modifier = modifier,
            list = list, onOpen = onOpen)
    }
}

@Composable
private fun AnimeCompactCoverCard(key: String, title: String, cover: String, caption: String, sourceId: String,
    badge: (@Composable () -> Unit)?, description: String, modifier: Modifier,
    list: Boolean, captionBadge: (@Composable () -> Unit)? = null, onOpen: () -> Unit) {
    val interaction = remember(key) { MutableInteractionSource() }
    val click = modifier.motionPress(interaction, pressedScale = .98f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看$title", onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = description }
    if (list) {
        Row(click.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimePoster(cover, Modifier.width(52.dp).height(74.dp).clearAndSetSemantics { }, sourceId = sourceId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                AnimeCompactCardCaption(caption, captionBadge)
                badge?.invoke()
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(click, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(.7f)) {
                AnimePoster(cover, Modifier.fillMaxSize().clearAndSetSemantics { }, sourceId = sourceId)
                if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { badge() }
            }
            Text(title, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis)
            AnimeCompactCardCaption(caption, captionBadge)
        }
    }
}

@Composable
private fun AnimeCompactCardCaption(caption: String, badge: (@Composable () -> Unit)?) {
    if (caption.isBlank() && badge == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        badge?.invoke()
        if (caption.isNotBlank()) Text(caption, Modifier.weight(1f, fill = false).clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnimeDiscoveryScores(scores: List<AnimeSourceScore>, inline: Boolean, onOpen: (() -> Unit)?) {
    val description = scores.joinToString("，") { score ->
        "${animeDiscoveryProviderLabel(score.provider)} ${detailMetricScore(score.rating)} 分，满分 ${score.scale}"
    }
    val interaction = remember { MutableInteractionSource() }
    val button = if (onOpen == null) Modifier else Modifier
        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看来源评分", onClick = onOpen)
        .motionPress(interaction, pressedScale = .98f)
    BoxWithConstraints(button.semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = if (inline) Alignment.CenterStart else Alignment.TopEnd) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val count = if (maxWidth >= 108.dp * fontScale) scores.size else 1
        Row(Modifier.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .95f))
            .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            scores.take(count).forEach { score ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReviewSourceMark(animeDiscoveryScoreSourceId(score.provider), size = 12.dp)
                    Text(animeDiscoveryScoreText(score), Modifier.clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = mangaMetricValueStyle(12.sp, 16.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count < scores.size) Text("+${scores.size - count}", Modifier.clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

internal fun animeDiscoveryScoreSourceId(provider: AnimeDiscoveryProvider): String = when (provider) {
    AnimeDiscoveryProvider.DOUBAN -> "douban"
    AnimeDiscoveryProvider.BANGUMI -> "bangumi"
    AnimeDiscoveryProvider.ANILIST -> "anilist"
}

internal fun animeDiscoveryScoreText(score: AnimeSourceScore): String =
    "${detailMetricScore(score.rating)}/${score.scale}"
