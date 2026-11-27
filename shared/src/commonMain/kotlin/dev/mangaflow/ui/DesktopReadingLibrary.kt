package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.platform.localToday
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopResumeShelf(
    recent: List<ReadingProgress>, repository: MangaRepository, active: Boolean,
    onContinue: (ReadingProgress) -> Unit, onDetails: (Manga) -> Unit, onHistory: (Manga) -> Unit,
) {
    val latest = recent.firstOrNull() ?: return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val split = recent.size > 1 && maxWidth >= 660.dp * density.fontScale.coerceAtLeast(1f)
        val gap = 22.dp
        val secondaryWidth = if (split) (maxWidth - gap) / 2.35f else maxWidth
        val totalWidthPx = constraints.maxWidth
        val leadWidth = if (split) with(density) {
            (totalWidthPx - gap.roundToPx() - secondaryWidth.roundToPx()).toDp()
        } else maxWidth
        // Keep the same book and menu owners as the secondary list moves below the lead.
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (split) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(leadWidth).align(Alignment.CenterVertically)
                .background(Brush.horizontalGradient(listOf(lerp(colors.surface, colors.secondaryContainer, .43f),
                    colors.surfaceContainerLow)), RoundedCornerShape(18.dp)).padding(16.dp)) {
                key(latest.manga.sourceId, latest.manga.id) {
                    DesktopContextMenuArea(if (active) listOf(
                        DesktopMenuAction("章节记录", icon = Icons.Outlined.History) { onHistory(latest.manga) },
                        DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList) { onDetails(latest.manga) })
                        else emptyList()) {
                        DesktopReadingLead(latest, repository, active, onContinue, onDetails, prominent = true,
                            onHistory = { onHistory(latest.manga) })
                    }
                }
            }
            if (recent.size > 1) Column(Modifier.width(secondaryWidth).align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("最近还读过", Modifier.padding(start = 4.dp, bottom = 3.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                recent.drop(1).forEach { progress -> key(progress.manga.sourceId, progress.manga.id) {
                    DesktopContextMenuArea(if (active) listOf(
                        DesktopMenuAction("继续阅读", icon = Icons.Outlined.AutoStories) { onContinue(progress) },
                        DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList) { onDetails(progress.manga) },
                        DesktopMenuAction("章节记录", icon = Icons.Outlined.History) { onHistory(progress.manga) })
                        else emptyList()) {
                        ContinueRow(progress, repository, { if (active) onContinue(it) }, namedHistory = true,
                            onHistory = { if (active) onHistory(progress.manga) })
                    }
                } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopReadingLead(
    progress: ReadingProgress, repository: MangaRepository, active: Boolean,
    onContinue: (ReadingProgress) -> Unit, onDetails: ((Manga) -> Unit)?, prominent: Boolean = false,
    onHistory: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 318.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val coverWidth = when {
            !prominent || narrow -> 68.dp
            maxWidth < 440.dp -> 88.dp
            else -> 108.dp
        }.coerceAtMost(maxWidth * .27f)
        val cover: @Composable () -> Unit = {
            MangaCover(progress.manga, repository, Modifier.width(coverWidth).aspectRatio(.70f)
                .mangaCoverCard(8.dp, shadowRadius = 5.dp))
        }
        val information: @Composable () -> Unit = {
            Text(progress.manga.title, style = if (prominent) browseTitleStyle(24.sp, 30.sp)
                else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(mangaNumberText(recentReadingLocation(progress)), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val fraction = detailReadingFraction(progress)
            if (prominent && fraction != null) Box(Modifier.widthIn(max = 160.dp).fillMaxWidth().height(3.dp)
                .clip(RoundedCornerShape(50)).background(colors.onSurface.copy(alpha = .09f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary.copy(alpha = .8f)))
            }
        }
        val actions: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MotionButton(onClick = { onContinue(progress) }, enabled = active, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(17.dp))
                    Text("继续阅读", Modifier.padding(start = 5.dp), maxLines = 1)
                }
                if (onDetails != null) MotionTextButton(onClick = { onDetails(progress.manga) }, enabled = active,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) { Text("详情", maxLines = 1) }
                if (onHistory != null) MotionIconButton(onClick = onHistory, enabled = active,
                    modifier = Modifier.size(36.dp), tooltip = "章节记录") {
                    Icon(Icons.Outlined.History, "章节记录", Modifier.size(17.dp))
                }
            }
        }
        val currentActions by rememberUpdatedState(actions)
        val placeActions = remember { movableContentOf { currentActions() } }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (prominent) 18.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                cover()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (prominent) 7.dp else 3.dp)) {
                    information()
                    if (!narrow) placeActions()
                }
            }
            if (narrow) placeActions()
        }
    }
}

private data class HistorySelection(val key: String, val positions: Map<String, Int>)

/** The selected book and its chapter history use the same scoped local record. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopReadingHistory(
    history: List<ReadingProgress>, repository: MangaRepository, library: LibraryStore, listState: LazyListState,
    active: Boolean, onContinue: (ReadingProgress) -> Unit, onRemove: (String) -> Unit,
    modifier: Modifier, selection: String?, onSelect: (String) -> Unit,
    onDetails: ((Manga) -> Unit)? = null, warmCovers: Boolean = false, header: (@Composable () -> Unit)? = null,
) {
    val positions = remember(history) {
        buildMap<String, Int>(history.size) {
            history.forEachIndexed { index, record -> put(record.historyBookKey(), index) }
        }
    }
    var previous by remember { mutableStateOf<HistorySelection?>(null, referentialEqualityPolicy()) }
    val before = previous
    val retainedIndex = before?.let { positions[it.key] }
    val previousIndex = before?.let { it.positions[it.key] } ?: 0
    val selectedIndex = if (selection == null) 0
        else positions[selection] ?: retainedIndex ?: previousIndex.coerceAtMost(history.lastIndex)
    val selectedRecord = history.getOrNull(selectedIndex) ?: return
    val selectedKey = selectedRecord.historyBookKey()
    // If filtering or removal hid the previous book, compare both books in the old list.
    val direction = when {
        before == null || before.key == selectedKey -> 0
        retainedIndex != null -> selectedIndex.compareTo(retainedIndex)
        else -> before.positions[selectedKey]?.compareTo(previousIndex) ?: 0
    }
    SideEffect {
        if (before == null || before.key != selectedKey || before.positions !== positions)
            previous = HistorySelection(selectedKey, positions)
    }
    val colors = MaterialTheme.colorScheme
    val today = localToday()
    val groups = remember(history) { readingHistoryByDay(history) }
    HistoryCoverWarmup(groups, repository, active && warmCovers, listState) { "work:" + it.sourceId + ":" + it.id }
    val focus = LocalFocusManager.current
    val motion = LocalMotionEnabled.current
    val placement = if (motion) spring<IntOffset>(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(selectedKey, motion, active) {
        if (direction == 0 || !motion || !active) {
            arrival.snapTo(0f)
        } else {
            arrival.snapTo(direction.toFloat())
            arrival.animateTo(0f, tween(MangaMotion.Content, easing = MangaMotion.EnterEasing))
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(.38f).fillMaxHeight().background(colors.surfaceContainerLow.copy(alpha = .82f),
            RoundedCornerShape(15.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState,
                contentPadding = PaddingValues(bottom = 16.dp)) {
                if (header != null) item(key = "history-notice") { header() }
                groups.forEach { (date, records) ->
                    item(key = "date:$date") {
                        Text(readingHistoryDay(date, today), Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                            .padding(start = 10.dp, top = 10.dp, bottom = 7.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                    items(records, key = { "work:" + it.historyBookKey() }) { record ->
                        val chosen = record.historyBookKey() == selectedKey
                        val interaction = remember { MutableInteractionSource() }
                        var resumeKey by remember { mutableStateOf<Key?>(null) }
                        val select = { onSelect(record.historyBookKey()) }
                        val selectionColor = animateColorAsState(
                            if (chosen) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f),
                            if (motion) tween(MangaMotion.Quick) else snap(), label = "history-row-selection")
                        val actions = if (!active) emptyList() else buildList {
                            add(DesktopMenuAction("继续阅读", icon = Icons.Outlined.AutoStories) { onContinue(record) })
                            onDetails?.let { open -> add(DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList) { open(record.manga) }) }
                            add(DesktopMenuAction("移除阅读记录", icon = Icons.Outlined.DeleteOutline, destructive = true) { onRemove(record.manga.id) })
                        }
                        Box(Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)) {
                            DesktopContextMenuArea(actions) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                    .drawBehind { drawRect(selectionColor.value) }
                                    .semantics { this.selected = chosen }
                                    .onFocusChanged { if (it.isFocused && active) select() else resumeKey = null }
                                    .onPreviewKeyEvent { event ->
                                        if (!active || event.isCtrlPressed || event.isAltPressed ||
                                            event.isShiftPressed || event.isMetaPressed) { resumeKey = null; false } else when (event.key) {
                                            Key.Enter, Key.NumPadEnter -> {
                                                if (event.type == KeyEventType.KeyDown) resumeKey = event.key
                                                else if (event.type == KeyEventType.KeyUp && resumeKey == event.key) {
                                                    resumeKey = null
                                                    onContinue(record)
                                                }
                                                true
                                            }
                                            Key.DirectionUp -> event.type == KeyEventType.KeyDown && focus.moveFocus(FocusDirection.Up)
                                            Key.DirectionDown -> event.type == KeyEventType.KeyDown && focus.moveFocus(FocusDirection.Down)
                                            else -> false
                                        }
                                    }.combinedClickable(enabled = active, interactionSource = interaction, indication = null,
                                        role = Role.Button, onClickLabel = "查看章节记录", onClick = select,
                                        onDoubleClick = { onContinue(record) }).motionPress(interaction, active, .99f)
                                    .padding(horizontal = 9.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MangaCover(record.manga, repository, Modifier.size(38.dp, 53.dp).mangaCoverCard(5.dp, shadowRadius = 2.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(record.manga.title, style = MaterialTheme.typography.titleSmall,
                                            color = if (chosen) colors.onPrimaryContainer else colors.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(mangaNumberText(recentReadingLocation(record)), style = MaterialTheme.typography.labelSmall,
                                            color = if (chosen) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            DesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
        // One chapter-history loader owns the visible work, including halfway through a direction change.
        Box(Modifier.weight(.62f).fillMaxHeight().clipToBounds()) {
            key(selectedKey) {
                Column(Modifier.fillMaxSize().graphicsLayer {
                    val displacement = if (motion) arrival.value else 0f
                    translationY = displacement * 9.dp.toPx()
                    alpha = 1f - .16f * abs(displacement)
                }) {
                    DesktopReadingLead(selectedRecord, repository, active, onContinue, onDetails)
                    Spacer(Modifier.height(15.dp))
                    ChapterHistoryContent(selectedRecord.manga, library, onContinue = { if (active) onContinue(it) }, embedded = true)
                }
            }
        }
    }
}
