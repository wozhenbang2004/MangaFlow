package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.experience.sourceNetwork
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.TextSourceWordmark
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

internal fun animeEpisodeNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Keep a real episode title, but do not repeat a native/number-only primary label. */
internal fun animeEpisodeSubtitle(episode: AnimeEpisode): String? {
    fun normalized(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        val numberOnly = Regex("^第0*([0-9]+)[集话話]$").matchEntire(compact)
        return numberOnly?.groupValues?.get(1)?.toLongOrNull()?.let { "episode:$it" } ?: compact
    }
    return episode.title.trim().takeIf { it.isNotEmpty() && normalized(it) != normalized(animeEpisodeLabel(episode)) }
}

internal fun animeEpisodeLabel(episode: AnimeEpisode): String =
    if (episode.subjectId.startsWith("native:")) episode.title.ifBlank { "来源剧集" }
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

@Composable
internal fun AnimePoster(subject: AnimeSubject, modifier: Modifier = Modifier) =
    AnimePoster(subject.coverUrl, modifier, subject.externalIds["native-source"]
        ?: BANGUMI_PROVIDER.takeIf { "bangumi" in subject.externalIds })

@Composable
internal fun AnimePoster(url: String, modifier: Modifier = Modifier, sourceId: String? = null) {
    Box(modifier.mangaCoverCard(10.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Tv, null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f))
        if (url.isNotBlank()) {
            val context = LocalMangaContext.current
            val request = remember(context, url, sourceId) {
                sourceId?.let { ImageRequest.Builder(imagePlatformContext(context)).data(url)
                    .sourceNetwork(it, DownloadCategory.ANIME).build() } ?: url
            }
            AsyncImage(model = request, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun AnimeSubjectRow(
    subject: AnimeSubject,
    subtitle: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember(subject.id) { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .motionPress(interaction, pressedScale = .99f)
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
        .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimePoster(subject, Modifier.width(52.dp).height(72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(subject.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
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

/** Uses the same neutral, occluded marks as the existing reader. Count means bindings, not playback health. */
@Composable
internal fun AnimeSourceStack(names: List<Pair<String, String>>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sources = names.distinctBy { it.first }.take(3)
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
        .motionPress(interaction, pressedScale = .97f)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = if (names.isEmpty()) "关联播放来源" else
                "已关联 " + names.distinctBy { it.first }.size + " 个来源，打开来源管理"
        }.padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sources.isEmpty()) Icon(Icons.Outlined.Link, null, Modifier.size(19.dp))
        else Box(Modifier.width(22.dp + 10.dp * (sources.size - 1)).height(22.dp)) {
            sources.indices.reversed().forEach { index ->
                Box(Modifier.offset(x = 10.dp * index).size(22.dp).clip(CircleShape)
                    .background(lerp(colors.surface, colors.onSurface, .055f + .025f * index)),
                    contentAlignment = Alignment.Center) {
                    TextSourceWordmark(sources[index].second.take(1), Modifier.size(14.dp),
                        colors.onSurface.copy(alpha = if (index == 0) .85f else .6f))
                }
            }
        }
        Text(if (names.isEmpty()) "来源" else names.distinctBy { it.first }.size.toString(),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
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
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.onSurface),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 14.dp, vertical = 13.dp)
            .semantics { contentDescription = placeholder },
        decorationBox = { field ->
            Box { if (value.isBlank()) Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant.copy(alpha = .7f)); field() }
        })
}

@Composable
internal fun AnimeQuietLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
