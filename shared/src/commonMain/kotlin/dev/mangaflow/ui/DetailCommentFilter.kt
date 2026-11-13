package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress

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
    LaunchedEffect(visible) { if (!visible) { more = false; detailsId = null } }
    val reviewSources = remember(sources) { sources.filter { it.hasWorkReviews }.distinctBy { it.id } }
    val pending = remember(sources) { sources.filter {
        it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null ||
            (it.status == ReviewStatus.NO_MATCH && it.candidates.isNotEmpty())
    } }
    val details = pending.firstOrNull { it.id == detailsId }
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
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice(null, "全部评论来源", selected == null) {
                        detailsId = null
                        onSelect(null)
                    }
                    reviewSources.forEach { source ->
                        androidx.compose.runtime.key(source.id) {
                            SourceChoice(source.id, source.name, selected == source.id, source) {
                                onSelect(source.id)
                                detailsId = source.id.takeIf { id -> pending.any { it.id == id } }
                            }
                        }
                    }
                }
                MotionIconButton(onClick = { more = !more }, modifier = Modifier.size(36.dp).semantics {
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
                    .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    if (pending.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pending.forEach { source ->
                            SourceChoice(source.id, source.name + "，来源状态", detailsId == source.id, source) {
                                detailsId = source.id
                            }
                        }
                    }
                    details?.let { source ->
                        androidx.compose.runtime.key(source.id) {
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
    onClick: () -> Unit,
) {
    val accent = id?.let { reviewSourceAccent(it, DetailForeground.luminance() > .5f) } ?: DetailForeground
    val motion = LocalMotionEnabled.current
    val interaction = remember(id) { MutableInteractionSource() }
    val background by animateColorAsState(if (selected) accent.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent,
        tween(if (motion) MangaMotion.Content else 0), label = "source-selection-color")
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(20.dp)).background(background)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
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
    Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch) { onClick() }
        .motionPress(interaction, pressedScale = .98f).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = DetailSecondary, modifier = Modifier.size(15.dp))
        Text(label, color = if (checked) DetailForeground else DetailSecondary, fontSize = 12.sp)
        if (checked) Icon(Icons.Outlined.Check, null, tint = DetailForeground, modifier = Modifier.size(13.dp))
    }
}
