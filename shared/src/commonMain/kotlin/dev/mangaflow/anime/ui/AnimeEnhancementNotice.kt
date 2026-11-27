package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.player.ANIME_ENHANCED_FRAME_FRESH_MS
import dev.mangaflow.anime.player.AnimeEnhancementProtection
import dev.mangaflow.anime.player.AnimeEnhancementProtectionCause
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

internal fun AnimePlayerState.currentEnhancementProtection(): AnimeEnhancementProtection? =
    enhancementProtection?.takeIf { assetKey != null && !preEnhanced && hasVideoTracks != false &&
        (phase == AnimePlayerPhase.OPENING || phase == AnimePlayerPhase.READY) }

internal val AnimeEnhancementProtection.reasonLabel: String get() = when (cause) {
    AnimeEnhancementProtectionCause.THERMAL -> "温度保护"
    AnimeEnhancementProtectionCause.MEMORY -> "内存紧张"
    AnimeEnhancementProtectionCause.GPU_BUDGET -> "GPU 帧耗时偏高"
}

private fun effectLabel(picture: Boolean, motion: Boolean) = when {
    picture && motion -> "实时画质增强与补帧"
    picture -> "实时画质增强"
    else -> "补帧"
}

internal val AnimeEnhancementProtection.actionLabel: String get() =
    if (cause == AnimeEnhancementProtectionCause.GPU_BUDGET) "已切至轻量增强"
    else effectLabel(picture, motion) + "已暂缓"

internal val AnimeEnhancementProtection.symbol: ImageVector get() = when (cause) {
    AnimeEnhancementProtectionCause.THERMAL -> Icons.Outlined.Thermostat
    AnimeEnhancementProtectionCause.MEMORY -> Icons.Outlined.Memory
    AnimeEnhancementProtectionCause.GPU_BUDGET -> Icons.Outlined.Speed
}

internal data class AnimeEnhancementNotice(val text: String, val icon: ImageVector)

/** The desired mode may return before the renderer emits anything. Wait for new output on this timeline. */
private class EnhancementResumption(val held: AnimeEnhancementProtection, playback: AnimePlayerState) {
    val timeline = playback.timelineGeneration
    private val releasedAt = monotonicTimeMillis()
    private var motionFloor = playback.realtimeMotion?.outputInterpolatedFrames ?: 0L
    private var pictureSeen = false
    private var motionSeen = false
    val complete get() = (!held.picture || pictureSeen) && (!held.motion || motionSeen)

    fun observe(playback: AnimePlayerState): AnimeEnhancementNotice? {
        if (!playback.isPlaying || playback.playbackSuppressed || playback.firstFrameRendered == false ||
            playback.hasVideoTracks == false || playback.preEnhanced) return null
        val effect = playback.videoEnhancement
        val sample = effect.observation
        val now = monotonicTimeMillis()
        val submitted = sample?.lastSubmittedAtMillis
        fun fresh(at: Long?) = at != null && at >= releasedAt && now - at in 0L until ANIME_ENHANCED_FRAME_FRESH_MS
        val freshSubmission = sample?.recentlySubmitting == true && sample.submittedFrames > 0L && fresh(submitted)
        val native = playback.nativeVideoStatistics
        val filtered = native?.confirmedSpatialOutputSize
        val freshFilter = filtered != null && fresh(native?.sampledAtMillis) &&
            filtered.width == effect.outputWidth && filtered.height == effect.outputHeight
        val resumedPicture = held.picture && !pictureSeen && effect.phase == AnimeVideoEnhancementPhase.ACTIVE &&
            !effect.renderedOriginalFallback && effect.mode != AnimeVideoEnhancementMode.OFF &&
            (held.cause != AnimeEnhancementProtectionCause.GPU_BUDGET || effect.mode != AnimeVideoEnhancementMode.FAST) &&
            (freshSubmission || freshFilter)
        val motion = playback.realtimeMotion
        val output = motion?.outputInterpolatedFrames
        if (output != null && output < motionFloor) motionFloor = output
        val resumedMotion = held.motion && !motionSeen && motion?.phase == AnimeVideoEnhancementPhase.ACTIVE &&
            output != null && output > motionFloor
        if (!resumedPicture && !resumedMotion) return null
        pictureSeen = pictureSeen || resumedPicture
        motionSeen = motionSeen || resumedMotion
        val label = if (held.cause == AnimeEnhancementProtectionCause.GPU_BUDGET) "画质增强已恢复原档"
            else effectLabel(pictureSeen, motionSeen) + "已恢复"
        return AnimeEnhancementNotice(label, Icons.Outlined.AutoAwesome)
    }
}

@Composable
internal fun rememberAnimeEnhancementNotice(observations: State<AnimePlayerState>, session: Long,
    asset: String?, episode: String?, available: Boolean): State<AnimeEnhancementNotice?> {
    val intent = remember(observations) { derivedStateOf { observations.value.enhancementIntent } }.value
    val notice = remember(observations, session, asset, episode, intent) { mutableStateOf<AnimeEnhancementNotice?>(null) }
    val visible by rememberUpdatedState(available)
    LaunchedEffect(notice) {
        var lastAnnounced: AnimeEnhancementProtection? = null
        var watched = false
        var waiting: EnhancementResumption? = null
        snapshotFlow {
            if (visible) observations.value.takeIf { asset != null && it.assetKey == asset && it.episodeId == episode &&
                it.sessionGeneration == session && it.enhancementIntent == intent } else null
        }.collect { current ->
            if (current == null) {
                watched = false
                waiting = null
                notice.value = null
                return@collect
            }
            val protection = current.currentEnhancementProtection()
            if (protection != null) {
                if (lastAnnounced != protection)
                    notice.value = AnimeEnhancementNotice(protection.reasonLabel + " · " + protection.actionLabel, protection.symbol)
                lastAnnounced = protection
                waiting = null
            } else {
                lastAnnounced?.let { held ->
                    notice.value = null
                    if (watched) waiting = EnhancementResumption(held, current)
                    lastAnnounced = null
                }
                waiting?.let { previous ->
                    val pending = if (previous.timeline == current.timelineGeneration) previous
                        else EnhancementResumption(previous.held, current)
                    pending.observe(current)?.let { notice.value = it }
                    waiting = pending.takeUnless { it.complete }
                }
            }
            watched = true
        }
    }
    val message = notice.value
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(3500L,
        containsIcons = true, containsText = true, containsControls = false) ?: 3500L
    LaunchedEffect(notice, message, timeout) {
        if (message != null && timeout != Long.MAX_VALUE) {
            delay(timeout)
            if (notice.value === message) notice.value = null
        }
    }
    return notice
}

@Composable
internal fun AnimeEnhancementNoticeChip(message: AnimeEnhancementNotice?, modifier: Modifier = Modifier) {
    val motion = LocalMotionEnabled.current
    var last by remember { mutableStateOf(message) }
    SideEffect { if (message != null) last = message }
    val shown = message ?: last
    AnimatedVisibility(visible = message != null, modifier = modifier,
        enter = if (motion) fadeIn(tween(MangaMotion.Quick)) +
            slideInVertically(tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing)) { -it / 5 } else EnterTransition.None,
        exit = if (motion) fadeOut(tween(MangaMotion.ListExit)) +
            slideOutVertically(tween(MangaMotion.ListExit, easing = MangaMotion.ExitEasing)) { -it / 8 } else ExitTransition.None) {
        val content = shown ?: return@AnimatedVisibility
        AnimePlaybackSurface(null, cornerRadius = 16.dp) {
            Row(Modifier.clearAndSetSemantics { if (message != null) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = content.text
            } }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimeGlassIcon(content.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                AnimeGlassLabel(content.text, style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
