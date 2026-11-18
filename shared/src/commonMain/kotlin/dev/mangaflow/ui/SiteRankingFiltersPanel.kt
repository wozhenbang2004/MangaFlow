@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

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
    val open = visible && active
    LaunchedEffect(open) {
        if (open) {
            draft = selection
            yearText = selection.filters.year?.toString().orEmpty()
            scroll.scrollTo(0)
        }
    }
    val currentYear = remember(open) { formatInstantMillis(wallTimeMillis(), "yyyy").toIntOrNull() }
    val yearValid = yearText.isBlank() || yearText.toIntOrNull()?.let { it in 1900..3000 } == true
    val preview = draft.normalized()
    val changeFilters: (SiteRankingFilters) -> Unit = { filters -> draft = draft.copy(filters = filters) }

    MotionDialog(visible = open, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp,
        onClosed = onClosed) {
        val colors = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        val dismiss: () -> Unit = { focus.clearFocus(force = true); onDismiss() }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            // MotionDialog already accounts for safe drawing and IME; one stable height owner.
            val panelHeight = minOf(maxHeight, 640.dp)
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().height(panelHeight),
                cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { paneTitle = "榜单组合筛选" }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ReviewSourceMark(preview.site.id, size = 17.dp)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("组合筛选", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium)
                            Text(preview.site.title + " · " + preview.kind.label,
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MotionIconButton(onClick = dismiss, enabled = open,
                            modifier = Modifier.size(44.dp), tooltip = "关闭筛选，不应用修改") {
                            Icon(Icons.Outlined.Close, "关闭筛选，不应用修改", Modifier.size(19.dp))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("首发年份", style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant)
                            val years = listOf<Int?>(null) + if (currentYear != null)
                                listOf(currentYear, currentYear - 1, currentYear - 2) else emptyList()
                            val yearLabels = if (currentYear != null) listOf("全部", "今年", "去年", "前年")
                                else listOf("全部")
                            RankingFilterTabs(yearLabels,
                                selected = years.indexOf(draft.filters.year).coerceAtLeast(0),
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
                            Text(if (yearValid) "按首发年筛选，保留当前评分与热度" else "请输入 1900–3000 之间的年份",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (yearValid) colors.onSurfaceVariant else colors.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (draft.site.supportsMonth) {
                                Text("月份", Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                RankingFilterTabs(listOf("全部") + (1..12).map { "${it}月" },
                                    selected = preview.filters.month ?: 0,
                                    enabled = open && preview.supportsMonth, selectionVisible = preview.supportsMonth,
                                    onSelect = { index -> changeFilters(draft.filters.copy(month = index.takeIf { it > 0 })) })
                                Text(if (preview.supportsMonth) "限定该年内的首发月份" else "选择年份后可限定月份",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("范围", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            val scopes = preview.availableScopes
                            if (scopes.size > 1) RankingFilterTabs(scopes.map { it.label },
                                selected = scopes.indexOf(preview.scope).coerceAtLeast(0), enabled = open,
                                onSelect = { draft = draft.copy(scope = scopes[it]) })
                            else Text(preview.scopeLabel, Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                            if (preview.site == RankingSite.BANGUMI && preview.usesBangumiSearch)
                                Text("当前条件按漫画标签检索，不再限定系列或单行本",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }

                        if (preview.supportsAdvancedFilters && preview.site.supportsQuery) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("关键词", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
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
                                Text("题材 · 可多选", style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    SiteRankingGenre.entries.forEach { genre ->
                                        val chosen = genre.id in draft.filters.genres
                                        MotionTextButton(onClick = {
                                            changeFilters(draft.filters.copy(genres = if (chosen)
                                                draft.filters.genres - genre.id else draft.filters.genres + genre.id))
                                        }, enabled = open, modifier = Modifier.heightIn(min = 44.dp).semantics {
                                            selected = chosen
                                            stateDescription = if (chosen) "已选择" else "未选择"
                                        }, colors = ButtonDefaults.textButtonColors(
                                            containerColor = if (chosen) colors.onSurface.copy(alpha = .08f) else Color.Transparent,
                                            contentColor = colors.onSurface),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                                            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                                if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
                                            }
                                            Text(genre.label, Modifier.padding(start = 4.dp),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        if (preview.site.supportsStatus) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("连载状态", style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                                val statuses = listOf<SiteRankingStatus?>(null) + SiteRankingStatus.entries
                                RankingFilterTabs(statuses.map { it?.label ?: "全部" },
                                    selected = statuses.indexOfFirst { it?.id == draft.filters.status }.coerceAtLeast(0),
                                    enabled = open, onSelect = { changeFilters(draft.filters.copy(status = statuses[it]?.id)) })
                            }
                        }
                        if (preview.site.supportsCountry) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("地区", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                val countries = listOf(null, "JP", "KR", "CN", "TW")
                                RankingFilterTabs(listOf("全部", "日本", "韩国", "中国", "台湾"),
                                    selected = countries.indexOf(draft.filters.country).coerceAtLeast(0), enabled = open,
                                    onSelect = { changeFilters(draft.filters.copy(country = countries[it])) })
                            }
                        }
                        if (preview.supportsAdvancedFilters && preview.site.supportsMinScore) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("最低评分 · 10 分制", style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                                val scores = listOf(null, 6, 7, 8, 9)
                                RankingFilterTabs(listOf("不限", "6+", "7+", "8+", "9+"),
                                    selected = scores.indexOf(draft.filters.minScore).coerceAtLeast(0), enabled = open,
                                    selectionVisible = draft.filters.minScore in scores,
                                    onSelect = { changeFilters(draft.filters.copy(minScore = scores[it])) })
                            }
                        }
                        if (preview.supportsAdvancedFilters && preview.site.supportsMinVotes) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("评分人数至少", style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                                val votes = listOf(null, 50, 100, 500, 1000)
                                RankingFilterTabs(listOf("不限", "50", "100", "500", "1000"),
                                    selected = votes.indexOf(draft.filters.minVotes).coerceAtLeast(0), enabled = open,
                                    selectionVisible = draft.filters.minVotes in votes,
                                    onSelect = { changeFilters(draft.filters.copy(minVotes = votes[it])) })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MotionTextButton(onClick = {
                            draft = draft.copy(filters = SiteRankingFilters()).normalized()
                            yearText = ""
                            focus.clearFocus(force = true)
                        }, enabled = open, modifier = Modifier.heightIn(min = 44.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(17.dp))
                            Text("重置", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.weight(1f))
                        MotionTextButton(onClick = {
                            focus.clearFocus(force = true)
                            onApply(preview)
                            onDismiss()
                        }, enabled = open && yearValid && preview.valid(),
                            modifier = Modifier.heightIn(min = 44.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = colors.onSurface.copy(alpha = .09f), contentColor = colors.onSurface),
                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                            Text("应用", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Compose the existing Ovio selector in a scrollable row; labels and hit regions stay fixed. */
@Composable
private fun RankingFilterTabs(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    selectionVisible: Boolean = true,
) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.coerceAtLeast(64.dp * scale * labels.size)
        Box(Modifier.horizontalScroll(rememberScrollState(), enabled = enabled)) {
            MotionTabs(selected, labels, onSelect = { if (enabled) onSelect(it) },
                modifier = Modifier.width(width).motionInputGate(enabled),
                controlHeight = 44.dp, fontSize = 12.sp, role = Role.RadioButton,
                selectionVisible = selectionVisible)
        }
    }
}
