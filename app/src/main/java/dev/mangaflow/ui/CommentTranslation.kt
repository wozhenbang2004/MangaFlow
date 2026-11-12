package dev.mangaflow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.ui.motion.MotionDialog

/** An affordance heuristic only; no language model, source request or background translation. */
internal fun hasForeignCommentText(text: String): Boolean {
    var han = 0
    var foreign = 0
    text.forEach { character ->
        if (!character.isLetter()) return@forEach
        when (Character.UnicodeScript.of(character.code)) {
            Character.UnicodeScript.HAN -> han++
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> return true
            else -> foreign++
        }
    }
    return foreign >= 3 && foreign.toFloat() / (han + foreign).coerceAtLeast(1) >= .35f
}

/** Uses the phone's native text tools only after a tap; their result never replaces the original. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentTranslationAction(text: String, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fallbackVisible by remember(text) { mutableStateOf(false) }
    var message by remember(text) { mutableStateOf("") }
    var textTool by remember(text) { mutableStateOf<Intent?>(null) }

    fun showFallback(reason: String, allowTools: Boolean) {
        textTool = if (allowTools) processTextIntent(text).takeIf { hasHandler(context, it) } else null
        message = reason + if (textTool != null) "可选择手机上的文字工具，或复制原文后翻译。"
            else "可复制原文，粘贴到你常用的翻译工具。"
        fallbackVisible = true
    }

    val interaction = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = 20.dp)
        .semantics { contentDescription = "用手机上的翻译应用翻译评论" }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
            // Stay well below Binder's transaction limit; never silently translate a truncated review.
            if (text.length > 32_000) {
                showFallback("这条评论较长。", allowTools = false)
            } else {
                val target = translationIntent(context, text)
                when {
                    target == null -> showFallback("手机未提供直接翻译。", allowTools = true)
                    !openTextTool(context, target, "选择翻译应用") ->
                        showFallback("暂时无法打开翻译应用。", allowTools = true)
                }
            }
        }).padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text("翻译", color = color, fontSize = 11.sp, lineHeight = 16.sp)
    }

    MotionDialog(visible = fallbackVisible, onDismissRequest = { fallbackVisible = false }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("翻译评论", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(message, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { fallbackVisible = false }) { Text("取消") }
                    TextButton(onClick = {
                        val copied = runCatching {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                ?: error("Clipboard unavailable")
                            clipboard.setPrimaryClip(ClipData.newPlainText("评论原文", text))
                        }.isSuccess
                        Toast.makeText(context, if (copied) "已复制评论原文" else "暂时无法复制评论",
                            Toast.LENGTH_SHORT).show()
                        if (copied) fallbackVisible = false
                    }) { Text("复制原文") }
                    textTool?.let { target ->
                        TextButton(onClick = {
                            if (openTextTool(context, target, "选择文本工具")) fallbackVisible = false
                            else {
                                textTool = null
                                message = "暂时无法打开文字工具，可复制原文后翻译。"
                            }
                        }) { Text("文字工具") }
                    }
                }
            }
        }
    }
}

private fun translationIntent(context: Context, text: String): Intent? {
    if (Build.VERSION.SDK_INT < 29) return null
    val base = Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text)
    // The platform contract does not require a MIME type; some native apps require text/plain.
    return listOf(base, Intent(base).setType("text/plain")).firstOrNull { hasHandler(context, it) }
}

private fun processTextIntent(text: String): Intent = Intent(Intent.ACTION_PROCESS_TEXT)
    .setType("text/plain")
    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

@Suppress("DEPRECATION")
private fun hasHandler(context: Context, intent: Intent): Boolean = runCatching {
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any { resolve ->
        val activity = resolve.activityInfo
        activity != null && activity.exported && activity.enabled && activity.applicationInfo.enabled &&
            (activity.permission.isNullOrEmpty() ||
                context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
    }
}.getOrDefault(false)

private fun openTextTool(context: Context, intent: Intent, title: String): Boolean = runCatching {
    val chooser = Intent.createChooser(intent, title)
    if (context.activityOrNull() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}.isSuccess
