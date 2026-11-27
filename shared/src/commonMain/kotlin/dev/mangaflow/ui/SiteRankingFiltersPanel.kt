@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.RankingSite
import dev.mangaflow.data.SiteRankingFilters
import dev.mangaflow.data.SiteRankingGenre
import dev.mangaflow.data.SiteRankingSelection
import dev.mangaflow.data.SiteRankingStatus
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** A retained draft inside the adopted PANEL; only Apply changes the active catalogue request. */
@Composable
internal fun SiteRankingFiltersDialog(
    visible: Boolean,
    selection: SiteRankingSelection,
    active: Boolean,
    backdrop: HazeState,
    onApply: (SiteRankingSelection) -> Unit,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    var draft by remember { mutableStateOf(selection) }
    // Keep incomplete numeric input local instead of normalizing each keystroke to "all years".
    var yearText by remember { mutableStateOf(selection.filters.year?.toString().orEmpty()) }
    val scroll = rememberScrollState()
    val advancedScroll = rememberScrollState()
    val open = visible && active
    LaunchedEffect(open) {
        if (open) {
            draft = selection
            yearText = selection.filters.year?.toString().orEmpty()
            scroll.scrollTo(0)
            advancedScroll.scrollTo(0)
        }
    }
    val currentYear = remember(open) { formatInstantMillis(wallTimeMillis(), "yyyy").toIntOrNull() }
    val yearValid = yearText.isBlank() || yearText.toIntOrNull()?.let { it in 1900..3000 } == true
    val preview = draft.normalized()
    val changeFilters: (SiteRankingFilters) -> Unit = { filters -> draft = draft.copy(filters = filters) }
    val desktop = fitsDesktopIndex(LocalMangaWindow.current.width, 900.dp)

    MotionDialog(visible = open, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = if (desktop) 884.dp else 520.dp,
        onClosed = onClosed) {
        val colors = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        val dismiss: () -> Unit = { focus.clearFocus(force = true); onDismiss() }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            // MotionDialog already accounts for safe drawing and IME; one stable height owner.
            val panelHeight = minOf(maxHeight, 640.dp)
            val split = desktop && fitsDesktopIndex(maxWidth, 690.dp)
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().height(panelHeight),
                cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { paneTitle = "榜单筛选与排序" }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("筛选与排序", Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                ReviewSourceMark(preview.site.id, size = 14.dp)
                                val scope = if (desktop) listOf(preview.site.title, preview.scopeLabel, preview.kind.label)
                                    .joinToString(" · ") else preview.site.title
                                Text(scope, style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        MotionIconButton(onClick = dismiss, enabled = open,
                            modifier = Modifier.size(44.dp), tooltip = "关闭筛选，不应用修改") {
                            Icon(Icons.Outlined.Close, "关闭筛选，不应用修改", Modifier.size(19.dp))
                        }
                    }
                    RankingFilterColumns(split, scroll, advancedScroll, Modifier.weight(1f).fillMaxWidth(), basic = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RankingFilterHeading("排序")
                            val kinds = preview.availableKinds
                            RankingFilterChoices(kinds.map { it.label },
                                selected = kinds.indexOf(preview.kind).coerceAtLeast(0), enabled = open,
                                onSelect = { draft = draft.copy(kind = kinds[it]) })
                            if (preview.site == RankingSite.BANGUMI && preview.filters.query.isNotBlank())
                                Text("关键词结果按匹配程度排列；清空关键词后使用所选排序。",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RankingFilterHeading("作品范围")
                            val scopes = preview.availableScopes
                            if (scopes.size > 1) RankingFilterChoices(scopes.map { it.label },
                                selected = scopes.indexOf(preview.scope).coerceAtLeast(0), enabled = open,
                                onSelect = { draft = draft.copy(scope = scopes[it]) })
                            else Text(preview.scopeLabel, Modifier.padding(vertical = 5.dp),
                                style = MaterialTheme.typography.bodyMedium)
                            if (preview.site == RankingSite.BANGUMI && preview.usesBangumiSearch)
                                Text("当前条件按漫画标签检索，不再限定系列或单行本",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RankingFilterHeading("首发年份")
                            val years = listOf<Int?>(null) + if (currentYear != null)
                                listOf(currentYear, currentYear - 1, currentYear - 2) else emptyList()
                            RankingFilterChoices(years.map { it?.toString() ?: "不限" },
                                selected = years.indexOf(draft.filters.year),
                                selectionVisible = yearValid && draft.filters.year in years,
                                enabled = open, onSelect = { index ->
                                    val year = years[index]
                                    yearText = year?.toString().orEmpty()
                                    changeFilters(draft.filters.copy(year = year,
                                        month = draft.filters.month.takeIf { year != null }))
                                    focus.clearFocus(force = true)
                                })
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.onSurface.copy(alpha = .045f))
                                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("指定年份", style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                                BasicTextField(yearText, onValueChange = { value ->
                                    if (value.length <= 4 && value.all(Char::isDigit)) {
                                        yearText = value
                                        val year = value.toIntOrNull()
                                        changeFilters(draft.filters.copy(year = year,
                                            month = draft.filters.month.takeIf { year != null }))
                                    }
                                }, enabled = open, singleLine = true,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                                        .semantics { contentDescription = "指定首发年份，1900 至 3000，留空为全部" },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.onSurface),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true) }),
                                    decorationBox = { input ->
                                        Box {
                                            if (yearText.isEmpty()) Text("1900–3000",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.onSurfaceVariant.copy(alpha = .55f))
                                            input()
                                        }
                                    })
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    if (yearText.isNotEmpty()) MotionIconButton(onClick = {
                                        yearText = ""
                                        changeFilters(draft.filters.copy(year = null, month = null))
                                    }, enabled = open, modifier = Modifier.size(44.dp), tooltip = "清除年份") {
                                        Icon(Icons.Outlined.Close, "清除年份", Modifier.size(16.dp))
                                    }
                                }
                            }
                            Text(when {
                                !yearValid -> "请输入 1900–3000 之间的年份。"
                                draft.site.supportsMonth && !preview.supportsMonth -> "选择年份后可再限定首发月份。"
                                else -> "按作品首发年份筛选。"
                            },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (yearValid) colors.onSurfaceVariant else colors.error)
                            if (yearValid && preview.supportsMonth) {
                                RankingFilterHeading("首发月份", Modifier.padding(top = 7.dp))
                                RankingFilterChoices(listOf("不限") + (1..12).map { "${it}月" },
                                    selected = preview.filters.month ?: 0,
                                    enabled = open,
                                    onSelect = { index -> changeFilters(draft.filters.copy(month = index.takeIf { it > 0 })) })
                            }
                        }

                    }, advanced = {
                        if (split && !preview.supportsAdvancedFilters && !preview.site.supportsStatus && !preview.site.supportsCountry) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                RankingFilterHeading("榜单口径")
                                Text(preview.explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        if (preview.supportsAdvancedFilters && preview.site.supportsQuery) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("关键词")
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                                    BasicTextField(draft.filters.query,
                                        onValueChange = { changeFilters(draft.filters.copy(query = it.take(300))) },
                                        enabled = open, singleLine = true,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp)
                                            .semantics { contentDescription = "榜单关键词，可填写书名或别名" },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                        cursorBrush = SolidColor(colors.onSurface),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true) }),
                                        decorationBox = { input ->
                                            Box {
                                                if (draft.filters.query.isEmpty()) Text("书名或别名",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = colors.onSurfaceVariant.copy(alpha = .55f))
                                                input()
                                            }
                                        })
                                    MotionIconButton(onClick = { changeFilters(draft.filters.copy(query = "")) },
                                        enabled = open && draft.filters.query.isNotEmpty(),
                                        modifier = Modifier.size(44.dp), tooltip = "清除关键词") {
                                        Icon(Icons.Outlined.Close, "清除关键词", Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        if (preview.supportsAdvancedFilters && preview.site.supportsGenres) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("题材 · 可多选")
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    SiteRankingGenre.entries.forEach { genre ->
                                        val chosen = genre.id in draft.filters.genres
                                        RankingFilterChoice(genre.label, chosen, open, multiple = true) {
                                            changeFilters(draft.filters.copy(genres = if (chosen)
                                                draft.filters.genres - genre.id else draft.filters.genres + genre.id))
                                        }
                                    }
                                }
                            }
                        }

                        if (preview.site.supportsStatus) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("连载状态")
                                val statuses = listOf<SiteRankingStatus?>(null) + SiteRankingStatus.entries
                                RankingFilterChoices(statuses.map { it?.label ?: "不限" },
                                    selected = statuses.indexOfFirst { it?.id == draft.filters.status }.coerceAtLeast(0),
                                    enabled = open, onSelect = { changeFilters(draft.filters.copy(status = statuses[it]?.id)) })
                            }
                        }
                        if (preview.site.supportsCountry) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("地区")
                                val countries = listOf(null, "JP", "KR", "CN", "TW")
                                RankingFilterChoices(listOf("不限", "日本", "韩国", "中国", "台湾"),
                                    selected = countries.indexOf(draft.filters.country).coerceAtLeast(0), enabled = open,
                                    onSelect = { changeFilters(draft.filters.copy(country = countries[it])) })
                            }
                        }
                        if (preview.supportsAdvancedFilters && preview.site.supportsMinScore) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("最低评分 · 10 分制")
                                val scores = listOf(null, 6, 7, 8, 9)
                                RankingFilterChoices(listOf("不限", "6+", "7+", "8+", "9+"),
                                    selected = scores.indexOf(draft.filters.minScore).coerceAtLeast(0), enabled = open,
                                    selectionVisible = draft.filters.minScore in scores,
                                    onSelect = { changeFilters(draft.filters.copy(minScore = scores[it])) })
                            }
                        }
                        if (preview.supportsAdvancedFilters && preview.site.supportsMinVotes) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RankingFilterHeading("评分人数至少")
                                val votes = listOf(null, 50, 100, 500, 1000)
                                RankingFilterChoices(listOf("不限", "50", "100", "500", "1000"),
                                    selected = votes.indexOf(draft.filters.minVotes).coerceAtLeast(0), enabled = open,
                                    selectionVisible = draft.filters.minVotes in votes,
                                    onSelect = { changeFilters(draft.filters.copy(minVotes = votes[it])) })
                            }
                        }
                    })
                    if (split && preview.activeFilterCount > 0) Text(preview.filterSummary,
                        Modifier.fillMaxWidth().background(colors.onSurface.copy(alpha = .04f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = colors.onSurface.copy(alpha = .08f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        MotionTextButton(onClick = {
                            draft = draft.copy(filters = SiteRankingFilters()).normalized()
                            yearText = ""
                            focus.clearFocus(force = true)
                        }, enabled = open && (draft.filters != SiteRankingFilters() || yearText.isNotEmpty()),
                            modifier = Modifier.weight(1f, fill = false).heightIn(min = 44.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(17.dp))
                            Text("清除条件", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        MotionTextButton(onClick = {
                            focus.clearFocus(force = true)
                            onApply(preview)
                            onDismiss()
                        }, enabled = open && yearValid && preview.valid(),
                            modifier = Modifier.weight(1f, fill = false).heightIn(min = 44.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = colors.onSurface, contentColor = colors.surface),
                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                            Text("应用筛选", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingFilterColumns(split: Boolean, left: ScrollState, right: ScrollState, modifier: Modifier,
    basic: @Composable ColumnScope.() -> Unit, advanced: @Composable ColumnScope.() -> Unit) {
    if (!split) {
        val desktop = dev.mangaflow.platform.platformKind == dev.mangaflow.platform.PlatformKind.DESKTOP
        Box(modifier) {
            Column(Modifier.fillMaxSize().verticalScroll(left).padding(top = 12.dp, bottom = 12.dp,
                end = if (desktop) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                basic()
                advanced()
            }
            if (desktop) DesktopVerticalScrollbar(left, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        return
    }
    Row(modifier) {
        Box(Modifier.weight(.44f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(left).padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp), content = basic)
            DesktopVerticalScrollbar(left, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
        }
        androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Box(Modifier.weight(.56f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(right).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp), content = advanced)
            DesktopVerticalScrollbar(right, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
        }
    }
}

@Composable
private fun RankingFilterHeading(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold)
}

/** Adapt the existing SourceBrowseChoice pills: options wrap within the panel, not a second scroll axis. */
@Composable
private fun RankingFilterChoices(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    selectionVisible: Boolean = true,
) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { index, label ->
            RankingFilterChoice(label, selectionVisible && index == selected, enabled) { onSelect(index) }
        }
    }
}

@Composable
private fun RankingFilterChoice(label: String, chosen: Boolean, enabled: Boolean,
    multiple: Boolean = false, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onSelect, enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp).semantics {
            role = if (multiple) Role.Checkbox else Role.RadioButton
            if (multiple) toggleableState = if (chosen) ToggleableState.On else ToggleableState.Off
            selected = chosen
            stateDescription = if (chosen) "已选择" else "未选择"
        }, shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (chosen) colors.primary else colors.onSurfaceVariant,
            containerColor = if (chosen) colors.primary.copy(alpha = .10f) else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp)) {
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
        }
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium,
            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}
