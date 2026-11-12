package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.flow.first
import kotlin.math.sin

/** A fixed toolbar slot: the surface grows left from the same, always reachable icon. */
@Composable
internal fun ExpandingSearchBar(
    expanded: Boolean,
    query: String,
    active: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val backdrop = rememberHazeState()
    var retainedQuery by remember { mutableStateOf(query) }
    var inputFocused by remember { mutableStateOf(false) }
    SideEffect { if (expanded) retainedQuery = query }
    val displayedQuery = if (expanded) query else retainedQuery
    val transition = updateTransition(expanded, label = "search-expansion")
    val reveal = transition.animateFloat(
        transitionSpec = {
            if (!motion) snap()
            else spring(dampingRatio = MangaMotion.PanelDamping, stiffness = MangaMotion.PanelStiffness)
        }, label = "search-width",
    ) { if (it) 1f else 0f }
    val icon = transition.animateFloat(
        transitionSpec = { if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap() },
        label = "search-icon",
    ) { if (it) 1f else 0f }
    val surfaceAlpha = transition.animateFloat(
        transitionSpec = { if (motion) tween(MangaMotion.Quick) else snap() }, label = "search-surface",
    ) { if (it) 1f else 0f }
    val present = expanded || transition.currentState || transition.isRunning

    LaunchedEffect(expanded, active) {
        if ((!expanded || !active) && inputFocused) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }
    BoxWithConstraints(modifier) {
        val fullWidth = maxWidth
        Box(Modifier.fillMaxSize().padding(end = 48.dp)
            .hazeSource(backdrop)
            .motionInputGate(active && !present)) { content() }

        if (present) {
            var inputPlaced by remember { mutableStateOf(false) }
            LaunchedEffect(expanded, active, inputPlaced) {
                if (expanded && active && inputPlaced) {
                    // Give the lens room before focus and the keyboard claim the input.
                    snapshotFlow { reveal.value }.first { it >= .86f }
                    requester.requestFocus()
                    keyboard?.show()
                }
            }
            LiquidGlassSurface(
                state = backdrop,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .width(48.dp + (fullWidth - 48.dp).coerceAtLeast(0.dp) * reveal.value.coerceIn(0f, 1f))
                    .height(48.dp).graphicsLayer { alpha = surfaceAlpha.value },
                cornerRadius = 24.dp,
            ) {
                // Measure text at its final width and reveal it from the right; never scale glyphs.
                Row(Modifier.wrapContentWidth(Alignment.End, unbounded = true)
                    .width(fullWidth).height(48.dp).padding(start = 14.dp, end = 48.dp)
                    .motionInputGate(expanded && active),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = displayedQuery,
                        onValueChange = { if (expanded && active) onQuery(it) },
                        enabled = expanded && active,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            if (expanded && active && query.isNotBlank()) onSearch()
                        }),
                        modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(requester)
                            .onFocusChanged { inputFocused = it.isFocused }
                            .onPlaced { inputPlaced = true }
                            .semantics { contentDescription = "搜索漫画、作者" },
                        decorationBox = { input ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (displayedQuery.isEmpty()) Text("搜索漫画、作者",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                input()
                            }
                        },
                    )
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (displayedQuery.isNotEmpty()) MotionIconButton(
                            onClick = { onQuery("") }, enabled = expanded && active,
                            modifier = Modifier.size(40.dp),
                        ) { Icon(Icons.Outlined.Cancel, "清空搜索", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        MotionIconButton(
            onClick = {
                if (expanded) { keyboard?.hide(); focus.clearFocus(force = true) }
                onExpandedChange(!expanded)
            },
            enabled = active,
            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp).semantics {
                contentDescription = if (expanded) "关闭搜索" else "搜索漫画、作者"
                stateDescription = if (expanded) "已展开" else "已收起"
            },
        ) {
            Box(Modifier.size(22.dp).graphicsLayer {
                val response = sin(icon.value.coerceIn(0f, 1f) * Math.PI).toFloat()
                scaleX = 1f - .12f * response
                scaleY = 1f - .12f * response
            }) {
                Icon(Icons.Outlined.Search, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - icon.value
                    rotationZ = -45f * icon.value
                })
                Icon(Icons.Outlined.Close, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = icon.value
                    rotationZ = 45f * (1f - icon.value)
                })
            }
        }
    }
}
