package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

@Composable
internal fun AnimeDiscovery(state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scroll: LazyGridState, onOpen: (AnimeSubject) -> Unit, onSearch: () -> Unit, onExploreSources: () -> Unit) {
    var filtersVisible by remember { mutableStateOf(false) }
    var yearText by remember { mutableStateOf("") }
    var tagText by remember { mutableStateOf("") }
    var filterError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.browseMode, state.calendarDay, state.catalogYear, state.catalogTag, state.query) {
        scroll.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionTabs(state.browseMode.ordinal, listOf("排行", "新番", "番表"),
                onSelect = { controller.discover(AnimeBrowseMode.entries[it]) },
                modifier = Modifier.width(184.dp), compact = true, controlHeight = 44.dp,
                treatment = MotionTabTreatment.Soft)
            Spacer(Modifier.weight(1f))
            MotionIconButton(onClick = onExploreSources, modifier = Modifier.size(44.dp), tooltip = "来源发现") {
                Icon(Icons.Outlined.Explore, "浏览来源推荐与分类", Modifier.size(20.dp))
            }
            if (state.browseMode != AnimeBrowseMode.CALENDAR) MotionIconButton(onClick = {
                yearText = state.catalogYear?.toString().orEmpty(); tagText = state.catalogTag
                filterError = null; filtersVisible = true
            }, modifier = Modifier.size(44.dp), tooltip = "年份与标签") {
                Icon(Icons.Outlined.FilterList, "筛选年份与标签", Modifier.size(20.dp),
                    tint = if (state.catalogYear != null || state.catalogTag.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.browseMode == AnimeBrowseMode.CALENDAR) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val daysWidth = maxWidth.coerceAtLeast(352.dp)
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    MotionTabs(state.calendarDay ?: 0, listOf("全", "一", "二", "三", "四", "五", "六", "日"),
                        onSelect = { controller.selectCalendarDay(it.takeIf { day -> day > 0 }) },
                        modifier = Modifier.width(daysWidth), controlHeight = 44.dp,
                        treatment = MotionTabTreatment.Underline)
                }
            }
        } else if (state.catalogYear != null || state.catalogTag.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimeQuietLabel(listOfNotNull(state.catalogYear?.toString(), state.catalogTag.takeIf { it.isNotBlank() })
                    .joinToString(" · "), Modifier.weight(1f))
                MotionIconButton(onClick = { controller.setCatalogFilters(null, "") }, modifier = Modifier.size(44.dp),
                    tooltip = "清除筛选") { Icon(Icons.Outlined.Close, "清除筛选", Modifier.size(16.dp)) }
            }
        }
        Box(Modifier.weight(1f)) {
            AnimeDiscoveryGrid(state, scroll, onOpen,
                onRetry = { if (state.query.isBlank()) controller.discover(state.browseMode) else controller.search(state.query) },
                onSearch = onSearch, onLoadMore = controller::loadMoreCatalog)
        }
    }
    MotionDialog(filtersVisible, onDismissRequest = { filtersVisible = false }, maximumWidth = 480.dp) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimeSectionTitle("筛选番剧", action = {
                    MotionIconButton(onClick = { filtersVisible = false }, modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                })
                AnimeTextField(yearText, { yearText = it; filterError = null }, "年份，如 2026；留空不限")
                AnimeTextField(tagText, { tagText = it }, "标签，如 奇幻；留空不限")
                if (filterError != null) Text(filterError.orEmpty(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotionTextButton(onClick = { yearText = ""; tagText = ""; filterError = null },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("清空") }
                    MotionButton(onClick = {
                        val year = yearText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        if (yearText.isNotBlank() && (year == null || yearText.trim().length != 4 || year <= 0))
                            filterError = "请输入四位年份，或留空。"
                        else { controller.setCatalogFilters(year, tagText.trim()); filtersVisible = false }
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("应用筛选") }
                }
            }
        }
    }
}
