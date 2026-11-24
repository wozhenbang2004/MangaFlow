package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.anime.sources.DOUBAN_PROVIDER
import dev.mangaflow.anime.sources.AnimeHttp
import dev.mangaflow.anime.sources.lanerc.lanercArtworkUrl
import dev.mangaflow.experience.sourceNetwork
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.typography.MangaFontScene
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.LocalMotionEnabled

internal fun animeEpisodeNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private val episodeOrdinalTitle = Regex("""(?:第|EP(?:ISODE)?\.?)?([0-9]+(?:\.[0-9]+)?)(?:集|话|話)?""",
    RegexOption.IGNORE_CASE)

/** Discard an ordinal alias only when it describes this exact numbered episode. */
internal fun animeEpisodeSubtitle(episode: AnimeEpisode): String? {
    val title = episode.title.trim().ifEmpty { return null }
    val compact = title.filterNot(Char::isWhitespace)
    if (compact == animeEpisodeLabel(episode).filterNot(Char::isWhitespace)) return null
    if (episode.kind == AnimeEpisodeKind.MAIN &&
        episodeOrdinalTitle.matchEntire(compact)?.groupValues?.get(1)?.toDoubleOrNull() == episode.number) return null
    return title
}

internal fun animeEpisodeLabel(episode: AnimeEpisode): String =
    if (episode.subjectId.startsWith("native:") || episode.subjectId.startsWith("douban:") &&
        episode.kind == AnimeEpisodeKind.OTHER && episode.number <= 0.0 && episode.metadataEpisodeId == null)
        episode.title.ifBlank { "来源剧集" }
    else when (episode.kind) {
    AnimeEpisodeKind.MAIN -> "第 " + animeEpisodeNumber(episode.number) + " 集"
    AnimeEpisodeKind.SPECIAL -> "特别篇 " + animeEpisodeNumber(episode.number)
    AnimeEpisodeKind.OPENING -> "片头 " + animeEpisodeNumber(episode.number)
    AnimeEpisodeKind.ENDING -> "片尾 " + animeEpisodeNumber(episode.number)
    AnimeEpisodeKind.TRAILER -> "预告 " + animeEpisodeNumber(episode.number)
    AnimeEpisodeKind.OTHER -> "其他 " + animeEpisodeNumber(episode.number)
}

internal fun animeStatusLabel(status: AnimeWatchStatus?): String = when (status) {
    AnimeWatchStatus.WISH -> "想看"
    AnimeWatchStatus.WATCHING -> "在看"
    AnimeWatchStatus.COMPLETED -> "看过"
    AnimeWatchStatus.ON_HOLD -> "搁置"
    AnimeWatchStatus.DROPPED -> "弃番"
    null -> "未追番"
}

internal fun animeSourceName(id: String): String = when (id) {
    DOUBAN_PROVIDER, "anime-lanerc-douban", "douban" -> "豆瓣"
    "anime-tvtfun" -> "TvTFun"
    "anime-age" -> "AGE 动漫"
    "anime-girigiri" -> "GiriGiriLove"
    "anime-lmm" -> "LMM 动漫"
    else -> id.removePrefix("anime-")
}

internal fun animeSourceName(id: String, state: AnimeScreenState): String =
    state.sourceExtensions.firstOrNull { it.id == id }?.name
        ?: state.sourceResults.firstOrNull { it.providerId == id }?.name
        ?: animeSourceName(id)

internal fun animeProviderName(id: String): String = when (id) {
    DOUBAN_PROVIDER, "anime-lanerc-douban", "douban" -> "豆瓣"
    "anime-bangumi", "bangumi" -> "Bangumi"
    "anime-anilist", "anilist" -> "AniList"
    else -> id
}

internal fun animeTime(milliseconds: Long): String {
    if (milliseconds < 0L) return "--:--"
    val seconds = milliseconds / 1000L
    val minutes = seconds / 60L
    return if (minutes >= 60L) (minutes / 60L).toString() + ":" +
        (minutes % 60L).toString().padStart(2, '0') + ":" + (seconds % 60L).toString().padStart(2, '0')
    else minutes.toString() + ":" + (seconds % 60L).toString().padStart(2, '0')
}

internal fun animePosterSourceId(subject: AnimeSubject): String? = subject.externalIds["native-source"] ?: when {
    subject.canonicalDoubanId() != null -> DOUBAN_PROVIDER
    subject.canonicalBangumiId() != null -> BANGUMI_PROVIDER
    else -> null
}

internal fun ImageRequest.Builder.animeArtwork(url: String, sourceId: String?): ImageRequest.Builder {
    val imageUrl = sourceId?.let { lanercArtworkUrl(it, url) } ?: url
    data(imageUrl)
    if (sourceId != null) sourceNetwork(sourceId, DownloadCategory.ANIME)
    val host = imageUrl.toHttpUrlOrNull()?.takeIf { it.isHttps }?.host
    if (sourceId in setOf(DOUBAN_PROVIDER, "anime-lanerc-douban", "anime-lanerc-lanerc") &&
        (host == "doubanio.com" || host?.endsWith(".doubanio.com") == true)) {
        httpHeaders(NetworkHeaders.Builder().apply {
            set("Referer", "https://movie.douban.com/")
            set("User-Agent", AnimeHttp.USER_AGENT)
        }.build())
    }
    return this
}

@Composable
internal fun AnimePoster(subject: AnimeSubject, modifier: Modifier = Modifier) =
    AnimePoster(subject.coverUrl, modifier, animePosterSourceId(subject))

@Composable
internal fun AnimePoster(
    url: String, modifier: Modifier = Modifier, sourceId: String? = null,
    fallbackUrl: String? = null, title: String? = null,
) {
    val alternate = fallbackUrl?.takeIf { it.isNotBlank() && it != url }
    var imageUrl by remember(url, alternate, sourceId) { mutableStateOf(url.ifBlank { alternate.orEmpty() }) }
    var unavailable by remember(imageUrl, sourceId) { mutableStateOf(imageUrl.isBlank()) }
    Box(modifier.mangaCoverCard(10.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center) {
        if (unavailable && !title.isNullOrBlank()) {
            Text(title, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Icon(Icons.Outlined.Tv, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f))
        }
        if (imageUrl.isNotBlank()) key(sourceId, imageUrl) {
            val context = LocalMangaContext.current
            val request = remember(context, imageUrl, sourceId) {
                ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(imageUrl, sourceId).build()
            }
            AsyncImage(model = request, contentDescription = null,
                onError = {
                    if (alternate != null && imageUrl != alternate) imageUrl = alternate
                    else unavailable = true
                },
                onSuccess = { unavailable = false },
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeSubjectRow(
    subject: AnimeSubject,
    subtitle: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    ratings: (@Composable () -> Unit)? = null,
    onOpenLabel: String = "查看详情",
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember(subject.id) { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .motionPress(interaction, pressedScale = .99f)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = onOpenLabel, onClick = onOpen,
            onLongClickLabel = if (onLongClick != null) "番剧操作" else null, onLongClick = onLongClick)
        .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimePoster(subject, Modifier.width(56.dp).height(80.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(subject.title, style = MaterialTheme.typography.titleSmall,
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ratings?.invoke()
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.invoke()
        }
        if (trailing != null) trailing()
    }
}

/** The existing frosted shell stays, with a readable body over moving video and captions. */
@Composable
internal fun AnimePanelSurface(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidGlassSurface(backdrop, modifier, cornerRadius = cornerRadius,
        role = GlassSurfaceRole.PANEL, softCard = true, preferSource = true) {
        Box(Modifier.fillMaxWidth(), propagateMinConstraints = true, content = content)
    }
}

/** Ovio's stationary hit cells, kept readable when a source supplies many long category names. */
@Composable
internal fun AnimeChoiceStrip(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val motion = LocalMotionEnabled.current
    val index = selected.coerceIn(labels.indices)
    BoxWithConstraints(modifier.fillMaxWidth().motionInputGate(active)) {
        val viewport = maxWidth
        val minimum = with(density) { 13.sp.toDp() * (labels.maxOf { it.length }.coerceIn(2, 8)) + 28.dp }
            .coerceAtLeast(56.dp)
        val width = maxOf(viewport, minimum * labels.size)
        LaunchedEffect(index, labels, width, viewport, density, scroll.maxValue) {
            val cell = with(density) { width.toPx() / labels.size }
            val visible = with(density) { viewport.toPx() }
            val start = index * cell
            val end = start + cell
            val target = when {
                start < scroll.value -> start.toInt()
                end > scroll.value + visible -> (end - visible).toInt()
                else -> scroll.value
            }.coerceIn(0, scroll.maxValue)
            if (motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
        }
        Box(Modifier.horizontalScroll(scroll, enabled = active)) {
            MotionTabs(index, labels, onSelect = onSelect, modifier = Modifier.width(width),
                controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, selectionVisible = selected in labels.indices, tooltips = labels)
        }
    }
}

@Composable
internal fun AnimeMessage(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    action: String? = null,
    icon: ImageVector = Icons.Outlined.Tv,
    onAction: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) MotionTextButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) { Text(action) }
    }
}

@Composable
internal fun AnimeSectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) action()
    }
}

@Composable
internal fun AnimeSelection(
    title: String,
    detail: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleMaxLines: Int = 2,
    detailMaxLines: Int = 3,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.onSurface.copy(alpha = .075f) else Color.Transparent)
        .motionPress(interaction, enabled = enabled, pressedScale = .99f)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }
        .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else .45f),
                maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = detailMaxLines, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = colors.onSurface)
        else Spacer(Modifier.size(18.dp))
    }
}

/** Contextual form field, matching the existing quiet form surfaces; not another search shell. */
@Composable
internal fun AnimeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    emptyHint: String? = null,
    inputModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(placeholder, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.onSurface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 14.dp, vertical = 13.dp)
            .semantics { contentDescription = placeholder }.then(inputModifier),
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && emptyHint != null) Text(emptyHint,
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                field()
            }
        })
    }
}

@Composable
internal fun AnimeQuietLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
