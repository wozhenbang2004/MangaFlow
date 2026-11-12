package dev.mangaflow.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.MangaReview
import dev.mangaflow.ui.CommentAvatar
import dev.mangaflow.ui.CommentSourceBadge
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** A compact next-chapter action floats over the existing last image. */
@Composable
internal fun ChapterEndFooter(
    pull: BottomPullLoadState,
    nextTitle: String?,
    unavailableMessage: String,
    foreground: Color,
    onNext: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(14.dp))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onNext != null) Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            BottomPullLoadIndicator(pull, Modifier.size(32.dp), color = foreground.copy(alpha = .65f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(when {
                onNext == null -> unavailableMessage
                pull.loading -> "正在打开下一话"
                pull.progress >= 1f -> "松手进入下一话"
                pull.horizontal -> "左滑进入下一话"
                else -> "上拉进入下一话"
            },
                color = foreground.copy(alpha = .72f), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!nextTitle.isNullOrBlank()) Text(nextTitle, color = foreground.copy(alpha = .48f),
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onNext != null) TextButton(onClick = onNext) {
            Text("下一话", color = foreground.copy(alpha = .8f), fontSize = 12.sp)
        } else if (onRetry != null) TextButton(onClick = onRetry) {
            Text("重试", color = foreground.copy(alpha = .8f), fontSize = 12.sp)
        }
    }
}

/**
 * Keep bounded, keyed presence while individual previews drift through a shallow bubble field.
 * These are real chapter rows. Leaving this composition cancels all decorative work.
 */
@Composable
internal fun ChapterEndComments(
    chapterId: String,
    state: ChapterDiscussionState,
    backdrop: HazeState,
    bottomClearance: Dp,
    touchExploration: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale > 1.3f
    val count = state.totalCount
    val candidates = remember(state.sources, chapterId) {
        mixedDiscussionRows(state.sources, chapterId).mapIndexedNotNull { index, row ->
            floatingPreviewText(row.review)?.let { text ->
                FloatingComment(row.key, row.source.source.id, row.review, text,
                    bubbleFlight(row.key, index, text.length))
            }
        }
    }
    var bubbles by remember(chapterId) { mutableStateOf<List<FloatingComment>>(emptyList()) }
    val latestCandidates by rememberUpdatedState(candidates)
    val bottom by animateDpAsState(
        bottomClearance,
        animationSpec = if (motion) tween(MangaMotion.Chrome, easing = MangaMotion.EnterEasing) else snap(),
        label = "chapter-comments-anchor",
    )
    LaunchedEffect(state) { state.ensureLoaded() }
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = bottom).heightIn(max = 380.dp).height(360.dp)) {
        val fieldWidth = maxWidth
        val fieldHeight = maxHeight
        val singlePreview = largeText || fieldHeight < 280.dp
        // Leave the next-chapter action and the comments button unobstructed at the bottom.
        val launchBottom = 56.dp
        LaunchedEffect(chapterId, candidates.isNotEmpty(), motion, touchExploration, singlePreview, lifecycle) {
            bubbles = emptyList()
            if ((!motion || touchExploration) && candidates.isNotEmpty()) {
                bubbles = candidates.take(1)
            } else if (candidates.isNotEmpty()) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        val initial = latestCandidates.take(if (singlePreview) 1 else 3)
                        bubbles = initial.mapIndexed { index, row ->
                            row.copy(initialProgress = if (singlePreview) 0f else (initial.lastIndex - index) * .27f)
                        }
                        var cursor = initial.size
                        while (true) {
                            delay(if (singlePreview) SinglePreviewLifetimeMillis + 100L else 1_200L)
                            val available = latestCandidates
                            if (available.isNotEmpty()) {
                                val row = available[cursor++ % available.size]
                                if (bubbles.none { it.key == row.key } && (singlePreview || bubbles.size < 3)) {
                                    bubbles = (bubbles + row).takeLast(if (singlePreview) 1 else 3)
                                }
                                if (!singlePreview) delay(row.flight.intervalMillis - 1_200L)
                            }
                        }
                    } finally {
                        bubbles = emptyList()
                    }
                }
            }
        }
        bubbles.forEach { bubble ->
            key(bubble.key) {
                val width = if (singlePreview) minOf(fieldWidth, 320.dp)
                else (fieldWidth * bubble.flight.widthFraction).coerceIn(
                    minOf(fieldWidth, 152.dp), minOf(fieldWidth, 248.dp))
                FloatingCommentBubble(bubble,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = launchBottom).width(width),
                    fieldWidth = fieldWidth, flightHeight = (fieldHeight - launchBottom).coerceAtLeast(0.dp),
                    singlePreview = singlePreview || !motion || touchExploration,
                    animate = motion && !touchExploration,
                    onFinished = { bubbles = bubbles.filterNot { it.key == bubble.key } })
            }
        }
        LiquidGlassSurface(
            state = backdrop,
            trackPointer = true,
            modifier = Modifier.align(Alignment.BottomStart).size(48.dp),
            cornerRadius = 24.dp,
        ) {
            MotionIconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Forum, count?.let { "评论，$it 条" } ?: "评论",
                    Modifier.size(22.dp), tint = colors.onSurface)
            }
        }
    }
}

private data class FloatingComment(
    val key: String,
    val sourceId: String,
    val review: MangaReview,
    val text: String,
    val flight: BubbleFlight,
    val initialProgress: Float = 0f,
)

private data class BubbleFlight(
    val anchor: Float,
    val widthFraction: Float,
    val sway: Float,
    val phase: Float,
    val lifetimeMillis: Int,
    val intervalMillis: Long,
)

private const val SinglePreviewLifetimeMillis = 4_600

/** Stable variations do not jump when source statuses or unrelated reader state changes. */
private fun bubbleFlight(key: String, index: Int, textLength: Int): BubbleFlight {
    val seed = key.hashCode().toLong() and 0x7fffffffL
    val anchor = when (index % 6) { 0 -> 0f; 1 -> 1f; 2 -> .08f; 3 -> .94f; 4 -> .22f; else -> .82f }
    return BubbleFlight(
        anchor = anchor,
        widthFraction = .46f + textLength.coerceAtMost(72) / 72f * .24f,
        sway = 5f + (seed % 5).toFloat(),
        phase = ((seed shr 4) % 100).toFloat() / 100f * (2 * PI).toFloat(),
        lifetimeMillis = 3_600 + ((seed shr 8) % 801).toInt(),
        intervalMillis = 1_200L + ((seed shr 12) % 401),
    )
}

@Composable
private fun FloatingCommentBubble(
    comment: FloatingComment,
    modifier: Modifier,
    fieldWidth: Dp,
    flightHeight: Dp,
    singlePreview: Boolean,
    animate: Boolean,
    onFinished: () -> Unit,
) {
    val travel = remember(comment.key) { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    val foreground = MaterialTheme.colorScheme.onSurface
    val flight = comment.flight
    LaunchedEffect(comment.key, singlePreview, animate) {
        if (!animate) { travel.snapTo(0f); return@LaunchedEffect }
        travel.snapTo(comment.initialProgress)
        val lifetime = if (singlePreview) SinglePreviewLifetimeMillis else flight.lifetimeMillis
        travel.animateTo(1f, tween((lifetime * (1f - comment.initialProgress)).toInt(),
            easing = LinearEasing))
        finished()
    }
    Box(
        modifier = modifier.clearAndSetSemantics {}.graphicsLayer {
            val progress = if (animate) travel.value else .10f
            val enter = (progress / .055f).coerceIn(0f, 1f)
            val leave = ((1f - progress) / .20f).coerceIn(0f, 1f)
            val room = (fieldWidth.toPx() - size.width).coerceAtLeast(0f)
            val rise = (flightHeight.toPx() - size.height - 12.dp.toPx()).coerceAtLeast(0f)
            val drift = (sin(flight.phase + progress * (PI * 1.6f).toFloat()) - sin(flight.phase)) * flight.sway.dp.toPx()
            alpha = minOf(enter, leave) * .96f
            translationX = if (singlePreview) room * .5f else (room * flight.anchor + drift).coerceIn(0f, room)
            // A gentle acceleration and independent drift avoid synchronized, elevator-like rows.
            translationY = -rise * (.84f * progress + .16f * progress * progress)
            scaleX = .94f + .06f * enter
            scaleY = scaleX
        }.clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))
            .border(.5.dp, foreground.copy(alpha = .06f), RoundedCornerShape(13.dp)),
    ) {
        // The preview has no pointer handler; scrolling the artwork passes straight through it.
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CommentAvatar(comment.review, foreground, size = 18.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(comment.review.author, color = foreground.copy(alpha = .90f),
                        fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CommentSourceBadge(comment.sourceId)
                }
                Text(comment.text, color = foreground.copy(alpha = .9f), fontSize = 12.sp,
                    lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Conservative eligibility applies only to unsolicited previews; the sheet retains original text. */
private fun floatingPreviewText(review: MangaReview): String? {
    if (review.spoiler) return null
    val text = review.body.ifBlank { review.summary }.replace(PreviewWhitespace, " ").trim()
    if (text.isEmpty() || PreviewLink.containsMatchIn(text) || PreviewSolicitation.containsMatchIn(text)) return null
    return text
}

private val PreviewWhitespace = Regex("""\s+""")
private val PreviewLink = Regex(
    """https?://|www\.|[a-z0-9][a-z0-9-]*\.(?:com|net|org|top|xyz|vip|app|link|cc|me|site|cn|club|live|tv|ink|fun|shop|online|video)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)
private val PreviewSolicitation = Regex(
    """加[微vＶ群]|[微薇]信|vx|群[号號]|掃碼|扫码|網址|网址|免费[看观]|免費[看觀]|搜妹|找哥|互相解决|互相解決|约炮|約炮|裸聊|成人视频|成人視頻|赌[博場]|賭[博場]""",
    RegexOption.IGNORE_CASE,
)

