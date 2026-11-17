package dev.mangaflow.reader

import androidx.compose.runtime.staticCompositionLocalOf

/** One retained chapter owner; shared with every layout's exact preparation window. */
internal val LocalReaderCacheRangeOwner = staticCompositionLocalOf<Any?> { null }
