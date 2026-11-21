package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
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
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.anime.discoveryProvider
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
    val colors = MaterialTheme.colorScheme
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            AnimeSectionTitle("各站评分", request.title, action = {
                if (request.subject != null) MotionIconButton(
                    onClick = { controller.refresh(request.subject) },
                    enabled = checks.values.none { it.loading }, modifier = Modifier.size(44.dp), tooltip = "刷新评分") {
                    Icon(Icons.Outlined.Refresh, "刷新评分", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭评分") {
                    Icon(Icons.Outlined.Close, "关闭评分", Modifier.size(18.dp))
                }
            })
            (scores.map { it.provider } + checks.keys).distinct()
                .sortedBy { if (it == AnimeDiscoveryProvider.DOUBAN) -1 else it.ordinal }.forEach { provider ->
                val score = scores.firstOrNull { it.provider == provider }
                val check = checks[provider]
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReviewSourceMark(animeDiscoveryScoreSourceId(provider), size = 20.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(animeDiscoveryProviderLabel(provider), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (score != null) Text(score.ratingCount?.let { "$it 人评分" } ?: "人数未提供",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        if (score != null) Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "${animeDiscoveryScoreText(score)}，" +
                                    (score.ratingCount?.let { "$it 人评分" } ?: "评分人数未提供")
                            }) {
                            Text(detailMetricScore(score.rating), Modifier.alignByBaseline(),
                                style = mangaMetricValueStyle(20.sp, 26.sp), maxLines = 1, softWrap = false)
                            Text("/ ${score.scale}", Modifier.alignByBaseline(),
                                style = mangaMetricValueStyle(12.sp, 16.sp), color = colors.onSurfaceVariant,
                                maxLines = 1, softWrap = false)
                        }
                        if (check?.loading == true) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            MotionBusyIndicator(Modifier.size(16.dp))
                        } else if (request.subject != null) MotionIconButton(
                            onClick = { controller.refresh(request.subject, provider) }, modifier = Modifier.size(44.dp),
                            tooltip = "刷新 ${animeDiscoveryProviderLabel(provider)} 评分") {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        }
                    }
                    val status = when {
                        check?.loading == true -> "正在更新"
                        check?.error != null -> check.error
                        check?.message != null -> check.message
                        score == null -> "暂无评分"
                        else -> null
                    }
                    if (status != null) AnimeQuietLabel(status, Modifier.padding(start = 30.dp, bottom = 4.dp))
                }
            }
            if (scores.isEmpty() && checks.isEmpty()) AnimeQuietLabel("暂未取得可显示的评分", Modifier.padding(vertical = 8.dp))
            AnimeQuietLabel("各站保留原始分制，不合并总分；人数仅在来源提供时显示。", Modifier.padding(top = 8.dp))
        }
    }
}
