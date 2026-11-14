package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.SourceResumeChoice
import dev.mangaflow.reader.ReaderDialogSurface
import dev.mangaflow.reader.ReaderPanelHeading
import dev.mangaflow.reader.ReaderToggle
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionTextButton

/** A page number is an explicit approximation, never an asserted cross-edition image match. */
@Composable
internal fun SourceResumePrompt(
    request: SourceResumeChoice,
    backdrop: HazeState,
    onChoose: (samePage: Boolean, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var rememberChoice by remember(request) { mutableStateOf(false) }
    var submitting by remember(request) { mutableStateOf(false) }
    val pageNumber = request.fromPageIndex.toLong().coerceAtLeast(0) + 1L
    val approximatePercent = if (request.fromPageCount > 1)
        (request.fromPageIndex.toFloat() / (request.fromPageCount - 1) * 100).toInt().coerceIn(0, 100)
        else null
    fun choose(samePage: Boolean) {
        if (submitting) return
        submitting = true
        onChoose(samePage, rememberChoice)
    }

    MotionDialog(visible = true, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth(), backdrop,
        ) {
            Column(Modifier.heightIn(max = LocalMangaWindow.current.panelHeight)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderPanelHeading("切换到${request.variant.sourceName}", "取消切换", onDismiss)
                Text(request.chapter.title, color = colors.onSurface, fontSize = 14.sp)
                Text("当前第${pageNumber}页" + (approximatePercent?.let { " · 约 $it%" } ?: "") +
                    "。版本分图可能不同，将按进度比例找附近位置。",
                    color = colors.onSurfaceVariant, fontSize = 12.sp)
                ReaderToggle("记住选择，不再提示", rememberChoice,
                    description = "可在阅读设置中更改") { rememberChoice = it }
                MotionButton(onClick = { choose(true) }, enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface,
                        contentColor = colors.surface)) {
                    Text("在新来源附近继续")
                }
                MotionTextButton(onClick = { choose(false) }, enabled = !submitting,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("使用该来源上次记录", color = colors.onSurface)
                }
                Text("该来源无记录时从本话开头。可在阅读设置中修改默认续读方式。", color = colors.onSurfaceVariant,
                    fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
}
