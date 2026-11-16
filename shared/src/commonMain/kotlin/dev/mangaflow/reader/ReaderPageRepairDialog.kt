package dev.mangaflow.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.approximateReaderPage
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.data.sources.materializeJmPage
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Session-only replacement; original chapter, history, offline downloads and page index remain unchanged. */
internal data class ReaderPageRepair(
    val primaryIndex: Int, val nativeIndex: Int, val variant: ChapterVariant,
    val page: Page, val headers: Map<String, String>,
)

@Composable
internal fun ReaderPageRepairDialog(
    index: Int?,
    mainPageCount: Int,
    alternatives: List<ChapterVariant>,
    repository: MangaRepository,
    backdrop: HazeState,
    onApply: (ReaderPageRepair) -> Unit,
    onDismiss: () -> Unit,
) {
    if (index == null || mainPageCount <= 0 || alternatives.isEmpty()) return
    val context = LocalMangaContext.current
    var variant by remember(index, alternatives) { mutableStateOf(alternatives.first()) }
    var candidates by remember(index, variant) { mutableStateOf<List<Page>>(emptyList()) }
    var headers by remember(index, variant) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selected by remember(index, variant) { mutableIntStateOf(0) }
    var directoryBusy by remember(index, variant) { mutableStateOf(true) }
    var imageBusy by remember(index, variant, selected) { mutableStateOf(true) }
    var prepared by remember(index, variant, selected) { mutableStateOf<Page?>(null) }
    var previewReady by remember(index, variant, selected) { mutableStateOf(false) }
    var error by remember(index, variant, selected) { mutableStateOf<String?>(null) }
    var manualIndex by remember(index, variant) { mutableStateOf("") }

    LaunchedEffect(index, variant, repository) {
        directoryBusy = true
        candidates = emptyList()
        prepared = null
        previewReady = false
        error = null
        try {
            val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
            val entries = withContext(Dispatchers.IO) { repository.pages(owner, variant.storageId) }
            if (entries.isEmpty()) throw IllegalStateException("无图片")
            headers = repository.imageHeaders(owner, variant.storageId)
            selected = approximateReaderPage(index, mainPageCount, entries.size)
            manualIndex = (selected + 1).toString()
            candidates = entries
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "备用章节目录暂未读取，请换一个来源" }
        finally { directoryBusy = false }
    }
    val native = candidates.getOrNull(selected)
    LaunchedEffect(index, variant, native, headers) {
        prepared = null
        previewReady = false
        if (native == null) return@LaunchedEffect
        imageBusy = true
        error = null
        try {
            val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
            val resolved = withContext(Dispatchers.IO) {
                val image = resolveScriptPage(context, owner, variant.storageId, native)
                val materialized = materializeScriptPage(context, owner, variant.storageId, image)
                if (variant.sourceId == "jmcomic")
                    materializeJmPage(context, owner, variant.storageId, materialized) ?: materialized
                else materialized
            }
            // Keep the canonical page index while restoring the native source's URL and headers.
            prepared = resolved.copy(index = index, headers = headers + resolved.headers,
                sourceRouteId = variant.sourceId)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "备用图片未能读取，请调整页码或来源" }
        finally { imageBusy = false }
    }
    val request = remember(prepared, variant) {
        prepared?.let { readerPageRequest(context, it, emptyMap(), variant.sourceId).size(520, 780).memoryCachePolicy(CachePolicy.DISABLED).build() }
    }
    MotionDialog(visible = true, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 14.dp).widthIn(max = 520.dp)
            .fillMaxWidth().heightIn(max = 690.dp), backdrop) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderPanelHeading("单页补图 · ${index + 1}", "关闭补图", onDismiss)
                Text("只替换当前图片，不切换整章或修改阅读记录。不同版本可能错页，先核对预览。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    alternatives.forEach { item ->
                        MotionTextButton(onClick = { variant = item }) {
                            Text(item.sourceName + if (item.storageId == variant.storageId) " ✓" else "",
                                fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionTextButton(onClick = {
                        selected = (selected - 1).coerceAtLeast(0)
                        manualIndex = (selected + 1).toString()
                    }, enabled = selected > 0 && !directoryBusy) { Text("‹") }
                    OutlinedTextField(value = manualIndex, onValueChange = { typed ->
                        manualIndex = typed.filter(Char::isDigit).take(7)
                        manualIndex.toIntOrNull()?.let { value ->
                            if (value in 1..candidates.size) selected = value - 1
                        }
                    }, modifier = Modifier.width(92.dp), singleLine = true,
                        label = { Text("备源页") }, enabled = candidates.isNotEmpty())
                    Text(if (directoryBusy) "读取目录…" else "/ ${candidates.size}",
                        modifier = Modifier.weight(1f), fontSize = 12.sp)
                    MotionTextButton(onClick = {
                        selected = (selected + 1).coerceAtMost(candidates.lastIndex)
                        manualIndex = (selected + 1).toString()
                    }, enabled = candidates.isNotEmpty() && selected < candidates.lastIndex) { Text("›") }
                }
                Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
                    if (request != null) AsyncImage(model = request,
                        contentDescription = "备用第 ${selected + 1} 页预览",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        onSuccess = { previewReady = true },
                        onError = { previewReady = false; error = "图片预览失败，尝试其他页或来源" })
                    if (directoryBusy || imageBusy) MotionBusyIndicator(Modifier.size(18.dp))
                    error?.let { Text(it, modifier = Modifier.align(Alignment.BottomCenter),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = onDismiss) { Text("取消") }
                    MotionTextButton(onClick = {
                        val image = prepared ?: return@MotionTextButton
                        if (!previewReady) return@MotionTextButton
                        onApply(ReaderPageRepair(index, native?.index ?: selected, variant, image, headers))
                    }, enabled = previewReady && prepared != null && !directoryBusy && !imageBusy) {
                        Text("补这一页")
                    }
                }
            }
        }
    }
}
