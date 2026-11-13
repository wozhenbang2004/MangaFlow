package dev.mangaflow.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.reader.panels.PanelSettings
import dev.mangaflow.reader.panels.PanelEngine
import dev.mangaflow.ui.PaperThemePicker
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
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
    onDismiss: () -> Unit,
) {
    var dimAmount by remember { mutableFloatStateOf(preferences.dimAmount) }
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val modes = listOf(ReadingMode.VERTICAL to "纵向",
        ReadingMode.RIGHT_TO_LEFT to "右翻", ReadingMode.LEFT_TO_RIGHT to "左翻")
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(160).dp
    LaunchedEffect(visible, preferences.dimAmount) { if (visible) dimAmount = preferences.dimAmount }

    MotionDialog(visible = visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth(), backdrop) {
            Column(Modifier.heightIn(max = availableHeight).padding(horizontal = 18.dp)) {
                ReaderPanelHeading("阅读设置", "关闭设置", onDismiss)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ReaderSettingsSection("阅读") {
                        MotionTabs(
                            selected = modes.indexOfFirst { it.first == preferences.mode },
                            labels = modes.map { it.second },
                            onSelect = { update(preferences.copy(mode = modes[it].first)) },
                            foreground = colors.onSurface,
                            muted = colors.onSurfaceVariant,
                            track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
                            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                            controlHeight = 52.dp, fontSize = 14.sp, role = Role.RadioButton,
                            selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
                        )
                        Column(Modifier.fillMaxWidth().animateContentSize(
                            animationSpec = tween(if (motion) MangaMotion.Content else 0,
                                easing = MangaMotion.EnterEasing),
                        )) {
                            if (preferences.panelEngine == PanelEngine.OFF && preferences.mode == ReadingMode.VERTICAL) {
                                ReaderToggle("页间留白", preferences.pageGap) { update(preferences.copy(pageGap = it)) }
                            } else if (preferences.panelEngine == PanelEngine.OFF) {
                                ReaderToggle("双页跨页", preferences.doublePage) { update(preferences.copy(doublePage = it)) }
                                if (preferences.doublePage) {
                                    ReaderToggle("封面独页", preferences.coverAlone) { update(preferences.copy(coverAlone = it)) }
                                }
                            }
                        }
                    }

                    ReaderSettingsSection("分镜") {
                        PanelSettings(preferences, update, onModels)
                    }

                    ReaderSettingsSection("外观") {
                        Text("阅读底色", color = colors.onSurface, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                        PaperThemePicker(preferences.theme) { update(preferences.copy(theme = it)) }
                    }

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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
        content()
    }
}
