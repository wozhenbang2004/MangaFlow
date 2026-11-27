package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback

/** Keep the existing filter state and callbacks, disclosing controls inside the comment layout. */
@Composable
internal fun DetailCommentFilter(
    backdrop: HazeState,
    sources: List<ReviewSource>,
    selected: String?,
    longOnly: Boolean,
    hidePromotions: Boolean,
    onSelect: (String?) -> Unit,
    onLongOnly: (Boolean) -> Unit,
    onHidePromotions: (Boolean) -> Unit,
    onRetry: (String) -> Unit,
    onMatch: (String, String) -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean,
    onPresenceChange: (Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    var more by remember { mutableStateOf(false) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    // Keep the outgoing controls intact until their parent finishes folding.
    // Clearing them when the close is requested removes content mid-animation.
    LaunchedEffect(present) { if (!present) { more = false; detailsId = null } }
    val reviewSources = remember(sources) { sources.filter { it.hasWorkReviews }.distinctBy { it.id } }
    val selectedSource = if (selected == null) 0 else reviewSources.indexOfFirst { it.id == selected }
        .let { if (it < 0) -1 else it + 1 }
    val cursor = animateFloatAsState(selectedSource.coerceAtLeast(0).toFloat(),
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.GestureStiffness) else snap(), label = "comment-source-position")
    val sourceInk = selected?.let { reviewSourceAccent(it, DetailForeground.luminance() > .5f) } ?: DetailForeground
    val markerColor = animateColorAsState(sourceInk, if (motion) tween(MangaMotion.Quick) else snap(),
        label = "comment-source-ink")
    val sourceScroll = rememberScrollState()
    val density = LocalDensity.current
    var stripWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, selectedSource, stripWidth, sourceScroll.maxValue) {
        if (!visible || selectedSource < 0 || stripWidth <= 0) return@LaunchedEffect
        val cell = with(density) { 44.dp.roundToPx() }
        val step = cell + with(density) { 2.dp.roundToPx() }
        val start = selectedSource * step
        val current = sourceScroll.value
        val target = when {
            start < current -> start
            start + cell > current + stripWidth -> start + cell - stripWidth
            else -> current
        }.coerceIn(0, sourceScroll.maxValue)
        if (target != current) {
            if (motion) sourceScroll.animateScrollTo(target) else sourceScroll.scrollTo(target)
        }
    }
    val pending = remember(sources) { sources.filter {
        it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null ||
            (it.status == ReviewStatus.NO_MATCH && it.candidates.isNotEmpty())
    } }
    val details = pending.firstOrNull { it.id == detailsId }
    var retainedDetails by remember { mutableStateOf<ReviewSource?>(null) }
    SideEffect { if (details != null) retainedDetails = details }
    val shownDetails = details ?: retainedDetails
    val changed = selected != null || longOnly || !hidePromotions
    BackHandler(visible) {
        if (detailsId != null) detailsId = null
        else if (more) more = false
        else onDismiss()
    }
    AnimatedVisibility(presence,
        enter = expandVertically(tween(if (motion) MangaMotion.Enter else 0), expandFrom = Alignment.Top) +
            fadeIn(tween(if (motion) MangaMotion.Content else 0)),
        exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0), shrinkTowards = Alignment.Top) +
            fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
        Column(Modifier.fillMaxWidth().motionInputGate(visible)
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (platformKind == PlatformKind.DESKTOP) {
                    var open by remember { mutableStateOf(false) }
                    val chosen = reviewSources.firstOrNull { it.id == selected }
                    val selectSource: (String?) -> Unit = { id ->
                        onSelect(id)
                        detailsId = id?.takeIf { candidate -> pending.any { it.id == candidate } }
                    }
                    val choices = listOf(DesktopMenuAction("全部评论来源", selected = selected == null) { selectSource(null) }) +
                        reviewSources.map { source ->
                            val status = when {
                                source.loadingMore -> "读取中"
                                source.status == ReviewStatus.UNAVAILABLE || source.reviewsError != null -> "暂不可用"
                                source.status == ReviewStatus.NO_MATCH && source.candidates.isNotEmpty() -> "待匹配"
                                else -> null
                            }
                            DesktopMenuAction(listOfNotNull(source.name, status).joinToString(" · "),
                                selected = selected == source.id) { selectSource(source.id) }
                        }
                    Box(Modifier.weight(1f)) {
                        dev.mangaflow.ui.motion.MotionTextButton(onClick = { open = !open }, enabled = visible,
                            modifier = Modifier.heightIn(min = 40.dp).semantics {
                                stateDescription = if (open) "来源已展开" else "来源已收起"
                            }) {
                            if (chosen != null) ReviewSourceMark(chosen.id, size = 17.dp)
                            else Icon(Icons.Outlined.Public, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(chosen?.name ?: "全部评论来源", Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.labelLarge, maxLines = 2)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(15.dp))
                        }
                        DesktopActionPopup(open && visible, choices, { open = false }, alignToStart = true)
                    }
                } else Row(Modifier.weight(1f).onSizeChanged { stripWidth = it.width }
                    .horizontalScroll(sourceScroll).selectableGroup().drawBehind {
                        if (selectedSource >= 0) {
                            val cell = 44.dp.roundToPx().toFloat()
                            val offset = cursor.value * (cell + 2.dp.roundToPx())
                            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - cell - offset else offset
                            val h = minOf(size.height, 36.dp.toPx())
                            drawRoundRect(markerColor.value.copy(alpha = .11f), Offset(x + 2.dp.toPx(), (size.height - h) / 2f),
                                Size(cell - 4.dp.toPx(), h), CornerRadius(h / 2f))
                        }
                    },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice(null, "全部评论来源", selected == null, ownHighlight = false) {
                        detailsId = null
                        onSelect(null)
                    }
                    reviewSources.forEach { source ->
                        androidx.compose.runtime.key(source.id) {
                            SourceChoice(source.id, source.name, selected == source.id, source, ownHighlight = false) {
                                onSelect(source.id)
                                detailsId = source.id.takeIf { id -> pending.any { it.id == id } }
                            }
                        }
                    }
                }
                MotionIconButton(onClick = { more = !more }, tooltip = if (more) "收起更多筛选" else "更多评论筛选", modifier = Modifier.size(36.dp).semantics {
                    stateDescription = if (more) "更多筛选已展开" else "更多筛选已收起"
                }) {
                    Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreHoriz, "更多评论筛选", Modifier.size(19.dp), tint = DetailSecondary)
                        if (longOnly || !hidePromotions) Box(Modifier.align(Alignment.TopEnd).size(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(DetailForeground))
                    }
                }
                MotionIconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, "收起评论筛选", Modifier.size(17.dp), tint = DetailSecondary)
                }
            }
            AnimatedVisibility(more,
                enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                    fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                    fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterToggle(Icons.AutoMirrored.Outlined.Article, "仅长评", longOnly) { onLongOnly(!longOnly) }
                    FilterToggle(Icons.Outlined.VisibilityOff, "折叠推广", hidePromotions) { onHidePromotions(!hidePromotions) }
                    if (pending.isNotEmpty()) MotionTextButton(onClick = {
                        detailsId = if (detailsId == null) pending.first().id else null
                    }) { Text("来源状态", color = DetailSecondary, fontSize = 11.sp) }
                    if (changed) MotionIconButton(onClick = {
                        onSelect(null); onLongOnly(false); onHidePromotions(true)
                        detailsId = null
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.RestartAlt, "重置评论筛选", Modifier.size(17.dp), tint = DetailSecondary)
                    }
                }
            }
            AnimatedVisibility(details != null,
                enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                    fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                    fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                Column(Modifier.fillMaxWidth().heightIn(max =
                    (LocalMangaWindow.current.height * .28f).coerceIn(72.dp, 168.dp))
                    .motionInputGate(visible && details != null)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    if (pending.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pending.forEach { source ->
                            SourceChoice(source.id, source.name + "，来源状态", detailsId == source.id, source) {
                                detailsId = source.id
                            }
                        }
                    }
                    shownDetails?.let { current ->
                        MotionTabContent(selected = current, contentKey = { it.id },
                            order = { item -> pending.indexOfFirst { it.id == item.id } },
                            fillContent = false, modifier = Modifier.fillMaxWidth()) { source ->
                            if (source.status == ReviewStatus.UNAVAILABLE || source.reviewsError != null)
                                CommentLoadNotice(source, onRetry)
                            else CommentMatchChoice(source, onMatch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChoice(
    id: String?,
    name: String,
    selected: Boolean,
    source: ReviewSource? = null,
    ownHighlight: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = id?.let { reviewSourceAccent(it, DetailForeground.luminance() > .5f) } ?: DetailForeground
    val motion = LocalMotionEnabled.current
    val interaction = remember(id) { MutableInteractionSource() }
    val feedback = rememberFeelFeedback()
    val background = if (ownHighlight) animateColorAsState(if (selected) accent.copy(alpha = .10f) else Color.Transparent,
        tween(if (motion) MangaMotion.Content else 0), label = "source-selection-color").value else Color.Transparent
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(20.dp)).background(background)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = {
            if (!selected) feedback.perform(FeelEvent.Selection)
            onClick()
        })
        .motionPress(interaction, pressedScale = .95f)
        .semantics(mergeDescendants = true) {
            contentDescription = name
            stateDescription = when {
                source?.loadingMore == true -> "正在读取"
                source?.status == ReviewStatus.UNAVAILABLE || source?.reviewsError != null -> "暂不可用"
                source?.status == ReviewStatus.NO_MATCH && source?.candidates?.isNotEmpty() == true -> "需要匹配漫画"
                selected -> "已选择"
                else -> "未选择"
            }
        }, contentAlignment = Alignment.Center) {
        DesktopIconTooltip(name, interaction, true)
        if (id == null) Icon(Icons.Outlined.Public, null, tint = accent, modifier = Modifier.size(19.dp))
        else ReviewSourceMark(id, size = 20.dp)
        if (source?.loadingMore == true)
            MotionBusyIndicator(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(8.dp),
                color = DetailSecondary, strokeWidth = 1.dp)
        else if (source?.status == ReviewStatus.UNAVAILABLE || source?.reviewsError != null)
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.align(Alignment.BottomEnd).padding(5.dp).size(9.dp),
                tint = DetailSecondary)
        else if (source?.status == ReviewStatus.NO_MATCH && source?.candidates?.isNotEmpty() == true)
            Icon(Icons.Outlined.HelpOutline, null, Modifier.align(Alignment.BottomEnd).padding(5.dp).size(9.dp),
                tint = DetailSecondary)
    }
}

@Composable
private fun FilterToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current
    val color by animateColorAsState(if (checked) DetailForeground else DetailSecondary,
        tween(if (motion) MangaMotion.Content else 0), label = "comment-filter-toggle")
    val marked = animateFloatAsState(if (checked) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), label = "comment-filter-check")
    val feedback = rememberFeelFeedback()
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch) {
            feedback.perform(FeelEvent.Selection)
            onClick()
        }
        .motionPress(interaction, pressedScale = .98f).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Text(label, color = color, fontSize = 12.sp)
        Icon(Icons.Outlined.Check, null, tint = color, modifier = Modifier.size(13.dp).graphicsLayer {
            alpha = marked.value.coerceIn(0f, 1f)
            scaleX = .72f + .28f * marked.value
            scaleY = scaleX
        })
    }
}
