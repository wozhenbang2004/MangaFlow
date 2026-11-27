package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** Desktop page tools share one baseline rather than a separate floating phone capsule. */
@Composable
internal fun DesktopManagementToolbar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    minimumToolsWidth: Dp? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = minimumToolsWidth?.let { maxWidth < it * fontScale } == true
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    navigation()
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                // Only the real tools scroll; the navigation and page title keep their anchor.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            navigation()
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Settings keep their readable line length while the spare space carries category navigation. */
@Composable
internal fun DesktopSettingsLayout(
    selected: Int,
    onSelected: (Int) -> Unit,
    topPadding: Dp,
    content: @Composable (Dp) -> Unit,
) {
    val pages = listOf("阅读" to Icons.AutoMirrored.Outlined.MenuBook, "外观" to Icons.Outlined.Palette,
        "数据" to Icons.Outlined.Storage, "应用" to Icons.Outlined.Tune)
    val labels = pages.map { it.first }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topPadding)) {
        if (maxWidth >= 900.dp * fontScale) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                MotionNavigationColumn(selected, labels.size,
                    Modifier.width(160.dp * fontScale).padding(top = 14.dp)) { index, itemModifier ->
                    val chosen = index == selected
                    MotionTextButton(onClick = { onSelected(index) },
                        modifier = itemModifier.fillMaxWidth().semantics { this.selected = chosen },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (chosen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Icon(pages[index].second, null, Modifier.size(18.dp))
                        Text(labels[index], Modifier.weight(1f).padding(start = 12.dp),
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { content(8.dp) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                MotionTabs(selected, labels, onSelected,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).widthIn(max = 480.dp).fillMaxWidth(),
                    treatment = MotionTabTreatment.Underline, controlHeight = 44.dp)
                Box(Modifier.weight(1f).fillMaxWidth()) { content(4.dp) }
            }
        }
    }
}

/** Retain import/font/wallpaper composition and work when switching category; hide only its layout. */
@Composable
internal fun RetainedSettingsCategory(index: Int, selected: Int?, content: @Composable () -> Unit) {
    if (selected == null) {
        content()
        return
    }
    val visible = index == selected
    val motion = LocalMotionEnabled.current
    val opacity = animateFloatAsState(if (visible) 1f else 0f,
        if (motion) tween(if (visible) MangaMotion.Content else MangaMotion.Quick) else snap(), label = "settings-category")
    val position = animateFloatAsState(index.compareTo(selected).toFloat(),
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
        label = "settings-category-position")
    val stillPainting = remember { derivedStateOf { opacity.value > .001f } }
    Box(Modifier.fillMaxWidth().zIndex(if (visible) 1f else 0f).motionInputGate(visible)
        .layout { measurable, bounds ->
            if (!visible && !stillPainting.value) {
                layout(bounds.minWidth, 0) {}
            } else {
                val body = measurable.measure(bounds.copy(minHeight = 0))
                // Only the selected category owns scroll height; the outgoing page can finish
                // painting at the same origin while retaining its import and editing state.
                layout(body.width, if (visible) body.height else 0) {
                    body.placeRelativeWithLayer(0, 0) {
                        alpha = opacity.value
                        translationY = 12.dp.toPx() * position.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                }
            }
        }) { content() }
}
