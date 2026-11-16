@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Page
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.readerCacheOnly
import dev.mangaflow.experience.readerPageRequest
import dev.mangaflow.experience.readerPreviewSize
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

private enum class RepairImageSide { REFERENCE, BACKUP }

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
    var inspecting by remember(controller, content?.primaryIndex) { mutableStateOf(false) }
    var referenceIndex by remember(controller, content?.primaryIndex) {
        mutableIntStateOf(content?.primaryIndex ?: 0)
    }
    var side by remember(controller, content?.primaryIndex) { mutableStateOf(RepairImageSide.REFERENCE) }
    val comparisonScroll = rememberScrollState()
    val sourceScroll = rememberScrollState()
    LaunchedEffect(controller, content?.primaryIndex) {
        comparisonScroll.scrollTo(0)
        sourceScroll.scrollTo(0)
    }
    val dismiss = { if (inspecting) inspecting = false else controller.dismiss() }
    MotionDialog(visible = selection != null, onDismissRequest = dismiss,
        maximumWidth = 960.dp, properties = DialogProperties(usePlatformDefaultWidth = false),
        onClosed = { retained = null; controller.selectionClosed() }) {
        if (content != null) {
            val window = LocalMangaWindow.current
            val focus = LocalFocusManager.current
            val interactive = selection != null && content.phase != ReaderRepairPhase.SAVING
            var targetText by remember(content.variant.storageId, content.targetIndex) {
                mutableStateOf((content.targetIndex + 1).toString())
            }
            val targetMatchesPreview = targetText.toIntOrNull() == content.targetIndex + 1
            val referenceState = key(referenceIndex, state.primaryReferences[referenceIndex],
                primaryPages.getOrNull(referenceIndex), primaryHeaders, primarySource) { rememberReaderImageState() }
            val backupZoom = key(content.variant.storageId, content.targetIndex, content.environment, content.preview?.page) {
                rememberZoomableState()
            }
            val inspect: (RepairImageSide) -> Unit = {
                focus.clearFocus(force = true)
                side = it
                inspecting = true
            }
            ReaderDialogSurface(Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .height(window.panelHeight.coerceAtMost(860.dp)), backdrop) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                    ReaderPanelHeading("对齐 · 原页 ${content.primaryIndex + 1}", "关闭单页对齐", controller::dismiss)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide = maxWidth >= 660.dp * window.fontScale
                        val imageHeight = (maxHeight - (if (wide) 174.dp else 202.dp) * window.fontScale)
                            .coerceIn(180.dp, 520.dp)
                        MotionTabContent(selected = inspecting, contentKey = { it }, order = { if (it) 1 else 0 },
                            modifier = Modifier.fillMaxSize()) { enlarged ->
                            if (enlarged) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        MotionIconButton({ inspecting = false }, tooltip = "返回对照") {
                                            Icon(Icons.Outlined.ArrowBack, "返回对照", Modifier.size(19.dp))
                                        }
                                        RepairSideTabs(side, referenceIndex, content.primaryIndex, content.targetIndex,
                                            { side = it }, Modifier.weight(1f))
                                    }
                                    Box(Modifier.weight(1f).fillMaxWidth()) {
                                        RepairComparisonImage(side, referenceIndex, content, state.primaryReferences,
                                            primaryPages, primaryHeaders, primarySource, referenceState, backupZoom, true, interactive,
                                            Modifier.fillMaxSize(), onInspect = null)
                                    }
                                    Text(if (side == RepairImageSide.REFERENCE) "本机参考 · 缩放 / 拖动查看"
                                        else "备用预览 · 缩放 / 拖动查看", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Column(Modifier.fillMaxSize().verticalScroll(comparisonScroll),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(sourceScroll),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        alternatives.forEach { variant ->
                                            val chosen = content.variant.storageId == variant.storageId
                                            MotionTextButton(onClick = {
                                                controller.select(content.primaryIndex, variant)
                                            }, enabled = interactive, modifier = Modifier.semantics {
                                                contentDescription = "${variant.sourceName} · ${variant.edition} · ${variant.scanlator} · ${variant.title}"
                                                selected = chosen
                                            }) {
                                                ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(19.dp))
                                                if (chosen) Icon(Icons.Outlined.Check, "当前对齐来源：${variant.sourceName}",
                                                    Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Text(listOf(content.variant.sourceName, content.variant.edition, content.variant.scanlator)
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (wide) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            RepairImageSide.entries.forEach { imageSide ->
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    RepairImageHeading(imageSide, referenceIndex, content, primarySource)
                                                    RepairComparisonImage(imageSide, referenceIndex, content,
                                                        state.primaryReferences, primaryPages, primaryHeaders, primarySource, referenceState, backupZoom,
                                                        false, interactive, Modifier.fillMaxWidth().height(imageHeight),
                                                        onInspect = { inspect(imageSide) })
                                                }
                                            }
                                        }
                                    } else {
                                        RepairSideTabs(side, referenceIndex, content.primaryIndex, content.targetIndex,
                                            { side = it }, Modifier.fillMaxWidth())
                                        RepairComparisonImage(side, referenceIndex, content, state.primaryReferences,
                                            primaryPages, primaryHeaders, primarySource, referenceState, backupZoom, false, interactive,
                                            Modifier.fillMaxWidth().height(imageHeight), onInspect = { inspect(side) })
                                    }
                                    RepairReferenceStrip(content.primaryIndex, referenceIndex, state.primaryReferences,
                                        primaryPages, primaryHeaders, primarySource, interactive,
                                        onSelect = { referenceIndex = it; side = RepairImageSide.REFERENCE })
                                }
                            }
                        }
                    }
                    if (!inspecting) RepairTargetNavigation(content, targetText, { targetText = it }, interactive, controller)
                    (content.reason ?: state.reason)?.let {
                        Text(it, Modifier.fillMaxWidth().heightIn(max = 64.dp).verticalScroll(rememberScrollState()),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${primarySource} · ${content.primaryIndex + 1} ← ${content.variant.sourceName} · ${content.targetIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp).semantics {
                            contentDescription = "仅替换原来源 $primarySource 第 ${content.primaryIndex + 1} 页，" +
                                "使用 ${content.variant.sourceName} 第 ${content.targetIndex + 1} 页"
                        })
                    Text(if (targetMatchesPreview) "确认相同画面；附近位置仅为估算。" else "先打开输入的备用页，再确认画面。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (state.replacements.containsKey(content.primaryIndex)) MotionIconButton(
                            { controller.undo(content.primaryIndex) }, enabled = interactive, tooltip = "撤回这一页的替换") {
                            Icon(Icons.Outlined.Undo, "撤回这一页的替换", Modifier.size(19.dp))
                        }
                        if (content.phase == ReaderRepairPhase.ERROR || content.phase == ReaderRepairPhase.DEFERRED)
                            MotionTextButton(controller::retrySelection, enabled = interactive) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp)); Text("重试")
                            }
                        MotionTextButton(controller::confirm,
                            enabled = selection != null && content.phase == ReaderRepairPhase.READY && targetMatchesPreview) {
                            if (content.phase == ReaderRepairPhase.SAVING) MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(5.dp)); Text("补这一页", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairSideTabs(side: RepairImageSide, referenceIndex: Int, primaryIndex: Int, targetIndex: Int,
    onSelect: (RepairImageSide) -> Unit, modifier: Modifier) {
    MotionTabs(selected = side.ordinal,
        labels = listOf("${if (referenceIndex == primaryIndex) "原页" else "邻页"} ${referenceIndex + 1}", "备用 ${targetIndex + 1}"),
        onSelect = { onSelect(RepairImageSide.entries[it]) }, modifier = modifier, compact = true,
        controlHeight = 44.dp, fullWidthIndicator = true)
}

@Composable
private fun RepairImageHeading(side: RepairImageSide, referenceIndex: Int,
    content: ReaderRepairSelection, primarySource: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val reference = side == RepairImageSide.REFERENCE
        ChapterSourceLogo(if (reference) primarySource else content.variant.sourceId,
            if (reference) primarySource else content.variant.sourceName, Modifier.size(18.dp))
        Text(if (reference) "${if (referenceIndex == content.primaryIndex) "原页" else "邻页参考"} ${referenceIndex + 1}"
            else "${content.variant.sourceName} · ${content.targetIndex + 1}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RepairComparisonImage(side: RepairImageSide, referenceIndex: Int, content: ReaderRepairSelection,
    references: Map<Int, Page>, pages: List<Page>, headers: Map<String, String>, source: String,
    referenceState: ReaderImageState, backupZoom: ZoomableState,
    enlarged: Boolean, interactive: Boolean, modifier: Modifier, onInspect: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val inspect = if (onInspect != null) Modifier.clickable(enabled = interactive,
        interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = "放大查看", onClick = onInspect).motionPress(interaction, interactive) else Modifier
    Box(modifier.then(inspect).clip(RoundedCornerShape(9.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)), contentAlignment = Alignment.Center) {
        if (side == RepairImageSide.REFERENCE) {
            val page = references[referenceIndex] ?: pages.getOrNull(referenceIndex)
            if (page != null) key(page, headers, source) {
                RepairReferenceImage(page, headers, source, referenceIndex, referenceState, enlarged, interactive)
            } else RepairMissingReference()
        } else {
            content.preview?.let { preview -> key(preview) {
                val context = LocalMangaContext.current
                val painter = remember(preview, context) { preview.image.asPainter(imagePlatformContext(context)) }
                if (enlarged) {
                    // Use the same Telephoto painter/gesture wiring as PortableReaderZoomableImage.
                    // This is the prepared preview; inspecting it never starts another source request.
                    val zoom = backupZoom
                    zoom.contentScale = ContentScale.Fit
                    zoom.contentAlignment = Alignment.Center
                    zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(painter.intrinsicSize))
                    Image(painter, "备用来源 ${content.variant.sourceName} 第 ${content.targetIndex + 1} 页预览",
                        Modifier.fillMaxSize().zoomable(zoom, enabled = interactive),
                        contentScale = ContentScale.Inside)
                } else Image(painter, "备用来源 ${content.variant.sourceName} 第 ${content.targetIndex + 1} 页预览",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } }
            if (content.phase == ReaderRepairPhase.DIRECTORY || content.phase == ReaderRepairPhase.IMAGE)
                MotionBusyIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (content.preview == null) Icon(Icons.Outlined.ImageNotSupported, "备用页尚不可用",
                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onInspect != null) Icon(Icons.Outlined.OpenInFull, null,
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RepairReferenceImage(page: Page, headers: Map<String, String>, source: String,
    index: Int, image: ReaderImageState, enlarged: Boolean, interactive: Boolean) {
    val context = LocalMangaContext.current
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    val budget by policy.state.collectAsState()
    var unavailable by remember(page, headers, source, enlarged) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val size = readerPreviewSize(with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() },
            budget.runtime.previewWidthPx, budget.runtime.previewHeightPx)
        val request = remember(page, headers, source, size, enlarged) {
            readerPageRequest(context, page, headers, source).readerCacheOnly().size(size.width, size.height)
                .listener(onSuccess = { _, _ -> unavailable = false }, onError = { _, _ -> unavailable = true }).build()
        }
        if (enlarged) {
            ReaderZoomableImage(request, "原来源 $source 第 ${index + 1} 页，本机参考",
                Modifier.fillMaxSize(), image, gesturesEnabled = interactive && !unavailable)
            (image.detailError ?: image.detailNotice)?.let { message ->
                Text(message, Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else AsyncImage(request, "原来源 $source 第 ${index + 1} 页，本机参考",
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (unavailable) RepairMissingReference()
    }
}

@Composable
private fun RepairMissingReference() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.ImageNotSupported, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本机无参考图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RepairReferenceStrip(primaryIndex: Int, selectedIndex: Int, references: Map<Int, Page>, pages: List<Page>,
    headers: Map<String, String>, source: String, enabled: Boolean, onSelect: (Int) -> Unit) {
    val context = LocalMangaContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(primaryIndex - 1, primaryIndex, primaryIndex + 1).filter { it in pages.indices }.forEach { index ->
            val page = references[index] ?: pages[index]
            key(page, headers, source) {
                val interaction = remember { MutableInteractionSource() }
                var unavailable by remember { mutableStateOf(false) }
                val request = remember(page, headers, source) {
                    readerPageRequest(context, page, headers, source).readerCacheOnly().size(160, 256).build()
                }
                Column(Modifier.width(68.dp).clip(RoundedCornerShape(7.dp))
                    .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button,
                        onClickLabel = "查看本机参考", onClick = { onSelect(index) }).motionPress(interaction, enabled)
                    .semantics { selected = index == selectedIndex }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(60.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)), contentAlignment = Alignment.Center) {
                        AsyncImage(request, "原来源 $source 第 ${index + 1} 页，本机参考", Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit, onSuccess = { unavailable = false }, onError = { unavailable = true })
                        if (unavailable) Icon(Icons.Outlined.ImageNotSupported, "本机无参考图", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (index == selectedIndex) Icon(Icons.Outlined.Check, "当前参考页", Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("${index + 1}${if (index == primaryIndex) " · 原页" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairTargetNavigation(content: ReaderRepairSelection, targetText: String, onTargetText: (String) -> Unit,
    enabled: Boolean, controller: ReaderPageRepairController) {
    val target = targetText.toIntOrNull()?.takeIf { it > 0 && it <= content.pages.size }?.minus(1)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        MotionIconButton({ controller.moveTo(content.targetIndex - 1) }, enabled = enabled && content.targetIndex > 0,
            tooltip = "前一张备用页") { Icon(Icons.Outlined.ArrowBack, "前一张备用页", Modifier.size(18.dp)) }
        BasicTextField(targetText, onTargetText, enabled = enabled, singleLine = true,
            modifier = Modifier.width(72.dp).heightIn(min = 44.dp).padding(vertical = 12.dp, horizontal = 8.dp)
                .semantics { contentDescription = "备用页码，共 ${content.pages.size} 页" },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (enabled) target?.let(controller::moveTo) }))
        Text("/ ${content.pages.size.takeIf { it > 0 } ?: "—"}", style = MaterialTheme.typography.labelSmall)
        MotionIconButton({ target?.let(controller::moveTo) }, enabled = enabled && target != null,
            tooltip = "打开输入的备用页") { Icon(Icons.Outlined.Refresh, "打开输入的备用页", Modifier.size(17.dp)) }
        MotionIconButton({ if (content.targetIndex < Int.MAX_VALUE) controller.moveTo(content.targetIndex + 1) },
            enabled = enabled && content.targetIndex < content.pages.lastIndex, tooltip = "后一张备用页") {
            Icon(Icons.Outlined.ArrowForward, "后一张备用页", Modifier.size(18.dp))
        }
    }
}
