package dev.mangaflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.FontStore
import dev.mangaflow.typography.MangaFontCatalog
import dev.mangaflow.typography.MangaFontScene

private data class MangaFontFamilies(
    val interfaceFamily: FontFamily = FontFamily.SansSerif,
    val workTitleFamily: FontFamily = interfaceFamily,
    val readingFamily: FontFamily = interfaceFamily,
    val numericFamily: FontFamily = interfaceFamily,
)

private val LocalMangaFontFamilies = staticCompositionLocalOf { MangaFontFamilies() }

/** Scene choices are independent; startup and failed/missing downloads retain an offline system face. */
@Composable
internal fun MangaFontProvider(content: @Composable () -> Unit) {
    val context = LocalMangaContext.current
    val store = remember(context) { FontStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    fun selected(scene: MangaFontScene, inherited: FontFamily): FontFamily {
        val id = state.selections[scene] ?: MangaFontCatalog.defaultId(scene)
        return when (id) {
            "system" -> FontFamily.SansSerif
            "inherit" -> inherited
            else -> store.family(id) ?: FontFamily.SansSerif
        }
    }
    val ui = selected(MangaFontScene.INTERFACE, FontFamily.SansSerif)
    val families = MangaFontFamilies(ui,
        selected(MangaFontScene.WORK_TITLE, ui),
        selected(MangaFontScene.READING, ui),
        selected(MangaFontScene.NUMBERS, ui))
    CompositionLocalProvider(LocalMangaFontFamilies provides families, content = content)
}

@Composable
internal fun mangaFontFamily(scene: MangaFontScene): FontFamily = when (scene) {
    MangaFontScene.INTERFACE -> LocalMangaFontFamilies.current.interfaceFamily
    MangaFontScene.WORK_TITLE -> LocalMangaFontFamilies.current.workTitleFamily
    MangaFontScene.READING -> LocalMangaFontFamilies.current.readingFamily
    MangaFontScene.NUMBERS -> LocalMangaFontFamilies.current.numericFamily
}

private val MangaNumericRuns = Regex("[0-9]+(?:[.,:/–-][0-9]+)*(?:[%％])?")

/** Mixed labels retain their UI letters/units; only numeric runs use the numeric scene. */
@Composable
internal fun mangaNumberText(text: String): AnnotatedString {
    val family = mangaFontFamily(MangaFontScene.NUMBERS)
    return remember(text, family) {
        buildAnnotatedString {
            append(text)
            MangaNumericRuns.findAll(text).forEach { match ->
                addStyle(SpanStyle(fontFamily = family, fontFeatureSettings = "tnum"),
                    match.range.first, match.range.last + 1)
            }
        }
    }
}
