package dev.mangaflow.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Page
import dev.mangaflow.experience.readerPageRequest
import dev.mangaflow.experience.readerCacheOnly
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** Uses the retained reader PANEL and existing source/press controls; ratios are navigation only. */
@Composable
internal fun ReaderPageRepairDialog(
    state: ReaderRepairState,
    primaryPages: List<Page>,
    primaryHeaders: Map<String, String>,
    primarySource: String,
    alternatives: List<ChapterVariant>,
    backdrop: HazeState,
    controller: ReaderPageRepairController,
) {
    val selection = state.selection
    var retained by remember(controller) { mutableStateOf<ReaderRepairSelection?>(null) }
    SideEffect { if (selection != null) retained = selection }
    val content = selection ?: retained
    val context = LocalMangaContext.current
    MotionDialog(visible = selection != null, onDismissRequest = controller::dismiss,
        maximumWidth = 600.dp, properties = DialogProperties(usePlatformDefaultWidth = false),
        onClosed = { retained = null; controller.selectionClosed() }) {
        if (content != null) ReaderDialogSurface(Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .heightIn(max = LocalMangaWindow.current.panelHeight), backdrop) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderPanelHeading("对齐 · ${content.primaryIndex + 1}", "关闭单页对齐", controller::dismiss)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    alternatives.forEach { variant ->
                        val chosen = content.variant.storageId == variant.storageId
                        MotionTextButton(onClick = { controller.select(content.primaryIndex, variant) }, enabled = selection != null,
                            modifier = Modifier.semantics {
                                contentDescription = "${variant.sourceName} · ${variant.edition} · ${variant.scanlator} · ${variant.title}"
                            }) {
                            ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(19.dp))
                            if (chosen) Icon(Icons.Outlined.Check, "当前对齐来源：${variant.sourceName}", Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(listOf(content.variant.sourceName, content.variant.edition, content.variant.scanlator)
                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("原页与邻页", style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(content.primaryIndex - 1, content.primaryIndex, content.primaryIndex + 1)
                                .filter { it in primaryPages.indices }.forEach { index ->
                                    val page = state.primaryReferences[index] ?: primaryPages[index]
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        var unavailable by remember(page, primaryHeaders, primarySource) { mutableStateOf(false) }
                                        val request = remember(page, primaryHeaders, primarySource) {
                                            readerPageRequest(context, page, primaryHeaders, primarySource)
                                                .readerCacheOnly().size(280, 600).build()
                                        }
                                        Box(Modifier.fillMaxWidth().aspectRatio(.62f).clip(RoundedCornerShape(7.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))) {
                                            AsyncImage(request, "原来源第 ${index + 1} 页，本机参考", Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                                onSuccess = { unavailable = false }, onError = { unavailable = true })
                                            if (unavailable) Icon(Icons.Outlined.ImageNotSupported, "本机没有这一页的可用参考图",
                                                Modifier.align(Alignment.Center).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                                            color = if (index == content.primaryIndex) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                        }
                        Text("邻页只读本机缓存", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("备用页", style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)), contentAlignment = Alignment.Center) {
                            content.preview?.let { preview ->
                                val painter = remember(preview) { preview.image.asPainter(imagePlatformContext(context)) }
                                Image(painter, "备用第 ${content.targetIndex + 1} 页", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            if (content.phase in setOf(ReaderRepairPhase.DIRECTORY, ReaderRepairPhase.IMAGE, ReaderRepairPhase.SAVING))
                                MotionBusyIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                var targetText by remember(content.targetIndex) { mutableStateOf((content.targetIndex + 1).toString()) }
                val target = targetText.toIntOrNull()?.takeIf { it > 0 && it <= content.pages.size }?.minus(1)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    MotionIconButton({ controller.moveTo(content.targetIndex - 1) },
                        enabled = selection != null && content.targetIndex > 0, tooltip = "前一张备用页") {
                        Icon(Icons.Outlined.ArrowBack, "前一张备用页", Modifier.size(18.dp))
                    }
                    BasicTextField(targetText, { targetText = it }, singleLine = true,
                        modifier = Modifier.width(72.dp).heightIn(min = 44.dp).padding(vertical = 12.dp, horizontal = 8.dp)
                            .semantics { contentDescription = "备用页码，共 ${content.pages.size} 页" },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { target?.let(controller::moveTo) }))
                    Text("/ ${content.pages.size.takeIf { it > 0 } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    MotionIconButton({ target?.let(controller::moveTo) }, enabled = selection != null && target != null,
                        tooltip = "打开输入的备用页") { Icon(Icons.Outlined.Refresh, "打开输入的备用页", Modifier.size(17.dp)) }
                    MotionIconButton({ if (content.targetIndex < Int.MAX_VALUE) controller.moveTo(content.targetIndex + 1) },
                        enabled = selection != null && content.targetIndex < content.pages.lastIndex, tooltip = "后一张备用页") {
                        Icon(Icons.Outlined.ArrowForward, "后一张备用页", Modifier.size(18.dp))
                    }
                }
                (content.reason ?: state.reason)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                Text("确认相同画面，仅补这一页；附近位置是估算。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (state.replacements.containsKey(content.primaryIndex)) MotionIconButton(
                        { controller.undo(content.primaryIndex) }, tooltip = "撤回这一页的替换") {
                        Icon(Icons.Outlined.Undo, "撤回这一页的替换", Modifier.size(19.dp))
                    }
                    if (content.phase == ReaderRepairPhase.ERROR || content.phase == ReaderRepairPhase.DEFERRED)
                        MotionTextButton(controller::retrySelection, enabled = selection != null) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重试")
                        }
                    MotionTextButton(controller::confirm, enabled = selection != null && content.phase == ReaderRepairPhase.READY) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp)); Text("补这一页", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
