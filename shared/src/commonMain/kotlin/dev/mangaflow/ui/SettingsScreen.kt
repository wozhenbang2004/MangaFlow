package dev.mangaflow.ui

import dev.mangaflow.anime.AnimeController
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import dev.mangaflow.anime.ui.AnimeSourceManager
import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperationKind
import dev.mangaflow.background.ApplicationOperationPhase
import dev.mangaflow.background.UserOperationKeys
import dev.mangaflow.background.InspectedReadingBackup
import dev.mangaflow.background.WebDavOperationDraft
import dev.mangaflow.background.exportReadingBackup
import dev.mangaflow.background.inspectReadingBackup
import dev.mangaflow.background.restoreReadingBackup
import dev.mangaflow.background.webDav

import androidx.compose.foundation.layout.asPaddingValues

import kotlinx.coroutines.IO

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import dev.mangaflow.ui.motion.MotionButton as Button
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.experience.ExperiencePolicyStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.mangaflow.storage.LibraryBackupInfo
import dev.mangaflow.storage.LibraryStore
import androidx.compose.material.icons.outlined.SwapHoriz
import dev.mangaflow.storage.PageTurnGesture
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.WebDavBackupConfig
import dev.mangaflow.storage.WebDavBackupStore
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwitchThumb
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.rememberDelayedBusy
import dev.mangaflow.update.UpdateManager
import dev.mangaflow.update.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    libraryStore: LibraryStore,
    onRequestNotifications: () -> Unit,
    clearBrowseCache: suspend () -> Unit,
    clearMetadataCache: suspend () -> Unit,
    contentTopPadding: Dp = 0.dp,
    active: Boolean = true,
    sourceBackdrop: HazeState? = null,
    sourceRegistry: BodySourceRegistry? = null,
    sourcePlugins: SourcePluginStore? = null,
    sourceRepository: dev.mangaflow.data.UnifiedMangaRepository? = null,
    onOpenDownloads: () -> Unit = {},
    animeController: AnimeController? = null,
    onBrowseAnime: (dev.mangaflow.anime.AnimeDiscoveryProvider) -> Unit = {},
    openWebDavSettings: Boolean = false,
    onWebDavSettingsOpened: () -> Unit = {},
    onWebDavSettingsClosed: () -> Unit = {},
    onOpenAnimeDownloads: () -> Unit = {},
    onOpenLanReadingSync: (() -> Unit)? = null,
    onOpenMangaTracking: (() -> Unit)? = null,
    lanReadingConflictCount: Int = 0,
    desktopFindRequest: Int = 0,
) {
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    if (!libraryReady) {
        LibraryRestoreStatus(libraryStore)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val screenActive = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val operations = remember(context) { ApplicationOperations.get(context) }
    val operationStates by operations.state.collectAsStateWithLifecycle()
    val backupOperation = remember(operationStates) { operations.find(UserOperationKeys.BACKUP) }
    val preferences by libraryStore.preferences.collectAsStateWithLifecycle()
    val update by updateManager.state.collectAsStateWithLifecycle()
    var automatic by remember(updateManager) { mutableStateOf(updateManager.automatic) }
    var notifications by remember { mutableStateOf(notificationsEnabled(context)) }
    var expandedNotes by remember(update.release?.versionCode) { mutableStateOf(false) }
    val routeStore = remember(context) { DownloadRoutingStore.get(context) }
    val routes by routeStore.state.collectAsStateWithLifecycle()
    val experienceStore = remember(context) { ExperiencePolicyStore.get(context) }
    val experienceModeFlow = remember(experienceStore) {
        experienceStore.state.map { it.mode }.distinctUntilChanged()
    }
    val experienceMode by experienceModeFlow.collectAsStateWithLifecycle(initialValue = experienceStore.state.value.mode)
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val desktop = platformKind == PlatformKind.DESKTOP
    val appearanceManga = if (desktop) remember(libraryStore) {
        kotlinx.coroutines.flow.combine(libraryStore.history, libraryStore.favorites) { history, favorites ->
            history.firstOrNull { it.manga.coverUrl.isNotBlank() }?.manga
                ?: favorites.firstOrNull { it.coverUrl.isNotBlank() }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null).value else null
    var settingsCategory by rememberSaveable { mutableStateOf(0) }
    val settingsFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val settingsScroll = rememberLazyListState()
    LaunchedEffect(settingsCategory, desktop) {
        if (desktop) { settingsFocus.clearFocus(); settingsScroll.scrollToItem(0) }
    }
    val cacheScope = rememberCoroutineScope()
    var showStorage by remember { mutableStateOf(false) }
    var showStorageModels by remember { mutableStateOf(false) }
    var storageDestination by remember { mutableStateOf<String?>(null) }
    val storageBackdrop = sourceBackdrop ?: dev.chrisbanes.haze.rememberHazeState()
    var exportSaving by remember { mutableStateOf(false) }
    val backupBusy = if (exportSaving) "export-saving" else backupOperation?.takeIf { it.active }?.kind?.let { kind -> when (kind) {
        ApplicationOperationKind.BACKUP_EXPORT -> "export"
        ApplicationOperationKind.BACKUP_INSPECT -> "inspect"
        ApplicationOperationKind.BACKUP_RESTORE -> "restore"
        ApplicationOperationKind.WEBDAV_UPLOAD -> "webdav-upload"
        ApplicationOperationKind.WEBDAV_READ -> "webdav-download"
        else -> "webdav-save"
    } }
    var receivedBackupOperation by rememberSaveable { mutableStateOf<String?>(null) }
    var backupNotice by rememberSaveable(stateSaver = backupNoticeSaver) { mutableStateOf<BackupNotice?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingLibraryRestore?>(null) }
    var pendingWebDavRestore by remember { mutableStateOf<PendingLibraryRestore?>(null) }
    val webDavStore = remember(context.applicationContext) { WebDavBackupStore.get(context.applicationContext) }
    val webDavConfig by webDavStore.config.collectAsStateWithLifecycle()
    val wallpaperStore = remember(context.applicationContext) { WallpaperStore.get(context.applicationContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    var showWebDav by remember { mutableStateOf(false) }
    var webDavPresent by remember { mutableStateOf(false) }
    var webDavConnectionOnly by remember { mutableStateOf(false) }
    var webDavNotice by rememberSaveable(stateSaver = backupNoticeSaver) { mutableStateOf<BackupNotice?>(null) }
    var expandedSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var showBodySources by remember { mutableStateOf(false) }
    var showAnimeSources by remember { mutableStateOf(false) }
    LaunchedEffect(screenActive) {
        if (!screenActive) showAnimeSources = false
    }
    var showNetworkDownloads by remember { mutableStateOf(false) }
    var showExperience by remember { mutableStateOf(false) }
    val foundHeader = remember { BringIntoViewRequester() }
    var foundSetting by remember { mutableStateOf<String?>(null) }
    var findRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var lastFindArrival by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val arrival = remember { Animatable(0f) }
    val motion = LocalMotionEnabled.current
    fun openSetting(key: String) {
        expandedSetting = key
        foundSetting = key
        findRequest++
    }
    fun searchTarget(key: String): Modifier = if (desktop && key == foundSetting) {
        Modifier.bringIntoViewRequester(foundHeader).drawBehind {
            val amount = arrival.value
            if (amount > 0f) {
                drawRoundRect(colors.primary.copy(alpha = .10f * amount), cornerRadius = CornerRadius(8.dp.toPx()))
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - 2.dp.toPx() else 2.dp.toPx()
                val inset = minOf(8.dp.toPx(), size.height / 3f)
                drawLine(colors.primary.copy(alpha = amount), Offset(x, inset), Offset(x, size.height - inset),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    } else Modifier
    LaunchedEffect(findRequest, foundSetting, screenActive, motion) {
        arrival.snapTo(0f)
        if (!desktop || !screenActive || foundSetting == null || findRequest <= lastFindArrival) return@LaunchedEffect
        lastFindArrival = findRequest
        androidx.compose.runtime.withFrameNanos { }
        foundHeader.bringIntoView()
        arrival.snapTo(1f)
        kotlinx.coroutines.delay(650)
        if (motion) arrival.animateTo(0f, tween(MangaMotion.Enter, easing = MangaMotion.ExitEasing))
        else arrival.snapTo(0f)
    }
    val toggleSetting: (String) -> Unit = { expandedSetting = if (expandedSetting == it) null else it }
    LaunchedEffect(screenActive, openWebDavSettings, backupBusy) {
        if (screenActive && openWebDavSettings && (backupBusy == null || backupBusy.startsWith("webdav-"))) {
            webDavNotice = null
            webDavConnectionOnly = true
            showWebDav = true
            onWebDavSettingsOpened()
        }
    }
    LaunchedEffect(showWebDav, screenActive) {
        if (showWebDav && screenActive) webDavPresent = true
    }
    LaunchedEffect(screenActive, webDavPresent, pendingWebDavRestore) {
        if (screenActive && !webDavPresent && !showWebDav) pendingWebDavRestore?.let {
            backupNotice = null
            pendingRestore = it
            pendingWebDavRestore = null
        }
    }

    val backupWriter = rememberTextDocumentSaver { result ->
        backupNotice = when {
            result.saved -> BackupNotice("阅读备份已导出")
            result.error != null -> BackupNotice(result.error, PreferenceNoticeKind.ERROR)
            else -> BackupNotice("已取消导出", PreferenceNoticeKind.INFO)
        }
        exportSaving = false
    }
    fun exportBackup() {
        if (backupBusy != null) return
        backupNotice = null
        webDavNotice = null
        if (!operations.exportReadingBackup(libraryStore).accepted)
            backupNotice = BackupNotice("已有备份操作，请稍后再导出。", PreferenceNoticeKind.ERROR)
    }
    val importBackup = rememberOpenDocumentPicker() { uri ->
        if (uri != null) {
            if (backupBusy == null) {
                backupNotice = null
                webDavNotice = null
                val handle = operations.inspectReadingBackup(context, libraryStore, uri)
                if (!handle.accepted) {
                    releasePickedDocuments(listOf(uri))
                    backupNotice = BackupNotice("已有备份操作，请稍后再选择文件。", PreferenceNoticeKind.ERROR)
                }
            } else releasePickedDocuments(listOf(uri))
        } else if (backupBusy == null) {
            webDavNotice = null
            backupNotice = BackupNotice("已取消选择备份文件", PreferenceNoticeKind.INFO)
        }
    }

    LaunchedEffect(backupOperation?.id, backupOperation?.phase, screenActive) {
        val operation = backupOperation ?: return@LaunchedEffect
        if (!screenActive || operation.active || operation.id == receivedBackupOperation) return@LaunchedEffect
        val remote = operation.kind in setOf(ApplicationOperationKind.WEBDAV_SAVE,
            ApplicationOperationKind.WEBDAV_UPLOAD, ApplicationOperationKind.WEBDAV_READ)
        fun deliver(receipt: BackupNotice?) {
            backupNotice = if (remote) null else receipt
            webDavNotice = if (remote) receipt else null
        }
        if (operation.phase != ApplicationOperationPhase.SUCCEEDED) {
            val cancelled = operation.phase == ApplicationOperationPhase.CANCELLED
            deliver(BackupNotice(operation.message ?: if (cancelled) "已取消${operation.title}" else "${operation.title}未完成",
                if (cancelled) PreferenceNoticeKind.INFO else PreferenceNoticeKind.ERROR))
            operations.consumeResult(operation.id)
            receivedBackupOperation = operation.id
            return@LaunchedEffect
        }
        when (operation.kind) {
            ApplicationOperationKind.BACKUP_EXPORT -> operations.result<String>(operation.id)?.let { result ->
                val payload = result.await()
                operations.consumeResult(operation.id)
                // The document picker belongs only to this active UI, never to the worker.
                exportSaving = true
                backupWriter.launch("MangaFlow-backup.json", payload)
            }
            ApplicationOperationKind.BACKUP_INSPECT -> operations.result<InspectedReadingBackup>(operation.id)?.let { result ->
                val inspected = result.await()
                pendingRestore = PendingLibraryRestore(inspected.payload, inspected.info)
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.BACKUP_RESTORE -> operations.result<LibraryBackupInfo>(operation.id)?.let { result ->
                val restored = result.await()
                deliver(BackupNotice("已恢复 ${restored.favorites} 本收藏、${restored.history} 条阅读记录"))
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_READ -> operations.result<InspectedReadingBackup?>(operation.id)?.let { result ->
                result.await()?.let { inspected ->
                    val pending = PendingLibraryRestore(inspected.payload, inspected.info)
                    if (showWebDav || webDavPresent) { pendingWebDavRestore = pending; showWebDav = false }
                    else pendingRestore = pending
                    deliver(BackupNotice("已读取远端备份"))
                }
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_UPLOAD -> operations.result<Any?>(operation.id)?.let {
                deliver(BackupNotice("阅读备份已上传"))
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_SAVE -> operations.result<Any?>(operation.id)?.let {
                deliver(if (webDavStore.config.value.configured)
                    BackupNotice("连接设置已保存 · 尚未验证", PreferenceNoticeKind.INFO, savedConfiguration = true)
                    else BackupNotice("本机连接设置已清除"))
                operations.consumeResult(operation.id)
            }
            else -> Unit
        }
        if (!operation.statusDurable && operation.message != null) {
            val receipt = if (remote) webDavNotice else backupNotice
            deliver(receipt?.copy(message = "${receipt.message}（操作记录未保存，重启后请确认结果）", kind = PreferenceNoticeKind.INFO)
                ?: BackupNotice(operation.message, PreferenceNoticeKind.INFO))
        }
        receivedBackupOperation = operation.id
    }

    fun runWebDav(action: String, draft: WebDavBackupDraft? = null) {
        if (backupBusy != null) return
        backupNotice = null
        webDavNotice = null
        try {
            val route = if (action == "upload" || action == "download") routeStore.snapshot(DownloadCategory.WEBDAV)
                else DownloadRouteSnapshot(DownloadRouteMode.SYSTEM)
            val accepted = draft?.let { WebDavOperationDraft(it.endpoint, it.username, it.password, it.remotePath) }
            val operation = operations.webDav(libraryStore, webDavStore, action, accepted, route)
            if (!operation.accepted) webDavNotice = BackupNotice("已有备份操作，请稍后重试", PreferenceNoticeKind.ERROR)
        } catch (_: Exception) {
            webDavNotice = BackupNotice("备份线路不可用，请检查连接设置", PreferenceNoticeKind.ERROR)
        }
    }

    LaunchedEffect(context) { notifications = queryNotificationsEnabled(context) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cacheScope.launch { notifications = queryNotificationsEnabled(context) }
                automatic = updateManager.automatic
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backupFeedback = when {
        exportSaving -> PreferenceNotice("等待选择保存位置", PreferenceNoticeKind.INFO)
        backupOperation?.active == true -> PreferenceNotice(backupOperation.message ?: backupOperation.title, PreferenceNoticeKind.WORKING)
        backupNotice != null || webDavNotice != null -> (backupNotice ?: webDavNotice)?.presentation()
        backupOperation != null && (backupOperation.phase == ApplicationOperationPhase.FAILED ||
            backupOperation.phase == ApplicationOperationPhase.INTERRUPTED) ->
            PreferenceNotice(backupOperation.message ?: "${backupOperation.title}未完成", PreferenceNoticeKind.ERROR)
        backupOperation != null && !backupOperation.statusDurable -> PreferenceNotice(
            backupOperation.message ?: "操作记录未保存", PreferenceNoticeKind.INFO)
        else -> null
    }
    val settingsContent: @Composable (Dp) -> Unit = { paneTopPadding ->
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = settingsScroll,
        modifier = Modifier.fillMaxSize().imePadding().background(
            if (LocalWallpaperActive.current) Color.Transparent else colors.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = paneTopPadding, bottom = 32.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
    ) {
        item {
            // Keep the import controls composed while scrolling; their in-progress copy owns this lifetime.
            val selected = if (desktop) settingsCategory else null
            val sections: @Composable () -> Unit = {
                RetainedSettingsCategory(0, selected) {
                SettingsSection("阅读", sourceBackdrop) {
                    if (desktop) PreferenceSectionLabel("翻页方式")
                    SettingsDisclosure("阅读顺序", when (preferences.mode) {
                        ReadingMode.VERTICAL -> "纵向滚动"
                        ReadingMode.LEFT_TO_RIGHT -> "从左至右"
                        ReadingMode.RIGHT_TO_LEFT -> "从右至左"
                    }, Icons.Outlined.SwapVert, expandedSetting == "mode", { toggleSetting("mode") }, headerModifier = searchTarget("mode")) {
                        SettingsChoices(
                            choices = listOf(ReadingMode.VERTICAL to "纵向",
                                ReadingMode.LEFT_TO_RIGHT to "左至右", ReadingMode.RIGHT_TO_LEFT to "右至左"),
                            selected = preferences.mode,
                            onSelected = { libraryStore.updatePreferences(preferences.copy(mode = it)) },
                        )
                    }
                    SettingsDisclosure("横向翻页手势", when (preferences.pageTurnGesture) {
                        PageTurnGesture.FOLLOW_READING_ORDER -> "跟随阅读顺序"
                        PageTurnGesture.SWIPE_LEFT_NEXT -> "左滑下一页"
                        PageTurnGesture.SWIPE_RIGHT_NEXT -> "右滑下一页"
                    }, Icons.Outlined.SwapHoriz, expandedSetting == "page-turn", { toggleSetting("page-turn") }, headerModifier = searchTarget("page-turn")) {
                        SettingsChoices(choices = listOf(
                            PageTurnGesture.FOLLOW_READING_ORDER to "跟随顺序",
                            PageTurnGesture.SWIPE_LEFT_NEXT to "左滑下一页",
                            PageTurnGesture.SWIPE_RIGHT_NEXT to "右滑下一页"),
                            selected = preferences.pageTurnGesture,
                            onSelected = { libraryStore.updatePageTurnGesture(it) })
                    }
                    if (desktop) PreferenceSectionLabel("阅读画面", Modifier.padding(top = 12.dp))
                    SettingsDisclosure("阅读底色", when (preferences.theme) {
                        ReaderTheme.PAPER -> "纸白"
                        ReaderTheme.SEPIA -> "暖纸"
                        ReaderTheme.INK -> "墨黑"
                    }, Icons.Outlined.Palette, expandedSetting == "paper", { toggleSetting("paper") }, headerModifier = searchTarget("paper")) {
                        PaperThemePicker(preferences.theme, desktopPreview = desktop, manga = appearanceManga,
                            repository = sourceRepository) { theme ->
                            libraryStore.updatePreferences(preferences.copy(theme = theme))
                        }
                    }
                    Column {
                        Box(searchTarget("awake")) { SettingsToggle("阅读时保持亮屏", preferences.keepScreenOn, Icons.Outlined.LightMode) {
                            libraryStore.updatePreferences(preferences.copy(keepScreenOn = it))
                        } }
                        Box(searchTarget("page-gap")) { SettingsToggle("图片之间留白", preferences.pageGap, Icons.Outlined.ViewDay) {
                            libraryStore.updatePreferences(preferences.copy(pageGap = it))
                        } }
                    }
                }
                }
                RetainedSettingsCategory(1, selected) {
                SettingsSection("外观", sourceBackdrop) {
                    if (desktop) PreferenceSectionLabel("作品详情")
                    DetailAppearanceSettings(searchTarget("cover-tone"), if (foundSetting == "cover-tone") findRequest else 0,
                        manga = appearanceManga, repository = sourceRepository)
                    if (desktop) PreferenceSectionLabel("界面个性化", Modifier.padding(top = 12.dp))
                    SettingsDisclosure("字体", if (desktop) interfaceFontSettingValue() else "", Icons.Outlined.TextFields,
                        expandedSetting == "fonts", { toggleSetting("fonts") }, headerModifier = searchTarget("fonts")) {
                        FontSettings()
                    }
                    SettingsDisclosure("壁纸", when {
                        wallpaper.mediaPath == null -> "默认"
                        wallpaper.rotationEnabled -> "${wallpaper.items.size} 张 · 每 ${wallpaper.rotationMinutes} 分钟"
                        wallpaper.items.size > 1 -> "${wallpaper.items.size} 张 · 手动切换"
                        else -> wallpaper.name.ifBlank { "本地壁纸" }
                    }, Icons.Outlined.Image,
                        expandedSetting == "wallpaper", { toggleSetting("wallpaper") }, headerModifier = searchTarget("wallpaper")) {
                        WallpaperSettings(store = wallpaperStore, backdrop = sourceBackdrop)
                    }
                }
                }
                RetainedSettingsCategory(2, selected) {
                SettingsSection("数据", sourceBackdrop) {
                    val lanReadingSyncEntry: @Composable () -> Unit = {
                        onOpenLanReadingSync?.let { open ->
                            SettingsLinkRow("局域网阅读同步", when {
                                lanReadingConflictCount <= 0 -> "发现并配对设备 · 收藏、页码与书签"
                                desktop -> "$lanReadingConflictCount 处阅读差异待确认"
                                else -> "查看双方版本，选择继续阅读的位置"
                            }, Icons.Outlined.CloudSync, onClick = open)
                        }
                    }
                    val sourceActions: @Composable () -> Unit = {
                        sourceRegistry?.let { registry ->
                            val sources by registry.entries.collectAsStateWithLifecycle()
                            val added = sources.count { !it.removed }
                            val enabled = sources.count { !it.removed && it.enabled }
                            SettingsLinkRow("漫画源", "已启用 $enabled · 已添加 $added",
                                Icons.AutoMirrored.Outlined.MenuBook, inlineDetail = true, compact = true) {
                                showBodySources = true
                            }
                        }
                        if (animeController != null) {
                            SettingsLinkRow("动漫源", "内容来源与线路", Icons.Outlined.PlayCircleOutline,
                                inlineDetail = true, compact = true) {
                                showAnimeSources = true
                            }
                        }
                    }
                    if (desktop) {
                        PreferenceSectionLabel("备份、同步与存储")
                        lanReadingSyncEntry()
                    } else sourceActions()
                    SettingsDisclosure("备份与同步", when {
                        expandedSetting != "backup" && (backupBusy != null || backupFeedback?.kind == PreferenceNoticeKind.ERROR) ->
                            backupFeedback?.message.orEmpty()
                        !desktop && lanReadingConflictCount > 0 -> "$lanReadingConflictCount 处阅读差异待确认"
                        expandedSetting != "backup" && backupFeedback != null -> backupFeedback.message
                        desktop -> "漫画账号 · 阅读备份"
                        else -> "设备 · 漫画账号 · 阅读备份"
                    },
                        Icons.Outlined.CloudSync, expandedSetting == "backup", { toggleSetting("backup") }, headerModifier = searchTarget("backup")) {
                        Column {
                            if (!desktop) lanReadingSyncEntry()
                            onOpenMangaTracking?.let { open ->
                                SettingsLinkRow("漫画账号同步", "Bangumi · AniList", Icons.Outlined.CloudSync, onClick = open)
                            }
                            SettingsLinkRow("导出阅读备份",
                                when (backupBusy) {
                                    "export" -> "正在准备备份…"
                                    "export-saving" -> "等待选择保存位置"
                                    else -> "书架、进度、画面收藏与阅读统计"
                                }, Icons.Outlined.UploadFile, enabled = backupBusy == null) {
                                exportBackup()
                            }
                            SettingsLinkRow("恢复阅读备份",
                                if (backupBusy == "inspect" || backupBusy == "restore") "正在处理…" else "从备份文件恢复本机阅读数据",
                                Icons.Outlined.Restore, enabled = backupBusy == null) {
                                importBackup.launch(arrayOf("application/json", "text/plain"))
                            }
                            SettingsLinkRow("WebDAV 阅读备份", webDavStatusDetail(webDavConfig, backupBusy), Icons.Outlined.CloudSync,
                                enabled = backupBusy == null || backupBusy.startsWith("webdav-")) {
                                webDavNotice = null; showWebDav = true
                            }
                        }
                        PreferenceOperationFeedback(backupFeedback) {
                            val op = backupOperation
                            if (op?.cancellable == true && !exportSaving) TextButton(onClick = { operations.cancel(op.id) }) {
                                Text("取消")
                            }
                        }
                        Text("备份不包含账号、密码、缓存与漫画文件；恢复前会再次确认。",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    SettingsLinkRow("存储管理", "阅读缓存、离线下载与模型", Icons.Outlined.Storage,
                        inlineDetail = true) { showStorage = true }
                    if (desktop && (sourceRegistry != null || animeController != null)) {
                        PreferenceSectionLabel("内容来源", Modifier.padding(top = 12.dp))
                        sourceActions()
                    }
                }
                }
                RetainedSettingsCategory(3, selected) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSection("应用", sourceBackdrop) {
                        if (desktop) PreferenceSectionLabel("播放与连接")
                        SettingsLinkRow("体验策略", if (desktop) experienceModeLabel(experienceMode)
                            else "画质、补帧、缓冲与预读 · ${experienceModeLabel(experienceMode)}", Icons.Outlined.Tune,
                            inlineDetail = true) { showExperience = true }
                        SettingsLinkRow("网络与下载", networkRouteOverview(routes), Icons.Outlined.SwapVert,
                            inlineDetail = true) { showNetworkDownloads = true }
                        if (desktop) PreferenceSectionLabel("版本与更新", Modifier.padding(top = 12.dp))
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .animateContentSize(if (LocalMotionEnabled.current) tween(MangaMotion.Content) else snap()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val checked = update.lastChecked.takeIf { it > 0 }?.let(::settingsLastCheck)
                            val timestamp = update.lastChecked.takeIf { it > 0 }?.let {
                                formatInstantMillis(it, "yyyy-MM-dd HH:mm")
                            }
                            val updateAction = when (update.phase) {
                                UpdatePhase.CHECKING -> "检查中"
                                UpdatePhase.VERIFYING -> "核对中"
                                UpdatePhase.AVAILABLE -> if (update.downloaded > 0) "继续下载更新" else "下载更新"
                                UpdatePhase.DOWNLOADING -> "暂停下载"
                                UpdatePhase.READY -> if (platformKind == PlatformKind.IOS) "打开更新" else "安装更新"
                                UpdatePhase.ERROR -> "重试检查更新"
                                else -> "检查更新"
                            }
                            Row(searchTarget("updates").fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Outlined.SystemUpdate)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("软件更新", style = MaterialTheme.typography.titleSmall, color = foreground)
                                    val version = update.release?.let { "v${platformVersion(LocalContext.current)} → ${it.versionName}" }
                                        ?: "v${platformVersion(LocalContext.current)}"
                                    Text(listOfNotNull(version, checked).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = secondary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.then(if (timestamp == null) Modifier else Modifier
                                            .semantics { contentDescription = "$version，上次检查 $timestamp" }
                                            .clickable(role = Role.Button, onClickLabel = "查看完整检查时间") {
                                                showPlatformMessage(context, "上次检查 $timestamp")
                                            }))
                                }
                                val updateEnabled = update.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING)
                                val runUpdate: () -> Unit = {
                                        when (update.phase) {
                                            UpdatePhase.AVAILABLE -> updateManager.download()
                                            UpdatePhase.DOWNLOADING -> updateManager.cancelDownload()
                                            UpdatePhase.READY -> updateManager.install(context)
                                            UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.ERROR -> updateManager.check()
                                            else -> Unit
                                        }
                                }
                                val updateGlyph: @Composable () -> Unit = {
                                    MotionSwap(update.phase, Modifier.size(20.dp)) { phase ->
                                        when (phase) {
                                            UpdatePhase.CHECKING, UpdatePhase.VERIFYING ->
                                                CircularProgressIndicator(Modifier.size(15.dp), color = secondary, strokeWidth = 1.5.dp)
                                            UpdatePhase.AVAILABLE -> Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(20.dp), tint = colors.primary)
                                            UpdatePhase.DOWNLOADING -> Icon(Icons.Outlined.Pause, null, Modifier.size(20.dp), tint = foreground)
                                            UpdatePhase.READY -> Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(20.dp), tint = colors.primary)
                                            UpdatePhase.CURRENT -> Icon(if (desktop) Icons.Outlined.CheckCircle else Icons.Outlined.Refresh,
                                                null, Modifier.size(20.dp), tint = foreground)
                                            else -> Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp), tint = foreground)
                                        }
                                    }
                                }
                                if (desktop) TextButton(onClick = runUpdate, enabled = updateEnabled,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.textButtonColors(containerColor = colors.secondaryContainer,
                                        contentColor = colors.onSecondaryContainer),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                                    updateGlyph()
                                    Text(updateAction, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                                } else MotionIconButton(onClick = runUpdate, enabled = updateEnabled,
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = updateAction }) { updateGlyph() }
                            }
                            val status = when (update.phase) {
                                UpdatePhase.IDLE -> null
                                UpdatePhase.CURRENT -> update.message
                                UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.DOWNLOADING -> null
                                else -> update.message
                            }
                            if (!status.isNullOrBlank()) {
                                Text(status, style = MaterialTheme.typography.bodySmall,
                                    color = if (update.phase == UpdatePhase.ERROR) colors.error else secondary,
                                    modifier = Modifier.padding(start = 32.dp, bottom = 2.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            update.release?.let { release ->
                                if (release.notes.isNotBlank()) {
                                    if (desktop) SettingsDisclosure("更新说明", release.versionName, Icons.Outlined.Info,
                                        expandedNotes, { expandedNotes = !expandedNotes }, compact = true) {
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = foreground)
                                        }
                                    } else Text(release.notes,
                                        modifier = Modifier.padding(start = 32.dp).clickable(role = Role.Button,
                                            onClickLabel = if (expandedNotes) "收起更新说明" else "展开更新说明") { expandedNotes = !expandedNotes },
                                        style = MaterialTheme.typography.bodySmall, color = secondary,
                                        maxLines = if (expandedNotes) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (update.phase == UpdatePhase.AVAILABLE && release.bytes > 0 && update.downloaded == 0L) {
                                    Text(settingsBytes(release.bytes), style = MaterialTheme.typography.bodySmall,
                                        color = secondary, modifier = Modifier.padding(start = 32.dp))
                                }
                            }
                            if (update.phase == UpdatePhase.DOWNLOADING) {
                                Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (update.total > 0L) LinearProgressIndicator(progress = { update.progress },
                                        modifier = Modifier.weight(1f).height(2.dp).clip(CircleShape),
                                        color = colors.primary.copy(alpha = .8f), trackColor = colors.primary.copy(alpha = .10f))
                                    else LinearProgressIndicator(modifier = Modifier.weight(1f).height(2.dp).clip(CircleShape),
                                        color = colors.primary.copy(alpha = .8f), trackColor = colors.primary.copy(alpha = .10f))
                                    Text(if (update.total > 0L) "${settingsBytes(update.downloaded)} / ${settingsBytes(update.total)}"
                                        else "已下载 ${settingsBytes(update.downloaded)}",
                                        style = MaterialTheme.typography.bodySmall, color = secondary)
                                }
                            }
                        }
                        DownloadRouteControl(DownloadCategory.UPDATES,
                            modifier = Modifier.padding(start = 24.dp),
                            enabled = update.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING), backdrop = sourceBackdrop)
                        Column(searchTarget("update-options").fillMaxWidth().padding(top = 4.dp)) {
                            SettingsToggle("自动检查更新", automatic, Icons.Outlined.Update) {
                                automatic = it
                                updateManager.setAutomatic(it)
                            }
                            SettingsLinkRow(
                                title = "更新提醒", detail = if (notifications) "已开启" else "未开启",
                                icon = Icons.Outlined.NotificationsNone,
                                inlineDetail = true, compact = true,
                                onClick = {
                                    if (notifications) {
                                        if (!openNotificationSettings(context)) showPlatformMessage(context, "暂时无法打开通知设置")
                                    } else onRequestNotifications()
                                },
                            )
                        }
                    }
                    Box(searchTarget("project").fillMaxWidth().padding(horizontal = if (desktop) 0.dp else 16.dp)) {
                        SettingsLinkRow("项目主页", "", Icons.Outlined.Code, external = true, compact = true) { if (!openUrl(context, "https://github.com/${UpdateManager.REPOSITORY}")) showPlatformMessage(context, "暂时无法打开项目主页") }
                    }
                }
                }
            }
            if (desktop) Box(Modifier.fillMaxWidth().clipToBounds()) { sections() }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { sections() }
        }
    }
    if (desktop) DesktopVerticalScrollbar(settingsScroll,
        Modifier.align(Alignment.CenterEnd).padding(top = paneTopPadding))
    }
    }
    if (desktop) {
        val links = buildList {
            add(DesktopSettingsLink(0, "阅读顺序", "纵向 横向 左右 翻页") { openSetting("mode") })
            add(DesktopSettingsLink(0, "横向翻页手势", "方向 左滑 右滑 翻页") { openSetting("page-turn") })
            add(DesktopSettingsLink(0, "阅读底色", "主题 纸白 暖纸 墨黑 背景") { openSetting("paper") })
            add(DesktopSettingsLink(0, "阅读时保持亮屏", "屏幕 常亮 熄屏") { openSetting("awake") })
            add(DesktopSettingsLink(0, "图片之间留白", "页面 间距 空隙") { openSetting("page-gap") })
            add(DesktopSettingsLink(1, "封面氛围", "详情 模糊 亮度 背景") { openSetting("cover-tone") })
            add(DesktopSettingsLink(1, "字体", "字形 排版 导入") { openSetting("fonts") })
            add(DesktopSettingsLink(1, "壁纸", "图片 桌面 轮换 背景", Icons.Outlined.Image) { openSetting("wallpaper") })
            add(DesktopSettingsLink(2, "备份与同步", "恢复 导出 WebDAV 进度", Icons.Outlined.CloudSync) { openSetting("backup") })
            add(DesktopSettingsLink(2, "存储管理", "缓存 清理 离线 下载 模型", Icons.Outlined.Storage) { showStorage = true })
            onOpenLanReadingSync?.let { add(DesktopSettingsLink(2, "局域网阅读同步", "设备 配对 书签", open = it)) }
            onOpenMangaTracking?.let { add(DesktopSettingsLink(2, "漫画账号同步", "Bangumi AniList 追踪", open = it)) }
            if (sourceRegistry != null) add(DesktopSettingsLink(2, "漫画源", "来源 站点 插件") { showBodySources = true })
            if (animeController != null) add(DesktopSettingsLink(2, "动漫源", "来源 播放") { showAnimeSources = true })
            add(DesktopSettingsLink(3, "体验策略", "画质 补帧 缓冲 预读") { showExperience = true })
            add(DesktopSettingsLink(3, "网络与下载", "线路 代理 网络 下载") { showNetworkDownloads = true })
            add(DesktopSettingsLink(3, "软件更新", "版本 安装 检查 下载") { openSetting("updates") })
            add(DesktopSettingsLink(3, "自动检查与更新提醒", "通知 提醒 开关") { openSetting("update-options") })
            add(DesktopSettingsLink(3, "项目主页", "GitHub 仓库 源代码") { openSetting("project") })
        }
        DesktopSettingsLayout(settingsCategory, { index ->
            foundSetting = null
            settingsCategory = index
        }, contentTopPadding, links, desktopFindRequest, settingsContent)
    } else settingsContent(contentTopPadding)

    if (showBodySources && sourceRegistry != null) {
        BodySourceSettingsSheet(sourceRegistry, sourceBackdrop, plugins = sourcePlugins,
            onDismiss = { showBodySources = false }, repository = sourceRepository)
    }

    animeController?.let { controller ->
        val animeState by controller.state.collectAsStateWithLifecycle()
        AnimeSourceManager(showAnimeSources && screenActive, animeState, controller, storageBackdrop,
            onDismiss = { showAnimeSources = false },
            onBrowse = { provider -> if (screenActive) onBrowseAnime(provider) })
    }

    NetworkDownloadDialog(showNetworkDownloads, sourceBackdrop, onDismiss = { showNetworkDownloads = false },
        repository = sourceRepository, registry = sourceRegistry)
    ExperienceSettingsDialog(showExperience, sourceBackdrop, onDismiss = { showExperience = false },
        onOpenStorage = { showStorage = true }, onOpenNetwork = { showNetworkDownloads = true },
        onOpenSourceChecks = if (sourceRegistry != null) ({ showBodySources = true }) else null)

    StorageManagementDialog(showStorage, storageBackdrop, libraryStore, repository = sourceRepository, active = screenActive,
        onDismiss = { showStorage = false },
        onDownloads = { storageDestination = "downloads"; showStorage = false },
        onAnimeDownloads = { storageDestination = "anime-downloads"; showStorage = false },
        onModels = { storageDestination = "models"; showStorage = false },
        onClosed = {
            val target = storageDestination
            storageDestination = null
            if (target == "downloads") onOpenDownloads()
            if (target == "anime-downloads") onOpenAnimeDownloads()
            if (target == "models") showStorageModels = true
        })
    dev.mangaflow.reader.panels.PanelModelsDialog(showStorageModels, storageBackdrop) { showStorageModels = false }

    BackupRestoreDialog(
        pending = pendingRestore.takeIf { screenActive },
        backdrop = sourceBackdrop,
        busy = backupBusy == "restore",
        error = backupNotice?.takeIf { it.failed && pendingRestore != null }?.message,
        onDismiss = {
            if (pendingRestore != null) {
                pendingRestore = null
                webDavNotice = null
                backupNotice = BackupNotice("已取消恢复，阅读数据未更改", PreferenceNoticeKind.INFO)
            }
        },
        onConfirm = {
            val pending = pendingRestore ?: return@BackupRestoreDialog
            if (backupBusy != null) return@BackupRestoreDialog
            val handle = operations.restoreReadingBackup(libraryStore, pending.payload)
            if (handle.accepted) {
                backupNotice = null
                webDavNotice = null
                pendingRestore = null
            } else backupNotice = BackupNotice("已有备份操作，请稍后再恢复。", PreferenceNoticeKind.ERROR)
        },
    )
    WebDavBackupDialog(
        visible = showWebDav && screenActive,
        backdrop = sourceBackdrop,
        config = webDavConfig,
        busy = backupBusy?.takeIf { it.startsWith("webdav-") },
        cancellable = backupOperation?.cancellable == true,
        notice = webDavNotice,
        connectionOnly = webDavConnectionOnly,
        onDismiss = { showWebDav = false },
        onCancel = { backupOperation?.takeIf { it.cancellable }?.let { operations.cancel(it.id) } },
        onClosed = {
            // Present the inspected restore only after the connection workspace has left.
            if (!showWebDav || !screenActive) {
                webDavPresent = false
                if (webDavConnectionOnly) {
                    webDavConnectionOnly = false
                    if (screenActive) onWebDavSettingsClosed()
                }
                if (screenActive) pendingWebDavRestore?.let {
                    backupNotice = null
                    pendingRestore = it
                    pendingWebDavRestore = null
                }
            }
        },
        onSave = { runWebDav("save", it) },
        onUpload = { runWebDav("upload", it) },
        onDownload = { runWebDav("download", it) },
        onClear = { runWebDav("clear") },
    )
}

private data class PendingLibraryRestore(
    val payload: String,
    val info: LibraryBackupInfo,
)

private data class BackupNotice(
    val message: String,
    val kind: PreferenceNoticeKind = PreferenceNoticeKind.DONE,
    val savedConfiguration: Boolean = false,
) {
    val failed: Boolean get() = kind == PreferenceNoticeKind.ERROR
    fun presentation() = PreferenceNotice(message, kind)
}

// Keep the last visible receipt across navigation. The backup payload stays in memory.
private val backupNoticeSaver = listSaver<BackupNotice?, String>(
    save = { notice -> notice?.let { listOf(it.message, it.kind.name, it.savedConfiguration.toString()) }.orEmpty() },
    restore = { fields -> fields.takeIf { it.size == 3 }?.let {
        BackupNotice(it[0], PreferenceNoticeKind.valueOf(it[1]), it[2].toBoolean())
    } },
)

private data class WebDavBackupDraft(
    val endpoint: String,
    val username: String,
    val password: String,
    val remotePath: String,
)

@Composable
private fun ReadingBackupPanel(backdrop: HazeState?, modifier: Modifier, content: @Composable () -> Unit) {
    if (backdrop != null) {
        LiquidGlassSurface(backdrop, modifier, cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) { content() }
    } else {
        val colors = MaterialTheme.colorScheme
        Surface(modifier, shape = RoundedCornerShape(22.dp),
            color = lerp(colors.background, colors.surface, .85f), tonalElevation = 0.dp) { content() }
    }
}

@Composable
private fun WebDavBackupDialog(
    visible: Boolean,
    backdrop: HazeState?,
    config: WebDavBackupConfig,
    busy: String?,
    cancellable: Boolean,
    notice: BackupNotice?,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onClosed: () -> Unit,
    onSave: (WebDavBackupDraft) -> Unit,
    onUpload: (WebDavBackupDraft) -> Unit,
    onDownload: (WebDavBackupDraft) -> Unit,
    onClear: () -> Unit,
    connectionOnly: Boolean = false,
) {
    var endpoint by remember(visible, config.endpoint) { mutableStateOf(config.endpoint) }
    var username by remember(visible, config.username) { mutableStateOf(config.username) }
    var password by remember(visible, config.hasPassword) { mutableStateOf("") }
    var remotePath by remember(visible, config.remotePath) { mutableStateOf(config.remotePath) }
    var editing by remember { mutableStateOf(!config.configured) }
    var advanced by remember { mutableStateOf(false) }
    var savingRequested by remember { mutableStateOf(false) }
    var pendingUpload by remember(visible) { mutableStateOf<WebDavBackupDraft?>(null) }
    val overviewScroll = rememberScrollState()
    val active = visible && busy == null
    val enabled = active && endpoint.isNotBlank() && remotePath.isNotBlank()
    LaunchedEffect(visible) {
        if (visible) { editing = !config.configured; advanced = false }
        else { password = ""; savingRequested = false }
    }
    LaunchedEffect(busy, notice, savingRequested) {
        if (savingRequested && busy == null && notice != null) {
            if (notice.savedConfiguration) { password = ""; editing = false }
            savingRequested = false
        }
    }
    val draft = { WebDavBackupDraft(endpoint, username, password, remotePath) }
    val resetDraft = {
        endpoint = config.endpoint; username = config.username; password = ""
        remotePath = config.remotePath; editing = false; advanced = false
    }
    val colors = MaterialTheme.colorScheme
    val dismissStep = { if (pendingUpload != null) pendingUpload = null else onDismiss() }
    MotionDialog(visible = visible, onDismissRequest = dismissStep, onClosed = onClosed, maximumWidth = 520.dp) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp).imePadding()) {
            val height = minOf(maxHeight, LocalMangaWindow.current.height * .88f, 680.dp)
            ReadingBackupPanel(backdrop, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().heightIn(max = height).padding(horizontal = 16.dp, vertical = 8.dp)) dialogBody@{
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudSync, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                        PreferenceHeading(if (connectionOnly) "WebDAV 连接" else if (pendingUpload == null) "WebDAV 备份" else "确认上传备份",
                            Modifier.weight(1f).padding(start = 10.dp))
                        val closeLabel = if (pendingUpload == null) "关闭备份" else "取消上传"
                        MotionIconButton(onClick = dismissStep, enabled = visible, modifier = Modifier.size(44.dp), tooltip = closeLabel) {
                            Icon(Icons.Outlined.Close, closeLabel, Modifier.size(19.dp))
                        }
                    }
                    val upload = pendingUpload
                    if (upload != null) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("服务器", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(upload.endpoint, style = MaterialTheme.typography.bodyMedium)
                            Text("远端文件", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(upload.remotePath, style = MaterialTheme.typography.bodyMedium)
                            Text("此路径已有备份时，本机备份会覆盖它。", style = MaterialTheme.typography.bodySmall, color = colors.error)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = dismissStep) { Text("取消") }
                            Button(onClick = {
                                pendingUpload = null
                                onUpload(upload)
                            }, enabled = active,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                                Text("确认上传")
                            }
                        }
                        return@dialogBody
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(overviewScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyShelfReveal(!editing && config.configured) {
                            Column {
                                WebDavTransferAction(Icons.Outlined.Tune, "连接设置", config.endpoint, active, opensDetails = true) {
                                    editing = true
                                }
                                Text(webDavStatusDetail(config, busy), style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant)
                                if (busy != null && cancellable) TextButton(onClick = onCancel) { Text("取消后台操作") }
                            }
                        }
                        CopyShelfReveal(editing) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("HTTPS 连接 · 密码加密保存在本机", style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant)
                                OutlinedTextField(
                                    value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(),
                                    enabled = active, singleLine = true, label = { Text("服务器") },
                                    placeholder = { Text("https://cloud.example.com/dav/") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(14.dp), colors = settingsInputColors(),
                                )
                                OutlinedTextField(
                                    value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(),
                                    enabled = active, singleLine = true, label = { Text("用户名") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(14.dp), colors = settingsInputColors(),
                                )
                                OutlinedTextField(
                                    value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                                    enabled = active, singleLine = true, label = { Text("密码") },
                                    placeholder = { Text(if (config.hasPassword) "留空保留；更换服务器或用户名需重输" else "可留空") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    shape = RoundedCornerShape(14.dp), colors = settingsInputColors(),
                                )
                            }
                        }
                        SettingsDisclosure("文件与路由", remotePath, Icons.Outlined.FolderOpen, advanced,
                            { if (active) advanced = !advanced }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = remotePath, onValueChange = { remotePath = it }, modifier = Modifier.fillMaxWidth(),
                                    enabled = active && editing, singleLine = true, label = { Text("远端文件") },
                                    placeholder = { Text(WebDavBackupConfig.DEFAULT_REMOTE_PATH) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    shape = RoundedCornerShape(14.dp), colors = settingsInputColors(),
                                )
                                DownloadRouteControl(DownloadCategory.WEBDAV, enabled = active)
                            }
                        }
                        CopyShelfReveal(!editing && config.configured && !connectionOnly) {
                            Column {
                                WebDavTransferAction(Icons.Outlined.CloudDownload, "预览远端备份", "读取后可查看内容并选择恢复", enabled) {
                                    onDownload(draft())
                                }
                                WebDavTransferAction(Icons.Outlined.CloudUpload, "上传本机备份", "先确认服务器和远端文件", enabled,
                                    opensDetails = true) {
                                    pendingUpload = draft()
                                }
                            }
                        }
                        val phase = when (busy) {
                            "webdav-save" -> "正在保存连接"
                            "webdav-upload" -> "正在上传备份"
                            "webdav-download" -> "正在读取备份"
                            null -> null
                            else -> "正在处理配置"
                        }
                        PreferenceOperationFeedback(phase?.let { PreferenceNotice(it, PreferenceNoticeKind.WORKING) }
                            ?: notice?.presentation())
                        Text(if (connectionOnly) "成人阅读进度使用此目录中的独立文件。"
                            else "书架、阅读位置与偏好；不含账号、缓存和漫画文件。手动备份，不自动双向同步。",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    CopyShelfReveal(editing) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (config.configured) MotionIconButton(onClick = onClear, enabled = active,
                                modifier = Modifier.size(44.dp), tooltip = "清除本机连接配置，保留远端备份") {
                                Icon(Icons.Outlined.DeleteOutline, "清除本机连接配置，保留远端备份", Modifier.size(18.dp), tint = colors.error)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { if (config.configured) resetDraft() else onDismiss() }, enabled = active) { Text("取消") }
                            Button(onClick = { savingRequested = true; onSave(draft()) }, enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) { Text("保存连接") }
                        }
                    }
                }
            }
        }
    }
}

/** Existing settings-row and beUI press primitives, bound to real backup operations. */
@Composable
private fun WebDavTransferAction(icon: ImageVector, title: String, detail: String, enabled: Boolean,
    opensDetails: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
        Icon(icon, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (opensDetails) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun BackupRestoreDialog(
    pending: PendingLibraryRestore?,
    backdrop: HazeState?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    var retained by remember { mutableStateOf(pending) }
    androidx.compose.runtime.SideEffect { if (pending != null) retained = pending }
    MotionDialog(visible = pending != null, onDismissRequest = onDismiss,
        onClosed = { if (pending == null) retained = null }, maximumWidth = if (desktop) 592.dp else 520.dp) {
        (pending ?: retained)?.let { restore ->
            val colors = MaterialTheme.colorScheme
            ReadingBackupPanel(backdrop, Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column(Modifier.heightIn(max = LocalMangaWindow.current.height * .84f)
                    .padding(horizontal = 18.dp, vertical = 12.dp)) {
                    PreferenceHeading("恢复阅读备份", Modifier.padding(vertical = 6.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val date = restore.info.createdAt.takeIf { it > 0 }?.let {
                            formatInstantMillis(it, "yyyy-MM-dd HH:mm")
                        } ?: "未知时间"
                        Text(if (desktop) "备份时间 · $date" else date, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (desktop) {
                            val info = restore.info
                            val totals = listOf("书架收藏" to "${info.favorites} 本", "阅读记录" to "${info.history} 条",
                                "画面收藏" to "${info.bookmarks} 项")
                            BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh,
                                RoundedCornerShape(10.dp)).padding(12.dp)) {
                                val full = maxWidth >= 320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                                if (full) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    totals.forEach { (label, value) ->
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                            Text(value, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    totals.forEach { (label, value) -> Row(Modifier.fillMaxWidth()) {
                                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(value, style = MaterialTheme.typography.bodyMedium)
                                    } }
                                }
                            }
                            Text(listOfNotNull(info.animeEntries?.let { "追番记录 $it 部" } ?: "当前追番记录将保留",
                                info.animeSources?.let { "动漫源 $it 个" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        } else Text(
                            "${restore.info.favorites} 本收藏 · ${restore.info.history} 条阅读记录 · ${restore.info.bookmarks} 项画面收藏" +
                                (restore.info.animeEntries?.let { " · $it 部追番记录" } ?: " · 现有追番记录保留") +
                                (restore.info.animeSources?.let { " · $it 个动漫源" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                        Text(
                            "这会覆盖本机书架、阅读位置、画面收藏、时长和阅读偏好。下载文件与登录状态保持不变。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                        CopyShelfValue(error) { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = colors.error)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = pending != null && !busy) { Text("取消") }
                        Button(onClick = onConfirm, enabled = pending != null && !busy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("覆盖并恢复")
                        }
                    }
                }
            }
        }
    }
}

private fun webDavStatusDetail(config: WebDavBackupConfig, busy: String?): String {
    return when (busy) {
        "webdav-save" -> "正在保存…"
        "webdav-upload" -> "正在上传…"
        "webdav-download" -> "正在读取…"
        "webdav-clear" -> "正在清除…"
        else -> {
            if (!config.configured) return "未配置"
            val uploadIsLatest = config.lastUploadAt >= config.lastDownloadAt
            val timestamp = if (uploadIsLatest) config.lastUploadAt else config.lastDownloadAt
            if (timestamp <= 0L) "已配置" else {
                val action = if (uploadIsLatest) "上次上传" else "上次读取"
                val date = formatInstantMillis(timestamp, "yyyy-MM-dd HH:mm")
                "$action $date"
            }
        }
    }
}

@Composable
internal fun settingsInputColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    val resting = lerp(colors.surface, colors.onSurface, .045f)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = lerp(colors.surface, colors.primary, .065f),
        unfocusedContainerColor = resting, disabledContainerColor = resting,
        disabledTextColor = colors.onSurfaceVariant,
        focusedBorderColor = colors.primary, unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
    )
}

@Composable
internal fun PreferenceHeading(title: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(title, modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading, letterSpacing = (-.25f).sp),
        fontWeight = FontWeight.SemiBold, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SettingsSection(title: String, backdrop: HazeState?, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (platformKind == PlatformKind.DESKTOP) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp),
                content = content)
        }
        return
    }
    val shape = RoundedCornerShape(22.dp)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PreferenceHeading(title, Modifier.padding(start = 16.dp))
        if (backdrop != null) {
            LiquidGlassSurface(state = backdrop, modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { body() }
        } else {
            Surface(Modifier.fillMaxWidth(), shape = shape, color = colors.surface,
                tonalElevation = 0.dp) { body() }
        }
    }
}

/** Retain the actual controls while collapsed, including an in-progress wallpaper import. */
@Composable
internal fun SettingsDisclosure(
    title: String,
    value: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    compact: Boolean = false,
    modified: Boolean = false,
    headerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val desktop = platformKind == PlatformKind.DESKTOP
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(), label = "setting-disclosure")
    Column(Modifier.fillMaxWidth().then(if (desktop) Modifier.drawBehind {
        val amount = reveal.value.coerceIn(0f, 1f)
        if (amount > 0f) drawRoundRect(colors.surfaceContainerLow.copy(alpha = .78f * amount),
            cornerRadius = CornerRadius(16.dp.toPx()))
    } else Modifier)) {
        val header: @Composable (Boolean) -> Unit = { stacked ->
            Row(headerModifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).preferencePointerSurface(interaction)
                .semantics(mergeDescendants = compact) {
                    stateDescription = listOf(if (compact) "" else value,
                        if (modified) "已自定义" else "", if (expanded) "已展开" else "已收起")
                        .filter(String::isNotBlank).joinToString("，")
                }
                .clickable(interactionSource = interaction, indication = null,
                    role = if (compact) Role.Button else null, onClick = onToggle)
                .motionPress(interaction, pressedScale = .99f)
                .heightIn(min = if (desktop) 54.dp else if (compact) 44.dp else 52.dp)
                .padding(horizontal = if (desktop) 10.dp else 0.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
                SettingsRowIcon(icon, selected = expanded)
                if (stacked) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                } else {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface, maxLines = if (compact) 1 else Int.MAX_VALUE,
                        overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip)
                    if (value.isNotBlank()) Text(value, Modifier.weight(.85f), textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall, color = if (expanded) colors.primary else colors.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (compact && modified) Box(Modifier.size(4.dp).clip(CircleShape)
                    .background(colors.primary))
                Box(Modifier.width(16.dp).height(32.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp)
                        .graphicsLayer { rotationZ = 90f * reveal.value }, tint = colors.onSurfaceVariant)
                }
            }
        }
        if (value.isBlank()) header(false) else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimum = if (desktop) 396.dp else if (compact) 300.dp else 260.dp
            header(maxWidth < minimum * LocalDensity.current.fontScale.coerceAtLeast(1f))
        }
        Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(expanded)
            .layout { measurable, constraints ->
                // Keep the composed child alive for imports, but do not measure it when closed.
                if (reveal.value <= 0f) {
                    layout(constraints.minWidth.coerceAtLeast(0), 0) {}
                } else {
                    val body = measurable.measure(constraints.copy(minHeight = 0))
                    val extent = reveal.value.coerceIn(0f, 1f)
                    layout(body.width, (body.height * extent).toInt().coerceAtLeast(0)) {
                        body.placeRelativeWithLayer(0, 0) {
                            // Let the first line unfold immediately beneath its own header.
                            translationY = if (motion) -6.dp.toPx() * (1f - extent) else 0f
                            alpha = (extent / .62f).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            .padding(start = if (desktop) 14.dp else 0.dp, end = if (desktop) 14.dp else 0.dp,
                top = if (desktop) 8.dp else 4.dp, bottom = if (desktop) 16.dp else if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp), content = content)
    }
}

@Composable
internal fun SettingsRowIcon(icon: ImageVector, selected: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val ink by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant,
        if (motion) tween(MangaMotion.Quick) else snap(), label = "settings-control-ink")
    if (platformKind == PlatformKind.DESKTOP) {
        val surface = animateColorAsState(if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .045f),
            if (motion) tween(MangaMotion.Quick) else snap(), label = "settings-control-surface")
        Box(Modifier.size(32.dp).drawBehind {
            drawRoundRect(surface.value, cornerRadius = CornerRadius(10.dp.toPx()))
        }, contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = ink)
        }
    } else Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = ink)
    }
}

@Composable
internal fun Modifier.preferencePointerSurface(interaction: MutableInteractionSource): Modifier {
    if (platformKind != PlatformKind.DESKTOP) return this
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val tint = animateColorAsState(when {
        focused -> colors.primary.copy(alpha = .09f)
        hovered -> colors.onSurface.copy(alpha = .045f)
        else -> Color.Transparent
    }, if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "preference-pointer")
    return drawBehind { drawRoundRect(tint.value, cornerRadius = CornerRadius(10.dp.toPx())) }
}


@Composable
internal fun <T> SettingsChoices(choices: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTabs(
        selected = choices.indexOfFirst { it.first == selected }, labels = choices.map { it.second },
        onSelect = { onSelected(choices[it].first) }, role = Role.RadioButton,
        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface,
        fullWidthIndicator = true, controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
    )
}

@Composable
internal fun SettingsToggle(title: String, checked: Boolean, icon: ImageVector, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).preferencePointerSurface(interaction)
            .toggleable(checked, enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = onCheckedChange)
            .motionPress(interaction, enabled = enabled, pressedScale = .99f)
            .graphicsLayer { alpha = if (enabled) 1f else .45f }.heightIn(min = 52.dp)
            .padding(horizontal = if (platformKind == PlatformKind.DESKTOP) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsRowIcon(icon, selected = checked)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            MotionSwitchThumb(checked, interaction)
        }
    }
}

@Composable
internal fun SettingsLinkRow(title: String, detail: String, icon: ImageVector, external: Boolean = false,
    inlineDetail: Boolean = false, compact: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val desktop = platformKind == PlatformKind.DESKTOP
    val row: @Composable (Boolean) -> Unit = { trailing ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).preferencePointerSurface(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .99f)
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = .5f })
            .heightIn(min = if (desktop) 54.dp else if (compact) 48.dp else 56.dp)
            .padding(horizontal = if (desktop) 10.dp else 0.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRowIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                if (!trailing && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            if (trailing) Text(detail, Modifier.weight(.85f), textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.ChevronRight,
                contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
    if (inlineDetail && detail.isNotBlank()) BoxWithConstraints(Modifier.fillMaxWidth()) {
        row(maxWidth >= (if (desktop) 396.dp else 260.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f))
    } else row(false)
}

private fun settingsBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 1) + " GB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 0) + " KB"
    else -> "$bytes B"
}

private fun settingsLastCheck(time: Long): String = relativeCheckTime(time)
