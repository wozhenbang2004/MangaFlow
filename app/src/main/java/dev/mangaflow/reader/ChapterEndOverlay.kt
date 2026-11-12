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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.MangaReview
import dev.mangaflow.ui.CommentAvatar
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.BottomPullLoadIndicator
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.delay

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
            Text(if (onNext != null) "上拉进入下一话" else unavailableMessage,
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
 * Adapt the saved toast-stacked reference's bounded, keyed presence to an upward stream.
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
        mixedDiscussionRows(state.sources, chapterId).mapNotNull { row ->
            floatingPreviewText(row.review)?.let { text -> FloatingComment(row.key, row.review, text) }
        }
    }
    var bubbles by remember(chapterId) { mutableStateOf<List<FloatingComment>>(emptyList()) }
    val bottom by animateDpAsState(
        if (controlsVisible) 112.dp else 0.dp,
        animationSpec = if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(),
        label = "chapter-comments-anchor",
    )
    LaunchedEffect(state) { state.ensureLoaded() }
    LaunchedEffect(candidates, motion, touchExploration, largeText, lifecycle) {
        bubbles = emptyList()
        if (motion && !touchExploration && candidates.isNotEmpty()) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    delay(450)
                    // Larger text gets a single unobstructed preview instead of overlapping cards.
                    candidates.forEach { row ->
                        bubbles = (bubbles + row).takeLast(if (largeText) 1 else 2)
                        delay(if (largeText) 6_800L else 3_600L)
                    }
                    delay(6_500)
                } finally {
                    bubbles = emptyList()
                }
            }
        }
    }
    Box(modifier.fillMaxWidth().padding(bottom = bottom).height(260.dp)) {
        bubbles.forEach { bubble ->
            key(bubble.key) {
                FloatingCommentBubble(bubble, backdrop,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 56.dp)
                        .widthIn(max = 280.dp).fillMaxWidth(),
                    onFinished = { bubbles = bubbles.filterNot { it.key == bubble.key } })
            }
        }
        LiquidGlassSurface(
            state = backdrop,
            modifier = Modifier.align(Alignment.BottomStart).size(48.dp),
            cornerRadius = 24.dp,
        ) {
            MotionIconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Forum, count?.let { "本话评论，$it 条" } ?: "本话评论",
                    Modifier.size(22.dp), tint = colors.onSurface)
            }
        }
    }
}

private data class FloatingComment(val key: String, val review: MangaReview, val text: String)

@Composable
private fun FloatingCommentBubble(
    comment: FloatingComment,
    backdrop: HazeState,
    modifier: Modifier,
    onFinished: () -> Unit,
) {
    val travel = remember(comment.key) { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    val foreground = MaterialTheme.colorScheme.onSurface
    LaunchedEffect(comment.key) {
        travel.animateTo(1f, tween(6_500, easing = LinearEasing))
        finished()
    }
    LiquidGlassSurface(
        state = backdrop,
        modifier = modifier.clearAndSetSemantics {}.graphicsLayer {
            val progress = travel.value
            val enter = (progress / .08f).coerceIn(0f, 1f)
            val leave = ((1f - progress) / .23f).coerceIn(0f, 1f)
            alpha = minOf(enter, leave) * .96f
            translationY = -(8.dp.toPx() + 176.dp.toPx() * progress)
            scaleX = .97f + .03f * enter
            scaleY = scaleX
        },
        cornerRadius = 18.dp,
    ) {
        // The preview has no pointer handler; scrolling the artwork passes straight through it.
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CommentAvatar(comment.review, foreground)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(comment.review.author, color = foreground.copy(alpha = .58f),
                    fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(comment.text, color = foreground.copy(alpha = .9f), fontSize = 12.sp,
                    lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
