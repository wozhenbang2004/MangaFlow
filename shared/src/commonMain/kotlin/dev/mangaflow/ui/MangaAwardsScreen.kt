package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.MangaAward
import dev.mangaflow.data.MangaAwardEntry
import dev.mangaflow.data.MangaAwardsCatalog
import dev.mangaflow.data.RankingSite
import dev.mangaflow.data.SiteRankedWork
import dev.mangaflow.data.SiteRankingFilters
import dev.mangaflow.data.SiteRankingKind
import dev.mangaflow.data.SiteRankingSelection
import dev.mangaflow.data.SiteRankingsRepository
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Award browsing shares the native catalogue chrome; an award never becomes a synthetic subject. */
@Composable
internal fun MangaAwardsScreen(
    repository: SiteRankingsRepository,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    active: Boolean,
    onSite: (RankingSite) -> Unit,
    onBack: () -> Unit,
    onOpen: (SiteRankedWork) -> Unit,
) {
    var awardId by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(MangaAwardsCatalog.years().firstOrNull()) }
    val award = MangaAward.entries.firstOrNull { it.id == awardId }
    val years = remember(award) { MangaAwardsCatalog.years(award) }
    val selection = AwardCatalogueSelection(award, year)
    val positions = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    var siteMenu by remember { mutableStateOf(false) }
    var sitePresent by remember { mutableStateOf(false) }
    var requestedSite by remember { mutableStateOf<RankingSite?>(null) }
    var yearMenu by remember { mutableStateOf(false) }
    var yearPresent by remember { mutableStateOf(false) }
    var menuYear by remember { mutableStateOf(year) }
    var infoVisible by remember { mutableStateOf(false) }
    var infoPresent by remember { mutableStateOf(false) }
    var lookupEntry by remember { mutableStateOf<MangaAwardEntry?>(null) }
    var lookupVisible by remember { mutableStateOf(false) }
    var lookupPresent by remember { mutableStateOf(false) }
    var requestedWork by remember { mutableStateOf<SiteRankedWork?>(null) }
    val modalPresent = infoVisible || infoPresent || lookupVisible || lookupPresent
    val contentReady = active && !modalPresent && !siteMenu && !sitePresent && !yearMenu && !yearPresent

    LaunchedEffect(active) {
        if (!active) {
            focus.clearFocus(force = true)
            requestedSite = null
            requestedWork = null
            siteMenu = false
            yearMenu = false
            infoVisible = false
            lookupVisible = false
        }
    }
    BackHandler(active && !modalPresent) {
        focus.clearFocus(force = true)
        when {
            siteMenu -> siteMenu = false
            yearMenu -> yearMenu = false
            sitePresent || yearPresent -> Unit
            else -> onBack()
        }
    }

    RankingPageFrame(Modifier.fillMaxSize().background(colors.background.copy(alpha = .97f))
        .semantics { paneTitle = "年度获奖" }, header = {
        Column {
            PageTitleHeader("年度获奖", backdrop,
                navigation = { Box(Modifier.motionInputGate(contentReady)) { navigation() } },
                modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                summary = {
                    Box {
                        MotionTextButton(onClick = {
                            focus.clearFocus(force = true)
                            siteMenu = !siteMenu
                            yearMenu = false
                        }, enabled = active && !modalPresent && !yearPresent && requestedSite == null,
                            contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.semantics {
                                stateDescription = if (siteMenu) "榜单菜单已展开" else "榜单菜单已收起"
                            }) {
                            Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(17.dp))
                            Text("年度获奖", Modifier.weight(1f, fill = false).padding(start = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ExpandMore, "选择榜单", Modifier.padding(start = 4.dp).size(17.dp))
                        }
                        CompactGlassPopover(siteMenu && active && !modalPresent, backdrop,
                            onDismiss = { siteMenu = false }, alignToStart = true,
                            maximumWidth = 208.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.4f),
                            onPresenceChange = { present ->
                                sitePresent = present
                                if (!present) {
                                    val next = requestedSite
                                    requestedSite = null
                                    if (active && next != null) {
                                        focus.clearFocus(force = true)
                                        onSite(next)
                                    }
                                }
                            }) {
                            Column(Modifier.selectableGroup()) {
                                RankingSite.entries.forEach { site ->
                                    RankingMenuChoice(site.title, false, active && siteMenu, sourceId = site.id) {
                                        requestedSite = site
                                        siteMenu = false
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.EmojiEvents, null,
                                        Modifier.padding(start = 10.dp).size(18.dp), tint = colors.onSurfaceVariant)
                                    Box(Modifier.weight(1f)) {
                                        RankingMenuChoice("年度获奖", true, active && siteMenu) { siteMenu = false }
                                    }
                                }
                            }
                        }
                    }
                }, actions = {
                    MotionIconButton(onClick = {
                        focus.clearFocus(force = true)
                        infoPresent = true
                        infoVisible = true
                    }, enabled = contentReady, modifier = Modifier.size(44.dp), tooltip = "获奖目录说明") {
                        Icon(Icons.Outlined.Info, "获奖目录说明", Modifier.size(19.dp))
                    }
                })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val width = maxWidth.coerceAtLeast(272.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        MotionTabs(selected = (award?.ordinal ?: -1) + 1,
                            labels = listOf("全部", "漫画大赏", "小学馆", "讲谈社"),
                            onSelect = { index ->
                                if (contentReady) {
                                    val next = MangaAward.entries.getOrNull(index - 1)
                                    awardId = next?.id
                                    val available = MangaAwardsCatalog.years(next)
                                    val selectedYear = year
                                    if (selectedYear != null && selectedYear !in available) year = available.firstOrNull()
                                }
                            }, modifier = Modifier.width(width).motionInputGate(contentReady),
                            treatment = MotionTabTreatment.Capsule, fontSize = 12.sp, controlHeight = 44.dp)
                    }
                }
                Box {
                    MotionTextButton(onClick = {
                        focus.clearFocus(force = true)
                        if (!yearMenu) menuYear = year
                        yearMenu = !yearMenu
                        siteMenu = false
                    }, enabled = active && !modalPresent && !sitePresent,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(year?.toString() ?: "历年", style = MaterialTheme.typography.bodySmall)
                        Icon(Icons.Outlined.ExpandMore, "选择获奖年份", Modifier.padding(start = 3.dp).size(16.dp))
                    }
                    CompactGlassPopover(yearMenu && active && !modalPresent, backdrop,
                        onDismiss = { yearMenu = false }, maximumWidth = 180.dp,
                        onPresenceChange = { yearPresent = it }) {
                        Column(Modifier.selectableGroup()) {
                            RankingMenuChoice("全部年份", menuYear == null, active && yearMenu) {
                                year = null
                                yearMenu = false
                            }
                            years.forEach { value ->
                                RankingMenuChoice(value.toString(), menuYear == value, active && yearMenu) {
                                    year = value
                                    yearMenu = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }) {
        MotionTabContent(selection, Modifier.fillMaxSize(), contentKey = { it.key },
            order = { (it.award?.ordinal ?: -1) * 10000 - (it.year ?: 0) }) { pane ->
            positions.SaveableStateProvider(pane.key) {
                val rows = remember(pane) {
                    MangaAwardsCatalog.entries.filter {
                        (pane.award == null || it.award == pane.award) && (pane.year == null || it.year == pane.year)
                    }.sortedWith(compareByDescending<MangaAwardEntry> { it.year }.thenBy { it.award.ordinal })
                }
                LazyColumn(state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop)
                        .motionInputGate(contentReady && pane == selection),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp,
                        bottom = 24.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { it.key }, contentType = { "award-work" }) { entry ->
                        MangaAwardRow(entry, contentReady && pane == selection) {
                            lookupEntry = entry
                            requestedWork = null
                            lookupPresent = true
                            lookupVisible = true
                        }
                    }
                    if (rows.isEmpty()) item("empty-awards") {
                        EmptyMessage("当前年份尚未收录", "可以选择其他年份，或查看目录说明。")
                    }
                }
            }
        }
    }

    MotionDialog(infoVisible && active, onDismissRequest = { infoVisible = false },
        maximumWidth = 360.dp, onClosed = { if (!infoVisible) infoPresent = false }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, softCard = true) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("获奖目录", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
                    MotionIconButton(onClick = { infoVisible = false }, modifier = Modifier.size(44.dp), tooltip = "关闭说明") {
                        Icon(Icons.Outlined.Close, "关闭说明", Modifier.size(18.dp))
                    }
                }
                Text(MangaAwardsCatalog.coverageNote, Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("年份指获奖公布年，与作品首发年份不同。这里保留正式获奖作品，不含提名或授予个人的奖项。",
                    Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                Text("资料核对 · " + MangaAwardsCatalog.verifiedAt, Modifier.padding(bottom = 10.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MangaAward.entries.forEach { value ->
                    Text(value.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(value.home, Modifier.padding(top = 3.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
    MangaAwardLookupDialog(lookupEntry, lookupVisible, active, repository, backdrop,
        onDismiss = { requestedWork = null; lookupVisible = false },
        onSelect = { work -> requestedWork = work; lookupVisible = false },
        onClosed = {
            if (!lookupVisible) {
                lookupPresent = false
                lookupEntry = null
                val work = requestedWork
                requestedWork = null
                if (active && work != null) onOpen(work)
            }
        })
}

private data class AwardCatalogueSelection(val award: MangaAward?, val year: Int?) {
    val key: String get() = (award?.id ?: "all") + ":" + (year?.toString() ?: "all")
}

@Composable
private fun MangaAwardRow(entry: MangaAwardEntry, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
        indication = null, onClick = onClick).motionPress(interaction, enabled, pressedScale = .985f)
        .padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.EmojiEvents, null, Modifier.padding(end = 12.dp).size(21.dp),
            tint = colors.onSurfaceVariant.copy(alpha = .65f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.authors.joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.year} · ${entry.award.title} · ${entry.category}",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, "查找作品", Modifier.padding(start = 8.dp).size(18.dp),
            tint = colors.onSurfaceVariant.copy(alpha = .6f))
    }
}

private data class AwardLookupQuery(val awardKey: String, val site: RankingSite, val query: String, val revision: Int)
private data class AwardLookupSnapshot(
    val works: List<SiteRankedWork> = emptyList(),
    val page: Int = -1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/** User-selected real catalogue results continue through the existing readable-source association flow. */
@Composable
private fun MangaAwardLookupDialog(
    entry: MangaAwardEntry?,
    visible: Boolean,
    active: Boolean,
    repository: SiteRankingsRepository,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onSelect: (SiteRankedWork) -> Unit,
    onClosed: () -> Unit,
) {
    var site by remember(entry?.key) { mutableStateOf(RankingSite.BANGUMI) }
    var query by remember(entry?.key) { mutableStateOf(entry?.title.orEmpty()) }
    var submitted by remember(entry?.key) { mutableStateOf(entry?.title.orEmpty()) }
    var revision by remember(entry?.key) { mutableIntStateOf(0) }
    var provenance by remember(entry?.key) { mutableStateOf(false) }
    val identity = AwardLookupQuery(entry?.key.orEmpty(), site, submitted, revision)
    // Each query owns different state objects, so a cancelled old response cannot paint a new query.
    var snapshot by remember(identity) { mutableStateOf(AwardLookupSnapshot()) }
    var requestedPage by remember(identity) { mutableIntStateOf(0) }
    var retry by remember(identity) { mutableIntStateOf(0) }
    val open = visible && active && entry != null
    val selection = remember(site, submitted) {
        SiteRankingSelection(site, SiteRankingKind.SCORE, site.scopes.first(),
            SiteRankingFilters(query = submitted)).normalized()
    }

    LaunchedEffect(visible, active, entry?.key, identity, requestedPage, retry) {
        if (!open || submitted.isBlank()) return@LaunchedEffect
        snapshot = snapshot.copy(loading = true, error = null)
        try {
            val result = repository.page(selection, requestedPage)
            currentCoroutineContext().ensureActive()
            val works = result.entries.map { it.work }
            snapshot = AwardLookupSnapshot(
                works = (if (requestedPage == 0) works else snapshot.works + works).distinctBy { it.key },
                page = requestedPage, hasMore = result.hasMore,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            snapshot = snapshot.copy(loading = false,
                error = failure.message?.take(240) ?: "暂时无法读取，可重试或更换站点。")
        }
    }

    MotionDialog(open, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 560.dp,
        onClosed = onClosed) {
        val shown = entry ?: return@MotionDialog
        val colors = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        val listState = rememberLazyListState()
        LaunchedEffect(identity) { listState.scrollToItem(0) }
        val dismiss: () -> Unit = { focus.clearFocus(force = true); onDismiss() }
        val submit: () -> Unit = {
            if (open && query.isNotBlank()) {
                focus.clearFocus(force = true)
                submitted = query.trim()
                revision++
            }
        }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().height(minOf(maxHeight, 640.dp)),
                cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { paneTitle = "查找获奖作品" }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        Text("查找作品", Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        MotionIconButton(onClick = { provenance = !provenance }, enabled = open,
                            modifier = Modifier.size(44.dp), tooltip = "查看获奖出处") {
                            Icon(Icons.Outlined.Info, "查看获奖出处", Modifier.size(18.dp))
                        }
                        MotionIconButton(onClick = dismiss, enabled = open, modifier = Modifier.size(44.dp), tooltip = "关闭查找") {
                            Icon(Icons.Outlined.Close, "关闭查找", Modifier.size(19.dp))
                        }
                    }
                    // The header and search controls scroll with results when the keyboard reduces available height.
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        item("award-original") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(shown.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(shown.authors.joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                                Text("${shown.year} · ${shown.award.title} · ${shown.category}",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                if (provenance) {
                                    Text("${shown.edition?.let { "第${it}届 · " }.orEmpty()}核对于 ${MangaAwardsCatalog.verifiedAt}",
                                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    SelectionContainer {
                                        Text(shown.sourceUrl, style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item("lookup-controls") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val width = maxWidth.coerceAtLeast(324.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                                        MotionTabs(site.ordinal, RankingSite.entries.map { it.title }, onSelect = { index ->
                                            if (open) {
                                                focus.clearFocus(force = true)
                                                site = RankingSite.entries[index]
                                            }
                                        }, modifier = Modifier.width(width).motionInputGate(open),
                                            treatment = MotionTabTreatment.Capsule, controlHeight = 44.dp, fontSize = 12.sp)
                                    }
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(query, onValueChange = { query = it.take(300) }, enabled = open,
                                        modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                                            .semantics { contentDescription = "搜索书名，可修改后重新搜索" },
                                        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                        cursorBrush = SolidColor(colors.onSurface),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { submit() }))
                                    MotionIconButton(onClick = submit, enabled = open && query.isNotBlank(),
                                        modifier = Modifier.size(44.dp), tooltip = "搜索该站目录") {
                                        Icon(Icons.Outlined.Search, "搜索该站目录", Modifier.size(19.dp))
                                    }
                                }
                                Text("请选择对应作品，再查看阅读来源。", style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant)
                                if (query.trim() != submitted) Text("当前结果 · $submitted",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        items(snapshot.works, key = { "catalogue:" + it.key }, contentType = { "award-candidate" }) { work ->
                            AwardCatalogueCandidateRow(work, open) {
                                if (open && snapshot.works.any { it.key == work.key }) {
                                    focus.clearFocus(force = true)
                                    onSelect(work)
                                }
                            }
                        }
                        item("lookup-footer") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                when {
                                    snapshot.loading -> MotionBusyIndicator(Modifier.padding(10.dp).size(20.dp))
                                    snapshot.error != null -> {
                                        Text(snapshot.error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                            color = colors.error)
                                        MotionTextButton(onClick = { retry++ }, enabled = open) { Text("重试") }
                                    }
                                    snapshot.works.isEmpty() && snapshot.page >= 0 -> Text(
                                        if (snapshot.hasMore) "当前页没有条目，可继续读取。" else "未找到对应条目，可修改书名或更换站点。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                if (snapshot.hasMore && !snapshot.loading && snapshot.error == null) {
                                    if (snapshot.page < 199) MotionTextButton(onClick = {
                                        requestedPage = snapshot.page + 1
                                    }, enabled = open) { Text("更多结果") }
                                    else Text("已到该站分页范围，可缩小搜索词。", style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AwardCatalogueCandidateRow(work: SiteRankedWork, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
        indication = null, onClick = onClick).motionPress(interaction, enabled, pressedScale = .985f)
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        CatalogueCover(work.manga, Modifier.width(44.dp).height(64.dp).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(work.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (work.manga.authors.isNotEmpty()) Text(work.manga.authors.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(work.site.title, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, "选择这部作品", Modifier.padding(start = 7.dp).size(18.dp),
            tint = colors.onSurfaceVariant)
    }
}
