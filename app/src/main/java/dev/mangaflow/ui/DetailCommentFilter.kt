package dev.mangaflow.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
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
) {
    val density = LocalDensity.current
    var sourceSettings by remember { mutableStateOf(false) }
    val pending = sources.filter {
        it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null ||
            (it.status == ReviewStatus.NO_MATCH && it.candidates.isNotEmpty())
    }
    val width = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtMost(300.dp)
    val changed = selected != null || longOnly || !hidePromotions
    Popup(alignment = Alignment.TopEnd, offset = with(density) { IntOffset(-12.dp.roundToPx(), 40.dp.roundToPx()) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        LiquidGlassSurface(backdrop, Modifier.width(width), cornerRadius = 24.dp, role = GlassSurfaceRole.POPOVER) {
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
                    if (sourceSettings) pending.forEach { source ->
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

@Composable
private fun SourceChoice(id: String?, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dark = DetailForeground.luminance() > .5f
    val accent = id?.let { reviewSourceAccent(it, dark) } ?: DetailForeground
    Row(modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) accent.copy(alpha = .12f) else DetailForeground.copy(alpha = .025f))
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
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, role = Role.Switch) { onClick() }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, tint = DetailSecondary, modifier = Modifier.size(16.dp))
        Text(label, color = DetailForeground, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Outlined.Check, null, tint = accent, modifier = Modifier.size(16.dp))
        else Text("关", color = DetailMuted.copy(alpha = .6f), fontSize = 11.sp, lineHeight = 16.sp)
    }
}
