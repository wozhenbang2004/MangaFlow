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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
    onBack: () -> Unit,
    onOpen: (SiteRankedWork) -> Unit,
    sectionTabs: (@Composable () -> Unit)? = null,
) {
    var awardId by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(MangaAwardsCatalog.years().firstOrNull()) }
    val award = MangaAward.entries.firstOrNull { it.id == awardId }
    val years = remember(award) { MangaAwardsCatalog.years(award) }
    val selection = AwardCatalogueSelection(award, year)
    val positions = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val pageTitle = if (sectionTabs != null) "漫画榜单" else "年度获奖"
    var filterMenu by remember { mutableStateOf<AwardFilterMenu?>(null) }
    var awardPresent by remember { mutableStateOf(false) }
    var yearPresent by remember { mutableStateOf(false) }
    var menuAwardId by remember { mutableStateOf(awardId) }
    var menuYear by remember { mutableStateOf(year) }
    var infoVisible by remember { mutableStateOf(false) }
    var infoPresent by remember { mutableStateOf(false) }
    var lookupEntry by remember { mutableStateOf<MangaAwardEntry?>(null) }
    var lookupVisible by remember { mutableStateOf(false) }
    var lookupPresent by remember { mutableStateOf(false) }
    var requestedWork by remember { mutableStateOf<SiteRankedWork?>(null) }
    val modalPresent = infoVisible || infoPresent || lookupVisible || lookupPresent
    val contentReady = active && !modalPresent && filterMenu == null && !awardPresent && !yearPresent

    LaunchedEffect(active) {
        if (!active) {
            focus.clearFocus(force = true)
            requestedWork = null
            filterMenu = null
            infoVisible = false
            lookupVisible = false
        }
    }
    BackHandler(active && !modalPresent) {
        focus.clearFocus(force = true)
        when {
            filterMenu != null -> filterMenu = null
            awardPresent || yearPresent -> Unit
            else -> onBack()
        }
    }

    RankingPageFrame(Modifier.fillMaxSize().background(colors.background.copy(alpha = .97f))
        .semantics { paneTitle = pageTitle }, header = {
        Column {
            PageTitleHeader(pageTitle, backdrop,
                navigation = { Box(Modifier.motionInputGate(contentReady)) { navigation() } },
                modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                summary = {
                    Text(pageTitle, Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, actions = {
                    if (sectionTabs == null) MotionIconButton(onClick = {
                        focus.clearFocus(force = true)
                        onBack()
                    }, enabled = contentReady, modifier = Modifier.size(44.dp), tooltip = "返回榜单") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(19.dp))
                    }
                    MotionIconButton(onClick = {
                        focus.clearFocus(force = true)
                        infoPresent = true
                        infoVisible = true
                    }, enabled = contentReady, modifier = Modifier.size(44.dp), tooltip = "获奖目录说明") {
                        Icon(Icons.Outlined.Info, "获奖目录说明", Modifier.size(19.dp))
                    }
                })
            if (sectionTabs != null) Box(Modifier.motionInputGate(contentReady)) { sectionTabs() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val awardMenu = filterMenu == AwardFilterMenu.AWARD && active && !modalPresent
                val yearMenu = filterMenu == AwardFilterMenu.YEAR && active && !modalPresent
                AwardFilterControl("奖项", award?.title ?: "全部奖项", backdrop, awardMenu,
                    modifier = Modifier.weight(1f, fill = false),
                    enabled = contentReady || awardMenu, onPresence = { awardPresent = it },
                    onDismiss = { if (awardMenu) filterMenu = null },
                    onToggle = {
                        focus.clearFocus(force = true)
                        if (!awardMenu) menuAwardId = awardId
                        filterMenu = if (awardMenu) null else AwardFilterMenu.AWARD
                    }) {
                    RankingMenuChoice("全部奖项", menuAwardId == null, awardMenu) {
                        awardId = null
                        filterMenu = null
                    }
                    MangaAward.entries.forEach { next ->
                        RankingMenuChoice(next.title, menuAwardId == next.id, awardMenu) {
                            awardId = next.id
                            val available = MangaAwardsCatalog.years(next)
                            val selectedYear = year
                            if (selectedYear != null && selectedYear !in available) year = available.firstOrNull()
                            filterMenu = null
                        }
                    }
                }
                AwardFilterControl("公布年份", year?.let { "${it} 年公布" } ?: "全部年份", backdrop, yearMenu,
                    modifier = Modifier.weight(1f, fill = false),
                    enabled = contentReady || yearMenu, onPresence = { yearPresent = it },
                    onDismiss = { if (yearMenu) filterMenu = null },
                    onToggle = {
                        focus.clearFocus(force = true)
                        if (!yearMenu) menuYear = year
                        filterMenu = if (yearMenu) null else AwardFilterMenu.YEAR
                    }) {
                    RankingMenuChoice("全部年份", menuYear == null, yearMenu) {
                        year = null
                        filterMenu = null
                    }
                    years.forEach { value ->
                        RankingMenuChoice(value.toString(), menuYear == value, yearMenu) {
                            year = value
                            filterMenu = null
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
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    rows.groupBy { it.year }.forEach { (announcementYear, yearRows) ->
                        if (pane.year == null) item(key = "award-year:$announcementYear", contentType = "award-year") {
                            Text("${announcementYear} 年公布", Modifier.padding(top = 10.dp, bottom = 4.dp)
                                .semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        }
                        yearRows.groupBy { it.award to it.edition }.forEach { (group, works) ->
                            val (source, edition) = group
                            if (pane.award == null || edition != null) item(
                                key = "award-group:$announcementYear:${source.id}:$edition", contentType = "award-group",
                            ) {
                                MangaAwardGroupHeading(source.takeIf { pane.award == null }, edition)
                            }
                            items(works, key = { it.key }, contentType = { "award-work" }) { entry ->
                                MangaAwardRow(entry, contentReady && pane == selection) {
                                    lookupEntry = entry
                                    requestedWork = null
                                    lookupPresent = true
                                    lookupVisible = true
                                }
                            }
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

private enum class AwardFilterMenu { AWARD, YEAR }

@Composable
private fun AwardFilterControl(label: String, value: String, backdrop: HazeState, visible: Boolean,
    enabled: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit, onPresence: (Boolean) -> Unit,
    modifier: Modifier = Modifier, choices: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        MotionTextButton(onClick = onToggle, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "$label，$value" }) {
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
        }
        CompactGlassPopover(visible, backdrop, onDismiss, onPresenceChange = onPresence,
            alignToStart = true, maximumWidth = 232.dp) {
            Column(Modifier.selectableGroup(), content = choices)
        }
    }
}

@Composable
private fun MangaAwardGroupHeading(award: MangaAward?, edition: Int?) {
    val editionLabel = edition?.let { "第${it}届" }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(award?.title ?: editionLabel.orEmpty(), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (award != null && editionLabel != null) Text(editionLabel,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MangaAwardRow(entry: MangaAwardEntry, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val category = entry.category.takeUnless { it == "获奖作品" || entry.award == MangaAward.MANGA_TAISHO && it == "大赏" }
    val summary = listOfNotNull(category, entry.authors.joinToString(" / ").takeIf { it.isNotBlank() })
        .joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
        indication = null, onClickLabel = "查找作品", onClick = onClick).motionPress(interaction, enabled, pressedScale = .985f)
        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 8.dp).size(18.dp),
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
                        Text("查找作品", Modifier.weight(1f),
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
