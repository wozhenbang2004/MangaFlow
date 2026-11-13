package dev.mangaflow.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Reuse the app's plain error state without attaching the failed scene or its materials. */
@Composable
internal fun LaunchFailureMessage(report: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyMessage("应用上次意外退出", "可复制错误详情，也可以重试打开。",
                action = if (copied) "已复制" else "复制错误详情") {
                copied = runCatching {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                        ?: return@runCatching false
                    clipboard.setPrimaryClip(ClipData.newPlainText("MangaFlow 错误详情", report))
                    true
                }.getOrDefault(false)
            }
            TextButton(onClick = onRetry) { Text("重试打开") }
        }
    }
}
