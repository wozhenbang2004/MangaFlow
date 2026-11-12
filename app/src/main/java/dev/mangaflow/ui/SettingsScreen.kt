package dev.mangaflow.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.BuildConfig
import dev.mangaflow.data.CopyMangaConfig
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.ReadingMode
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineLarge, color = foreground, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("找到舒服的阅读方式", style = MaterialTheme.typography.bodyMedium, color = secondary)
            Spacer(Modifier.height(32.dp))
            SettingsSectionLabel("阅读")
            Spacer(Modifier.height(18.dp))
            Text("翻页方式", style = MaterialTheme.typography.bodyLarge, color = foreground)
            Spacer(Modifier.height(12.dp))
            SettingsChoices(
                choices = listOf(ReadingMode.VERTICAL to "纵向滚动", ReadingMode.LEFT_TO_RIGHT to "从左至右", ReadingMode.RIGHT_TO_LEFT to "从右至左"),
                selected = preferences.mode,
                onSelected = { libraryStore.updatePreferences(preferences.copy(mode = it)) },
            )
            Spacer(Modifier.height(24.dp))
            Text("阅读底色", style = MaterialTheme.typography.bodyLarge, color = foreground)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(ReaderTheme.INK, "墨黑", Color(0xFF171719)),
                    Triple(ReaderTheme.PAPER, "纸白", Color(0xFFFAFAF8)),
                    Triple(ReaderTheme.SEPIA, "暖纸", Color(0xFFEADFC8)),
                ).forEach { (theme, label, color) ->
                    val selected = theme == preferences.theme
                    Row(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
                            .selectable(selected, role = Role.RadioButton) { libraryStore.updatePreferences(preferences.copy(theme = theme)) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(color).border(0.5.dp, colors.outline, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = if (theme == ReaderTheme.INK) Color.White else Color(0xFF242426), modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = foreground)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsToggle("阅读时保持亮屏", preferences.keepScreenOn) { libraryStore.updatePreferences(preferences.copy(keepScreenOn = it)) }
            SettingsToggle("图片之间留白", preferences.pageGap) { libraryStore.updatePreferences(preferences.copy(pageGap = it)) }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(28.dp))
            SettingsSectionLabel("缓存")
            Spacer(Modifier.height(8.dp))
            listOf("browse" to "推荐与热榜", "metadata" to "评分与评论", "images" to "图片与头像").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground)
                        Text(cacheSizes[key]?.let(::settingsBytes) ?: "正在统计…",
                            style = MaterialTheme.typography.bodySmall, color = secondary,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    TextButton(enabled = clearingCache == null && cacheSizes[key] != null,
                        onClick = { clearCache(key, label) }) {
                        if (clearingCache == key) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                        else Text("清理", color = secondary)
                    }
                }
            }
            Text("图片最多保留 256 MB；书架、阅读记录和已下载章节独立保存。",
                style = MaterialTheme.typography.bodySmall, color = secondary, modifier = Modifier.padding(top = 8.dp))
            cacheNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary,
                modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(26.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(28.dp))
            SettingsSectionLabel("应用更新")
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.primary), contentAlignment = Alignment.Center) {
                    Text("流", style = MaterialTheme.typography.titleLarge, color = colors.onPrimary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("漫流", style = MaterialTheme.typography.titleMedium, color = foreground)
                    Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.labelMedium, color = secondary)
                }
                TextButton(onClick = { context.openSettingsLink("https://github.com/${UpdateManager.REPOSITORY}/releases") }) {
                    Text("版本记录", color = secondary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = secondary, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            update.release?.let { release ->
                Text("新版本 ${release.versionName}", style = MaterialTheme.typography.titleSmall, color = foreground)
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        release.notes,
                        modifier = Modifier.clickable { expandedNotes = !expandedNotes },
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        maxLines = if (expandedNotes) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(update.message, style = MaterialTheme.typography.bodyMedium, color = if (update.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else secondary)
            Spacer(Modifier.height(14.dp))
            when (update.phase) {
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = colors.primary, trackColor = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${settingsBytes(update.downloaded)} / ${settingsBytes(update.total)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = secondary)
                        TextButton(onClick = updateManager::cancelDownload) {
                            Icon(Icons.Outlined.Pause, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("暂停", color = foreground)
                        }
                    }
                }
                UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
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
                else -> SettingsAction(onClick = { updateManager.check() }) { Text("检查更新") }
            }
            if (update.lastChecked > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "上次检查 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(update.lastChecked))}",
                    style = MaterialTheme.typography.labelSmall, color = secondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsToggle("自动检查更新", automatic) {
                automatic = it
                updateManager.setAutomatic(it)
            }
            SettingsLinkRow(
                title = "更新提醒",
                detail = if (notifications) "已开启" else "未开启",
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
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(28.dp))
            SettingsSectionLabel("关于")
            Spacer(Modifier.height(12.dp))
            SettingsLinkRow("拷贝漫画", "原站", external = true) { context.openSettingsLink(CopyMangaConfig().websiteOrigin) }
            SettingsLinkRow("MangaFlow", "项目主页", external = true) { context.openSettingsLink("https://github.com/${UpdateManager.REPOSITORY}") }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
}

@Composable
private fun <T> SettingsChoices(choices: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(colors.surfaceContainerHigh).padding(4.dp).selectableGroup()) {
        choices.forEach { (value, label) ->
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (selected == value) colors.surface else Color.Transparent)
                    .selectable(selected == value, role = Role.RadioButton) { onSelected(value) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected == value) colors.onSurface else colors.onSurfaceVariant, fontWeight = if (selected == value) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariant, uncheckedTrackColor = colors.surfaceContainerHigh, uncheckedBorderColor = colors.outlineVariant,
            ),
        )
    }
}

@Composable
private fun SettingsLinkRow(title: String, detail: String, external: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Icon(if (external) Icons.Outlined.OpenInNew else Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SettingsAction(enabled: Boolean = true, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
            disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant),
        elevation = null, content = content,
    )
}

private fun Context.notificationsEnabled(): Boolean = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

private tailrec fun Context.settingsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> null
}

private fun Context.openSettingsLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, "暂时无法打开链接", Toast.LENGTH_SHORT).show() }
}

private fun settingsBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
