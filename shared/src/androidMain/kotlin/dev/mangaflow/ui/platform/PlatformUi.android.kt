package dev.mangaflow.ui.platform

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import coil3.BitmapImage
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dev.mangaflow.account.CopyWebsiteLogin
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDocumentPicker(kind: DocumentPickerKind, onResult: (List<String>) -> Unit): DocumentPickerLauncher {
    val latest = rememberUpdatedState(onResult)
    return when (kind) {
        DocumentPickerKind.OPEN -> {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                latest.value(uri?.let { listOf(it.toString()) }.orEmpty())
            }
            remember(launcher) { DocumentPickerLauncher { types, _ -> launcher.launch(types.ifEmpty { arrayOf("*/*") }) } }
        }
        DocumentPickerKind.OPEN_MULTIPLE -> {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                latest.value(uris.map(Uri::toString))
            }
            remember(launcher) { DocumentPickerLauncher { types, _ -> launcher.launch(types.ifEmpty { arrayOf("*/*") }) } }
        }
        DocumentPickerKind.FOLDER -> {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                latest.value(uri?.let { listOf(it.toString()) }.orEmpty())
            }
            remember(launcher) { DocumentPickerLauncher { _, _ -> launcher.launch(null) } }
        }

    }
}

@Composable
actual fun rememberTextDocumentSaver(onResult: (DocumentSaveResult) -> Unit): TextDocumentSaver {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pending = remember { arrayOfNulls<String>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri == null || text == null) latest.value(DocumentSaveResult(false))
        else scope.launch {
            val result = runCatching { writeDocumentText(context, uri.toString(), text) }
            latest.value(if (result.isSuccess) DocumentSaveResult(true) else DocumentSaveResult(false,
                result.exceptionOrNull()?.message ?: "备份导出失败"))
        }
    }
    return remember(launcher) { TextDocumentSaver { name, text ->
        if (pending[0] != null) latest.value(DocumentSaveResult(false, "请先完成当前导出"))
        else { pending[0] = text; launcher.launch(name) }
    } }
}

actual suspend fun readDocumentText(context: PlatformContext, uri: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
    require(maxBytes in 1..64 * 1024 * 1024)
    val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("无法读取所选文件")
    input.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "备份文件过大或格式异常" }
            output.write(buffer, 0, count)
        }
        output.toByteArray().decodeToString()
    }
}
actual suspend fun writeDocumentText(context: PlatformContext, uri: String, text: String): Unit = withContext(Dispatchers.IO) {
    val output = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: error("无法创建备份文件")
    output.bufferedWriter().use { it.write(text) }
}
actual fun documentName(context: PlatformContext, uri: String): String {
    val parsed = Uri.parse(uri)
    return runCatching {
        context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank {
        Uri.decode(parsed.lastPathSegment.orEmpty()).substringAfterLast(':').substringAfterLast('/').ifBlank { "本地漫画" }
    }
}
actual fun releasePickedDocuments(uris: List<String>) = Unit
actual fun copyText(context: PlatformContext, label: String, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text)); true
}.getOrDefault(false)
actual fun showPlatformMessage(context: PlatformContext, text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
actual fun notificationsEnabled(context: PlatformContext): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
actual suspend fun queryNotificationsEnabled(context: PlatformContext): Boolean = notificationsEnabled(context)
actual fun openNotificationSettings(context: PlatformContext): Boolean = runCatching {
    context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)
actual fun openUrl(context: PlatformContext, url: String): Boolean = runCatching {
    val parsed = Uri.parse(url)
    require(parsed.scheme == "https" || parsed.scheme == "http")
    context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)
actual val modernPlatformBlur: Boolean get() = Build.VERSION.SDK_INT >= 31
actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = allowHardware(false)
actual fun imagePixelAt(image: Image, x: Int, y: Int): Int? = runCatching {
    (image as? BitmapImage)?.bitmap?.takeUnless { it.isRecycled }?.getPixel(x, y)
}.getOrNull()

@Composable
actual fun SystemBarAppearance(lightStatus: Boolean, lightNavigation: Boolean, active: Boolean) {
    val activity = LocalContext.current.nativeActivity()
    SideEffect {
        if (active && activity != null) WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightStatus
            isAppearanceLightNavigationBars = lightNavigation
        }
    }
}
private tailrec fun Context.nativeActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.nativeActivity()
    else -> null
}
@Composable
actual fun NativeWebsiteLogin(
    onBegin: () -> Long, onCancel: (Long) -> Unit, onCredential: (Long, String) -> Unit,
    onLoading: (Boolean) -> Unit, onError: (String) -> Unit, modifier: Modifier,
) {
    val session = remember { CopyWebsiteLogin(onBegin, onCancel, onCredential, onLoading, onError) }
    DisposableEffect(session) { onDispose { session.close() } }
    AndroidView(factory = session::create, modifier = modifier, onReset = null, onRelease = { session.close() })
}
private fun textIntent(text: String, tool: CommentTextTool): Intent? = when (tool) {
    CommentTextTool.TRANSLATE -> if (Build.VERSION.SDK_INT >= 29) Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text) else null
    CommentTextTool.PROCESS_TEXT -> Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
}
@Suppress("DEPRECATION")
private fun handler(context: Context, intent: Intent): Boolean = runCatching {
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
        val activity = it.activityInfo
        activity != null && activity.exported && activity.enabled && activity.applicationInfo.enabled &&
            (activity.permission.isNullOrEmpty() || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
    }
}.getOrDefault(false)
actual fun availableCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean {
    val base = textIntent(text, tool) ?: return false
    return handler(context, base) || (tool == CommentTextTool.TRANSLATE && handler(context, Intent(base).setType("text/plain")))
}
actual fun openCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean = runCatching {
    val base = textIntent(text, tool) ?: return false
    val target = if (handler(context, base)) base else Intent(base).setType("text/plain").takeIf { handler(context, it) } ?: return false
    context.startActivity(Intent.createChooser(target, if (tool == CommentTextTool.TRANSLATE) "选择翻译应用" else "选择文本工具")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)
