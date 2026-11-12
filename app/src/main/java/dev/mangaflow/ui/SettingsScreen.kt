package dev.mangaflow.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.BuildConfig
import dev.mangaflow.storage.GlassSkin
import dev.mangaflow.storage.LibraryBackupInfo
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.WebDavBackupConfig
import dev.mangaflow.storage.WebDavBackupStore
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    libraryStore: LibraryStore,
    onRequestNotifications: () -> Unit,
    readBrowseCacheBytes: suspend () -> Long,
    clearBrowseCache: suspend () -> Unit,
    readMetadataCacheBytes: suspend () -> Long,
    clearMetadataCache: suspend () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences by libraryStore.preferences.collectAsStateWithLifecycle()
    val update by updateManager.state.collectAsStateWithLifecycle()
    var automatic by remember(updateManager) { mutableStateOf(updateManager.automatic) }
    var notifications by remember { mutableStateOf(context.notificationsEnabled()) }
    var expandedNotes by remember(update.release?.versionCode) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val cacheScope = rememberCoroutineScope()
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
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
    val toggleSetting: (String) -> Unit = { expandedSetting = if (expandedSetting == it) null else it }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && backupBusy == null) {
            backupBusy = "export"
            backupNotice = null
            cacheScope.launch {
                try {
                    val payload = libraryStore.exportBackup()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("无法创建备份文件")
                        stream.bufferedWriter().use { it.write(payload) }
                    }
                    backupNotice = "阅读备份已导出"
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    backupNotice = failure.message ?: "备份导出失败"
                } finally {
                    backupBusy = null
                }
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && backupBusy == null) {
            backupBusy = "inspect"
            backupNotice = null
            cacheScope.launch {
                try {
                    val payload = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: error("无法读取备份文件")
                        stream.bufferedReader().use { it.readText() }
                    }
                    require(payload.length <= 8 * 1024 * 1024) { "备份文件过大或格式异常" }
                    val info = libraryStore.inspectBackup(payload)
                    pendingRestore = PendingLibraryRestore(payload, info)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    backupNotice = failure.message ?: "备份文件无法读取"
                } finally {
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

    LaunchedEffect(imageLoader) {
        try { readCacheSizes() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { cacheNotice = "暂时无法读取缓存大小" }
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

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = context.notificationsEnabled()
                automatic = updateManager.automatic
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(
            if (LocalWallpaperActive.current) Color.Transparent else colors.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
    ) {
        item {
            // Keep the import controls composed while scrolling; their in-progress copy owns this lifetime.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                SettingsSection("阅读") {
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
                        SettingsToggle("阅读时保持亮屏", preferences.keepScreenOn) {
                            libraryStore.updatePreferences(preferences.copy(keepScreenOn = it))
                        }
                        SettingsToggle("图片之间留白", preferences.pageGap) {
                            libraryStore.updatePreferences(preferences.copy(pageGap = it))
                        }
                    }
                }
                SettingsSection("外观") {
                    SettingsDisclosure("玻璃材质", if (preferences.glassSkin == GlassSkin.FROSTED) "毛玻璃" else "液态玻璃",
                        Icons.Outlined.Layers, expandedSetting == "glass", { toggleSetting("glass") }) {
                        GlassSkinPicker(
                            selected = preferences.glassSkin,
                            onSelected = { skin ->
                                val current = libraryStore.preferences.value
                                if (skin != current.glassSkin) libraryStore.updatePreferences(current.copy(glassSkin = skin))
                            },
                        )
                        if (Build.VERSION.SDK_INT < 33 && preferences.glassSkin == GlassSkin.LIQUID)
                            Text("此设备使用毛玻璃效果", style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    SettingsDisclosure("壁纸", if (wallpaper.mediaPath == null) "默认" else wallpaper.name.ifBlank { "本地壁纸" }, Icons.Outlined.Image,
                        expandedSetting == "wallpaper", { toggleSetting("wallpaper") }) {
                        WallpaperSettings(store = wallpaperStore)
                    }
                }
                SettingsSection("数据") {
                    SettingsDisclosure("备份与同步", if (backupBusy != null) "正在处理…" else "本地文件 · WebDAV",
                        Icons.Outlined.CloudSync, expandedSetting == "backup", { toggleSetting("backup") }) {
                        Column {
                            SettingsLinkRow("导出阅读备份",
                                if (backupBusy == "export") "正在导出…" else "书架、进度、画面收藏与阅读统计") {
                                if (backupBusy == null) exportBackup.launch("MangaFlow-backup.json")
                            }
                            SettingsLinkRow("恢复阅读备份",
                                if (backupBusy == "inspect" || backupBusy == "restore") "正在处理…" else "从备份文件恢复本机阅读数据") {
                                if (backupBusy == null) importBackup.launch(arrayOf("application/json", "text/plain"))
                            }
                            SettingsLinkRow("WebDAV 阅读备份", webDavStatusDetail(webDavConfig, backupBusy)) {
                                if (backupBusy == null) { webDavNotice = null; showWebDav = true }
                            }
                        }
                        Text("备份不包含账号、密码、缓存与漫画文件；恢复前会再次确认。",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    SettingsDisclosure("缓存管理", when {
                        cacheSizes.size == 3 -> settingsBytes(cacheSizes.values.sum())
                        cacheNotice != null -> "暂时无法读取"
                        else -> "正在统计…"
                    },
                        Icons.Outlined.Storage, expandedSetting == "cache", { toggleSetting("cache") }) {
                        Column {
                            listOf("browse" to "推荐与热榜", "metadata" to "目录、评分与评论", "images" to "图片与头像").forEach { (key, label) ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground)
                                        Text(cacheSizes[key]?.let(::settingsBytes) ?: "正在统计…",
                                            style = MaterialTheme.typography.bodySmall, color = secondary)
                                    }
                                    val showBusy = rememberDelayedBusy(clearingCache == key)
                                    SettingsTextAction(enabled = clearingCache == null && cacheSizes[key] != null,
                                        onClick = { clearCache(key, label) }) {
                                        MotionSwap(showBusy, Modifier.size(16.dp)) { busy ->
                                            if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Text("清理", color = secondary)
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
                SettingsSection("应用") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("当前版本", style = MaterialTheme.typography.bodySmall, color = secondary)
                                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium,
                                    color = foreground, fontWeight = FontWeight.Medium)
                            }
                            if (update.phase in setOf(UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.ERROR)) {
                                SettingsTextAction(onClick = { updateManager.check() }) {
                                    Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp), tint = foreground)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (update.phase == UpdatePhase.ERROR) "重试" else "检查更新", color = foreground)
                                }
                            }
                        }
                        update.release?.let { release ->
                            Text("新版本 ${release.versionName}", style = MaterialTheme.typography.titleSmall, color = foreground)
                            if (release.notes.isNotBlank()) {
                                Text(
                                    release.notes,
                                    modifier = Modifier.clickable { expandedNotes = !expandedNotes },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = secondary,
                                    maxLines = if (expandedNotes) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (update.phase in setOf(UpdatePhase.CURRENT, UpdatePhase.ERROR, UpdatePhase.AVAILABLE, UpdatePhase.READY))
                            Text(update.message, style = MaterialTheme.typography.bodySmall,
                                color = if (update.phase == UpdatePhase.ERROR) colors.error else secondary)
                        when (update.phase) {
                            UpdatePhase.DOWNLOADING -> {
                                LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = colors.primary, trackColor = colors.outlineVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${settingsBytes(update.downloaded)} / ${settingsBytes(update.total)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = secondary)
                                    SettingsTextAction(onClick = updateManager::cancelDownload) {
                                        Icon(Icons.Outlined.Pause, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("暂停", color = foreground)
                                    }
                                }
                            }
                            UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = foreground, strokeWidth = 1.8.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (update.phase == UpdatePhase.CHECKING) "正在检查" else "正在核对安装包", style = MaterialTheme.typography.labelLarge, color = foreground)
                                }
                            }
                            UpdatePhase.AVAILABLE -> {
                                SettingsAction(onClick = updateManager::download) {
                                    Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (update.downloaded > 0) "继续下载" else "下载更新 · ${settingsBytes(update.release?.bytes ?: 0)}")
                                }
                            }
                            UpdatePhase.READY -> {
                                val activity = context.settingsActivity()
                                SettingsAction(enabled = activity != null, onClick = { activity?.let(updateManager::install) }) { Text("安装新版本") }
                            }
                            else -> Unit
                        }
                        if (update.lastChecked > 0) {
                            Text(
                                "上次检查 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(update.lastChecked))}",
                                style = MaterialTheme.typography.bodySmall, color = secondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth()) {
                        SettingsToggle("自动检查更新", automatic, leadingInset = 0.dp) {
                            automatic = it
                            updateManager.setAutomatic(it)
                        }
                        SettingsLinkRow(
                            title = "更新提醒",
                            detail = if (notifications) "已开启" else "未开启",
                            inlineDetail = true,
                            onClick = {
                                if (notifications) {
                                    runCatching {
                                        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { Toast.makeText(context, "暂时无法打开通知设置", Toast.LENGTH_SHORT).show() }
                                } else onRequestNotifications()
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingsLinkRow("项目主页", "", external = true) { context.openProjectPage() }
                }
            }
        }
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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("WebDAV 阅读备份", style = MaterialTheme.typography.titleMedium)
                Text(
                    "同步书架、阅读位置、画面收藏、时长和阅读偏好。服务器必须提供可信 HTTPS，密码仅加密保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
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
                    if (config.configured) {
                        TextButton(onClick = onClear, enabled = busy == null) { Text("清除本机配置") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = busy == null) { Text("取消") }
                    TextButton(onClick = { onSave(draft()) }, enabled = enabled) { Text("保存") }
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
                    elevation = null,
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
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                            .format(Date(it))
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
                val date = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                    Locale.getDefault(),
                ).format(Date(timestamp))
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        Surface(Modifier.fillMaxWidth().softSurfaceShadow(shape, colors.surface.luminance() > .5f, strength = .48f),
            shape = shape, color = lerp(colors.background, colors.surface, .85f), tonalElevation = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
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
            .semantics { stateDescription = "$value，${if (expanded) "已展开" else "已收起"}" }
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .motionPress(interaction, pressedScale = .99f)
            .heightIn(min = 52.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface)
            Text(value, Modifier.widthIn(max = 132.dp), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)
                .graphicsLayer { rotationZ = 90f * reveal.value }, tint = colors.onSurfaceVariant.copy(alpha = .65f))
        }
        Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(expanded)
            .layout { measurable, constraints ->
                val body = measurable.measure(constraints.copy(minHeight = 0))
                layout(body.width, (body.height * reveal.value).toInt().coerceAtLeast(0)) {
                    if (reveal.value > 0f) body.placeRelative(0, 0)
                }
            }.graphicsLayer { alpha = reveal.value }
            .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun <T> SettingsChoices(choices: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTabs(
        selected = choices.indexOfFirst { it.first == selected }, labels = choices.map { it.second },
        onSelect = { onSelected(choices[it].first) }, role = Role.RadioButton,
        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface,
        fullWidthIndicator = true, controlHeight = 52.dp, treatment = MotionTabTreatment.Soft,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
    )
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, leadingInset: Dp = 31.dp, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    // Saved 17:03 press-stretch switch: position and width retain the same moving anchor.
    val position = animateFloatAsState(if (checked) 1f else 0f,
        if (motion) spring(dampingRatio = .72f, stiffness = 300f) else snap(), label = "setting-switch-position")
    val thumbWidth = animateFloatAsState(if (pressed) 21f else 18f,
        if (motion) spring(dampingRatio = .72f, stiffness = 300f) else snap(), label = "setting-switch-press")
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .toggleable(checked, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = onCheckedChange).heightIn(min = 54.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).padding(start = leadingInset, end = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(22.dp).drawBehind {
                val p = position.value.coerceIn(0f, 1f)
                drawRoundRect(lerp(colors.surfaceContainerHigh, colors.primary, p),
                    cornerRadius = CornerRadius(size.height / 2f))
                val w = thumbWidth.value.dp.toPx()
                val x = 2.dp.toPx() + (size.width - 4.dp.toPx() - w) *
                    if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) 1f - p else p
                drawRoundRect(lerp(colors.surface, colors.onPrimary, p),
                    topLeft = Offset(x, 2.dp.toPx()), size = Size(w, 18.dp.toPx()),
                    cornerRadius = CornerRadius(9.dp.toPx()))
            })
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, detail: String, external: Boolean = false, inlineDetail: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, pressedScale = .99f).heightIn(min = 56.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (!inlineDetail && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        if (inlineDetail && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        Icon(if (external) Icons.Outlined.OpenInNew else Icons.Outlined.ChevronRight,
            contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = .7f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsAction(enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        interactionSource = interaction,
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface.copy(alpha = .065f), contentColor = colors.onSurface,
            disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant),
        elevation = null,
    ) {
        Row(Modifier.motionPress(interaction, enabled, pressedScale = .985f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    }
}

@Composable
private fun SettingsTextAction(enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(enabled = enabled, onClick = onClick, interactionSource = interaction) {
        Row(Modifier.motionPress(interaction, enabled), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

private fun Context.notificationsEnabled(): Boolean = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

private tailrec fun Context.settingsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> null
}

private fun Context.openProjectPage() {
    val project = Uri.parse("https://github.com/${UpdateManager.REPOSITORY}")
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, project).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, "暂时无法打开项目主页", Toast.LENGTH_SHORT).show() }
}

private fun settingsBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
