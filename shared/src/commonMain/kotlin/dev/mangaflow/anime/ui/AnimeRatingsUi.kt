package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeRatingSnapshot
import dev.mangaflow.anime.AnimeRatingsController
import dev.mangaflow.anime.AnimeScoreCheck
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.anime.discoveryProvider
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.LocalMetricSourceFan
import dev.mangaflow.ui.MetricSourceFan
import dev.mangaflow.ui.MetricSourceFanState
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.detailMetricScore
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal data class AnimeRatingRequest(
    val identity: String,
    val title: String,
    val subject: AnimeSubject?,
    val seeds: List<AnimeSourceScore>,
)

internal data class AnimeRatingUiState(
    val controller: AnimeRatingsController? = null,
    val active: Boolean = false,
    val obscured: Boolean = false,
    val open: (AnimeRatingRequest) -> Unit = {},
)

internal val LocalAnimeRatings = compositionLocalOf { AnimeRatingUiState() }

internal fun animeSubjectRatingSeeds(subject: AnimeSubject): List<AnimeSourceScore> {
    val provider = when {
        subject.canonicalDoubanId() != null -> AnimeDiscoveryProvider.DOUBAN
        subject.canonicalBangumiId() != null -> AnimeDiscoveryProvider.BANGUMI
        else -> return emptyList()
    }
    return subject.rating?.takeIf { it.isFinite() && it > 0 && it <= 10 }
        ?.let { listOf(AnimeSourceScore(provider, it, 10, subject.ratingCount)) }.orEmpty()
}

internal fun AnimeDiscoveryItem.ratingRequest(): AnimeRatingRequest = AnimeRatingRequest(
    key, title, subject, animeDiscoverySourceScores(this, discoveryProvider ?: AnimeDiscoveryProvider.DOUBAN),
)

/** Like manga, each composed score observes only its work and releases its shared request on exit. */
@Composable
internal fun observedAnimeRatingSnapshot(
    subject: AnimeSubject?,
    seeds: List<AnimeSourceScore> = subject?.let(::animeSubjectRatingSeeds).orEmpty(),
    requestDelay: Long = 180L,
    enabled: Boolean = true,
): AnimeRatingSnapshot {
    val host = LocalAnimeRatings.current
    val owner = host.controller
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(subject, seeds, owner, lifecycle, host.active, enabled, requestDelay) {
        if (subject == null || owner == null || !host.active || !enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(requestDelay)
            owner.retain(subject, seeds)
            try { awaitCancellation() } finally { owner.release(subject.id) }
        }
    }
    val updates = remember(owner, subject?.id) {
        owner?.values?.map { values -> subject?.id?.let { values[it] } ?: AnimeRatingSnapshot() }?.distinctUntilChanged()
            ?: flowOf(AnimeRatingSnapshot())
    }
    val initial = remember(owner, subject?.id) {
        subject?.id?.let { owner?.values?.value?.get(it) } ?: AnimeRatingSnapshot()
    }
    val emitted by updates.collectAsStateWithLifecycle(initialValue = initial)
    val snapshot = if (owner != null && subject != null) {
        owner.currentSnapshot(subject)?.let { current -> if (current == emitted) emitted else current } ?: AnimeRatingSnapshot()
    } else emitted
    return remember(snapshot, seeds) {
        snapshot.copy(scores = (snapshot.scores + seeds.filter { seed ->
            val check = snapshot.checks[seed.provider]
            check == null
        }).filter { it.rating.isFinite() && it.rating > 0 && it.scale > 0 && it.rating <= it.scale }
            .distinctBy { it.provider })
    }
}

@Composable
internal fun AnimeSubjectRatings(subject: AnimeSubject, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val host = LocalAnimeRatings.current
    val scores = observedAnimeRatingSnapshot(subject, enabled = enabled).scores
    if (scores.isNotEmpty()) AnimeRatingBadges(subject.id, scores, modifier.fillMaxWidth(),
        onExpand = ({ host.open(AnimeRatingRequest(subject.id, subject.title, subject, scores)) })
            .takeIf { host.active && enabled })
}

/** The same source fan and one ratings sheet serve every anime placement. */
@Composable
internal fun AnimeRatingHost(controller: AnimeController, backdrop: HazeState, active: Boolean,
    navigationKey: Any, content: @Composable () -> Unit) {
    val owner = controller.ratings
    val fan = remember(controller) { MetricSourceFanState() }
    var request by remember(controller) { mutableStateOf<AnimeRatingRequest?>(null) }
    LaunchedEffect(active, navigationKey) {
        request = null
        fan.clear()
    }
    val host = AnimeRatingUiState(owner, active, request != null || fan.request != null) {
        if (active) request = it
    }
    CompositionLocalProvider(LocalAnimeRatings provides host, LocalMetricSourceFan provides fan) {
        content()
        MetricSourceFan(fan, backdrop)
        request?.takeIf { active }?.let { selected ->
            AnimeRatingsSheet(selected, owner, backdrop, onDismiss = { request = null })
        }
    }
}

@Composable
private fun AnimeRatingsSheet(request: AnimeRatingRequest, controller: AnimeRatingsController,
    backdrop: HazeState, onDismiss: () -> Unit) {
    val snapshot = observedAnimeRatingSnapshot(request.subject, request.seeds, requestDelay = 0L)
    val scores = snapshot.scores
    val checks = snapshot.checks
    val providers = (scores.map { it.provider } + checks.keys).distinct()
        .sortedBy { if (it == AnimeDiscoveryProvider.DOUBAN) -1 else it.ordinal }
    val refreshing = checks.values.any { it.loading }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop, desktopWidth = 814.dp) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (platformKind == PlatformKind.DESKTOP && request.subject != null) {
                    AnimePoster(request.subject, Modifier.size(44.dp, 64.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("各站评分", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(request.title, Modifier.semantics { heading() }, style = animeWorkTitleStyle(large = true),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                request.subject?.let { subject ->
                    MotionIconButton(onClick = { controller.refresh(subject) }, enabled = !refreshing,
                        modifier = Modifier.size(44.dp), tooltip = "刷新各站评分") {
                        Icon(Icons.Outlined.Refresh, "刷新各站评分", Modifier.size(20.dp))
                    }
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭评分") {
                    Icon(Icons.Outlined.Close, "关闭评分", Modifier.size(20.dp))
                }
            }
            BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                val columns = if (platformKind == PlatformKind.DESKTOP && providers.size > 1)
                    (maxWidth / (216.dp * scale)).toInt().coerceIn(1, providers.size) else 1
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.chunked(columns).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            group.forEach { provider ->
                                Box(Modifier.weight(1f)) {
                                    AnimeRatingProviderRow(provider, scores.firstOrNull { it.provider == provider },
                                        checks[provider], refreshEnabled = !refreshing, comparison = columns > 1,
                                        onRefresh = request.subject?.let { subject -> { controller.refresh(subject, provider) } })
                                }
                            }
                            repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (providers.isEmpty()) AnimeMessage("暂未取得评分",
                        if (request.subject == null) "这条结果没有可读取的评分资料。" else null)
                }
                Box(Modifier.matchParentSize()) {
                    DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun AnimeRatingProviderRow(
    provider: AnimeDiscoveryProvider,
    score: AnimeSourceScore?,
    check: AnimeScoreCheck?,
    refreshEnabled: Boolean,
    comparison: Boolean,
    onRefresh: (() -> Unit)?,
) {
    val name = animeDiscoveryProviderLabel(provider)
    val colors = MaterialTheme.colorScheme
    val refreshing = check?.loading == true
    val failure = check?.error
    val status = when {
        refreshing -> if (score != null) "正在更新，仍显示上次评分" else "正在读取评分"
        failure != null -> if (score == null) failure else "仍显示上次评分 · $failure"
        check?.message != null -> check.message
        score == null -> "暂无评分"
        else -> null
    }
    val refreshAction: @Composable () -> Unit = {
        when {
            refreshing -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                MotionBusyIndicator(Modifier.size(16.dp))
            }
            onRefresh != null -> {
                val action = if (failure != null) "重试 $name 评分" else "刷新 $name 评分"
                MotionIconButton(onClick = onRefresh, enabled = refreshEnabled,
                    modifier = Modifier.size(44.dp), tooltip = action) {
                    Icon(Icons.Outlined.Refresh, action, Modifier.size(18.dp),
                        tint = if (failure != null && refreshEnabled) colors.error
                            else colors.onSurfaceVariant.copy(alpha = if (refreshEnabled) 1f else .45f))
                }
            }
            else -> Unit
        }
    }
    if (comparison) {
        Column(Modifier.fillMaxWidth().heightIn(min = 156.dp)
            .background(colors.onSurface.copy(alpha = .04f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ReviewSourceMark(animeDiscoveryScoreSourceId(provider), size = 18.dp)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                refreshAction()
            }
            if (score != null) {
                AnimeRatingValue(score, large = true)
                AnimeProgressTrack((score.rating / score.scale).toFloat().coerceIn(0f, 1f))
                score.ratingCount?.let { AnimeQuietLabel("$it 人评分") }
            }
            if (status != null) Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = if (failure != null && !refreshing) colors.error else colors.onSurfaceVariant)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()
        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = if (status == null) 6.dp else 10.dp)) {
        val separateScore = maxWidth < 240.dp * LocalDensity.current.fontScale
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) { ReviewSourceMark(animeDiscoveryScoreSourceId(provider), size = 20.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (score != null) {
                        if (separateScore) AnimeRatingValue(score)
                        score.ratingCount?.let { count ->
                            Text("$count 人评分", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (score != null && !separateScore) AnimeRatingValue(score)
                refreshAction()
            }
            if (status != null) Text(status, Modifier.padding(start = 44.dp, end = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (failure != null && !refreshing) colors.error else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeRatingValue(score: AnimeSourceScore, large: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "评分 ${animeDiscoveryScoreText(score)}"
        }) {
        Text(detailMetricScore(score.rating), Modifier.alignByBaseline(),
            style = if (large) mangaMetricValueStyle(32.sp, 38.sp) else mangaMetricValueStyle(23.sp, 29.sp), maxLines = 1, softWrap = false)
        Text("/ ${score.scale}", Modifier.alignByBaseline(), style = mangaMetricValueStyle(12.sp, 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}
