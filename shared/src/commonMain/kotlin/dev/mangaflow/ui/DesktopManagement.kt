package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.MotionIconButton
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
                    DesktopPageHeading(title)
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
            DesktopPageHeading(title)
            content()
        }
    }
}

internal class DesktopSettingsLink(val category: Int, val title: String, val terms: String, val open: () -> Unit)

@Composable
internal fun DesktopSettingsLayout(
    selected: Int,
    onSelected: (Int) -> Unit,
    topPadding: Dp,
    links: List<DesktopSettingsLink>,
    content: @Composable (Dp) -> Unit,
) {
    val pages = listOf("阅读" to Icons.AutoMirrored.Outlined.MenuBook, "外观" to Icons.Outlined.Palette,
        "数据" to Icons.Outlined.Storage, "应用" to Icons.Outlined.Tune)
    val labels = pages.map { it.first }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    val matches = if (words.isEmpty()) emptyList() else links.filter { link ->
        words.all { (labels[link.category] + " " + link.title + " " + link.terms).contains(it, ignoreCase = true) }
    }
    val focus = LocalFocusManager.current
    fun open(link: DesktopSettingsLink) {
        query = ""
        focus.clearFocus()
        onSelected(link.category)
        link.open()
    }
    val inputInteraction = remember { MutableInteractionSource() }
    val focused by inputInteraction.collectIsFocusedAsState()
    val finder: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp)
            .background(if (focused) colors.secondaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            BasicTextField(query, { query = it }, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || query.isBlank()) false else when (event.key) {
                        Key.Escape -> { query = ""; true }
                        Key.Enter -> matches.firstOrNull()?.let { open(it); true } ?: false
                        else -> false
                    }
                }.semantics { contentDescription = "查找设置" }, singleLine = true, interactionSource = inputInteraction,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { matches.firstOrNull()?.let(::open) }),
                decorationBox = { input -> Box {
                    if (query.isEmpty()) Text("查找设置", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    input()
                } })
            if (query.isNotEmpty()) MotionIconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp), tooltip = "清空") {
                Icon(Icons.Outlined.Close, "清空查找", Modifier.size(14.dp))
            }
        }
    }
    val results: @Composable () -> Unit = {
        if (matches.isEmpty()) Text("没有匹配的设置", Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        matches.forEach { link ->
            MotionTextButton(onClick = { open(link) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(link.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(labels[link.category], style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    val latestContent by rememberUpdatedState(content)
    val retainedPane = remember { movableContentOf { inset: Dp -> latestContent(inset) } }
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topPadding)) {
        if (maxWidth >= 700.dp * fontScale) {
            Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(176.dp * fontScale).fillMaxHeight().padding(top = 14.dp)) {
                finder()
                if (query.isNotBlank()) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { results() }
                else
                MotionNavigationColumn(selected, labels.size,
                    Modifier.fillMaxWidth().padding(top = 12.dp)) { index, itemModifier ->
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
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant.copy(alpha = .45f)))
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 850.dp).fillMaxSize()) { retainedPane(12.dp) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
                    finder()
                    if (query.isBlank()) MotionTabs(selected, labels, onSelected, modifier = Modifier.fillMaxWidth(),
                        treatment = MotionTabTreatment.Underline, controlHeight = 40.dp)
                    else Column(Modifier.fillMaxWidth().heightIn(max = 154.dp).verticalScroll(rememberScrollState())) { results() }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { retainedPane(4.dp) }
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
