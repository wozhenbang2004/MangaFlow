package dev.mangaflow.reader

import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.PageTurnGesture
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.storage.swipeRightForNext
import dev.mangaflow.reader.panels.PanelSettings
import dev.mangaflow.reader.panels.PanelEngine
import dev.mangaflow.reader.panels.PanelReadingAvailable
import dev.mangaflow.ui.PaperThemePicker
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.roundToInt

/** Keep related choices together; each filled segment represents its complete hit region. */
@Composable
internal fun ReaderSettingsDialog(
    visible: Boolean,
    preferences: ReaderPreferences,
    backdrop: HazeState,
    onDimPreview: (Float) -> Unit,
    update: (ReaderPreferences) -> Unit,
    onModels: () -> Unit,
    sourceResumePolicy: String = "ask",
    onSourceResumePolicy: (String) -> Unit = {},
    embedded: Boolean = false,
    onDismiss: () -> Unit,
) {
    var dimAmount by remember { mutableFloatStateOf(preferences.dimAmount) }
    var sourceResumeExpanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val desktop = platformKind == PlatformKind.DESKTOP
    val modes = listOf(ReadingMode.VERTICAL to "纵向",
        ReadingMode.RIGHT_TO_LEFT to "右至左", ReadingMode.LEFT_TO_RIGHT to "左至右")
    val vertical = preferences.mode == ReadingMode.VERTICAL
    val layout = when {
        vertical -> 0
        preferences.doublePage -> 2
        else -> 1
    }
    LaunchedEffect(visible, preferences.dimAmount) { if (visible) dimAmount = preferences.dimAmount }

    val panel: @Composable () -> Unit = {
        val availableHeight = LocalMangaWindow.current.panelHeight
        val surface = if (embedded) Modifier.fillMaxSize().motionInputGate(visible)
            else Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()
        ReaderDialogSurface(surface, backdrop, dialogWindow = !embedded) {
            Column((if (embedded) Modifier.fillMaxSize() else Modifier.heightIn(max = availableHeight))
                .padding(horizontal = if (embedded) 14.dp else 18.dp)) {
                ReaderPanelHeading("阅读设置", "关闭设置", onDismiss)
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f, fill = false)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)
                    .padding(end = if (desktop) 10.dp else 0.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    ReaderSettingsSection(if (desktop) "阅读布局" else "阅读顺序") {
                        MotionTabs(
                            selected = if (desktop) layout else modes.indexOfFirst { it.first == preferences.mode },
                            labels = if (desktop) listOf("条漫", "单页", "双页") else modes.map { it.second },
                            onSelect = { index ->
                                if (desktop) {
                                    val pagedMode = if (vertical) preferences.lastPagedMode else preferences.mode
                                    update(preferences.copy(
                                        mode = if (index == 0) ReadingMode.VERTICAL else pagedMode,
                                        doublePage = if (index == 0) preferences.doublePage else index == 2,
                                    ))
                                } else update(preferences.copy(mode = modes[index].first))
                            },
                            foreground = colors.onSurface,
                            muted = colors.onSurfaceVariant,
                            track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
                            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                            controlHeight = 52.dp, fontSize = 14.sp, role = Role.RadioButton,
                            selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
                        )
                        if (preferences.panelEngine == PanelEngine.OFF && !vertical) {
                            if (desktop && preferences.doublePage) {
                                Text("双页阅读顺序 · 本作品", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                MotionTabs(
                                    selected = if (preferences.mode == ReadingMode.LEFT_TO_RIGHT) 0 else 1,
                                    labels = listOf("先左页，后右页", "先右页，后左页"),
                                    onSelect = { index ->
                                        val mode = if (index == 0) ReadingMode.LEFT_TO_RIGHT else ReadingMode.RIGHT_TO_LEFT
                                        update(preferences.copy(mode = mode))
                                    },
                                    foreground = colors.onSurface, muted = colors.onSurfaceVariant,
                                    track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
                                    fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                                    controlHeight = 44.dp, fontSize = 12.sp, role = Role.RadioButton,
                                )
                            }
                            Text(if (desktop) "翻页方向 · 所有漫画" else "翻页手势 · 所有漫画",
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            val gestures = if (desktop) listOf(PageTurnGesture.SWIPE_LEFT_NEXT, PageTurnGesture.SWIPE_RIGHT_NEXT)
                                else PageTurnGesture.entries
                            val selected = if (desktop) {
                                if (preferences.swipeRightForNext(desktop = true)) 1 else 0
                            } else gestures.indexOf(preferences.pageTurnGesture)
                            MotionTabs(selected = selected,
                                labels = if (desktop) listOf("左滑前进", "右滑前进") else listOf("跟随顺序", "左滑下一页", "右滑下一页"),
                                onSelect = { update(preferences.copy(pageTurnGesture = gestures[it])) },
                                foreground = colors.onSurface, muted = colors.onSurfaceVariant,
                                track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
                                fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                                controlHeight = 44.dp, fontSize = 12.sp, role = Role.RadioButton)
                        }
                        Column(Modifier.fillMaxWidth().animateContentSize(
                            animationSpec = tween(if (motion) MangaMotion.Content else 0,
                                easing = MangaMotion.EnterEasing),
                        )) {
                            if (preferences.panelEngine == PanelEngine.OFF && vertical) {
                                ReaderToggle("页间留白", preferences.pageGap) { update(preferences.copy(pageGap = it)) }
                            } else if (preferences.panelEngine == PanelEngine.OFF) {
                                if (!desktop) ReaderToggle("双页跨页", preferences.doublePage) { update(preferences.copy(doublePage = it)) }
                                if (preferences.doublePage) {
                                    ReaderToggle("封面独页", preferences.coverAlone) { update(preferences.copy(coverAlone = it)) }
                                }
                            }
                        }
                    }

                    Column(Modifier.fillMaxWidth()) {
                        val resumePolicies = listOf("ask", "source_record", "same_page")
                        val resumeLabels = listOf("每次询问", "本源记录", "尝试同页")
                        val selectedPolicy = resumePolicies.indexOf(sourceResumePolicy).coerceAtLeast(0)
                        val interaction = remember { MutableInteractionSource() }
                        val turn = animateFloatAsState(if (sourceResumeExpanded) 180f else 0f,
                            tween(if (motion) MangaMotion.Quick else 0), label = "reader-source-settings")
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                                onClickLabel = if (sourceResumeExpanded) "收起切源续读选项" else "展开切源续读选项",
                                onClick = { sourceResumeExpanded = !sourceResumeExpanded })
                            .motionPress(interaction, pressedScale = .99f)
                            .semantics {
                                stateDescription = resumeLabels[selectedPolicy] +
                                    if (sourceResumeExpanded) "，已展开" else "，已收起"
                            }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("切源续读", Modifier.weight(1f), color = colors.onSurfaceVariant,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(resumeLabels[selectedPolicy], color = colors.onSurfaceVariant, fontSize = 12.sp)
                            Icon(Icons.Outlined.ExpandMore, null, tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn.value })
                        }
                        AnimatedVisibility(sourceResumeExpanded,
                            enter = expandVertically(tween(if (motion) MangaMotion.Content else 0,
                                easing = MangaMotion.EnterEasing)) + fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
                            exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0,
                                easing = MangaMotion.ExitEasing)) + fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MotionTabs(
                                    selected = selectedPolicy, labels = resumeLabels,
                                    onSelect = { onSourceResumePolicy(resumePolicies[it]) },
                                    foreground = colors.onSurface, muted = colors.onSurfaceVariant,
                                    track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
                                    fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                                    controlHeight = 44.dp, fontSize = 12.sp, role = Role.RadioButton,
                                    selectedFontWeight = FontWeight.Medium, unselectedFontWeight = FontWeight.Normal,
                                )
                                AnimatedVisibility(sourceResumePolicy == "same_page",
                                    enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                                        expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                                    exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                                        shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                                    Text("不同版本分图可能不同，页码不保证对应。",
                                        color = colors.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (PanelReadingAvailable) ReaderSettingsSection("分镜") {
                        PanelSettings(preferences, update, onModels)
                    }

                    ReaderSettingsSection("外观") {
                        Text("阅读底色", color = colors.onSurface, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                        PaperThemePicker(preferences.theme) { update(preferences.copy(theme = it)) }
                    }

                    LocalReaderKeyboard.current?.settings?.invoke(!vertical && preferences.swipeRightForNext(desktop = true))

                    ReaderSettingsSection("屏幕") {
                        ReaderToggle("保持常亮", preferences.keepScreenOn) { update(preferences.copy(keepScreenOn = it)) }
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("降低亮度", fontSize = 14.sp, color = colors.onSurface)
                                Text("${(dimAmount * 100).roundToInt()}%", color = colors.onSurfaceVariant,
                                    fontSize = 12.sp)
                            }
                            ReaderSeekBar(
                                value = dimAmount, onValueChange = { dimAmount = it; onDimPreview(it) },
                                onValueChangeFinished = { update(preferences.copy(dimAmount = dimAmount)) },
                                valueRange = 0f..0.65f, modifier = Modifier.fillMaxWidth(), label = "降低阅读亮度",
                                valueLabel = { (it * 100).roundToInt().toString() + "%" },
                            )
                        }
                    }
                }
                DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                }
            }
        }
    }
    if (embedded) panel()
    else MotionDialog(visible = visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), content = panel)
}

@Composable
private fun ReaderSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}
