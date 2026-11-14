package dev.mangaflow.ui

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
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import dev.mangaflow.ui.motion.MotionButton as Button
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
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
import dev.mangaflow.storage.LibraryBackupInfo
import dev.mangaflow.storage.LibraryStore
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
import coil3.SingletonImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    libraryStore: LibraryStore,
    onRequestNotifications: () -> Unit,
    readBrowseCacheBytes: suspend () -> Long,
    clearBrowseCache: suspend () -> Unit,
    readMetadataCacheBytes: suspend () -> Long,
    clearMetadataCache: suspend () -> Unit,
    contentTopPadding: Dp = 0.dp,
    sourceBackdrop: HazeState? = null,
    sourceRegistry: BodySourceRegistry? = null,
    sourcePlugins: SourcePluginStore? = null,
) {
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    if (!libraryReady) {
        LibraryRestoreStatus(libraryStore)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences by libraryStore.preferences.collectAsStateWithLifecycle()
    val update by updateManager.state.collectAsStateWithLifecycle()
    var automatic by remember(updateManager) { mutableStateOf(updateManager.automatic) }
    var notifications by remember { mutableStateOf(notificationsEnabled(context)) }
    var expandedNotes by remember(update.release?.versionCode) { mutableStateOf(false) }
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
    val imageLoader = remember(context) { SingletonImageLoader.get(imagePlatformContext(context)) }
    var cacheSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var clearingCache by remember { mutableStateOf<String?>(null) }
    var cacheNotice by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf<String?>(null) }
    var backupNotice by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingLibraryRestore?>(null) }
    val webDavStore = remember(context.applicationContext) { WebDavBackupStore(context.applicationContext) }
    val webDavConfig by webDavStore.config.collectAsStateWithLifecycle()
    val wallpaperStore = remember(context.applicationContext) { WallpaperStore.get(context.applicationContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    var showWebDav by remember { mutableStateOf(false) }
    var webDavNotice by remember { mutableStateOf<String?>(null) }
    var expandedSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var showBodySources by remember { mutableStateOf(false) }
    val toggleSetting: (String) -> Unit = { expandedSetting = if (expandedSetting == it) null else it }

    val backupWriter = rememberTextDocumentSaver { result ->
        backupNotice = if (result.saved) "阅读备份已导出" else result.error
        backupBusy = null
    }
    fun exportBackup() {
        if (backupBusy != null) return
        backupBusy = "export"
        backupNotice = null
        cacheScope.launch {
            try {
                val payload = libraryStore.exportBackup()
                backupWriter.launch("MangaFlow-backup.json", payload)
            } catch (cancel: CancellationException) {
                backupBusy = null
                throw cancel
            } catch (failure: Exception) {
                backupNotice = failure.message ?: "备份导出失败"
                backupBusy = null
            }
        }
    }
    val importBackup = rememberOpenDocumentPicker() { uri ->
        if (uri != null && backupBusy == null) {
            backupBusy = "inspect"
            backupNotice = null
            cacheScope.launch {
                try {
                    val payload = withContext(Dispatchers.IO) {
                        readDocumentText(context, uri)
                    }
                    require(payload.length <= 8 * 1024 * 1024) { "备份文件过大或格式异常" }
                    val info = libraryStore.inspectBackup(payload)
                    pendingRestore = PendingLibraryRestore(payload, info)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    backupNotice = failure.message ?: "备份文件无法读取"
                } finally {
                    releasePickedDocuments(listOf(uri))
                    backupBusy = null
                }
            }
        }
    }

    suspend fun readCacheSizes() {
        cacheSizes = mapOf(
            "browse" to readBrowseCacheBytes(),
            "metadata" to readMetadataCacheBytes(),
            "images" to withContext(Dispatchers.IO) { imageLoader.diskCache?.size ?: 0L },
        )
    }

    LaunchedEffect(expandedSetting, imageLoader) {
        if (expandedSetting == "cache") {
            cacheNotice = null
            try { readCacheSizes() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { cacheNotice = "暂时无法读取缓存大小" }
        }
    }

    fun clearCache(key: String, label: String) {
        if (clearingCache != null) return
        clearingCache = key
        cacheNotice = null
        cacheScope.launch {
            try {
                when (key) {
                    "browse" -> clearBrowseCache()
                    "metadata" -> clearMetadataCache()
                    "images" -> withContext(Dispatchers.IO) {
                        imageLoader.memoryCache?.clear()
                        imageLoader.diskCache?.clear()
                    }
                }
                readCacheSizes()
                cacheNotice = "$label 已清理"
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { cacheNotice = "清理未完成，请重试" }
            finally { clearingCache = null }
        }
    }

    fun runWebDav(action: String, draft: WebDavBackupDraft? = null) {
        if (backupBusy != null) return
        backupBusy = "webdav-$action"
        webDavNotice = null
        cacheScope.launch {
            try {
                if (action == "clear") {
                    webDavStore.clearConfiguration()
                    showWebDav = false
                    webDavNotice = "WebDAV 本机配置已清除，远端备份未删除"
                    return@launch
                }
                val values = draft ?: error("缺少 WebDAV 配置")
                webDavStore.saveConfiguration(
                    endpoint = values.endpoint,
                    username = values.username,
                    remotePath = values.remotePath,
                    password = values.password.takeIf { it.isNotEmpty() },
                )
                when (action) {
                    "save" -> {
                        showWebDav = false
                        webDavNotice = "WebDAV 配置已安全保存"
                    }
                    "upload" -> {
                        val payload = libraryStore.exportBackup()
                        webDavStore.upload(payload)
                        showWebDav = false
                        webDavNotice = "阅读备份已上传并覆盖远端文件"
                    }
                    "download" -> {
                        val payload = webDavStore.download()
                        val info = libraryStore.inspectBackup(payload)
                        pendingRestore = PendingLibraryRestore(payload, info)
                        showWebDav = false
                        webDavNotice = "已读取远端备份，确认后才会恢复"
                    }
                    else -> error("未知 WebDAV 操作")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                webDavNotice = failure.message ?: "WebDAV 操作失败，请稍后重试"
            } finally {
                backupBusy = null
            }
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
                    SettingsDisclosure("翻页方式", when (preferences.mode) {
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
                                if (backupBusy == null) { webDavNotice = null; showWebDav = true }
                            }
                        }
                        Text("备份不包含账号、密码、缓存与漫画文件；恢复前会再次确认。",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    SettingsDisclosure("缓存管理", when {
                        cacheSizes.size == 3 -> settingsBytes(cacheSizes.values.sum())
                        cacheNotice != null -> "暂时无法读取"
                        else -> if (expandedSetting == "cache") "正在统计…" else "点按查看用量"
                    },
                        Icons.Outlined.Storage, expandedSetting == "cache", { toggleSetting("cache") }) {
                        Column {
                            listOf("browse" to "推荐与热榜", "metadata" to "目录、评分与评论", "images" to "图片与头像").forEach { (key, label) ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SettingsRowIcon(when (key) {
                                        "browse" -> Icons.Outlined.Explore
                                        "metadata" -> Icons.Outlined.Forum
                                        else -> Icons.Outlined.Image
                                    })
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground)
                                        Text(cacheSizes[key]?.let(::settingsBytes) ?: "正在统计…",
                                            style = MaterialTheme.typography.bodySmall, color = secondary)
                                    }
                                    val showBusy = rememberDelayedBusy(clearingCache == key)
                                    MotionIconButton(enabled = clearingCache == null && cacheSizes[key] != null,
                                        modifier = Modifier.size(44.dp).semantics { contentDescription = "清理$label" },
                                        onClick = { clearCache(key, label) }) {
                                        MotionSwap(showBusy, Modifier.size(20.dp)) { busy ->
                                            if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                                            else Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = secondary)
                                        }
                                    }
                                }
                            }
                        }
                        Text("图片缓存最多 256 MB。书架、阅读记录与下载章节独立保存。",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                        cacheNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary) }
                    }
                    backupNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.padding(vertical = 6.dp)) }
                    webDavNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.padding(vertical = 6.dp)) }
                }
                }
                RetainedSettingsCategory(!desktop || settingsCategory == 3) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSection("应用", sourceBackdrop) {
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
            onDismiss = { showBodySources = false })
    }

    BackupRestoreDialog(
        pending = pendingRestore,
        busy = backupBusy == "restore",
        onDismiss = { if (backupBusy == null) pendingRestore = null },
        onConfirm = {
            val pending = pendingRestore ?: return@BackupRestoreDialog
            if (backupBusy != null) return@BackupRestoreDialog
            backupBusy = "restore"
            backupNotice = null
            cacheScope.launch {
                try {
                    val restored = libraryStore.restoreBackup(pending.payload)
                    backupNotice = "已恢复 ${restored.favorites} 本收藏、${restored.history} 条阅读记录"
                    pendingRestore = null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    backupNotice = failure.message ?: "恢复失败，本机数据未完成替换"
                } finally {
                    backupBusy = null
                }
            }
        },
    )
    WebDavBackupDialog(
        visible = showWebDav,
        config = webDavConfig,
        busy = backupBusy?.takeIf { it.startsWith("webdav-") },
        notice = webDavNotice,
        onDismiss = { if (backupBusy == null) showWebDav = false },
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

@Composable
private fun WebDavBackupDialog(
    visible: Boolean,
    config: WebDavBackupConfig,
    busy: String?,
    notice: String?,
    onDismiss: () -> Unit,
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
    LaunchedEffect(visible) {
        if (visible) { editing = !config.configured; advanced = false }
    }
    val enabled = busy == null && endpoint.isNotBlank() && remotePath.isNotBlank()
    val draft = {
        WebDavBackupDraft(
            endpoint = endpoint,
            username = username,
            password = password,
            remotePath = remotePath,
        )
    }

    MotionDialog(visible = visible, onDismissRequest = onDismiss) {
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier.padding(20.dp).fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
        ) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(tween(if (LocalMotionEnabled.current) MangaMotion.Content else 0,
                        easing = MangaMotion.EnterEasing)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("WebDAV 阅读备份", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (editing) "使用可信 HTTPS 服务器。密码仅加密保存在本机。" else "备份书架、阅读位置、画面收藏与阅读偏好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (!editing) {
                    SettingsLinkRow("连接设置", config.endpoint, Icons.Outlined.CloudSync,
                        inlineDetail = false) { if (busy == null) editing = true }
                    Text(config.remotePath, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busy == null,
                        singleLine = true,
                        label = { Text("WebDAV HTTPS 地址") },
                        placeholder = { Text("https://cloud.example.com/dav/") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(17.dp),
                        colors = settingsInputColors(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busy == null,
                        singleLine = true,
                        label = { Text("用户名") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(17.dp),
                        colors = settingsInputColors(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busy == null,
                        singleLine = true,
                        label = { Text("密码") },
                        placeholder = {
                            Text(if (config.hasPassword) "留空保留已保存密码" else "可留空")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(17.dp),
                        colors = settingsInputColors(),
                    )

                    TextButton(onClick = { advanced = !advanced }, enabled = busy == null,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                        Text(if (advanced) "收起高级设置" else "高级设置", style = MaterialTheme.typography.bodySmall)
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = remotePath,
                            onValueChange = { remotePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = busy == null,
                            singleLine = true,
                            label = { Text("远端备份文件") },
                            placeholder = { Text(WebDavBackupConfig.DEFAULT_REMOTE_PATH) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            shape = RoundedCornerShape(17.dp),
                            colors = settingsInputColors(),
                        )

                    }
                }
                Text(
                    "上传会覆盖这个远端文件；下载只读取并展示恢复确认。暂不后台自动同步，也不会上传漫画图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                busy?.let { operation ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (operation) {
                                "webdav-save" -> "正在安全保存配置…"
                                "webdav-upload" -> "正在上传阅读备份…"
                                "webdav-download" -> "正在读取远端备份…"
                                else -> "正在处理 WebDAV 配置…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (editing && config.configured) {
                        TextButton(onClick = onClear, enabled = busy == null) { Text("清除本机配置") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (editing && config.configured) {
                        TextButton(onClick = {
                            endpoint = config.endpoint; username = config.username; password = ""
                            remotePath = config.remotePath; editing = false; advanced = false
                        }, enabled = busy == null) { Text("取消编辑") }
                    } else TextButton(onClick = onDismiss, enabled = busy == null) { Text(if (editing) "取消" else "关闭") }
                    if (editing) TextButton(onClick = { onSave(draft()) }, enabled = enabled) { Text("保存") }
                }
                FilledTonalButton(
                    onClick = { onDownload(draft()) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text("下载并检查远端备份")
                }
                Button(
                    onClick = { onUpload(draft()) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                    
                ) {
                    Text("上传当前阅读备份")
                }
            }
        }
    }
}

@Composable
private fun BackupRestoreDialog(
    pending: PendingLibraryRestore?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    MotionDialog(visible = pending != null, onDismissRequest = onDismiss) {
        pending?.let { restore ->
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.surface,
            ) {
                Column(Modifier.padding(start = 22.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("恢复阅读备份？", style = MaterialTheme.typography.titleMedium)
                    val date = restore.info.createdAt.takeIf { it > 0 }?.let {
                        formatInstantMillis(it, "yyyy-MM-dd HH:mm")
                    } ?: "未知时间"
                    Text("备份时间 $date", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${restore.info.favorites} 本收藏 · ${restore.info.history} 条阅读记录 · ${restore.info.bookmarks} 项画面收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        "这会覆盖本机书架、阅读位置、画面收藏、时长和阅读偏好。下载文件与登录状态保持不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                        TextButton(onClick = onConfirm, enabled = !busy) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("恢复")
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
        "webdav-download" -> "正在下载…"
        "webdav-clear" -> "正在清除…"
        else -> {
            if (!config.configured) return "未配置"
            val uploadIsLatest = config.lastUploadAt >= config.lastDownloadAt
            val timestamp = if (uploadIsLatest) config.lastUploadAt else config.lastDownloadAt
            if (timestamp <= 0L) "已配置" else {
                val action = if (uploadIsLatest) "上次上传" else "上次下载"
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
private fun SettingsDisclosure(
    title: String,
    value: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "setting-disclosure")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .semantics {
                stateDescription = listOf(value, if (expanded) "已展开" else "已收起")
                    .filter(String::isNotBlank).joinToString("，")
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .motionPress(interaction, pressedScale = .99f)
            .heightIn(min = 48.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRowIcon(icon)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface)
            Text(value, Modifier.weight(.8f, fill = false).widthIn(max = 132.dp), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.width(44.dp).height(32.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)
                    .graphicsLayer { rotationZ = 90f * reveal.value }, tint = colors.onSurfaceVariant.copy(alpha = .65f))
            }
        }
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
            .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}


@Composable
private fun <T> SettingsChoices(choices: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
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
private fun SettingsToggle(title: String, checked: Boolean, icon: ImageVector, onCheckedChange: (Boolean) -> Unit) {
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
