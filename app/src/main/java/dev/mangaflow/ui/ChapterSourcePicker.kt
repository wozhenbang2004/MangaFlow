package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.roundToInt

private val failedSourceMarks = mutableStateMapOf<String, Boolean>()

/** Copy uses our muted-blue wordmark; other providers retain their own marks/name fallback. */
@Composable
internal fun ChapterSourceLogo(sourceId: String, sourceName: String, modifier: Modifier = Modifier,
    muted: Boolean = false) {
    val favicon = when (sourceId) {
        "komiic" -> "https://komiic.com/favicon.ico"
        "mangadex" -> "https://mangadex.org/favicon.ico"
        "baozi" -> "https://www.baozimh.com/favicon.ico"
        "manhuagui" -> "https://www.manhuagui.com/favicon.ico"
        "mangabz" -> "https://mangabz.com/favicon.ico"
        else -> null
    }
    val failed = failedSourceMarks[sourceId] == true
    val monochrome = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (sourceId == "copy") CopySourceWordmark(Modifier.fillMaxSize())
        else if (favicon != null && !failed) AsyncImage(
            model = favicon, contentDescription = null, contentScale = ContentScale.Fit,
            colorFilter = if (muted) monochrome else null,
            modifier = Modifier.fillMaxSize(), onError = { failedSourceMarks[sourceId] = true },
        ) else Text(sourceName, color = DetailSecondary, fontSize = 9.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Each visible source owns a separate hit slot; only actual overflow shares a list. */
@Composable
internal fun ChapterSourceBadge(
    chapter: Chapter,
    modifier: Modifier = Modifier,
    onOpen: (Rect?) -> Unit,
    onReadVariant: (Chapter, ChapterVariant) -> Unit = { _, _ -> },
    enabled: Boolean = true,
) {
    if (chapter.variants.isEmpty()) return
    val sources = remember(chapter.id, chapter.variants) {
        chapter.variants.sortedBy { if (it.storageId == chapter.id) 0 else 1 }
            .groupBy { it.sourceId }.values.toList()
    }
    val configuration = LocalViewConfiguration.current
    val compactTargets = remember(configuration) {
        object : ViewConfiguration by configuration {
            override val minimumTouchTargetSize = DpSize(24.dp, 24.dp)
        }
    }
    BoxWithConstraints(modifier.height(24.dp)) {
        val capacity = (maxWidth.value / 24f).toInt().coerceAtLeast(1)
        val directCount = if (sources.size <= capacity) sources.size else (capacity - 1).coerceAtLeast(0)
        CompositionLocalProvider(LocalViewConfiguration provides compactTargets) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                sources.take(directCount).forEach { variants ->
                    ChapterSourceBadgeItem(chapter, variants, overflow = false, enabled = enabled,
                        onOpen = onOpen, onReadVariant = onReadVariant)
                }
                if (directCount < sources.size) {
                    ChapterSourceBadgeItem(chapter, sources.drop(directCount).map { it.first() },
                        overflow = true, enabled = enabled, onOpen = onOpen, onReadVariant = onReadVariant)
                }
            }
        }
    }
}

@Composable
private fun ChapterSourceBadgeItem(
    chapter: Chapter,
    variants: List<ChapterVariant>,
    overflow: Boolean,
    enabled: Boolean,
    onOpen: (Rect?) -> Unit,
    onReadVariant: (Chapter, ChapterVariant) -> Unit,
) {
    val first = variants.first()
    val needsPicker = overflow || variants.size > 1
    var anchor by remember(chapter.logicalId, first.storageId) { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val description = when {
        overflow -> "${variants.size}个其他来源，选择来源"
        variants.size > 1 -> "${first.sourceName}，${variants.size}个版本，选择版本"
        else -> "使用${first.sourceName}阅读本章"
    }
    Box(Modifier.size(24.dp).onGloballyPositioned { coordinates ->
        val origin = coordinates.localToScreen(Offset.Zero)
        if (origin.isSpecified) anchor = Rect(origin, androidx.compose.ui.geometry.Size(
            coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }.clickable(enabled = enabled, interactionSource = interaction, indication = null,
        role = Role.Button, onClickLabel = description,
        onClick = { if (needsPicker) onOpen(anchor) else onReadVariant(chapter, first) })
        .motionPress(interaction, enabled = enabled, pressedScale = .94f)
        .semantics { contentDescription = chapter.title + "，" + description },
        contentAlignment = Alignment.Center) {
        if (overflow) {
            val marks = variants.take(3)
            val spacing = if (marks.size > 1) 10.dp / (marks.size - 1) else 0.dp
            Box(Modifier.size(24.dp), contentAlignment = Alignment.CenterStart) {
                marks.indices.reversed().forEach { index ->
                    val variant = marks[index]
                    ChapterSourceLogo(variant.sourceId, variant.sourceName,
                        Modifier.absoluteOffset(x = spacing * index).size(14.dp), muted = true)
                }
            }
        } else ChapterSourceLogo(first.sourceId, first.sourceName, Modifier.size(14.dp), muted = true)
    }
}

private object ChapterSourceWindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * A single catalog/reader owner. The measured panel morphs from the selected badge;
 * no grid item changes size. Inaccessible/short windows use the app's frosted sheet.
 * Source selection starts preparation in the caller and stays visible on failure.
 */
@Composable
internal fun ChapterSourcePicker(
    chapter: Chapter?,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onReadVariant: (Chapter, ChapterVariant) -> Unit,
    onSetPreferredSource: (String) -> Unit = {},
    onClearChapterSource: (Chapter) -> Unit = {},
    onSplitVariant: (Chapter, ChapterVariant) -> Unit = { _, _ -> },
    onRelinkVariant: (Chapter, ChapterVariant, Chapter) -> Unit = { _, _, _ -> },
    chapters: List<Chapter> = emptyList(),
    allowCorrections: Boolean = chapters.isNotEmpty(),
    downloaded: Map<String, DownloadEntry> = emptyMap(),
    chapterPositions: Map<String, ReadingProgress>? = null,
    anchor: Rect? = null,
    closeRequested: Boolean = false,
    onCancelSelection: () -> Unit = {},
    loading: Boolean = false,
    error: String? = null,
    onPresenceChange: (Boolean) -> Unit = {},
) {
    if (chapter == null) return
    val key = chapter.logicalId.ifBlank { chapter.id }
    val frozen = remember(key) { chapter }
    val sourceAnchor = remember(key) { anchor }
    val motion = LocalMotionEnabled.current
    val window = LocalMangaWindow.current
    val density = LocalDensity.current
    var closing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, closeRequested) { if (closeRequested) closing = true }
    val onClosed by rememberUpdatedState(onDismiss)
    val cancelSelection by rememberUpdatedState(onCancelSelection)
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(true) }
    DisposableEffect(key) { onDispose { reportPresence(false) } }
    val close = { cancelSelection(); closing = true }
    val useSheet = sourceAnchor == null || density.fontScale > 1.35f ||
        window.height < 520.dp
    val content: @Composable (() -> Unit) -> Unit = { dismiss ->
        ChapterSourceContent(frozen, downloaded, chapterPositions, loading, error, onReadVariant, dismiss)
    }
    if (useSheet) {
        MotionBottomSheet(onDismissRequest = { if (!closeRequested) cancelSelection(); onClosed() }, backdrop = backdrop,
            containerColor = DetailSurface, contentColor = DetailForeground) { dismiss ->
            LaunchedEffect(closeRequested) { if (closeRequested) dismiss() }
            val userDismiss = { cancelSelection(); dismiss() }
            BackHandler(enabled = !closeRequested) { userDismiss() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                content(userDismiss)
            }
        }
        return
    }
    val progress = remember(key) { Animatable(0f) }
    var origin by remember(key) { mutableStateOf<Offset?>(null) }
    var panelSize by remember(key) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(key, closing, motion, origin != null, panelSize != IntSize.Zero) {
        if (origin == null || panelSize == IntSize.Zero) return@LaunchedEffect
        progress.animateTo(if (closing) 0f else 1f,
            if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap())
        if (closing) onClosed()
    }
    Popup(popupPositionProvider = ChapterSourceWindowOrigin, onDismissRequest = close,
        properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned {
            val offset = it.localToScreen(Offset.Zero)
            if (offset.isSpecified) origin = offset
        }.semantics { dismiss { close(); true } }) {
            val panelWidth = minOf(maxWidth - 24.dp, 308.dp).coerceAtLeast(1.dp)
            val windowWidth = with(density) { maxWidth.toPx() }
            val windowHeight = with(density) { maxHeight.toPx() }
            val inset = with(density) { 12.dp.toPx() }
            val width = with(density) { panelWidth.toPx() }
            val screenOrigin = origin ?: Offset.Zero
            val from = sourceAnchor!!.translate(-screenOrigin)
            val left = (from.center.x - width / 2f).coerceIn(inset, (windowWidth - width - inset).coerceAtLeast(inset))
            val height = panelSize.height.toFloat().coerceAtLeast(1f)
            val preferredTop = if (from.top > height + inset * 2f) from.top - height - inset else from.bottom + inset
            val top = preferredTop.coerceIn(inset, (windowHeight - height - inset).coerceAtLeast(inset))
            Box(Modifier.fillMaxSize().background(DetailForeground.copy(alpha = .09f * progress.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "收起章节来源", onClick = close))
            LiquidGlassSurface(backdrop,
                Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .width(panelWidth).heightIn(max = (maxHeight - 24.dp).coerceAtLeast(48.dp))
                    .onSizeChanged { panelSize = it }
                    .motionInputGate(!closing && progress.value >= .95f)
                    .graphicsLayer {
                        val p = progress.value
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = (from.left - left) * (1f - p)
                        translationY = (from.top - top) * (1f - p)
                        scaleX = (from.width / width).coerceIn(.08f, 1f) * (1f - p) + p
                        scaleY = (from.height / height).coerceIn(.04f, 1f) * (1f - p) + p
                        alpha = if (origin == null) 0f else .65f + .35f * p
                    },
                cornerRadius = 20.dp, role = GlassSurfaceRole.POPOVER, softCard = true,
                materialExpansion = { progress.value }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = ((progress.value - .35f) / .65f).coerceIn(0f, 1f) }
                    .padding(10.dp)) { content(close) }
            }
        }
    }
}

@Composable
private fun ChapterSourceContent(
    chapter: Chapter,
    downloaded: Map<String, DownloadEntry>,
    positions: Map<String, ReadingProgress>?,
    loading: Boolean,
    error: String?,
    onRead: (Chapter, ChapterVariant) -> Unit,
    dismiss: () -> Unit,
) {
    var requestedVariant by remember(chapter.logicalId, chapter.id) { mutableStateOf<ChapterVariant?>(null) }
    val variants = remember(chapter.id, chapter.variants) {
        chapter.variants.sortedBy { if (it.storageId == chapter.id) 0 else 1 }
    }
    val sourceCount = variants.map { it.sourceId }.distinct().size
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chapter.title, color = DetailForeground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (sourceCount == variants.size) "$sourceCount 个来源" else
                    "$sourceCount 源 · ${variants.size} 个版本", color = DetailMuted, fontSize = 11.sp)
            }
            DetailAction(Icons.Outlined.Close, "收起章节来源", onClick = dismiss)
        }
        variants.forEach { variant ->
            val selected = variant.storageId == chapter.id
            val entry = downloaded[variant.storageId]
            val saved = positions?.get(variant.storageId)
            val interaction = remember(variant.storageId) { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) DetailForeground.copy(alpha = .055f) else Color.Transparent)
                .clickable(enabled = !loading, interactionSource = interaction, indication = null,
                    role = Role.Button, onClickLabel = "使用${variant.sourceName}阅读本章",
                    onClick = { requestedVariant = variant; onRead(chapter, variant) })
                .motionPress(interaction, enabled = !loading, pressedScale = .99f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(variant.sourceName, color = DetailForeground, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (selected) Icon(Icons.Outlined.Check, "当前来源", Modifier.size(12.dp), tint = DetailSecondary)
                        if (loading && requestedVariant?.storageId == variant.storageId)
                            MotionBusyIndicator(Modifier.size(12.dp), color = DetailSecondary, strokeWidth = 1.3.dp)
                    }
                    val identity = listOf(variant.language, variant.edition, variant.scanlator)
                        .filter(String::isNotBlank).joinToString(" · ")
                    Text(identity, color = DetailMuted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (variant.title != chapter.title) Text(variant.title, color = DetailSecondary,
                        fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val location = listOfNotNull(
                        "已下载".takeIf { entry?.status == DownloadStatus.COMPLETED },
                        saved?.let { "续读第${it.pageIndex + 1}页" },
                    ).joinToString(" · ")
                    if (location.isNotEmpty()) Text(location, color = DetailMuted, fontSize = 10.sp)
                }
            }
        }
        if (loading) Row(Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionBusyIndicator(Modifier.size(14.dp), color = DetailSecondary, strokeWidth = 1.5.dp)
            Text("正在准备${requestedVariant?.sourceName.orEmpty()}本章", color = DetailSecondary, fontSize = 11.sp)
        }
        if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}
