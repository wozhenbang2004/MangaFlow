package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

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
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.ui.motion.MotionDialog

private val commentNonLanguageTokens = Regex("""https?://\S+|(?<!\S)@[\p{L}\p{N}_.-]+""", RegexOption.IGNORE_CASE)

/** An affordance heuristic only; no language model, source request or background translation. */
internal fun hasForeignCommentText(text: String): Boolean {
    var han = 0
    var foreign = 0
    // Explicit mentions and URLs alone are not text that benefits from translation.
    text.replace(commentNonLanguageTokens, "").forEach { character ->
        if (!character.isLetter()) return@forEach
        when (character.code) {
            in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF -> han++
            in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xAC00..0xD7AF,
            in 0x1100..0x11FF, in 0x3130..0x318F -> return true
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
    var textTool by remember(text) { mutableStateOf<CommentTextTool?>(null) }

    fun showFallback(reason: String, allowTools: Boolean) {
        textTool = if (allowTools) CommentTextTool.PROCESS_TEXT.takeIf { availableCommentTextTool(context, text, it) } else null
        message = reason + if (textTool != null) "可选择设备上的文字工具，或复制原文后翻译。"
            else "可复制原文，粘贴到你常用的翻译工具。"
        fallbackVisible = true
    }

    val interaction = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = 28.dp)
        .semantics { contentDescription = "用设备上的翻译应用翻译评论" }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
            // Stay well below Binder's transaction limit; never silently translate a truncated review.
            if (text.length > 32_000) {
                showFallback("这条评论较长。", allowTools = false)
            } else {
                val target = CommentTextTool.TRANSLATE.takeIf { availableCommentTextTool(context, text, it) }
                when {
                    target == null -> showFallback("此设备未提供直接翻译。", allowTools = true)
                    !openCommentTextTool(context, text, target) ->
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
                        val copied = copyText(context, "评论原文", text)
                        showPlatformMessage(context, if (copied) "已复制评论原文" else "暂时无法复制评论")
                        if (copied) fallbackVisible = false
                    }) { Text("复制原文") }
                    textTool?.let { target ->
                        TextButton(onClick = {
                            if (openCommentTextTool(context, text, target)) fallbackVisible = false
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
