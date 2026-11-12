package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionInputGate

@OptIn(ExperimentalLayoutApi::class)
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
    val density = LocalDensity.current
    val motion = LocalMotionEnabled.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = updateTransition(presence, label = "comment-filter")
    val alpha = transition.animateFloat(transitionSpec = {
        tween(if (motion) if (targetState) MangaMotion.Content else MangaMotion.Quick else 0)
    }, label = "filter-opacity") { if (it) 1f else 0f }
    val scale = transition.animateFloat(transitionSpec = {
        if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap()
    }, label = "filter-scale") { if (it) 1f else .94f }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    SideEffect { onPresenceChange(present) }
    if (!present) return
    var sourceSettings by remember { mutableStateOf(false) }
    val pending = sources.filter {
        it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null ||
            (it.status == ReviewStatus.NO_MATCH && it.candidates.isNotEmpty())
    }
    val width = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtMost(300.dp)
    val changed = selected != null || longOnly || !hidePromotions
    Popup(alignment = Alignment.TopEnd, offset = with(density) { IntOffset(-12.dp.roundToPx(), 0) },
        onDismissRequest = { if (visible) onDismiss() }, properties = PopupProperties(focusable = visible)) {
        LiquidGlassSurface(backdrop, Modifier.width(width).motionInputGate(visible).graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value; scaleY = scale.value
            transformOrigin = TransformOrigin(1f, 0f)
            translationY = -8.dp.toPx() * (1f - alpha.value)
        }, cornerRadius = 24.dp, role = GlassSurfaceRole.POPOVER) {
            Column(Modifier.heightIn(max = 328.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("评论来源", color = DetailSecondary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("重置", color = if (changed) DetailForeground else DetailMuted.copy(alpha = .4f), fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.clickable(enabled = changed, role = Role.Button) {
                            onSelect(null); onLongOnly(false); onHidePromotions(true)
                        }.padding(horizontal = 8.dp, vertical = 8.dp))
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tileWidth = (maxWidth - 12.dp) / 3
                    FlowRow(Modifier.fillMaxWidth().selectableGroup(), maxItemsInEachRow = 3,
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceChoice(null, "全部", selected == null, Modifier.width(tileWidth)) { onSelect(null) }
                        sources.filter { it.hasWorkReviews }.distinctBy { it.id }.forEach { source ->
                            SourceChoice(source.id, source.name, selected == source.id, Modifier.width(tileWidth)) { onSelect(source.id) }
                        }
                    }
                }
                HorizontalDivider(color = DetailForeground.copy(alpha = .055f), thickness = .5.dp)
                Column {
                    FilterToggle(Icons.Outlined.Article, "仅看长评", longOnly) { onLongOnly(!longOnly) }
                    FilterToggle(Icons.Outlined.VisibilityOff, "折叠推广", hidePromotions) { onHidePromotions(!hidePromotions) }
                }
                if (pending.isNotEmpty()) {
                    TextButton(onClick = { sourceSettings = !sourceSettings }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sourceSettings) "收起来源设置" else "来源设置", color = DetailSecondary, fontSize = 12.sp)
                    }
                    AnimatedVisibility(sourceSettings,
                        enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) + expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                        exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) + shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                        Column {
                            pending.forEach { source ->
                                key(source.id) {
                                    if (source.status == ReviewStatus.UNAVAILABLE || source.reviewsError != null) {
                                        CommentLoadNotice(source, onRetry)
                                    } else {
                                        CommentMatchChoice(source, onMatch)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChoice(id: String?, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dark = DetailForeground.luminance() > .5f
    val accent = id?.let { reviewSourceAccent(it, dark) } ?: DetailForeground
    val motion = LocalMotionEnabled.current
    val background by animateColorAsState(if (selected) accent.copy(alpha = .12f) else DetailForeground.copy(alpha = .025f),
        tween(if (motion) MangaMotion.Content else 0), label = "source-selection-color")
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(background)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = name }
        .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        if (id == null) {
            Icon(Icons.Outlined.Public, null, tint = accent, modifier = Modifier.size(14.dp))
            Text(" 全部", color = accent, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        } else {
            ReviewSourceMark(id, size = if (id in listOf("copy", "anilist", "myanimelist", "kitsu")) 16.dp else 21.dp)
            val label = when (id) { "copy" -> "拷贝"; "anilist" -> "AniList"; "myanimelist" -> "MAL"; "kitsu" -> "Kitsu"; else -> null }
            label?.let { Text(it, color = if (selected) accent else DetailSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp)) }
        }
    }
}

@Composable
private fun FilterToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    val accent = reviewSourceAccent("anilist", DetailForeground.luminance() > .5f)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, role = Role.Switch) { onClick() }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, tint = DetailSecondary, modifier = Modifier.size(16.dp))
        Text(label, color = DetailForeground, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        MotionSwap(checked, Modifier.size(18.dp)) { value ->
            if (value) Icon(Icons.Outlined.Check, null, tint = accent, modifier = Modifier.size(16.dp))
            else Text("关", color = DetailMuted.copy(alpha = .6f), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

