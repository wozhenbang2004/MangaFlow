package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun reviewSourceAccent(id: String, onDark: Boolean): Color = when (id) {
    "copy" -> if (onDark) Color(0xFFA8BED2) else Color(0xFF567593)
    "bangumi" -> if (onDark) Color(0xFFE9B1C8) else Color(0xFF9D5C78)
    "anilist" -> if (onDark) Color(0xFFAFCBE9) else Color(0xFF53749A)
    "mangaupdates" -> if (onDark) Color(0xFFD4BFE8) else Color(0xFF7B638F)
    "mangadex" -> if (onDark) Color(0xFFE5C38D) else Color(0xFF96753E)
    "myanimelist" -> if (onDark) Color(0xFFB4C5EA) else Color(0xFF5C709C)
    "kitsu" -> if (onDark) Color(0xFFE4B8A0) else Color(0xFF9B6B52)
    "shikimori" -> if (onDark) Color(0xFFC8C4DE) else Color(0xFF77718E)
    else -> if (onDark) Color(0xFFCFCCD4) else Color(0xFF76717E)
}

/** Every app source glyph uses the same square optical box, independent of a site's initials. */
internal fun reviewSourceMarkWidth(size: Dp, fontScale: Float): Dp = size * (1.1f * fontScale.coerceAtLeast(1f))

/** One local typographic source mark, shared by compact badges and full source rows. */
@Composable
internal fun CopySourceWordmark(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val onDark = MaterialTheme.colorScheme.onSurface.luminance() > .5f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Text("拷", color = reviewSourceAccent("copy", onDark),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium, fontSize = with(density) { (side * .86f).toSp() },
                lineHeight = with(density) { side.toSp() }, letterSpacing = 0.sp),
            maxLines = 1, softWrap = false)
    }
}

/** App-owned pictograms, not replacement official logos. Names retain exact source identity. */
@Composable
internal fun ReviewSourceMark(
    id: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    onDark: Boolean = MaterialTheme.colorScheme.onSurface.luminance() > .5f,
    heat: Boolean = false,
    alignment: Alignment = Alignment.Center,
) {
    val color = reviewSourceAccent(id, onDark)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier.size(width = reviewSourceMarkWidth(size, fontScale), height = size * fontScale)
        .clearAndSetSemantics { }, contentAlignment = alignment) {
        when {
            heat -> Icon(Icons.Outlined.LocalFireDepartment, null, tint = color,
                modifier = Modifier.size(size * (.88f * fontScale)))
            id == "copy" -> CopySourceWordmark(Modifier.requiredSize(size * fontScale))
            id == "kitsu" -> {
                Icon(KitsuGlyph, null, tint = color,
                    modifier = Modifier.size(size * (.92f * fontScale)))
            }
            else -> {
                val glyph = when (id) {
                    "bangumi" -> Icons.Outlined.Tv
                    "anilist" -> Icons.AutoMirrored.Outlined.MenuBook
                    "mangaupdates" -> Icons.Outlined.CollectionsBookmark
                    "myanimelist" -> Icons.AutoMirrored.Outlined.PlaylistPlay
                    "mangadex" -> Icons.Outlined.Bookmarks
                    "shikimori" -> Icons.Outlined.Theaters
                    "tencent" -> Icons.Outlined.SmartDisplay
                    "bahamut" -> Icons.Outlined.Forum
                    "manhuagui" -> Icons.AutoMirrored.Outlined.LibraryBooks
                    "mangabaka" -> Icons.Outlined.AutoStories
                    else -> Icons.Outlined.Language
                }
                Icon(glyph, null, tint = color, modifier = Modifier.size(size * (.92f * fontScale)))
            }
        }
    }
}

private val KitsuGlyph: ImageVector by lazy {
    ImageVector.Builder("Kitsu", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString("M1.429 5.441a12.478 12.478 0 0 0 1.916 2.056c.011.011.022.011.022.022.452.387 1.313.947 1.937 1.173 0 0 3.886 1.496 4.091 1.582a1.4 1.4 0 0 0 .237.075.694.694 0 0 0 .808-.549c.011-.065.022-.172.022-.248V5.161c.011-.667-.205-1.679-.398-2.239 0-.011-.011-.022-.011-.032A11.979 11.979 0 0 0 8.824.36L8.781.285a.697.697 0 0 0-.958-.162c-.054.032-.086.075-.129.119L7.608.36a4.743 4.743 0 0 0-.786 3.412 8.212 8.212 0 0 0-.775.463c-.043.032-.42.291-.71.56A4.803 4.803 0 0 0 1.87 4.3c-.043.011-.097.021-.14.032-.054.022-.107.043-.151.076a.702.702 0 0 0-.193.958l.043.075zM8.222 1.07c.366.614.678 1.249.925 1.917-.495.086-.98.215-1.453.388a3.918 3.918 0 0 1 .528-2.305zM4.658 5.463a7.467 7.467 0 0 0-.893 1.216 11.68 11.68 0 0 1-1.453-1.55 3.825 3.825 0 0 1 2.346.334zm13.048-.302a7.673 7.673 0 0 0-2.347-.474 7.583 7.583 0 0 0-3.811.818l-.215.108v3.918c0 .054 0 .258-.032.431a1.535 1.535 0 0 1-.646.98 1.545 1.545 0 0 1-1.152.247 2.618 2.618 0 0 1-.409-.118 747.6 747.6 0 0 1-3.402-1.313 8.9 8.9 0 0 0-.323-.129 30.597 30.597 0 0 0-3.822 3.832l-.075.086a.698.698 0 0 0 .538 1.098.676.676 0 0 0 .42-.118c.011-.011.022-.022.043-.032 1.313-.947 2.756-1.712 4.284-2.325a.7.7 0 0 1 .818.13.704.704 0 0 1 .054.915l-.237.388a20.277 20.277 0 0 0-1.97 4.306l-.032.129a.646.646 0 0 0 .108.538.713.713 0 0 0 .549.301.657.657 0 0 0 .42-.118c.054-.043.108-.086.151-.14l.043-.065a18.95 18.95 0 0 1 1.765-2.153 20.156 20.156 0 0 1 10.797-6.018c.032-.011.065-.011.097-.011.237.011.42.215.409.452a.424.424 0 0 1-.344.398c-3.908.829-10.948 5.469-8.483 12.208.043.108.075.172.129.269a.71.71 0 0 0 .538.301.742.742 0 0 0 .657-.398c.398-.754 1.152-1.593 3.326-2.497 6.061-2.508 7.062-6.093 7.17-8.364v-.129a7.716 7.716 0 0 0-5.016-7.451zm-6.083 17.762c-.56-1.669-.506-3.283.151-4.823 1.26 2.035 3.456 2.207 3.456 2.207-2.25.937-3.133 1.863-3.607 2.616z").toNodes(), fill = SolidColor(Color.White))
    }.build()
}
