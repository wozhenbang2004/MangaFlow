package dev.mangaflow.ui

import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.ui.AnimeSourceManager
import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperation
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import dev.mangaflow.enhancement.ui.EnhancementCenterPanel
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
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.rememberDelayedBusy
import dev.mangaflow.update.UpdateManager
import dev.mangaflow.update.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val experienceBudget by experienceStore.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val desktop = platformKind == PlatformKind.DESKTOP
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
    var backupNotice by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingLibraryRestore?>(null) }
    var pendingWebDavRestore by remember { mutableStateOf<PendingLibraryRestore?>(null) }
    val webDavStore = remember(context.applicationContext) { WebDavBackupStore.get(context.applicationContext) }
    val webDavConfig by webDavStore.config.collectAsStateWithLifecycle()
    val wallpaperStore = remember(context.applicationContext) { WallpaperStore.get(context.applicationContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    var showWebDav by remember { mutableStateOf(false) }
    var webDavPresent by remember { mutableStateOf(false) }
    var webDavNotice by remember { mutableStateOf<WebDavFeedback?>(null) }
    var expandedSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var showBodySources by remember { mutableStateOf(false) }
    var showAnimeSources by remember { mutableStateOf(false) }
    LaunchedEffect(screenActive) {
        if (!screenActive) showAnimeSources = false
    }
    var showNetworkDownloads by remember { mutableStateOf(false) }
    var showExperience by remember { mutableStateOf(false) }
    var showEnhancement by remember { mutableStateOf(false) }
    val toggleSetting: (String) -> Unit = { expandedSetting = if (expandedSetting == it) null else it }
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
        backupNotice = if (result.saved) "阅读备份已导出" else result.error
        exportSaving = false
    }
    fun exportBackup() {
        if (backupBusy != null) return
        backupNotice = null
        operations.exportReadingBackup(libraryStore)
    }
    val importBackup = rememberOpenDocumentPicker() { uri ->
        if (uri != null) {
            if (backupBusy == null) {
                backupNotice = null
                val handle = operations.inspectReadingBackup(context, libraryStore, uri)
                if (!handle.accepted) releasePickedDocuments(listOf(uri))
            } else releasePickedDocuments(listOf(uri))
        }
    }

    LaunchedEffect(backupOperation?.id, backupOperation?.phase, screenActive) {
        val operation = backupOperation ?: return@LaunchedEffect
        if (!screenActive || operation.active) return@LaunchedEffect
        if (operation.phase != ApplicationOperationPhase.SUCCEEDED) {
            if (operation.kind in setOf(ApplicationOperationKind.WEBDAV_SAVE,
                ApplicationOperationKind.WEBDAV_UPLOAD, ApplicationOperationKind.WEBDAV_READ)) {
                webDavNotice = operation.message?.let {
                    WebDavFeedback(it, failed = operation.phase != ApplicationOperationPhase.CANCELLED)
                }
            } else backupNotice = operation.message
            operations.consumeResult(operation.id)
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
                backupNotice = "已恢复 ${restored.favorites} 本收藏、${restored.history} 条阅读记录"
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_READ -> operations.result<InspectedReadingBackup?>(operation.id)?.let { result ->
                result.await()?.let { inspected ->
                    val pending = PendingLibraryRestore(inspected.payload, inspected.info)
                    if (showWebDav || webDavPresent) { pendingWebDavRestore = pending; showWebDav = false }
                    else { backupNotice = null; pendingRestore = pending }
                }
                webDavNotice = WebDavFeedback("已读取远端备份，可查看恢复预览")
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_UPLOAD -> {
                webDavNotice = WebDavFeedback("阅读备份已上传")
                operations.consumeResult(operation.id)
            }
            ApplicationOperationKind.WEBDAV_SAVE -> {
                webDavNotice = if (webDavStore.config.value.configured)
                    WebDavFeedback("连接设置已保存 · 尚未验证", savedConfiguration = true)
                    else WebDavFeedback("本机连接设置已清除")
                operations.consumeResult(operation.id)
            }
            else -> Unit
        }
    }

    fun runWebDav(action: String, draft: WebDavBackupDraft? = null) {
        if (backupBusy != null) return
        webDavNotice = null
        try {
            val route = if (action == "upload" || action == "download") routeStore.snapshot(DownloadCategory.WEBDAV)
                else DownloadRouteSnapshot(DownloadRouteMode.SYSTEM)
            val accepted = draft?.let { WebDavOperationDraft(it.endpoint, it.username, it.password, it.remotePath) }
            val operation = operations.webDav(libraryStore, webDavStore, action, accepted, route)
            if (!operation.accepted) webDavNotice = WebDavFeedback("已有备份操作，请稍后重试", failed = true)
        } catch (_: Exception) {
            webDavNotice = WebDavFeedback("备份线路不可用，请检查连接设置", failed = true)
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

    val settingsContent: @Composable (Dp) -> Unit = { paneTopPadding ->
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = settingsScroll,
        modifier = Modifier.fillMaxSize().imePadding().background(
            if (LocalWallpaperActive.current) Color.Transparent else colors.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = paneTopPadding, bottom = 32.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
    ) {
        item {
            // Keep the import controls composed while scrolling; their in-progress copy owns this lifetime.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (desktop) 0.dp else 16.dp)) {
                RetainedSettingsCategory(!desktop || settingsCategory == 0) {
                SettingsSection("阅读", sourceBackdrop) {
                    SettingsDisclosure("阅读顺序", when (preferences.mode) {
                        ReadingMode.VERTICAL -> "纵向滚动"
                        ReadingMode.LEFT_TO_RIGHT -> "从左至右"
                        ReadingMode.RIGHT_TO_LEFT -> "从右至左"
                    }, Icons.Outlined.SwapVert, expandedSetting == "mode", { toggleSetting("mode") }) {
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
                    }, Icons.Outlined.SwapHoriz, expandedSetting == "page-turn", { toggleSetting("page-turn") }) {
                        SettingsChoices(choices = listOf(
                            PageTurnGesture.FOLLOW_READING_ORDER to "跟随顺序",
                            PageTurnGesture.SWIPE_LEFT_NEXT to "左滑下一页",
                            PageTurnGesture.SWIPE_RIGHT_NEXT to "右滑下一页"),
                            selected = preferences.pageTurnGesture,
                            onSelected = { libraryStore.updatePageTurnGesture(it) })
                    }
                    SettingsDisclosure("阅读底色", when (preferences.theme) {
                        ReaderTheme.PAPER -> "纸白"
                        ReaderTheme.SEPIA -> "暖纸"
                        ReaderTheme.INK -> "墨黑"
                    }, Icons.Outlined.Palette, expandedSetting == "paper", { toggleSetting("paper") }) {
                        PaperThemePicker(preferences.theme) { theme ->
                            libraryStore.updatePreferences(preferences.copy(theme = theme))
                        }
                    }
                    Column {
                        SettingsToggle("阅读时保持亮屏", preferences.keepScreenOn, Icons.Outlined.LightMode) {
                            libraryStore.updatePreferences(preferences.copy(keepScreenOn = it))
                        }
                        SettingsToggle("图片之间留白", preferences.pageGap, Icons.Outlined.ViewDay) {
                            libraryStore.updatePreferences(preferences.copy(pageGap = it))
                        }
                    }
                }
                }
                RetainedSettingsCategory(!desktop || settingsCategory == 1) {
                SettingsSection("外观", sourceBackdrop) {
                    DetailAppearanceSettings()
                    SettingsDisclosure("字体", "", Icons.Outlined.TextFields,
                        expandedSetting == "fonts", { toggleSetting("fonts") }) {
                        FontSettings()
                    }
                    SettingsDisclosure("壁纸", when {
                        wallpaper.mediaPath == null -> "默认"
                        wallpaper.rotationEnabled -> "${wallpaper.items.size} 张 · 每 ${wallpaper.rotationMinutes} 分钟"
                        wallpaper.items.size > 1 -> "${wallpaper.items.size} 张 · 手动切换"
                        else -> wallpaper.name.ifBlank { "本地壁纸" }
                    }, Icons.Outlined.Image,
                        expandedSetting == "wallpaper", { toggleSetting("wallpaper") }) {
                        WallpaperSettings(store = wallpaperStore, backdrop = sourceBackdrop)
                    }
                }
                }
                RetainedSettingsCategory(!desktop || settingsCategory == 2) {
                SettingsSection("数据", sourceBackdrop) {
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
                    SettingsDisclosure("备份与同步", if (backupBusy != null) "正在处理…" else "本地文件 · WebDAV",
                        Icons.Outlined.CloudSync, expandedSetting == "backup", { toggleSetting("backup") }) {
                        Column {
                            SettingsLinkRow("导出阅读备份",
                                if (backupBusy == "export") "正在导出…" else "书架、进度、画面收藏与阅读统计",
                                Icons.Outlined.UploadFile) {
                                if (backupBusy == null) exportBackup()
                            }
                            SettingsLinkRow("恢复阅读备份",
                                if (backupBusy == "inspect" || backupBusy == "restore") "正在处理…" else "从备份文件恢复本机阅读数据",
                                Icons.Outlined.Restore) {
                                if (backupBusy == null) importBackup.launch(arrayOf("application/json", "text/plain"))
                            }
                            SettingsLinkRow("WebDAV 阅读备份", webDavStatusDetail(webDavConfig, backupBusy), Icons.Outlined.CloudSync) {
                                if (backupBusy == null || backupBusy.startsWith("webdav-")) { webDavNotice = null; showWebDav = true }
                            }
                        }
                        SettingsOperationStatus(backupOperation) { operations.cancel(it) }
                        Text("备份不包含账号、密码、缓存与漫画文件；恢复前会再次确认。",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    SettingsLinkRow("存储管理", "阅读缓存、离线下载与模型", Icons.Outlined.Storage,
                        inlineDetail = true) { showStorage = true }
                    backupNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.padding(vertical = 6.dp)) }
                    webDavNotice?.let { Text(it.message, style = MaterialTheme.typography.bodySmall,
                        color = if (it.failed) colors.error else secondary,
                        modifier = Modifier.padding(vertical = 6.dp)) }
                }
                }
                RetainedSettingsCategory(!desktop || settingsCategory == 3) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSection("应用", sourceBackdrop) {
                        SettingsLinkRow("画质与性能", "增强、补帧与提前处理", Icons.Outlined.Image,
                            inlineDetail = true) { showEnhancement = true }
                        SettingsLinkRow("体验策略", experienceModeLabel(experienceBudget.mode), Icons.Outlined.Tune,
                            inlineDetail = true) { showExperience = true }
                        SettingsLinkRow("网络与下载", downloadRouteLabel(routes.defaultMode), Icons.Outlined.SwapVert,
                            inlineDetail = true) { showNetworkDownloads = true }
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
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Outlined.SystemUpdate)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("软件更新", style = MaterialTheme.typography.bodyMedium, color = foreground)
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
                                MotionIconButton(
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = updateAction },
                                    enabled = update.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING),
                                    onClick = {
                                        when (update.phase) {
                                            UpdatePhase.AVAILABLE -> updateManager.download()
                                            UpdatePhase.DOWNLOADING -> updateManager.cancelDownload()
                                            UpdatePhase.READY -> updateManager.install(context)
                                            UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.ERROR -> updateManager.check()
                                            else -> Unit
                                        }
                                    },
                                ) {
                                    MotionSwap(update.phase, Modifier.size(20.dp)) { phase ->
                                        when (phase) {
                                            UpdatePhase.CHECKING, UpdatePhase.VERIFYING ->
                                                CircularProgressIndicator(Modifier.size(15.dp), color = secondary, strokeWidth = 1.5.dp)
                                            UpdatePhase.AVAILABLE -> Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(20.dp), tint = colors.primary)
                                            UpdatePhase.DOWNLOADING -> Icon(Icons.Outlined.Pause, null, Modifier.size(20.dp), tint = foreground)
                                            UpdatePhase.READY -> Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(20.dp), tint = colors.primary)
                                            else -> Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp), tint = foreground)
                                        }
                                    }
                                }
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
                                    modifier = Modifier.padding(start = 32.dp, bottom = 2.dp))
                            }
                            update.release?.let { release ->
                                if (release.notes.isNotBlank()) {
                                    Text(release.notes,
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
                                    LinearProgressIndicator(progress = { update.progress },
                                        modifier = Modifier.weight(1f).height(2.dp).clip(CircleShape),
                                        color = colors.primary.copy(alpha = .8f), trackColor = colors.primary.copy(alpha = .10f))
                                    Text("${settingsBytes(update.downloaded)} / ${settingsBytes(update.total)}",
                                        style = MaterialTheme.typography.bodySmall, color = secondary)
                                }
                            }
                        }
                        DownloadRouteControl(DownloadCategory.UPDATES,
                            modifier = Modifier.padding(start = 24.dp),
                            enabled = update.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING), backdrop = sourceBackdrop)
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
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
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SettingsLinkRow("项目主页", "", Icons.Outlined.Code, external = true, compact = true) { if (!openUrl(context, "https://github.com/${UpdateManager.REPOSITORY}")) showPlatformMessage(context, "暂时无法打开项目主页") }
                    }
                }
                }
            }
        }
    }
    if (desktop) DesktopVerticalScrollbar(settingsScroll,
        Modifier.align(Alignment.CenterEnd).padding(top = paneTopPadding))
    }
    }
    if (desktop) {
        DesktopSettingsLayout(settingsCategory, { settingsCategory = it }, contentTopPadding, settingsContent)
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
    EnhancementCenterPanel(showEnhancement, sourceBackdrop, onDismiss = { showEnhancement = false })

    StorageManagementDialog(showStorage, storageBackdrop, libraryStore, repository = sourceRepository, active = screenActive,
        onDismiss = { showStorage = false },
        onDownloads = { storageDestination = "downloads"; showStorage = false },
        onModels = { storageDestination = "models"; showStorage = false },
        onClosed = {
            val target = storageDestination
            storageDestination = null
            if (target == "downloads") onOpenDownloads()
            if (target == "models") showStorageModels = true
        })
    dev.mangaflow.reader.panels.PanelModelsDialog(showStorageModels, storageBackdrop) { showStorageModels = false }

    BackupRestoreDialog(
        pending = pendingRestore.takeIf { screenActive },
        backdrop = sourceBackdrop,
        busy = backupBusy == "restore",
        error = backupNotice?.takeIf { pendingRestore != null },
        onDismiss = { pendingRestore = null },
        onConfirm = {
            val pending = pendingRestore ?: return@BackupRestoreDialog
            if (backupBusy != null) return@BackupRestoreDialog
            val handle = operations.restoreReadingBackup(libraryStore, pending.payload)
            if (handle.accepted) {
                backupNotice = null
                pendingRestore = null
            }
        },
    )
    WebDavBackupDialog(
        visible = showWebDav && screenActive,
        backdrop = sourceBackdrop,
        config = webDavConfig,
        busy = backupBusy?.takeIf { it.startsWith("webdav-") },
        cancellable = backupOperation?.cancellable == true,
        notice = webDavNotice,
        onDismiss = { showWebDav = false },
        onCancel = { backupOperation?.takeIf { it.cancellable }?.let { operations.cancel(it.id) } },
        onClosed = {
            // Present the inspected restore only after the connection workspace has left.
            if (!showWebDav || !screenActive) {
                webDavPresent = false
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

private data class WebDavBackupDraft(
    val endpoint: String,
    val username: String,
    val password: String,
    val remotePath: String,
)

private data class WebDavFeedback(
    val message: String,
    val failed: Boolean = false,
    val savedConfiguration: Boolean = false,
)

@Composable
private fun SettingsOperationStatus(operation: ApplicationOperation?, onCancel: (String) -> Unit) {
    if (operation == null || !operation.active && operation.statusDurable &&
        operation.phase != ApplicationOperationPhase.FAILED && operation.phase != ApplicationOperationPhase.INTERRUPTED) return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (operation.active) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
        else Icon(Icons.Outlined.Info, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        Text(if (operation.active) operation.message ?: "后台处理中 · 可离开此页" else operation.message.orEmpty(),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (operation.cancellable) TextButton(onClick = { onCancel(operation.id) }) { Text("取消") }
    }
}

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
    notice: WebDavFeedback?,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onClosed: () -> Unit,
    onSave: (WebDavBackupDraft) -> Unit,
    onUpload: (WebDavBackupDraft) -> Unit,
    onDownload: (WebDavBackupDraft) -> Unit,
    onClear: () -> Unit,
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
                        Text(if (pendingUpload == null) "WebDAV 备份" else "确认上传备份",
                            Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        CopyShelfReveal(!editing && config.configured) {
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
                        CopyShelfValue(notice) { feedback ->
                            Text(feedback.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (feedback.failed) colors.error else colors.onSurfaceVariant)
                        }
                        CopyShelfValue(busy) { operation ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Text(when (operation) {
                                    "webdav-save" -> "正在保存连接"
                                    "webdav-upload" -> "正在上传备份"
                                    "webdav-download" -> "正在读取备份"
                                    else -> "正在处理配置"
                                }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Text("书架、阅读位置与偏好；不含账号、缓存和漫画文件。手动备份，不自动双向同步。",
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
    var retained by remember { mutableStateOf(pending) }
    androidx.compose.runtime.SideEffect { if (pending != null) retained = pending }
    MotionDialog(visible = pending != null, onDismissRequest = onDismiss,
        onClosed = { if (pending == null) retained = null }, maximumWidth = 520.dp) {
        (pending ?: retained)?.let { restore ->
            val colors = MaterialTheme.colorScheme
            ReadingBackupPanel(backdrop, Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column(Modifier.heightIn(max = LocalMangaWindow.current.height * .84f)
                    .padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("恢复阅读备份", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val date = restore.info.createdAt.takeIf { it > 0 }?.let {
                            formatInstantMillis(it, "yyyy-MM-dd HH:mm")
                        } ?: "未知时间"
                        Text(date, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text(
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
private fun settingsInputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
)

@Composable
private fun SettingsSection(title: String, backdrop: HazeState?, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        val surfaceModifier = Modifier.fillMaxWidth()
            .softSurfaceShadow(shape, colors.surface.luminance() > .5f, strength = .48f)
        if (backdrop != null) {
            // The root source ancestor excludes itself; PANEL samples the lower wallpaper source.
            LiquidGlassSurface(state = backdrop, modifier = surfaceModifier, cornerRadius = 20.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { body() }
        } else {
            Surface(surfaceModifier, shape = shape, color = lerp(colors.background, colors.surface, .85f),
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "setting-disclosure")
    Column(Modifier.fillMaxWidth()) {
        val header: @Composable (Boolean) -> Unit = { stacked ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .semantics(mergeDescendants = compact) {
                    stateDescription = listOf(if (compact) "" else value,
                        if (modified) "已自定义" else "", if (expanded) "已展开" else "已收起")
                        .filter(String::isNotBlank).joinToString("，")
                }
                .clickable(interactionSource = interaction, indication = null,
                    role = if (compact) Role.Button else null, onClick = onToggle)
                .motionPress(interaction, pressedScale = .99f)
                .heightIn(min = if (compact) 44.dp else 48.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
                SettingsRowIcon(icon)
                if (stacked) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface, maxLines = if (compact) 1 else Int.MAX_VALUE,
                        overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip)
                    Text(value, Modifier.weight(.8f, fill = false).widthIn(max = 132.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (compact && modified) Box(Modifier.size(4.dp).clip(CircleShape)
                    .background(colors.onSurfaceVariant.copy(alpha = .65f)))
                Box(Modifier.width(if (compact) 16.dp else 44.dp).height(32.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)
                        .graphicsLayer { rotationZ = 90f * reveal.value }, tint = colors.onSurfaceVariant.copy(alpha = .65f))
                }
            }
        }
        if (compact) BoxWithConstraints(Modifier.fillMaxWidth()) {
            header(maxWidth < 300.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f))
        } else header(false)
        Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(expanded)
            .layout { measurable, constraints ->
                // Keep the composed child alive for imports, but do not measure it when closed.
                if (reveal.value <= 0f) {
                    layout(constraints.minWidth.coerceAtLeast(0), 0) {}
                } else {
                    val body = measurable.measure(constraints.copy(minHeight = 0))
                    layout(body.width, (body.height * reveal.value).toInt().coerceAtLeast(0)) {
                        body.placeRelative(0, 0)
                    }
                }
            }.graphicsLayer { alpha = reveal.value }
            .padding(top = 4.dp, bottom = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp), content = content)
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun SettingsToggle(title: String, checked: Boolean, icon: ImageVector, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .toggleable(checked, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = onCheckedChange).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsRowIcon(icon)
        Text(title, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            MotionSwitchThumb(checked, interaction)
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, detail: String, icon: ImageVector, external: Boolean = false, inlineDetail: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, pressedScale = .99f).heightIn(min = if (compact) 48.dp else 56.dp)
            .padding(vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsRowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (!inlineDetail && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        if (inlineDetail && detail.isNotBlank()) Text(detail,
            modifier = Modifier.weight(.8f, fill = false).widthIn(max = 160.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(44.dp).height(32.dp), contentAlignment = Alignment.Center) {
            Icon(if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.ChevronRight,
                contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = .7f), modifier = Modifier.size(18.dp))
        }
    }
}

private fun settingsBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 1) + " GB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 0) + " KB"
    else -> "$bytes B"
}

private fun settingsLastCheck(time: Long): String = relativeCheckTime(time)
