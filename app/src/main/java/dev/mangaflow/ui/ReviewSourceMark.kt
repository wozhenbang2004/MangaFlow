package dev.mangaflow.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.R
import dev.mangaflow.data.ReviewProviders

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

/** The measured slot is identical for vectors and wordmarks, including enlarged text. */
internal fun reviewSourceMarkWidth(size: Dp, fontScale: Float): Dp = size * (1.45f * fontScale.coerceAtLeast(1f))

/** Stack layout measures the same honest wordmark that this renderer uses. */
internal fun reviewSourceWordmark(id: String): String? = when (id) {
    "anilist", "myanimelist", "kitsu" -> null
    "copy" -> "拷"
    "bangumi" -> "bgm"
    "mangaupdates" -> "MU"
    "mangadex" -> "MD"
    else -> ReviewProviders.find(id)?.mark ?: id.take(2).uppercase()
}

internal fun reviewSourceLabelScale(mark: String): Float {
    val units = mark.fold(0) { count, character ->
        count + if (character.code >= 0x2E80) 2 else 1
    }.coerceAtLeast(1)
    return minOf(.68f, 2.8f / units)
}

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

/** Local source markers and honest wordmarks share one optically balanced reference box. */
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
    val resource = when (id) {
        "anilist" -> R.drawable.ic_source_anilist
        "myanimelist" -> R.drawable.ic_source_myanimelist
        "kitsu" -> R.drawable.ic_source_kitsu
        else -> null
    }
    Box(modifier.size(width = reviewSourceMarkWidth(size, fontScale), height = size * fontScale)
        .clearAndSetSemantics { }, contentAlignment = alignment) {
        when {
            heat -> Icon(Icons.Outlined.LocalFireDepartment, null, tint = color,
                modifier = Modifier.size(size * (.88f * fontScale)))
            id == "copy" -> CopySourceWordmark(Modifier.requiredSize(size * fontScale))
            resource != null -> {
                // The original AL path fills 75% of its viewport height; MAL fills 42%.
                // Match their visible ink instead of making every 24px viewport equally tall.
                val opticalScale = when (id) { "anilist" -> .76f; "myanimelist" -> 1.14f; else -> .66f }
                Icon(painterResource(resource), null, tint = color,
                    modifier = Modifier.requiredSize(size * (opticalScale * fontScale)))
            }
            else -> {
                val mark = reviewSourceWordmark(id).orEmpty()
                val labelScale = reviewSourceLabelScale(mark)
                Text(mark, color = color, style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                    fontSize = (size.value * labelScale).sp, lineHeight = size.value.sp, letterSpacing = 0.sp),
                    maxLines = 1, softWrap = false)
            }
        }
    }
}
