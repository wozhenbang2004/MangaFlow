package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

/** Measure the remaining workspace after the app navigation has taken its share. */
@Composable
internal fun fitsDesktopIndex(width: Dp, minimum: Dp = 720.dp): Boolean =
    platformKind == PlatformKind.DESKTOP && width >= minimum * LocalDensity.current.fontScale.coerceAtLeast(1f)

@Composable
internal fun DesktopIndexColumns(
    modifier: Modifier = Modifier,
    ordinalWidth: Dp = 0.dp,
    ordinal: @Composable () -> Unit = {},
    work: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    figure: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ordinalWidth > 0.dp) Box(Modifier.width(ordinalWidth)) { ordinal() }
        Box(Modifier.weight(if (figure == null) .64f else .50f).padding(end = 16.dp)) { work() }
        Box(Modifier.weight(if (figure == null) .36f else .26f).padding(end = 12.dp)) { detail() }
        if (figure != null) Box(Modifier.weight(.24f)) { figure() }
        Box(Modifier.width(38.dp), contentAlignment = Alignment.CenterEnd) { action() }
    }
}

@Composable
internal fun DesktopIndexLabels(detail: String, figure: String?, ordinalWidth: Dp = 0.dp,
    modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        DesktopIndexColumns(Modifier.heightIn(min = 32.dp), ordinalWidth,
            ordinal = { DesktopIndexNote("位次") }, work = { DesktopIndexNote("作品") },
            detail = { DesktopIndexNote(detail) }, figure = if (figure != null) ({ DesktopIndexNote(figure) }) else null)
        HorizontalDivider(color = ink.copy(alpha = .17f))
    }
}

@Composable
internal fun DesktopIndexNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun DesktopIndexWork(manga: Manga, cover: @Composable (Modifier) -> Unit, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        cover(Modifier.size(43.dp, 60.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, style = browseTitleStyle(16.sp, 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) DesktopIndexNote(subtitle)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DesktopBrowseIndexRow(
    manga: Manga,
    repository: MangaRepository,
    placement: String,
    subtitle: String = manga.authors.take(3).joinToString(" / "),
    detail: @Composable () -> Unit,
    figure: (@Composable () -> Unit)?,
    ordinalWidth: Dp = 0.dp,
    ordinal: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Column(Modifier.fillMaxWidth()) {
        DesktopIndexColumns(Modifier.browseIntentWarmup(manga, repository, interaction)
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "查看${manga.title}", onLongClickLabel = if (onLongClick != null) "漫画操作" else null,
                onLongClick = onLongClick, onClick = {
                    transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                    onOpen()
                })
            .motionPress(interaction, pressedScale = .997f).padding(vertical = 7.dp), ordinalWidth, ordinal,
            work = { DesktopIndexWork(manga, cover = { frame ->
                MangaCover(manga, repository, frame.mangaSharedCover(manga.id, placement).mangaCoverCard(5.dp))
            }, subtitle = subtitle) }, detail = detail, figure = figure)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f))
    }
}

/** Native filter options remain available while the independent catalogue is scrolled. */
@Composable
internal fun DesktopCatalogueSidebar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = .025f))) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 17.dp, end = 18.dp, top = 17.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
    }
}

@Composable
internal fun DesktopFilterHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopFilterOptions(heading: String, labels: List<String>, selection: Int, enabled: Boolean,
    onChoose: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DesktopFilterHeading(heading)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEachIndexed { index, label ->
                val chosen = index == selection
                MotionTextButton(onClick = { if (!chosen) onChoose(index) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 32.dp).semantics { role = Role.RadioButton; selected = chosen },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (chosen) colors.primary.copy(alpha = .11f) else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (chosen) colors.primary else colors.onSurface)) {
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
