package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.*
import dev.mangaflow.anime.danmaku.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Forms exclude frame positions, so an open setting does not recompose on every video frame. */
@Composable
internal fun AnimeDanmakuPane(section: AnimePlayerPanel, controller: AnimeController, screen: AnimeScreenState,
    onSection: (AnimePlayerPanel) -> Unit, modifier: Modifier = Modifier) {
    val control = controller.danmaku
    val formState = remember(control) { control.state.map { it.copy(visible = emptyList(), positionMs = 0L, playing = false) }
        .distinctUntilChanged() }
    val state by formState.collectAsStateWithLifecycle(initialValue = control.state.value.copy(
        visible = emptyList(), positionMs = 0L, playing = false))
    val current = screen.detail?.episodes?.firstOrNull { it.id == screen.selectedEpisodeId }
    var query by remember(state.ownerKey) { mutableStateOf(screen.detail?.subject?.title.orEmpty()) }
    var episodeQuery by remember(state.ownerKey) { mutableStateOf(current?.let {
        if (it.kind == AnimeEpisodeKind.MAIN) animeEpisodeNumber(it.number) else it.title
    }.orEmpty()) }
    var fontDraft by remember(state.ownerKey) { mutableStateOf(state.settings.fontScale.toString()) }
    var densityDraft by remember(state.ownerKey) { mutableStateOf(state.settings.density.toString()) }
    var offsetDraft by remember(state.ownerKey) { mutableStateOf(state.settings.offsetMs.toString()) }
    var wordsDraft by remember(state.ownerKey) { mutableStateOf(state.settings.blockedWords.joinToString("，")) }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var removingConfiguration by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val scrolls = remember(control) { mutableMapOf<AnimePlayerPanel, LazyListState>() }
    val scroll = remember(section) { scrolls.getOrPut(section) { LazyListState() } }
    LaunchedEffect(section) {
        inputError = null
        if (section != AnimePlayerPanel.DANMAKU_ACCESS) { appId = ""; appSecret = ""; removingConfiguration = false }
        if (section == AnimePlayerPanel.DANMAKU_DISPLAY) {
            fontDraft = state.settings.fontScale.toString(); densityDraft = state.settings.density.toString()
            offsetDraft = state.settings.offsetMs.toString(); wordsDraft = state.settings.blockedWords.joinToString("，")
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = scroll, contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section) {
            AnimePlayerPanel.DANMAKU -> {
                item { SettingsToggle("显示弹幕", state.settings.enabled, Icons.Outlined.Subtitles,
                    onCheckedChange = { control.updateSettings(state.settings.copy(enabled = it)) }) }
                item {
                    AnimeSectionTitle(state.selectionLabel ?: "还没有匹配弹幕",
                        if (state.loading) "正在读取弹幕" else if (state.selectionLabel != null) state.totalCount.toString() + " 条弹幕" else
                            current?.let { animeEpisodeLabel(it) + " · " + it.title })
                }
                item { CompactMenuAction(Icons.Outlined.Search, "匹配当前集弹幕", enabled = state.ownerKey != null,
                    onClick = { onSection(AnimePlayerPanel.DANMAKU_SEARCH) }) }
                if (screen.sourceDanmakuAvailable) item {
                    CompactMenuAction(Icons.Outlined.Tv, "使用当前来源的本集弹幕", enabled = !state.loading,
                        onClick = controller::loadCurrentSourceDanmaku)
                }
                item { CompactMenuAction(Icons.Outlined.Tune, "显示、偏移与屏蔽词",
                    onClick = { onSection(AnimePlayerPanel.DANMAKU_DISPLAY) }) }
                item { CompactMenuAction(Icons.Outlined.Key, if (state.configured) "弹幕服务 · 已配置" else "配置弹幕服务",
                    onClick = { onSection(AnimePlayerPanel.DANMAKU_ACCESS) }) }
                if (state.selectionLabel != null || state.loading) item {
                    CompactMenuAction(Icons.Outlined.LinkOff, "取消本集弹幕匹配", onClick = control::clearSelection)
                }
            }
            AnimePlayerPanel.DANMAKU_SEARCH -> {
                item { AnimeQuietLabel("核对季度与集名后选择，弹幕不会按片名自动套到当前视频。") }
                if (!state.configured) item {
                    AnimeMessage("先配置弹幕服务", "填写你自己的弹弹play应用凭据后，可通过官方服务搜索剧集。",
                        action = "配置服务", onAction = { onSection(AnimePlayerPanel.DANMAKU_ACCESS) })
                } else {
                    item { AnimeTextField(query, { query = it }, "番剧名称或别名",
                        onSubmit = { control.search(query.trim(), episodeQuery.trim()) }) }
                    item { AnimeTextField(episodeQuery, { episodeQuery = it }, "集号或集名，可留空",
                        onSubmit = { control.search(query.trim(), episodeQuery.trim()) }) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MotionButton(onClick = { control.search(query.trim(), episodeQuery.trim()) },
                                enabled = query.isNotBlank() && !state.searching && state.ownerKey != null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(if (state.searching) "正在搜索" else "搜索弹幕")
                            }
                            if (state.searching) MotionTextButton(onClick = control::cancelSearch,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                        }
                    }
                    state.candidates.distinctBy { it.episodeId }.groupBy { it.animeId to it.animeTitle }.forEach { (anime, episodes) ->
                        item(key = "anime:" + anime.first) { AnimeSectionTitle(anime.second) }
                        items(episodes, key = { it.episodeId }) { candidate ->
                            AnimeSelection(candidate.episodeTitle, selected = state.selected?.episodeId == candidate.episodeId,
                                onClick = { control.select(candidate); onSection(AnimePlayerPanel.DANMAKU) })
                        }
                    }
                    if (!state.searching && state.query.isNotBlank() && state.candidates.isEmpty() && state.error == null) item {
                        AnimeMessage("没有找到对应弹幕", "试试别名，或清空集号后查看候选。")
                    }
                }
            }
            AnimePlayerPanel.DANMAKU_DISPLAY -> {
                item {
                    AnimeSectionTitle("透明度", (state.settings.opacity * 100).toInt().toString() + "%")
                    MotionSlider(state.settings.opacity, onValueChange = {
                        control.updateSettings(control.state.value.settings.copy(opacity = it))
                    }, valueRange = 0f..1f, formatValue = { (it * 100).toInt().toString() + "%" },
                        modifier = Modifier.fillMaxWidth())
                }
                item { SettingsToggle("显示彩色弹幕", state.settings.showColored, Icons.Outlined.Palette,
                    onCheckedChange = { control.updateSettings(state.settings.copy(showColored = it)) }) }
                item { SettingsToggle("合并重复内容", state.settings.deduplicate, Icons.Outlined.FilterList,
                    onCheckedChange = { control.updateSettings(state.settings.copy(deduplicate = it)) }) }
                item { AnimeTextField(fontDraft, { fontDraft = it; inputError = null }, "字号倍率，大于 0") }
                item { AnimeTextField(densityDraft, { densityDraft = it; inputError = null }, "同时显示条数，大于 0") }
                item { AnimeTextField(offsetDraft, { offsetDraft = it; inputError = null }, "时间偏移（毫秒，可为负）") }
                item { AnimeQuietLabel("正数让弹幕延后，负数提前；1000 毫秒等于 1 秒。字号与密度按当前画面空间显示，输入值会保留。") }
                item { AnimeTextField(wordsDraft, { wordsDraft = it; inputError = null }, "屏蔽词，用逗号分隔") }
                item {
                    MotionButton(onClick = {
                        val font = fontDraft.trim().toFloatOrNull()
                        val density = densityDraft.trim().toIntOrNull()
                        val offset = offsetDraft.trim().toLongOrNull()
                        if (font == null || !font.isFinite() || font <= 0f) inputError = "字号倍率必须是大于零的数字。"
                        else if (density == null || density <= 0) inputError = "同时显示条数必须是大于零的整数。"
                        else if (offset == null) inputError = "时间偏移需要整数毫秒，数值不能超出可保存范围。"
                        else {
                            control.updateSettings(state.settings.copy(fontScale = font, density = density, offsetMs = offset,
                                blockedWords = wordsDraft.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank).distinct()))
                            inputError = null
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用显示设置") }
                }
            }
            AnimePlayerPanel.DANMAKU_ACCESS -> {
                item { AnimeQuietLabel("使用你自己的弹弹play应用凭据。凭据保存在本机的秘密存储中，不放入追番备份。") }
                if (state.configured) item { AnimeQuietLabel("已配置；这里不会回显已保存的密钥。") }
                item { AnimeTextField(appId, { appId = it; inputError = null }, "App ID") }
                item { AnimeTextField(appSecret, { appSecret = it; inputError = null }, "App Secret", secret = true) }
                item { MotionButton(onClick = {
                    control.configure(appId, appSecret)
                    appId = ""; appSecret = ""
                }, enabled = appId.isNotBlank() && appSecret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存服务配置") } }
                if (state.configured) item {
                    if (!removingConfiguration) CompactMenuAction(Icons.Outlined.DeleteOutline, "移除服务凭据", destructive = true,
                        onClick = { removingConfiguration = true })
                    else {
                        Text("移除后需要重新配置才能通过官方服务搜索弹幕。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MotionTextButton(onClick = { removingConfiguration = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                            MotionButton(onClick = { control.clearConfiguration(); removingConfiguration = false },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("确认移除凭据") }
                        }
                    }
                }
            }
            else -> Unit
        }
        if (inputError != null) item { Text(inputError.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error) }
        if (state.error != null) item { Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error) }
        if (state.notice != null) item { AnimeQuietLabel(state.notice.orEmpty()) }
    }
}
