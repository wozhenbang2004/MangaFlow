package dev.mangaflow.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

/** A small end target belongs to the image list; the discussion itself stays in its sheet. */
@Composable
internal fun ChapterEndFooter(
    pull: BottomPullLoadState,
    nextTitle: String?,
    unavailableMessage: String,
    background: Color,
    foreground: Color,
    onTap: () -> Unit,
    onNext: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val tap by rememberUpdatedState(onTap)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 144.dp).background(background)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onNext != null) Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            BottomPullLoadIndicator(pull, Modifier.size(26.dp), color = foreground.copy(alpha = .65f))
            Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(13.dp), tint = foreground.copy(alpha = .65f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                onNext == null -> unavailableMessage
                pull.loading -> "正在打开下一话"
                pull.progress >= 1f -> "松手进入下一话"
                else -> "上拉进入下一话"
            },
                color = foreground.copy(alpha = .72f), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!nextTitle.isNullOrBlank()) Text(nextTitle, color = foreground.copy(alpha = .48f),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    controlsVisible: Boolean,
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
    val bottom by animateDpAsState(
        if (controlsVisible) 112.dp else 0.dp,
        animationSpec = if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(),
        label = "chapter-comments-anchor",
    )
    LaunchedEffect(state) { state.ensureLoaded() }
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = bottom).height(420.dp)) {
        val fieldWidth = maxWidth
        val fieldHeight = maxHeight
        val singlePreview = largeText || fieldHeight < 280.dp
        // Leave the next-chapter action and the comments button unobstructed at the bottom.
        val launchBottom = if (singlePreview) 76.dp else 100.dp
        LaunchedEffect(candidates, motion, touchExploration, singlePreview, lifecycle) {
            bubbles = emptyList()
            if (motion && !touchExploration && candidates.isNotEmpty()) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        delay(MangaMotion.Quick.toLong())
                        while (true) {
                            candidates.forEach { row ->
                                // Keep the field alive while the reader stays at chapter end, but never
                                // stack the same comment on top of itself when only a few rows are available.
                                if (bubbles.none { it.key == row.key }) {
                                    bubbles = (bubbles + row).takeLast(if (singlePreview) 1 else 4)
                                }
                                delay(if (singlePreview) SinglePreviewLifetimeMillis + 250L else row.flight.intervalMillis)
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
                    minOf(fieldWidth, 180.dp), minOf(fieldWidth, 264.dp))
                FloatingCommentBubble(bubble, backdrop,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = launchBottom).width(width),
                    fieldWidth = fieldWidth, flightHeight = (fieldHeight - launchBottom).coerceAtLeast(0.dp),
                    singlePreview = singlePreview,
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
)

private data class BubbleFlight(
    val anchor: Float,
    val widthFraction: Float,
    val sway: Float,
    val phase: Float,
    val lifetimeMillis: Int,
    val intervalMillis: Long,
)

private const val MaximumBubbleLifetimeMillis = 4_700
private const val SinglePreviewLifetimeMillis = 6_800

/** Stable variations do not jump when source statuses or unrelated reader state changes. */
private fun bubbleFlight(key: String, index: Int, textLength: Int): BubbleFlight {
    val seed = key.hashCode().toLong() and 0x7fffffffL
    val anchor = when (index % 4) { 0 -> .06f; 1 -> .92f; 2 -> .31f; else -> .72f }
    return BubbleFlight(
        anchor = anchor,
        widthFraction = .54f + textLength.coerceAtMost(52) / 52f * .18f,
        sway = 5f + (seed % 5).toFloat(),
        phase = ((seed shr 4) % 100).toFloat() / 100f * (2 * PI).toFloat(),
        lifetimeMillis = 4_000 + ((seed shr 8) % 701).toInt(),
        intervalMillis = 1_250L + ((seed shr 12) % 401),
    )
}

@Composable
private fun FloatingCommentBubble(
    comment: FloatingComment,
    backdrop: HazeState,
    modifier: Modifier,
    fieldWidth: Dp,
    flightHeight: Dp,
    singlePreview: Boolean,
    onFinished: () -> Unit,
) {
    val travel = remember(comment.key) { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    val foreground = MaterialTheme.colorScheme.onSurface
    val flight = comment.flight
    LaunchedEffect(comment.key, singlePreview) {
        travel.snapTo(0f)
        travel.animateTo(1f, tween(if (singlePreview) SinglePreviewLifetimeMillis else flight.lifetimeMillis,
            easing = LinearEasing))
        finished()
    }
    LiquidGlassSurface(
        state = backdrop,
        modifier = modifier.clearAndSetSemantics {}.graphicsLayer {
            val progress = travel.value
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
        },
        cornerRadius = 18.dp,
    ) {
        // The preview has no pointer handler; scrolling the artwork passes straight through it.
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommentAvatar(comment.review, foreground, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(comment.review.author, color = foreground.copy(alpha = .90f),
                        fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CommentSourceBadge(comment.sourceId)
                }
                Text(comment.text, color = foreground.copy(alpha = .9f), fontSize = 12.sp,
                    lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
