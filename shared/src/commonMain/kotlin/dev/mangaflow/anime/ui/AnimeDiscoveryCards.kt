package dev.mangaflow.anime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.detailMetricScore
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.typography.MangaFontScene
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

/** Root-retained positions belong to the visible feed, never to an unrelated search request. */
internal class AnimeDiscoveryScrolls {
    private val grids = linkedMapOf<Any, LazyGridState>()
    var featuredKey: String? = null
    fun grid(key: String, query: AnimeDiscoveryQuery? = null): LazyGridState {
        val identity = if (query == null) key else key to query
        val state = grids.remove(identity) ?: LazyGridState()
        grids[identity] = state
        while (grids.size > 48) grids.remove(grids.keys.first())
        return state
    }
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
    val desktop = platformKind == PlatformKind.DESKTOP
    val gap = animeDiscoveryGap(columns)
    val side = MangaLayout.sidePadding(width)
    return when {
        columns == 1 -> 1
        desktop && columns == 0 -> MangaLayout.automaticDesktopColumns(width, fontScale, gap, side)
        else -> MangaLayout.columns(width, columns,
            (if (desktop) 156.dp else if (columns >= 4) 68.dp else 88.dp) * fontScale, gap, side)
    }
}

internal fun animeDiscoveryGap(columns: Int): Dp = when {
    platformKind == PlatformKind.DESKTOP -> 12.dp
    columns >= 4 -> 8.dp
    else -> 10.dp
}

@Composable
internal fun animeDiscoveryInset(width: Dp, preference: Int?, columns: Int): Dp {
    val side = MangaLayout.sidePadding(width)
    val preferred = animePreferredColumns(width, preference)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val contentWidth = when {
        columns == 1 -> MangaLayout.ListWidth
        platformKind == PlatformKind.DESKTOP && preferred > 1 ->
            240.dp * scale * columns + animeDiscoveryGap(columns) * (columns - 1) + side * 2
        else -> width
    }
    return side + ((width - contentWidth) / 2f).coerceAtLeast(0.dp)
}

/** Adapts the ordinary manga desktop pager's one-request-per-result-range guard. */
@Composable
internal fun AnimeBrowsePagination(key: Any, refreshKey: Any?, itemCount: Int, enabled: Boolean,
    nearEnd: () -> Boolean, onMore: () -> Unit) {
    val latestCount by rememberUpdatedState(itemCount)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestNearEnd by rememberUpdatedState(nearEnd)
    val latestMore by rememberUpdatedState(onMore)
    LaunchedEffect(key, refreshKey) {
        var requestedCount = -1
        snapshotFlow { (latestEnabled && latestCount > 0 && latestNearEnd()) to latestCount }
            .collect { (eligible, count) ->
                if (eligible && count > requestedCount) {
                    requestedCount = count
                    latestMore()
                }
            }
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

/** Artwork, title and the shared manga score band keep the same reading order in every density. */
@Composable
internal fun AnimeDiscoveryCard(item: AnimeDiscoveryItem, query: AnimeDiscoveryQuery,
    modifier: Modifier = Modifier, list: Boolean = false, onScores: ((AnimeDiscoveryItem) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    onOpen: () -> Unit) {
    val provider = item.discoveryProvider ?: query.provider
    val scores = observedAnimeRatingSnapshot(item.subject, animeDiscoverySourceScores(item, provider)).scores
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
        ratings = if (scores.isEmpty()) null else ({
            AnimeRatingBadges(item.key, scores, Modifier.fillMaxWidth(), onExpand = showScores)
        }),
        captionBadge = sourceBadge,
        description = listOfNotNull(item.title, caption.takeIf(String::isNotBlank)).joinToString("，"),
        modifier = modifier, list = list, onManage = onManage, onOpen = onOpen)
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
        ratings = null, captionBadge = sourceBadge,
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
            ratings = null, captionBadge = { AnimeSourceBadge(sources, compact = true, onClick = onSources) },
            description = title.title, modifier = modifier,
            list = list, onOpen = onOpen)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimeCompactCoverCard(key: String, title: String, cover: String, caption: String, sourceId: String,
    ratings: (@Composable () -> Unit)?, description: String, modifier: Modifier,
    list: Boolean, captionBadge: (@Composable () -> Unit)? = null, onManage: (() -> Unit)? = null, onOpen: () -> Unit) {
    val interaction = remember(key) { MutableInteractionSource() }
    val click = modifier.motionPress(interaction, pressedScale = .98f)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看$title", onLongClickLabel = "番剧操作".takeIf { onManage != null },
            onLongClick = onManage, onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = description }
    DesktopContextMenuArea(if (onManage != null) listOf(
        DesktopMenuAction("查看详情", onClick = onOpen), DesktopMenuAction("番剧操作", onClick = onManage)) else emptyList()) {
    if (list) {
        Row(click.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimePoster(cover, Modifier.width(52.dp).height(74.dp).clearAndSetSemantics { }, sourceId = sourceId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, Modifier.clearAndSetSemantics { }, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                ratings?.invoke()
                AnimeCompactCardCaption(caption, captionBadge)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(click) {
            AnimePoster(cover, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clearAndSetSemantics { }, sourceId = sourceId)
            Text(title, Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp).clearAndSetSemantics { },
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.Medium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            if (ratings != null) Box(Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp)) { ratings() }
            if (caption.isNotBlank() || captionBadge != null)
                Box(Modifier.fillMaxWidth().padding(top = 3.dp, start = 2.dp, end = 2.dp)) {
                    AnimeCompactCardCaption(caption, captionBadge)
                }
        }
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

internal fun animeDiscoveryScoreSourceId(provider: AnimeDiscoveryProvider): String = when (provider) {
    AnimeDiscoveryProvider.DOUBAN -> "douban"
    AnimeDiscoveryProvider.BANGUMI -> "bangumi"
    AnimeDiscoveryProvider.ANILIST -> "anilist"
}

internal fun animeDiscoveryScoreText(score: AnimeSourceScore): String =
    "${detailMetricScore(score.rating)}/${score.scale}"
