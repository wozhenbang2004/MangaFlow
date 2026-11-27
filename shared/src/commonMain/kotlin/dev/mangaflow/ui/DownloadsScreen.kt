package dev.mangaflow.ui

import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperationPhase
import dev.mangaflow.background.UserOperationKeys
import dev.mangaflow.background.LocalImportInput as LocalImportSource
import dev.mangaflow.background.importLocal
import androidx.compose.runtime.DisposableEffect

import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.only

import androidx.compose.foundation.layout.asPaddingValues

import kotlinx.coroutines.IO

import dev.mangaflow.ui.platform.*

import dev.mangaflow.ui.motion.BackHandler

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.data.Manga
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.ReaderChapterEnhancementTask
import dev.mangaflow.enhancement.ui.EnhancementCenterPanel
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.localPdfImportSupported
import dev.mangaflow.storage.ChapterPreparationPhase
import dev.mangaflow.storage.ChapterPreparationState
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) {
    ALL("全部"), ACTIVE("未完成"), SAVED("已下载");

    fun includes(saved: Boolean): Boolean = when (this) {
        ALL -> true
        ACTIVE -> !saved
        SAVED -> saved
    }
}

/** A desktop inspection is of one visible download, not a work, chapter name or reusable row ID. */
private data class DesktopDownloadInspection(
    val filter: DownloadFilter, val id: String, val mangaId: String, val chapterId: String, val createdAt: Long,
) {
    constructor(filter: DownloadFilter, entry: DownloadEntry) :
        this(filter, entry.id, entry.manga.id, entry.chapter.id, entry.createdAt)

    fun matches(entry: DownloadEntry): Boolean = id == entry.id && mangaId == entry.manga.id &&
        chapterId == entry.chapter.id && createdAt == entry.createdAt &&
        filter.includes(entry.status == DownloadStatus.COMPLETED)
}

// Layout depends on membership/order/completion, never on page or byte progress.
private data class DownloadDirectoryEntry(
    val id: String, val mangaId: String, val group: String, val chapterIndex: Int, val saved: Boolean,
)

private data class PendingLocalImport(
    val source: LocalImportSource,
    val mangaTitle: String,
    val chapterTitle: String,
)

@Composable
fun DownloadsScreen(
    store: DownloadStore,
    repository: MangaRepository,
    onRead: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onRetryUsingLatestRoute: (DownloadEntry) -> Unit,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    toolbar: @Composable (@Composable () -> Unit) -> Unit,
    active: Boolean = true,
    adultProfile: Boolean = false,
    refreshingIds: Set<String> = emptySet(),
    history: List<ReadingProgress> = emptyList(),
    wallpaper: @Composable () -> Unit = {},
    backgroundInParent: Boolean = false,
    excludedSourceIds: Set<String> = emptySet(),
    onDetails: ((Manga) -> Unit)? = null,
    includedSourceIds: Set<String>? = null,
    preparations: List<ChapterPreparationState> = emptyList(),
    onCancelPreparation: (String, Long) -> Unit = { _, _ -> },
    onRetryPreparation: (String, Long) -> Unit = { _, _ -> },
    onRetryPreparationUsingLatestRoute: (String, Long) -> Unit,
) {
    fun sourceIncluded(sourceId: String): Boolean =
        (includedSourceIds == null || sourceId in includedSourceIds) && sourceId !in excludedSourceIds
    val localImportAllowed = sourceIncluded("local")
    val desktop = platformKind == PlatformKind.DESKTOP
    val context = LocalContext.current
    val enhancementCenter = remember(context) { EnhancementCenter.get(context) }
    val enhancementContent = if (adultProfile) EnhancementContent.ADULT_MANGA else EnhancementContent.MANGA
    val allEnhancementTasks by enhancementCenter.chapters.state.collectAsStateWithLifecycle()
    val enhancementTasks = remember(allEnhancementTasks, enhancementContent) {
        allEnhancementTasks.filter { it.content == enhancementContent }
    }
    val entries by store.entries.collectAsStateWithLifecycle()
    val loading by store.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val operations = remember(context) { ApplicationOperations.get(context) }
    val operationStates by operations.state.collectAsStateWithLifecycle()
    val importOperation = remember(operationStates) { operations.find(UserOperationKeys.LOCAL_IMPORT) }
    val haptics = dev.mangaflow.ui.motion.rememberFeelFeedback()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var inspectedTaskId by remember { mutableStateOf<String?>(null) }
    // A retained tab/menu callback cannot act after leaving this page or changing content ownership.
    val desktopScope = remember(store, adultProfile, includedSourceIds, excludedSourceIds, active) { Any() }
    var chapterFieldFocused by remember(desktopScope) { mutableStateOf(false) }
    val currentDesktopScope by rememberUpdatedState(desktopScope)
    var desktopInspection by remember(desktopScope) { mutableStateOf<DesktopDownloadInspection?>(null) }
    var desktopQualityReturn by remember(desktopScope) { mutableStateOf<DesktopDownloadInspection?>(null) }
    var removingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeletion by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedBooks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showImportSources by remember { mutableStateOf(false) }
    var showQueueActions by remember { mutableStateOf(false) }
    var queueActionsPresent by remember { mutableStateOf(false) }
    var pendingNetworkSettings by remember { mutableStateOf(false) }
    var showNetworkSettings by remember { mutableStateOf(false) }
    var showEnhancement by remember(if (desktop) desktopScope else Unit) { mutableStateOf(false) }
    var pendingEnhancement by remember(if (desktop) desktopScope else Unit) { mutableStateOf(false) }
    var qualityReturnTaskId by remember { mutableStateOf<String?>(null) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val filterChoices = remember { listOf(
        GlassTabChoice(DownloadFilter.ALL.label, Icons.Outlined.Inventory2),
        GlassTabChoice(DownloadFilter.ACTIVE.label, Icons.Outlined.Downloading),
        GlassTabChoice(DownloadFilter.SAVED.label, Icons.Outlined.CheckCircleOutline),
    ) }
    val preferredFilterWidth = morphGlassTabWidth(filterChoices)
    var pendingImport by remember { mutableStateOf<PendingLocalImport?>(null) }
    val importInputOpen = remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            importInputOpen.value = false
            // Read the actual delegated state: acceptance clears it before navigation/disposal.
            pendingImport?.source?.documents()?.let(::releasePickedDocuments)
        }
    }
    val importBusy = importOperation?.active == true
    var importError by remember { mutableStateOf<String?>(null) }
    val allScroll = rememberLazyListState()
    val activeScroll = rememberLazyListState()
    val savedScroll = rememberLazyListState()
    val showLoading = rememberDelayedBusy(loading)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val contentInsets = screenFitContentInsets().asPaddingValues()
    val topInset = contentInsets.calculateTopPadding()
    val bottomInset = contentInsets.calculateBottomPadding()
    var headerHeightPx by remember(density, topInset) { mutableIntStateOf(with(density) { (48.dp + topInset).roundToPx() }) }
    val contentTopPadding = with(density) { headerHeightPx.toDp() } + HeaderContentGap
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val visibleEntries = remember(entries, includedSourceIds, excludedSourceIds) {
        entries.filter { sourceIncluded(it.manga.sourceId) }
    }
    val visiblePreparations = remember(preparations, includedSourceIds, excludedSourceIds) {
        preparations.filter { sourceIncluded(it.manga.sourceId) }
    }
    val manageableEntries = remember(visibleEntries, removingIds) { visibleEntries.filterNot { it.id in removingIds } }
    val selectedEntries = remember(manageableEntries, selectedIds) { manageableEntries.filter { it.id in selectedIds } }
    val entriesById = remember(visibleEntries) { visibleEntries.associateBy(DownloadEntry::id) }
    val historyById = remember(history, includedSourceIds, excludedSourceIds) {
        history.filter { sourceIncluded(it.manga.sourceId) }.associateBy { it.manga.id }
    }
    val directory = remember(manageableEntries) {
        manageableEntries.map { DownloadDirectoryEntry(it.id, it.manga.id, it.chapter.group,
            it.chapter.index, it.status == DownloadStatus.COMPLETED) }
    }
    val orderedBooks = remember(directory) {
        DownloadFilter.entries.associateWith { page ->
            directory.filter { entry -> page.includes(entry.saved) }
                .groupBy(DownloadDirectoryEntry::mangaId).mapValues { (_, chapters) ->
                    chapters.groupBy(DownloadDirectoryEntry::group).values.flatMap { group ->
                        group.sortedBy(DownloadDirectoryEntry::chapterIndex).map(DownloadDirectoryEntry::id)
                    }
                }
        }
    }
    val shownDesktopInspection = desktopInspection
    val desktopTask = shownDesktopInspection?.takeIf { active && !managing && it.filter == filter }
        ?.let { target -> entriesById[target.id]?.takeIf { it.id !in removingIds && target.matches(it) } }
    LaunchedEffect(shownDesktopInspection, desktopTask != null) {
        if (shownDesktopInspection != null && desktopTask == null && desktopInspection === shownDesktopInspection) {
            desktopInspection = null
        }
    }

    fun resolveDesktopInspection(target: DesktopDownloadInspection): DownloadEntry? {
        if (!active || currentDesktopScope !== desktopScope || managing || target.filter != filter) return null
        // Resolve at the click, not from an outgoing tab or the detail row's previous composition.
        return store.entries.value.firstOrNull { it.id !in removingIds && target.matches(it) && sourceIncluded(it.manga.sourceId) }
    }

    fun clearDesktopInspection() {
        desktopInspection = null
        if (desktopQualityReturn != null) {
            desktopQualityReturn = null
            pendingEnhancement = false
        }
    }

    fun selectFilter(next: DownloadFilter) {
        if (filter != next && desktop) clearDesktopInspection()
        filter = next
    }

    fun inspectDownload(entry: DownloadEntry, shownFilter: DownloadFilter, selectWork: Boolean) {
        if (!desktop) {
            inspectedTaskId = entry.id
            return
        }
        val target = DesktopDownloadInspection(shownFilter, entry)
        if (resolveDesktopInspection(target) == null) return
        if (selectWork) expandedBooks = listOf(target.mangaId)
        desktopInspection = target
    }
    val motion = LocalMotionEnabled.current
    val activeEntries = manageableEntries.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) }
    val pausedEntries = manageableEntries.filter { it.status == DownloadStatus.PAUSED && it.manga.sourceUrl != LOCAL_SOURCE_URL }
    val retryableEntries = manageableEntries.filter { it.status == DownloadStatus.FAILED && it.manga.sourceUrl != LOCAL_SOURCE_URL }
    val completedEntries = manageableEntries.filter { it.status == DownloadStatus.COMPLETED }
    var queueRequest by remember(desktopScope) { mutableStateOf<DownloadQueueRequest?>(null) }
    fun dispatchQueue(command: QueueCommand, candidates: List<DownloadEntry>, retryAction: (DownloadEntry) -> Unit = onRetry) {
        if (!active || desktop && currentDesktopScope !== desktopScope) return
        val requested = candidates.filter { sourceIncluded(it.manga.sourceId) }.distinctBy(DownloadEntry::id)
        if (requested.isEmpty()) return
        val latest = store.entries.value.associateBy(DownloadEntry::id)
        val targets = requested.mapNotNull { original ->
            latest[original.id]?.takeIf { current ->
                sameDownloadTask(original, current) && sourceIncluded(current.manga.sourceId) && current.id !in removingIds &&
                    when (command) {
                        QueueCommand.PAUSE -> current.status == DownloadStatus.RUNNING || current.status == DownloadStatus.QUEUED ||
                            current.status == DownloadStatus.WAITING
                        QueueCommand.RESUME -> current.status == DownloadStatus.PAUSED && current.manga.sourceUrl != LOCAL_SOURCE_URL
                        QueueCommand.RETRY -> current.manga.sourceUrl != LOCAL_SOURCE_URL && (current.needsSourceRefresh ||
                            current.status == DownloadStatus.FAILED || current.status == DownloadStatus.PAUSED) &&
                            current.status != DownloadStatus.COMPLETED
                    }
            }
        }
        if (desktop) queueRequest = DownloadQueueRequest(command, targets, visiblePreparations, requested.size - targets.size)
        targets.forEach { target ->
            if (command == QueueCommand.PAUSE) store.pause(target.id, target.progress.workId.takeIf { it.isNotBlank() })
            else retryAction(target)
        }
    }
    fun retrySelection(group: List<DownloadEntry>, retryAction: (DownloadEntry) -> Unit = onRetry) {
        val command = if (group.all { it.status == DownloadStatus.PAUSED }) QueueCommand.RESUME else QueueCommand.RETRY
        dispatchQueue(command, group, retryAction)
    }

    val usedBytes = visibleEntries.sumOf { it.downloadedBytes }
    var availableBytes by remember(store) { mutableStateOf<Long?>(null) }
    var spaceReadFailed by remember(store) { mutableStateOf(false) }
    LaunchedEffect(showQueueActions, active, store) {
        if (showQueueActions && active) {
            availableBytes = null
            spaceReadFailed = false
            try { availableBytes = withContext(Dispatchers.IO) { store.availableStorageBytes() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { spaceReadFailed = true }
        }
    }
    LaunchedEffect(active, includedSourceIds, excludedSourceIds, if (desktop) desktopScope else Unit) {
        showQueueActions = false
        filterMenuExpanded = false
        showNetworkSettings = false
        showImportSources = false
        // An exiting menu cannot open a new window after its owning page/scope changes.
        pendingNetworkSettings = false
        pendingEnhancement = false
        showEnhancement = false
        qualityReturnTaskId = null
    }
    LaunchedEffect(active, pendingNetworkSettings, queueActionsPresent, showQueueActions) {
        if (pendingNetworkSettings && !queueActionsPresent && !showQueueActions) {
            pendingNetworkSettings = false
            if (active) showNetworkSettings = true
        }
    }
    LaunchedEffect(active, pendingEnhancement, queueActionsPresent, showQueueActions, qualityReturnTaskId, desktopQualityReturn) {
        if (pendingEnhancement && qualityReturnTaskId == null && desktopQualityReturn == null &&
            !queueActionsPresent && !showQueueActions) {
            pendingEnhancement = false
            if (active) showEnhancement = true
        }
    }

    fun openQuality(entry: DownloadEntry? = null, inPane: Boolean = false) {
        if (desktop) {
            if (!active || currentDesktopScope !== desktopScope) return
            desktopQualityReturn = entry?.let { selected ->
                desktopInspection?.takeIf { it.matches(selected) && resolveDesktopInspection(it) != null } ?: return
            }
            desktopInspection = null
        } else {
            qualityReturnTaskId = entry?.id
            inspectedTaskId = null
        }
        // The inline surface has no exit window. The narrow/modal path still waits for its own close.
        pendingEnhancement = !inPane
        if (inPane) showEnhancement = true
        showQueueActions = false
    }
    fun enhanceChapters(chapters: List<DownloadEntry>, openSettings: () -> Unit = { openQuality() }) {
        val complete = chapters.filter { it.status == DownloadStatus.COMPLETED }
        if (complete.isEmpty()) return
        val policy = enhancementCenter.settings.policy(enhancementContent)
        if (policy.tier == QualityTier.ORIGINAL) {
            openSettings()
            scope.launch { snackbar.showSnackbar("先选择增强档位，再建立整章任务") }
            return
        }
        var queued = 0
        var failure: String? = null
        complete.forEach { entry ->
            runCatching { enhancementCenter.chapters.enqueue(entry, enhancementContent, policy) }
                .onSuccess { queued++ }.onFailure { failure = it.message }
        }
        showQueueActions = false
        scope.launch { snackbar.showSnackbar(if (failure == null) "已加入 $queued 话增强任务"
            else "已加入 $queued 话；${failure}") }
    }

    val renderTaskDetails: @Composable (DownloadEntry?, Boolean) -> Unit = { entry, inPane ->
        val selection = shownDesktopInspection?.takeIf { entry != null && it.matches(entry) }
        val enhancement = entry?.let { chapterEnhancementFor(it, enhancementTasks) }
        fun currentTarget(shown: DownloadEntry): DownloadEntry? {
            if (!desktop) return shown
            return selection?.takeIf { desktopInspection === it && it.matches(shown) }
                ?.let(::resolveDesktopInspection)?.takeIf { it.status == shown.status }
        }
        fun currentEnhancement(id: String): Boolean {
            if (!desktop) return true
            val download = entry?.let(::currentTarget) ?: return false
            val shown = enhancement?.takeIf { it.id == id } ?: return false
            return enhancementCenter.chapters.state.value.any {
                it.id == id && it.content == enhancementContent && it.mangaId == download.manga.id &&
                    it.chapterId == download.chapter.id && it.downloadCreatedAt == download.createdAt &&
                    it.generation == shown.generation && it.phase == shown.phase
            }
        }
        DownloadTaskDetails(
            entry = entry, active = active, refreshing = entry?.let { it.id in refreshingIds } == true,
            backdrop = backdrop, inPane = inPane,
            onDismiss = {
                if (!desktop) inspectedTaskId = null
                else if (desktopInspection === selection) desktopInspection = null
            },
            onPause = { currentTarget(it)?.let { target -> dispatchQueue(QueueCommand.PAUSE, listOf(target)) } },
            onRetry = { currentTarget(it)?.let { target -> retrySelection(listOf(target)) } },
            onRetryUsingLatestRoute = { currentTarget(it)?.let { target -> retrySelection(listOf(target), onRetryUsingLatestRoute) } },
            enhancement = enhancement,
            onEnhance = { shown -> currentTarget(shown)?.let { target ->
                enhanceChapters(listOf(target)) { openQuality(target, inPane) }
            } },
            onPauseEnhancement = { if (currentEnhancement(it)) enhancementCenter.chapters.pause(it) },
            onResumeEnhancement = { if (currentEnhancement(it)) enhancementCenter.chapters.resume(it) },
            onCancelEnhancement = { if (currentEnhancement(it)) enhancementCenter.chapters.cancel(it) },
            onQualitySettings = { entry?.let(::currentTarget)?.let { openQuality(it, inPane) } },
            onClosed = {
                if (pendingEnhancement && (desktopQualityReturn != null || qualityReturnTaskId != null)) {
                    pendingEnhancement = false
                    if (active && (!desktop || desktopQualityReturn?.let(::resolveDesktopInspection) != null)) {
                        showEnhancement = true
                    } else if (desktop) desktopQualityReturn = null
                }
            },
        )
    }

    BackHandler(enabled = managing) { managing = false; selectedIds = emptySet() }

    val archivePicker = rememberOpenDocumentPicker() { uri ->
        uri?.let {
            if (!importInputOpen.value || !localImportAllowed) {
                releasePickedDocuments(listOf(it))
                return@rememberOpenDocumentPicker
            }
            val base = documentName(context, it).substringBeforeLast('.').ifBlank { "本地漫画" }
            pendingImport = PendingLocalImport(LocalImportSource.Archive(it), base, "第 1 话")
        }
    }
    val pdfPicker = rememberOpenDocumentPicker() { uri ->
        uri?.let {
            if (!importInputOpen.value || !localImportAllowed || !localPdfImportSupported) {
                releasePickedDocuments(listOf(it))
                return@rememberOpenDocumentPicker
            }
            val base = documentName(context, it).substringBeforeLast('.').ifBlank { "本地漫画" }
            importError = null
            pendingImport = PendingLocalImport(LocalImportSource.Pdf(it), base, "第 1 话")
        }
    }
    val imagePicker = rememberMultipleDocumentPicker() { uris ->
        if (!importInputOpen.value || !localImportAllowed) {
            releasePickedDocuments(uris)
            return@rememberMultipleDocumentPicker
        }
        if (uris.isNotEmpty()) pendingImport = PendingLocalImport(
            LocalImportSource.Images(uris), "本地漫画", "第 1 话",
        )
    }
    val folderPicker = rememberFolderPicker() { uri ->
        uri?.let {
            if (!importInputOpen.value || !localImportAllowed) {
                releasePickedDocuments(listOf(it))
                return@rememberFolderPicker
            }
            pendingImport = PendingLocalImport(
                LocalImportSource.Folder(it), documentName(context, it).ifBlank { "本地漫画" }, "第 1 话",
            )
        }
    }

    fun importLocal(pending: PendingLocalImport, mangaTitle: String, chapterTitle: String) {
        if (!localImportAllowed) {
            importError = "本地导入保存在普通分区"
            return
        }
        if (importBusy) return
        val handle = operations.importLocal(store, pending.source, mangaTitle.trim(), chapterTitle.trim())
        if (handle.accepted) {
            // The task owns picked files from acceptance until terminal cleanup.
            pendingImport = null
            importError = null
        } else importError = "已有导入正在进行，可在下载页查看或取消"
    }

    LaunchedEffect(importOperation?.id, importOperation?.phase, active) {
        val operation = importOperation ?: return@LaunchedEffect
        if (!active || operation.active) return@LaunchedEffect
        if (operation.phase == ApplicationOperationPhase.SUCCEEDED) {
            operations.result<DownloadEntry>(operation.id)?.let {
                it.await()
                selectFilter(DownloadFilter.SAVED)
                operations.consumeResult(operation.id)
            }
        } else if (operation.phase == ApplicationOperationPhase.FAILED ||
            operation.phase == ApplicationOperationPhase.INTERRUPTED) importError = operation.message
    }

    fun toggleSelection(targets: List<DownloadEntry>) {
        val ids = targets.map(DownloadEntry::id).filter { it in entriesById }.toSet()
        if (ids.isEmpty()) return
        selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids else selectedIds + ids
    }

    fun removeWithUndo(targets: List<DownloadEntry>) {
        val ids = targets.map(DownloadEntry::id).filter { it in entriesById }.toSet() - removingIds
        if (ids.isEmpty()) return
        removingIds = removingIds + ids
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            var undone = false
            var failed = 0
            try {
                undone = snackbar.showSnackbar("已移除 ${ids.size} 话", actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                if (undone) haptics.perform(dev.mangaflow.ui.motion.FeelEvent.Undo)
            } finally {
                // Page navigation commits an already requested removal; undo never recreates deleted files.
                if (!undone) withContext(NonCancellable) {
                    ids.forEach { id ->
                        try { store.remove(id) }
                        catch (_: Exception) { failed++ }
                    }
                }
                removingIds = removingIds - ids
                if (failed > 0 && currentCoroutineContext().isActive)
                    snackbar.showSnackbar("$failed 话未能删除，请重试")
            }
        }
    }

    val queueActions: @Composable () -> Unit = {
                    if (managing) {
                        MotionIconButton(onClick = {
                            val visible = when (filter) {
                                DownloadFilter.ALL -> manageableEntries
                                DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                                DownloadFilter.SAVED -> completedEntries
                            }
                            toggleSelection(visible)
                        }, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.SelectAll, "全选当前列表", Modifier.size(20.dp), tint = foreground) }
                        MotionIconButton(enabled = selectedEntries.any { it.status == DownloadStatus.COMPLETED },
                            onClick = { enhanceChapters(selectedEntries) }, modifier = Modifier.size(44.dp), tooltip = "增强选中的已下载章节") {
                            Icon(Icons.Outlined.AutoAwesome, "增强选中的已下载章节", Modifier.size(20.dp), tint = secondary)
                        }
                        MotionIconButton(enabled = selectedEntries.isNotEmpty(), onClick = { pendingDeletion = selectedEntries },
                            modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除选中的 ${selectedEntries.size} 话", Modifier.size(20.dp),
                                tint = if (selectedEntries.isNotEmpty()) colors.error else secondary.copy(alpha = .4f)) }
                        MotionIconButton(onClick = { managing = false; selectedIds = emptySet() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Close, "退出管理", Modifier.size(20.dp), tint = secondary)
                        }
                    } else {
                        MotionIconButton(enabled = !importBusy && localImportAllowed, modifier = Modifier.size(44.dp),
                            tooltip = if (localImportAllowed) "导入漫画" else "本地导入保存在普通分区",
                            onClick = { if (localImportAllowed) showImportSources = true }) {
                            if (importBusy) CircularProgressIndicator(Modifier.size(16.dp)
                                .semantics { contentDescription = "正在导入" }, strokeWidth = 1.5.dp, color = secondary)
                            else Icon(Icons.Outlined.Add,
                                if (localImportAllowed) "导入漫画" else "本地导入保存在普通分区",
                                Modifier.size(21.dp), tint = foreground)
                        }
                        Box {
                            MotionIconButton(onClick = {
                                pendingNetworkSettings = false
                                filterMenuExpanded = false
                                showQueueActions = !showQueueActions
                            }, enabled = active, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.MoreHoriz, "存储与下载管理", Modifier.size(21.dp), tint = secondary)
                            }
                            CompactGlassPopover(visible = showQueueActions && active, backdrop = backdrop,
                                onDismiss = { showQueueActions = false },
                                onPresenceChange = { queueActionsPresent = it },
                                maximumWidth = 216.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.35f)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("下载占用", fontSize = 11.sp, color = secondary)
                                        Spacer(Modifier.weight(1f))
                                        Text(downloadBytes(usedBytes), fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium, color = foreground)
                                    }
                                    Text(availableBytes?.let { "${downloadBytes(it)} 可用" }
                                        ?: if (spaceReadFailed) "暂时无法读取可用空间" else "正在读取可用空间…",
                                        fontSize = 11.sp, color = secondary)
                                    if (importOperation != null) {
                                        Text("本地导入 · " + (if (importBusy) "后台处理中" else importOperation.message.orEmpty()),
                                            fontSize = 11.sp, color = secondary)
                                        if (importOperation.cancellable) CompactMenuAction(Icons.Outlined.Close, "取消导入") {
                                            operations.cancel(importOperation.id)
                                            showQueueActions = false
                                        }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = foreground.copy(alpha = .07f))
                                CompactMenuAction(Icons.Outlined.Tune, "网络与下载") {
                                    pendingNetworkSettings = true
                                    showQueueActions = false
                                }
                                CompactMenuAction(Icons.Outlined.AutoAwesome, "画质与性能") { openQuality() }
                                if (completedEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.AutoAwesome, "增强已下载章节") {
                                    enhanceChapters(completedEntries)
                                }
                                CompactMenuAction(Icons.Outlined.Inventory2, "管理下载", enabled = manageableEntries.isNotEmpty()) {
                                    showQueueActions = false; managing = true; selectedIds = emptySet()
                                }
                                if (activeEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.Pause, "全部暂停") {
                                    showQueueActions = false; dispatchQueue(QueueCommand.PAUSE, activeEntries)
                                }
                                if (pausedEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.PlayArrow, "继续已暂停") {
                                    showQueueActions = false; dispatchQueue(QueueCommand.RESUME, pausedEntries)
                                }
                                if (retryableEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.Refresh, "重试失败任务") {
                                    showQueueActions = false; dispatchQueue(QueueCommand.RETRY, retryableEntries)
                                }
                                if (completedEntries.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = foreground.copy(alpha = .07f))
                                    CompactMenuAction(Icons.Outlined.DeleteOutline, "清理已下载", destructive = true) {
                                        showQueueActions = false; pendingDeletion = completedEntries
                                    }
                                }
                            }
                        }
                    }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        // An inspection owns text selection, including the frame before its pane is drawn.
        if (!active || !desktop || chapterFieldFocused || desktopInspection != null || desktopTask != null ||
            event.type != KeyEventType.KeyDown) false
        else if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A && manageableEntries.isNotEmpty()) {
            val currentEntries = when (filter) {
                DownloadFilter.ALL -> manageableEntries
                DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                DownloadFilter.SAVED -> completedEntries
            }
            if (currentEntries.isEmpty()) false else {
                managing = true
                selectedIds = currentEntries.map(DownloadEntry::id).toSet()
                true
            }
        } else if (managing && event.key == Key.Delete && selectedEntries.isNotEmpty()) {
            pendingDeletion = selectedEntries
            true
        } else false
    }.background(if (LocalWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background)) {
        val filterWidth = preferredFilterWidth.coerceAtMost(
            (maxWidth - if (managing) 224.dp else 180.dp).coerceAtLeast(70.dp))
        val contentInset = MangaLayout.sidePadding(maxWidth)
        val desktopScale = density.fontScale.coerceAtLeast(1f)
        val desktopSplit = desktop && maxWidth >= 780.dp * desktopScale && orderedBooks.getValue(filter).isNotEmpty()
        val directoryWidth = (maxWidth * .38f).coerceIn(292.dp * desktopScale, 440.dp * desktopScale)
        // A short wide window also needs the original scrollable dialog rather than a clipped inspector.
        val inPane = desktopSplit && maxHeight - contentTopPadding - bottomInset >= 240.dp * density.fontScale.coerceAtLeast(1f)
        val paneFilter = filter
        val paneWorks = orderedBooks.getValue(paneFilter)
        fun selectedWork(taskWorkId: String?): String? = taskWorkId ?: expandedBooks.lastOrNull { it in paneWorks }
            ?: paneWorks.keys.firstOrNull()
        val inspectedBook = selectedWork(desktopTask?.manga?.id)
        val inspectedChapters = inspectedBook?.let { paneWorks[it]?.mapNotNull(entriesById::get) }.orEmpty()
        BackHandler(enabled = inPane && desktopTask != null) {
            if (desktopInspection === shownDesktopInspection) desktopInspection = null
        }
        // A transfer row needs room for its title, source and a separate action target.
        // Reusing the four-column catalogue geometry clips that information on phones.
        val downloadChapterColumns = if (desktop) 1 else MangaLayout.columns(maxWidth, 3,
            220.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), 8.dp, contentInset + 4.dp)
        // The captured scene contains the wallpaper and scrolling list, never the header itself.
        Box(Modifier.fillMaxSize().mangaHazeSource(backdrop).background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)) {
            wallpaper()
            // Work selection and chapter disclosure share the current filtered queue.
            Row(Modifier.fillMaxSize()) {
            Box(Modifier.then(if (desktopSplit) Modifier.width(directoryWidth) else Modifier.weight(1f)).fillMaxSize()) {
            MotionTabContent(filter.ordinal, Modifier.fillMaxSize()) { index ->
                val pageFilter = DownloadFilter.entries[index]
                val visible = when (pageFilter) {
                    DownloadFilter.ALL -> manageableEntries
                    DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                    DownloadFilter.SAVED -> manageableEntries.filter { it.status == DownloadStatus.COMPLETED }
                }
                val scroll = when (pageFilter) {
                    DownloadFilter.ALL -> allScroll
                    DownloadFilter.ACTIVE -> activeScroll
                    DownloadFilter.SAVED -> savedScroll
                }
                LaunchedEffect(active, index, filter) {
                    if (!active || index != filter.ordinal) scroll.stopScroll()
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), state = scroll,
                    contentPadding = PaddingValues(start = if (desktopSplit) 16.dp else contentInset,
                        end = if (desktopSplit) 10.dp else contentInset, top = contentTopPadding, bottom = 32.dp + bottomInset),
                ) {
                    if (desktopSplit) item(key = "download-directory-heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("作品", style = MaterialTheme.typography.titleMedium)
                            Text(orderedBooks.getValue(pageFilter).size.toString(), style = mangaMetricValueStyle(),
                                color = colors.onSurfaceVariant)
                        }
                    }
                    if (pageFilter != DownloadFilter.SAVED && localImportAllowed && importOperation != null &&
                        (importOperation.active || importOperation.phase == ApplicationOperationPhase.FAILED ||
                            importOperation.phase == ApplicationOperationPhase.INTERRUPTED)) item(key = "local-import-operation") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (importBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            else Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            Text(if (importBusy) "本地导入 · 可离开此页" else importOperation.message.orEmpty(),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                            if (importOperation.cancellable) MotionIconButton(onClick = { operations.cancel(importOperation.id) },
                                modifier = Modifier.size(44.dp), tooltip = "取消导入") {
                                Icon(Icons.Outlined.Close, "取消本地导入", Modifier.size(18.dp))
                            }
                        }
                    }
                    if (pageFilter != DownloadFilter.SAVED) items(visiblePreparations,
                        key = { "preparation:" + it.id }, contentType = { "chapter-preparation" }) { preparation ->
                        ChapterPreparationRow(preparation, backdrop = backdrop, active = active && pageFilter == filter,
                            onCancel = { onCancelPreparation(preparation.id, preparation.generation) },
                            onRetry = { onRetryPreparation(preparation.id, preparation.generation) },
                            onRetryUsingLatestRoute = {
                                onRetryPreparationUsingLatestRoute(preparation.id, preparation.generation)
                            })
                    }
                    if (loading) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    if (showLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = foreground)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("正在恢复下载", style = MaterialTheme.typography.bodyMedium, color = secondary)
                            }
                        }
                    } else if (visible.isEmpty() &&
                        (pageFilter == DownloadFilter.SAVED || visiblePreparations.isEmpty())) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(30.dp), tint = secondary)
                                }
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    when (pageFilter) {
                                        DownloadFilter.ALL -> "暂时没有下载"
                                        DownloadFilter.ACTIVE -> "没有未完成的下载"
                                        DownloadFilter.SAVED -> "还没有下载完成的章节"
                                    },
                                    style = MaterialTheme.typography.titleMedium, color = foreground,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(when (pageFilter) {
                                    DownloadFilter.ALL -> "从漫画目录下载章节，或添加本地漫画。"
                                    DownloadFilter.ACTIVE -> if (completedEntries.isNotEmpty()) "已完成的章节保存在“已下载”中。" else "从漫画目录选择章节，即可开始下载。"
                                    DownloadFilter.SAVED -> if (manageableEntries.isNotEmpty() || visiblePreparations.isNotEmpty())
                                        "任务尚未完成，可在“未完成”查看。" else "下载完成后，就能在这里离线阅读。"
                                }, style = MaterialTheme.typography.bodySmall, color = secondary)
                                if (pageFilter == DownloadFilter.ACTIVE && completedEntries.isNotEmpty()) {
                                    TextButton(onClick = { selectFilter(DownloadFilter.SAVED) }) { Text("查看已下载") }
                                } else if (pageFilter == DownloadFilter.SAVED &&
                                    (manageableEntries.isNotEmpty() || visiblePreparations.isNotEmpty())) {
                                    TextButton(onClick = { selectFilter(DownloadFilter.ACTIVE) }) { Text("查看未完成") }
                                } else if (pageFilter == DownloadFilter.ALL) {
                                    TextButton(enabled = localImportAllowed,
                                        onClick = { if (localImportAllowed) showImportSources = true }) {
                                        Text("添加本地漫画")
                                    }
                                }
                            }
                        }
                    } else {
                        if (pageFilter != DownloadFilter.SAVED && !managing &&
                            (activeEntries.isNotEmpty() || pausedEntries.isNotEmpty() ||
                                manageableEntries.any { it.status == DownloadStatus.FAILED })) {
                            item(key = "queue-summary", contentType = "download-summary") {
                                DownloadQueueSummary(manageableEntries,
                                    onPause = { dispatchQueue(QueueCommand.PAUSE, activeEntries) },
                                    onResume = { dispatchQueue(QueueCommand.RESUME, pausedEntries) },
                                    onRetry = { dispatchQueue(QueueCommand.RETRY, retryableEntries) },
                                    hasPaused = pausedEntries.isNotEmpty(), hasRetryable = retryableEntries.isNotEmpty())
                            }
                        }
                        orderedBooks.getValue(pageFilter).forEach { (mangaId, chapterIds) ->
                            val expanded = if (desktopSplit) mangaId == inspectedBook
                                else mangaId in expandedBooks && chapterIds.size > 1
                            item(key = "work:$mangaId", contentType = "download-work") {
                                // Byte progress changes the entry map, not the directory. Resolve
                                // rows only when this work is composed, including lazy prefetch.
                                val chapters = chapterIds.mapNotNull(entriesById::get)
                                val placement = Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)
                                Box(placement) {
                                    SwipeToRemove(enabled = !managing && !desktop,
                                        label = "删除这部作品的 ${chapters.size} 话下载", onRemove = { removeWithUndo(chapters) }) {
                                        DownloadBookRow(chapters, repository, expanded, managing,
                                            selected = chapters.all { it.id in selectedIds }, refreshingIds = refreshingIds,
                                            onRead = {
                                                val resume = historyById[mangaId]
                                                val target = chapters.firstOrNull { it.chapter.id == resume?.chapterId }
                                                    ?: chapters.firstOrNull { it.status == DownloadStatus.COMPLETED }
                                                    ?: chapters.firstOrNull { it.completedPages > 0 } ?: chapters.first()
                                                onRead(target)
                                            },
                                            onSelect = { toggleSelection(chapters) },
                                            onManage = { if (managing) toggleSelection(chapters) else { managing = true; selectedIds = chapters.map(DownloadEntry::id).toSet() } },
                                            onExpand = {
                                                if (desktopSplit) {
                                                    if (active && currentDesktopScope === desktopScope && pageFilter == filter) {
                                                        clearDesktopInspection()
                                                        expandedBooks = listOf(mangaId)
                                                    }
                                                } else expandedBooks = if (expanded) expandedBooks - mangaId else expandedBooks + mangaId
                                            },
                                            onPause = { group -> dispatchQueue(QueueCommand.PAUSE, group) },
                                            onRetry = { group -> retrySelection(group) },
                                            onDetails = onDetails?.let { open -> { open(chapters.first().manga) } },
                                            onRemove = { pendingDeletion = chapters }, inspectMode = desktopSplit,
                                            active = active && pageFilter == filter,
                                            onInspect = { inspectDownload(it, pageFilter, selectWork = desktopSplit) },
                                            enhancementTasks = enhancementTasks)
                                    }
                                }
                            }
                            if (expanded && !desktopSplit) items(
                                chapterIds.mapNotNull(entriesById::get).chunked(downloadChapterColumns),
                                key = { "chapters:" + it.first().id }, contentType = { "download-chapter-grid" }) { row ->
                                Row(Modifier.fillMaxWidth().animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Quick) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null
                                ).padding(start = if (downloadChapterColumns == 1) 24.dp else 12.dp,
                                    end = 4.dp, top = 3.dp, bottom = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { entry -> key(entry.id, entry.createdAt) {
                                        Box(Modifier.weight(1f)) {
                                            SwipeToRemove(enabled = !managing && !desktop, label = "删除 ${entry.chapter.title} 下载",
                                                onRemove = { removeWithUndo(listOf(entry)) }) {
                                                DownloadChapterRow(entry, managing, entry.id in selectedIds, refreshingIds,
                                                    onRead = { onRead(entry) }, onSelect = { toggleSelection(listOf(entry)) },
                                                    onManage = { if (managing) toggleSelection(listOf(entry)) else {
                                                        managing = true; selectedIds = setOf(entry.id) } },
                                                    onPause = { dispatchQueue(QueueCommand.PAUSE, listOf(it)) }, onRetry = { retrySelection(listOf(it)) },
                                                    onRemove = { pendingDeletion = listOf(entry) },
                                                    active = active && pageFilter == filter,
                                                    onInspect = { inspectDownload(it, pageFilter, selectWork = false) },
                                                    enhancementTasks = enhancementTasks)
                                            }
                                        }
                                    } }
                                    repeat(downloadChapterColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            item(key = "space:$mangaId", contentType = "download-separator") {
                                HorizontalDivider(Modifier.animateItem(fadeInSpec = null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = null).padding(start = 60.dp, top = 2.dp, bottom = 2.dp),
                                    color = foreground.copy(alpha = .05f))
                            }
                        }
                    }
                }
            }
            if (desktop) DesktopVerticalScrollbar(when (filter) {
                DownloadFilter.ALL -> allScroll
                DownloadFilter.ACTIVE -> activeScroll
                DownloadFilter.SAVED -> savedScroll
            }, Modifier.align(Alignment.CenterEnd).padding(top = contentTopPadding, bottom = bottomInset))
            }
            if (desktopSplit) {
                Box(Modifier.padding(top = contentTopPadding, bottom = bottomInset).fillMaxHeight().width(1.dp)
                    .background(colors.outlineVariant.copy(alpha = .45f)))
                DesktopDownloadChapterPane(
                    entries = inspectedChapters, managing = managing, selectedIds = selectedIds, repository = repository,
                    refreshingIds = refreshingIds, resumeChapterId = inspectedBook?.let { historyById[it]?.chapterId },
                    topPadding = contentTopPadding,
                    bottomPadding = 32.dp + bottomInset,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onRead = onRead, onSelect = { toggleSelection(listOf(it)) },
                    onManage = { managing = true; selectedIds = setOf(it.id) },
                    onRemove = { pendingDeletion = listOf(it) },
                    onPause = { dispatchQueue(QueueCommand.PAUSE, listOf(it)) }, onRetry = { retrySelection(listOf(it)) },
                    onFindFocus = { chapterFieldFocused = it },
                    active = active, onInspect = { entry ->
                        if (entry.manga.id == selectedWork(desktopInspection?.mangaId)) {
                            inspectDownload(entry, paneFilter, selectWork = true)
                        }
                    },
                    enhancementTasks = enhancementTasks,
                    inspection = if (inPane && desktopTask != null) {
                        { key(shownDesktopInspection) { renderTaskDetails(desktopTask, true) } }
                    } else null,
                )
            }
            }
        }
        if (desktop) {
            val stackedTools = maxWidth < 840.dp * density.fontScale.coerceAtLeast(1f)
            DesktopManagementToolbar("下载", Modifier.align(Alignment.TopCenter)
                .onSizeChanged { headerHeightPx = it.height },
                navigation = navigation, minimumToolsWidth = 840.dp) {
                MotionTabs(filter.ordinal, DownloadFilter.entries.map { it.label }, { index ->
                    selectFilter(DownloadFilter.entries[index]); selectedIds = emptySet()
                }, modifier = if (stackedTools) Modifier.width(360.dp * density.fontScale.coerceAtLeast(1f))
                    else Modifier.widthIn(max = 420.dp).weight(1f, fill = false),
                    treatment = MotionTabTreatment.Soft, controlHeight = 36.dp)
                if (!stackedTools) Spacer(Modifier.weight(1f))
                if (!managing) TextButton(enabled = manageableEntries.isNotEmpty(),
                    onClick = { managing = true; selectedIds = emptySet() }) { Text("选择") }
                queueActions()
            }
        } else Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeightPx = it.height }
            .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 2.dp)) {
            PageTitleHeader("下载", backdrop, navigation,
                leadingWidth = filterWidth,
                leading = {
                    Spacer(Modifier.width(filterWidth).height(morphGlassTabHeight()))
                }, actions = {
                toolbar {
                    queueActions()
                }
            })

        }

        if (!desktop) MorphGlassTabSelector(backdrop, filterChoices, filter.ordinal, filterWidth,
            maximumHeight = maxHeight,
            onToggle = { if (active) {
                pendingNetworkSettings = false
                filterMenuExpanded = !filterMenuExpanded
                showQueueActions = false
            } },
            expanded = filterMenuExpanded && active, onDismiss = { filterMenuExpanded = false },
            onSelect = { index ->
                filterMenuExpanded = false
                selectFilter(DownloadFilter.entries[index])
                selectedIds = emptySet()
            })
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (desktop && active) {
                val request = queueRequest
                CopyShelfValue(request) { shown ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DownloadQueueFeedback(shown, entriesById, visiblePreparations) {
                            if (queueRequest === shown) queueRequest = null
                        }
                    }
                }
            }
            MangaSnackbarHost(snackbar)
        }
        // Keep the modal host composed through a resize so its exit and settings handoff can settle.
        if (desktop) renderTaskDetails(if (inPane) null else desktopTask, false)
    }

    NetworkDownloadDialog(showNetworkSettings && active, backdrop, { showNetworkSettings = false },
        repository = repository as? dev.mangaflow.data.UnifiedMangaRepository,
        initialScene = NetworkDownloadScene.CATEGORIES)

    pendingDeletion?.let { pendingTargets ->
        val targets = pendingTargets.mapNotNull { entriesById[it.id] }
        if (targets.isNotEmpty()) AlertDialog(onDismissRequest = { pendingDeletion = null }, shape = RoundedCornerShape(22.dp),
            containerColor = colors.surface,
            title = { Text("删除 ${targets.size} 话下载？", style = MaterialTheme.typography.titleMedium) },
            text = {
                val titles = targets.map { it.manga.title }.distinct()
                Text("${titles.take(3).joinToString("、")}${if (titles.size > 3) "等 ${titles.size} 部作品" else ""}\n" +
                    "${targets.size} 话 · ${downloadBytes(targets.sumOf { it.downloadedBytes })}\n阅读记录和收藏保留。",
                    style = MaterialTheme.typography.bodyMedium, color = secondary)
            },
            confirmButton = {
                DownloadTextAction(onClick = {
                    pendingDeletion = null
                    managing = false
                    selectedIds = emptySet()
                    removeWithUndo(targets)
                }) { Text("删除", color = colors.error) }
            }, dismissButton = { DownloadTextAction(onClick = { pendingDeletion = null }) { Text("取消", color = secondary) } })
    }

    MotionDialog(visible = showImportSources, onDismissRequest = { showImportSources = false }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
            var showFormats by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height)
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("导入漫画", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = { showImportSources = false }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "取消导入", Modifier.size(20.dp), tint = secondary)
                    }
                }
                if (!localImportAllowed) Text("本地导入保存在普通分区",
                    style = MaterialTheme.typography.bodySmall, color = secondary)
                LocalImportChoice(Icons.Outlined.Inventory2, "压缩包", "CBZ · ZIP", enabled = localImportAllowed) {
                    showImportSources = false
                    archivePicker.launch(arrayOf(
                        "application/zip", "application/x-zip-compressed", "application/x-cbz",
                        "application/vnd.comicbook+zip", "application/octet-stream",
                    ))
                }
                if (localPdfImportSupported) LocalImportChoice(Icons.Outlined.PictureAsPdf, "PDF", "按原页序", enabled = localImportAllowed) {
                    showImportSources = false
                    pdfPicker.launch(arrayOf("application/pdf"))
                }
                LocalImportChoice(Icons.Outlined.Collections, "图片", "多选", enabled = localImportAllowed) {
                    showImportSources = false
                    imagePicker.launch(arrayOf("image/*"))
                }
                LocalImportChoice(Icons.Outlined.FolderOpen, "文件夹", "图片目录", enabled = localImportAllowed) {
                    showImportSources = false
                    folderPicker.launch(null)
                }
                TextButton(onClick = { showFormats = !showFormats }) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("格式说明", style = MaterialTheme.typography.labelMedium, color = secondary)
                }
                if (showFormats) Text(
                    if (localPdfImportSupported) "图片按文件名自然排序，PDF 按原页序保存为图片。每次导入合成一个章节；原文件保留。"
                    else "按文件名自然排序。多张图片或文件夹会合成一个章节；导入后保留原文件。",
                    style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
    }

    val inspectedTask = manageableEntries.firstOrNull { it.id == inspectedTaskId }
    LaunchedEffect(active, inspectedTaskId, inspectedTask != null) {
        if (!active || inspectedTask == null) inspectedTaskId = null
    }
    if (!desktop) renderTaskDetails(if (active) inspectedTask else null, false)
    EnhancementCenterPanel(visible = showEnhancement && active, backdrop = backdrop,
        initialContent = enhancementContent, allowMediaSwitch = false, onDismiss = { showEnhancement = false }, onClosed = {
            if (desktop) {
                val returnTo = desktopQualityReturn
                desktopQualityReturn = null
                if (returnTo != null && resolveDesktopInspection(returnTo) != null) desktopInspection = returnTo.copy()
            } else {
                val returnId = qualityReturnTaskId
                qualityReturnTaskId = null
                if (active && returnId != null && manageableEntries.any { it.id == returnId }) inspectedTaskId = returnId
            }
        })

    LocalImportDetailsDialog(
        pending = pendingImport,
        busy = importBusy,
        error = importError,
        backdrop = backdrop,
        allowed = localImportAllowed,
        onDismiss = {
            pendingImport?.source?.documents()?.let(::releasePickedDocuments)
            pendingImport = null
            importError = null
        },
        onConfirm = { pending, mangaTitle, chapterTitle -> importLocal(pending, mangaTitle, chapterTitle) },
    )

}

@Composable
private fun ChapterPreparationRow(
    state: ChapterPreparationState,
    backdrop: HazeState,
    active: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRetryUsingLatestRoute: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val failed = state.phase == ChapterPreparationPhase.FAILED
    val committing = state.phase == ChapterPreparationPhase.COMMITTING
    var retryMenu by remember(state.id, state.generation) { mutableStateOf(false) }
    var retryPresent by remember(state.id, state.generation) { mutableStateOf(false) }
    LaunchedEffect(active, failed) { if (!active || !failed) retryMenu = false }
    val sourceId = remember(state.manga.id, state.chapter) {
        runCatching { dev.mangaflow.data.SourceKeys.decodeChapter(
            dev.mangaflow.data.chapterSourceMangaId(state.manga.id, state.chapter), state.chapter.id).sourceId
        }.getOrDefault(state.chapter.sourceId)
    }
    val source = dev.mangaflow.data.sourceDisplayName(sourceId)
    val status = when (state.phase) {
        ChapterPreparationPhase.WAITING -> state.error.ifBlank { "等待准备" }
        ChapterPreparationPhase.PREPARING -> "正在获取章节信息"
        ChapterPreparationPhase.COMMITTING -> "正在加入下载"
        ChapterPreparationPhase.FAILED -> "准备失败"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state.phase) {
                ChapterPreparationPhase.WAITING -> Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant)
                ChapterPreparationPhase.FAILED -> Icon(Icons.Outlined.Info, null, Modifier.size(18.dp),
                    tint = colors.error)
                else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp,
                    color = colors.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.chapter.title + " · " + source, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status + (state.route?.let { " · " + downloadRouteLabel(it.mode) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.error else colors.onSurfaceVariant)
            if (failed && state.error.isNotBlank()) Text(state.error, style = MaterialTheme.typography.bodySmall,
                color = colors.error)
        }
        Box(Modifier.width(if (failed || retryPresent) 44.dp else 0.dp).height(44.dp)) {
            if (failed) MotionIconButton(onClick = { retryMenu = !retryMenu }, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "选择重试线路") {
                Icon(Icons.Outlined.Refresh, "选择 ${state.chapter.title} 的重试线路", Modifier.size(20.dp), tint = colors.error)
            }
            CompactGlassPopover(visible = retryMenu && failed && active, backdrop = backdrop,
                onDismiss = { retryMenu = false }, onPresenceChange = { retryPresent = it }, maximumWidth = 240.dp) {
                CompactMenuAction(Icons.Outlined.Refresh, "原线路重试", enabled = failed && active && state.route != null) {
                    retryMenu = false
                    onRetry()
                }
                CompactMenuAction(Icons.Outlined.Refresh, "使用当前设置重试", enabled = failed && active) {
                    retryMenu = false
                    onRetryUsingLatestRoute()
                }
            }
        }
        MotionIconButton(onClick = onCancel, enabled = active && !committing, modifier = Modifier.size(44.dp),
            tooltip = if (committing) "加入下载后可暂停" else if (failed) "移除准备记录" else "取消准备") {
            Icon(Icons.Outlined.Close,
                if (committing) "正在加入下载" else if (failed) "移除准备记录" else "取消准备 ${state.chapter.title}",
                Modifier.size(18.dp), tint = colors.onSurfaceVariant.copy(alpha = if (committing) .35f else 1f))
        }
    }
}

@Composable
private fun DownloadQueueSummary(
    entries: List<DownloadEntry>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    hasPaused: Boolean,
    hasRetryable: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val running = entries.count { it.status == DownloadStatus.RUNNING }
    val queued = entries.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.WAITING }
    val failed = entries.count { it.status == DownloadStatus.FAILED }
    val paused = entries.count { it.status == DownloadStatus.PAUSED }
    val working = running + queued > 0
    val statuses = listOf(running to "话下载中", queued to "话等待", failed to "话需处理", paused to "话暂停")
        .filter { it.first > 0 }
    val (count, phase) = statuses.firstOrNull() ?: return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 9.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(count.toString(), Modifier.alignByBaseline(), style = mangaMetricValueStyle(29.sp, 35.sp),
                    color = if (!working && failed > 0) colors.error else colors.onSurface)
                Text(phase, Modifier.alignByBaseline(), style = MaterialTheme.typography.titleSmall)
            }
            if (statuses.size > 1) Text(mangaNumberText(statuses.drop(1).joinToString(" · ") { "${it.first} ${it.second}" }),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        val action = when {
            working -> "全部暂停" to onPause
            hasRetryable -> "重试失败" to onRetry
            hasPaused -> "继续下载" to onResume
            else -> null
        }
        action?.let { (label, click) ->
            TextButton(onClick = click) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun LocalImportChoice(
    icon: ImageVector,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, enabled = enabled, pressedScale = .985f).heightIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp),
            tint = colors.onSurfaceVariant.copy(alpha = .65f))
    }
}


/** A selected work's real chapters stay beside the queue on wide desktop windows. */
@Composable
private fun DesktopDownloadChapterPane(
    entries: List<DownloadEntry>,
    repository: MangaRepository,
    managing: Boolean,
    selectedIds: Set<String>,
    refreshingIds: Set<String>,
    resumeChapterId: String?,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier,
    onRead: (DownloadEntry) -> Unit,
    onSelect: (DownloadEntry) -> Unit,
    onManage: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    active: Boolean,
    onInspect: (DownloadEntry) -> Unit,
    enhancementTasks: List<ReaderChapterEnhancementTask>,
    inspection: (@Composable () -> Unit)?,
    onFindFocus: (Boolean) -> Unit,
) {
    val workId = entries.firstOrNull()?.manga?.id
    // The exact work owns its chapter offset even while its list is not mounted.
    val scroll = key(workId) { rememberLazyListState() }
    var query by remember(workId) { mutableStateOf("") }
    var focusRequest by remember(workId) { mutableIntStateOf(0) }
    val canFind = entries.size >= 6 || query.isNotEmpty()
    val visible = if (query.isBlank()) entries else {
        val matches = rememberMatchingChapters(entries.map { it.chapter }, query)
        val ids = remember(matches) { matches.map { it.id }.toSet() }
        entries.filter { it.chapter.id in ids }
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier.onPreviewKeyEvent { event ->
        if (active && inspection == null && canFind && event.type == KeyEventType.KeyDown &&
            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.F) {
            focusRequest++
            true
        } else false
    }.padding(start = 18.dp, end = 20.dp, top = topPadding)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            entries.firstOrNull()?.manga?.let { manga ->
                val cover = Modifier.size(46.dp, 66.dp).mangaCoverCard(7.dp)
                if (manga.sourceUrl == LOCAL_SOURCE_URL)
                    coil3.compose.AsyncImage(manga.coverUrl, null, cover, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else MangaCover(manga, repository, cover)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entries.firstOrNull()?.manga?.title.orEmpty(),
                    fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val saved = entries.count { it.status == DownloadStatus.COMPLETED }
                Text("已保存 $saved / ${entries.size} 话 · ${downloadBytes(entries.sumOf { it.downloadedBytes })}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (entries.isNotEmpty() && !managing && inspection == null) TextButton(onClick = {
                val target = entries.firstOrNull { it.chapter.id == resumeChapterId }
                    ?: entries.firstOrNull { it.status == DownloadStatus.COMPLETED }
                    ?: entries.firstOrNull { it.completedPages > 0 } ?: entries.first()
                onRead(target)
            }) { Text(if (resumeChapterId != null) "继续阅读" else "阅读") }
        }
        HorizontalDivider(color = colors.onSurface.copy(alpha = .06f))
        if (inspection == null && canFind) {
            DisposableEffect(workId) { onDispose { onFindFocus(false) } }
            ChapterSearchField(query, { query = it }, focusRequest, active,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged { onFindFocus(it.hasFocus) }.focusGroup(),
                onOpenMatch = visible.firstOrNull()?.takeIf { !managing && query.isNotBlank() }?.let { entry -> { onInspect(entry) } })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (inspection != null) {
                Box(Modifier.fillMaxSize().padding(top = 10.dp, bottom = bottomPadding)) { inspection() }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = scroll,
                    contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (visible.isEmpty() && query.isNotBlank()) item {
                        Text("没有匹配的章节", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    items(visible, key = DownloadEntry::id, contentType = { "desktop-download-chapter" }) { entry ->
                        DownloadChapterRow(entry, managing, entry.id in selectedIds, refreshingIds,
                            onRead = { onRead(entry) }, onSelect = { onSelect(entry) },
                            onManage = { onManage(entry) }, onPause = onPause, onRetry = onRetry,
                            onRemove = { onRemove(entry) }, active = active, onInspect = onInspect,
                            enhancementTasks = enhancementTasks)
                    }
                }
                DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}


@Composable
private fun LocalImportDetailsDialog(
    pending: PendingLocalImport?,
    busy: Boolean,
    error: String?,
    backdrop: HazeState,
    allowed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PendingLocalImport, String, String) -> Unit,
) {
    var retained by remember { mutableStateOf(pending) }
    SideEffect { if (pending != null) retained = pending }
    val displayed = pending ?: retained ?: return
    var mangaTitle by remember(displayed.source) { mutableStateOf(displayed.mangaTitle) }
    var chapterTitle by remember(displayed.source) { mutableStateOf(displayed.chapterTitle) }
    var showStorageNote by remember(displayed.source) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val sourceIcon = when (displayed.source) {
        is LocalImportSource.Archive -> Icons.Outlined.Inventory2
        is LocalImportSource.Pdf -> Icons.Outlined.PictureAsPdf
        is LocalImportSource.Images -> Icons.Outlined.Collections
        is LocalImportSource.Folder -> Icons.Outlined.FolderOpen
    }
    val sourceLabel = when (displayed.source) {
        is LocalImportSource.Archive -> "CBZ / ZIP"
        is LocalImportSource.Pdf -> "PDF · 按原页序"
        is LocalImportSource.Images -> "${displayed.source.uris.size} 张图片"
        is LocalImportSource.Folder -> "图片文件夹"
    }
    fun submit() {
        if (allowed && !busy && pending != null && mangaTitle.isNotBlank() && chapterTitle.isNotBlank()) {
            focus.clearFocus(force = true)
            onConfirm(displayed, mangaTitle, chapterTitle)
        }
    }
    MotionDialog(visible = pending != null, onDismissRequest = onDismiss) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height)
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("导入漫画", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(sourceIcon, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                            Text(sourceLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "取消导入", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                LocalImportNameField("漫画", mangaTitle, !busy, ImeAction.Next,
                    onChange = { mangaTitle = it.take(120) },
                    onAction = { focus.moveFocus(FocusDirection.Next) })
                LocalImportNameField("章节", chapterTitle, !busy, ImeAction.Done,
                    onChange = { chapterTitle = it.take(120) }, onAction = ::submit)
                if (!allowed) Text("本地导入保存在普通分区",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { showStorageNote = !showStorageNote }) {
                        Icon(Icons.Outlined.Info, "导入说明", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionButton(enabled = allowed && !busy && mangaTitle.isNotBlank() && chapterTitle.isNotBlank(),
                        onClick = ::submit, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface,
                            contentColor = colors.surface,
                            disabledContainerColor = colors.onSurface.copy(alpha = .10f),
                            disabledContentColor = colors.onSurfaceVariant)) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "正在导入" else if (error != null) "重试" else "导入")
                    }
                }
                if (showStorageNote) Text(if (displayed.source is LocalImportSource.Pdf)
                    "PDF 按原页序保存为图片，大页面会按比例缩放。原文件保留，移动或删除原文件不影响已导入内容。"
                    else "保存为离线章节，原文件保留。移动或删除原文件不影响已导入内容。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Same quiet input surface as the existing source importer, without an outlined form card. */
@Composable
private fun LocalImportNameField(
    label: String,
    value: String,
    enabled: Boolean,
    action: ImeAction,
    onChange: (String) -> Unit,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        BasicTextField(value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = action),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(colors.onSurface.copy(alpha = .045f))
                .semantics { contentDescription = "${label}名称" },
            decorationBox = { input ->
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart) { input() }
            })
    }
}


@Composable
private fun DownloadTextAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, enabled = enabled, contentPadding = contentPadding, interactionSource = interaction) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

internal fun downloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 1) + " GB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 0) + " KB"
    else -> "$bytes B"
}
