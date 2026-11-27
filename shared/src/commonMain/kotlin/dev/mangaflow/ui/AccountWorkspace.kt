package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

/** Both slots keep their composition (including an active credential form) across a window resize. */
@Composable
internal fun AccountWorkspace(
    modifier: Modifier = Modifier,
    mobileWidth: Dp,
    top: Dp,
    bottom: Dp,
    sideInset: Dp,
    allowSplit: Boolean = true,
    detailKey: String? = null,
    overview: @Composable (wide: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val wide = desktop && allowSplit && constraints.hasBoundedHeight &&
            maxWidth >= 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val width = if (wide) 936.dp else if (desktop) 540.dp else mobileWidth
        val scroll = key(if (desktop) detailKey else null) { rememberScrollState() }
        val overviewScroll = rememberScrollState()
        val detailScroll = key(detailKey) { rememberScrollState() }
        Box(Modifier.widthIn(max = width).fillMaxSize()) {
            val layoutBounds = if (wide) Modifier.fillMaxSize() else Modifier.fillMaxWidth().verticalScroll(scroll)
            Layout(modifier = layoutBounds.padding(
                start = sideInset, end = sideInset, top = if (wide) top + 12.dp else top, bottom = bottom),
                content = {
                    AccountLane(wide, overviewScroll) {
                        Box(if (wide) Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(20.dp)).padding(18.dp) else Modifier) { overview(wide) }
                    }
                    AccountLane(wide, detailScroll, content)
                }) { slots, bounds ->
                val gap = if (wide) 32.dp.roundToPx() else 0
                val overviewWidth = if (wide) ((bounds.maxWidth - gap) / 3) else bounds.maxWidth
                val detailWidth = if (wide) bounds.maxWidth - gap - overviewWidth else bounds.maxWidth
                val first = slots[0].measure(bounds.copy(minWidth = overviewWidth, maxWidth = overviewWidth, minHeight = 0))
                val second = slots[1].measure(bounds.copy(minWidth = detailWidth, maxWidth = detailWidth, minHeight = 0))
                layout(bounds.maxWidth, if (wide) bounds.maxHeight else first.height + second.height) {
                    first.placeRelative(0, 0)
                    second.placeRelative(if (wide) overviewWidth + gap else 0, if (wide) 0 else first.height)
                }
            }
            if (desktop && !wide) DesktopVerticalScrollbar(scroll,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun AccountLane(wide: Boolean, scroll: ScrollState, content: @Composable () -> Unit) {
    Box {
        Column(if (wide) Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 10.dp) else Modifier.fillMaxWidth()) {
            content()
        }
        if (wide) Box(Modifier.matchParentSize()) {
            DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** The scrollbar measures against the list's viewport and never determines a small dialog's height. */
@Composable
internal fun AccountReviewList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    if (platformKind != PlatformKind.DESKTOP) {
        LazyColumn(modifier, state = state, contentPadding = contentPadding,
            verticalArrangement = verticalArrangement, content = content)
        return
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().padding(end = 10.dp), state = state, contentPadding = contentPadding,
            verticalArrangement = verticalArrangement, content = content)
        Box(Modifier.matchParentSize()) {
            DesktopVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** The action stays in sight on desktop. A short window puts it in the same scroll as the review. */
@Composable
internal fun AccountDifferencePanel(
    header: @Composable () -> Unit,
    summary: @Composable () -> Unit,
    hasDifferences: Boolean,
    footer: @Composable () -> Unit,
    mobileListMax: Dp = Dp.Infinity,
    differences: LazyListScope.() -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val window = LocalMangaWindow.current
    val short = desktop && window.height < 350.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val cap = if (desktop) window.height.coerceAtMost(720.dp)
        else (window.height - 64.dp).coerceAtLeast(160.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = cap)
        .padding(horizontal = if (desktop) 22.dp else 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        if (desktop) {
            AccountReviewList(Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "review-summary") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { summary() } }
                if (hasDifferences) {
                    differences()
                    if (short) item(key = "review-apply") { footer() }
                }
            }
        } else {
            summary()
            if (hasDifferences) LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = mobileListMax),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = differences)
        }
        if (hasDifferences && !short) footer()
    }
}

@Composable
internal fun Modifier.accountReviewRow(selected: Boolean = false): Modifier {
    if (platformKind != PlatformKind.DESKTOP) return this
    val colors = MaterialTheme.colorScheme
    val background = animateColorAsState(if (selected) colors.primary.copy(alpha = .085f) else colors.surfaceContainerLow,
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "account-review-selection")
    return drawBehind { drawRoundRect(background.value, cornerRadius = CornerRadius(14.dp.toPx())) }
        .padding(horizontal = 14.dp, vertical = 8.dp)
}

/** Local and remote always keep their own heading and order, including at large system font sizes. */
@Composable
internal fun ReviewComparison(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 410.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.weight(1f)) { right() }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            left()
            right()
        }
    }
}
