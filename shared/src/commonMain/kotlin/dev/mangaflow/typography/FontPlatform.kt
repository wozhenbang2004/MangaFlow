package dev.mangaflow.typography

import androidx.compose.ui.text.font.FontFamily

/** Loads verified on-disk font faces. Call from a background dispatcher. */
internal expect fun loadMangaFontFamily(identity: String, files: List<MangaFontFile>): FontFamily
